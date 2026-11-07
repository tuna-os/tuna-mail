package org.tunaos.mail.backend.session

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test

class EncryptedFileSessionStoreTest {
    private val directory: File = Files.createTempDirectory("sessions").toFile()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = EncryptedFileSessionStore(directory) { key }

    @Test
    fun `put then get returns the session`() {
        store.put(ACCOUNT, SESSION)

        assertThat(store.get(ACCOUNT)).isEqualTo(SESSION)
    }

    @Test
    fun `the file on disk does not contain the session in plain text`() {
        store.put(ACCOUNT, SESSION)

        val bytes = File(directory, "$ACCOUNT.session").readBytes().toString(Charsets.ISO_8859_1)
        assertThat(bytes).doesNotContain("access_token")
    }

    @Test
    fun `put replaces the earlier session`() {
        store.put(ACCOUNT, SESSION)
        store.put(ACCOUNT, "{\"other\":1}")

        assertThat(store.get(ACCOUNT)).isEqualTo("{\"other\":1}")
    }

    @Test
    fun `remove forgets the session`() {
        store.put(ACCOUNT, SESSION)

        store.remove(ACCOUNT)

        assertThat(store.get(ACCOUNT)).isNull()
    }

    @Test
    fun `a session copied onto another account does not decrypt and is dropped`() {
        store.put(ACCOUNT, SESSION)
        val stolen = File(directory, "$OTHER_ACCOUNT.session")
        File(directory, "$ACCOUNT.session").copyTo(stolen)

        assertThat(store.get(OTHER_ACCOUNT)).isNull()
        assertThat(stolen.exists()).isFalse()
    }

    @Test
    fun `a session sealed with another key is dropped`() {
        store.put(ACCOUNT, SESSION)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThat(EncryptedFileSessionStore(directory) { otherKey }.get(ACCOUNT)).isNull()
    }

    @Test
    fun `accountUuids lists the accounts with a session`() {
        store.put(ACCOUNT, SESSION)
        store.put(OTHER_ACCOUNT, SESSION)
        store.remove(OTHER_ACCOUNT)

        assertThat(store.accountUuids()).isEqualTo(setOf(ACCOUNT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an account id with a path separator is refused`() {
        store.put("../escape", SESSION)
    }

    private companion object {
        const val ACCOUNT = "0b1f6a8e-1c1e-4c43-9a43-5d1c1f0e2a11"
        const val OTHER_ACCOUNT = "9c1f6a8e-1c1e-4c43-9a43-5d1c1f0e2a99"
        const val SESSION = "{\"login\":\"a@tuta.com\",\"access_token\":\"secret\"}"
    }
}
