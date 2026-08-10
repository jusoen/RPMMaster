package com.rpmmonitor.master

import com.rpmmonitor.master.proto.IntervalStats
import com.rpmmonitor.master.state.EngineStability
import com.rpmmonitor.master.state.NoStability
import com.rpmmonitor.master.state.Roughness
import com.rpmmonitor.master.state.RpmSample
import com.rpmmonitor.master.state.Stability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The stability maths, on the JVM. No emulator and no packets — the whole point of
 * keeping the state layer free of Android types.
 */
class StabilityTest {

    private fun samples(
        count: Int,
        intervalMs: Long = 100,
        rpm: (Int) -> Long,
    ): List<RpmSample> = (0 until count).map { RpmSample(1_000L + it * intervalMs, rpm(it)) }

    private fun measured(s: List<RpmSample>): Stability.Measured =
        EngineStability.of(s) as Stability.Measured

    @Test
    fun `a perfectly steady engine measures zero`() {
        val result = measured(samples(100) { 3000 })
        assertEquals(0.0, result.sigmaRpm, 1e-9)
        assertEquals(0.0, result.covPercent, 1e-9)
        assertEquals(3000.0, result.meanRpm, 1e-9)
    }

    @Test
    fun `a clean ramp measures zero, because the trend is removed not the spread`() {
        // The case plain standard deviation gets wrong: opening the throttle is not
        // instability. Undetrended, this window has a sigma of about 290 rpm.
        val result = measured(samples(100) { 2000L + it * 10L })
        assertEquals(0.0, result.sigmaRpm, 1e-6)
        // 10 rpm per 100 ms sample is 100 rpm per second.
        assertEquals(100.0, result.slopeRpmPerSecond, 1e-6)
    }

    @Test
    fun `known noise is recovered`() {
        // Fixed seed: a statistical assertion that is allowed to fail at random is
        // worse than no assertion.
        val rng = Random(20260809)
        val sigma = 50.0
        val s = samples(400) { (3000.0 + rng.nextGaussian(sigma)).roundToLong() }
        val result = measured(s)
        // Sampling error on sigma from 400 points is about sigma/sqrt(2n), ~1.8 rpm.
        assertEquals(sigma, result.sigmaRpm, 6.0)
        assertEquals(sigma / 3000.0 * 100.0, result.covPercent, 0.25)
    }

    @Test
    fun `noise on a ramp still measures the noise`() {
        val rng = Random(4242)
        val s = samples(400) { (2000.0 + it * 5.0 + rng.nextGaussian(40.0)).roundToLong() }
        val result = measured(s)
        assertEquals(40.0, result.sigmaRpm, 6.0)
        assertEquals(50.0, result.slopeRpmPerSecond, 3.0)
    }

    @Test
    fun `irregular arrivals do not distort the trend`() {
        // Gappy delivery, the same underlying ramp. Fitting against sample index
        // rather than elapsed time would bend the line and leave a false residual.
        val gaps = listOf(100L, 100, 700, 100, 100, 100, 400, 100, 100, 250)
        var t = 1_000L
        val s = ArrayList<RpmSample>()
        repeat(40) { i ->
            t += gaps[i % gaps.size]
            s += RpmSample(t, 1500L + (t - 1_000L) / 10L)
        }
        val result = measured(s)
        assertEquals(0.0, result.sigmaRpm, 1e-6)
        assertEquals(100.0, result.slopeRpmPerSecond, 1e-6)
    }

    @Test
    fun `too few samples is declined rather than guessed at`() {
        val s = samples(EngineStability.MIN_SAMPLES - 1) { 3000 }
        val result = EngineStability.of(s) as Stability.Unavailable
        assertEquals(NoStability.TOO_FEW_SAMPLES, result.reason)
    }

    @Test
    fun `the sample floor is inclusive`() {
        assertTrue(EngineStability.of(samples(EngineStability.MIN_SAMPLES) { 3000 })
            is Stability.Measured)
    }

    @Test
    fun `a stall in the window is declined, not averaged in`() {
        // One zero among a hundred readings would otherwise pull the mean down and
        // inflate the residual, reporting a healthy engine as wildly unstable.
        val s = samples(100) { if (it == 50) 0 else 3000 }
        val result = EngineStability.of(s) as Stability.Unavailable
        assertEquals(NoStability.STALL_IN_WINDOW, result.reason)
    }

    @Test
    fun `an empty window is declined`() {
        val result = EngineStability.of(emptyList()) as Stability.Unavailable
        assertEquals(NoStability.TOO_FEW_SAMPLES, result.reason)
    }

    @Test
    fun `identical timestamps fall back to spread about the mean`() {
        // No time axis to fit against. The line must not be divided by zero, and the
        // answer that remains correct is the plain standard deviation.
        val s = (0 until 40).map { RpmSample(5_000L, if (it % 2 == 0) 2900L else 3100L) }
        val result = measured(s)
        assertEquals(0.0, result.slopeRpmPerSecond, 1e-9)
        // Population spread is exactly 100, and n-2 degrees of freedom lifts it a little.
        assertEquals(100.0, result.sigmaRpm, 3.0)
    }

    // --- Roughness, the version-2 within-interval half ------------------------------

    /** One steady window whose packets carry version-2 statistics. */
    private fun v2Samples(
        count: Int = 40,
        rpm: Long = 3000,
        stats: (Int) -> IntervalStats?,
    ): List<RpmSample> = (0 until count).map { RpmSample(1_000L + it * 100L, rpm, stats(it)) }

    private fun interval(revCount: Int, sdRpmX10: Int, rpm: Long = 3000) =
        IntervalStats(revCount, sdRpmX10, rpm - 200, rpm + 200)

    private fun roughness(s: List<RpmSample>): Roughness =
        (EngineStability.of(s) as Stability.Measured).roughness

    @Test
    fun `a version 1 window reports no roughness at all`() {
        assertEquals(Roughness.NotReported, roughness(samples(40) { 3000 }))
    }

    @Test
    fun `pooled roughness recovers a known within-interval sigma`() {
        val r = roughness(v2Samples { interval(revCount = 5, sdRpmX10 = 1000) }) as Roughness.Measured
        assertEquals(100.0, r.sigmaRpm, 1e-9)
        assertEquals(100.0 / 3000.0 * 100.0, r.covPercent, 1e-9)
        assertEquals(200L, r.revolutions)
        assertEquals(40, r.intervals)
        assertEquals(0, r.excluded)
    }

    @Test
    fun `the sample standard deviation is weighted by n minus one, not by n`() {
        // The firmware-author correction. Alternating intervals of 2 revolutions at
        // sigma 100 and 10 revolutions at sigma 10: pooling on n - 1 gives sqrt(1090),
        // pooling on n gives sqrt(1750). The two are far enough apart that a wrong
        // weight cannot pass this by rounding.
        val r = roughness(v2Samples {
            if (it % 2 == 0) interval(revCount = 2, sdRpmX10 = 1000)
            else interval(revCount = 10, sdRpmX10 = 100)
        }) as Roughness.Measured
        assertEquals(kotlin.math.sqrt(1090.0), r.sigmaRpm, 1e-9)
        assertTrue("n-weighted pooling would give about 41.8", r.sigmaRpm < 35.0)
    }

    @Test
    fun `intervals of one revolution carry no spread and are simply absent`() {
        // The dominant case below about 1200 rpm at the 100 ms cadence. A one-sample
        // interval has no standard deviation, so it must contribute neither a value
        // nor a weight — reporting its zero as a measurement would read as a
        // flawlessly smooth engine.
        assertEquals(Roughness.TooFewRevolutions, roughness(v2Samples(rpm = 900) {
            IntervalStats(revCount = 1, sdRpmX10 = 0, minRpm = 900, maxRpm = 900)
        }))
        // Mixed in with real intervals, they change nothing but the count.
        val r = roughness(v2Samples {
            if (it % 2 == 0) interval(revCount = 5, sdRpmX10 = 1000)
            else IntervalStats(revCount = 1, sdRpmX10 = 0, minRpm = 3000, maxRpm = 3000)
        }) as Roughness.Measured
        assertEquals(100.0, r.sigmaRpm, 1e-9)
        assertEquals(20, r.intervals)
        assertEquals(100L, r.revolutions)
    }

    @Test
    fun `an interval with no completed revolution reads as a stall`() {
        // The node must send rpm = 0 alongside rev_count = 0, and a zero reading is a
        // stall by the rule above. Below about 600 rpm a revolution cannot always
        // close inside 100 ms, so empty intervals appear and no stability figure is
        // offered for that window. Deliberate: at that speed the window is describing
        // an engine that is barely turning, not one running roughly.
        val s = v2Samples(count = 40) { i ->
            if (i == 20) IntervalStats(revCount = 0, sdRpmX10 = 0, minRpm = 0, maxRpm = 0)
            else interval(revCount = 5, sdRpmX10 = 1000)
        }.mapIndexed { i, sample -> if (i == 20) sample.copy(rpm = 0) else sample }
        assertEquals(
            NoStability.STALL_IN_WINDOW,
            (EngineStability.of(s) as Stability.Unavailable).reason,
        )
    }

    @Test
    fun `the degrees of freedom floor is enforced and is inclusive`() {
        // Twenty intervals of two revolutions give exactly one degree of freedom each.
        val just = roughness(v2Samples(count = 20) { interval(revCount = 2, sdRpmX10 = 500) })
        assertEquals(50.0, (just as Roughness.Measured).sigmaRpm, 1e-9)
        // Nineteen of them is one short, and the window is padded back to the sample
        // floor with intervals that carry no weight.
        val short = roughness(v2Samples(count = 40) {
            if (it < 19) interval(revCount = 2, sdRpmX10 = 500)
            else IntervalStats(revCount = 1, sdRpmX10 = 0, minRpm = 3000, maxRpm = 3000)
        })
        assertEquals(Roughness.TooFewRevolutions, short)
    }

    @Test
    fun `a self-contradictory interval is excluded and counted`() {
        val r = roughness(v2Samples(count = 44) {
            // Four intervals whose bounds cannot bracket the mean they came with.
            if (it < 4) IntervalStats(revCount = 5, sdRpmX10 = 9999, minRpm = 100, maxRpm = 200)
            else interval(revCount = 5, sdRpmX10 = 1000)
        }) as Roughness.Measured
        assertEquals(4, r.excluded)
        // The bad intervals are gone from the figure entirely, not merely diluted.
        assertEquals(100.0, r.sigmaRpm, 1e-9)
        assertEquals(40, r.intervals)
    }

    @Test
    fun `roughness and wander are measured independently of one another`() {
        // Perfectly steady interval means, so wander is zero, while each interval is
        // internally rough. Version 1 could not tell these apart at all.
        val s = v2Samples { interval(revCount = 5, sdRpmX10 = 1200) }
        val m = EngineStability.of(s) as Stability.Measured
        assertEquals(0.0, m.sigmaRpm, 1e-9)
        assertEquals(120.0, (m.roughness as Roughness.Measured).sigmaRpm, 1e-9)
    }

    @Test
    fun `the quantisation floor rises with the square of speed`() {
        val idle = roughness(v2Samples(rpm = 900) { interval(revCount = 5, sdRpmX10 = 200, rpm = 900) })
        val fast = roughness(v2Samples(rpm = 7500) { interval(revCount = 5, sdRpmX10 = 200, rpm = 7500) })
        // 1 us on each of two pulses per revolution: sigma is q*sqrt(2/12) in the
        // period, scaled into rpm by rpm^2 / 60e6.
        assertEquals(0.0055, (idle as Roughness.Measured).floorRpm, 5e-4)
        assertEquals(0.383, (fast as Roughness.Measured).floorRpm, 5e-3)
        // At the top of the range the floor is a real fraction of a small reading, so
        // 20 rpm of measured roughness there is engine and 0.3 rpm is the clock.
        assertTrue(fast.floorRpm > idle.floorRpm * 50)
    }

    /** Box-Muller. `kotlin.random` offers no Gaussian of its own. */
    private fun Random.nextGaussian(sigma: Double): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sigma * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
