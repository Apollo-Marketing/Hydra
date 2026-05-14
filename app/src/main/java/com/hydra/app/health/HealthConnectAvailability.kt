package com.hydra.app.health

/**
 * Whether Health Connect can accept writes on this device right now. Distinct from "the user
 * has granted permission" — that's tracked separately in [HealthConnectGateway.hasPermissions].
 *
 * The SDK's underlying status int collapses several real-world cases into one value; we split
 * them back out here so the Settings UI can show actionable text ("Install Health Connect" vs
 * "Update Health Connect" vs "Not supported on this device").
 */
sealed class HealthConnectAvailability {
    /** Health Connect is installed at a compatible version and the SDK can talk to it. */
    data object Available : HealthConnectAvailability()

    /**
     * Health Connect isn't installed. Most common on API 31–33 where Health Connect ships
     * as a Play-Store APK the user has to install themselves; on API 34+ this is rare
     * (the Mainline module is preinstalled).
     */
    data object NotInstalled : HealthConnectAvailability()

    /** Installed but older than what our connect-client version expects. User must update. */
    data object UpdateRequired : HealthConnectAvailability()

    /** Device variant doesn't support Health Connect at all (rare — e.g. some Android Go builds). */
    data object Unsupported : HealthConnectAvailability()
}
