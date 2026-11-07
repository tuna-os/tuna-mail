package org.tunaos.mail.backend.proton

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome.Error
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome.Success
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log
import org.tunaos.proton.ProtonFolder
import org.tunaos.proton.ProtonFolderKind
import org.tunaos.proton.ProtonFolderRole

private const val TAG = "ProtonFolderList"

/** Proton's well-known system label ids. */
internal object ProtonFolderIds {
    const val INBOX = "0"
    const val TRASH = "3"
    const val SPAM = "4"
    const val ALL_MAIL = "5"
    const val ARCHIVE = "6"
    const val SENT = "7"
    const val DRAFTS = "8"
    const val STARRED = "10"
}

internal const val FOLDER_PATH_DELIMITER = "/"

/**
 * Mirrors Proton's folder rows into the local folder list: system folders, user folders and user
 * labels. A label is a tag, so a message can sit in a label folder and in its real folder at
 * once; Proton keeps the message id either way.
 */
internal class ProtonFolderList(
    private val backendStorage: BackendStorage,
    private val api: ProtonApi,
) {
    fun refresh() {
        val folders = protonCall { api.sync(limit = 1, folder = ProtonFolderIds.INBOX).folders }
        mirror(folders)
    }

    /**
     * Creates a user folder at [path] (`Parent/Child`; the parent must be a user folder) and
     * mirrors the new list. A folder or label already at [path] counts as created unless
     * [mustCreate].
     */
    suspend fun create(path: String, mustCreate: Boolean): Outcome<Success, Error> = try {
        createOrFind(path, mustCreate)
    } catch (e: MessagingException) {
        Log.w(TAG, e, "Could not create a Proton folder")
        Outcome.failure(Error.FailedToCreateRemoteFolder(e.message ?: "Proton refused the folder"))
    }

    private suspend fun createOrFind(path: String, mustCreate: Boolean): Outcome<Success, Error> {
        val folders = protonSuspendCall { api.sync(limit = 1, folder = ProtonFolderIds.INBOX).folders }
        val byId = folders.associateBy { it.id }
        val name = path.substringAfterLast(FOLDER_PATH_DELIMITER)
        val parentPath = path.substringBeforeLast(FOLDER_PATH_DELIMITER, missingDelimiterValue = "")
        val parent = parentPath.takeIf { it.isNotEmpty() }?.let { wanted ->
            folders.firstOrNull { it.kind == ProtonFolderKind.FOLDER && pathOf(it, byId) == wanted }?.id
        }
        return when {
            path in byId || folders.any { pathOf(it, byId) == path } ->
                if (mustCreate) Outcome.failure(Error.AlreadyExists) else Outcome.success(Success.AlreadyExists)

            name.isBlank() || (parentPath.isNotEmpty() && parent == null) ->
                Outcome.failure(Error.FailedToCreateRemoteFolder("Proton has no folder $parentPath for $name"))

            else -> {
                val created = protonSuspendCall { api.createFolder(name, parent) }
                mirror(folders + created)
                Outcome.success(Success.Created)
            }
        }
    }

    /** The folders that are user labels, as of the last refresh. */
    fun labelIds(): Set<String> {
        val stored = backendStorage.getExtraString(EXTRA_LABEL_IDS) ?: return emptySet()
        return stored.split(LIST_SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }

    private fun mirror(remoteFolders: List<ProtonFolder>) {
        val byId = remoteFolders.associateBy { it.id }
        val remote = remoteFolders.map { folder ->
            FolderInfo(
                serverId = folder.id,
                name = pathOf(folder, byId),
                type = folder.folderType,
                folderPathDelimiter = FOLDER_PATH_DELIMITER,
            )
        }
        val localIds = backendStorage.getFolderServerIds().toSet()
        val (existing, new) = remote.partition { it.serverId in localIds }

        backendStorage.updateFolders {
            existing.forEach { changeFolder(it.serverId, it.name, it.type) }
            createFolders(new)
            deleteFolders((localIds - remote.map { it.serverId }.toSet()).toList())
        }
        val labelIds = remoteFolders.filter { it.kind == ProtonFolderKind.LABEL }.map { it.id }
        backendStorage.setExtraString(EXTRA_LABEL_IDS, labelIds.joinToString(LIST_SEPARATOR))
    }

    /** "Parent/Child", following parent ids; a cycle or a missing parent ends the walk. */
    private fun pathOf(folder: ProtonFolder, byId: Map<String, ProtonFolder>): String {
        val names = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        var current: ProtonFolder? = folder
        while (current != null && seen.add(current.id)) {
            names.addFirst(current.label)
            current = current.parent.takeIf { it.isNotEmpty() }?.let(byId::get)
        }
        return names.joinToString(FOLDER_PATH_DELIMITER)
    }

    private val ProtonFolder.folderType: FolderType
        get() = when (role) {
            ProtonFolderRole.INBOX -> FolderType.INBOX

            ProtonFolderRole.DRAFTS -> FolderType.DRAFTS

            ProtonFolderRole.SENT -> FolderType.SENT

            ProtonFolderRole.ARCHIVE -> FolderType.ARCHIVE

            ProtonFolderRole.JUNK -> FolderType.SPAM

            ProtonFolderRole.TRASH -> FolderType.TRASH

            ProtonFolderRole.STARRED,
            ProtonFolderRole.OTHER,
            -> FolderType.REGULAR
        }

    private companion object {
        const val EXTRA_LABEL_IDS = "protonLabelIds"
        const val LIST_SEPARATOR = "\n"
    }
}
