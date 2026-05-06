package com.hydra.app.ble

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * Larq bottle wire protocol.
 *
 * Frame layout (both directions):
 *   0x0d  <seq:4-byte LE uint32>  <protobuf top-level fields...>
 *
 * Requests have one top-level field: field 2 (tag 0x12, length-delimited) = a google.protobuf.Any.
 * Responses have two top-level fields:
 *   field 2 (tag 0x10, varint)         = status code (1 = OK, 2+ = error)
 *   field 3 (tag 0x1a, length-delimited) = the Any
 *
 * google.protobuf.Any structure:
 *   field 1 (tag 0x0a) = type_url string, e.g. "type.googleapis.com/RequestGetCapTofLog"
 *   field 2 (tag 0x12) = the actual message bytes (omitted entirely when empty)
 *
 * Some protocol errors (e.g. "unknown command") arrive as raw 2-byte notifications
 * without the 0x0d header — those are surfaced as DecodedFrame.error.
 */
object BottleProtocol {

    // Type URL prefix used by all Larq messages.
    const val TYPE_URL_PREFIX = "type.googleapis.com/"

    /** Bottle's success status code (top-level field 2 = 1). Anything else is an error. */
    const val STATUS_OK = 1L

    /** CapEnumLogQuerySearchAlgo values for RequestGetCapTofLog pagination. */
    const val SEARCH_ALGO_TIMESTAMP = 0  // filter: return entries newer than fromTimestamp
    const val SEARCH_ALGO_INCREMENT = 1  // pagination: return next page after fromTimestamp

    // Known request type URLs (the bottle's full vocabulary as discovered from snoop log).
    object Requests {
        const val SET_TIME              = "${TYPE_URL_PREFIX}RequestSetCapTimeSettings"
        const val SET_HYDRO_REMINDER    = "${TYPE_URL_PREFIX}RequestSetCapHydroReminderSettings"
        const val GET_UI_STATE          = "${TYPE_URL_PREFIX}RequestGetCapUiState"
        const val GET_DND               = "${TYPE_URL_PREFIX}RequestGetCapDoNotDisturbSettings"
        const val GET_LOW_BATTERY       = "${TYPE_URL_PREFIX}RequestGetCapLowBatterySettings"
        const val GET_TOF_SETTINGS      = "${TYPE_URL_PREFIX}RequestGetCapTofSettings"
        const val GET_HYDRO_REMINDER    = "${TYPE_URL_PREFIX}RequestGetCapHydroReminderSettings"
        const val GET_UV_CONFIG         = "${TYPE_URL_PREFIX}RequestGetCapUvConfig"
        const val GET_TOF_LOG           = "${TYPE_URL_PREFIX}RequestGetCapTofLog"
        const val GET_FAULT_LOG         = "${TYPE_URL_PREFIX}RequestGetCapFaultLog"
        const val GET_ACTIVATION_LOG    = "${TYPE_URL_PREFIX}RequestGetCapActivationLog"
        const val GET_CHARGING_ADC_LOG  = "${TYPE_URL_PREFIX}RequestGetChargingCapAdcLog"
        const val GET_ACTIVATION_ADC_LOG = "${TYPE_URL_PREFIX}RequestGetActivationCapAdcLog"
    }
}

// ── Decoded frame envelope ─────────────────────────────────────────────────────

/**
 * Result of parsing one BLE notification.
 * - Successful framed responses populate [seq], [typeUrl], [valueBytes], [status].
 * - Raw protocol errors (no 0x0d header) populate [error] only.
 * - Returns null from decode() for anything else (incomplete, malformed).
 */
data class DecodedFrame(
    val seq: Int? = null,
    val typeUrl: String? = null,
    val valueBytes: ByteArray? = null,
    val status: Long? = null,
    val error: ByteArray? = null,
) {
    val isError: Boolean get() = error != null || (status != null && status != BottleProtocol.STATUS_OK)
}

// ── Sip event (TofLog) ────────────────────────────────────────────────────────

/**
 * One sip event parsed from a TofLog entry inside ResponseGetCapTofLog.
 *
 * Protobuf field mapping (from decompiled Fc.g0):
 *   field 1 = timestamp (varint, Unix epoch SECONDS)
 *   field 2 = triggerType (varint enum)
 *   field 3 = distanceInMillimeter (fixed32 int) — the polynomial input
 *   field 4 = kcps (fixed32 int) — sensor noise/photon count
 *   field 5 = uvLedTempInOhm (fixed32 IEEE-754 float)
 */
data class SipEntry(
    val timestampSec: Long,
    val triggerType: Int,
    val distanceMm: Int,
    val kcps: Int,
    val uvLedTempOhm: Float,
)

// ── Settings messages (decoded from CapBle.java schemas) ──────────────────────

enum class HydroReminderState(val code: Int) {
    OFF(0), INTERVAL_FIXED(1), INTERVAL_ADAPTIVE(2);
    companion object { fun from(c: Int): HydroReminderState? = entries.firstOrNull { it.code == c } }
}

enum class DoNotDisturbState(val code: Int) {
    OFF(0), ON(1), AUTO(2);
    companion object { fun from(c: Int): DoNotDisturbState? = entries.firstOrNull { it.code == c } }
}

enum class TofDisplayMode(val code: Int) {
    OFF(0), ON(1), EVENT(2);
    companion object { fun from(c: Int): TofDisplayMode? = entries.firstOrNull { it.code == c } }
}

/** Bottle's "what is the cap currently doing" enum (CapEnumUiState). */
enum class UiStateEnum(val code: Int) {
    ON(0), FAULT(1), UV_MAINTENANCE(2), UV_NORMAL(3), UV_ADVENTURE(4),
    PAIRED(5), HYDRATION_REMINDER(6), BATTERY_LOW(7), CHARGING(8), CHARGED(9),
    UV_INTERLOCK(10), BOTTLE_CALIBRATION(11), TOF_MEASUREMENT(12),
    TURN_OFF(13), FACTORY_RESET(14), ALL_OFF(15), LOCKED(16),
    QC(17), LAST(18);
    companion object { fun from(c: Int): UiStateEnum? = entries.firstOrNull { it.code == c } }
}

data class UiStateInfo(
    val state: UiStateEnum?,
    val stateRaw: Int,
    val powerSavingMode: Int,
) {
    fun format(): String = buildString {
        append(state?.name ?: "state=$stateRaw")
        append(", powerSave=$powerSavingMode")
    }
}

data class HydroReminderSettings(
    val state: HydroReminderState?,
    val stateRaw: Int,
    val startTimeInSec: Int,
    val stopTimeInSec: Int,
    val intervalInSec: Int,
    val colorRaw: ByteArray?, // CapColor sub-message, schema TBD
) {
    fun format(): String = buildString {
        append(state?.name ?: "state=$stateRaw")
        append(' ')
        append(formatHMRange(startTimeInSec, stopTimeInSec))
        append(" every ")
        append(formatDurationShort(intervalInSec))
    }
}

data class DoNotDisturbSettings(
    val state: DoNotDisturbState?,
    val stateRaw: Int,
    val startTimeInSec: Int,
    val stopTimeInSec: Int,
    val snoozeTimeInMinutes: Int,
) {
    fun format(): String = buildString {
        append(state?.name ?: "state=$stateRaw")
        append(' ')
        append(formatHMRange(startTimeInSec, stopTimeInSec))
        append(", snooze ${snoozeTimeInMinutes}min")
    }
}

data class LowBatterySettings(
    val alertThresholdPct: Int,
    val lockoutThresholdPct: Int,
) {
    fun format(): String = "alert ≤${alertThresholdPct}%, lockout ≤${lockoutThresholdPct}%"
}

data class TofSettings(
    val mode: TofDisplayMode?,
    val modeRaw: Int,
    val isIntervalTriggerEnabled: Boolean,
    val triggerIntervalInSec: Int,
    val accelerometerCheckIntervalInSec: Int,
    val bottleSizeInMilliliter: Int,
    val accelerometerTimeoutInSec: Int,
) {
    fun format(): String = buildString {
        append(mode?.name ?: "mode=$modeRaw")
        if (isIntervalTriggerEnabled) append(" interval=${triggerIntervalInSec}s")
        append(", bottle=${bottleSizeInMilliliter}mL")
        append(", accel ${accelerometerCheckIntervalInSec}s/${accelerometerTimeoutInSec}s")
    }
}

data class UvParameters(val rawBytes: ByteArray) {
    val fields: List<ProtoField> get() = Wire.walkFields(rawBytes)
}

data class UvConfig(
    val maintenance: UvParameters?,
    val standard: UvParameters?,
    val adventure: UvParameters?,
) {
    fun format(): String {
        val present = listOfNotNull(
            maintenance?.let { "maintenance" },
            standard?.let { "standard" },
            adventure?.let { "adventure" },
        )
        return "${present.size} UV modes (${present.joinToString(", ")})"
    }
}

// ── Generic protobuf field walker (fallback for unknown messages) ─────────────

/** One protobuf field walked generically without schema knowledge. */
data class ProtoField(
    val number: Int,
    val wireType: Int,
    /** varint as Long, fixed32/fixed64 as Long, length-delimited as ByteArray, unknown as null */
    val value: Any?,
) {
    fun format(): String = when (wireType) {
        0 -> "f$number=$value"
        1 -> "f$number=fixed64:$value"
        2 -> {
            val b = value as? ByteArray ?: ByteArray(0)
            // Heuristic: if all printable ASCII, show as string, else show length + hex preview
            val ascii = b.all { (it in 0x20..0x7E) || it.toInt() == 0x0A }
            if (ascii && b.isNotEmpty()) "f$number=\"${String(b)}\"" else "f$number=[${b.size}B]"
        }
        5 -> "f$number=fixed32:$value"
        else -> "f$number=?wt$wireType"
    }
}

// ── Wire format helpers ───────────────────────────────────────────────────────

/** Hand-rolled protobuf wire format helpers. Just enough to build/parse Larq messages. */
object Wire {

    /** Write an unsigned varint to [out]. Handles values up to Long.MAX_VALUE. */
    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    fun writeVarint(out: ByteArrayOutputStream, value: Int) = writeVarint(out, value.toLong())

    /** Read a varint from [buf] starting at [offset]. Returns (value, bytes-consumed). */
    fun readVarint(buf: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < buf.size) {
            val b = buf[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if ((b and 0x80) == 0) return result to (pos - offset)
            shift += 7
            if (shift > 63) error("varint too long")
        }
        error("truncated varint")
    }

    /** Read 4-byte little-endian uint32, returned as Int. */
    fun readFixed32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    /** Read 8-byte little-endian as Long. */
    fun readFixed64(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((buf[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    /** Read 4-byte little-endian as IEEE 754 float. */
    fun readFixed32Float(buf: ByteArray, offset: Int): Float =
        Float.fromBits(readFixed32(buf, offset))

    /** Write 4-byte little-endian uint32. */
    fun writeFixed32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    /**
     * Write a length-delimited field: tag byte + varint length + bytes.
     * [tag] must be a wire-tag like 0x0a (field 1) or 0x12 (field 2) etc.
     */
    fun writeLengthDelimited(out: ByteArrayOutputStream, tag: Int, bytes: ByteArray) {
        out.write(tag and 0xFF)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    /**
     * Generic protobuf walker — decodes any payload into a list of ProtoField tuples
     * without schema knowledge. Used as a fallback for unknown response types.
     * Crash-safe: returns empty list on malformed input.
     */
    fun walkFields(bytes: ByteArray): List<ProtoField> = runCatching {
        val out = mutableListOf<ProtoField>()
        var pos = 0
        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF
            pos++
            val number = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                0 -> {
                    val (v, c) = readVarint(bytes, pos); pos += c
                    out += ProtoField(number, 0, v)
                }
                1 -> {
                    if (pos + 8 > bytes.size) return@runCatching out
                    out += ProtoField(number, 1, readFixed64(bytes, pos)); pos += 8
                }
                5 -> {
                    if (pos + 4 > bytes.size) return@runCatching out
                    out += ProtoField(number, 5, readFixed32(bytes, pos).toLong()); pos += 4
                }
                2 -> {
                    val (l, c) = readVarint(bytes, pos); pos += c
                    if (l !in 0..(bytes.size - pos).toLong()) return@runCatching out
                    val payload = bytes.copyOfRange(pos, pos + l.toInt())
                    pos += l.toInt()
                    out += ProtoField(number, 2, payload)
                }
                else -> return@runCatching out  // unknown wire type, stop walking
            }
        }
        out.toList()
    }.getOrElse { emptyList() }
}

// ── Frame envelope ────────────────────────────────────────────────────────────

object Frame {

    /**
     * Build a complete request frame:
     *   0x0d <seq:4 LE> 0x12 <varint anyLen> <Any-bytes>
     * where Any-bytes = 0x0a <varint typeUrlLen> <typeUrl> [0x12 <varint valLen> <value>]
     *
     * If [valueBytes] is empty, the value field is omitted (matches official app for empty Get* requests).
     */
    fun encode(typeUrl: String, valueBytes: ByteArray, seq: Int): ByteArray {
        // Build the Any payload first.
        val anyOut = ByteArrayOutputStream()
        Wire.writeLengthDelimited(anyOut, tag = 0x0a, bytes = typeUrl.toByteArray(Charsets.UTF_8))
        if (valueBytes.isNotEmpty()) {
            Wire.writeLengthDelimited(anyOut, tag = 0x12, bytes = valueBytes)
        }
        val anyBytes = anyOut.toByteArray()

        // Outer frame.
        val out = ByteArrayOutputStream()
        out.write(0x0d)
        Wire.writeFixed32(out, seq)
        Wire.writeLengthDelimited(out, tag = 0x12, bytes = anyBytes)
        return out.toByteArray()
    }

    /**
     * Decode an incoming notification.
     * - Returns DecodedFrame with parsed fields on success.
     * - Returns DecodedFrame.error for raw error blobs (no 0x0d header) or malformed frames.
     * - Returns null only for empty input.
     *
     * Internally guarded with try/catch so a malformed frame never throws to the BLE
     * callback thread (which would crash the app).
     */
    fun decode(bytes: ByteArray): DecodedFrame? {
        if (bytes.isEmpty()) return null

        // Bottle's "unknown command" error arrives as a raw notification without 0x0d header.
        // Heuristic: anything <6 bytes or not starting with 0x0d is treated as a raw error blob.
        if (bytes[0] != 0x0d.toByte() || bytes.size < 6) {
            return DecodedFrame(error = bytes.copyOf())
        }

        return runCatching { decodeInner(bytes) }.getOrElse { t ->
            android.util.Log.w("HydraBle", "Frame.decode threw on ${bytes.size}B packet: ${t.message}")
            DecodedFrame(error = bytes.copyOf())
        }
    }

    private fun decodeInner(bytes: ByteArray): DecodedFrame {
        val seq = Wire.readFixed32(bytes, 1)

        var pos = 5
        var status: Long? = null
        var typeUrl: String? = null
        var valueBytes: ByteArray? = null

        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF
            pos++
            val wireType = tag and 0x07
            when (wireType) {
                0 -> {
                    val (v, consumed) = Wire.readVarint(bytes, pos)
                    pos += consumed
                    if ((tag ushr 3) == 2) status = v
                }
                1 -> pos += 8
                5 -> pos += 4
                2 -> {
                    val (len, consumed) = Wire.readVarint(bytes, pos)
                    pos += consumed
                    if (len !in 0..(bytes.size - pos).toLong()) {
                        return DecodedFrame(seq = seq, error = bytes.copyOf())
                    }
                    val end = pos + len.toInt()
                    val payload = bytes.copyOfRange(pos, end)
                    pos = end

                    val any = parseAny(payload)
                    if (any != null) {
                        typeUrl = any.first
                        valueBytes = any.second
                    }
                }
                else -> return DecodedFrame(seq = seq, error = bytes.copyOf())
            }
        }

        return DecodedFrame(seq = seq, typeUrl = typeUrl, valueBytes = valueBytes, status = status)
    }

    private fun parseAny(bytes: ByteArray): Pair<String, ByteArray>? {
        if (bytes.isEmpty() || bytes[0] != 0x0a.toByte()) return null
        var pos = 0
        var typeUrl: String? = null
        var value: ByteArray = ByteArray(0)
        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF
            if (tag != 0x0a && tag != 0x12) return null
            pos++
            val (len, consumed) = Wire.readVarint(bytes, pos)
            pos += consumed
            if (len !in 0..(bytes.size - pos).toLong()) return null
            val end = pos + len.toInt()
            val payload = bytes.copyOfRange(pos, end)
            pos = end
            when (tag) {
                0x0a -> typeUrl = String(payload, Charsets.UTF_8)
                0x12 -> value = payload
            }
        }
        return typeUrl?.let { it to value }
    }
}

// ── Request builders ──────────────────────────────────────────────────────────

/**
 * Builders for known request payloads (the inner "value" bytes for the Any).
 * Distinct from [BottleProtocol.Requests] which holds the type-URL string constants.
 */
object RequestPayloads {

    /** Empty value (for Get* requests with no params). */
    val EMPTY: ByteArray = ByteArray(0)

    /**
     * RequestSetCapTimeSettings — sets the bottle's clock.
     * Inner: { field 1 sub-message: { field 1 varint: epochMs } }
     */
    fun setTime(epochMs: Long): ByteArray {
        val sub = ByteArrayOutputStream()
        sub.write(0x08) // field 1, varint
        Wire.writeVarint(sub, epochMs)

        val out = ByteArrayOutputStream()
        Wire.writeLengthDelimited(out, tag = 0x0a, bytes = sub.toByteArray())
        return out.toByteArray()
    }

    /**
     * RequestGetCapTofLog — fetch sip history.
     * Inner CapLogQuery { fromTimestamp varint=field1, limit fixed32=field2, algo fixed32=field3 }.
     * algo defaults to SEARCH_ALGO_TIMESTAMP for first-page calls; use SEARCH_ALGO_INCREMENT
     * to paginate continuing from the last received timestamp.
     */
    fun getTofLog(
        sinceSec: Long,
        maxCount: Int,
        algo: Int = BottleProtocol.SEARCH_ALGO_TIMESTAMP,
    ): ByteArray {
        val sub = ByteArrayOutputStream()
        sub.write(0x08) // field 1, varint = fromTimestamp
        Wire.writeVarint(sub, sinceSec)
        sub.write(0x15) // field 2, fixed32 = limit
        Wire.writeFixed32(sub, maxCount)
        if (algo != BottleProtocol.SEARCH_ALGO_TIMESTAMP) {
            sub.write(0x1d) // field 3, fixed32 = algo
            Wire.writeFixed32(sub, algo)
        }

        val out = ByteArrayOutputStream()
        Wire.writeLengthDelimited(out, tag = 0x0a, bytes = sub.toByteArray())
        return out.toByteArray()
    }
}

// ── Response parsers ──────────────────────────────────────────────────────────

object Responses {

    /**
     * Many ResponseGetCapXxx messages wrap the actual CapXxx as a single field-1 sub-message:
     *   ResponseGetCapXxx { CapXxx settings = 1; }
     * Unwraps that one level if present, otherwise returns the bytes unchanged so flat
     * messages (UiState, etc.) still parse correctly.
     */
    private fun unwrapField1(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes[0] != 0x0a.toByte()) return bytes
        return runCatching {
            val (len, consumed) = Wire.readVarint(bytes, 1)
            val start = 1 + consumed
            if (len !in 0..(bytes.size - start).toLong()) return@runCatching bytes
            // Only unwrap if the field-1 sub-message is the entire payload.
            if (start + len.toInt() != bytes.size) return@runCatching bytes
            bytes.copyOfRange(start, start + len.toInt())
        }.getOrElse { bytes }
    }


    /**
     * Parse one ResponseGetCapTofLog notification.
     * Each notification carries ONE TofLog entry wrapped in field 1 (length-delimited).
     */
    fun parseTofLogEntry(valueBytes: ByteArray): SipEntry? = runCatching {
        if (valueBytes.size < 2 || valueBytes[0] != 0x0a.toByte()) return@runCatching null
        val (innerLen, lenConsumed) = Wire.readVarint(valueBytes, 1)
        val innerStart = 1 + lenConsumed
        if (innerLen !in 0..(valueBytes.size - innerStart).toLong()) return@runCatching null
        val end = innerStart + innerLen.toInt()

        var pos = innerStart
        var ts = 0L
        var trigger = 0
        var distance = 0
        var kcps = 0
        var uvTemp = 0f

        while (pos < end) {
            val tag = valueBytes[pos].toInt() and 0xFF
            pos++
            when (tag) {
                0x08 -> { val (v, c) = Wire.readVarint(valueBytes, pos); ts = v; pos += c }
                0x10 -> { val (v, c) = Wire.readVarint(valueBytes, pos); trigger = v.toInt(); pos += c }
                0x1d -> { if (pos + 4 > end) return@runCatching null; distance = Wire.readFixed32(valueBytes, pos); pos += 4 }
                0x25 -> { if (pos + 4 > end) return@runCatching null; kcps = Wire.readFixed32(valueBytes, pos); pos += 4 }
                0x2d -> { if (pos + 4 > end) return@runCatching null; uvTemp = Wire.readFixed32Float(valueBytes, pos); pos += 4 }
                else -> {
                    val wireType = tag and 0x07
                    when (wireType) {
                        0 -> { val (_, c) = Wire.readVarint(valueBytes, pos); pos += c }
                        1 -> { if (pos + 8 > end) return@runCatching null; pos += 8 }
                        5 -> { if (pos + 4 > end) return@runCatching null; pos += 4 }
                        2 -> {
                            val (l, c) = Wire.readVarint(valueBytes, pos)
                            pos += c
                            if (l !in 0..(end - pos).toLong()) return@runCatching null
                            pos += l.toInt()
                        }
                        else -> return@runCatching null
                    }
                }
            }
        }

        if (ts == 0L && distance == 0 && kcps == 0) return@runCatching null
        SipEntry(ts, trigger, distance, kcps, uvTemp)
    }.getOrNull()

    fun parseHydroReminderSettings(bytes: ByteArray): HydroReminderSettings? = runCatching {
        var stateRaw = 0
        var start = 0
        var stop = 0
        var interval = 0
        var color: ByteArray? = null
        for (f in Wire.walkFields(unwrapField1(bytes))) {
            when (f.number) {
                1 -> if (f.wireType == 0) stateRaw = (f.value as Long).toInt()
                2 -> if (f.wireType == 5) start = (f.value as Long).toInt()
                3 -> if (f.wireType == 5) stop = (f.value as Long).toInt()
                4 -> if (f.wireType == 5) interval = (f.value as Long).toInt()
                5 -> if (f.wireType == 2) color = f.value as ByteArray
            }
        }
        HydroReminderSettings(HydroReminderState.from(stateRaw), stateRaw, start, stop, interval, color)
    }.getOrNull()

    fun parseDoNotDisturbSettings(bytes: ByteArray): DoNotDisturbSettings? = runCatching {
        var stateRaw = 0
        var start = 0
        var stop = 0
        var snoozeMin = 0
        for (f in Wire.walkFields(unwrapField1(bytes))) {
            when (f.number) {
                1 -> if (f.wireType == 0) stateRaw = (f.value as Long).toInt()
                2 -> if (f.wireType == 5) start = (f.value as Long).toInt()
                3 -> if (f.wireType == 5) stop = (f.value as Long).toInt()
                4 -> if (f.wireType == 5) snoozeMin = (f.value as Long).toInt()
            }
        }
        DoNotDisturbSettings(DoNotDisturbState.from(stateRaw), stateRaw, start, stop, snoozeMin)
    }.getOrNull()

    fun parseLowBatterySettings(bytes: ByteArray): LowBatterySettings? = runCatching {
        var alert = 0
        var lockout = 0
        for (f in Wire.walkFields(unwrapField1(bytes))) {
            when (f.number) {
                1 -> if (f.wireType == 5) alert = (f.value as Long).toInt()
                2 -> if (f.wireType == 5) lockout = (f.value as Long).toInt()
            }
        }
        LowBatterySettings(alert, lockout)
    }.getOrNull()

    fun parseTofSettings(bytes: ByteArray): TofSettings? = runCatching {
        var modeRaw = 0
        var intervalEnabled = false
        var triggerInterval = 0
        var accelCheck = 0
        var bottleSize = 0
        var accelTimeout = 0
        for (f in Wire.walkFields(unwrapField1(bytes))) {
            when (f.number) {
                1 -> if (f.wireType == 0) modeRaw = (f.value as Long).toInt()
                2 -> if (f.wireType == 0) intervalEnabled = (f.value as Long) != 0L
                3 -> if (f.wireType == 5) triggerInterval = (f.value as Long).toInt()
                4 -> if (f.wireType == 5) accelCheck = (f.value as Long).toInt()
                5 -> if (f.wireType == 5) bottleSize = (f.value as Long).toInt()
                6 -> if (f.wireType == 5) accelTimeout = (f.value as Long).toInt()
            }
        }
        TofSettings(
            mode = TofDisplayMode.from(modeRaw),
            modeRaw = modeRaw,
            isIntervalTriggerEnabled = intervalEnabled,
            triggerIntervalInSec = triggerInterval,
            accelerometerCheckIntervalInSec = accelCheck,
            bottleSizeInMilliliter = bottleSize,
            accelerometerTimeoutInSec = accelTimeout,
        )
    }.getOrNull()

    fun parseUvConfig(bytes: ByteArray): UvConfig? = runCatching {
        var maintenance: UvParameters? = null
        var standard: UvParameters? = null
        var adventure: UvParameters? = null
        for (f in Wire.walkFields(unwrapField1(bytes))) {
            if (f.wireType != 2) continue
            val params = UvParameters(f.value as ByteArray)
            when (f.number) {
                1 -> maintenance = params
                2 -> standard = params
                3 -> adventure = params
            }
        }
        UvConfig(maintenance, standard, adventure)
    }.getOrNull()

    /**
     * ResponseGetCapUiState is FLAT (no field-1 wrapper). Schema (from CapBle.java line 46694):
     *   field 1 varint state (CapEnumUiState)
     *   field 2 varint powerSavingMode
     */
    fun parseUiState(bytes: ByteArray): UiStateInfo? = runCatching {
        var stateRaw = 0
        var ps = 0
        for (f in Wire.walkFields(bytes)) {
            when (f.number) {
                1 -> if (f.wireType == 0) stateRaw = (f.value as Long).toInt()
                2 -> if (f.wireType == 0) ps = (f.value as Long).toInt()
            }
        }
        UiStateInfo(UiStateEnum.from(stateRaw), stateRaw, ps)
    }.getOrNull()
}

// ── Display helpers ───────────────────────────────────────────────────────────

/** Format seconds-since-midnight as "HH:mm". Wraps gracefully for out-of-range input. */
internal fun formatHM(secondsFromMidnight: Int): String {
    val total = ((secondsFromMidnight % 86400) + 86400) % 86400
    val h = total / 3600
    val m = (total % 3600) / 60
    return "%02d:%02d".format(h, m)
}

/**
 * Format a UTC seconds-since-midnight time range as "HH:mm–HH:mm" (local) followed by
 * " (HH:mm–HH:mm UTC)" if the device's timezone differs from UTC.
 * The bottle stores all times in UTC; this surfaces both for clarity.
 */
internal fun formatHMRange(startSecUtc: Int, endSecUtc: Int): String {
    val utcRange = "${formatHM(startSecUtc)}–${formatHM(endSecUtc)}"
    val offsetSec = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
    if (offsetSec == 0) return utcRange
    val localStart = ((startSecUtc + offsetSec) % 86400 + 86400) % 86400
    val localEnd = ((endSecUtc + offsetSec) % 86400 + 86400) % 86400
    val localRange = "${formatHM(localStart)}–${formatHM(localEnd)}"
    return "$localRange ($utcRange UTC)"
}

/** Format a duration in seconds as a short string, e.g. "60min", "1h 30min", "45s". */
internal fun formatDurationShort(seconds: Int): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    if (m < 60) return "${m}min"
    val h = m / 60
    val rem = m % 60
    return if (rem == 0) "${h}h" else "${h}h ${rem}min"
}
