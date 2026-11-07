package org.tunaos.mail.backend.proton

import com.fsck.k9.backend.api.SyncListener

class RecordingSyncListener : SyncListener {
    val newMessages = mutableListOf<Pair<String, Boolean>>()
    val removedMessages = mutableListOf<String>()
    val flagChanges = mutableListOf<String>()
    var isFinished = false
    var failure: Exception? = null

    override fun syncStarted(folderServerId: String) = Unit
    override fun syncAuthenticationSuccess() = Unit
    override fun syncHeadersStarted(folderServerId: String) = Unit
    override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
    override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) = Unit
    override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit

    override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) {
        newMessages += messageServerId to isOldMessage
    }

    override fun syncRemovedMessage(folderServerId: String, messageServerId: String) {
        removedMessages += messageServerId
    }

    override fun syncFlagChanged(folderServerId: String, messageServerId: String) {
        flagChanges += messageServerId
    }

    override fun syncFinished(folderServerId: String) {
        isFinished = true
    }

    override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
        failure = exception
    }

    override fun folderStatusChanged(folderServerId: String) = Unit
}
