package com.fsck.k9.ui.messagelist

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.account.storage.profile.AvatarDto
import net.thunderbird.feature.account.storage.profile.AvatarTypeDto
import net.thunderbird.feature.account.storage.profile.ProfileDto

internal val FIXTURE_ACCOUNT_ID: AccountId = AccountIdFactory.of("6b84207b-25de-4dab-97c3-953bbf03fec6")

@Suppress("LongParameterList")
internal fun fixtureMessageListItem(
    account: LegacyAccount = fixtureLegacyAccount(),
    displayName: CharSequence = "irrelevant",
    displayAddress: Address? = Address.parse("irrelevant@domain.example").first(),
    isRead: Boolean = false,
    threadCount: Int = 0,
    uniqueId: Long = 0L,
    folderId: Long = 1L,
    messageUid: String = "irrelevant",
    databaseId: Long = 0L,
    threadRoot: Long = 0L,
): MessageListItem {
    return MessageListItem(
        account = account,
        subject = "irrelevant",
        threadCount = threadCount,
        messageDate = 0L,
        internalDate = 0L,
        displayName = displayName,
        displayAddress = displayAddress,
        displayMessageDateTime = "12:34",
        previewText = "irrelevant",
        isMessageEncrypted = false,
        isRead = isRead,
        isStarred = false,
        isAnswered = false,
        isForwarded = false,
        hasAttachments = false,
        uniqueId = uniqueId,
        folderId = folderId,
        messageUid = messageUid,
        databaseId = databaseId,
        threadRoot = threadRoot,
        contactColor = -1,
    )
}

internal fun fixtureLegacyAccount(id: AccountId = FIXTURE_ACCOUNT_ID): LegacyAccount {
    val serverSettings = ServerSettings(
        type = "imap",
        host = "irrelevant",
        port = 993,
        connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
        authenticationType = AuthType.PLAIN,
        username = "irrelevant",
        password = "irrelevant",
        clientCertificateAlias = null,
    )
    return LegacyAccount(
        id = id,
        name = "irrelevant",
        email = "irrelevant@example.com",
        profile = ProfileDto(
            id = id,
            name = "irrelevant",
            color = 0xFF00FF,
            avatar = AvatarDto(
                accountId = id,
                avatarType = AvatarTypeDto.MONOGRAM,
                avatarMonogram = "ab",
                avatarImageUri = null,
                avatarIconName = null,
            ),
        ),
        incomingServerSettings = serverSettings,
        outgoingServerSettings = serverSettings,
        identities = listOf(Identity(description = null, name = "irrelevant", email = "irrelevant@example.com")),
    )
}
