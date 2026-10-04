package org.offlinemesh.app.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * Every currently-open connection this device holds, from EITHER GATT role — [MeshGattClient]'s
 * outbound connections and [MeshGattServer]'s inbound ones — the shared view [RelayResponder]
 * needs to flood-forward a newly-arrived frame across every OTHER open link (PLAN-v2.md P1 §5.3),
 * not just respond on the one it arrived on. Neither GATT class has ever needed to know about the
 * other's connections before this; this is the first place that view exists.
 *
 * Each side registers a lightweight push callback for its own connections and unregisters on
 * disconnect — the registry has no idea whether a callback writes a GATT characteristic (client
 * role) or notifies one (server role, with its own cross-peer-notify-race handling — see
 * [MeshGattServer]'s class doc), only that calling it attempts to deliver bytes to that peer.
 *
 * Keyed the same way [PeerIdentityResolver] resolves — a stable identity once known, the raw
 * address otherwise — so [RelayResponder]'s split-horizon exclusion (never hand a frame straight
 * back to whoever just sent it) and [ForwardingPolicy]'s open-link-count signal both see one
 * consistent count regardless of which GATT role registered a given link.
 */
class ConnectionRegistry {
    fun interface Push {
        suspend fun send(bytes: ByteArray): Boolean
    }

    private val connections = ConcurrentHashMap<String, Push>()

    /** Call once a connection to [peerKey] is fully up (post MTU/CCCD setup) and ready to accept
     *  writes — the same readiness point each GATT class already uses for `pushOnConnect`. */
    fun register(peerKey: String, push: Push) {
        connections[peerKey] = push
    }

    fun unregister(peerKey: String) {
        connections.remove(peerKey)
    }

    fun openLinkCount(): Int = connections.size

    /** Every currently-open connection except [excludePeerKey] (split horizon), as a snapshot —
     *  safe to iterate even if the live registry mutates while a caller is still working through
     *  the returned map. */
    fun others(excludePeerKey: String?): Map<String, Push> =
        if (excludePeerKey == null) connections.toMap() else connections.filterKeys { it != excludePeerKey }
}
