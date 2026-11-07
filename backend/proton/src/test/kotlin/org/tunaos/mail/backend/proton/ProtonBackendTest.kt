package org.tunaos.mail.backend.proton

import app.k9mail.backend.testing.InMemoryBackendFolder
import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsOnly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncConfig.ExpungePolicy
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import java.util.EnumSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.proton.FakeProtonApi.Companion.protonError
import org.tunaos.proton.ProtonFolder
import org.tunaos.proton.ProtonFolderKind
import org.tunaos.proton.ProtonMessage
import org.tunaos.proton.ProtonSyncChanges

class ProtonBackendTest {
    private val storage = InMemoryBackendStorage()
    private val api = FakeProtonApi()
    private val backend = ProtonBackend(storage, api)
    private val listener = RecordingSyncListener()
    private val syncConfig = SyncConfig(
        expungePolicy = ExpungePolicy.IMMEDIATELY,
        earliestPollDate = null,
        syncRemoteDeletions = true,
        maximumAutoDownloadMessageSize = 1_000_000,
        defaultVisibleLimit = 25,
        syncFlags = EnumSet.of(Flag.SEEN, Flag.FLAGGED),
    )

    @BeforeTest
    fun setUp() {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
        Log.logger = TestLogger()
    }

    @Test
    fun `refreshFolderList mirrors system folders, user folders and labels`() {
        api.folders = FakeProtonApi.SYSTEM_FOLDERS + listOf(
            ProtonFolder(id = "work", label = "Work", kind = ProtonFolderKind.FOLDER),
            ProtonFolder(id = "clients", label = "Clients", parent = "work", kind = ProtonFolderKind.FOLDER),
            ProtonFolder(id = "red", label = "Urgent", kind = ProtonFolderKind.LABEL),
        )

        assertThat(backend.refreshFolderList()).isEqualTo("/")

        assertThat(storage.folders.mapValues { it.value.name to it.value.type }).isEqualTo(
            mapOf(
                "0" to ("Inbox" to FolderType.INBOX),
                "8" to ("Drafts" to FolderType.DRAFTS),
                "7" to ("Sent" to FolderType.SENT),
                "10" to ("Starred" to FolderType.REGULAR),
                "6" to ("Archive" to FolderType.ARCHIVE),
                "4" to ("Spam" to FolderType.SPAM),
                "3" to ("Trash" to FolderType.TRASH),
                "5" to ("All Mail" to FolderType.REGULAR),
                "work" to ("Work" to FolderType.REGULAR),
                "clients" to ("Work/Clients" to FolderType.REGULAR),
                "red" to ("Urgent" to FolderType.REGULAR),
            ),
        )
    }

    @Test
    fun `refreshFolderList renames changed folders and drops deleted ones`() {
        api.folders = FakeProtonApi.SYSTEM_FOLDERS + ProtonFolder(id = "work", label = "Work")
        backend.refreshFolderList()
        api.folders = FakeProtonApi.SYSTEM_FOLDERS + ProtonFolder(id = "work", label = "Job")
        backend.refreshFolderList()
        api.folders = FakeProtonApi.SYSTEM_FOLDERS
        backend.refreshFolderList()

        assertThat(storage.folders.keys).doesNotContain("work")
        api.folders = FakeProtonApi.SYSTEM_FOLDERS + ProtonFolder(id = "work", label = "Job")
        backend.refreshFolderList()
        assertThat(storage.getFolder("work").name).isEqualTo("Job")
    }

    @Test
    fun `create makes a user folder under its parent folder and mirrors it`() = runTest {
        api.folders = FakeProtonApi.SYSTEM_FOLDERS +
            ProtonFolder(id = "work", label = "Work", kind = ProtonFolderKind.FOLDER)

        val outcome = backend.create(FolderServerId("Work/Clients"), mustCreate = true, FolderType.ARCHIVE)

        assertThat(outcome).isEqualTo(Outcome.success(RemoteFolderCreationOutcome.Success.Created))
        val created = api.folders.last()
        assertThat(created.parent).isEqualTo("work")
        assertThat(created.kind).isEqualTo(ProtonFolderKind.FOLDER)
        assertThat(storage.getFolder(created.id).name).isEqualTo("Work/Clients")
    }

    @Test
    fun `create reports a folder or label already at the path`() = runTest {
        api.folders = FakeProtonApi.SYSTEM_FOLDERS + ProtonFolder(id = "red", label = "Urgent")

        val existing = backend.create(FolderServerId("Urgent"), mustCreate = false)
        val required = backend.create(FolderServerId("Urgent"), mustCreate = true)

        assertThat(existing).isEqualTo(Outcome.success(RemoteFolderCreationOutcome.Success.AlreadyExists))
        assertThat(required).isEqualTo(Outcome.failure(RemoteFolderCreationOutcome.Error.AlreadyExists))
    }

    @Test
    fun `create fails under a label, or when Proton refuses`() = runTest {
        api.folders = FakeProtonApi.SYSTEM_FOLDERS + ProtonFolder(id = "red", label = "Urgent")

        val underLabel = backend.create(FolderServerId("Urgent/Clients"), mustCreate = true)
        api.failure = protonError("folder_failed")
        val refused = backend.create(FolderServerId("Clients"), mustCreate = true)

        assertThat(underLabel).isInstanceOf<Outcome.Failure<RemoteFolderCreationOutcome.Error>>()
        assertThat(refused).isInstanceOf<Outcome.Failure<RemoteFolderCreationOutcome.Error>>()
        assertThat(api.folders.size).isEqualTo(FakeProtonApi.SYSTEM_FOLDERS.size + 1)
    }

    @Test
    fun `first sync downloads the newest visibleLimit messages with their flags`() {
        val inbox = createInbox(visibleLimit = 2)
        api.addMessage("m1", INBOX, dateMs = 1_000, isUnread = false)
        api.addMessage("m2", INBOX, dateMs = 2_000, isStarred = true)
        api.addMessage("m3", INBOX, dateMs = 3_000)

        backend.sync(INBOX, syncConfig, listener)

        assertThat(listener.isFinished).isTrue()
        assertThat(inbox.getMessageServerIds()).containsOnly("m3", "m2")
        assertThat(inbox.getMessageFlags("m2")).containsOnly(Flag.FLAGGED, Flag.X_DOWNLOADED_FULL)
        assertThat(inbox.getFolderExtraString(ProtonMessageSync.EXTRA_EVENT_ID)).isEqualTo("event-1")
        assertThat(inbox.getMoreMessages()).isEqualTo(MoreMessages.TRUE)
        assertThat(listener.newMessages).containsExactly("m3" to false, "m2" to false)
    }

    @Test
    fun `first sync pages until the visible limit and records the end of the folder`() {
        val inbox = createInbox(visibleLimit = 0)
        repeat(150) { api.addMessage("m$it", INBOX, dateMs = it.toLong()) }

        backend.sync(INBOX, syncConfig, listener)

        assertThat(inbox.getMessageServerIds().size).isEqualTo(150)
        assertThat(api.syncCalls.map { it.cursor }).containsExactly(null, "m50")
        assertThat(inbox.getMoreMessages()).isEqualTo(MoreMessages.FALSE)
    }

    @Test
    fun `first sync removes local messages that are gone from the server`() {
        val inbox = createInbox()
        inbox.createMessages("gone" to "/message.eml")
        api.addMessage("m1", INBOX, dateMs = 1_000)

        backend.sync(INBOX, syncConfig, listener)

        assertThat(inbox.getMessageServerIds()).containsOnly("m1")
        assertThat(listener.removedMessages).containsExactly("gone")
    }

    @Test
    fun `later sync replays events - new, changed, moved away and deleted`() {
        val inbox = createInbox()
        api.addMessage("keep", INBOX, dateMs = 1_000)
        api.addMessage("moved", INBOX, dateMs = 2_000)
        api.addMessage("deleted", INBOX, dateMs = 3_000)
        backend.sync(INBOX, syncConfig, listener)

        api.messages.remove("deleted")
        api.messages["moved"] = FakeProtonApi.header("moved", listOf(ARCHIVE), 2_000)
        api.messages["keep"] = FakeProtonApi.header("keep", listOf(INBOX), 1_000, isUnread = false, isStarred = true)
        val new = api.addMessage("new", INBOX, dateMs = 4_000)
        api.changes["event-1"] = "event-2" to ProtonSyncChanges(
            refresh = false,
            more = false,
            deleted = listOf("deleted"),
            updated = listOf(api.messages.getValue("keep"), api.messages.getValue("moved"), new),
        )

        backend.sync(INBOX, syncConfig, listener)

        assertThat(inbox.getMessageServerIds()).containsOnly("keep", "new")
        assertThat(inbox.getMessageFlags("keep")).containsOnly(Flag.SEEN, Flag.FLAGGED, Flag.X_DOWNLOADED_FULL)
        assertThat(listener.removedMessages).containsExactlyInAnyOrder("deleted", "moved")
        assertThat(listener.flagChanges).containsExactly("keep")
        assertThat(inbox.getFolderExtraString(ProtonMessageSync.EXTRA_EVENT_ID)).isEqualTo("event-2")
        assertThat(api.syncCalls.last().since).isEqualTo("event-1")
    }

    @Test
    fun `later sync follows more until the event log is drained`() {
        val inbox = createInbox()
        backend.sync(INBOX, syncConfig, listener)
        val first = api.addMessage("a", INBOX, dateMs = 1)
        val second = api.addMessage("b", INBOX, dateMs = 2)
        api.changes["event-1"] = "event-2" to ProtonSyncChanges(false, true, emptyList(), listOf(first))
        api.changes["event-2"] = "event-3" to ProtonSyncChanges(false, false, emptyList(), listOf(second))

        backend.sync(INBOX, syncConfig, listener)

        assertThat(inbox.getMessageServerIds()).containsOnly("a", "b")
        assertThat(inbox.getFolderExtraString(ProtonMessageSync.EXTRA_EVENT_ID)).isEqualTo("event-3")
    }

    @Test
    fun `a refresh demanded by the server falls back to a full sync`() {
        val inbox = createInbox()
        backend.sync(INBOX, syncConfig, listener)
        api.addMessage("m1", INBOX, dateMs = 1)
        api.eventId = "event-9"
        api.changes["event-1"] = "event-9" to ProtonSyncChanges(true, false, emptyList(), emptyList())

        backend.sync(INBOX, syncConfig, listener)

        assertThat(inbox.getMessageServerIds()).containsOnly("m1")
        assertThat(inbox.getFolderExtraString(ProtonMessageSync.EXTRA_EVENT_ID)).isEqualTo("event-9")
    }

    @Test
    fun `a larger visible limit pages further back from the stored cursor`() {
        val inbox = createInbox(visibleLimit = 2)
        repeat(5) { api.addMessage("m$it", INBOX, dateMs = it.toLong()) }
        backend.sync(INBOX, syncConfig, listener)
        listener.newMessages.clear()

        inbox.visibleLimit = 4
        backend.sync(INBOX, syncConfig, listener)

        assertThat(inbox.getMessageServerIds()).containsOnly("m4", "m3", "m2", "m1")
        assertThat(listener.newMessages).containsExactly("m2" to true, "m1" to true)
        assertThat(inbox.getMoreMessages()).isEqualTo(MoreMessages.TRUE)
    }

    @Test
    fun `a refused session fails the sync as an authentication failure`() {
        createInbox()
        api.failure = protonError("session_revoked")

        backend.sync(INBOX, syncConfig, listener)

        assertThat(listener.isFinished).isFalse()
        assertThat(listener.failure).isNotNull().isInstanceOf<AuthenticationFailedException>()
    }

    @Test
    fun `a message that cannot be decrypted is skipped, not fatal`() {
        val inbox = createInbox()
        api.addMessage("ok", INBOX, dateMs = 2)
        api.addMessage("broken", INBOX, dateMs = 1)
        val broken = object : ProtonApi by api {
            override suspend fun message(id: String): ProtonMessage =
                if (id == "broken") throw protonError("decryption_failed") else api.message(id)
        }

        ProtonBackend(storage, broken).sync(INBOX, syncConfig, listener)

        assertThat(listener.isFinished).isTrue()
        assertThat(inbox.getMessageServerIds()).containsOnly("ok")
    }

    @Test
    fun `setFlag maps seen to mark_read and flagged to star in batches of 50`() {
        val ids = List(120) { "m$it" }

        backend.setFlag(INBOX, ids, Flag.SEEN, true)
        backend.setFlag(INBOX, ids.take(3), Flag.FLAGGED, false)
        backend.setFlag(INBOX, ids, Flag.ANSWERED, true)

        assertThat(api.markReadCalls.map { it.first.size to it.second })
            .containsExactly(50 to true, 50 to true, 20 to true)
        assertThat(api.starCalls).containsExactly(ids.take(3) to false)
    }

    @Test
    fun `an outage outcome is a temporary failure, a refused id a permanent one`() {
        api.outcomeCodes["m1"] = "transport_failed"
        assertFailure { backend.setFlag(INBOX, listOf("m1"), Flag.SEEN, true) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isFalse()

        api.outcomeCodes["m1"] = "request_failed"
        assertFailure { backend.setFlag(INBOX, listOf("m1"), Flag.SEEN, true) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isTrue()
    }

    @Test
    fun `markAllAsRead marks every unread message in the folder`() {
        api.addMessage("read", INBOX, dateMs = 1, isUnread = false)
        api.addMessage("unread", INBOX, dateMs = 2)

        backend.markAllAsRead(INBOX)

        assertThat(api.markReadCalls).containsExactly(listOf("unread") to true)
    }

    @Test
    fun `searchStep asks Proton for the keyword and pages up to three pages or the wanted count`() {
        repeat(350) { api.addMessage("old-$it", INBOX, dateMs = 1_000L + it) }
        api.messages["new"] = FakeProtonApi.header("new", listOf(INBOX), dateMs = 9_000)
        api.addMessage("elsewhere", ARCHIVE, dateMs = 10_000)

        val firstStep = backend.searchStep(INBOX, " subject ", null, null, true, cursor = null, wanted = 200)

        assertThat(firstStep.messageServerIds.size).isEqualTo(200)
        assertThat(firstStep.messageServerIds.first()).isEqualTo("new")
        assertThat(firstStep.messageServerIds).doesNotContain("elsewhere")
        assertThat(firstStep.nextCursor).isEqualTo("200")
        assertThat(firstStep.searchedCount).isNull()
        assertThat(firstStep.isHeadersOnly).isTrue()
        assertThat(api.searchCalls.map { it.second }).containsExactly(null, "150")
        assertThat(api.searchCalls.first().first.keyword).isEqualTo("subject")
        assertThat(api.searchCalls.first().first.folder).isEqualTo(INBOX)

        val secondStep = backend.searchStep(
            folderServerId = INBOX,
            query = " subject ",
            requiredFlags = null,
            forbiddenFlags = null,
            performFullTextSearch = true,
            cursor = firstStep.nextCursor,
            wanted = 200,
        )

        assertThat(secondStep.messageServerIds.size).isEqualTo(151)
        assertThat(secondStep.nextCursor).isNull()
        assertThat(secondStep.isHeadersOnly).isTrue()
        assertThat(api.searchCalls.map { it.second }).containsExactly(null, "150", "200")
    }

    @Test
    fun `search runs the first step only`() {
        repeat(150) { api.addMessage("old-$it", INBOX, dateMs = 1_000L + it) }
        api.messages["new"] = FakeProtonApi.header("new", listOf(INBOX), dateMs = 9_000)

        val ids = backend.search(INBOX, " subject ", null, null, performFullTextSearch = true)

        assertThat(ids.first()).isEqualTo("new")
        assertThat(ids.size).isEqualTo(100)
    }

    @Test
    fun `search maps read and flagged requirements to Proton's unread and starred`() {
        api.addMessage("unread-starred", INBOX, dateMs = 3_000, isUnread = true, isStarred = true)
        api.addMessage("read-starred", INBOX, dateMs = 2_000, isUnread = false, isStarred = true)
        api.addMessage("unread", INBOX, dateMs = 1_000, isUnread = true)

        val ids = backend.search(INBOX, "subject", setOf(Flag.FLAGGED), setOf(Flag.SEEN), false)

        assertThat(ids).containsExactly("unread-starred")
        val query = api.searchCalls.single().first
        assertThat(query.isUnread).isEqualTo(true)
        assertThat(query.isStarred).isEqualTo(true)
    }

    @Test
    fun `search with nothing to look for asks nothing`() {
        assertThat(backend.search(INBOX, "  ", null, null, false)).isEmpty()
        assertThat(api.searchCalls).isEmpty()
    }

    @Test
    fun `a refused session fails the search as an authentication failure`() {
        api.failure = protonError("session_revoked")

        assertFailure { backend.search(INBOX, "subject", null, null, false) }
            .isInstanceOf<AuthenticationFailedException>()
    }

    @Test
    fun `move keeps the message ids`() {
        val ids = listOf("m1", "m2")

        val mapping = backend.moveMessagesAndMarkAsRead(INBOX, ARCHIVE, ids)

        assertThat(mapping).isEqualTo(mapOf("m1" to "m1", "m2" to "m2"))
        assertThat(api.moveCalls).containsExactly(ids to ARCHIVE)
        assertThat(api.markReadCalls).containsExactly(ids to true)
    }

    @Test
    fun `delete outside Trash moves to Trash`() {
        backend.deleteMessages(INBOX, listOf("m1"))

        assertThat(api.moveCalls).containsExactly(listOf("m1") to ProtonFolderIds.TRASH)
        assertThat(api.deleteCalls).isEmpty()
    }

    @Test
    fun `delete in Trash and Drafts is permanent`() {
        backend.deleteMessages(ProtonFolderIds.TRASH, listOf("m1"))
        backend.deleteMessages(ProtonFolderIds.DRAFTS, listOf("d1"))

        assertThat(api.deleteCalls).containsExactly(listOf("m1"), listOf("d1"))
        assertThat(api.moveCalls).isEmpty()
    }

    @Test
    fun `emptying Trash repeats until Proton reports nothing more`() {
        api.emptyRuns = 3

        backend.deleteAllMessages(ProtonFolderIds.TRASH)

        assertThat(api.emptyCalls).containsExactly("3", "3", "3")
    }

    @Test
    fun `only Trash and Spam can be emptied`() {
        assertFailure { backend.deleteAllMessages(INBOX) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isTrue()
    }

    @Test
    fun `moving into a label applies it and moving out of one removes it`() {
        withLabelFolder()

        backend.moveMessages(INBOX, LABEL, listOf("m1"))
        backend.moveMessages(LABEL, ARCHIVE, listOf("m2"))

        assertThat(api.labelCalls).containsExactly(
            Triple(listOf("m1"), LABEL, true),
            Triple(listOf("m2"), LABEL, false),
        )
        assertThat(api.moveCalls).containsExactly(listOf("m2") to ARCHIVE)
    }

    @Test
    fun `copy applies a label but cannot copy into a folder`() {
        withLabelFolder()

        assertThat(backend.copyMessages(INBOX, LABEL, listOf("m1"))).isEqualTo(mapOf("m1" to "m1"))
        assertThat(api.labelCalls).containsExactly(Triple(listOf("m1"), LABEL, true))
        assertFailure { backend.copyMessages(INBOX, ARCHIVE, listOf("m1")) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isTrue()
    }

    @Test
    fun `an append to Sent returns no id because Proton files the sent copy itself`() {
        assertThat(backend.uploadMessage(ProtonFolderIds.SENT, TestMessages.plainText())).isNull()
    }

    @Test
    fun `an append to Drafts saves a Proton draft and returns its id`() {
        val id = backend.uploadMessage(ProtonFolderIds.DRAFTS, TestMessages.plainText())

        assertThat(id).isEqualTo("draft-1")
        assertThat(api.drafts.single().subject).isEqualTo("Hello")
        assertThat(api.drafts.single().inReplyTo).isEqualTo("<parent@example.com>")
    }

    @Test
    fun `send hands the mapped mail to Proton`() {
        backend.sendMessage(TestMessages.plainText())

        assertThat(api.sent.single().subject).isEqualTo("Hello")
    }

    @Test
    fun `a send that may have gone out is not retried`() {
        api.failure = protonError("send_uncertain")

        assertFailure { backend.sendMessage(TestMessages.plainText()) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isTrue()
    }

    @Test
    fun `a network failure while sending is temporary`() {
        api.failure = protonError("transport_failed")

        assertFailure { backend.sendMessage(TestMessages.plainText()) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isFalse()
    }

    @Test
    fun `downloadMessage stores the full message`() {
        val inbox = createInbox()
        api.addMessage("m1", INBOX, dateMs = 1)

        backend.downloadMessage(syncConfig, INBOX, "m1")

        assertThat(inbox.getMessageFlags("m1")).containsOnly(Flag.X_DOWNLOADED_FULL)
        assertThat(api.messageCalls).containsExactly("m1")
    }

    private fun withLabelFolder() {
        api.folders = FakeProtonApi.SYSTEM_FOLDERS +
            ProtonFolder(id = LABEL, label = "Urgent", kind = ProtonFolderKind.LABEL)
        backend.refreshFolderList()
    }

    private fun createInbox(visibleLimit: Int = 25): InMemoryBackendFolder {
        storage.folders[INBOX] = InMemoryBackendFolder("Inbox", FolderType.INBOX)
        return storage.getFolder(INBOX).also { it.visibleLimit = visibleLimit }
    }

    private companion object {
        const val INBOX = ProtonFolderIds.INBOX
        const val ARCHIVE = ProtonFolderIds.ARCHIVE
        const val LABEL = "label-urgent"
    }
}
