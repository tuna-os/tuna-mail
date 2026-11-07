package org.tunaos.mail.backend.proton

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.tunaos.proton.ProtonDelivery
import org.tunaos.proton.ProtonMailbox

class ProtonOutgoingMailMapperTest {
    @BeforeTest
    fun setUp() {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
    }

    @Test
    fun `addresses, threading headers and a plain-text body`() {
        val mail = ProtonOutgoingMailMapper.map(TestMessages.plainText())

        assertThat(mail.sender).isEqualTo("bob@proton.me")
        assertThat(mail.senderName).isEqualTo("Bob")
        assertThat(mail.subject).isEqualTo("Hello")
        assertThat(mail.to).containsExactly(ProtonMailbox("carol@example.com", "Carol"))
        assertThat(mail.cc).containsExactly(ProtonMailbox("dave@example.com"))
        assertThat(mail.bcc).containsExactly(ProtonMailbox("eve@example.com"))
        assertThat(mail.replyTos).containsExactly(ProtonMailbox("replies@proton.me", "Replies"))
        assertThat(mail.messageId).isEqualTo("<m1@proton.me>")
        assertThat(mail.inReplyTo).isEqualTo("<parent@example.com>")
        assertThat(mail.references).isEqualTo("<root@example.com> <parent@example.com>")
        assertThat(mail.bodyHtml).isEqualTo(
            "<div style=\"white-space: pre-wrap\">Hi &lt;Carol&gt; &amp; co\n  indented\n</div>",
        )
        assertThat(mail.delivery).isEqualTo(ProtonDelivery.ORDINARY)
        assertThat(mail.ordinaryConfirmed).isTrue()
    }

    @Test
    fun `html body, inline image and attachment`() {
        val mail = ProtonOutgoingMailMapper.map(TestMessages.multipart())

        assertThat(mail.bodyHtml).isEqualTo("<p>HTML version <img src=\"cid:logo@proton.me\"></p>")
        assertThat(mail.attachments.map { listOf(it.name, it.mimeType, it.cid) }).containsExactly(
            listOf("attachment", "image/png", "logo@proton.me"),
            listOf("report.pdf", "application/pdf", null),
        )
        assertThat(mail.attachments.map { it.data.toList() }).containsExactly(
            Base64.getDecoder().decode("iVBORw0K").toList(),
            Base64.getDecoder().decode("JVBERi0=").toList(),
        )
        assertThat(mail.inReplyTo).isEqualTo(null)
    }
}
