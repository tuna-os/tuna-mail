package org.tunaos.mail.feature

import net.thunderbird.feature.funding.api.FundingSettings

/**
 * Tuna Mail asks for no funding: the reminder state reads as already shown, and nothing is stored.
 */
@Suppress("TooManyFunctions", "EmptyFunctionBlock")
internal class TunaMailFundingSettings : FundingSettings {

    override fun isReady(): Boolean = true

    override fun getReminderReferenceTimestamp(): Long = 0L

    override fun setReminderReferenceTimestamp(timestamp: Long) {}

    override fun getReminderShownTimestamp() = 0L

    override fun setReminderShownTimestamp(timestamp: Long) {}

    override fun getLastReminderShownActivityAmount(): Long = 0L

    override suspend fun setLastReminderShownActivityAmount(activityInMillis: Long) {}

    override fun getReminderShownCount(): Int = 100

    override suspend fun setReminderShownCount(count: Int) {}

    override suspend fun incrementReminderShownCount() {}

    override fun getActivityCounterInMillis(): Long = 0L

    override fun setActivityCounterInMillis(activeTime: Long) {}
}
