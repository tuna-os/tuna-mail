package org.tunaos.mail.featureflag

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.core.featureflag.FeatureFlagKey
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.FeatureFlagResult
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import net.thunderbird.core.featureflag.provider.CatalogFeatureFlagProvider

class TunaMailFeatureFlagProviderTest {

    @Test
    fun `provide should disable Thundermail onboarding`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.THUNDERMAIL_ONBOARDING_ENABLED)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Disabled)
    }

    @Test
    fun `provide should enable Compose message list items`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_LIST_ITEMS)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Enabled)
    }

    @Test
    fun `provide should enable the new message reader CSS styles`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.USE_NEW_MESSAGE_READER_CSS_STYLES)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Enabled)
    }

    @Test
    fun `provide should disable the new message list state`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.ENABLE_MESSAGE_LIST_NEW_STATE)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Disabled)
    }

    @Test
    fun `provide should disable the Compose message reader`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_READER)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Disabled)
    }

    @Test
    fun `provide should let a debug override turn off Compose message list items`() {
        // Arrange
        val debugOverrides = FakeFeatureFlagProvider(
            GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_LIST_ITEMS.key to FeatureFlagResult.Disabled,
        )
        val testSubject = TunaMailFeatureFlagProvider(debugOverrides = debugOverrides)

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_LIST_ITEMS)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Disabled)
    }

    @Test
    fun `provide should let a debug override turn on the new message list state`() {
        // Arrange
        val debugOverrides = FakeFeatureFlagProvider(
            GeneratedFeatureFlagKey.ENABLE_MESSAGE_LIST_NEW_STATE.key to FeatureFlagResult.Enabled,
        )
        val testSubject = TunaMailFeatureFlagProvider(debugOverrides = debugOverrides)

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.ENABLE_MESSAGE_LIST_NEW_STATE)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Enabled)
    }

    @Test
    fun `provide should leave flags it does not pin to the catalogs`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.ARCHIVE_MARKS_AS_READ)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Unavailable)
    }

    @Test
    fun `provide should prefer a debug override over a pinned flag`() {
        // Arrange
        val debugOverrides = FakeFeatureFlagProvider(
            GeneratedFeatureFlagKey.THUNDERMAIL_ONBOARDING_ENABLED.key to FeatureFlagResult.Enabled,
        )
        val testSubject = TunaMailFeatureFlagProvider(debugOverrides = debugOverrides)

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.THUNDERMAIL_ONBOARDING_ENABLED)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Enabled)
    }

    @Test
    fun `provide should fall back to the pinned flag when there is no debug override`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider(debugOverrides = FakeFeatureFlagProvider())

        // Act
        val result = testSubject.provide(GeneratedFeatureFlagKey.THUNDERMAIL_ONBOARDING_ENABLED)

        // Assert
        assertThat(result).isEqualTo(FeatureFlagResult.Disabled)
    }

    @Test
    fun `state should be resolved from the start`() {
        // Arrange
        val testSubject = TunaMailFeatureFlagProvider()

        // Act
        val state = testSubject.state.value

        // Assert
        assertThat(state).isEqualTo(CatalogFeatureFlagProvider.State.Resolved)
    }

    private class FakeFeatureFlagProvider(
        vararg results: Pair<String, FeatureFlagResult>,
    ) : FeatureFlagProvider {
        private val results = results.toMap()

        override fun provide(key: FeatureFlagKey): FeatureFlagResult =
            results[key.key] ?: FeatureFlagResult.Unavailable
    }
}
