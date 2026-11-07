package org.tunaos.mail.provider

import app.k9mail.autodiscovery.api.AutoDiscovery
import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.autodiscovery.api.AutoDiscoveryRunnable
import app.k9mail.autodiscovery.api.DirectServerSettings
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.thunderbird.core.common.mail.EmailAddress
import net.thunderbird.legacy.logging.Log
import org.minidns.dnsname.InvalidDnsNameException

/**
 * Recognizes the addresses of a [TunaMailProvider]: by their domain, before any network lookup, and otherwise by the
 * mail servers the domain's MX record names, for a custom domain the provider hosts. An MX answer that is not signed
 * (DNSSEC) gives an untrusted result, which the user has to confirm.
 */
class TunaProviderAutoDiscovery(
    private val providers: List<TunaMailProvider>,
    private val mxLookup: TunaMxLookup,
) : AutoDiscovery {
    override fun initDiscovery(email: EmailAddress): List<AutoDiscoveryRunnable> {
        return listOf(
            AutoDiscoveryRunnable {
                val domain = email.domain.normalized
                val provider = providers.firstOrNull { it.isProviderDomain(domain) }
                if (provider != null) {
                    settings(provider, domain, email, isTrusted = true, source = "TunaMail:${provider.serverType}")
                } else {
                    discoverByMailServer(domain, email)
                }
            },
        )
    }

    private suspend fun discoverByMailServer(domain: String, email: EmailAddress): AutoDiscoveryResult {
        val mx = lookUpMailServers(domain) ?: return AutoDiscoveryResult.NoUsableSettingsFound
        val provider = mx.hosts.firstNotNullOfOrNull { host -> providers.firstOrNull { it.isProviderMailServer(host) } }
            ?: return AutoDiscoveryResult.NoUsableSettingsFound
        val source = "TunaMail:${provider.serverType}:mx"
        return settings(provider, domain, email, isTrusted = mx.isTrusted, source = source)
    }

    private suspend fun lookUpMailServers(domain: String): MxHosts? {
        return try {
            runInterruptible(Dispatchers.IO) { mxLookup.lookup(domain) }
        } catch (e: IOException) {
            Log.d(TAG, e, "Could not look up the MX record of %s", domain)
            null
        } catch (e: InvalidDnsNameException) {
            Log.d(TAG, e, "Not a valid DNS name: %s", domain)
            null
        }
    }

    private fun settings(
        provider: TunaMailProvider,
        domain: String,
        email: EmailAddress,
        isTrusted: Boolean,
        source: String,
    ): AutoDiscoveryResult.Settings {
        val settings = TunaDirectServerSettings(provider.serverType, domain, email.address)
        return AutoDiscoveryResult.Settings(
            incomingServerSettings = settings,
            outgoingServerSettings = settings,
            isTrusted = isTrusted,
            source = source,
        )
    }

    private companion object {
        const val TAG = "TunaProviderAutoDiscovery"
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
