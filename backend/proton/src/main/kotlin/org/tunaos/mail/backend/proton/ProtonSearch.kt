package org.tunaos.mail.backend.proton

import net.thunderbird.core.common.mail.Flag
import org.tunaos.proton.ProtonSearchQuery

/**
 * Server search of one folder through the SDK's `search`. Proton can only search what it can read: the subject,
 * the sender and the recipients, never bodies or attachments, which are end-to-end encrypted. So the query is a
 * keyword over those, full text or not, and read and starred state narrow it on the server. Pages are followed
 * until [MAX_RESULTS] matches or [MAX_PAGES] pages; newest first.
 */
internal class ProtonSearch(private val api: ProtonApi) {
    fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): List<String> {
        val required = requiredFlags.orEmpty()
        val forbidden = forbiddenFlags.orEmpty()
        val searchQuery = ProtonSearchQuery(
            keyword = query?.trim()?.takeIf { it.isNotEmpty() },
            isUnread = state(Flag.SEEN, required, forbidden)?.not(),
            isStarred = state(Flag.FLAGGED, required, forbidden),
            folder = folderServerId,
        )
        if (searchQuery.keyword == null && searchQuery.isUnread == null && searchQuery.isStarred == null) {
            return emptyList()
        }

        val ids = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        protonCall {
            do {
                val page = api.search(searchQuery, PAGE_SIZE, cursor)
                page.messages.filter { header ->
                    val flags = header.flags().filterValues { it }.keys
                    flags.containsAll(required) && flags.none { it in forbidden }
                }.mapTo(ids) { it.id }
                cursor = page.nextCursor
                pages++
            } while (cursor != null && ids.size < MAX_RESULTS && pages < MAX_PAGES)
        }
        return ids.distinct().take(MAX_RESULTS)
    }

    /** True when [flag] is required, false when forbidden, null when the search does not care. */
    private fun state(flag: Flag, required: Set<Flag>, forbidden: Set<Flag>): Boolean? = when (flag) {
        in required -> true
        in forbidden -> false
        else -> null
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_RESULTS = 200
        const val MAX_PAGES = 10
    }
}
