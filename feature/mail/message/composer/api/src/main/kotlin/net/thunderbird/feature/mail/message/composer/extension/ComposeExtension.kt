package net.thunderbird.feature.mail.message.composer.extension

import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentActivity

/**
 * Adds an account type's own send options to the message composer, for accounts whose server sends mail in ways a
 * MIME message cannot ask for by itself (a server that encrypts per recipient, say).
 *
 * Every extension bound in Koin is asked, for the account the composer sends from, whether it has options for it; the
 * first that does supplies them until the composer switches to another account.
 */
fun interface ComposeExtension {
    /** This extension's options for the account [accountUuid] in the composer [activity], or null when it has none. */
    fun optionsFor(activity: FragmentActivity, accountUuid: String): ComposeOptions?
}

/** One composer's options for one account. */
interface ComposeOptions {
    /**
     * Adds the options' menu items, or brings them up to date, each time the composer's menu is prepared. Items must
     * be found by id before being added, so a second call adds none.
     */
    fun onPrepareOptionsMenu(menu: Menu)

    /** Handles a click on one of the options' menu items; false for any other item. */
    fun onOptionsItemSelected(item: MenuItem): Boolean

    /**
     * Called before a message to [recipients] (their addresses) is built for sending. Returns true when it may go;
     * false holds the send, and the options call [send] once it may go, or never when the user called it off.
     */
    fun beforeSend(recipients: List<String>, send: Runnable): Boolean

    /**
     * The headers to add to the message built with the Message-ID [messageId], as a draft when [isDraft] or for
     * sending. Drafts are stored, so their headers must hold nothing secret.
     */
    fun headersFor(messageId: String, isDraft: Boolean): Map<String, String>

    /** A draft is opened for editing; [header] reads the first value of one of its headers. */
    fun onDraftOpened(header: (name: String) -> String?)
}
