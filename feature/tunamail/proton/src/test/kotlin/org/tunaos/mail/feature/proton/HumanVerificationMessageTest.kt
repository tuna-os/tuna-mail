package org.tunaos.mail.feature.proton

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import org.tunaos.proton.HumanVerificationToken

class HumanVerificationMessageTest {
    @Test
    fun `a solved CAPTCHA carries the token and its type`() {
        val message = HumanVerificationMessage.parse(
            """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"abc","type":"captcha"}}""",
        )

        assertThat(message).isEqualTo(HumanVerificationMessage.Solved(HumanVerificationToken("abc", "captcha")))
    }

    @Test
    fun `close is a dismissal`() {
        assertThat(HumanVerificationMessage.parse("""{"type":"CLOSE"}""")).isEqualTo(HumanVerificationMessage.Closed)
    }

    @Test
    fun `other, malformed and incomplete messages are ignored`() {
        listOf(
            """{"type":"RESIZE","payload":{"height":400}}""",
            """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"","type":"captcha"}}""",
            """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"abc"}}""",
            """{"type":"HUMAN_VERIFICATION_SUCCESS"}""",
            "not json",
            "\"a string\"",
        ).forEach { data ->
            assertThat(HumanVerificationMessage.parse(data)).isEqualTo(HumanVerificationMessage.Other)
        }
    }
}
