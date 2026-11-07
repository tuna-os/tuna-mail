package com.fsck.k9.ui.messagelist

import net.thunderbird.feature.account.AccountId

/**
 * The database ids to flag in one account: [messageIds] for `MessagingController.setFlag` and [threadRootIds]
 * for `MessagingController.setFlagForThreads`.
 */
internal data class MessageListFlagTargets(
    val messageIds: List<Long>,
    val threadRootIds: List<Long>,
)

/**
 * Groups these items by account into the ids `MessagingController` flags. A thread shown collapsed in a threaded
 * list is flagged as a whole through its thread root.
 */
internal fun List<MessageListItem>.toFlagTargets(
    showingThreadedList: Boolean,
): Map<AccountId, MessageListFlagTargets> =
    groupBy { it.account.id }.mapValues { (_, accountItems) ->
        val (threads, messages) = accountItems.partition { showingThreadedList && it.threadCount > 1 }
        MessageListFlagTargets(
            messageIds = messages.map { it.databaseId },
            threadRootIds = threads.map { it.threadRoot },
        )
    }

/**
 * The items behind [messageReferences], the identity strings a `MessageItemUi` carries.
 *
 * `MessageItemUi.id` is the message's server UID, which is neither the database id `MessagingController`
 * takes nor necessarily a number, so the ids have to come from the [MessageListItem]s the list was built from.
 */
internal fun List<MessageListItem>.filterByMessageReferences(
    messageReferences: Collection<String>,
): List<MessageListItem> {
    val wantedReferences = messageReferences.toSet()
    return filter { it.messageReference.toIdentityString() in wantedReferences }
}
