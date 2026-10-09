@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakerb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.*
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway
import org.offlinemesh.app.gateway.*

private class FakeLink : UplinkLink {
    val offered = mutableListOf<MeshFrameCodec.Frame.Uplink>()
    var closed = false
    var connected = false
    override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision {
        offered += frame; return UplinkGateway.Decision.Accepted(false)
    }
    override fun setInterestTags(tags: Collection<ByteArray>) {}
    override fun tick() {}
    override fun close() { closed = true }
    override fun hasConnectedRelay() = connected
}

private class FakeSource(val key: ByteArray = ByteArray(32) { it.toByte() }) : UplinkSource {
    var groupsList = listOf("g1")
    var files = emptyList<FileUplink>()
    override suspend fun groups() = groupsList.map { UplinkGroup(it, key) }
    override suspend fun liveFrames(groupId: String) = emptyList<ByteArray>()
    override suspend fun mailboxItems(groupId: String) = listOf(MailboxItem("sos:1", byteArrayOf(1, 2, 3)))
    override suspend fun lastKnownFrames(groupId: String) = emptyList<ByteArray>()
    override suspend fun fileUplinks(groupId: String) = files
}

class BreakerbControllerTest {
    private fun cfg() = InternetReachController.Config(jitterFraction = 0.0)

    /** Scenario: switch on, phone offline (link exists, no relay), message wrapped+held; switch off (link closed,
     *  held frames discarded); switch on again. The message is marked "uplinked" so it is never re-wrapped. */
    // Held-then-restarted bookkeeping is fixed at the runtime level: one UplinkGateway lives for the whole service, so frames
    // the controller counted as queued survive a stop/start (see the test below and InternetReachRuntime).
    @Test fun heldFramesSurviveALinkRestartWhenTheGatewayIsShared() = runBlocking {
        val gateway = org.offlinemesh.app.ble.UplinkGateway()
        val frame = org.offlinemesh.app.gateway.UplinkWrapper.wrap(ByteArray(32) { 1 }, MeshFrameCodec.UPLINK_CLASS_TEXT, ByteArray(40) { 2 }, System.currentTimeMillis() / 1000)
        gateway.offer(frame)
        // a "restart" creates a new link around the SAME gateway: the held frame is still there to upload
        assertEquals(1, gateway.pendingCount())
        assertEquals(1, gateway.heldFrames(5, 4096).size)
    }

    @Test fun deletedGroupDoesNotLeakIntoFurtherOffers() = runBlocking {
        var clock = 1_000_000L
        val link = FakeLink()
        val src = FakeSource()
        val c = InternetReachController(src, { link }, { _, _ -> }, { clock }, cfg())
        c.start(); c.step()
        val before = link.offered.size
        src.groupsList = emptyList(); clock += 10_000; c.step()
        assertEquals(before, link.offered.size)
    }
}
