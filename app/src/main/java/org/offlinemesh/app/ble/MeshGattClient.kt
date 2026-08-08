package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT client role: we connect out to peers we heard beaconing and push our data to them,
 * subscribing to their notifications so their push comes back over the same connection.
 *
 * **Invariant: every outbound GATT operation on a connection — writes, the CCCD descriptor write
 * included — is serialized through [writeQueue] against that connection's address**, so nothing
 * goes out until the previous operation's completion callback has actually fired. `BluetoothGatt`
 * allows exactly one outstanding operation per connection, of any kind; see `docs/DECISIONS.md`,
 * decision 2, for the live-tested asymmetry ("I can see them but can't send") that not having this
 * produced.
 *
 * **Links are now PERSISTENT (PLAN-v2.md P3, docs/DECISIONS.md decision 19).** A connection that
 * reaches [heldConnections] stays open — no fixed idle/max timer cuts it — until either
 * [LinkSelector] decides a newly-heard candidate is diverse enough to evict it for (only considered
 * once every [maxConcurrentClientConnections] slot is already in use), it fails on its own, or it
 * hits [BleTuning.Profile.connectionBackstopMs], a distant safety net (minutes, not seconds) against
 * a bug in the eviction path monopolising a slot forever. This is what lets
 * [RelayResponder]'s P1 flood-forward (§5.3) actually use an open link the moment new content
 * arrives, instead of that content waiting for the link's own next reconnect cycle — see the P1+P3
 * simulator finding (decisions 16-17) that a link only being open ~15-20s of every ~60-65s cycle,
 * not P1's own forwarding logic, was the real bottleneck.
 */
class MeshGattClient(
    private val context: Context,
    private val responder: RelayResponder,
    private val serviceScope: CoroutineScope,
    private val currentTier: () -> MeshService.PowerTier,
    // Same shared instance RelayResponder writes to (PLAN-v2.md §5.2 / P0b) — see
    // PeerIdentityResolver's class doc. Read only here.
    private val peerIdentity: PeerIdentityResolver,
    // Shared with MeshGattServer and RelayResponder (PLAN-v2.md P1 §5.3) — see
    // ConnectionRegistry's class doc.
    private val connectionRegistry: ConnectionRegistry,
) {
    private val writeQueue = GattOperationQueue()
    private val maxConcurrentClientConnections = 3
    private val reconnectCooldownMs = 45_000L
    // Peer-selection lever (see ConnectionAttemptTracker's class doc) — DELIBERATELY NEUTRALIZED
    // (equal to reconnectCooldownMs, i.e. no behavior change): a longer cooldown here for an
    // already-synced peer once broke radar freshness (position dots refresh only via GATT
    // reconnect) — see docs/DECISIONS.md, decision 5, before raising this above 90s, and re-derive
    // any future value from the shorter of PositionTracker's/HopTracker's staleness windows, not
    // picked independently.
    private val syncedReconnectCooldownMs = reconnectCooldownMs
    private val connectTimeoutMs = 15_000L
    // Absolute ceiling on how long a connection may stay stuck in SETUP (not yet in
    // heldConnections) before it's reclaimed as hung — see the second watchdog in maybeConnect.
    // Comfortably above real MTU/discovery/CCCD setup time; unrelated to how long a connection is
    // allowed to stay HELD once it gets there (BleTuning.Profile.connectionBackstopMs, now minutes
    // — see decision 19), which the watchdog explicitly excludes via the heldConnections check.
    private val connectionHardDeadlineMs = CONNECTION_HARD_DEADLINE_MS
    // The connection-attempt state machine itself lives in ConnectionAttemptTracker (unit-tested in
    // isolation there) — this class only owns the real connectGatt() call, the real timeout delay,
    // and closing the gatt object.
    private val attemptTracker = ConnectionAttemptTracker(
        maxConcurrentClientConnections, reconnectCooldownMs, syncedReconnectCooldownMs,
        currentEpoch = { responder.catalogEpoch }
    )
    // Negotiated MTU per peer address, recorded in onMtuChanged — see pushOnConnect's use of it to
    // size RelayResponder.framesToPushOnConnect's catalog-filter-vs-eager-push decision to what
    // this specific connection can actually carry in one write. Cleared on disconnect alongside
    // lastActivity/writeQueue, same reasoning: a BLE address rotates every ~15min, so this isn't a
    // stable identity worth remembering past one connection's lifetime.
    private val negotiatedMtu = ConcurrentHashMap<String, Int>()
    // Set once pushOnConnect actually ran for this connection (i.e. we got far enough to offer our
    // content, not necessarily that every frame succeeded) — read once by onConnectionStateChange's
    // disconnect branch to pick which of the two cooldowns above applies, then cleared either way.
    private val syncedThisSession = ConcurrentHashMap.newKeySet<String>()

    // Guards onServicesDiscovered's setup (registration + pushOnConnect + periodicRefresh) against
    // running more than once for the same connection. Some Android BLE stacks re-fire onMtuChanged
    // (which unconditionally calls discoverServices()) without an intervening disconnect, so
    // onServicesDiscovered itself can fire two or three times a second or so apart for one physical
    // link — harmless when connections were short-lived (pre-P3), but now spawns duplicate
    // periodicRefresh loops that live and independently re-push frames for the connection's entire
    // persistent lifetime. Identity-keyed (add() is atomic — exactly "run this once per gatt");
    // cleaned up on disconnect below since a BluetoothGatt object isn't reused across reconnects.
    private val handledGatts = ConcurrentHashMap.newKeySet<BluetoothGatt>()

    // Which attempt is currently the live one for each address. Both watchdogs below capture the
    // value at launch and bail if it has moved on — without that, a watchdog scheduled by an
    // EARLIER attempt to the same address fires while a LATER, perfectly healthy connection is in
    // flight, sees it still tracked, and tears it down. Confirmed live: "synced ok" immediately
    // followed by "hung past deadline" for the same peer ~1.5s later, repeatedly, each one costing
    // one of only maxConcurrentClientConnections slots for a full deadline period.
    private val attemptGeneration = ConcurrentHashMap<String, Long>()
    private val attemptCounter = java.util.concurrent.atomic.AtomicLong(0)

    // The attemptTracker key actually used for the address's CURRENT attempt, captured once at
    // attemptStarted and reused for every later callback of that same connection — see
    // PeerIdentityResolver's class doc. Deliberately NOT re-resolved on every call: if
    // peerIdentity.learn() fires mid-connection (a presence frame arriving on THIS connection),
    // re-resolving later callbacks would ask attemptTracker to end a key attemptStarted never
    // actually started, leaking the original key's `connecting` entry forever. Bounded by
    // maxConcurrentClientConnections in practice (one entry per address currently being dialled
    // or connected), cleaned up on every path that ends an attempt below.
    private val activeTrackerKey = ConcurrentHashMap<String, String>()

    // A connection past CCCD-ready, held persistently — the actual "is this slot in use" truth
    // for eviction purposes (see maybeConnect/considerEvicting). Keyed the same as everywhere
    // else: the resolved identity once known, the raw address otherwise.
    private data class HeldConnection(val gatt: BluetoothGatt, val characteristic: BluetoothGattCharacteristic)
    private val heldConnections = ConcurrentHashMap<String, HeldConnection>()
    // Most recently heard RSSI per held peer — refreshed on every BeaconRadio sighting even while
    // already connected (see the early-return at the top of maybeConnect below), so a diversity
    // decision always uses a fresh reading, not whatever the signal happened to be at connect time.
    private val heldRssi = ConcurrentHashMap<String, Int>()

    @Synchronized
    @SuppressLint("MissingPermission")
    fun maybeConnect(device: BluetoothDevice, rssi: Int) {
        val addr = device.address
        val trackerKey = peerIdentity.resolve(addr)

        // Already holding this peer — refresh its RSSI for future diversity decisions, don't
        // re-attempt (attemptTracker.canAttempt would also refuse it, via the `connecting` set
        // never clearing for a persistent link, but checking heldConnections directly here is the
        // actual truth this class owns, not an implicit side effect of that).
        if (heldConnections.containsKey(trackerKey)) {
            heldRssi[trackerKey] = rssi
            return
        }

        if (!attemptTracker.canAttempt(trackerKey)) {
            // Not admittable right now — either in cooldown (nothing to do about that) or every
            // slot is held. Only the "every slot held" case is worth a diversity check; a cooldown
            // means "we just tried this peer," not "our held set could be better."
            if (heldConnections.size >= maxConcurrentClientConnections) considerEvicting(device, trackerKey, rssi)
            return
        }
        attemptTracker.attemptStarted(trackerKey)
        activeTrackerKey[addr] = trackerKey
        val generation = attemptCounter.incrementAndGet()
        attemptGeneration[addr] = generation
        val gatt = device.connectGatt(context, false, callback)
        // Guard against connectGatt() never calling onConnectionStateChange at all — a real,
        // undocumented Android BLE failure mode (peer out of range mid-attempt, or Bluetooth
        // toggled while pending) that otherwise left a peer's address marked "connecting" forever
        // — see docs/DECISIONS.md, decision 5. If nothing has called back for this exact address
        // by the time this fires, force the cleanup ourselves instead of waiting forever.
        serviceScope.launch {
            delay(connectTimeoutMs)
            if (attemptGeneration[addr] != generation) return@launch // superseded by a newer attempt
            if (attemptTracker.isStuck(trackerKey)) {
                Log.w("MeshGattClient", "connect attempt to $addr never got a callback — forcing cleanup")
                try { gatt.close() } catch (_: Exception) {}
                DiagnosticsLog.event("conn", "timeout, no callback: ${addr.take(PEER_ID_LOG_CHARS)}")
                attemptTracker.connectionEnded(trackerKey)
                activeTrackerKey.remove(addr)
                return@launch
            }
            // Second watchdog, for the failure the first one structurally cannot see. Once
            // STATE_CONNECTED arrives, isStuck() goes false forever — so a connection that comes up
            // and then goes silent without ever firing STATE_DISCONNECTED (the same undocumented
            // class of BLE failure as decision 5, just one stage later) keeps its
            // maxConcurrentClientConnections slot AND its writeQueue entries indefinitely, and that
            // peer can never be reconnected to. With only 3 client slots, a few of these strand the
            // client role entirely. Deadline bounds SETUP time only (MTU/discovery/CCCD) — how long
            // a link may then stay HELD is a completely separate question (BleTuning's
            // connectionBackstopMs, now minutes) since P3, so this can only ever catch a connection
            // that never made it to heldConnections at all, never a legitimately long-lived one.
            delay(connectionHardDeadlineMs - connectTimeoutMs)
            if (attemptGeneration[addr] != generation) return@launch // superseded by a newer attempt
            if (attemptTracker.isTracked(trackerKey) && !heldConnections.containsKey(trackerKey)) {
                Log.w("MeshGattClient", "connection to $addr hung past its hard deadline — forcing cleanup")
                DiagnosticsLog.event("conn", "hung past deadline: ${addr.take(PEER_ID_LOG_CHARS)}")
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
                writeQueue.clear(addr)
                negotiatedMtu.remove(addr)
                attemptTracker.connectionEnded(trackerKey, synced = syncedThisSession.remove(addr))
                // Defensive, not expected: this branch already requires trackerKey NOT in
                // heldConnections, and registration/heldConnections-insertion happen together (see
                // onServicesDiscovered) — but a race between this check and that insertion isn't
                // provably impossible across coroutines, and unregister/remove on an absent key is
                // a harmless no-op either way.
                connectionRegistry.unregister(trackerKey)
                heldConnections.remove(trackerKey)
                heldRssi.remove(trackerKey)
                activeTrackerKey.remove(addr)
            }
        }
    }

    /** Every client slot is held — decide via the real [LinkSelector] whether [candidateRssi] is
     *  diverse enough over the current held set to be worth evicting the most redundant one for.
     *  PLAN-v2.md P3/§5.4: without this, a node would permanently lock onto whichever
     *  [maxConcurrentClientConnections] peers happened to connect first, the "first-heard clusters
     *  on whoever's nearest" failure mode §9.2 item 2 names — now that links no longer cycle every
     *  ~60s on their own, there is no other way for a held set to ever change. */
    @Synchronized
    private fun considerEvicting(candidate: BluetoothDevice, candidateKey: String, candidateRssi: Int) {
        val heldKeys = heldConnections.keys.toList()
        val heldRssiValues = heldKeys.map { (heldRssi[it] ?: return).toDouble() }
        val evictIndex = LinkSelector.evictionCandidate(heldRssiValues, candidateRssi.toDouble(), MIN_RSSI_SEPARATION)
            ?: return
        val evictKey = heldKeys[evictIndex]
        Log.i(
            "MeshGattClient",
            "evicting a held link for diversity: ${evictKey.take(PEER_ID_LOG_CHARS)} -> " +
                "${candidateKey.take(PEER_ID_LOG_CHARS)} (candidate RSSI ${candidateRssi}dBm)",
        )
        DiagnosticsLog.event("conn", "diversity evict: ${evictKey.take(PEER_ID_LOG_CHARS)}")
        disconnectHeld(evictKey)
        maybeConnect(candidate, candidateRssi) // slot is free now — retry immediately
    }

    /** Proactively tears down a held connection (full cleanup done here, not left to
     *  onConnectionStateChange — same defensive-cleanup precedent as the hard-deadline watchdog,
     *  since a `disconnect()` call is not guaranteed to fire its callback promptly, or at all). */
    @SuppressLint("MissingPermission")
    private fun disconnectHeld(trackerKey: String) {
        val held = heldConnections.remove(trackerKey) ?: return
        heldRssi.remove(trackerKey)
        connectionRegistry.unregister(trackerKey)
        try { held.gatt.disconnect() } catch (_: Exception) {}
        try { held.gatt.close() } catch (_: Exception) {}
        writeQueue.clear(held.gatt.device.address)
        negotiatedMtu.remove(held.gatt.device.address)
        activeTrackerKey.remove(held.gatt.device.address)
        attemptTracker.connectionEnded(trackerKey, synced = syncedThisSession.remove(held.gatt.device.address))
    }

    /** Tears down every currently held connection — called when the mesh is toggled off
     *  (`MeshService.setMeshActive(false)`). Before P3, a connection this old assumption relied on
     *  ("it'll idle out within ~[BleTuning.Profile.connectionBackstopMs]'s old ~20s value on its
     *  own") is no longer true — a persistent link can now live for minutes, so toggling off needs
     *  to actively close what's open rather than wait it out. */
    fun disconnectAll() {
        for (key in heldConnections.keys.toList()) disconnectHeld(key)
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        writeQueue.run(gatt.device.address) {
            // Bucket-padded here, not by the caller — every outgoing frame goes through this one
            // function, so this is the choke point where padding applies uniformly to all frame
            // types without each call site having to remember to pad. See padGattFrame's own doc.
            characteristic.value = MeshFrameCodec.padGattFrame(data)
            try { gatt.writeCharacteristic(characteristic) } catch (e: Exception) { false }
        }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        writeQueue.run(gatt.device.address) {
            try { gatt.writeDescriptor(descriptor) } catch (e: Exception) { false }
        }

    private suspend fun pushOnConnect(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        val mtu = negotiatedMtu[address] ?: MeshProtocol.DEFAULT_ATT_MTU
        val maxFrameBytes = mtu - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES
        for (bytes in responder.framesToPushOnConnect(maxFrameBytes, address)) {
            write(gatt, characteristic, bytes)
        }
        // Reaching here means we got through MTU negotiation, service discovery, and the CCCD write
        // well enough to actually offer our content — that's "synced" for cooldown purposes even if
        // an individual frame above failed; see syncedReconnectCooldownMs.
        syncedThisSession.add(address)
        DiagnosticsLog.event("conn", "synced ok: ${address.take(PEER_ID_LOG_CHARS)}")
    }

    /** Re-pushes presence/position every [BleTuning.Profile.presenceRefreshIntervalMs] for as
     *  long as [registryKey] stays in [heldConnections] — see `RelayResponder.refreshFramesToPush`
     *  / docs/DECISIONS.md decision 20. Loop, not a fixed count: a persistent link's actual
     *  lifetime is open-ended (diversity-evicted, fails, or the distant backstop), so this simply
     *  stops on its own the moment the link is no longer held, checked at the top of every
     *  iteration rather than assumed from how long [awaitBackstop] happens to run. */
    private suspend fun periodicRefresh(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, registryKey: String,
    ) {
        while (heldConnections.containsKey(registryKey)) {
            delay(BleTuning.forTier(currentTier()).presenceRefreshIntervalMs)
            if (!heldConnections.containsKey(registryKey)) return
            for (bytes in responder.refreshFramesToPush(registryKey)) {
                write(gatt, characteristic, bytes)
            }
        }
    }

    /** The distant safety-net backstop only — see [BleTuning.Profile.connectionBackstopMs]'s doc.
     *  A healthy persistent link is expected to still be held when this returns; the caller
     *  disconnects unconditionally, same as it always did at the old (much shorter) cap. */
    private suspend fun awaitBackstop() {
        delay(BleTuning.forTier(currentTier()).connectionBackstopMs)
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            // Falls back to the raw address if, somehow, this callback fires after maybeConnect's
            // own cleanup already removed the entry (e.g. racing the hard-deadline watchdog) —
            // matches ConnectionAttemptTracker's own graceful handling of an address it never
            // tracked in the first place.
            val trackerKey = activeTrackerKey[address] ?: address
            attemptTracker.callbackReceived(trackerKey)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                attemptTracker.connectionEnded(trackerKey, synced = syncedThisSession.remove(address))
                connectionRegistry.unregister(trackerKey) // same key register() used — see its call site
                heldConnections.remove(trackerKey)
                heldRssi.remove(trackerKey)
                activeTrackerKey.remove(address)
                writeQueue.clear(address)
                negotiatedMtu.remove(address)
                handledGatts.remove(gatt)
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Only record a genuinely successful negotiation — on failure, pushOnConnect's fallback
            // to MeshProtocol.DEFAULT_ATT_MTU (the safe, pre-negotiation floor) is more honest than
            // trusting whatever value a failed request happened to report.
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu[gatt.device.address] = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Some stacks re-fire this (via a duplicate onMtuChanged) without a real disconnect in
            // between — see handledGatts' doc. Second and later firings for the same link are a
            // no-op here.
            if (!handledGatts.add(gatt)) return
            val characteristic = gatt.getService(MeshProtocol.SERVICE_UUID)
                ?.getCharacteristic(MeshProtocol.RELAY_CHAR_UUID) ?: run { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(characteristic, true)
            responder.resetSessionBudget(gatt.device.address)
            serviceScope.launch {
                val cccd = characteristic.getDescriptor(MeshGattServer.CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    // Must complete before any data write goes out on this connection — see the
                    // class doc above for the bug this fixes.
                    writeDescriptor(gatt, cccd)
                }
                // Registered/held as soon as the link can actually accept a write — see
                // ConnectionRegistry's class doc. Reuses activeTrackerKey (captured once at
                // attemptStarted, see that field's doc) rather than a fresh peerIdentity.resolve()
                // here — the same "resolve once, reuse for this attempt's whole lifecycle" reason:
                // if identity gets learned between attempt-start and this point, a fresh resolve
                // here would register under a DIFFERENT key than onConnectionStateChange's
                // disconnect branch would later try to unregister, leaking the entry.
                val registryKey = activeTrackerKey[gatt.device.address] ?: gatt.device.address
                heldConnections[registryKey] = HeldConnection(gatt, characteristic)
                connectionRegistry.register(registryKey) { bytes -> write(gatt, characteristic, bytes) }
                pushOnConnect(gatt, characteristic)
                serviceScope.launch { periodicRefresh(gatt, characteristic, registryKey) }
                // From here the link is persistent (see class doc) — this suspends until the
                // distant backstop, not an idle timer; disconnectHeld/considerEvicting are what
                // normally end a healthy link before that, from outside this coroutine entirely.
                awaitBackstop()
                try { gatt.disconnect() } catch (_: Exception) {}
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeQueue.complete(gatt.device.address, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            writeQueue.complete(gatt.device.address, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val address = gatt.device.address
            val frame = MeshFrameCodec.unpadGattFrame(characteristic.value) ?: return
            serviceScope.launch {
                responder.handleIncoming(frame, address) { respBytes -> write(gatt, characteristic, respBytes) }
            }
        }
    }

    private companion object {
        // Only this much of a peer address goes into the exportable diagnostics log — see
        // DiagnosticsLog's class doc on why full identifiers are never written to disk.
        const val PEER_ID_LOG_CHARS = 8

        // See connectionHardDeadlineMs above for what this bounds and why it's this far out.
        const val CONNECTION_HARD_DEADLINE_MS = 60_000L

        // How far apart (dBm) a candidate's RSSI must be from EVERY currently-held link's before
        // it's considered a real diversity gain worth evicting for — see considerEvicting. RSSI
        // in real BLE hardware is noisy sample to sample; too small a threshold would evict/re-
        // admit the same pair of peers back and forth on measurement noise alone. 15 dB is a
        // conservative, clearly-distinct-signal-band gap, not derived from live measurement (no
        // hardware available in this environment) — a real 3-phone/crowd pass should tune this.
        const val MIN_RSSI_SEPARATION = 15.0
    }
}
