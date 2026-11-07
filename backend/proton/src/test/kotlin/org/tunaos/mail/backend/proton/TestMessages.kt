package org.tunaos.mail.backend.proton

import com.fsck.k9.mail.internet.MimeMessage

object TestMessages {
    fun plainText(): MimeMessage = parse(
        """
        From: Bob <bob@proton.me>
        To: Carol <carol@example.com>
        Cc: dave@example.com
        Bcc: eve@example.com
        Reply-To: Replies <replies@proton.me>
        Subject: Hello
        Message-ID: <m1@proton.me>
        In-Reply-To: <parent@example.com>
        References: <root@example.com>
         <parent@example.com>
        Date: Mon, 2 Nov 2026 10:00:00 +0000
        MIME-Version: 1.0
        Content-Type: text/plain; charset=utf-8

        Hi <Carol> & co
          indented
        """,
    )

    fun multipart(): MimeMessage = parse(
        """
        From: bob@proton.me
        To: carol@example.com
        Subject: Report
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="outer"

        --outer
        Content-Type: multipart/alternative; boundary="inner"

        --inner
        Content-Type: text/plain; charset=utf-8

        Plain version
        --inner
        Content-Type: text/html; charset=utf-8

        <p>HTML version <img src="cid:logo@proton.me"></p>
        --inner--
        --outer
        Content-Type: image/png
        Content-Disposition: inline
        Content-ID: <logo@proton.me>
        Content-Transfer-Encoding: base64

        iVBORw0K
        --outer
        Content-Type: application/pdf; name="report.pdf"
        Content-Disposition: attachment; filename="report.pdf"
        Content-Transfer-Encoding: base64

        JVBERi0=
        --outer--
        """,
    )

    private fun parse(text: String): MimeMessage {
        val crlf = text.trimIndent().replace("\n", "\r\n") + "\r\n"
        return MimeMessage.parseMimeMessage(crlf.byteInputStream(), false)
    }
}
