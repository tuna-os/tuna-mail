package org.tunaos.mail.provider

import androidx.navigation.NavGraphBuilder
import net.thunderbird.backend.api.BackendFactory

/**
 * A native mail provider Tuna Mail offers next to IMAP and POP3 (Tuta, Proton).
 *
 * Each provider module binds one with a qualifier of its own, `single<TunaMailProvider>(named("tuta")) { ... }`;
 * [tunaMailProviderModule] collects them all, so the backends, the address discovery, the setup screens and the
 * session cleanup need no per-provider wiring.
 */
interface TunaMailProvider {
    /** The `ServerSettings.type` of the provider's accounts, e.g. `tuta`. */
    val serverType: String

    /** Creates the backends of the provider's accounts. */
    val backendFactory: BackendFactory

    /** Whether addresses at [domain] (lower case, e.g. `tuta.com`) are the provider's. */
    fun isProviderDomain(domain: String): Boolean

    /**
     * Registers the provider's sign-in screens at [route]. They read the address and password from the account being
     * set up (`AccountStateRepository`), sign in, hand the session to [TunaSignInHandoff.complete] and call
     * [onSignedIn].
     */
    fun registerSignIn(navGraphBuilder: NavGraphBuilder, route: String, onBack: () -> Unit, onSignedIn: () -> Unit)

    /**
     * Closes [session] on the server, for an account that was removed. Best effort: failures are logged, not thrown,
     * and a session that is not this provider's is ignored.
     */
    suspend fun revoke(session: String)
}
