package com.standby.opticalscanner.decoder

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

internal data class SymbolBasisSolution(
    val levels: DoubleArray,
    val rank: Int,
    val singularValues: DoubleArray,
    val diagPinvBtB: DoubleArray
)

/**
 * Numerically stable least-squares and pseudoinverse diagonal solver for N x 16
 * exposure basis matrices using thin Householder QR + one-sided Jacobi SVD.
 */
internal object LinearAlgebra {
    private const val MACHINE_EPS = 2.220446049250313e-16
    private const val PINV_RCOND = 1e-15

    fun solveSymbolBasis(basis: Array<DoubleArray>, observed: DoubleArray): SymbolBasisSolution {
        val m = basis.size
        val n = basis[0].size
        require(m >= n) { "Basis must have at least $n rows, got $m." }

        // Copy basis into working matrix for Householder QR: B = Q * R
        val a = Array(m) { row -> basis[row].copyOf() }
        val rhs = observed.copyOf()

        for (k in 0 until n) {
            var normSq = 0.0
            for (i in k until m) {
                val v = a[i][k]
                normSq += v * v
            }
            val norm = sqrt(normSq)
            if (norm == 0.0) continue

            val sign = if (a[k][k] >= 0.0) 1.0 else -1.0
            val u0 = a[k][k] + sign * norm
            val v = DoubleArray(m - k)
            v[0] = 1.0
            var vNormSq = 1.0
            for (i in k + 1 until m) {
                val vi = a[i][k] / u0
                v[i - k] = vi
                vNormSq += vi * vi
            }
            val beta = 2.0 / vNormSq

            // Apply reflector to remaining columns of A
            for (j in k until n) {
                var dot = 0.0
                for (i in k until m) {
                    dot += v[i - k] * a[i][j]
                }
                val scale = beta * dot
                for (i in k until m) {
                    a[i][j] -= scale * v[i - k]
                }
            }

            // Apply reflector to rhs (computes Q^T * observed)
            var dotRhs = 0.0
            for (i in k until m) {
                dotRhs += v[i - k] * rhs[i]
            }
            val scaleRhs = beta * dotRhs
            for (i in k until m) {
                rhs[i] -= scaleRhs * v[i - k]
            }
        }

        // Extract upper-triangular n x n matrix R and top n entries of Q^T * observed
        val rMat = Array(n) { row ->
            DoubleArray(n) { col -> if (col >= row) a[row][col] else 0.0 }
        }
        val qtY = DoubleArray(n) { i -> rhs[i] }

        // One-sided Jacobi SVD of R = U_r * Sigma * V^T
        val vMat = Array(n) { row -> DoubleArray(n) { col -> if (row == col) 1.0 else 0.0 } }
        val maxSweeps = 60
        for (sweep in 0 until maxSweeps) {
            var rotated = false
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    var alpha = 0.0
                    var beta = 0.0
                    var gamma = 0.0
                    for (i in 0 until n) {
                        val ap = rMat[i][p]
                        val aq = rMat[i][q]
                        alpha += ap * ap
                        beta += aq * aq
                        gamma += ap * aq
                    }
                    val scale = sqrt(alpha * beta)
                    if (scale == 0.0 || abs(gamma) <= 1e-15 * scale) continue

                    rotated = true
                    val zeta = (beta - alpha) / (2.0 * gamma)
                    val t = if (zeta >= 0.0) {
                        1.0 / (zeta + hypot(1.0, zeta))
                    } else {
                        -1.0 / (-zeta + hypot(1.0, zeta))
                    }
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = c * t

                    for (i in 0 until n) {
                        val ap = rMat[i][p]
                        val aq = rMat[i][q]
                        rMat[i][p] = c * ap - s * aq
                        rMat[i][q] = s * ap + c * aq

                        val vp = vMat[i][p]
                        val vq = vMat[i][q]
                        vMat[i][p] = c * vp - s * vq
                        vMat[i][q] = s * vp + c * vq
                    }
                }
            }
            if (!rotated) break
        }

        // Column norms are singular values
        val rawSigma = DoubleArray(n) { j ->
            var sumSq = 0.0
            for (i in 0 until n) sumSq += rMat[i][j] * rMat[i][j]
            sqrt(sumSq)
        }
        val order = (0 until n).sortedByDescending { rawSigma[it] }.toIntArray()

        val singularValues = DoubleArray(n) { idx -> rawSigma[order[idx]] }
        val sigmaMax = singularValues[0]

        // NumPy lstsq(rcond=None) uses max(M, N) * eps * sigma_max
        val lstsqCutoff = max(m, n) * MACHINE_EPS * sigmaMax
        var rank = 0
        for (sv in singularValues) {
            if (sv > lstsqCutoff) rank++
        }

        // Compute levels = V * Sigma^+ * U_r^T * (Q^T * y)
        val w = DoubleArray(n)
        for (j in 0 until n) {
            val sv = singularValues[j]
            if (sv > lstsqCutoff) {
                val origCol = order[j]
                var uDotY = 0.0
                for (i in 0 until n) {
                    uDotY += (rMat[i][origCol] / sv) * qtY[i]
                }
                w[j] = uDotY / sv
            }
        }

        val levels = DoubleArray(n)
        for (row in 0 until n) {
            var sum = 0.0
            for (j in 0 until n) {
                sum += vMat[row][order[j]] * w[j]
            }
            levels[row] = sum
        }

        // Diagonal of pinv(B^T * B) = V * (Sigma^2)^+ * V^T
        val pinvCutoffSq = PINV_RCOND * sigmaMax * sigmaMax
        val diagPinv = DoubleArray(n)
        for (row in 0 until n) {
            var sum = 0.0
            for (j in 0 until n) {
                val svSq = singularValues[j] * singularValues[j]
                if (svSq > pinvCutoffSq) {
                    val vij = vMat[row][order[j]]
                    sum += (vij * vij) / svSq
                }
            }
            diagPinv[row] = max(0.0, sum)
        }

        return SymbolBasisSolution(
            levels = levels,
            rank = rank,
            singularValues = singularValues,
            diagPinvBtB = diagPinv
        )
    }
}
