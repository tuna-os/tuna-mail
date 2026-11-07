package org.tunaos.mail.featureflag

import net.thunderbird.core.featureflag.model.AppVariantOverrides
import net.thunderbird.core.featureflag.model.BaseAppVariantOverrides
import net.thunderbird.core.featureflag.model.FlagOverrides

/**
 * Holds the catalog's per-build-type overrides Tuna Mail follows: the "k9" ones (see TunaMailApp.appName).
 *
 * @param wrapper Map containing variant names as keys and their corresponding FlagOverrides.
 */
class TunaMailOverrides(wrapper: Map<String, FlagOverrides>) : BaseAppVariantOverrides(wrapper) {
    companion object {
        val Factory = AppVariantOverrides.Factory { wrapper -> TunaMailOverrides(wrapper) }
    }
}
