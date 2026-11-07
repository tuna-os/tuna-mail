package org.tunaos.mail.provider

import app.k9mail.autodiscovery.api.AutoDiscovery
import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.autodiscovery.api.AutoDiscoveryRunnable
import app.k9mail.autodiscovery.api.DirectServerSettings
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import net.thunderbird.core.common.mail.EmailAddress

/** Recognizes the addresses of a [TunaMailProvider] by their domain, before any network lookup. */
class TunaProviderAutoDiscovery(
    private val providers: List<TunaMailProvider>,
) : AutoDiscovery {
    override fun initDiscovery(email: EmailAddress): List<AutoDiscoveryRunnable> {
        return listOf(
            AutoDiscoveryRunnable {
                val domain = email.domain.normalized
                val provider = providers.firstOrNull { it.isProviderDomain(domain) }
                if (provider == null) {
                    AutoDiscoveryResult.NoUsableSettingsFound
                } else {
                    val settings = TunaDirectServerSettings(provider.serverType, domain, email.address)
                    AutoDiscoveryResult.Settings(
                        incomingServerSettings = settings,
                        outgoingServerSettings = settings,
                        isTrusted = true,
                        source = "TunaMail:${provider.serverType}",
                    )
                }
            },
        )
    }
}

/** A provider account's settings, as the setup flow carries them until the account is created. */
data class TunaDirectServerSettings(
    val serverType: String,
    val host: String,
    val email: String,
) : DirectServerSettings {
    override fun toServerSettings(password: String?): ServerSettings =
        tunaServerSettings(serverType = serverType, host = host, email = email, password = password)
}

/**
 * The server settings of a provider account, incoming and outgoing alike: the provider's backend does both. The
 * [password] is only carried through the setup flow to the provider's sign-in screen, which removes it
 * ([TunaSignInHandoff.complete]); a saved account never has one.
 */
fun tunaServerSettings(serverType: String, host: String, email: String, password: String?): ServerSettings {
    return ServerSettings(
        type = serverType,
        host = host,
        port = HTTPS_PORT,
        connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
        authenticationType = AuthType.PLAIN,
        username = email,
        password = password,
        clientCertificateAlias = null,
    )
}

private const val HTTPS_PORT = 443
