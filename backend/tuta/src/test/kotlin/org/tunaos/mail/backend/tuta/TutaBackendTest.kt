package org.tunaos.mail.backend.tuta

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
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
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test
import org.tunaos.tuta.Address
import org.tunaos.tuta.Attachment
import org.tunaos.tuta.Folder
import org.tunaos.tuta.FolderRole
import org.tunaos.tuta.Label
import org.tunaos.tuta.ListedMail
import org.tunaos.tuta.MailHeader
import org.tunaos.tuta.Message
import org.tunaos.tuta.ThreadAction
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaSession

class TutaBackendTest {
    private val api = FakeTutaApi(
        folders = listOf(
            Folder(id = INBOX, role = FolderRole.INBOX, label = "Inbox"),
            Folder(id = DRAFTS, role = FolderRole.DRAFTS, label = "Drafts"),
            Folder(id = TRASH, role = FolderRole.TRASH, label = "Trash"),
            Folder(id = WORK, role = FolderRole.OTHER, label = "Work"),
            Folder(id = PROJECTS, role = FolderRole.OTHER, label = "Projects", parent = WORK),
        ),
    )
    private val sessions = FakeSessionSource()
    private val storage = InMemoryBackendStorage()
    private val backend = TutaBackend(storage, api, sessions)
    private val listener = RecordingSyncListener()

    @Before
    fun setUp() {
        Log.logger = TestLogger()
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
    }

    @Test
    fun `refreshFolderList mirrors the folder tree with paths and types`() {
        backend.refreshFolderList()

        assertThat(storage.folders.mapValues { (_, folder) -> folder.name to folder.type }).isEqualTo(
            mapOf(
                INBOX to ("Inbox" to FolderType.INBOX),
                DRAFTS to ("Drafts" to FolderType.DRAFTS),
                TRASH to ("Trash" to FolderType.TRASH),
                WORK to ("Work" to FolderType.REGULAR),
                PROJECTS to ("Work/Projects" to FolderType.REGULAR),
            ),
        )
    }

    @Test
    fun `refreshFolderList renames and removes folders`() {
        backend.refreshFolderList()
        api.folders = api.folders.filter { it.id != PROJECTS }.map {
            if (it.id == WORK) it.copy(label = "Office") else it
        }

        backend.refreshFolderList()

        assertThat(storage.folders.keys).containsOnly(INBOX, DRAFTS, TRASH, WORK)
        assertThat(storage.getFolder(WORK).name).isEqualTo("Office")
    }

    @Test
    fun `create makes the folder under its parent and mirrors it`() = runBlocking {
        backend.refreshFolderList()

        val outcome = backend.create(FolderServerId("Work/Clients"), mustCreate = true, FolderType.ARCHIVE)

        assertThat(outcome).isEqualTo(Outcome.success(RemoteFolderCreationOutcome.Success.Created))
        val created = api.folders.last()
        assertThat(created.parent).isEqualTo(WORK)
        assertThat(created.label).isEqualTo("Clients")
        assertThat(storage.getFolder(created.id).name).isEqualTo("Work/Clients")
    }

    @Test
    fun `create reports a folder that is already there`() = runBlocking {
        val existing = backend.create(FolderServerId("Work"), mustCreate = false)
        val required = backend.create(FolderServerId("Work"), mustCreate = true)

        assertThat(existing).isEqualTo(Outcome.success(RemoteFolderCreationOutcome.Success.AlreadyExists))
        assertThat(required).isEqualTo(Outcome.failure(RemoteFolderCreationOutcome.Error.AlreadyExists))
        assertThat(api.folders.size).isEqualTo(5)
    }

    @Test
    fun `create fails without the parent folder or when Tuta refuses`() = runBlocking {
        val orphan = backend.create(FolderServerId("Nowhere/Clients"), mustCreate = true)
        api.failures += "folder_failed"
        val refused = backend.create(FolderServerId("Clients"), mustCreate = true)

        assertThat(orphan).isInstanceOf<Outcome.Failure<RemoteFolderCreationOutcome.Error>>()
            .prop(Outcome.Failure<RemoteFolderCreationOutcome.Error>::error)
            .isInstanceOf<RemoteFolderCreationOutcome.Error.FailedToCreateRemoteFolder>()
        assertThat(refused).isInstanceOf<Outcome.Failure<RemoteFolderCreationOutcome.Error>>()
        assertThat(api.folders.size).isEqualTo(5)
    }

    @Test
    fun `sync stores new mail whole, or as its envelope when it has attachments`() {
        backend.refreshFolderList()
        api.mails[INBOX] =
            mutableListOf(listed(mail("m2", hasAttachments = true)), listed(mail("m1", isUnread = false)))
        api.messages["m1"] = message(mail("m1", isUnread = false), html = "<p>Hello</p>")

        backend.sync(INBOX, syncConfig(), listener)

        val inbox = storage.getFolder(INBOX)
        assertThat(inbox.getMessageServerIds()).containsOnly("m1", "m2")
        assertThat(inbox.getMessageFlags("m1")).containsOnly(Flag.SEEN, Flag.X_DOWNLOADED_FULL)
        assertThat(inbox.getMessageFlags("m2")).isEmpty()
        assertThat(listener.finished).containsExactly(INBOX)
        assertThat(listener.newMessages).containsOnly("m1" to true, "m2" to true)
    }

    @Test
    fun `sync reports mail arriving after the first sync as new`() {
        backend.refreshFolderList()
        api.mails[INBOX] = mutableListOf(listed(mail("m1", dateMs = 1_000)))
        api.messages["m1"] = message(mail("m1", dateMs = 1_000))
        backend.sync(INBOX, syncConfig(), listener)
        api.mails.getValue(INBOX).add(0, listed(mail("m2", dateMs = 2_000)))
        api.messages["m2"] = message(mail("m2", dateMs = 2_000))
        listener.newMessages.clear()

        backend.sync(INBOX, syncConfig(), listener)

        assertThat(listener.newMessages).containsExactly("m2" to false)
    }

    @Test
    fun `sync drops mail the folder no longer holds and updates flags`() {
        backend.refreshFolderList()
        api.labels += Label("flag-label", "Flagged", "#ffffff")
        api.mails[INBOX] = mutableListOf(listed(mail("m1")), listed(mail("m2")))
        api.messages["m1"] = message(mail("m1"))
        api.messages["m2"] = message(mail("m2"))
        backend.sync(INBOX, syncConfig(), listener)

        api.mails[INBOX] = mutableListOf(listed(mail("m1", isUnread = false), labels = listOf("flag-label")))
        backend.sync(INBOX, syncConfig(), listener)

        val inbox = storage.getFolder(INBOX)
        assertThat(inbox.getMessageServerIds()).containsOnly("m1")
        assertThat(inbox.getMessageFlags("m1")).all {
            contains(Flag.SEEN)
            contains(Flag.FLAGGED)
        }
        assertThat(listener.removed).containsExactly("m2")
        assertThat(listener.flagChanged).containsExactly("m1")
    }

    @Test
    fun `sync lists only as many mails as the folder shows, in pages`() {
        backend.refreshFolderList()
        storage.getFolder(INBOX).visibleLimit = 150
        api.mails[INBOX] = (1..200).map { listed(mail("m$it", hasAttachments = true)) }.toMutableList()

        backend.sync(INBOX, syncConfig(), listener)

        assertThat(storage.getFolder(INBOX).getMessageServerIds().size).isEqualTo(150)
        assertThat(api.syncRequests.filter { it.first == INBOX }).containsExactly(
            Triple(INBOX, 100, 0),
            Triple(INBOX, 50, 100),
        )
        assertThat(storage.getFolder(INBOX).getMoreMessages()).isEqualTo(MoreMessages.TRUE)
    }

    @Test
    fun `a refused session fails the sync as an authentication failure and is forgotten`() {
        backend.refreshFolderList()
        api.failures += "session_revoked"

        backend.sync(INBOX, syncConfig(), listener)

        assertThat(listener.failure as? AuthenticationFailedException).isNotNull()
        assertThat(sessions.isInvalidated).isTrue()
    }

    @Test
    fun `no session is an authentication failure`() {
        sessions.session = null

        assertFailure { backend.refreshFolderList() }.isInstanceOf<AuthenticationFailedException>()
    }

    @Test
    fun `a dropped connection is a temporary failure`() {
        api.failures += "transport_failed"

        assertFailure { backend.refreshFolderList() }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isEqualTo(false)
    }

    @Test
    fun `downloadCompleteMessage stores the rebuilt MIME message and keeps the local flag`() {
        backend.refreshFolderList()
        api.mails[INBOX] = mutableListOf(listed(mail("m1", hasAttachments = true)))
        backend.sync(INBOX, syncConfig(), listener)
        storage.getFolder(INBOX).setMessageFlag("m1", Flag.FLAGGED, true)
        api.messages["m1"] = message(
            mail("m1", hasAttachments = true),
            html = "<p>Report attached</p>",
            headers = "Message-ID: <m1@example.com>\r\nIn-Reply-To: <parent@example.com>\r\n" +
                "References: <root@example.com>\r\n <parent@example.com>\r\n",
            attachments = listOf(Attachment(name = "report.pdf", mimeType = "application/pdf", data = "JVBERi0=")),
        )

        runBlocking { backend.downloadCompleteMessage(INBOX, "m1") }

        val inbox = storage.getFolder(INBOX)
        assertThat(inbox.getMessageFlags("m1")).all {
            contains(Flag.X_DOWNLOADED_FULL)
            contains(Flag.FLAGGED)
        }
    }

    @Test
    fun `setFlag SEEN marks mail read in batches of 50`() {
        val ids = (1..120).map { "m$it" }

        backend.setFlag(INBOX, ids, Flag.SEEN, true)

        assertThat(api.markReadCalls.map { it.first.size to it.second }).containsExactly(
            50 to true,
            50 to true,
            20 to true,
        )
    }

    @Test
    fun `setFlag FLAGGED creates the Flagged label once and applies it`() {
        backend.setFlag(INBOX, listOf("m1"), Flag.FLAGGED, true)
        backend.setFlag(INBOX, listOf("m2"), Flag.FLAGGED, false)

        assertThat(api.labels.map { it.name }).containsExactly("Flagged")
        assertThat(api.applyLabelsCalls).containsExactly(
            Triple(listOf("m1"), listOf("label-1"), emptyList<String>()),
            Triple(listOf("m2"), emptyList<String>(), listOf("label-1")),
        )
    }

    @Test
    fun `setFlag FLAGGED stays local and tells the user once when the account cannot have the label`() {
        // The labels list, but the account's plan refuses a new one.
        val refusingApi = object : TutaApi by api {
            override suspend fun createLabel(session: TutaSession, name: String, color: String): String =
                throw TutaException.of("limit_reached")
        }
        var noticeCount = 0
        val refusingBackend = TutaBackend(storage, refusingApi, sessions, notices = TutaNotices { noticeCount++ })

        refusingBackend.setFlag(INBOX, listOf("m1"), Flag.FLAGGED, true)
        refusingBackend.setFlag(INBOX, listOf("m2"), Flag.FLAGGED, true)

        assertThat(api.applyLabelsCalls).isEmpty()
        assertThat(noticeCount).isEqualTo(1)
    }

    @Test
    fun `a label chosen for flags is used instead of Flagged, from the next flag change on`() {
        val refusingApi = object : TutaApi by api {
            override suspend fun createLabel(session: TutaSession, name: String, color: String): String =
                throw TutaException.of("limit_reached")
        }
        val refusingBackend = TutaBackend(storage, refusingApi, sessions)
        api.labels += Label("label-work", "Work", "#000000")
        refusingBackend.setFlag(INBOX, listOf("m1"), Flag.FLAGGED, true)
        val choice = TutaFlagLabelChoice(storage, api, sessions)

        val labels = runBlocking { choice.labels() }
        choice.choose("label-work")
        refusingBackend.setFlag(INBOX, listOf("m2"), Flag.FLAGGED, true)

        assertThat(labels.map { it.id }).containsExactly("label-work")
        assertThat(choice.chosen()).isEqualTo("label-work")
        assertThat(api.applyLabelsCalls).containsExactly(
            Triple(listOf("m2"), listOf("label-work"), emptyList<String>()),
        )
    }

    @Test
    fun `sync reads flags from the label chosen for flags`() {
        backend.refreshFolderList()
        api.labels += Label("label-work", "Work", "#000000")
        TutaFlagLabelChoice(storage, api, sessions).choose("label-work")
        api.mails[INBOX] = mutableListOf(listed(mail("m1", isUnread = false), labels = listOf("label-work")))
        api.messages["m1"] = message(mail("m1", isUnread = false))

        backend.sync(INBOX, syncConfig(), listener)

        assertThat(storage.getFolder(INBOX).getMessageFlags("m1")).contains(Flag.FLAGGED)
    }

    @Test
    fun `setFlag ignores flags Tuta does not keep`() {
        backend.setFlag(INBOX, listOf("m1"), Flag.ANSWERED, true)

        assertThat(api.markReadCalls).isEmpty()
        assertThat(api.applyLabelsCalls).isEmpty()
    }

    @Test
    fun `copy is not offered, and refused if asked for anyway`() {
        assertThat(backend.supportsCopy).isFalse()
        assertFailure { backend.copyMessages(INBOX, WORK, listOf("m1")) }.isInstanceOf<MessagingException>()
        assertThat(api.moveCalls).isEmpty()
    }

    @Test
    fun `moveMessagesAndMarkAsRead moves and marks the moved mail read`() {
        val ids = backend.moveMessagesAndMarkAsRead(INBOX, WORK, listOf("m1", "m2"))

        assertThat(ids).isEqualTo(mapOf("m1" to "m1", "m2" to "m2"))
        assertThat(api.moveCalls).containsExactly(listOf("m1", "m2") to WORK)
        assertThat(api.markReadCalls).containsExactly(listOf("m1", "m2") to true)
    }

    @Test
    fun `deleteMessages deletes for good in Trash`() {
        backend.refreshFolderList()

        backend.deleteMessages(TRASH, listOf("m1"))

        assertThat(api.deleteCalls).containsExactly(listOf("m1") to TRASH)
    }

    @Test
    fun `deleteMessages discards drafts`() {
        backend.refreshFolderList()

        backend.deleteMessages(DRAFTS, listOf("d1", "d2"))

        assertThat(api.deletedDrafts).containsExactly("d1", "d2")
        assertThat(api.deleteCalls).isEmpty()
    }

    @Test
    fun `deleteMessages outside Trash and Spam moves the mail to Trash`() {
        backend.refreshFolderList()
        api.undeletableFolders += WORK

        backend.deleteMessages(WORK, listOf("m1"))

        assertThat(api.moveCalls).containsExactly(listOf("m1") to TRASH)
    }

    @Test
    fun `deleteAllMessages empties the folder`() {
        backend.deleteAllMessages(TRASH)

        assertThat(api.emptiedFolders).containsExactly(TRASH)
    }

    @Test
    fun `sendMessage sends the message's fields`() {
        backend.sendMessage(parse(OUTGOING_REPLY))

        val request = api.sent.single()
        assertThat(request.sender).isEqualTo("alice@tuta.com")
        assertThat(request.senderName).isEqualTo("Alice")
        assertThat(request.to.map { it.address }).containsExactly("bob@example.com")
        assertThat(request.bcc.map { it.address }).containsExactly("carol@example.com")
        assertThat(request.subject).isEqualTo("Re: Plans")
        assertThat(request.bodyHtml).isEqualTo("<p>Sounds good</p>")
        assertThat(
            request.attachments.map {
                it.name to it.data.decodeToString()
            },
        ).containsExactly("notes.txt" to "notes")
        assertThat(request.thread.inReplyTo).isEqualTo("<parent@example.com>")
        assertThat(request.thread.action).isEqualTo(ThreadAction.REPLY)
        assertThat(request.confidential).isEqualTo(false)
        assertThat(request.ordinaryConfirmed).isTrue()
    }

    @Test
    fun `a send that may have gone out is a permanent failure`() {
        api.failures += "send_uncertain"

        assertFailure { backend.sendMessage(parse(OUTGOING_REPLY)) }
            .isInstanceOf<MessagingException>()
            .prop(MessagingException::isPermanentFailure).isTrue()
    }

    @Test
    fun `uploadMessage saves drafts and skips other folders`() {
        backend.refreshFolderList()

        val draftId = backend.uploadMessage(DRAFTS, parse(OUTGOING_REPLY))
        val otherId = backend.uploadMessage(WORK, parse(OUTGOING_REPLY))

        assertThat(draftId).isEqualTo("draft-1")
        assertThat(otherId).isNull()
        assertThat(api.drafts.map { it.subject }).containsExactly("Re: Plans")
    }

    @Test
    fun `plain text bodies are sent as HTML`() {
        backend.sendMessage(parse(OUTGOING_PLAIN))

        assertThat(api.sent.single().bodyHtml).isEqualTo("<div>a &lt; b<br>\nsecond line</div>")
        assertThat(api.sent.single().bodyHtml).doesNotContain("<b>")
    }

    private fun syncConfig() = SyncConfig(
        expungePolicy = ExpungePolicy.IMMEDIATELY,
        earliestPollDate = null,
        syncRemoteDeletions = true,
        maximumAutoDownloadMessageSize = 1_000_000,
        defaultVisibleLimit = 25,
        syncFlags = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED),
    )

    private fun parse(text: String): MimeMessage =
        MimeMessage.parseMimeMessage(text.replace("\n", "\r\n").byteInputStream(), false)

    companion object {
        const val INBOX = "folders/inbox"
        const val DRAFTS = "folders/drafts"
        const val TRASH = "folders/trash"
        const val WORK = "folders/work"
        const val PROJECTS = "folders/projects"

        fun mail(
            id: String,
            isUnread: Boolean = true,
            hasAttachments: Boolean = false,
            dateMs: Long = 1_700_000_000_000,
        ) = MailHeader(
            id = id,
            subject = "Subject $id",
            dateMs = dateMs,
            sender = Address(name = "Bob", address = "bob@example.com"),
            recipient = Address(name = "", address = "alice@tuta.com"),
            isUnread = isUnread,
            confidential = false,
            conversation = "conversation-$id",
            messageId = "$id@example.com",
            hasAttachments = hasAttachments,
        )

        fun listed(header: MailHeader, labels: List<String> = emptyList()) = ListedMail(header, labels)

        fun message(
            header: MailHeader,
            html: String = "<p>Body</p>",
            headers: String = "",
            attachments: List<Attachment> = emptyList(),
        ) = Message(
            header = header,
            html = html,
            headers = headers,
            to = listOf(Address(name = "Alice", address = "alice@tuta.com")),
            cc = emptyList(),
            replyTo = emptyList(),
            attachments = attachments,
        )

        val OUTGOING_REPLY = """
            From: Alice <alice@tuta.com>
            To: bob@example.com
            Bcc: carol@example.com
            Subject: Re: Plans
            In-Reply-To: <parent@example.com>
            References: <root@example.com> <parent@example.com>
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="outer"

            --outer
            Content-Type: multipart/alternative; boundary="inner"

            --inner
            Content-Type: text/plain; charset=utf-8

            Sounds good
            --inner
            Content-Type: text/html; charset=utf-8

            <p>Sounds good</p>
            --inner--
            --outer
            Content-Type: text/plain; name="notes.txt"
            Content-Disposition: attachment; filename="notes.txt"
            Content-Transfer-Encoding: base64

            bm90ZXM=
            --outer--
        """.trimIndent()

        val OUTGOING_PLAIN = """
            From: alice@tuta.com
            To: bob@example.com
            Subject: Plain
            Content-Type: text/plain; charset=utf-8

            a < b
            second line
        """.trimIndent()
    }
}

class RecordingSyncListener : SyncListener {
    val newMessages = mutableListOf<Pair<String, Boolean>>()
    val removed = mutableListOf<String>()
    val flagChanged = mutableListOf<String>()
    val finished = mutableListOf<String>()
    var failure: Exception? = null

    override fun syncStarted(folderServerId: String) = Unit
    override fun syncAuthenticationSuccess() = Unit
    override fun syncHeadersStarted(folderServerId: String) = Unit
    override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
    override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) = Unit
    override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit

    override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) {
        newMessages += messageServerId to isOldMessage
    }

    override fun syncRemovedMessage(folderServerId: String, messageServerId: String) {
        removed += messageServerId
    }

    override fun syncFlagChanged(folderServerId: String, messageServerId: String) {
        flagChanged += messageServerId
    }

    override fun syncFinished(folderServerId: String) {
        finished += folderServerId
    }

    override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
        failure = exception
    }

    override fun folderStatusChanged(folderServerId: String) = Unit
}
