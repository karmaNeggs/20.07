package org.offlinemesh.app.gateway

import org.offlinemesh.app.ble.RelayResponder
import org.offlinemesh.app.data.GroupRepository

/** Feeds [InternetReachController] from the app's real groups and its normal frame machinery. */
class ResponderUplinkSource(
    private val repo: GroupRepository,
    private val responder: RelayResponder,
) : UplinkSource {

    override suspend fun groups(): List<UplinkGroup> =
        repo.groupDao.getActiveGroups().mapNotNull { g -> repo.getGroupKey(g.id)?.let { UplinkGroup(g.id, it) } }

    override suspend fun liveFrames(groupId: String): List<ByteArray> = responder.uplinkLiveFrames(groupId)

    override suspend fun fileUplinks(groupId: String): List<FileUplink> =
        responder.uplinkEvidenceHeads(groupId).map { (id, meta, wanted) ->
            FileUplink(id, meta, wanted) { count -> responder.uplinkSymbols(id, count) }
        }

    override suspend fun lastKnownFrames(groupId: String): List<ByteArray> = responder.uplinkLastKnownFrames(groupId)

    override suspend fun mailboxItems(groupId: String): List<MailboxItem> =
        responder.uplinkMailboxItems(groupId).map { (id, frame, alert) -> MailboxItem(id, frame, alert) }
}
