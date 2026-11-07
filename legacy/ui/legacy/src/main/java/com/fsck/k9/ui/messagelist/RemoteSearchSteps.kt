package com.fsck.k9.ui.messagelist

import android.content.res.Resources
import com.fsck.k9.ui.R
import net.thunderbird.backend.api.search.SearchStep

/**
 * Where a server search that runs in steps (a backend's `SteppedSearch`) stands, for the message list: whether the
 * footer can offer the next step, and what the search has covered so far. Steps are written on the controller's
 * thread and read on the main thread.
 */
internal class RemoteSearchSteps {
    @Volatile
    private var lastStep: SearchStep? = null

    /** How many messages the steps so far looked at; null once a step did not say. */
    @Volatile
    private var searchedCount: Int? = 0

    /** Where the next step goes on from; null before the first step and once the folder is searched to the end. */
    val nextCursor: String? get() = lastStep?.nextCursor

    /** Forgets the steps of the previous search. */
    fun reset() {
        lastStep = null
        searchedCount = 0
    }

    fun onStepFinished(step: SearchStep) {
        searchedCount = searchedCount?.let { total -> step.searchedCount?.let { total + it } }
        lastStep = step
    }

    /**
     * The footer once a step's results are shown: the next step to tap, or that the folder has been searched to the
     * end, and the server's limit when it searches headers only. Null when the search did not run in steps.
     */
    fun footerText(resources: Resources): String? {
        val step = lastStep ?: return null
        val count = searchedCount
        val status = when {
            step.nextCursor != null && count != null ->
                resources.getQuantityString(R.plurals.remote_search_step_search_further_counted, count, count)

            step.nextCursor != null -> resources.getString(R.string.remote_search_step_search_further)

            count != null -> resources.getQuantityString(R.plurals.remote_search_step_searched_all_counted, count, count)

            else -> resources.getString(R.string.remote_search_step_searched_all)
        }
        return if (step.isHeadersOnly) {
            status + "\n" + resources.getString(R.string.remote_search_step_headers_only)
        } else {
            status
        }
    }
}
