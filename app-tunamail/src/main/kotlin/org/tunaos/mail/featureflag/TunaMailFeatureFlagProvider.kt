package org.tunaos.mail.featureflag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.thunderbird.core.featureflag.FeatureFlagKey
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.FeatureFlagResult
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import net.thunderbird.core.featureflag.provider.CatalogFeatureFlagProvider
import net.thunderbird.core.featureflag.provider.ProviderMetadata

/**
 * Feature flags Tuna Mail pins regardless of the bundled or remote catalog.
 *
 * It is bound as the in-memory provider, which the flag evaluator consults before the remote and bundled catalogs.
 * In debug builds the runtime debug overrides still win, so a pinned flag can be toggled for testing.
 *
 * @param debugOverrides The runtime debug override provider in debug builds, `null` otherwise.
 */
class TunaMailFeatureFlagProvider(
    private val debugOverrides: FeatureFlagProvider? = null,
) : CatalogFeatureFlagProvider {
    override val state: StateFlow<CatalogFeatureFlagProvider.State> =
        MutableStateFlow(CatalogFeatureFlagProvider.State.Resolved).asStateFlow()

    override val metadata: ProviderMetadata = Metadata

    override fun provide(key: FeatureFlagKey): FeatureFlagResult {
        val debugResult = debugOverrides?.provide(key) ?: FeatureFlagResult.Unavailable
        if (debugResult != FeatureFlagResult.Unavailable) return debugResult

        return when (PINNED_FLAGS[key.key]) {
            true -> FeatureFlagResult.Enabled
            false -> FeatureFlagResult.Disabled
            null -> FeatureFlagResult.Unavailable
        }
    }

    override fun toString(): String = "feature-flag provider '${metadata.name}': pinned = $PINNED_FLAGS"

    private object Metadata : ProviderMetadata {
        override val name: String = "tunamail_pinned"
    }

    companion object {
        /**
         * Flag key to pinned value. Why each flag is pinned, and what upstream still has to finish before the
         * flags left off can be turned on, is in docs/tunamail/compose.md.
         */
        val PINNED_FLAGS: Map<String, Boolean> = mapOf(
            // Thundermail is a Thunderbird service; Tuna Mail does not offer it.
            GeneratedFeatureFlagKey.THUNDERMAIL_ONBOARDING_ENABLED.key to false,
            // Compose rows in the message list. Rendering only, so it works for every account type.
            GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_LIST_ITEMS.key to true,
            // The reader's namespaced CSS, which upstream already defaults on. Pinned so a remote catalog
            // can't turn it off.
            GeneratedFeatureFlagKey.USE_NEW_MESSAGE_READER_CSS_STYLES.key to true,
            // The MVI message list: swipe and star do nothing, flagging a selection is a no-op, and starring
            // parses a server UID as a database id, which throws for Tuta and Proton messages.
            GeneratedFeatureFlagKey.ENABLE_MESSAGE_LIST_NEW_STATE.key to false,
            // Nothing reads this flag yet; pinned off so a remote catalog can't turn on an unreviewed reader.
            GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_READER.key to false,
        )
    }
}
