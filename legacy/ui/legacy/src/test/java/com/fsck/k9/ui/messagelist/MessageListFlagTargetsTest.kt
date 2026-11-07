package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MessageListFlagTargetsTest {
    @Test
    fun `filterByMessageReferences should find items by the reference a MessageItemUi carries`() {
        val tutaMessage = fixtureMessageListItem(messageUid = "O1qC-6--F-0", databaseId = 7L)
        val localMessage = fixtureMessageListItem(messageUid = "K9LOCAL:3", databaseId = 8L)
        val imapMessage = fixtureMessageListItem(messageUid = "42", databaseId = 9L)
        val items = listOf(tutaMessage, localMessage, imapMessage)

        val found = items.filterByMessageReferences(
            listOf(tutaMessage.messageReference.toIdentityString(), localMessage.messageReference.toIdentityString()),
        )

        assertThat(found).containsExactly(tutaMessage, localMessage)
    }

    @Test
    fun `toFlagTargets should flag database ids, not server UIDs`() {
        val items = listOf(
            fixtureMessageListItem(messageUid = "42", databaseId = 7L),
            fixtureMessageListItem(messageUid = "O1qC-6--F-0", databaseId = 8L),
        )

        val targets = items.toFlagTargets(showingThreadedList = false)

        assertThat(targets).isEqualTo(
            mapOf(FIXTURE_ACCOUNT_ID to flagTargets(messageIds = listOf(7L, 8L))),
        )
    }

    @Test
    fun `toFlagTargets should flag a collapsed thread through its root only in a threaded list`() {
        val items = listOf(
            fixtureMessageListItem(databaseId = 7L, threadCount = 3, threadRoot = 5L),
            fixtureMessageListItem(databaseId = 8L, threadCount = 1, threadRoot = 8L),
        )

        assertThat(items.toFlagTargets(showingThreadedList = true)).isEqualTo(
            mapOf(FIXTURE_ACCOUNT_ID to flagTargets(messageIds = listOf(8L), threadRootIds = listOf(5L))),
        )
        assertThat(items.toFlagTargets(showingThreadedList = false)).isEqualTo(
            mapOf(FIXTURE_ACCOUNT_ID to flagTargets(messageIds = listOf(7L, 8L))),
        )
    }

    @Test
    fun `toFlagTargets should group items by account`() {
        val otherAccountId = AccountIdFactory.of("0f5d4a3b-8f53-4f4a-9a3c-7d6f2b0a1e22")
        val otherAccount = fixtureLegacyAccount(id = otherAccountId)
        val items = listOf(
            fixtureMessageListItem(databaseId = 7L),
            fixtureMessageListItem(account = otherAccount, databaseId = 8L),
        )

        val targets = items.toFlagTargets(showingThreadedList = false)

        assertThat(targets).isEqualTo(
            mapOf(
                FIXTURE_ACCOUNT_ID to flagTargets(messageIds = listOf(7L)),
                otherAccountId to flagTargets(messageIds = listOf(8L)),
            ),
        )
    }

    private fun flagTargets(messageIds: List<Long>, threadRootIds: List<Long> = emptyList()) =
        MessageListFlagTargets(messageIds = messageIds, threadRootIds = threadRootIds)
}
