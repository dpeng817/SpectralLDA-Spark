package megadata.spectralLDA.datamoments

/**
 * Data Cumulants Calculation.
 * Created by Furong Huang on 11/2/15.
 */

import megadata.spectralLDA.utils.Tensors
import breeze.linalg._
import breeze.numerics._
import breeze.stats.distributions.{Rand, RandBasis}
import megadata.spectralLDA.algorithm.RandNLA
import megadata.spectralLDA.utils.Tensors
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.log4j.Logger


/** Data cumulant
  *
  * @param thirdOrderMoments Scaled whitened M3, precisely,
  *                            <math>\frac{\alpha_0(\alpha_0+1)(\alpha_0+2)}{2} M3(W^T,W^T,W^T)</math>
  * @param eigenVectorsM2   V-by-k top eigenvectors of shifted M2, stored column-wise
  * @param eigenValuesM2    length-k top eigenvalues of shifted M2
  * @param firstOrderMoments  average of term count frequencies M1
  *
  * REFERENCES
  * [Wang2015] Wang Y et al, Fast and Guaranteed Tensor Decomposition via Sketching, 2015,
  *            http://arxiv.org/abs/1506.04448
  *
  */
case class DataCumulant(thirdOrderMoments: DenseMatrix[Double],
                        eigenVectorsM2: DenseMatrix[Double],
                        eigenValuesM2: DenseVector[Double],
                        firstOrderMoments: DenseVector[Double])
  extends Serializable


object DataCumulant {
  @transient private lazy val logger = Logger.getLogger("DataCumulant")

  def getDataCumulant(dimK: Int,
                      alpha0: Double,
                      documents: RDD[(Long, SparseVector[Double])],
                      randomisedSVD: Boolean = true,
                      numIterationsKrylovMethod: Int = 1)
                     (implicit randBasis: RandBasis = Rand)
        : DataCumulant = {
    assert(dimK > 0, "The number of topics dimK must be positive.")
    assert(alpha0 > 0, "The topic concentration alpha0 must be positive.")

    val sc: SparkContext = documents.sparkContext

    val validDocuments = documents
      .map {
        case (id, wc) => (id, sum(wc), wc)
      }
      .filter {
        case (_, len, _) => len >= 3
      }
    validDocuments.cache()

    val dimVocab = validDocuments.map(_._3.length).take(1)(0)
    val numDocs = validDocuments.count()

    logger.info("Start calculating first order moments...")
    val (m1Index: Array[Int], m1Value: Array[Double]) = validDocuments
      .flatMap {
        case (_, length, vec) =>
          val termDistribution: SparseVector[Double] = vec / length.toDouble
          termDistribution.activeIterator.toSeq
      }
      .reduceByKey(_ + _)
      .mapValues(_ / numDocs.toDouble)
      .collect
      .sorted
      .unzip
    val firstOrderMoments = new SparseVector[Double](m1Index, m1Value, dimVocab).toDenseVector

    // Zero out the terms with low IDF
    logger.info("Finished calculating first order moments.")

    logger.info("Start calculating second order moments...")
    val (eigenVectors: DenseMatrix[Double], eigenValues: DenseVector[Double]) =
      if (randomisedSVD) {
        RandNLA.whiten2(
          alpha0,
          dimVocab,
          dimK,
          numDocs,
          firstOrderMoments,
          validDocuments,
          nIter = numIterationsKrylovMethod
        )
      }
      else {
        val E_x1_x2: DenseMatrix[Double] = validDocuments
          .map { case (_, len, vec) =>
            (Tensors.spVectorTensorProd2d(vec) - diag(vec)) / (len * (len - 1))
          }
          .reduce(_ + _)
          .map(_ / numDocs.toDouble).toDenseMatrix
        val M2: DenseMatrix[Double] = E_x1_x2 - alpha0 / (alpha0 + 1) * (firstOrderMoments * firstOrderMoments.t)

        val eigSym.EigSym(sigma, u) = eigSym(alpha0 * (alpha0 + 1) * M2)
        val i = argsort(sigma)
        (u(::, i.slice(dimVocab - dimK, dimVocab)).copy, sigma(i.slice(dimVocab - dimK, dimVocab)).copy)
      }
    logger.info("Finished calculating second order moments and whitening matrix.")

    logger.info("Start whitening data with dimensionality reduction...")
    val W: DenseMatrix[Double] = eigenVectors * diag(eigenValues map { x => 1 / (sqrt(x) + 1e-9) })
    logger.info("Finished whitening data.")

    // We computing separately the first order, second order, 3rd order terms in Eq (25) (26)
    // in [Wang2015]. For the 2nd order, 3rd order terms, We'd achieve maximum performance with
    // reduceByKey() of w_i, 1\le i\le V, the rows of the whitening matrix W.
    logger.info("Start calculating third order moments...")
    val firstOrderMoments_whitened = W.t * firstOrderMoments
    val broadcasted_W = sc.broadcast[DenseMatrix[Double]](W)

    // First order terms: p^{\otimes 3}, p\otimes p\otimes q, p\otimes q\otimes p,
    // q\otimes p\otimes p
    val m3FirstOrderPart = validDocuments
      .map {
        case (_, len, vec) => whitenedM3FirstOrderTerms(
          alpha0,
          broadcasted_W.value,
          firstOrderMoments_whitened,
          vec, len
        )
      }
      .reduce(_ + _)
      .map(_ / numDocs.toDouble)

    // Second order terms: all terms as w_i\otimes w_i\otimes p, w_i\otimes w_i\otimes q,
    // 1\le i\le V and their permutations
    val m3SecondOrderPart = validDocuments
      .flatMap {
        case (_, len, vec) => whitenedM3SecondOrderTerms(
          alpha0,
          broadcasted_W.value,
          firstOrderMoments_whitened,
          vec, len
        )
      }
      .reduceByKey(_ + _)
      .map {
        case (i: Int, x: DenseVector[Double]) => computeSecondOrderTerms(
          i, x,
          broadcasted_W.value
        )
      }
      .reduce(_ + _)
      .map(_ / numDocs.toDouble)

    // Third order terms: all terms w_i^{\otimes 3}, 1\le i\le V
    val m3ThirdOrderPart = validDocuments
      .flatMap {
        case (_, len, vec) => whitenedM3ThirdOrderTerms(
          alpha0,
          vec, len
        )
      }
      .reduceByKey(_ + _)
      .map {
        case (i: Int, p: Double) => computeThirdOrderTerms(
          i, p,
          broadcasted_W.value
        )
      }
      .reduce(_ + _)
      .map(_ / numDocs.toDouble)

    // sketch of q=W^T M1
    val q_otimes_3 = 2 * alpha0 * alpha0 / ((alpha0 + 1) * (alpha0 + 2)) * Tensors.makeRankOneTensor3d(
      firstOrderMoments_whitened, firstOrderMoments_whitened, firstOrderMoments_whitened
    )

    broadcasted_W.unpersist()

    val whitenedM3 = m3FirstOrderPart + m3SecondOrderPart + m3ThirdOrderPart + q_otimes_3
    logger.info("Finished calculating third order moments.")

    new DataCumulant(
      whitenedM3 * alpha0 * (alpha0 + 1) * (alpha0 + 2) / 2.0,
      eigenVectors,
      eigenValues,
      firstOrderMoments
    )
  }

  private def whitenedM3FirstOrderTerms(alpha0: Double,
                                        W: DenseMatrix[Double],
                                        q: DenseVector[Double],
                                        n: SparseVector[Double],
                                        len: Double)
  : DenseMatrix[Double] = {
    // $p=W^T n$, where n is the original word count vector
    val p: DenseVector[Double] = W.t * n

    val coeff1 = 1.0 / (len * (len - 1) * (len - 2))
    val coeff2 = 1.0 / (len * (len - 1))
    val h1 = alpha0 / (alpha0 + 2)

    val s1 = Tensors.makeRankOneTensor3d(p, p, p)
    val s2 = Tensors.makeRankOneTensor3d(p, p, q)
    val s3 = Tensors.makeRankOneTensor3d(p, q, p)
    val s4 = Tensors.makeRankOneTensor3d(q, p, p)

    coeff1 * s1 - coeff2 * h1 * (s2 + s3 + s4)
  }

  private def whitenedM3SecondOrderTerms(alpha0: Double,
                                         W: DenseMatrix[Double],
                                         q: DenseVector[Double],
                                         n: SparseVector[Double],
                                         len: Double)
  : Seq[(Int, DenseVector[Double])] = {
    val p: DenseVector[Double] = W.t * n

    val coeff1 = 1.0 / (len * (len - 1) * (len - 2))
    val coeff2 = 1.0 / (len * (len - 1))
    val h1 = alpha0 / (alpha0 + 2)

    var seqTerms = Seq[(Int, DenseVector[Double])]()
    for ((wc_index, wc_value) <- n.activeIterator) {
      seqTerms ++= Seq(
        (wc_index, -coeff1 * wc_value * p),
        (wc_index, coeff2 * h1 * wc_value * q)
      )
    }

    seqTerms
  }

  private def whitenedM3ThirdOrderTerms(alpha0: Double,
                                        n: SparseVector[Double],
                                        len: Double)
  : Seq[(Int, Double)] = {
    val coeff1 = 1.0 / (len * (len - 1) * (len - 2))
    val coeff2 = 1.0 / (len * (len - 1))
    val h1 = alpha0 / (alpha0 + 2)

    var seqTerms = Seq[(Int, Double)]()
    for ((wc_index, wc_value) <- n.activeIterator) {
      seqTerms :+= (wc_index, 2 * coeff1 * wc_value)
    }

    seqTerms
  }

  private def computeSecondOrderTerms(i: Int,
                                      x: DenseVector[Double],
                                      W: DenseMatrix[Double])
      : DenseMatrix[Double] = {
    val w: DenseVector[Double] = W(i, ::).t

    val prod1 = Tensors.makeRankOneTensor3d(w, w, x)
    val prod2 = Tensors.makeRankOneTensor3d(w, x, w)
    val prod3 = Tensors.makeRankOneTensor3d(x, w, w)

    prod1 + prod2 + prod3
  }

  private def computeThirdOrderTerms(i: Int,
                                     p: Double,
                                     W: DenseMatrix[Double])
      : DenseMatrix[Double] = {
    val w: DenseVector[Double] = W(i, ::).t

    val z = Tensors.makeRankOneTensor3d(w, w, w)

    z * p
  }
}
