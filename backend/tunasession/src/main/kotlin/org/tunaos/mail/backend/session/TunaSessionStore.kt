package org.tunaos.mail.backend.session

/**
 * The saved sign-in of a native provider account (a Tuta or Proton session object, as the provider's SDK
 * serializes it), by account UUID.
 *
 * A session is a credential: it lives only here, encrypted, never in the account's settings or in a log.
 * Calls block on file I/O; call them off the main thread.
 */
interface TunaSessionStore {
    /** The session saved for [accountUuid], or null when there is none (or it can no longer be decrypted). */
    fun get(accountUuid: String): String?

    /** Saves [session] for [accountUuid], replacing any earlier one. */
    fun put(accountUuid: String, session: String)

    /** Forgets the session of [accountUuid]; nothing happens when there is none. */
    fun remove(accountUuid: String)

    /** The UUIDs of the accounts with a saved session. */
    fun accountUuids(): Set<String>
}
