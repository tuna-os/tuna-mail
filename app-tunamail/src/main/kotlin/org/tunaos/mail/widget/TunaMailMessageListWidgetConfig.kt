package org.tunaos.mail.widget

import app.k9mail.feature.widget.message.list.MessageListWidgetConfig

class TunaMailMessageListWidgetConfig : MessageListWidgetConfig {
    override val providerClass = MessageListWidgetProvider::class.java
}
