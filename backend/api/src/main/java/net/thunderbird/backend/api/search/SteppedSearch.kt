package net.thunderbird.backend.api.search

import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag

/**
 * A backend whose server search is a bounded scan the server resumes from a cursor, so it runs in steps: each step
 * searches part of the folder and says where the next one goes on from. A remote search of such a backend runs one
 * step, shows what it found, and searches further only when the user asks, instead of holding the message list until
 * the whole folder has been searched.
 *
 * A `Backend` that implements it is searched through [searchStep]; `Backend.search` then runs the first step only.
 */
interface SteppedSearch {
    /**
     * Runs one step of a search of [folderServerId]: the same criteria as `Backend.search`, going on from [cursor]
     * (null starts at the newest message). A step ends once it has found [wanted] matches (0: as many as one step
     * finds), or at a limit of the backend's own.
     */
    @Throws(MessagingException::class)
    fun searchStep(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
        cursor: String?,
        wanted: Int,
    ): SearchStep
}

/** What one [SteppedSearch.searchStep] found. */
data class SearchStep(
    /** The server ids of the messages found, newest first. */
    val messageServerIds: List<String>,

    /** Where the next step goes on from; null once the folder has been searched to the end. */
    val nextCursor: String?,

    /** How many messages this step looked at, when the server says; null when it does not. */
    val searchedCount: Int?,

    /** True when the server searched only the headers (subject, sender, recipients), even if full text was asked. */
    val isHeadersOnly: Boolean,
)
