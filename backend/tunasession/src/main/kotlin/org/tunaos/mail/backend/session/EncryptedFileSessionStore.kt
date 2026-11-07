package org.tunaos.mail.backend.session

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [TunaSessionStore] keeping one AES-GCM encrypted file per account in [directory].
 *
 * The file holds the 12-byte IV and the ciphertext; the account UUID is the additional authenticated data, so a
 * file copied onto another account does not decrypt. [key] supplies the AES key: in the app it lives in the
 * Android Keystore and never leaves it ([AndroidKeystoreKey]). A file that no longer decrypts (the Keystore was
 * reset, the app's data restored onto another device) is deleted and reported as no session: the account then
 * has to sign in again.
 */
class EncryptedFileSessionStore(
    private val directory: File,
    private val key: () -> SecretKey,
) : TunaSessionStore {
    private val lock = Any()

    override fun get(accountUuid: String): String? = synchronized(lock) {
        val file = fileFor(accountUuid)
        if (!file.exists()) return null

        val sealed = file.readBytes()
        val session = try {
            open(accountUuid, sealed)
        } catch (_: GeneralSecurityException) {
            null
        }
        if (session == null) file.delete()
        session
    }

    override fun put(accountUuid: String, session: String): Unit = synchronized(lock) {
        val file = fileFor(accountUuid)
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeBytes(seal(accountUuid, session))
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }

    override fun replace(accountUuid: String, session: String): String? = synchronized(lock) {
        get(accountUuid).also { put(accountUuid, session) }
    }

    override fun remove(accountUuid: String): Unit = synchronized(lock) {
        fileFor(accountUuid).delete()
    }

    override fun accountUuids(): Set<String> = synchronized(lock) {
        directory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(FILE_SUFFIX) }
            .map { it.removeSuffix(FILE_SUFFIX) }
            .toSet()
    }

    private fun fileFor(accountUuid: String): File {
        require(accountUuid.isNotEmpty() && accountUuid.all { it.isLetterOrDigit() || it == '-' }) {
            "not an account UUID"
        }
        return File(directory, "$accountUuid$FILE_SUFFIX")
    }

    private fun seal(accountUuid: String, session: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(accountUuid.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected IV length" }
        return iv + cipher.doFinal(session.toByteArray(Charsets.UTF_8))
    }

    private fun open(accountUuid: String, sealed: ByteArray): String? {
        if (sealed.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH))
        cipher.updateAAD(accountUuid.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH).toString(Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val FILE_SUFFIX = ".session"
    }
}
