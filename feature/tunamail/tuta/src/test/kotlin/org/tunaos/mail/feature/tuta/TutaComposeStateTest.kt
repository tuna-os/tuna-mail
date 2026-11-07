package org.tunaos.mail.feature.tuta

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.tunaos.mail.backend.tuta.TUTA_CONFIDENTIAL_HEADER
import org.tunaos.tuta.ContactPassword

class TutaComposeStateTest {
    private val state = TutaComposeState()

    @Test
    fun `a message that is not confidential is ready and says so in its header`() {
        assertThat(state.check(listOf(OUTSIDE))).isEqualTo(SendCheck.Ready)
        assertThat(state.headers()).isEqualTo(mapOf(TUTA_CONFIDENTIAL_HEADER to "no"))
    }

    @Test
    fun `a confidential message first asks the account about its recipients`() {
        state.setConfidential(true)

        assertThat(state.check(listOf(OUTSIDE, ON_TUTA, "  ", OUTSIDE.uppercase())))
            .isEqualTo(SendCheck.Lookup(listOf(OUTSIDE, ON_TUTA)))
        assertThat(state.headers()).isEqualTo(mapOf(TUTA_CONFIDENTIAL_HEADER to "yes"))
    }

    @Test
    fun `only recipients outside Tuta are asked for a password`() {
        state.setConfidential(true)
        state.adopt(listOf(OUTSIDE, ON_TUTA), listOf(external(OUTSIDE), onTuta(ON_TUTA)))

        assertThat(state.check(listOf(OUTSIDE, ON_TUTA))).isEqualTo(SendCheck.AskPasswords(listOf(OUTSIDE)))
    }

    @Test
    fun `a message only to Tuta needs no password`() {
        state.setConfidential(true)
        state.adopt(listOf(ON_TUTA), listOf(onTuta(ON_TUTA)))

        assertThat(state.check(listOf(ON_TUTA))).isEqualTo(SendCheck.Ready)
    }

    @Test
    fun `when the account cannot say, recipients off Tuta's domains need a password`() {
        state.setConfidential(true)
        state.adopt(listOf(OUTSIDE, "dan@tuta.io"), rows = null)

        assertThat(state.check(listOf(OUTSIDE, "dan@tuta.io"))).isEqualTo(SendCheck.AskPasswords(listOf(OUTSIDE)))
    }

    @Test
    fun `a password the contact keeps fills in one not typed, and is still confirmed once`() {
        state.setConfidential(true)
        state.setPassword("bob@example.org", "Typed-Pass-1")
        state.adopt(
            listOf(OUTSIDE, "bob@example.org"),
            listOf(external(OUTSIDE, "Stored-Pass-2"), external("bob@example.org", "Stored-Pass-3")),
        )
        val recipients = listOf(OUTSIDE, "bob@example.org")

        assertThat(state.password(OUTSIDE)).isEqualTo("Stored-Pass-2")
        assertThat(state.password("BOB@example.org")).isEqualTo("Typed-Pass-1")
        assertThat(state.check(recipients)).isEqualTo(SendCheck.AskPasswords(recipients))

        state.confirm(recipients)

        assertThat(state.check(recipients)).isEqualTo(SendCheck.Ready)
        assertThat(state.passwordsFor(recipients))
            .isEqualTo(mapOf(OUTSIDE to "Stored-Pass-2", "bob@example.org" to "Typed-Pass-1"))
    }

    @Test
    fun `a changed password or a new recipient asks again`() {
        state.setConfidential(true)
        state.adopt(listOf(OUTSIDE), listOf(external(OUTSIDE)))
        state.setPassword(OUTSIDE, "First-Pass-1")
        state.confirm(listOf(OUTSIDE))

        state.setPassword(OUTSIDE, "Second-Pass-2")
        assertThat(state.check(listOf(OUTSIDE))).isEqualTo(SendCheck.AskPasswords(listOf(OUTSIDE)))

        state.confirm(listOf(OUTSIDE))
        state.adopt(listOf("eve@example.net"), listOf(external("eve@example.net")))
        assertThat(state.check(listOf(OUTSIDE, "eve@example.net")))
            .isEqualTo(SendCheck.AskPasswords(listOf(OUTSIDE, "eve@example.net")))
    }

    @Test
    fun `a confirmed send with a blank password is not ready`() {
        state.setConfidential(true)
        state.adopt(listOf(OUTSIDE), listOf(external(OUTSIDE)))
        state.confirm(listOf(OUTSIDE))

        assertThat(state.check(listOf(OUTSIDE))).isEqualTo(SendCheck.AskPasswords(listOf(OUTSIDE)))
    }

    @Test
    fun `passwords are trimmed and a blank one is none`() {
        state.setPassword(OUTSIDE, "  Spaced-Pass-1  ")
        assertThat(state.password(OUTSIDE)).isEqualTo("Spaced-Pass-1")

        state.setPassword(OUTSIDE, "   ")
        assertThat(state.password(OUTSIDE)).isEqualTo("")
    }

    @Test
    fun `an opened draft sets confidential unless the user chose`() {
        state.onDraftOpened("yes")
        assertThat(state.isConfidential).isTrue()

        state.onDraftOpened("no")
        assertThat(state.isConfidential).isFalse()

        state.setConfidential(true)
        state.onDraftOpened("no")
        assertThat(state.isConfidential).isTrue()
    }

    @Test
    fun `a draft without the header keeps the default`() {
        state.onDraftOpened(null)

        assertThat(state.isConfidential).isFalse()
    }

    @Test
    fun `headers and the printed state never hold a password`() {
        state.setConfidential(true)
        state.setPassword(OUTSIDE, "Secret-Pass-9")

        assertThat(state.headers().values.joinToString()).doesNotContain("Secret-Pass-9")
        assertThat(state.toString()).doesNotContain("Secret-Pass-9")
    }

    private fun external(address: String, password: String? = null) =
        ContactPassword(address = address, external = true, password = password)

    private fun onTuta(address: String) = ContactPassword(address = address, external = false)

    private companion object {
        const val OUTSIDE = "ann@example.com"
        const val ON_TUTA = "carl@tuta.com"
    }
}
