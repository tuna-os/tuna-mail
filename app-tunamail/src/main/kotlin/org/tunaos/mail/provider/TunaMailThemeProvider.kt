package org.tunaos.mail.provider

import net.thunderbird.core.ui.theme.api.ThemeProvider
import org.tunaos.mail.R

internal class TunaMailThemeProvider : ThemeProvider {
    override val appThemeResourceId = R.style.Theme_TunaMail_DayNight
    override val appLightThemeResourceId = R.style.Theme_TunaMail_Light
    override val appDarkThemeResourceId = R.style.Theme_TunaMail_Dark
    override val dialogThemeResourceId = R.style.Theme_TunaMail_DayNight_Dialog
    override val translucentDialogThemeResourceId = R.style.Theme_TunaMail_DayNight_Dialog_Translucent
}
