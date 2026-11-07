package org.tunaos.mail.feature.tuta

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class TutaPasswordStrengthTest {
    /** Scores Tuta's own `getPasswordStrength` gave for these, run under Node from its `PasswordUtils.ts`. */
    @Test
    fun `scores match Tuta's own`() {
        val cases = listOf(
            Triple("", emptyList(), 0),
            Triple("a", emptyList(), 9),
            Triple("aX%7#+tO", emptyList(), 80),
            Triple("dasisteinpasswort", emptyList(), 57),
            Triple("das-ist-ein-passwort", emptyList(), 80),
            Triple("testtesttesttest", emptyList(), 0),
            Triple("abcdefgh", emptyList(), 26),
            Triple("12345678", emptyList(), 26),
            Triple("7jeGABvliT", listOf("7jegabvlit"), 54),
            Triple("7jegabvlit", emptyList(), 77),
            Triple("ann@example.com", listOf("ann@example.com", "Ann"), 47),
            Triple("Grüße-Straße_42", emptyList(), 80),
            Triple("İstanbul99!", emptyList(), 80),
            Triple("😀😀😀😀abc", emptyList(), 56),
            Triple("qwertz", emptyList(), 21),
            Triple("ZXCVBNM,./", emptyList(), 43),
            Triple("line\nbreak\n\nxx", emptyList(), 80),
            Triple("Tr0ub", emptyList(), 50),
            Triple("aX%7#", emptyList(), 53),
            Triple("abc123", emptyList(), 53),
            Triple("Ünïcödé1", emptyList(), 79),
            Triple("zzzzzz", emptyList(), 12),
            Triple("aB3\$aB3\$", emptyList(), 68),
            Triple("pass1234", emptyList(), 31),
            Triple("xY7!", emptyList(), 44),
            Triple("mjudleugdh", emptyList(), 62),
            Triple("!i\$hL1D", emptyList(), 75),
            Triple("\u2028\u2028\u2028x", emptyList(), 26),
            Triple("ÄÖÜäöü12", emptyList(), 68),
            Triple("😀a😀b", emptyList(), 42),
            Triple("Sommer2024", listOf("sommer"), 54),
            Triple("qwertzuiop", emptyList(), 32),
            Triple("poiuytrewq", emptyList(), 32),
            Triple("9876abcd", emptyList(), 36),
        )
        for ((password, bad, expected) in cases) {
            assertThat(TutaPasswordStrength.score(password, bad), password).isEqualTo(expected)
        }
    }

    @Test
    fun `bad strings count whatever their case`() {
        assertThat(TutaPasswordStrength.score("7jegabvlit", listOf("7jeGABvliT"))).isEqualTo(37)
        assertThat(TutaPasswordStrength.score("7JEGABVLIT", listOf("7jegabvlit")))
            .isEqualTo(TutaPasswordStrength.score("7jegabvlit", listOf("7jegabvlit")))
        assertThat(TutaPasswordStrength.score("7jegabvlit", listOf("7390535")))
            .isEqualTo(TutaPasswordStrength.score("7jegabvlit"))
    }

    @Test
    fun `secure starts where Tuta stops asking`() {
        assertThat(TutaPasswordStrength.isSecure(63)).isFalse()
        assertThat(TutaPasswordStrength.isSecure(64)).isTrue()
    }

    @Test
    fun `the hint follows Tuta's three bands`() {
        assertThat(TutaPasswordsDialog.strengthHint(0)).isEqualTo(R.string.tuta_compose_password_strength_weak)
        assertThat(TutaPasswordsDialog.strengthHint(49)).isEqualTo(R.string.tuta_compose_password_strength_weak)
        assertThat(TutaPasswordsDialog.strengthHint(50)).isEqualTo(R.string.tuta_compose_password_strength_fair)
        assertThat(TutaPasswordsDialog.strengthHint(63)).isEqualTo(R.string.tuta_compose_password_strength_fair)
        assertThat(TutaPasswordsDialog.strengthHint(64)).isEqualTo(R.string.tuta_compose_password_strength_strong)
    }
}
