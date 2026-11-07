package org.tunaos.mail.backend.tuta

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import java.io.File
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test
import org.tunaos.tuta.Address
import org.tunaos.tuta.ContactPassword
import org.tunaos.tuta.Folder
import org.tunaos.tuta.FolderRole
import org.tunaos.tuta.MailHeader
import org.tunaos.tuta.Message

class TutaConfidentialTest {
    private val api = FakeTutaApi(
        folders = listOf(
            Folder(id = INBOX, role = FolderRole.INBOX, label = "Inbox"),
            Folder(id = DRAFTS, role = FolderRole.DRAFTS, label = "Drafts"),
        ),
    )
    private val passwords = TutaRecipientPasswords()
    private val backend = TutaBackend(InMemoryBackendStorage(), api, FakeSessionSource(), passwords = passwords)

    @Before
    fun setUp() {
        Log.logger = TestLogger()
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
    }

    @Test
    fun `a message without the header goes as ordinary mail without passwords`() {
        passwords.put(MESSAGE_ID, mapOf("bob@example.com" to "Correct-Horse-7"))

        backend.sendMessage(parse(outgoing(confidential = null)))

        val request = api.sent.single()
        assertThat(request.confidential).isFalse()
        assertThat(request.ordinaryConfirmed).isTrue()
        assertThat(request.passwords).isEmpty()
        assertThat(api.contactPasswordCalls).isEmpty()
    }

    @Test
    fun `a message the composer marked not confidential goes as ordinary mail`() {
        backend.sendMessage(parse(outgoing(confidential = "no")))

        assertThat(api.sent.single().confidential).isFalse()
        assertThat(api.sent.single().ordinaryConfirmed).isTrue()
    }

    @Test
    fun `a confidential message goes with the passwords the composer handed over, then forgets them`() {
        passwords.put(
            MESSAGE_ID,
            mapOf(
                "Bob@Example.com" to " Correct-Horse-7 ",
                "carol@example.com" to "Battery-Staple-9",
            ),
        )

        backend.sendMessage(parse(outgoing(confidential = "yes")))

        val request = api.sent.single()
        assertThat(request.confidential).isTrue()
        assertThat(request.ordinaryConfirmed).isFalse()
        assertThat(request.passwords).isEqualTo(
            mapOf("bob@example.com" to "Correct-Horse-7", "carol@example.com" to "Battery-Staple-9"),
        )
        assertThat(api.contactPasswordCalls).isEmpty()
        assertThat(passwords.get(MESSAGE_ID)).isEmpty()
    }

    @Test
    fun `a queued confidential message reads missing passwords from the Tuta contacts`() {
        passwords.put(MESSAGE_ID, mapOf("bob@example.com" to "Correct-Horse-7"))
        api.contacts["carol@example.com"] =
            ContactPassword(address = "carol@example.com", external = true, password = "Stored-Pass-3")

        backend.sendMessage(parse(outgoing(confidential = "yes")))

        assertThat(api.contactPasswordCalls).containsExactly(listOf("carol@example.com"))
        assertThat(api.sent.single().passwords).isEqualTo(
            mapOf("bob@example.com" to "Correct-Horse-7", "carol@example.com" to "Stored-Pass-3"),
        )
    }

    @Test
    fun `a recipient outside Tuta without any password is left for Tuta to refuse`() {
        api.contacts["bob@example.com"] = ContactPassword(address = "bob@example.com", external = true)

        backend.sendMessage(parse(outgoing(confidential = "yes")))

        assertThat(api.sent.single().confidential).isTrue()
        assertThat(api.sent.single().passwords).isEmpty()
    }

    @Test
    fun `a refused send keeps the passwords for the retry`() {
        passwords.put(MESSAGE_ID, mapOf("bob@example.com" to "Correct-Horse-7", "carol@example.com" to "Battery-9x"))
        api.failures += "send_failed"

        assertFailure { backend.sendMessage(parse(outgoing(confidential = "yes"))) }
            .isInstanceOf<MessagingException>()

        assertThat(passwords.get(MESSAGE_ID).keys).isEqualTo(setOf("bob@example.com", "carol@example.com"))
    }

    @Test
    fun `a confidential draft is saved confidential and without passwords`() {
        backend.refreshFolderList()
        passwords.put(MESSAGE_ID, mapOf("bob@example.com" to "Correct-Horse-7"))

        backend.uploadMessage(DRAFTS, parse(outgoing(confidential = "yes")))

        assertThat(api.drafts.single().confidential).isTrue()
        assertThat(api.drafts.single().toString()).doesNotContain("Correct-Horse-7")
    }

    @Test
    fun `sendPasswords prefers typed passwords and skips recipients on Tuta`() {
        val stored = listOf(
            ContactPassword(address = "ann@tuta.com", external = false),
            ContactPassword(address = "bob@example.com", external = true, password = "stored"),
            ContactPassword(address = "dan@example.com", external = true, password = "  "),
            ContactPassword(address = "eve@example.com", external = null, password = "kept"),
        )

        val result = sendPasswords(
            addresses = listOf(
                "ann@tuta.com",
                "bob@example.com",
                "BOB@example.com",
                "dan@example.com",
                "eve@example.com",
            ),
            typed = mapOf("ANN@tuta.com" to "ignored", "bob@EXAMPLE.com" to "typed"),
            stored = stored,
        )

        assertThat(result).isEqualTo(mapOf("bob@example.com" to "typed", "eve@example.com" to "kept"))
    }

    @Test
    fun `the vault drops blank passwords and never prints one`() {
        passwords.put("<a@example.com>", mapOf("x@example.com" to "  ", "y@example.com" to "Secret-1"))

        assertThat(passwords.get("<a@example.com>")).isEqualTo(mapOf("y@example.com" to "Secret-1"))
        assertThat(passwords.toString()).doesNotContain("Secret-1")

        passwords.put("<a@example.com>", mapOf("x@example.com" to ""))
        assertThat(passwords.get("<a@example.com>")).isEmpty()
    }

    @Test
    fun `a mail from Tuta says whether it is confidential`() {
        val header = MailHeader(
            id = "draft",
            subject = "Draft",
            dateMs = 1_700_000_000_000,
            sender = Address(name = "Alice", address = "alice@tuta.com"),
            recipient = Address(name = "", address = "bob@example.com"),
            isUnread = false,
            confidential = true,
            conversation = "conversation",
            messageId = "draft@tuta.com",
            hasAttachments = false,
        )

        assertThat(isConfidential(TutaMime.envelope(header))).isTrue()
        assertThat(isConfidential(TutaMime.envelope(header.copy(confidential = false)))).isFalse()
        assertThat(
            isConfidential(
                TutaMime.fullMessage(
                    Message(header, "<p>x</p>", "", emptyList(), emptyList(), emptyList(), emptyList()),
                ),
            ),
        ).isTrue()
    }

    private fun parse(text: String): MimeMessage =
        MimeMessage.parseMimeMessage(text.replace("\n", "\r\n").byteInputStream(), false)

    private fun outgoing(confidential: String?): String = buildString {
        append("From: Alice <alice@tuta.com>\n")
        append("To: bob@example.com\n")
        append("Cc: carol@example.com\n")
        append("Subject: Plans\n")
        append("Message-ID: $MESSAGE_ID\n")
        confidential?.let { append("$TUTA_CONFIDENTIAL_HEADER: $it\n") }
        append("Content-Type: text/plain; charset=utf-8\n\n")
        append("See you\n")
    }

    private companion object {
        const val INBOX = "folders/inbox"
        const val DRAFTS = "folders/drafts"
        const val MESSAGE_ID = "<composed@tuta.com>"
    }
}
