package com.example.gudumap.navigation

import kotlin.math.abs
import kotlin.math.max

/**
 * Six-state navigation filter.
 *
 * State:
 * [0] North position, metres
 * [1] East position, metres
 * [2] Down position, metres
 * [3] North velocity, metres/second
 * [4] East velocity, metres/second
 * [5] Down velocity, metres/second
 *
 * predict() preserves the existing general displacement API.
 * predictPedestrianStep() applies independently estimated walking
 * displacement without extrapolating previous velocity.
 *
 * Callers must serialize access to this filter, including direct
 * access to state and P.
 */
class EKF(
    var processNoisePos: Double = 0.05,
    var processNoiseVel: Double = 0.20,
    var gnssPosNoise: Double = 3.0,
    var gnssVelNoise: Double = 0.3,
    var zuptVelNoise: Double = 0.02
) {

    companion object {
        const val STATE_DIM = 6
    }

    val state = DoubleArray(STATE_DIM)

    val P = Array(STATE_DIM) {
        DoubleArray(STATE_DIM)
    }

    var isInitialized: Boolean = false
        private set

    init {
        reset()
    }

    fun reset() {
        for (i in 0 until STATE_DIM) {
            state[i] = 0.0
            P[i].fill(0.0)
        }

        P[0][0] = 10.0
        P[1][1] = 10.0
        P[2][2] = 10.0

        P[3][3] = 1.0
        P[4][4] = 1.0
        P[5][5] = 1.0

        isInitialized = false
    }

    fun initialize(
        pNorth: Double,
        pEast: Double,
        pDown: Double,
        vNorth: Double = 0.0,
        vEast: Double = 0.0,
        vDown: Double = 0.0
    ) {
        require(
            listOf(
                pNorth,
                pEast,
                pDown,
                vNorth,
                vEast,
                vDown
            ).all { it.isFinite() }
        ) {
            "Initial position and velocity must be finite."
        }

        require(
            gnssPosNoise.isFinite() && gnssPosNoise > 0.0 &&
                    gnssVelNoise.isFinite() && gnssVelNoise > 0.0
        ) {
            "Initial measurement noise must be finite and positive."
        }

        state[0] = pNorth
        state[1] = pEast
        state[2] = pDown
        state[3] = vNorth
        state[4] = vEast
        state[5] = vDown

        for (row in P) {
            row.fill(0.0)
        }

        val positionVariance = gnssPosNoise * gnssPosNoise
        val velocityVariance = gnssVelNoise * gnssVelNoise

        for (axis in 0..2) {
            P[axis][axis] = positionVariance
            P[axis + 3][axis + 3] = velocityVariance
        }

        isInitialized = true
    }

    /**
     * Existing general displacement prediction.
     *
     * Preserved for compatibility with the vehicle, ML, and legacy
     * callers. Pedestrian step events should use
     * predictPedestrianStep() instead.
     *
     * This legacy covariance model assumes constant velocity,
     * whereas its state update uses supplied displacement.
     * It therefore remains an approximation.
     */
    fun predict(
        deltaPNed: DoubleArray,
        dt: Double
    ) {
        if (!isInitialized) return
        if (deltaPNed.size != 3) return
        if (!deltaPNed.all { it.isFinite() }) return
        if (!dt.isFinite() || dt <= 0.0) return

        val dtSafe = max(dt, 0.001)

        for (axis in 0..2) {
            state[axis] += deltaPNed[axis]
            state[axis + 3] = deltaPNed[axis] / dtSafe
        }

        val transition = Array(STATE_DIM) { i ->
            DoubleArray(STATE_DIM) { j ->
                when {
                    i == j -> 1.0
                    j == i + 3 -> dtSafe
                    else -> 0.0
                }
            }
        }

        val propagated = matrixMultiply(
            matrixMultiply(transition, P),
            transpose(transition)
        )

        val positionNoise =
            processNoisePos * processNoisePos * dtSafe

        val velocityNoise =
            processNoiseVel * processNoiseVel * dtSafe

        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                P[i][j] = propagated[i][j]
            }

            P[i][i] += if (i < 3) {
                positionNoise
            } else {
                velocityNoise
            }
        }
    }

    /**
     * Applies one accepted pedestrian step exactly once.
     *
     * Model:
     * positionNext = positionPrevious + stepDisplacement
     * velocityNext = stepDisplacement / stepInterval
     *
     * Previous velocity is replaced, not integrated.
     *
     * displacementStdMeters represents an approximate independent
     * horizontal displacement uncertainty for this step. It must
     * be calibrated; it does not fully model correlated heading
     * errors or long-term drift.
     *
     * Do not also apply raw window displacement for the same steps.
     */
    fun predictPedestrianStep(
        northMeters: Double,
        eastMeters: Double,
        dtSeconds: Double,
        displacementStdMeters: Double
    ) {
        if (!isInitialized) return

        if (!northMeters.isFinite() ||
            !eastMeters.isFinite()
        ) {
            return
        }

        if (!dtSeconds.isFinite() || dtSeconds <= 0.0) {
            return
        }

        if (!displacementStdMeters.isFinite() ||
            displacementStdMeters <= 0.0
        ) {
            return
        }

        val velocityNorth = northMeters / dtSeconds
        val velocityEast = eastMeters / dtSeconds

        val variance =
            displacementStdMeters * displacementStdMeters

        val velocityVariance =
            variance / (dtSeconds * dtSeconds)

        val positionVelocityCovariance =
            variance / dtSeconds

        if (!velocityNorth.isFinite() ||
            !velocityEast.isFinite() ||
            !variance.isFinite() ||
            !velocityVariance.isFinite() ||
            !positionVelocityCovariance.isFinite()
        ) {
            return
        }

        state[0] += northMeters
        state[1] += eastMeters

        state[3] = velocityNorth
        state[4] = velocityEast
        state[5] = 0.0

        /*
         * Retain position covariance.
         *
         * The new velocity depends on this step rather than the
         * previous velocity, so remove previous velocity covariance
         * and its cross-correlations.
         */
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                if (i >= 3 || j >= 3) {
                    P[i][j] = 0.0
                }
            }
        }

        /*
         * The same uncertain displacement contributes to both
         * position and velocity, creating their cross-covariance.
         */
        for (axis in 0..1) {
            val velocityAxis = axis + 3

            P[axis][axis] += variance

            P[velocityAxis][velocityAxis] =
                velocityVariance

            P[axis][velocityAxis] =
                positionVelocityCovariance

            P[velocityAxis][axis] =
                positionVelocityCovariance
        }
    }

    /**
     * Generic measurement update with optional Mahalanobis gating.
     *
     * Returns false when rejected, invalid, or uninitialized.
     * A threshold of zero disables outlier gating.
     */
    fun updateMeasurement(
        z: DoubleArray,
        H: Array<DoubleArray>,
        R: Array<DoubleArray>,
        mahalanobisThreshold: Double = 0.0
    ): Boolean {
        if (!isInitialized) return false

        val measurementSize = z.size

        require(measurementSize in 1..3) {
            "Supported measurement size is 1, 2, or 3."
        }

        require(
            H.size == measurementSize &&
                    H.all { it.size == STATE_DIM }
        ) {
            "Invalid measurement matrix dimensions."
        }

        require(
            R.size == measurementSize &&
                    R.all { it.size == measurementSize }
        ) {
            "Invalid measurement noise dimensions."
        }

        if (!z.all { it.isFinite() } ||
            !H.all { row -> row.all { it.isFinite() } } ||
            !R.all { row -> row.all { it.isFinite() } }
        ) {
            return false
        }

        if (!mahalanobisThreshold.isFinite() ||
            mahalanobisThreshold < 0.0
        ) {
            return false
        }

        val predictedMeasurement =
            DoubleArray(measurementSize)

        for (i in 0 until measurementSize) {
            var sum = 0.0

            for (j in 0 until STATE_DIM) {
                sum += H[i][j] * state[j]
            }

            predictedMeasurement[i] = sum
        }

        val residual = DoubleArray(measurementSize) { i ->
            z[i] - predictedMeasurement[i]
        }

        val hTranspose = transpose(H)

        val hPhTranspose = matrixMultiply(
            matrixMultiply(H, P),
            hTranspose
        )

        val innovationCovariance =
            Array(measurementSize) { i ->
                DoubleArray(measurementSize) { j ->
                    hPhTranspose[i][j] + R[i][j]
                }
            }

        val inverseInnovation =
            invertMatrix(innovationCovariance) ?: return false

        if (mahalanobisThreshold > 0.0) {
            var distanceSquared = 0.0

            for (i in 0 until measurementSize) {
                for (j in 0 until measurementSize) {
                    distanceSquared +=
                        residual[i] *
                                inverseInnovation[i][j] *
                                residual[j]
                }
            }

            if (!distanceSquared.isFinite() ||
                distanceSquared > mahalanobisThreshold
            ) {
                return false
            }
        }

        val gain = matrixMultiply(
            matrixMultiply(P, hTranspose),
            inverseInnovation
        )

        val updatedState = state.copyOf()

        for (i in 0 until STATE_DIM) {
            var correction = 0.0

            for (j in 0 until measurementSize) {
                correction += gain[i][j] * residual[j]
            }

            updatedState[i] += correction
        }

        if (!updatedState.all { it.isFinite() }) {
            return false
        }

        // Joseph-form covariance update.
        val identityMinusGainH = Array(STATE_DIM) { i ->
            DoubleArray(STATE_DIM) { j ->
                var gainH = 0.0

                for (k in 0 until measurementSize) {
                    gainH += gain[i][k] * H[k][j]
                }

                (if (i == j) 1.0 else 0.0) - gainH
            }
        }

        val covariancePartOne = matrixMultiply(
            matrixMultiply(identityMinusGainH, P),
            transpose(identityMinusGainH)
        )

        val covariancePartTwo = matrixMultiply(
            matrixMultiply(gain, R),
            transpose(gain)
        )

        val updatedCovariance = Array(STATE_DIM) { i ->
            DoubleArray(STATE_DIM) { j ->
                covariancePartOne[i][j] +
                        covariancePartTwo[i][j]
            }
        }

        if (!updatedCovariance.all { row ->
                row.all { it.isFinite() }
            }
        ) {
            return false
        }

        for (i in 0 until STATE_DIM) {
            state[i] = updatedState[i]

            for (j in 0 until STATE_DIM) {
                P[i][j] = updatedCovariance[i][j]
            }
        }

        return true
    }

    fun updateGnssPosition(
        pNorth: Double,
        pEast: Double,
        pDown: Double,
        noiseMeters: Double = gnssPosNoise,
        mahalanobisThreshold: Double = 0.0
    ): Boolean {
        if (!noiseMeters.isFinite() || noiseMeters <= 0.0) {
            return false
        }

        val measurement = doubleArrayOf(
            pNorth,
            pEast,
            pDown
        )

        val measurementMatrix = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        )

        val variance = noiseMeters * noiseMeters

        val measurementNoise = arrayOf(
            doubleArrayOf(variance, 0.0, 0.0),
            doubleArrayOf(0.0, variance, 0.0),
            doubleArrayOf(0.0, 0.0, variance)
        )

        return updateMeasurement(
            z = measurement,
            H = measurementMatrix,
            R = measurementNoise,
            mahalanobisThreshold = mahalanobisThreshold
        )
    }

    fun updateGnssVelocity(
        vNorth: Double,
        vEast: Double,
        vDown: Double,
        noiseMps: Double = gnssVelNoise,
        mahalanobisThreshold: Double = 0.0
    ): Boolean {
        if (!noiseMps.isFinite() || noiseMps <= 0.0) {
            return false
        }

        val measurement = doubleArrayOf(
            vNorth,
            vEast,
            vDown
        )

        val measurementMatrix = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        )

        val variance = noiseMps * noiseMps

        val measurementNoise = arrayOf(
            doubleArrayOf(variance, 0.0, 0.0),
            doubleArrayOf(0.0, variance, 0.0),
            doubleArrayOf(0.0, 0.0, variance)
        )

        return updateMeasurement(
            z = measurement,
            H = measurementMatrix,
            R = measurementNoise,
            mahalanobisThreshold = mahalanobisThreshold
        )
    }

    /**
     * Zero-velocity pseudo-measurement.
     *
     * Depending on covariance cross-correlations, this can also
     * correct position. Do not apply indiscriminately between
     * pedestrian steps.
     */
    fun updateZupt(
        noiseMps: Double = zuptVelNoise
    ) {
        if (!noiseMps.isFinite() || noiseMps <= 0.0) {
            return
        }

        val measurement = doubleArrayOf(
            0.0,
            0.0,
            0.0
        )

        val measurementMatrix = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        )

        val variance = noiseMps * noiseMps

        val measurementNoise = arrayOf(
            doubleArrayOf(variance, 0.0, 0.0),
            doubleArrayOf(0.0, variance, 0.0),
            doubleArrayOf(0.0, 0.0, variance)
        )

        updateMeasurement(
            z = measurement,
            H = measurementMatrix,
            R = measurementNoise
        )
    }

    /**
     * Calculates 1-sigma horizontal position standard deviation (uncertainty radius in meters)
     * derived mathematically from the diagonal elements of covariance matrix P:
     * sigma_horizontal = sqrt(P_north_north + P_east_east)
     */
    fun getHorizontalUncertainty(): Double {
        val variance = P[0][0] + P[1][1]
        return if (variance > 0.0 && variance.isFinite()) {
            kotlin.math.sqrt(variance)
        } else {
            0.0
        }
    }


    private fun matrixMultiply(
        A: Array<DoubleArray>,
        B: Array<DoubleArray>
    ): Array<DoubleArray> {
        val rowsA = A.size
        val columnsA = A[0].size
        val columnsB = B[0].size

        val result = Array(rowsA) {
            DoubleArray(columnsB)
        }

        for (i in 0 until rowsA) {
            for (j in 0 until columnsB) {
                var sum = 0.0

                for (k in 0 until columnsA) {
                    sum += A[i][k] * B[k][j]
                }

                result[i][j] = sum
            }
        }

        return result
    }

    private fun transpose(
        matrix: Array<DoubleArray>
    ): Array<DoubleArray> {
        val rows = matrix.size
        val columns = matrix[0].size

        return Array(columns) { column ->
            DoubleArray(rows) { row ->
                matrix[row][column]
            }
        }
    }

    /**
     * Small matrix inversion using partial-pivot Gaussian elimination.
     * Supports the 1–3 dimensional measurement matrices used here.
     */
    private fun invertMatrix(
        matrix: Array<DoubleArray>
    ): Array<DoubleArray>? {
        val size = matrix.size

        if (size !in 1..3) return null
        if (matrix.any { it.size != size }) return null
        if (matrix.any { row ->
                row.any { !it.isFinite() }
            }
        ) {
            return null
        }

        val augmented = Array(size) { row ->
            DoubleArray(size * 2) { column ->
                when {
                    column < size -> matrix[row][column]
                    column - size == row -> 1.0
                    else -> 0.0
                }
            }
        }

        for (column in 0 until size) {
            var pivotRow = column
            var largestPivot = abs(augmented[column][column])

            for (row in column + 1 until size) {
                val candidate = abs(augmented[row][column])

                if (candidate > largestPivot) {
                    largestPivot = candidate
                    pivotRow = row
                }
            }

            if (!largestPivot.isFinite() || largestPivot == 0.0) {
                return null
            }

            if (pivotRow != column) {
                val temporary = augmented[column]
                augmented[column] = augmented[pivotRow]
                augmented[pivotRow] = temporary
            }

            val pivot = augmented[column][column]

            for (j in 0 until size * 2) {
                augmented[column][j] /= pivot
            }

            for (row in 0 until size) {
                if (row == column) continue

                val factor = augmented[row][column]

                for (j in 0 until size * 2) {
                    augmented[row][j] -=
                        factor * augmented[column][j]
                }
            }
        }

        val inverse = Array(size) { row ->
            DoubleArray(size) { column ->
                augmented[row][column + size]
            }
        }

        return if (inverse.all { row ->
                row.all { it.isFinite() }
            }
        ) {
            inverse
        } else {
            null
        }
    }
}