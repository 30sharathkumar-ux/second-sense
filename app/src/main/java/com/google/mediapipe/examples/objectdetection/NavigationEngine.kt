package com.google.mediapipe.examples.objectdetection

import android.util.Log

/**
 * NavigationEngine — navigation-v6 Phase 1
 *
 * Splits the lower 2/3 of the MiDaS depth map into 5 vertical zones and computes
 * a walking-direction command (GO_STRAIGHT / MOVE_LEFT / MOVE_FAR_LEFT /
 * MOVE_RIGHT / MOVE_FAR_RIGHT / STOP) with hysteresis and flip-protection.
 *
 * ── Tuning constants ─────────────────────────────────────────────────────────
 */
class NavigationEngine {

    companion object {
        private const val TAG = "NAV"

        // ── Zone thresholds ──────────────────────────────────────────────────
        /** Normalised closeness (0..1) above which a zone is marked BLOCKED. */
        const val BLOCKED_THRESHOLD = 0.60f

        /** Extra closeness weight added per detected object box overlapping a zone.
         *  Applied after depth normalisation so it is scene-relative. */
        const val DETECTION_WEIGHT = 0.20f

        // ── Hysteresis & stability ───────────────────────────────────────────
        /** Command must be the same for this many consecutive engine calls to commit. */
        const val STABLE_FRAMES_REQUIRED = 3

        /** Minimum ms between switching from one side (left) to the other (right). */
        const val SIDE_FLIP_COOLDOWN_MS = 1_500L

        // ── Speech scheduling ────────────────────────────────────────────────
        /** Re-speak the current non-straight command every N ms as a reminder. */
        const val REMINDER_INTERVAL_MS = 4_000L
    }

    // ── State ─────────────────────────────────────────────────────────────────

    enum class Command {
        GO_STRAIGHT, MOVE_LEFT, MOVE_FAR_LEFT, MOVE_RIGHT, MOVE_FAR_RIGHT, STOP
    }

    /** Five vertical zones, left→right. */
    enum class Zone { FAR_LEFT, LEFT, CENTER, RIGHT, FAR_RIGHT }

    data class ZoneCloseness(
        val farLeft:  Float,
        val left:     Float,
        val center:   Float,
        val right:    Float,
        val farRight: Float
    )

    data class NavResult(
        val command:      Command,
        val zones:        ZoneCloseness,
        val shouldSpeak:  Boolean,
        val isReminder:   Boolean
    )

    // candidate command being accumulated
    private var candidateCommand: Command = Command.GO_STRAIGHT
    private var candidateCount:   Int     = 0

    // committed (stable) command
    private var committedCommand: Command = Command.GO_STRAIGHT

    // flip-protection: track which "side" the last committed non-straight command was on
    private enum class Side { NONE, LEFT, RIGHT }
    private var lastSide:         Side = Side.NONE
    private var lastSideTimestamp: Long = 0L

    // speech scheduling
    private var lastSpokenCommand:   Command = Command.GO_STRAIGHT
    private var lastSpokenTimestamp: Long    = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Feed this engine once per frame (on the background thread).
     *
     * @param depthMap     Raw MiDaS output (inverse depth, larger = closer).
     *                     Must be [mapSize × mapSize] floats.
     * @param mapSize      Width/height of the square depth map (typically 256).
     * @param maxDepthVal  Maximum value in [depthMap] (used for normalisation).
     * @param detections   List of (normLeft, normTop, normRight, normBottom) bounding boxes
     *                     in the coordinate space of the upright image [0..1].
     *                     Only used to add extra weight to overlapping zones.
     * @param nowMs        Wall-clock time; injectable for testing.
     */
    fun analyze(
        depthMap:   FloatArray,
        mapSize:    Int,
        maxDepthVal: Float,
        detections: List<FloatArray>,   // each: [normL, normT, normR, normB]
        nowMs:      Long = System.currentTimeMillis()
    ): NavResult {

        // ── 1. Compute average closeness per zone ─────────────────────────────
        val zones = computeZoneCloseness(depthMap, mapSize, maxDepthVal, detections)

        // ── 2. Determine raw command ──────────────────────────────────────────
        val raw = decideCommand(zones, nowMs)

        // ── 3. Hysteresis: only commit after STABLE_FRAMES_REQUIRED frames ────
        if (raw == candidateCommand) {
            candidateCount++
        } else {
            candidateCommand = raw
            candidateCount   = 1
        }

        val newCommand = if (candidateCount >= STABLE_FRAMES_REQUIRED) raw else committedCommand

        // ── 4. Speech scheduling ──────────────────────────────────────────────
        val commandChanged = newCommand != committedCommand
        committedCommand = newCommand

        val shouldSpeak: Boolean
        val isReminder: Boolean

        when {
            commandChanged -> {
                shouldSpeak = true
                isReminder  = false
                lastSpokenCommand   = newCommand
                lastSpokenTimestamp = nowMs
            }
            newCommand != Command.GO_STRAIGHT &&
            (nowMs - lastSpokenTimestamp) >= REMINDER_INTERVAL_MS -> {
                shouldSpeak = true
                isReminder  = true
                lastSpokenTimestamp = nowMs
            }
            else -> {
                shouldSpeak = false
                isReminder  = false
            }
        }

        Log.d(TAG, "zones FL=${f(zones.farLeft)} L=${f(zones.left)} C=${f(zones.center)} " +
                "R=${f(zones.right)} FR=${f(zones.farRight)} → cmd=$newCommand speak=$shouldSpeak")

        return NavResult(newCommand, zones, shouldSpeak, isReminder)
    }

    /** Simulate a specific scenario (used by test buttons — bypasses depth analysis). */
    fun simulate(command: Command, nowMs: Long = System.currentTimeMillis()): NavResult {
        candidateCommand = command; candidateCount = STABLE_FRAMES_REQUIRED
        committedCommand = command
        lastSpokenCommand = command; lastSpokenTimestamp = nowMs
        val fakeZones = fakeZonesForCommand(command)
        return NavResult(command, fakeZones, shouldSpeak = true, isReminder = false)
    }

    /** Reset all state (call when scanning stops). */
    fun reset() {
        candidateCommand  = Command.GO_STRAIGHT
        candidateCount    = 0
        committedCommand  = Command.GO_STRAIGHT
        lastSide          = Side.NONE
        lastSideTimestamp = 0L
        lastSpokenCommand = Command.GO_STRAIGHT
        lastSpokenTimestamp = 0L
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun computeZoneCloseness(
        depthMap:   FloatArray,
        mapSize:    Int,
        maxDepthVal: Float,
        detections: List<FloatArray>
    ): ZoneCloseness {
        val norm = maxDepthVal.takeIf { it > 0f } ?: 1f

        // Lower 2/3 of the map
        val rowStart = mapSize / 3
        val rowEnd   = mapSize  // exclusive

        // 5 columns: widths are [0..1/5), [1/5..2/5), [2/5..3/5), [3/5..4/5), [4/5..1)
        // in pixel space: each col span = mapSize / 5
        val colW = mapSize / 5

        val sums   = FloatArray(5)
        val counts = IntArray(5)

        for (r in rowStart until rowEnd) {
            for (c in 0 until mapSize) {
                val zoneIdx = (c / colW).coerceAtMost(4)
                sums[zoneIdx]   += depthMap[r * mapSize + c] / norm
                counts[zoneIdx] += 1
            }
        }

        val avgs = FloatArray(5) { i -> if (counts[i] > 0) sums[i] / counts[i] else 0f }

        // ── Add detection weight ──────────────────────────────────────────────
        // Convert normalised box coords → zone index and bump that zone
        for (box in detections) {
            val normL = box.getOrElse(0) { 0f }
            val normR = box.getOrElse(2) { 1f }
            // Find all zones the box overlaps (box coords already normalised 0..1)
            for (zi in 0..4) {
                val zoneNormL = zi / 5f
                val zoneNormR = (zi + 1) / 5f
                val overlap = minOf(normR, zoneNormR) - maxOf(normL, zoneNormL)
                if (overlap > 0f) {
                    avgs[zi] = (avgs[zi] + DETECTION_WEIGHT * (overlap / (zoneNormR - zoneNormL)))
                        .coerceAtMost(1f)
                }
            }
        }

        return ZoneCloseness(avgs[0], avgs[1], avgs[2], avgs[3], avgs[4])
    }

    private fun decideCommand(z: ZoneCloseness, nowMs: Long): Command {
        val cBlocked  = z.center  > BLOCKED_THRESHOLD
        val lBlocked  = z.left    > BLOCKED_THRESHOLD
        val rBlocked  = z.right   > BLOCKED_THRESHOLD
        val flBlocked = z.farLeft > BLOCKED_THRESHOLD
        val frBlocked = z.farRight > BLOCKED_THRESHOLD

        if (!cBlocked) return Command.GO_STRAIGHT

        // Center is blocked — decide a side
        val leftFree    = !lBlocked
        val rightFree   = !rBlocked
        val farLeftFree  = !flBlocked
        val farRightFree = !frBlocked

        // Nothing free on any side?
        if (!leftFree && !rightFree && !farLeftFree && !farRightFree) return Command.STOP

        // Prefer the side with more free width (lower closeness sum)
        val leftScore  = z.left  + z.farLeft
        val rightScore = z.right + z.farRight

        // Flip-protection
        val flipCooldownOk = (nowMs - lastSideTimestamp) >= SIDE_FLIP_COOLDOWN_MS

        fun canGoLeft()  = (leftFree || farLeftFree)  && (lastSide != Side.RIGHT || flipCooldownOk)
        fun canGoRight() = (rightFree || farRightFree) && (lastSide != Side.LEFT  || flipCooldownOk)

        return when {
            canGoLeft() && canGoRight() -> {
                // Choose the less-blocked side
                if (leftScore <= rightScore) pickLeftCommand(leftFree, farLeftFree, nowMs)
                else                         pickRightCommand(rightFree, farRightFree, nowMs)
            }
            canGoLeft()  -> pickLeftCommand(leftFree, farLeftFree, nowMs)
            canGoRight() -> pickRightCommand(rightFree, farRightFree, nowMs)
            else         -> Command.STOP
        }
    }

    private fun pickLeftCommand(leftFree: Boolean, farLeftFree: Boolean, nowMs: Long): Command {
        lastSide = Side.LEFT; lastSideTimestamp = nowMs
        return if (leftFree) Command.MOVE_LEFT else Command.MOVE_FAR_LEFT
    }

    private fun pickRightCommand(rightFree: Boolean, farRightFree: Boolean, nowMs: Long): Command {
        lastSide = Side.RIGHT; lastSideTimestamp = nowMs
        return if (rightFree) Command.MOVE_RIGHT else Command.MOVE_FAR_RIGHT
    }

    private fun fakeZonesForCommand(cmd: Command) = when (cmd) {
        Command.GO_STRAIGHT  -> ZoneCloseness(0.1f, 0.1f, 0.1f, 0.1f, 0.1f)
        Command.MOVE_LEFT    -> ZoneCloseness(0.1f, 0.1f, 0.8f, 0.8f, 0.8f)
        Command.MOVE_FAR_LEFT-> ZoneCloseness(0.1f, 0.8f, 0.8f, 0.8f, 0.8f)
        Command.MOVE_RIGHT   -> ZoneCloseness(0.8f, 0.8f, 0.8f, 0.1f, 0.1f)
        Command.MOVE_FAR_RIGHT->ZoneCloseness(0.8f, 0.8f, 0.8f, 0.8f, 0.1f)
        Command.STOP         -> ZoneCloseness(0.8f, 0.8f, 0.8f, 0.8f, 0.8f)
    }

    private fun f(v: Float) = "%.2f".format(v)
}
