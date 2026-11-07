package org.tunaos.mail.backend.tuta

import net.thunderbird.backend.api.search.SearchStep
import net.thunderbird.core.common.mail.Flag
import org.tunaos.tuta.SearchQuery

/**
 * Server search of one folder through the SDK's `search`. Tuta has no search index on the server (its own apps index
 * on the device), so the SDK scans the folder newest first, decrypting as it goes: one call looks at no more than 500
 * mails, fetches no more than 50 bodies and runs no more than 30 seconds, then hands back a cursor to go on from. The
 * keyword is looked for in the subject, the sender, the recipients and the body, full text or not.
 *
 * A [step] makes up to [CALLS_PER_STEP] calls and stops early once it has the matches it wants, so a step looks at
 * no more than 1,000 mails and returns the cursor for the next. Unread state narrows the scan on the server; the
 * other flags the search asks about are checked on the mails found.
 */
internal class TutaSearch(
    private val connection: TutaConnection,
    private val labels: TutaLabels,
) {
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
        val searchQuery = SearchQuery(
            keyword = query?.trim()?.take(MAX_KEYWORD_LENGTH)?.takeIf { it.isNotEmpty() },
            isUnread = when (Flag.SEEN) {
                in required -> false
                in forbidden -> true
                else -> null
            },
        )
        if (searchQuery.keyword == null && searchQuery.isUnread == null) {
            return SearchStep(emptyList(), nextCursor = null, searchedCount = 0, isHeadersOnly = false)
        }

        val target = if (wanted > 0) wanted else DEFAULT_WANTED
        val flaggedLabel = if (Flag.FLAGGED in required + forbidden) labels.flaggedLabelForReading() else null
        val ids = mutableListOf<String>()
        var nextCursor = cursor
        var searched = 0L
        var calls = 0
        do {
            val limit = (target - ids.size).coerceAtMost(MAX_PAGE_SIZE)
            val page = connection.call { search(it, searchQuery, folderServerId, limit, nextCursor) }
            page.messages.filter { hit ->
                val flags = hit.mail.header.flags(hit.mail.labels, flaggedLabel)
                flags.containsAll(required) && flags.none { it in forbidden }
            }.mapTo(ids) { it.mail.header.id }
            searched += page.scanned
            nextCursor = page.nextCursor
            calls++
        } while (nextCursor != null && ids.size < target && calls < CALLS_PER_STEP)

        return SearchStep(
            messageServerIds = ids.distinct(),
            nextCursor = nextCursor,
            searchedCount = searched.toInt(),
            isHeadersOnly = false,
        )
    }

    companion object {
        /** Each call looks at no more than 500 mails, so a step looks at no more than 1,000. */
        const val CALLS_PER_STEP = 2

        /** The matches a step looks for when the caller names no number. */
        const val DEFAULT_WANTED = 100

        /** The most matches the SDK hands back from one call. */
        private const val MAX_PAGE_SIZE = 100

        /** The longest search text the SDK takes. */
        private const val MAX_KEYWORD_LENGTH = 256
    }
}
