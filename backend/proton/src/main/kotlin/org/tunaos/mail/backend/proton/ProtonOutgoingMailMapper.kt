package org.tunaos.mail.backend.proton

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeParameterDecoder
import com.fsck.k9.mail.internet.MimeUtility
import net.thunderbird.core.common.exception.MessagingException
import org.tunaos.proton.ProtonDelivery
import org.tunaos.proton.ProtonDraft
import org.tunaos.proton.ProtonMailbox
import org.tunaos.proton.ProtonOutgoingAttachment
import org.tunaos.proton.ProtonOutgoingMail

/**
 * Turns a composed message into the structured mail Proton's `send` and `save_draft` take: Proton
 * encrypts and builds the message itself, so it wants the body as HTML and the attachments as
 * bytes rather than the finished MIME.
 *
 * Delivery is [ProtonDelivery.ORDINARY] with consent given, which is what Proton's own clients
 * do: end-to-end encrypted to Proton and PGP recipients, TLS to everyone else.
 */
internal object ProtonOutgoingMailMapper {
    fun map(message: Message): ProtonOutgoingMail {
        val from = message.from?.firstOrNull()
            ?: throw MessagingException("Message has no From address", true)
        val content = Content().also { it.collect(message) }

        return ProtonOutgoingMail(
            sender = from.address,
            senderName = from.personal.orEmpty(),
            subject = message.subject.orEmpty(),
            bodyHtml = content.html ?: textToHtml(content.text.orEmpty()),
            delivery = ProtonDelivery.ORDINARY,
            ordinaryConfirmed = true,
            to = message.getRecipients(RecipientType.TO).toMailboxes(),
            cc = message.getRecipients(RecipientType.CC).toMailboxes(),
            bcc = message.getRecipients(RecipientType.BCC).toMailboxes(),
            replyTos = message.replyTo.toMailboxes(),
            attachments = content.attachments,
            messageId = message.messageId?.takeIf { it.isNotBlank() },
            inReplyTo = message.headerTokens("In-Reply-To"),
            references = message.headerTokens("References"),
        )
    }

    fun mapDraft(message: Message): ProtonDraft = map(message).let { mail ->
        ProtonDraft(
            sender = mail.sender,
            subject = mail.subject,
            bodyHtml = mail.bodyHtml,
            senderName = mail.senderName,
            to = mail.to,
            cc = mail.cc,
            bcc = mail.bcc,
            replyTos = mail.replyTos,
            attachments = mail.attachments,
            messageId = mail.messageId,
            inReplyTo = mail.inReplyTo,
            references = mail.references,
        )
    }

    /** The header's values unfolded, with runs of whitespace collapsed to one space; null if empty. */
    private fun Message.headerTokens(name: String): String? = getHeader(name)
        .flatMap { it.split(WHITESPACE) }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    private class Content {
        var html: String? = null
        var text: String? = null
        val attachments = mutableListOf<ProtonOutgoingAttachment>()

        fun collect(part: Part) {
            val body = part.body
            when {
                body is Multipart -> body.bodyParts.forEach(::collect)
                part.isAttachment() -> attachments += part.toAttachment()
                part.isMimeType("text/html") && html == null -> html = MessageExtractor.getTextFromPart(part)
                part.isMimeType("text/plain") && text == null -> text = MessageExtractor.getTextFromPart(part)
                part.isMimeType("text/*") -> Unit
                else -> attachments += part.toAttachment()
            }
        }
    }

    private fun Part.isAttachment(): Boolean {
        val disposition = disposition?.let(::decodeHeader) ?: return false
        return disposition.value.equals("attachment", ignoreCase = true) ||
            disposition.parameters.containsKey("filename") ||
            (disposition.value.equals("inline", ignoreCase = true) && contentId != null && !isMimeType("text/*"))
    }

    private fun Part.toAttachment(): ProtonOutgoingAttachment {
        val data = MimeUtility.decodeBody(body).use { it.readBytes() }
        return ProtonOutgoingAttachment(
            name = fileName() ?: "attachment",
            mimeType = mimeType,
            data = data,
            cid = contentId?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun Part.fileName(): String? {
        val fromDisposition = disposition?.let { decodeHeader(it).parameters["filename"] }
        val fromContentType = contentType?.let { decodeHeader(it).parameters["name"] }
        return (fromDisposition ?: fromContentType)?.takeIf { it.isNotBlank() }
    }

    private fun decodeHeader(value: String) = MimeParameterDecoder.decode(MimeUtility.unfold(value))

    private fun Array<Address>?.toMailboxes(): List<ProtonMailbox> =
        orEmpty().map { ProtonMailbox(address = it.address, name = it.personal.orEmpty()) }

    internal fun textToHtml(text: String): String {
        val escaped = buildString(text.length) {
            for (char in text) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\r' -> Unit
                    else -> append(char)
                }
            }
        }
        return "<div style=\"white-space: pre-wrap\">$escaped</div>"
    }
}
