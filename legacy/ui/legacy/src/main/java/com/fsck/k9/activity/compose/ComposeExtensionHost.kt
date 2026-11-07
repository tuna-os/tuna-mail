package com.fsck.k9.activity.compose

import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentActivity
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.mail.message.composer.extension.ComposeExtension
import net.thunderbird.feature.mail.message.composer.extension.ComposeOptions
import org.koin.java.KoinJavaComponent.getKoin

/**
 * The composer's side of the [ComposeExtension]s bound in Koin: it asks them for the [ComposeOptions] of the account
 * the composer sends from, again whenever that account changes, and hands each call on to those options. With no
 * extension bound, or none for the account, every call does nothing.
 */
class ComposeExtensionHost @JvmOverloads constructor(
    private val activity: FragmentActivity,
    private val extensions: () -> List<ComposeExtension> = { getKoin().getAll() },
) {
    private var accountUuid: String? = null
    private var options: ComposeOptions? = null

    /** The options whose menu items are in the menu, and those items, removed when the account changes. */
    private var menuOptions: ComposeOptions? = null
    private val addedMenuItemIds = mutableSetOf<Int>()

    private fun optionsFor(account: LegacyAccount): ComposeOptions? {
        val uuid = account.id.toString()
        if (uuid != accountUuid) {
            accountUuid = uuid
            options = extensions().firstNotNullOfOrNull { it.optionsFor(activity, uuid) }
        }
        return options
    }

    fun onPrepareOptionsMenu(menu: Menu, account: LegacyAccount) {
        val current = optionsFor(account)
        if (current !== menuOptions) {
            addedMenuItemIds.forEach(menu::removeItem)
            addedMenuItemIds.clear()
            menuOptions = current
        }
        if (current == null) return

        val before = menu.itemIds()
        current.onPrepareOptionsMenu(menu)
        addedMenuItemIds += menu.itemIds() - before
    }

    fun onOptionsItemSelected(item: MenuItem, account: LegacyAccount): Boolean =
        optionsFor(account)?.onOptionsItemSelected(item) == true

    /** Whether the message may be sent now; see [ComposeOptions.beforeSend]. */
    fun beforeSend(account: LegacyAccount, recipients: RecipientPresenter, send: Runnable): Boolean {
        val current = optionsFor(account) ?: return true
        val addresses = (recipients.toAddresses + recipients.ccAddresses + recipients.bccAddresses).map { it.address }
        return current.beforeSend(addresses, send)
    }

    /** Adds the options' headers to [message], built as a draft when [isDraft] or for sending. */
    fun onMessageBuilt(account: LegacyAccount, message: MimeMessage, isDraft: Boolean) {
        val current = optionsFor(account) ?: return
        val messageId = message.messageId ?: return
        for ((name, value) in current.headersFor(messageId, isDraft)) {
            message.setHeader(name, value)
        }
    }

    fun onDraftOpened(account: LegacyAccount, message: Message) {
        optionsFor(account)?.onDraftOpened { name -> message.getHeader(name).firstOrNull() }
    }

    private fun Menu.itemIds(): Set<Int> = (0 until size()).map { getItem(it).itemId }.toSet()
}
