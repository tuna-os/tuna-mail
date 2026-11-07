package org.tunaos.mail.backend.proton

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the device is doing, sampled before each wait of the Proton event poll. */
interface ProtonPollConditions {
    /** Whether the user can be looking at the screen (Android's "interactive"). */
    val isScreenOn: Boolean

    /** Whether the active network is metered, so every poll may cost the user money. */
    val isNetworkMetered: Boolean

    /** A device always in use on an unmetered network: poll at the fastest pace. */
    object AlwaysActive : ProtonPollConditions {
        override val isScreenOn = true
        override val isNetworkMetered = false
    }
}

/**
 * How often the Proton event poll runs. The fastest pace is [MINIMUM_INTERVAL], the interval
 * Proton's own clients poll at; nothing here polls more often.
 *
 * |                       | Inbox    | other folders |
 * |-----------------------|----------|---------------|
 * | screen on, unmetered  | 30 s     | 5 min         |
 * | screen on, metered    | 2 min    | 5 min         |
 * | screen off            | 5 min    | 15 min        |
 *
 * An account whose Inbox is not watched polls at the pace of its other folders.
 */
class ProtonPollSchedule(private val conditions: ProtonPollConditions) {
    /** The wait between two polls. */
    fun interval(isInboxWatched: Boolean): Duration {
        val inbox = when {
            !conditions.isScreenOn -> SCREEN_OFF_INBOX
            conditions.isNetworkMetered -> METERED_INBOX
            else -> MINIMUM_INTERVAL
        }
        return if (isInboxWatched) inbox else otherFolders()
    }

    /** The least time between two pushes of a folder other than the Inbox. */
    fun otherFolders(): Duration = if (conditions.isScreenOn) OTHER_FOLDERS else SCREEN_OFF_OTHER_FOLDERS

    companion object {
        val MINIMUM_INTERVAL = 30.seconds
        private val METERED_INBOX = 2.minutes
        private val SCREEN_OFF_INBOX = 5.minutes
        private val OTHER_FOLDERS = 5.minutes
        private val SCREEN_OFF_OTHER_FOLDERS = 15.minutes
    }
}
