package org.tunaos.mail.provider

import net.thunderbird.feature.navigation.changelog.api.ChangelogConfigProvider
import org.tunaos.mail.R

class TunaMailChangelogConfigProvider : ChangelogConfigProvider {
    override val changelogIndexResId: Int = R.raw.changelog_index
}
