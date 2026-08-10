package com.rpmmonitor.master.state

import kotlin.math.sqrt

/**
 * Why no stability figure is being offered.
 *
 * Carried as a reason rather than a message: the wording belongs to the UI, and a
 * refusal that a caller can branch on is more useful than one it can only print.
 */
enum class NoStability {
    /** Too few samples in the window for the figure to mean anything. */
    TOO_FEW_SAMPLES,

    /**
     * The engine stopped inside the window. A stall is not roughness, and a mean
     * dragged toward zero would make the normalised figure read as catastrophic
     * instability when the engine is simply not turning.
     */
    STALL_IN_WINDOW,
}

/**
 * Cycle-to-cycle variation between individual revolutions, pooled across the window.
 *
 * This is the fast instability the master cannot compute for itself: combustion
 * roughness, a misfire, a weak cylinder. It exists only when the node speaks protocol
 * version 2 and reports [com.rpmmonitor.master.proto.IntervalStats] per packet.
 */
sealed interface Roughness {

    data class Measured(
        /** Pooled within-interval standard deviation, in rpm. */
        val sigmaRpm: Double,
        /** [sigmaRpm] as a percentage of the mean, so it compares across speeds. */
        val covPercent: Double,
        /** Revolutions behind the figure. Only intervals of two or more contribute. */
        val revolutions: Long,
        /** Intervals that contributed, that is those holding two or more revolutions. */
        val intervals: Int,
        /**
         * Intervals discarded as self-inconsistent, meaning the node's own min/max did
         * not bracket its mean. Increments for that and for nothing else: a healthy
         * node holds this at zero, and a non-zero value means the node's statistics
         * accumulator is at fault, not the engine.
         */
        val excluded: Int,
        /**
         * The σ that 1 µs period quantisation produces on its own at this speed. A
         * reading at or below it is measurement noise and not roughness. It rises with
         * the square of rpm, so it is negligible at idle and worth checking at the top
         * of the range.
         */
        val floorRpm: Double,
    ) : Roughness

    /** No version-2 statistics in the window: a version-1 node, or none heard yet. */
    data object NotReported : Roughness

    /**
     * Version-2 statistics are arriving but too few revolutions have been measured.
     *
     * The normal case at low speed, not a fault. At the firmware's 100 ms cadence an
     * interval holds one or two revolutions below about 1200 rpm, and an interval of
     * one carries no spread at all, so the window has to be long enough to pool a
     * usable number.
     */
    data object TooFewRevolutions : Roughness
}

/** How steadily the engine is running over one window of samples. */
sealed interface Stability {

    data class Measured(
        /**
         * Residual standard deviation about the fitted trend, in rpm.
         *
         * From a version-2 node this is specifically **wander**: the movement of one
         * interval's mean to the next, which is surge, hunting and drift. The faster
         * variation within each interval is [roughness] and is measured separately.
         */
        val sigmaRpm: Double,
        /** [sigmaRpm] as a percentage of the mean. The comparable figure. */
        val covPercent: Double,
        val meanRpm: Double,
        /**
         * Slope of the removed trend, rpm per second. Not part of the stability
         * figure — it is what was subtracted from it, and worth showing so a reading
         * taken during a deliberate change can be recognised as such.
         */
        val slopeRpmPerSecond: Double,
        val samples: Int,
        /** The within-interval half of the picture. See [Roughness]. */
        val roughness: Roughness = Roughness.NotReported,
    ) : Stability

    data class Unavailable(val reason: NoStability) : Stability
}

/**
 * Engine stability from the readings alone.
 *
 * **Why not the standard deviation of the window.** Plain σ counts a deliberate
 * change in speed as instability: open the throttle and it goes up while the engine
 * may be running perfectly. So a least-squares straight line is fitted first and σ is
 * taken about that. A ramp then measures zero, while the 0.5–2 Hz wander of a
 * hunting idle stays in the residual, which is the thing being measured.
 *
 * **Why not the residual about the displayed average.** The exponential average on
 * the graph has an alpha the user drags. A figure that changes when a display control
 * moves is not a measurement. The fit here has no tuning knob.
 *
 * **The two halves.** One reading per packet at 100 ms puts a 5 Hz ceiling on what
 * arrives, so this fit alone measures wander, surge and hunting and nothing faster.
 * Cycle-to-cycle combustion variation is below that ceiling and can only come from the
 * node, which is what protocol version 2 adds. When it is present, [Roughness] carries
 * the within-interval half and the fitted σ is the between-interval half.
 */
object EngineStability {

    /**
     * Below this, σ is dominated by the estimate's own error rather than by the
     * engine. The firmware sends at 100 ms in every build, so twenty samples is 2 s:
     * a 5 s window is comfortable and anything shorter legitimately declines.
     */
    const val MIN_SAMPLES = 20

    /**
     * Degrees of freedom needed before roughness is offered, that is the sum of
     * `revCount - 1` over the contributing intervals.
     *
     * The relative error on a σ estimate is about `1 / sqrt(2 * dof)`, so twenty is
     * a figure good to roughly 16%. At 3000 rpm an interval holds about five
     * revolutions and four intervals suffice. At idle it can take most of the window,
     * which is the honest answer rather than a figure built from three revolutions.
     */
    const val MIN_ROUGHNESS_DOF = 20L

    /** Pulse period resolution in the node's capture. */
    private const val PERIOD_QUANTUM_US = 1.0

    /**
     * Pulses the node sums to make one revolution. Two magnets, unequally spaced, so
     * the firmware adds consecutive pairs and the asymmetry cancels — which also means
     * two independent quantisation errors land in each revolution's period.
     */
    private const val PULSES_PER_REV = 2

    fun of(samples: List<RpmSample>): Stability {
        if (samples.size < MIN_SAMPLES) return Stability.Unavailable(NoStability.TOO_FEW_SAMPLES)
        if (samples.any { it.rpm == 0L }) return Stability.Unavailable(NoStability.STALL_IN_WINDOW)

        val n = samples.size
        // Times are taken relative to the first sample. Elapsed-clock values run to
        // tens of millions of milliseconds, and squaring them raw throws away
        // precision the residuals then need.
        val t0 = samples.first().elapsedMs
        var sumT = 0.0
        var sumX = 0.0
        for (s in samples) {
            sumT += (s.elapsedMs - t0).toDouble()
            sumX += s.rpm.toDouble()
        }
        val meanT = sumT / n
        val meanX = sumX / n

        var stt = 0.0
        var stx = 0.0
        for (s in samples) {
            val dt = (s.elapsedMs - t0).toDouble() - meanT
            stt += dt * dt
            stx += dt * (s.rpm.toDouble() - meanX)
        }

        // Every sample sharing one timestamp leaves no line to fit. Rather than
        // dividing by zero the trend is taken as flat, which reduces this to σ about
        // the mean — the right answer when there is no time axis to detrend against.
        val slopePerMs = if (stt > 0.0) stx / stt else 0.0
        val intercept = meanX - slopePerMs * meanT

        var rss = 0.0
        for (s in samples) {
            val dt = (s.elapsedMs - t0).toDouble()
            val residual = s.rpm.toDouble() - (intercept + slopePerMs * dt)
            rss += residual * residual
        }

        // n - 2: two parameters were fitted. With n at least MIN_SAMPLES this cannot
        // divide by zero.
        val sigma = sqrt(rss / (n - 2))

        return Stability.Measured(
            sigmaRpm = sigma,
            // meanX cannot be zero here: a zero reading would have been refused above
            // and every remaining sample is at least 1.
            covPercent = sigma / meanX * 100.0,
            meanRpm = meanX,
            slopeRpmPerSecond = slopePerMs * 1000.0,
            samples = n,
            roughness = roughnessOf(samples, meanX),
        )
    }

    /**
     * Pool the per-interval standard deviations into one within-interval figure.
     *
     * The node sends a **sample** standard deviation, divisor `n - 1`, so the sum of
     * squares each interval contributes is `(n - 1) * sd^2` and not `n * sd^2`. The
     * distinction is nearly free at a hundred revolutions an interval and is not free
     * at five, which is what 3000 rpm on a 100 ms cadence actually gives: weighting by
     * `n` there would overstate the variance by a quarter.
     *
     *     pooled variance = SUM( (n[i] - 1) * sd[i]^2 ) / SUM( n[i] - 1 )
     *
     * Intervals of one revolution carry no spread and drop out on their own, since
     * their weight is exactly zero. Intervals of none do the same.
     */
    private fun roughnessOf(samples: List<RpmSample>, meanRpm: Double): Roughness {
        var dof = 0L
        var sumSquares = 0.0
        var revolutions = 0L
        var intervals = 0
        var excluded = 0
        var reported = false

        for (s in samples) {
            val iv = s.interval ?: continue
            reported = true
            if (!iv.consistentWith(s.rpm)) {
                // The node's own figures contradict each other. Dropping the interval
                // costs this one contribution and leaves the reading untouched.
                excluded++
                continue
            }
            val sd = iv.sdRpm ?: continue   // fewer than two revolutions: nothing to pool
            val weight = (iv.revCount - 1).toLong()
            dof += weight
            sumSquares += weight * sd * sd
            revolutions += iv.revCount
            intervals++
        }

        if (!reported) return Roughness.NotReported
        // Guards the division below on every path: dof is a sum of non-negative
        // weights, so reaching the floor also puts it above zero.
        if (dof < MIN_ROUGHNESS_DOF) return Roughness.TooFewRevolutions

        val sigma = sqrt(sumSquares / dof)
        return Roughness.Measured(
            sigmaRpm = sigma,
            // meanRpm is non-zero: the stall refusal upstream guarantees it.
            covPercent = sigma / meanRpm * 100.0,
            revolutions = revolutions,
            intervals = intervals,
            excluded = excluded,
            floorRpm = quantisationFloorRpm(meanRpm),
        )
    }

    /**
     * The σ that period quantisation alone produces at [meanRpm].
     *
     * A revolution's rpm is `60e6 / T`, so an error in T of one quantum shows up in
     * rpm scaled by `rpm^2 / 60e6`. A uniform quantum has σ of `q / sqrt(12)`, and a
     * revolution is the sum of [PULSES_PER_REV] independently quantised pulse periods,
     * so their variances add. At 7500 rpm this is about 0.4 rpm and at idle it is
     * nothing, which is why it is worth reporting rather than assuming.
     */
    private fun quantisationFloorRpm(meanRpm: Double): Double =
        meanRpm * meanRpm / 60_000_000.0 * PERIOD_QUANTUM_US * sqrt(PULSES_PER_REV / 12.0)
}
