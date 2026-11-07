package com.fsck.k9.ui.messagelist.item

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.Address
import com.fsck.k9.ui.messagelist.MessageListItem
import com.fsck.k9.ui.messagelist.fixtureMessageListItem
import net.thunderbird.feature.mail.message.list.ui.state.ComposedAddressStyle
import org.junit.Test

class MessageItemContentTest {
    @Test
    fun `toMessageItemUi should show the sender's display name, not the address`() {
        val item = fixtureMessageListItem(
            displayName = "Alice Example",
            displayAddress = Address.parse("Alice Example <alice@example.com>").first(),
        )

        val messageItemUi = item.toUi()

        assertThat(messageItemUi.senders.displayName).isEqualTo("Alice Example")
    }

    @Test
    fun `toMessageItemUi should style the sender line it shows`() {
        val item = fixtureMessageListItem(
            displayName = "To: Alice, Bob",
            displayAddress = Address.parse("alice@example.com").first(),
            isRead = false,
        )

        val senders = item.toUi().senders

        assertThat(senders.displayName).isEqualTo("To: Alice, Bob")
        assertThat(senders.displayNameStyles).containsExactly(
            ComposedAddressStyle.Bold(start = 0, end = "To: Alice".length),
            ComposedAddressStyle.Regular(start = "To: Alice".length),
        )
    }

    private fun MessageListItem.toUi() = toMessageItemUi(
        showContactPicture = false,
        isSelected = false,
        isActive = false,
        monogram = "AE",
        url = null,
    )
}
