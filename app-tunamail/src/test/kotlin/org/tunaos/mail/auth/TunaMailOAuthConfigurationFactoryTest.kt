package org.tunaos.mail.auth

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlin.test.Test
import net.thunderbird.core.common.oauth.OAuthConfiguration

class TunaMailOAuthConfigurationFactoryTest {

    @Test
    fun `createConfigurations should return no providers when no client ids are configured`() {
        // Arrange
        val testSubject = createTestSubject()

        // Act
        val result = testSubject.createConfigurations()

        // Assert
        assertThat(result).isEmpty()
    }

    @Test
    fun `createConfigurations should return Google only when only its client id is configured`() {
        // Arrange
        val testSubject = createTestSubject(googleClientId = GOOGLE_CLIENT_ID)

        // Act
        val result = testSubject.createConfigurations()

        // Assert
        assertThat(result.keys.flatten()).containsExactlyInAnyOrder(
            "imap.gmail.com",
            "imap.googlemail.com",
            "smtp.gmail.com",
            "smtp.googlemail.com",
        )
        val gmail = result.forHost("imap.gmail.com")
        assertThat(gmail.clientId).isEqualTo(GOOGLE_CLIENT_ID)
        assertThat(gmail.redirectUri).isEqualTo("org.tunaos.mail.debug:/oauth2redirect")
    }

    @Test
    fun `createConfigurations should leave out Microsoft when the signature hash is missing`() {
        // Arrange
        val testSubject = createTestSubject(microsoftClientId = MICROSOFT_CLIENT_ID)

        // Act
        val result = testSubject.createConfigurations()

        // Assert
        assertThat(result).isEmpty()
    }

    @Test
    fun `createConfigurations should build the msauth redirect URI from the url-encoded signature hash`() {
        // Arrange
        val testSubject = createTestSubject(
            microsoftClientId = MICROSOFT_CLIENT_ID,
            microsoftSignatureHash = "ab+c/d1=",
        )

        // Act
        val result = testSubject.createConfigurations()

        // Assert
        assertThat(result.keys.flatten()).containsExactlyInAnyOrder(
            "outlook.office365.com",
            "smtp.office365.com",
            "smtp-mail.outlook.com",
        )
        val microsoft = result.forHost("outlook.office365.com")
        assertThat(microsoft.clientId).isEqualTo(MICROSOFT_CLIENT_ID)
        assertThat(microsoft.redirectUri).isEqualTo("msauth://org.tunaos.mail.debug/ab%2Bc%2Fd1%3D")
    }

    private fun createTestSubject(
        googleClientId: String = "",
        microsoftClientId: String = "",
        microsoftSignatureHash: String = "",
    ) = TunaMailOAuthConfigurationFactory(
        applicationId = "org.tunaos.mail.debug",
        googleClientId = googleClientId,
        microsoftClientId = microsoftClientId,
        microsoftSignatureHash = microsoftSignatureHash,
    )

    private fun Map<List<String>, OAuthConfiguration>.forHost(host: String): OAuthConfiguration {
        val configuration = entries.firstOrNull { (hosts, _) -> host in hosts }?.value
        assertThat(configuration).isNotNull()
        return configuration!!
    }

    private companion object {
        const val GOOGLE_CLIENT_ID = "1234-example.apps.googleusercontent.com"
        const val MICROSOFT_CLIENT_ID = "00000000-0000-0000-0000-000000000000"
    }
}
