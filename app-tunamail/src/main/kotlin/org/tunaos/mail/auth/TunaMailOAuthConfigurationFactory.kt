package org.tunaos.mail.auth

import java.net.URLEncoder
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory

/**
 * OAuth configurations for Tuna Mail.
 *
 * OAuth client registrations belong to one app (its package name and signing key), so Tuna Mail cannot use the ones
 * K-9 Mail and Thunderbird ship. The client ids come from the build (see docs/tunamail/oauth.md), and a provider whose
 * id is empty is left out: the account setup then offers password sign-in for its servers instead of OAuth.
 *
 * @param applicationId The application id of the running build, used in the redirect URIs.
 * @param googleClientId Client id of the Google "Android" OAuth client, or empty.
 * @param microsoftClientId Application (client) id of the Microsoft Entra app registration, or empty.
 * @param microsoftSignatureHash Base64 SHA-1 of the signing certificate, as Entra's Android platform shows it.
 */
class TunaMailOAuthConfigurationFactory(
    private val applicationId: String,
    private val googleClientId: String,
    private val microsoftClientId: String,
    private val microsoftSignatureHash: String,
) : OAuthConfigurationFactory {
    override fun createConfigurations(): Map<List<String>, OAuthConfiguration> {
        return listOfNotNull(
            createGmailConfiguration(),
            createMicrosoftConfiguration(),
        ).toMap()
    }

    private fun createGmailConfiguration(): Pair<List<String>, OAuthConfiguration>? {
        if (googleClientId.isEmpty()) return null

        return listOf(
            "imap.gmail.com",
            "imap.googlemail.com",
            "smtp.gmail.com",
            "smtp.googlemail.com",
        ) to OAuthConfiguration(
            clientId = googleClientId,
            scopes = listOf("https://mail.google.com/"),
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            redirectUri = "$applicationId:/oauth2redirect",
        )
    }

    private fun createMicrosoftConfiguration(): Pair<List<String>, OAuthConfiguration>? {
        if (microsoftClientId.isEmpty() || microsoftSignatureHash.isEmpty()) return null

        return listOf(
            "outlook.office365.com",
            "smtp.office365.com",
            "smtp-mail.outlook.com",
        ) to OAuthConfiguration(
            clientId = microsoftClientId,
            scopes = listOf(
                "profile",
                "openid",
                "email",
                "https://outlook.office.com/IMAP.AccessAsUser.All",
                "https://outlook.office.com/SMTP.Send",
                "offline_access",
            ),
            authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            redirectUri = "msauth://$applicationId/${URLEncoder.encode(microsoftSignatureHash, "UTF-8")}",
        )
    }
}
