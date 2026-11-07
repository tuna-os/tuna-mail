package org.tunaos.mail.backend.tuta

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeParameterDecoder
import com.fsck.k9.mail.internet.MimeUtility
import net.thunderbird.core.common.exception.MessagingException
import org.tunaos.tuta.DraftRequest
import org.tunaos.tuta.MailThread
import org.tunaos.tuta.OutgoingAttachment
import org.tunaos.tuta.Recipient
import org.tunaos.tuta.SendRequest
import org.tunaos.tuta.ThreadAction

/**
 * A composed message as Tuta takes it: Tuta sends and saves drafts from their fields (addresses, subject, an HTML
 * body, attachments), not from MIME, so the app's message is taken apart here.
 */
internal data class TutaOutgoing(
    val sender: Recipient,
    val to: List<Recipient>,
    val cc: List<Recipient>,
    val bcc: List<Recipient>,
    val replyTos: List<Recipient>,
    val subject: String,
    val bodyHtml: String,
    val attachments: List<OutgoingAttachment>,
    val thread: MailThread,
) {
    /**
     * An ordinary send: Tuta encrypts end to end for recipients on Tuta anyway, and mail to anyone else goes out as
     * a mail client sends it, so [SendRequest.ordinaryConfirmed] stands for the user's choice to send it.
     */
    fun toSendRequest() = SendRequest(
        sender = sender.address,
        senderName = sender.name,
        to = to,
        cc = cc,
        bcc = bcc,
        replyTos = replyTos,
        subject = subject,
        bodyHtml = bodyHtml,
        confidential = false,
        ordinaryConfirmed = true,
        attachments = attachments,
        thread = thread,
    )

    fun toDraftRequest() = DraftRequest(
        sender = sender.address,
        senderName = sender.name,
        to = to,
        cc = cc,
        bcc = bcc,
        replyTos = replyTos,
        subject = subject,
        bodyHtml = bodyHtml,
        confidential = false,
        attachments = attachments,
        thread = thread,
    )

    companion object {
        private const val FORWARD_PREFIX = "fwd:"

        fun from(message: Message): TutaOutgoing {
            val from = message.from?.firstOrNull() ?: throw MessagingException("Message has no sender", true)
            val content = Content().also { it.collect(message) }
            val subject = message.subject.orEmpty()
            val inReplyTo = message.getHeader("In-Reply-To").firstOrNull()?.trim()?.ifEmpty { null }
            val references = message.references?.joinToString(" ")?.trim()?.ifEmpty { null }

            return TutaOutgoing(
                sender = from.toRecipient(),
                to = message.getRecipients(RecipientType.TO).toRecipients(),
                cc = message.getRecipients(RecipientType.CC).toRecipients(),
                bcc = message.getRecipients(RecipientType.BCC).toRecipients(),
                replyTos = message.replyTo.toRecipients(),
                subject = subject,
                bodyHtml = content.html ?: content.plain?.let(::plainToHtml).orEmpty(),
                attachments = content.attachments,
                thread = MailThread(
                    inReplyTo = inReplyTo,
                    references = references,
                    action = when {
                        inReplyTo == null -> null
                        subject.trimStart().lowercase().startsWith(FORWARD_PREFIX) -> ThreadAction.FORWARD
                        else -> ThreadAction.REPLY
                    },
                ),
            )
        }

        private fun Address.toRecipient() = Recipient(name = personal.orEmpty(), address = address)

        private fun Array<Address>?.toRecipients(): List<Recipient> = orEmpty().map { it.toRecipient() }

        /** Plain text as HTML, line breaks and all. */
        fun plainToHtml(text: String): String {
            val escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "\n")
                .replace("\n", "<br>\n")
            return "<div>$escaped</div>"
        }
    }

    /** The body and attachments of a message, from its MIME tree. */
    private class Content {
        var html: String? = null
        var plain: String? = null
        val attachments = mutableListOf<OutgoingAttachment>()

        fun collect(part: Part) {
            val body = part.body ?: return
            if (body is Multipart) {
                body.bodyParts.forEach(::collect)
                return
            }

            val mimeType = part.mimeType.lowercase()
            val isAttachment = part.disposition?.trimStart()?.lowercase()?.startsWith("attachment") == true
            when {
                !isAttachment && mimeType == "text/html" && html == null ->
                    html =
                        MessageExtractor.getTextFromPart(part)

                !isAttachment && mimeType == "text/plain" && plain == null ->
                    plain = MessageExtractor.getTextFromPart(part)

                else -> attachments += part.toAttachment(mimeType)
            }
        }

        private fun Part.toAttachment(mimeType: String): OutgoingAttachment {
            val bytes = MimeUtility.decodeBody(body).use { it.readBytes() }
            return OutgoingAttachment(
                name = fileName() ?: "attachment",
                mimeType = mimeType,
                data = bytes,
                cid = contentId?.takeIf { disposition?.trimStart()?.lowercase()?.startsWith("inline") == true },
            )
        }

        private fun Part.fileName(): String? {
            val fromDisposition = disposition?.let { MimeParameterDecoder.decode(it).parameters["filename"] }
            val fromContentType = contentType?.let { MimeParameterDecoder.decode(it).parameters["name"] }
            return (fromDisposition ?: fromContentType)?.takeIf { it.isNotBlank() }
        }
    }
}
