package org.offlinemesh.app.ble

/**
 * Every WiFi Direct timing/size constant, in one place — mirrors [BleTuning]'s own role for the
 * BLE radios, but kept as a separate file rather than folded into it: [BleTuning]'s own class doc
 * frames itself as "every Bluetooth frequency/power/timing knob," and mixing WiFi-specific
 * constants into a doc-and-purpose-scoped BLE object would blur that scope. Keeping this feature's
 * tuning fully separate also matches how [BleCapabilities]/[TrickleTimer] exist as their own files
 * despite serving only [BeaconRadio] — an independent, trivially removable unit, which matters here
 * more than usual: see [WifiDirectAccelerator]'s class doc for why this whole feature is
 * NOT device-tested.
 */
object WifiDirectTuning {
    /** Only worth WFD's multi-second group-formation overhead when the deficit meaningfully
     *  exceeds what fits in a single BLE session's chunk budget
     *  ([RelayResponder]'s `maxChunksPerSession` = 150 chunks * [RelayEngine.CHUNK_SIZE] = 400
     *  bytes = 60,000 bytes/session). Set at 3x that so WFD only triggers when BLE would otherwise
     *  need multiple reconnect cycles to finish this one item. */
    const val MIN_DEFICIT_BYTES_FOR_HANDOFF = 180_000L

    /** One deadline covering the whole handoff — discovery, connect, socket, token handshake, and
     *  transfer — matching [ConnectionAttemptTracker]'s "one timeout, not a per-stage state
     *  machine" simplicity rather than a more elaborate phase-by-phase budget. */
    const val OVERALL_HANDOFF_TIMEOUT_MS = 20_000L

    /** How long each side waits, after the raw socket connects, for the other side's token bytes
     *  to arrive before giving up — see [WifiDirectAccelerator]'s doc on why nothing is trusted on
     *  the socket before this handshake completes. */
    const val TOKEN_HANDSHAKE_TIMEOUT_MS = 4_000L

    const val SOCKET_PORT = 8988

    /** Gap between sending/receiving Accept and actually calling `WifiP2pManager` APIs — gives
     *  both sides a shared target instant to start near-simultaneously rather than racing each
     *  other into `connect()`. */
    const val NEGOTIATION_LEAD_MS = 1_500L
}
