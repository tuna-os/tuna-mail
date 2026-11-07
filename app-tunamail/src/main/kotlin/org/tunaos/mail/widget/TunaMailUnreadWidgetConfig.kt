package org.tunaos.mail.widget

import app.k9mail.feature.widget.unread.UnreadWidgetConfig

class TunaMailUnreadWidgetConfig : UnreadWidgetConfig {
    override val providerClass = UnreadWidgetProvider::class.java
}
