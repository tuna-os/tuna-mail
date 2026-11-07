package org.tunaos.mail.backend.tuta

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.MimeHeaderEncoder
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeParameterEncoder
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.io.encoding.Base64
import org.tunaos.tuta.Attachment
import org.tunaos.tuta.MailHeader
import org.tunaos.tuta.Message
import org.tunaos.tuta.Address as TutaAddress

/**
 * Turns Tuta's decrypted mails into MIME messages for the app's store.
 *
 * Tuta hands out a mail's parts (the HTML body, the addresses, the attachments) rather than the message as received,
 * so the message is rebuilt from them as `multipart/mixed` (attachments) around `multipart/related` (inline images)
 * around the `text/html` body, and parsed with the app's own MIME parser. Threading headers (`In-Reply-To`,
 * `References`) are carried over from the original header block when the mail came from outside Tuta.
 */
internal object TutaMime {
    private const val CRLF = "\r\n"
    private const val BASE64_LINE_LENGTH = 76
    private val THREADING_HEADERS = listOf("In-Reply-To", "References")

    /** The complete message. */
    fun fullMessage(message: Message): MimeMessage {
        val text = StringBuilder()
        text.appendHeaders(
            header = message.header,
            to = message.to,
            cc = message.cc,
            replyTo = message.replyTo,
            originalHeaders = message.headers,
        )
        text.append("MIME-Version: 1.0").append(CRLF)
        text.appendBody(message)
        return parse(text.toString())
    }

    /** The message's headers only, for a mail listed but not downloaded yet. */
    fun envelope(header: MailHeader): MimeMessage {
        val text = StringBuilder()
        text.appendHeaders(
            header = header,
            to = listOfNotNull(header.recipient),
            cc = emptyList(),
            replyTo = emptyList(),
            originalHeaders = "",
        )
        text.append(CRLF)
        return parse(text.toString())
    }

    private fun parse(text: String): MimeMessage {
        return MimeMessage.parseMimeMessage(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), false)
    }

    private fun StringBuilder.appendHeaders(
        header: MailHeader,
        to: List<TutaAddress>,
        cc: List<TutaAddress>,
        replyTo: List<TutaAddress>,
        originalHeaders: String,
    ) {
        header.messageId?.let { appendHeader("Message-ID", "<${it.trim('<', '>')}>") }
        appendHeader("Date", rfc2822Date(header.dateMs))
        appendHeader("From", header.sender.encoded())
        appendAddressHeader("To", to)
        appendAddressHeader("Cc", cc)
        appendAddressHeader("Reply-To", replyTo)
        appendHeader("Subject", MimeHeaderEncoder.encode("Subject", header.subject))
        for ((name, value) in threadingHeaders(originalHeaders)) {
            appendHeader(name, value)
        }
    }

    private fun StringBuilder.appendHeader(name: String, value: String) {
        append(name).append(": ").append(value).append(CRLF)
    }

    private fun StringBuilder.appendAddressHeader(name: String, addresses: List<TutaAddress>) {
        if (addresses.isEmpty()) return
        appendHeader(name, addresses.joinToString(",$CRLF ") { it.encoded() })
    }

    private fun TutaAddress.encoded(): String = Address(address, name.ifBlank { null }).toEncodedString()

    private fun rfc2822Date(dateMs: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date(dateMs))

    /** `In-Reply-To` and `References` from a raw header block, unfolded. */
    private fun threadingHeaders(headers: String): List<Pair<String, String>> {
        val unfolded = headers.replace(Regex("\r?\n[ \t]+"), " ")
        return unfolded.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val name = line.substring(0, colon).trim()
                val canonical = THREADING_HEADERS.firstOrNull { it.equals(name, ignoreCase = true) }
                canonical?.let { it to line.substring(colon + 1).trim() }
            }
            .filter { (_, value) -> value.isNotEmpty() }
            .distinctBy { (name, _) -> name }
            .toList()
    }

    private fun StringBuilder.appendBody(message: Message) {
        val (inline, attached) = message.attachments.partition { !it.cid.isNullOrBlank() }
        if (attached.isEmpty()) {
            appendRelated(message.html, inline)
            return
        }

        val boundary = newBoundary()
        appendHeader("Content-Type", "multipart/mixed; boundary=\"$boundary\"")
        append(CRLF)
        append("--").append(boundary).append(CRLF)
        appendRelated(message.html, inline)
        for (attachment in attached) {
            append(CRLF).append("--").append(boundary).append(CRLF)
            appendAttachment(attachment, disposition = "attachment")
        }
        append(CRLF).append("--").append(boundary).append("--").append(CRLF)
    }

    private fun StringBuilder.appendRelated(html: String, inline: List<Attachment>) {
        if (inline.isEmpty()) {
            appendHtml(html)
            return
        }

        val boundary = newBoundary()
        appendHeader("Content-Type", "multipart/related; boundary=\"$boundary\"; type=\"text/html\"")
        append(CRLF)
        append("--").append(boundary).append(CRLF)
        appendHtml(html)
        for (attachment in inline) {
            append(CRLF).append("--").append(boundary).append(CRLF)
            appendAttachment(attachment, disposition = "inline")
        }
        append(CRLF).append("--").append(boundary).append("--").append(CRLF)
    }

    private fun StringBuilder.appendHtml(html: String) {
        appendHeader("Content-Type", "text/html; charset=utf-8")
        appendHeader("Content-Transfer-Encoding", "base64")
        append(CRLF)
        appendBase64Lines(Base64.encode(html.toByteArray(Charsets.UTF_8)))
    }

    private fun StringBuilder.appendAttachment(attachment: Attachment, disposition: String) {
        val mimeType = attachment.mimeType?.takeIf { it.contains('/') } ?: "application/octet-stream"
        appendHeader("Content-Type", MimeParameterEncoder.encode(mimeType, mapOf("name" to attachment.name)))
        appendHeader("Content-Transfer-Encoding", "base64")
        appendHeader(
            "Content-Disposition",
            MimeParameterEncoder.encode(disposition, mapOf("filename" to attachment.name)),
        )
        attachment.cid?.takeIf { it.isNotBlank() }?.let { appendHeader("Content-ID", "<${it.trim('<', '>')}>") }
        append(CRLF)
        appendBase64Lines(attachment.data.filterNot { it.isWhitespace() })
    }

    private fun StringBuilder.appendBase64Lines(base64: String) {
        base64.chunked(BASE64_LINE_LENGTH).forEach { append(it).append(CRLF) }
    }

    private fun newBoundary() = "----=_TunaMail_${UUID.randomUUID()}"
}
