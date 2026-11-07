package org.tunaos.mail.backend.proton

import net.thunderbird.backend.api.search.SearchStep
import net.thunderbird.core.common.mail.Flag
import org.tunaos.proton.ProtonSearchQuery

/**
 * Server search of one folder through the SDK's `search`. Proton can only search what it can read: the subject,
 * the sender and the recipients, never bodies or attachments, which are end-to-end encrypted. So the query is a
 * keyword over those, full text or not, and read and starred state narrow it on the server; the other flags the
 * search asks about are checked on the messages found.
 *
 * A [step] follows Proton's pages, newest first, for up to [PAGES_PER_STEP] pages and stops early once it has the
 * matches it wants; it returns the cursor for the next step.
 */
internal class ProtonSearch(private val api: ProtonApi) {
    fun step(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        cursor: String?,
        wanted: Int,
    ): SearchStep {
        val required = requiredFlags.orEmpty()
        val forbidden = forbiddenFlags.orEmpty()
        val searchQuery = ProtonSearchQuery(
            keyword = query?.trim()?.takeIf { it.isNotEmpty() },
            isUnread = state(Flag.SEEN, required, forbidden)?.not(),
            isStarred = state(Flag.FLAGGED, required, forbidden),
            folder = folderServerId,
        )
        if (searchQuery.keyword == null && searchQuery.isUnread == null && searchQuery.isStarred == null) {
            return SearchStep(emptyList(), nextCursor = null, searchedCount = null, isHeadersOnly = true)
        }

        val target = if (wanted > 0) wanted else DEFAULT_WANTED
        val ids = mutableListOf<String>()
        var nextCursor = cursor
        var pages = 0
        protonCall {
            do {
                val limit = (target - ids.size).coerceAtMost(MAX_PAGE_SIZE)
                val page = api.search(searchQuery, limit, nextCursor)
                page.messages.filter { header ->
                    val flags = header.flags().filterValues { it }.keys
                    flags.containsAll(required) && flags.none { it in forbidden }
                }.mapTo(ids) { it.id }
                nextCursor = page.nextCursor
                pages++
            } while (nextCursor != null && ids.size < target && pages < PAGES_PER_STEP)
        }
        return SearchStep(ids.distinct(), nextCursor, searchedCount = null, isHeadersOnly = true)
    }

    /** True when [flag] is required, false when forbidden, null when the search does not care. */
    private fun state(flag: Flag, required: Set<Flag>, forbidden: Set<Flag>): Boolean? = when (flag) {
        in required -> true
        in forbidden -> false
        else -> null
    }

    companion object {
        const val PAGES_PER_STEP = 3

        /** The matches a step looks for when the caller names no number. */
        const val DEFAULT_WANTED = 100

        /** The most hits the SDK hands back from one call. */
        private const val MAX_PAGE_SIZE = 150
    }
}
