package dev.anydisplay.client.video

import org.junit.Assert.*
import org.junit.Test

class DecodeNegotiationTest {
    private fun target(id: Long, w: Int, h: Int) = DecodeTarget(id, 2, DecodeSize(w, h))
    private fun policy(w: Int = 1600, h: Int = 2560) = DecodeNegotiation().apply {
        begin(DecodeSize(w, h), { x, y -> x <= 2560 && y <= 1440 })
    }

    @Test fun requestsDesiredSizeEvenWhenNotAdvertised() {
        assertEquals(DecodeSize(1600, 2560), policy().requested)
    }

    @Test fun boundsHugeRequestsWithoutIntegerOverflow() {
        val p = policy(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(DecodeSize(4096, 4096), p.requested)
    }

    @Test fun respectsAlignmentAndAspectRatioInBothOrientations() {
        for ((w, h) in listOf(1600 to 2560, 2560 to 1600)) {
            val p = DecodeNegotiation()
            val size = p.begin(DecodeSize(w + 1, h + 1), { _, _ -> false }, 16, 8)
            assertEquals(0, size.width % 16)
            assertEquals(0, size.height % 8)
            assertTrue(kotlin.math.abs(size.width.toDouble() / size.height - w.toDouble() / h) < .02)
        }
    }

    @Test fun configureAloneAndNoInputNeverTriggerTimeout() {
        val p = policy(); assertTrue(p.accept(target(1, 1600, 2560)))
        assertFalse(p.timedOut(100_000))
        assertFalse(p.confirmed)
    }

    @Test fun firstActualInputStartsFiveSecondDeadline() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.input(1, 100)
        p.input(1, 2_000)
        assertFalse(p.timedOut(5_099))
        assertTrue(p.timedOut(5_100))
    }

    @Test fun firstOutputConfirmsAndStaticImageDoesNotTimeOut() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.input(1, 100)
        assertTrue(p.output(1)); assertTrue(p.confirmed)
        assertFalse(p.timedOut(999_999))
    }

    @Test fun firstFailureTriesSlightlySmallerPortraitBeforeReportedLimits() {
        val p = policy(); p.accept(target(1, 1600, 2560))
        val action = p.recover(1, "no output") as DecodeRecovery.Retry
        assertEquals(DecodeSize(1440, 2304), action.size)
        assertFalse(p.output(1))
    }

    @Test fun nextFailureIncludesAnAdvertisedCandidate() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.recover(1, "failed")
        p.accept(target(2, 1440, 2304))
        val size = (p.recover(2, "failed") as DecodeRecovery.Retry).size
        assertTrue(size.width <= 2560 && size.height <= 1440)
        assertTrue(kotlin.math.abs(size.width.toDouble() / size.height - .625) < .01)
    }

    @Test fun stopsAfterFourActualAttempts() {
        val p = policy()
        for (i in 1L..4L) {
            val size = p.requested!!
            assertTrue(p.accept(DecodeTarget(i, 2, size)))
            val action = p.recover(i, "failed")
            if (i < 4) assertTrue(action is DecodeRecovery.Retry)
            else assertTrue(action is DecodeRecovery.Stop)
        }
    }

    @Test fun neverRepeatsFailedHostSelectedSize() {
        val p = policy(); p.accept(target(1, 848, 1358)); p.recover(1, "failed")
        assertFalse(p.accept(target(2, 848, 1358)))
    }

    @Test fun fallbackIsSmallerThanActualHostResponseNotOriginalRequest() {
        val p = policy(); p.accept(target(1, 640, 1024))
        val size = (p.recover(1, "failed") as DecodeRecovery.Retry).size
        assertTrue(size.width < 640 && size.height < 1024)
    }

    @Test fun resourceShortageStopsWithoutMisclassifyingResolution() {
        val p = policy(); p.accept(target(1, 1600, 2560))
        val result = p.recover(1, "insufficient resources", temporary = true)
        assertTrue(result is DecodeRecovery.Stop)
        assertTrue((result as DecodeRecovery.Stop).reason.contains("resources"))
        assertEquals(DecodeSize(1600, 2560), p.requested)
    }

    @Test fun staleStreamCallbacksCannotChangeNewAttempt() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.recover(1, "failed")
        p.accept(target(2, 1440, 2304))
        p.input(1, 0); assertFalse(p.output(1))
        assertEquals(DecodeRecovery.Ignore, p.recover(1, "late error"))
        assertFalse(p.timedOut(10_000))
    }

    @Test fun settingsChangeStartsFreshSeriesAndIgnoresOldStream() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.input(1, 0)
        val oldGeneration = p.generation
        p.begin(DecodeSize(2560, 1600), { _, _ -> true })
        assertTrue(p.generation > oldGeneration)
        assertFalse(p.output(1)); assertFalse(p.timedOut(10_000))
        assertEquals(DecodeSize(2560, 1600), p.requested)
    }

    @Test fun surfaceDetachCancelsDeadlineAndIgnoresCallbacks() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.input(1, 0); p.suspend()
        assertFalse(p.timedOut(10_000)); assertFalse(p.output(1))
        assertEquals(DecodeRecovery.Ignore, p.recover(1, "released"))
        p.resume(1); p.input(1, 20_000)
        assertFalse(p.timedOut(24_999)); assertTrue(p.timedOut(25_000))
    }

    @Test fun errorAfterConfirmedPlaybackStillUsesFiniteRecovery() {
        val p = policy(); p.accept(target(1, 1600, 2560)); p.output(1)
        assertTrue(p.recover(1, "decoder failed") is DecodeRecovery.Retry)
    }

    @Test fun invalidHostConfigurationIsRejected() {
        val p = policy()
        assertFalse(p.accept(target(1, 8192, 8192)))
    }

    @Test fun requestsAreSerializedAndOldConfigCannotBecomeCurrentAfterRotation() {
        val gate = DecodeRequestGate()
        assertTrue(gate.offer(1))
        assertFalse(gate.offer(2))
        assertFalse(gate.response(2))
        assertTrue(gate.offer(2))
        assertTrue(gate.response(2))
    }

    @Test fun unsolicitedConfigIsAllowedWhenNoRequestIsPending() {
        assertTrue(DecodeRequestGate().response(3))
    }

    @Test fun repeatedConfigDoesNotRestartFirstFrameDeadline() {
        val p = policy(); val t = target(1, 1600, 2560)
        p.accept(t); p.input(1, 0); p.accept(t)
        assertTrue(p.timedOut(5_000))
    }

    @Test fun zeroAndNegativeDimensionsAreRejectedBeforeBufferArithmetic() {
        assertThrows(IllegalArgumentException::class.java) { DecodeSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { DecodeSize(100, -1) }
    }

    @Test fun successfulSameSizeHostReconfigurationsDoNotExhaustResolutionBudget() {
        val p = policy()
        for (id in 1L..8L) {
            assertTrue(p.accept(target(id, 1600, 2560)))
            assertTrue(p.output(id))
        }
        assertTrue(p.recover(8, "failure after host reconfiguration") is DecodeRecovery.Retry)
    }

    @Test fun inputReportsOnlyTheFirstQueuedFrameForDeadlineLogging() {
        val p = policy(); p.accept(target(1, 1600, 2560))
        assertFalse(p.input(9, 0))
        assertTrue(p.input(1, 100))
        assertFalse(p.input(1, 200))
        p.output(1)
        assertFalse(p.input(1, 300))
    }

    @Test fun codecRestartRearmsFirstOutputDeadlineAfterSuccessfulPlayback() {
        val p = policy(); p.accept(target(1, 1600, 2560))
        assertTrue(p.decoderStarting(1, 10))
        p.input(1, 100, 10); p.output(1, 10)
        assertTrue(p.confirmed)
        assertTrue(p.decoderStarting(1, 11))
        assertFalse(p.confirmed)
        p.input(1, 20_000, 11)
        assertFalse(p.timedOut(24_999)); assertTrue(p.timedOut(25_000))
    }

    @Test fun callbacksFromOldCodecCannotConfirmOrFailRestartedCodecOnSameStream() {
        val p = policy(); p.accept(target(1, 1600, 2560))
        p.decoderStarting(1, 10); p.input(1, 0, 10); p.output(1, 10)
        p.decoderStarting(1, 11)
        assertFalse(p.input(1, 1_000, 10)); assertFalse(p.output(1, 10))
        assertEquals(DecodeRecovery.Ignore, p.recover(1, "old codec", decoderId = 10))
        assertFalse(p.decoderStarting(1, 10))
        p.input(1, 2_000, 11)
        assertTrue(p.timedOut(7_000))
    }

    @Test fun duplicateCodecStartingDoesNotPostponeDeadline() {
        val p = policy(); p.accept(target(1, 1600, 2560))
        p.decoderStarting(1, 10); p.input(1, 0, 10)
        assertFalse(p.decoderStarting(1, 10))
        assertTrue(p.timedOut(5_000))
    }
}
