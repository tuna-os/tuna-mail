package org.tunaos.mail.backend.proton

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fsck.k9.mail.AuthenticationFailedException
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.tunaos.proton.ProtonClient
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonTransport

class StoredSessionProtonApiTest {
    private var stored: String? = null
    private val requests = mutableListOf<String>()
    private val replies = ArrayDeque<String>()
    private val client = ProtonClient(
        transport = ProtonTransport { request ->
            requests += request
            replies.removeFirst()
        },
    )
    private val api = StoredSessionProtonApi(
        loadSession = { stored },
        saveSession = { stored = it },
        client = client,
    )

    @Test
    fun `without a stored session every call is an authentication failure`() = runTest {
        assertFailure { api.sync(limit = 1, folder = "0") }.isInstanceOf<AuthenticationFailedException>()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a session the reply rotates is saved`() = runTest {
        stored = session("old")
        replies += syncReply(session("new"))

        api.sync(limit = 1, folder = "0")

        assertThat(stored).isEqualTo(session("new"))
        assertThat(requests.single()).contains(""""access_token":"old"""")
    }

    @Test
    fun `a refused session is dropped so a new sign-in is picked up`() = runTest {
        stored = session("revoked")
        replies += """{"version":1,"error":{"code":"session_revoked"}}"""
        assertFailure { api.sync(limit = 1, folder = "0") }.isInstanceOf<ProtonException.Session>()

        stored = session("fresh")
        replies += syncReply(session("fresh"))
        api.sync(limit = 1, folder = "0")

        assertThat(requests.last()).contains(""""access_token":"fresh"""")
    }

    private fun session(token: String) = """{"login":"a@proton.me","access_token":"$token"}"""

    private fun syncReply(session: String) =
        """{"version":1,"result":{"folders":[],"folder":"0","messages":[],"exists":0,"offset":0,""" +
            """"event_id":"e1","session":$session}}"""
}
