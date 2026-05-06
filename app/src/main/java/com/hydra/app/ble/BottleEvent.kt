package com.hydra.app.ble

/**
 * Typed events emitted by [BottleConnection.events] for the activity feed.
 * Lets the UI render meaningful "Sent X → Received Y" lines instead of raw hex.
 */
sealed class BottleEvent {

    abstract val timestamp: Long

    /** App wrote a command to the bottle. */
    data class CommandSent(
        override val timestamp: Long,
        val typeUrl: String,
        val seq: Int,
    ) : BottleEvent()

    /** Bottle returned a status-only ACK with no body (e.g. SetCapTime success). */
    data class Ack(
        override val timestamp: Long,
        val seq: Int?,
        val statusCode: Long,
    ) : BottleEvent()

    /** Bottle returned a parsed sip log entry (one per notification). */
    data class SipReceived(
        override val timestamp: Long,
        val seq: Int?,
        val entry: SipEntry,
    ) : BottleEvent()

    /** Bottle returned a settings/config response that we parsed and pre-formatted for display. */
    data class SettingsReceived(
        override val timestamp: Long,
        val seq: Int?,
        val typeUrl: String,
        /** Short label like "Reminder" or "DND". */
        val label: String,
        /** Pre-formatted human-readable summary. */
        val summary: String,
    ) : BottleEvent()

    /**
     * Bottle returned a framed response we don't have a typed parser for.
     * [fields] is the result of generic Wire.walkFields() — empty if the payload was empty
     * or not parseable as protobuf.
     */
    data class UnparsedResponse(
        override val timestamp: Long,
        val seq: Int?,
        val typeUrl: String,
        val valueLen: Int,
        val fields: List<ProtoField> = emptyList(),
    ) : BottleEvent()

    /** Bottle returned an error (status != 1, or raw error blob like 10 02). */
    data class ProtocolError(
        override val timestamp: Long,
        val seq: Int?,
        val statusCode: Long?,
        val rawBytes: ByteArray,
    ) : BottleEvent()

    /** A standard BLE characteristic read (battery, device info, etc). */
    data class StandardRead(
        override val timestamp: Long,
        val charName: String,
        val value: String,
    ) : BottleEvent()

    /** Bottle notification arrived but didn't match any known shape. */
    data class Unknown(
        override val timestamp: Long,
        val rawBytes: ByteArray,
    ) : BottleEvent()

    /** Multi-page sip-log sync started (auto on Ready or manual via Get sip log). */
    data class SyncStarted(
        override val timestamp: Long,
        val sinceSec: Long,
    ) : BottleEvent()

    /** A page of sip entries finished arriving. */
    data class SyncProgress(
        override val timestamp: Long,
        val page: Int,
        val received: Int,
    ) : BottleEvent()

    /** Sync finished — bottle returned an empty page. */
    data class SyncFinished(
        override val timestamp: Long,
        val totalReceived: Int,
    ) : BottleEvent()
}
