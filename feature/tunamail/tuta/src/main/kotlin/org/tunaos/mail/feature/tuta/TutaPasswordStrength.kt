package org.tunaos.mail.feature.tuta

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How strong a password for a recipient outside Tuta is, scored as Tuta's own app scores it (`getPasswordStrength` in
 * its `PasswordUtils.ts`), so a password Tuta would warn about warns here too. Kotlin strings are UTF-16, as
 * JavaScript's are, so the counts match Tuta's character for character.
 */
object TutaPasswordStrength {
    /** The score of a full strength bar. */
    const val MAX = 80

    /** The lowest score Tuta calls secure; a weaker password is the user's to confirm, never refused. */
    const val SECURE = 64

    /** Below this Tuta shows the bar in its error color rather than the warning one. */
    const val MEDIUM = 50

    /** Keyboard rows (German and English) and the alphabet: a run of four or more, either way round, costs strength. */
    private val BAD_SEQUENCES = listOf(
        "^1234567890ß´",
        "°!\"§$%&/()=?`",
        "qwertzuiopü+",
        "asdfghjklöä#",
        "<yxcvbnm,.-",
        "`1234567890-=",
        "~!@#$%^&*()_+",
        "qwertyuiop[]",
        "asdfghjkl'\\",
        "\\zxcvbnm,./",
        "abcdefghijklmnopqrstuvwxyz",
    )

    /** Words Tuta always counts against a password. */
    private val BAD_STRINGS = listOf("passwort", "password", "tutanota", "free", "test", "keemail", "tutamail")

    private const val MIN_SEQUENCE_LENGTH = 4
    private const val LENGTH_WEIGHT = 11
    private const val MISSING_KIND_WEIGHT = 3
    private const val CONSECUTIVE_WEIGHT = 2
    private const val SAME_CHAR_WEIGHT = 5
    private const val SEQUENCE_WEIGHT = 4
    private const val KINDS = 4.0
    private const val ALLOWED_RUN = 2

    /**
     * The strength of [password], 0 to [MAX]. [badStrings] are the words that make it guessable for this recipient:
     * the sender's address and name and the recipient's, as Tuta's `getPasswordStrengthForUser` passes them.
     */
    fun score(password: String, badStrings: List<String> = emptyList()): Int {
        if (password.isEmpty()) return 0
        val length = password.length
        val lower = password.count(::isLower)
        val upper = password.count(::isUpper)
        val digits = password.count(::isDigit)
        val other = length - lower - upper - digits

        fun beyondAllowed(run: Int) = max(run - ALLOWED_RUN, 0).toDouble()
        val consecutive = beyondAllowed(longestRun(password, ::isLower)) +
            beyondAllowed(longestRun(password, ::isUpper)) +
            beyondAllowed(longestRun(password, ::isDigit)) +
            beyondAllowed(longestRun(password, ::isOther)) +
            beyondAllowed(longestRepeat(password))

        // At best a quarter of the password is each of the four kinds.
        val quarter = length / KINDS
        val missing = listOf(lower, upper, digits, other).sumOf { max(quarter - it, 0.0) }

        val lowered = password.lowercase()
        val sequences = BAD_SEQUENCES + BAD_SEQUENCES.map { it.reversed() }
        val bad = (badStrings + BAD_STRINGS).map { it.lowercase() }.filter { it.isNotEmpty() }

        val score = length * LENGTH_WEIGHT -
            missing * MISSING_KIND_WEIGHT -
            consecutive * CONSECUTIVE_WEIGHT -
            sameChars(password) * SAME_CHAR_WEIGHT -
            sequenceChars(lowered, sequences) * SEQUENCE_WEIGHT -
            sequenceChars(lowered, bad) * SEQUENCE_WEIGHT
        // JavaScript's Math.round takes .5 up; every score that could end in one is clamped to 0 first or positive.
        return min(max(score.roundToInt(), 0), MAX)
    }

    /** Whether Tuta takes a password of strength [score] without asking. */
    fun isSecure(score: Int): Boolean = score >= SECURE

    private fun isLower(c: Char) = c in 'a'..'z' || c == ' '

    private fun isUpper(c: Char) = c in 'A'..'Z'

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun isOther(c: Char) = !isLower(c) && !isUpper(c) && !isDigit(c)

    /** What JavaScript's `.` leaves out: the line terminators. */
    private fun isLineTerminator(c: Char) = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /** The longest run of characters that all pass [kind]. */
    private fun longestRun(text: String, kind: (Char) -> Boolean): Int {
        var longest = 0
        var run = 0
        for (c in text) {
            run = if (kind(c)) run + 1 else 0
            longest = max(longest, run)
        }
        return longest
    }

    /** The longest run of one repeated character, as `/(.)\1+/g` finds them: one alone is no match. */
    private fun longestRepeat(text: String): Int {
        var longest = 0
        var i = 0
        while (i < text.length) {
            var j = i + 1
            while (j < text.length && text[j] == text[i]) j++
            if (j - i >= 2 && !isLineTerminator(text[i])) longest = max(longest, j - i)
            i = j
        }
        return longest
    }

    /** How many UTF-16 units repeat a character seen before, counting by code point as `for (const c of s)` does. */
    private fun sameChars(text: String): Int {
        val distinct = text.codePoints().distinct().count().toInt()
        return text.length - distinct
    }

    /**
     * How many characters of [text] are in runs of four or more found in one of [sequences], each run taken at its
     * longest and counted once.
     */
    private fun sequenceChars(text: String, sequences: List<String>): Int {
        var count = 0
        var i = 0
        while (i + MIN_SEQUENCE_LENGTH <= text.length) {
            var found = 0
            var length = MIN_SEQUENCE_LENGTH
            while (i + length <= text.length) {
                val part = text.substring(i, i + length)
                if (sequences.any { it.contains(part) }) found = length
                length++
            }
            if (found > 0) {
                count += found
                i += found
            } else {
                i++
            }
        }
        return count
    }
}
