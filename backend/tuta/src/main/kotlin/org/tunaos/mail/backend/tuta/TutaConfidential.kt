package org.tunaos.mail.backend.tuta

import com.fsck.k9.mail.Message
import java.util.concurrent.ConcurrentHashMap
import org.tunaos.tuta.ContactPassword

/**
 * The header the composer writes into a Tuta account's message, `yes` or `no`: whether it goes confidential, end to
 * end encrypted for everyone, recipients outside Tuta reading it behind a password. Tuta builds the mail from its
 * parts, so the header never leaves the device; it lets a queued message and a draft keep the choice.
 */
const val TUTA_CONFIDENTIAL_HEADER = "X-Tuta-Confidential"

/** The value of [TUTA_CONFIDENTIAL_HEADER] for [confidential]. */
fun confidentialHeaderValue(confidential: Boolean): String = if (confidential) "yes" else "no"

/** Whether [message] is to go confidential, as its [TUTA_CONFIDENTIAL_HEADER] says. */
fun isConfidential(message: Message): Boolean =
    message.getHeader(TUTA_CONFIDENTIAL_HEADER).firstOrNull()?.trim().equals("yes", ignoreCase = true)

/**
 * The passwords of a confidential message's recipients outside Tuta, by the message's Message-ID, handed from the
 * composer to the send. They are kept in memory only, never in the message, its draft, the Outbox or a log: a message
 * still queued when the process ends reads them again from the Tuta contacts, which keep every password a
 * password-protected send used. One instance serves the whole app.
 */
class TutaRecipientPasswords {
    private val byMessage = ConcurrentHashMap<String, Map<String, String>>()

    /** Keeps [passwords] (address to password) for the message [messageId]; blank ones are dropped. */
    fun put(messageId: String, passwords: Map<String, String>) {
        val kept = passwords.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        if (kept.isEmpty()) byMessage.remove(messageId) else byMessage[messageId] = kept
    }

    /** The passwords kept for [messageId], none when there are none. */
    fun get(messageId: String): Map<String, String> = byMessage[messageId].orEmpty()

    /** Forgets the passwords of [messageId], once it went out. */
    fun remove(messageId: String) {
        byMessage.remove(messageId)
    }

    override fun toString(): String = "TutaRecipientPasswords(${byMessage.size} messages, <redacted>)"
}

/**
 * The passwords a confidential send to [addresses] goes with: the one the composer handed over in [typed] first, then,
 * for a recipient still without one, the one its Tuta contact keeps ([stored], as `contact_passwords` reads them).
 * Addresses match in any case, and blank passwords are none. Recipients on Tuta need none and get none.
 */
internal fun sendPasswords(
    addresses: List<String>,
    typed: Map<String, String>,
    stored: List<ContactPassword>,
): Map<String, String> {
    fun Map<String, String>.forAddress(address: String) =
        entries.firstOrNull { it.key.equals(address, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

    val result = linkedMapOf<String, String>()
    for (address in addresses.distinctBy { it.lowercase() }) {
        val contact = stored.firstOrNull { it.address.equals(address, ignoreCase = true) }
        if (contact?.external == false) continue
        val password = typed.forAddress(address) ?: contact?.password?.trim()?.takeIf { it.isNotEmpty() }
        if (password != null) result[address] = password
    }
    return result
}
