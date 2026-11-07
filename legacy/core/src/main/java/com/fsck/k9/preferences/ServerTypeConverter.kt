package com.fsck.k9.preferences

import net.thunderbird.core.common.mail.Protocols

internal object ServerTypeConverter {
    @JvmStatic
    fun toServerSettingsType(exportType: String): String = exportType.lowercase()

    /**
     * The export name of a server type. A type other than IMAP, POP3 and SMTP is an account type with a backend of its
     * own; it is exported by its name, upper case like the others, so the import reads it back as it was.
     */
    @JvmStatic
    fun fromServerSettingsType(serverSettingsType: String): String = when (serverSettingsType) {
        Protocols.IMAP -> "IMAP"
        Protocols.POP3 -> "POP3"
        Protocols.SMTP -> "SMTP"
        else -> serverSettingsType.uppercase()
    }

    /** Whether servers of [serverSettingsType] sign in with the password of the server settings. */
    @JvmStatic
    fun usesPassword(serverSettingsType: String): Boolean =
        serverSettingsType == Protocols.IMAP || serverSettingsType == Protocols.POP3 ||
            serverSettingsType == Protocols.SMTP
}
