package org.tunaos.mail.backend.tuta

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import java.io.File
import org.junit.Before
import org.junit.Test
import org.tunaos.tuta.Address
import org.tunaos.tuta.Attachment

class TutaMimeTest {
    @Before
    fun setUp() {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
    }

    @Test
    fun `a mail without attachments is a single HTML part`() {
        val header = TutaBackendTest.mail("m1").copy(subject = "Grüße")
        val message = TutaMime.fullMessage(TutaBackendTest.message(header, html = "<p>Hallo</p>"))

        assertThat(message.subject).isEqualTo("Grüße")
        assertThat(message.messageId).isEqualTo("<m1@example.com>")
        assertThat(message.from.single().address).isEqualTo("bob@example.com")
        assertThat(message.from.single().personal).isEqualTo("Bob")
        assertThat(message.getRecipients(RecipientType.TO).map { it.address }).containsExactly("alice@tuta.com")
        assertThat(message.sentDate.time).isEqualTo(1_700_000_000_000)
        assertThat(message.mimeType).isEqualTo("text/html")
        assertThat(MessageExtractor.getTextFromPart(message)).isEqualTo("<p>Hallo</p>")
    }

    @Test
    fun `attachments and inline images get their own parts`() {
        val message = TutaMime.fullMessage(
            TutaBackendTest.message(
                TutaBackendTest.mail("m1", hasAttachments = true),
                html = "<img src=\"cid:logo\">",
                attachments = listOf(
                    Attachment(name = "logo.png", mimeType = "image/png", cid = "logo", data = "iVBORw=="),
                    Attachment(name = "Bericht ä.pdf", mimeType = "application/pdf", data = "JVBERi0="),
                ),
            ),
        )

        assertThat(message.mimeType).isEqualTo("multipart/mixed")
        val mixed = message.body as Multipart
        assertThat(mixed.bodyParts.map { it.mimeType }).containsExactly("multipart/related", "application/pdf")

        val related = mixed.bodyParts[0].body as Multipart
        assertThat(related.bodyParts.map { it.mimeType }).containsExactly("text/html", "image/png")
        assertThat(related.bodyParts[1].contentId).isEqualTo("logo")

        val pdf = mixed.bodyParts[1]
        assertThat(MimeUtility.decodeBody(pdf.body).use { it.readBytes().decodeToString() }).isEqualTo("%PDF-")
        assertThat(pdf.disposition!!.contains("attachment")).isEqualTo(true)
    }

    @Test
    fun `threading headers are carried over from the original header block`() {
        val message = TutaMime.fullMessage(
            TutaBackendTest.message(
                TutaBackendTest.mail("m1"),
                headers =
                "Received: from x\r\nIN-REPLY-TO: <parent@example.com>\r\nReferences: <root@example.com>\r\n" +
                    "\t<parent@example.com>\r\nSubject: ignored\r\n",
            ),
        )

        assertThat(message.getHeader("In-Reply-To").toList()).containsExactly("<parent@example.com>")
        assertThat(message.references.toList()).containsExactly("<root@example.com> <parent@example.com>")
        assertThat(message.getHeader("Received").toList()).isEmpty()
    }

    @Test
    fun `a line break in the subject does not start another header`() {
        val header = TutaBackendTest.mail("m1").copy(subject = "Hi\r\nX-Injected: yes")

        val message = TutaMime.fullMessage(TutaBackendTest.message(header, html = "<p>Hallo</p>"))

        assertThat(message.subject).isEqualTo("Hi X-Injected: yes")
        assertThat(message.getHeader("X-Injected").toList()).isEmpty()
    }

    @Test
    fun `a line break in the subject does not end the header block`() {
        val header = TutaBackendTest.mail("m1").copy(subject = "Hi\r\n\r\nNot the body")

        val message = TutaMime.fullMessage(TutaBackendTest.message(header, html = "<p>Hallo</p>"))

        assertThat(message.mimeType).isEqualTo("text/html")
        assertThat(MessageExtractor.getTextFromPart(message)).isEqualTo("<p>Hallo</p>")
    }

    @Test
    fun `a line break in an address does not start another header`() {
        val message = TutaMime.fullMessage(
            TutaBackendTest.message(TutaBackendTest.mail("m1")).copy(
                replyTo = listOf(Address(name = "", address = "carol@example.com\r\nX-Injected: yes")),
            ),
        )

        assertThat(message.getHeader("X-Injected").toList()).isEmpty()
        assertThat(message.subject).isEqualTo("Subject m1")
    }

    @Test
    fun `a line break in an attachment's type or content id does not start another header`() {
        val message = TutaMime.fullMessage(
            TutaBackendTest.message(
                TutaBackendTest.mail("m1", hasAttachments = true),
                html = "<img src=\"cid:logo\">",
                attachments = listOf(
                    Attachment(
                        name = "logo.png",
                        mimeType = "image/png",
                        cid = "logo\r\nX-Injected: yes",
                        data = "iVBORw==",
                    ),
                    Attachment(name = "a.pdf", mimeType = "application/pdf\r\nX-Injected: yes", data = "JVBERi0="),
                ),
            ),
        )

        val mixed = message.body as Multipart
        val related = mixed.bodyParts[0].body as Multipart
        assertThat(related.bodyParts[1].getHeader("X-Injected").toList()).isEmpty()
        assertThat(mixed.bodyParts[1].getHeader("X-Injected").toList()).isEmpty()
        assertThat(mixed.bodyParts.map { it.mimeType }).containsExactly("multipart/related", "application/octet-stream")
    }

    @Test
    fun `an envelope has the headers and no body`() {
        val message = TutaMime.envelope(TutaBackendTest.mail("m1", hasAttachments = true))

        assertThat(message.subject).isEqualTo("Subject m1")
        assertThat(message.getRecipients(RecipientType.TO).map { it.address }).containsExactly("alice@tuta.com")
        assertThat(message.getRecipients(RecipientType.CC).toList()).containsExactly()
    }

    @Test
    fun `a draft has no Message-ID`() {
        val header = TutaBackendTest.mail("d1").copy(messageId = null)

        val message = TutaMime.fullMessage(TutaBackendTest.message(header))

        assertThat(message.messageId).isNull()
    }

    @Test
    fun `a sender without a name is a bare address`() {
        val header = TutaBackendTest.mail("m1").copy(sender = Address(name = "", address = "noreply@example.com"))

        val message = TutaMime.fullMessage(TutaBackendTest.message(header))

        assertThat(message.from.single().personal).isNull()
    }
}
