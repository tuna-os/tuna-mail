package org.tunaos.mail.backend.session

import android.content.Context
import java.io.File
import org.koin.dsl.module

/** Binds the app's [TunaSessionStore]: encrypted files in the no-backup directory, keyed in the Keystore. */
val tunaSessionModule = module {
    single<TunaSessionStore> {
        val context: Context = get()
        EncryptedFileSessionStore(
            directory = File(context.noBackupFilesDir, "tuna-sessions"),
            key = AndroidKeystoreKey::getOrCreate,
        )
    }
}
