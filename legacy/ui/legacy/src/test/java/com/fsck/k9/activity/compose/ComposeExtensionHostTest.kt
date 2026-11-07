package com.fsck.k9.activity.compose

import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentActivity
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.FakeLegacyAccount
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.composer.extension.ComposeExtension
import net.thunderbird.feature.mail.message.composer.extension.ComposeOptions
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.fakes.RoboMenu

class ComposeExtensionHostTest : K9RobolectricTest() {
    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
    private val withOptions = FakeLegacyAccount.create(id = AccountIdFactory.create(), email = "a@example.com")
    private val without = FakeLegacyAccount.create(id = AccountIdFactory.create(), email = "b@example.com")
    private val options = RecordingOptions()
    private val asked = mutableListOf<String>()
    private val extension = ComposeExtension { _, accountUuid ->
        asked += accountUuid
        options.takeIf { accountUuid == withOptions.id.toString() }
    }

    @Test
    fun `without extensions nothing changes`() {
        val host = ComposeExtensionHost(activity) { emptyList() }
        val message = message()
        val menu = RoboMenu()

        host.onMessageBuilt(withOptions, message, isDraft = false)
        host.onPrepareOptionsMenu(menu, withOptions)

        assertThat(message.getHeader(HEADER).toList()).isEmpty()
        assertThat(menu.size()).isEqualTo(0)
        assertThat(host.onOptionsItemSelected(menu.add("x"), withOptions)).isFalse()
    }

    @Test
    fun `built messages get the options' headers, drafts and sends alike`() {
        val host = ComposeExtensionHost(activity) { listOf(extension) }
        val draft = message()
        val sent = message()

        host.onMessageBuilt(withOptions, draft, isDraft = true)
        host.onMessageBuilt(withOptions, sent, isDraft = false)

        assertThat(draft.getHeader(HEADER).toList()).containsExactly("draft")
        assertThat(sent.getHeader(HEADER).toList()).containsExactly("send")
        assertThat(options.builtMessageIds).containsExactly(MESSAGE_ID, MESSAGE_ID)
        assertThat(asked).containsExactly(withOptions.id.toString())
    }

    @Test
    fun `an account without options gets nothing`() {
        val host = ComposeExtensionHost(activity) { listOf(extension) }
        val message = message()

        host.onMessageBuilt(without, message, isDraft = false)
        host.onDraftOpened(without, message)

        assertThat(message.getHeader(HEADER).toList()).isEmpty()
        assertThat(options.openedDraftValue).isNull()
    }

    @Test
    fun `an opened draft's headers reach the options`() {
        val host = ComposeExtensionHost(activity) { listOf(extension) }
        val draft = message().apply { setHeader(HEADER, "saved") }

        host.onDraftOpened(withOptions, draft)

        assertThat(options.openedDraftValue).isEqualTo("saved")
    }

    @Test
    fun `menu items follow the account`() {
        val host = ComposeExtensionHost(activity) { listOf(extension) }
        val menu = RoboMenu()

        host.onPrepareOptionsMenu(menu, withOptions)
        host.onPrepareOptionsMenu(menu, withOptions)
        assertThat(menu.size()).isEqualTo(1)
        val item = menu.findItem(ITEM_ID)
        assertThat(item).isNotNull()
        assertThat(host.onOptionsItemSelected(item, withOptions)).isTrue()

        host.onPrepareOptionsMenu(menu, without)
        assertThat(menu.findItem(ITEM_ID)).isNull()

        host.onPrepareOptionsMenu(menu, withOptions)
        assertThat(menu.findItem(ITEM_ID)).isNotNull()
    }

    private fun message() = MimeMessage().apply { messageId = MESSAGE_ID }

    private class RecordingOptions : ComposeOptions {
        val builtMessageIds = mutableListOf<String>()
        var openedDraftValue: String? = null

        override fun onPrepareOptionsMenu(menu: Menu) {
            if (menu.findItem(ITEM_ID) == null) menu.add(Menu.NONE, ITEM_ID, Menu.NONE, "Option")
        }

        override fun onOptionsItemSelected(item: MenuItem): Boolean = item.itemId == ITEM_ID

        override fun beforeSend(recipients: List<String>, send: Runnable): Boolean = true

        override fun headersFor(messageId: String, isDraft: Boolean): Map<String, String> {
            builtMessageIds += messageId
            return mapOf(HEADER to if (isDraft) "draft" else "send")
        }

        override fun onDraftOpened(header: (name: String) -> String?) {
            openedDraftValue = header(HEADER)
        }
    }

    private companion object {
        const val HEADER = "X-Test-Option"
        const val MESSAGE_ID = "<composed@example.com>"
        const val ITEM_ID = 4242
    }
}
