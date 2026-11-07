package org.tunaos.mail.feature.tuta

import org.tunaos.mail.backend.tuta.TUTA_CONFIDENTIAL_HEADER
import org.tunaos.mail.backend.tuta.confidentialHeaderValue
import org.tunaos.tuta.ContactPassword

/**
 * One composer's Tuta delivery: whether the message goes confidential, which recipients are outside Tuta, and the
 * password each of those needs. The passwords live here, in memory, and go nowhere but [passwordsFor]: never into a
 * draft, the instance state or a log.
 */
class TutaComposeState {
    /** End to end encrypted for everyone, recipients outside Tuta reading it behind a password. */
    var isConfidential: Boolean = false
        private set

    /** The user chose [isConfidential] in this composer, so an opened draft's choice no longer overrides it. */
    private var isChosen = false

    private val reach = TutaRecipientReach()
    private val passwords = mutableMapOf<String, String>()

    /** The recipients and passwords the user last confirmed for a send, so the same send does not ask twice. */
    private var confirmed: Map<String, String>? = null

    fun setConfidential(confidential: Boolean) {
        isConfidential = confidential
        isChosen = true
    }

    /** A draft opened with [headerValue] in its [TUTA_CONFIDENTIAL_HEADER], unless the user chose already. */
    fun onDraftOpened(headerValue: String?) {
        if (isChosen || headerValue == null) return
        isConfidential = headerValue.trim().equals(confidentialHeaderValue(true), ignoreCase = true)
    }

    /**
     * Takes in what the account's `contact_passwords` said about [asked] (see [TutaRecipientReach.adopt]). The password
     * a contact keeps fills in only one not typed yet.
     */
    fun adopt(asked: List<String>, rows: List<ContactPassword>?) {
        reach.adopt(asked, rows)
        for (row in rows.orEmpty()) {
            val stored = row.password?.trim().orEmpty()
            if (row.external != false && stored.isNotEmpty()) passwords.putIfAbsent(addressKey(row.address), stored)
        }
    }

    fun password(address: String): String = passwords[addressKey(address)].orEmpty()

    /** Sets [address]'s password; Tuta trims passwords, and a blank one is none. */
    fun setPassword(address: String, password: String) {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) passwords.remove(addressKey(address)) else passwords[addressKey(address)] = trimmed
    }

    /** The passwords of [recipients] that need one, by address as written. */
    fun passwordsFor(recipients: List<String>): Map<String, String> = reach.needingPasswords(recipients)
        .mapNotNull { address -> passwords[addressKey(address)]?.let { address to it } }
        .toMap()

    /** What a send to [recipients] still needs before it goes. */
    fun check(recipients: List<String>): SendCheck {
        val unknown = reach.unknown(recipients)
        val needing = reach.needingPasswords(recipients)
        val isConfirmed = confirmed == passwordsFor(recipients) && needing.all { password(it).isNotEmpty() }
        return when {
            !isConfidential -> SendCheck.Ready
            unknown.isNotEmpty() -> SendCheck.Lookup(unknown)
            needing.isEmpty() || isConfirmed -> SendCheck.Ready
            else -> SendCheck.AskPasswords(needing)
        }
    }

    /** The user confirmed the passwords for a send to [recipients]. */
    fun confirm(recipients: List<String>) {
        confirmed = passwordsFor(recipients)
    }

    /** The headers of the message built as a draft or for sending: whether it is confidential, never a password. */
    fun headers(): Map<String, String> = mapOf(TUTA_CONFIDENTIAL_HEADER to confidentialHeaderValue(isConfidential))

    override fun toString(): String = "TutaComposeState(confidential=$isConfidential, passwords=<redacted>)"
}

/** Which recipients are outside Tuta, as the account said. */
private class TutaRecipientReach {
    /** Whether each recipient is outside Tuta, by address in lower case; null when the account could not say. */
    private val outside = mutableMapOf<String, Boolean?>()

    /** The recipients among [addresses] the account has not been asked about yet, each once. */
    fun unknown(addresses: List<String>): List<String> = unique(addresses).filter { addressKey(it) !in outside }

    /**
     * Takes in whether each of [rows] is outside Tuta. An address of [asked] the answer left out, or all of them when
     * [rows] is null (the lookup failed), is not known to be on Tuta.
     */
    fun adopt(asked: List<String>, rows: List<ContactPassword>?) {
        for (row in rows.orEmpty()) outside[addressKey(row.address)] = row.external
        for (address in asked) outside.putIfAbsent(addressKey(address), null)
    }

    /**
     * The recipients among [addresses] that need a password: those the account said are outside Tuta, and, when it
     * could not say, those not on one of Tuta's own domains. Tuta decides again at the send, and ignores a password
     * for a recipient on Tuta.
     */
    fun needingPasswords(addresses: List<String>): List<String> = unique(addresses).filter { address ->
        outside[addressKey(address)] ?: !isTutaDomain(address.substringAfterLast('@'))
    }

    private fun unique(addresses: List<String>) = addresses.filter { it.isNotBlank() }.distinctBy(::addressKey)
}

private fun addressKey(address: String) = address.trim().lowercase()

/** What a send has to do before it goes. */
sealed interface SendCheck {
    data object Ready : SendCheck

    /** The account is to be asked whether these recipients are outside Tuta. */
    data class Lookup(val addresses: List<String>) : SendCheck

    /** These recipients outside Tuta need their passwords entered or confirmed. */
    data class AskPasswords(val addresses: List<String>) : SendCheck
}
