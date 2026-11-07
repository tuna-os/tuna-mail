package org.tunaos.mail.feature.proton

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import org.tunaos.mail.backend.proton.ProtonPollConditions

/** The device's screen and network state, read fresh each time the Proton event poll asks. */
class AndroidProtonPollConditions(context: Context) : ProtonPollConditions {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override val isScreenOn: Boolean
        get() = powerManager?.isInteractive ?: true

    override val isNetworkMetered: Boolean
        get() = connectivityManager?.isActiveNetworkMetered ?: false
}
