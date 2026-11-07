package app.k9mail.autodiscovery.api

import com.fsck.k9.mail.ServerSettings

/**
 * Settings for an account type with its own backend rather than IMAP/POP3 and SMTP: the [AutoDiscovery] that found
 * them supplies the finished [ServerSettings], and the same object serves as the incoming and outgoing settings.
 */
interface DirectServerSettings : IncomingServerSettings, OutgoingServerSettings {
    /** The account's server settings, given the [password] the user entered (if any). */
    fun toServerSettings(password: String?): ServerSettings
}
