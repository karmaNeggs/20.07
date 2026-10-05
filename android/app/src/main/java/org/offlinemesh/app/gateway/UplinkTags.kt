package org.offlinemesh.app.gateway

import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.crypto.CryptoUtils

/**
 * Relay topics for a group (`PLAN-v2.md` §13.2, §13.15), derived from the group's root key so only members
 * can compute them and everyone else sees opaque, rotating values.
 *
 * **LIVE** frames use the same 60 s HMAC the BLE beacon already advertises. **Mailbox** frames (texts,
 * last-known positions) use a 1 h HMAC so a recipient who connects later has only a few tags to ask for.
 * The window numbers cannot collide across purposes: 60 s windows are about 2.7e7-6.8e7, 1 h windows
 * about 4.4e5-1.1e6, 24 h content epochs about 1.8e4-4.7e4, 72 h handles about 6e3-1.6e4 (2020-2100).
 */
object UplinkTags {
    const val LIVE_WINDOW_SEC = CryptoUtils.ID_WINDOW_SECONDS
    const val MAILBOX_WINDOW_SEC = 3600L
    const val MAILBOX_LOOKBACK_HOURS = 24

    fun liveTag(groupKey: ByteArray, epochSec: Long): ByteArray =
        CryptoUtils.rotatingAdvertisementId(groupKey, epochSec, LIVE_WINDOW_SEC)

    fun mailboxTag(groupKey: ByteArray, epochSec: Long): ByteArray =
        CryptoUtils.rotatingAdvertisementId(groupKey, epochSec, MAILBOX_WINDOW_SEC)

    /** The tag to publish a frame of [cls] under at [nowSec]. */
    fun tagFor(groupKey: ByteArray, cls: Int, nowSec: Long): ByteArray =
        if (cls == MeshFrameCodec.UPLINK_CLASS_LIVE) liveTag(groupKey, nowSec) else mailboxTag(groupKey, nowSec)

    /** Every tag a member should listen on: this and the previous 60 s window (clock skew and in-flight
     *  frames) plus the current and last [MAILBOX_LOOKBACK_HOURS] hourly mailbox windows. */
    fun interestTags(groupKey: ByteArray, nowSec: Long): List<ByteArray> {
        val live = listOf(liveTag(groupKey, nowSec), liveTag(groupKey, nowSec - LIVE_WINDOW_SEC))
        val mailbox = (0..MAILBOX_LOOKBACK_HOURS).map { mailboxTag(groupKey, nowSec - it * MAILBOX_WINDOW_SEC) }
        return live + mailbox
    }
}

/** Wraps one already-sealed frame for the relay uplink. Pure: the caller supplies key, class and clock. */
object UplinkWrapper {
    fun wrap(groupKey: ByteArray, cls: Int, inner: ByteArray, nowSec: Long): MeshFrameCodec.Frame.Uplink =
        MeshFrameCodec.Frame.Uplink(UplinkTags.tagFor(groupKey, cls, nowSec), cls, 0, nowSec, inner)

    /** The sealed frame inside an uplink that came from the relay, or null if it is not a valid uplink. */
    fun unwrap(encodedUplink: ByteArray): ByteArray? =
        (MeshFrameCodec.decode(encodedUplink) as? MeshFrameCodec.Frame.Uplink)?.inner
}
