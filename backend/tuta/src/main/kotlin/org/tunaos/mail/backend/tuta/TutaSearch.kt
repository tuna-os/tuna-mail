package org.tunaos.mail.backend.tuta

import net.thunderbird.core.common.mail.Flag
import org.tunaos.tuta.SearchQuery

/**
 * Server search of one folder through the SDK's `search`. Tuta has no search index on the server (its own apps index
 * on the device), so the SDK scans the folder newest first, decrypting as it goes, a bounded slice per call; the
 * keyword is looked for in the subject, the sender, the recipients and the body, full text or not. Unread state
 * narrows the scan on the server, and the other flags the search asks about are checked on the listed mails. Calls
 * go on until [MAX_RESULTS] matches or [MAX_CALLS] calls.
 */
internal class TutaSearch(
    private val connection: TutaConnection,
    private val labels: TutaLabels,
) {
    fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): List<String> {
        val required = requiredFlags.orEmpty()
        val forbidden = forbiddenFlags.orEmpty()
        val searchQuery = SearchQuery(
            keyword = query?.trim()?.take(MAX_KEYWORD_LENGTH)?.takeIf { it.isNotEmpty() },
            isUnread = when (Flag.SEEN) {
                in required -> false
                in forbidden -> true
                else -> null
            },
        )
        if (searchQuery.keyword == null && searchQuery.isUnread == null) return emptyList()

        val flaggedLabel = if (Flag.FLAGGED in required + forbidden) labels.flaggedLabelForReading() else null
        val ids = mutableListOf<String>()
        var cursor: String? = null
        var calls = 0
        do {
            val page = connection.call { search(it, searchQuery, folderServerId, PAGE_SIZE, cursor) }
            page.messages.filter { hit ->
                val flags = hit.mail.header.flags(hit.mail.labels, flaggedLabel)
                flags.containsAll(required) && flags.none { it in forbidden }
            }.mapTo(ids) { it.mail.header.id }
            cursor = page.nextCursor
            calls++
        } while (cursor != null && ids.size < MAX_RESULTS && calls < MAX_CALLS)
        return ids.distinct().take(MAX_RESULTS)
    }

    companion object {
        /** The most matches one call hands back (the SDK allows 100). */
        const val PAGE_SIZE = 100
        const val MAX_RESULTS = 200

        /** Each call scans at most 500 mails, so this is the newest 10,000 mails of the folder. */
        const val MAX_CALLS = 20

        /** The longest search text the SDK takes. */
        private const val MAX_KEYWORD_LENGTH = 256
    }
}
