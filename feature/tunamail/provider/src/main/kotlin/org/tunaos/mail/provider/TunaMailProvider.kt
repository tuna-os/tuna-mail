package org.tunaos.mail.provider

import androidx.compose.runtime.Composable
import net.thunderbird.backend.api.BackendFactory

/**
 * A native mail provider Tuna Mail offers next to IMAP and POP3 (Tuta, Proton).
 *
 * Each provider module binds one with a qualifier of its own, `single<TunaMailProvider>(named("tuta")) { ... }`;
 * [tunaMailProviderModule] collects them all, so the backends, the address discovery, the setup screens, signing an
 * account in again and the session cleanup need no per-provider wiring.
 */
interface TunaMailProvider {
    /** The `ServerSettings.type` of the provider's accounts, e.g. `tuta`. */
    val serverType: String

    /** The provider's name as its users know it, e.g. `Tuta`. A brand name, so it is not translated. */
    val displayName: String

    /** Creates the backends of the provider's accounts. */
    val backendFactory: BackendFactory

    /** Whether addresses at [domain] (lower case, e.g. `tuta.com`) are the provider's. */
    fun isProviderDomain(domain: String): Boolean

    /**
     * Whether [host] (lower case, without a trailing dot), a mail server a domain's MX record names, is one of the
     * provider's: the domain is a custom domain the provider hosts.
     */
    fun isProviderMailServer(host: String): Boolean

    /**
     * The provider's sign-in screens for [target]: they sign in [TunaSignInTarget.email], hand the session to
     * [TunaSignInTarget.complete] and call [onSignedIn]. [onBack] leaves them.
     */
    @Composable
    fun SignInScreen(target: TunaSignInTarget, onBack: () -> Unit, onSignedIn: () -> Unit)

    /**
     * Closes [session] on the server, for an account that was removed or signed in again. Best effort: failures are
     * logged, not thrown, and a session that is not this provider's is ignored.
     */
    suspend fun revoke(session: String)
}
