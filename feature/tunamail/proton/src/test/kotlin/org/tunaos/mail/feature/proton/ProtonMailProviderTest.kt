package org.tunaos.mail.feature.proton

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.feature.account.AccountId
import org.tunaos.proton.ProtonClient
import org.tunaos.proton.ProtonTransport

class ProtonMailProviderTest {
    private val requests = mutableListOf<String>()
    private val provider = ProtonMailProvider(
        client = ProtonClient(
            transport = ProtonTransport { request ->
                requests += request
                """{"version":1,"result":{}}"""
            },
        ),
        backendFactory = object : BackendFactory {
            override fun createBackend(accountId: AccountId) = error("not used")
        },
    )

    @Test
    fun `Proton's own domains are recognized`() {
        listOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me").forEach {
            assertThat(provider.isProviderDomain(it)).isTrue()
        }
        assertThat(provider.isProviderDomain("tuta.com")).isFalse()
        assertThat(provider.isProviderDomain("example.com")).isFalse()
    }

    @Test
    fun `custom domains are recognized by Proton's mail servers`() {
        assertThat(provider.isProviderMailServer("mail.protonmail.ch")).isTrue()
        assertThat(provider.isProviderMailServer("mailsec.protonmail.ch")).isTrue()
        assertThat(provider.isProviderMailServer("mail.tutanota.de")).isFalse()
        assertThat(provider.isProviderMailServer("mx.example.com")).isFalse()
    }

    @Test
    fun `revoke closes a Proton session and ignores anything else`() = runTest {
        provider.revoke("""{"login":"a@tuta.com","encrypted_passphrase_key":"x"}""")
        provider.revoke("not json")
        assertThat(requests.isEmpty()).isTrue()

        provider.revoke("""{"login":"a@proton.me","uid":"u","key_pass":"k"}""")
        assertThat(requests.single().contains(""""op":"revoke"""")).isTrue()
    }
}
