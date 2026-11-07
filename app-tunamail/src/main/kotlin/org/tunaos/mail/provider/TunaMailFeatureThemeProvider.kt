package org.tunaos.mail.provider

import androidx.compose.runtime.Composable
import net.thunderbird.components.ui.bolt.theme.tunamail.TunaMailBoltTheme
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider

internal class TunaMailFeatureThemeProvider : FeatureThemeProvider {
    @Composable
    override fun WithTheme(content: @Composable () -> Unit) {
        TunaMailBoltTheme {
            content()
        }
    }

    @Composable
    override fun WithTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
        TunaMailBoltTheme(darkTheme = darkTheme) {
            content()
        }
    }
}
