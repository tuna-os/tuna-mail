package org.tunaos.mail.provider

import app.k9mail.core.android.common.provider.NotificationIconResourceProvider
import org.tunaos.mail.R

class TunaMailNotificationIconProvider : NotificationIconResourceProvider {
    override val pushNotificationIcon: Int
        get() = R.drawable.ic_logo_tunamail_white
}
