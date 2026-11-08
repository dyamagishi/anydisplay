package dev.anydisplay.client.video

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class DecodeSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val pixels: Long get() = width.toLong() * height
}

data class DecodeTarget(val streamId: Long, val codec: Int, val size: DecodeSize)

/** Keep one CONFIG_REQ in flight so a rotation cannot mistake an older CONFIG for its response. */
class DecodeRequestGate {
    private var pendingGeneration: Long? = null

    fun offer(generation: Long): Boolean {
        if (pendingGeneration != null) return false
        pendingGeneration = generation
        return true
    }

    fun response(currentGeneration: Long): Boolean {
        val sent = pendingGeneration
        pendingGeneration = null
        return sent == null || sent == currentGeneration
    }
}

sealed interface DecodeRecovery {
    data class Retry(val size: DecodeSize) : DecodeRecovery
    data class Stop(val reason: String) : DecodeRecovery
    data object Ignore : DecodeRecovery
}

/** Per-connection negotiation policy. Its owner serializes calls; no Android APIs or timers live here. */
class DecodeNegotiation {
    var generation = 0L
        private set
    var requested: DecodeSize? = null
        private set
    var target: DecodeTarget? = null
        private set
    var confirmed = false
        private set
    var decoderId: Long? = null
        private set

    private var advertised: (Int, Int) -> Boolean = { _, _ -> true }
    private var alignW = 2
    private var alignH = 2
    private var attempts = 0
    private var active = false
    private var firstInputMs: Long? = null
    private val failed = mutableSetOf<Pair<Int, DecodeSize>>()
    private val attemptedSizes = mutableSetOf<DecodeSize>()

    fun begin(
        desired: DecodeSize,
        declaredSupport: (Int, Int) -> Boolean,
        alignmentW: Int = 2,
        alignmentH: Int = 2,
    ): DecodeSize {
        generation++
        advertised = declaredSupport
        alignW = evenAlignment(alignmentW)
        alignH = evenAlignment(alignmentH)
        attempts = 0
        failed.clear()
        attemptedSizes.clear()
        target = null
        active = false
        confirmed = false
        decoderId = null
        firstInputMs = null
        val factor = min(1.0, min(MAX_EDGE.toDouble() / maxOf(desired.width, desired.height),
            sqrt(MAX_PIXELS.toDouble() / desired.pixels)))
        val size = scaled(desired, factor) ?: error("Requested image is smaller than decoder alignment")
        requested = size
        return size
    }

    fun accept(value: DecodeTarget): Boolean {
        if (requested == null || value.codec !in 1..3 || !bounded(value.size)) return false
        if (value == target) return true
        if (value.codec to value.size in failed) return false
        if (value.size !in attemptedSizes) {
            if (attempts >= MAX_ATTEMPTS) return false
            attemptedSizes += value.size
            attempts++
        }
        target = value
        active = true
        confirmed = false
        decoderId = null
        firstInputMs = null
        return true
    }

    fun isCurrent(streamId: Long): Boolean = active && target?.streamId == streamId

    fun decoderStarting(streamId: Long, id: Long): Boolean {
        if (!isCurrent(streamId) || id <= 0 || decoderId?.let { id <= it } == true) return false
        decoderId = id
        confirmed = false
        firstInputMs = null
        return true
    }

    fun input(streamId: Long, nowMs: Long, decoderId: Long? = null): Boolean {
        if (!isCurrent(streamId) || decoderId != this.decoderId || confirmed || firstInputMs != null) return false
        firstInputMs = nowMs
        return true
    }

    fun output(streamId: Long, decoderId: Long? = null): Boolean {
        if (!isCurrent(streamId) || decoderId != this.decoderId) return false
        confirmed = true
        firstInputMs = null
        return true
    }

    fun timedOut(nowMs: Long): Boolean {
        val started = firstInputMs ?: return false
        return active && !confirmed && nowMs - started >= FIRST_OUTPUT_TIMEOUT_MS
    }

    fun suspend() {
        active = false
        decoderId = null
        firstInputMs = null
    }

    fun resume(streamId: Long) {
        if (target?.streamId != streamId) return
        active = true
        decoderId = null
        confirmed = false
        firstInputMs = null
    }

    fun recover(streamId: Long, reason: String, temporary: Boolean = false, decoderId: Long? = null): DecodeRecovery {
        if (!isCurrent(streamId) || (decoderId != null && decoderId != this.decoderId)) return DecodeRecovery.Ignore
        val previous = target!!
        suspend()
        if (temporary) return DecodeRecovery.Stop(reason)
        failed += previous.codec to previous.size
        target = null
        if (attempts >= MAX_ATTEMPTS) return DecodeRecovery.Stop("Decoder failed after $attempts sizes: $reason")

        // Try a nearby size before trusting an under-reported capability table.
        val next = when (attempts) {
            1 -> scaled(previous.size, .9)
            2 -> advertisedBelow(previous.size) ?: scaled(previous.size, .75)
            else -> scaled(previous.size, .75)
        }
        if (next == null || next.width < 320 || next.height < 240 ||
            next.width >= previous.size.width || next.height >= previous.size.height ||
            previous.codec to next in failed) {
            return DecodeRecovery.Stop("No smaller usable decoder size: $reason")
        }
        requested = next
        return DecodeRecovery.Retry(next)
    }

    private fun advertisedBelow(size: DecodeSize): DecodeSize? {
        var candidate = size
        repeat(20) {
            candidate = scaled(candidate, .9) ?: return null
            if (advertised(candidate.width, candidate.height)) return candidate
        }
        return null
    }

    private fun scaled(size: DecodeSize, factor: Double): DecodeSize? {
        val w = floor(size.width * factor).toLong() / alignW * alignW
        val h = floor(size.height * factor).toLong() / alignH * alignH
        if (w <= 0 || h <= 0 || w > MAX_EDGE || h > MAX_EDGE || w * h > MAX_PIXELS) return null
        return DecodeSize(w.toInt(), h.toInt())
    }

    private fun bounded(size: DecodeSize) =
        size.width <= MAX_EDGE && size.height <= MAX_EDGE && size.pixels <= MAX_PIXELS

    private fun evenAlignment(value: Int): Int {
        require(value in 1..MAX_EDGE)
        return if (value % 2 == 0) value else value * 2
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val FIRST_OUTPUT_TIMEOUT_MS = 5_000L
        const val MAX_EDGE = 4096
        const val MAX_PIXELS = MAX_EDGE.toLong() * MAX_EDGE
    }
}
