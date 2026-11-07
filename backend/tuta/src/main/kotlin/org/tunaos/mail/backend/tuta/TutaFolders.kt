package org.tunaos.mail.backend.tuta

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import net.thunderbird.feature.mail.folder.api.FOLDER_DEFAULT_PATH_DELIMITER
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter
import org.tunaos.tuta.Folder
import org.tunaos.tuta.FolderRole

/**
 * Mirrors Tuta's folder tree into the store. A folder's server id is Tuta's compound folder id; its name is its path
 * from the top (`Parent/Child`), and its type comes from its role.
 */
internal class TutaFolders(
    private val backendStorage: BackendStorage,
    private val connection: TutaConnection,
) {
    @Volatile
    private var roles: Map<String, FolderRole> = emptyMap()

    fun refreshFolderList(): FolderPathDelimiter {
        val page = connection.call { sync(it, folder = null, limit = 1, offset = 0) }
        update(page.folders)
        return FOLDER_DEFAULT_PATH_DELIMITER
    }

    /** Takes the tree a sync page carried, so later role lookups need no request. */
    fun remember(folders: List<Folder>) {
        roles = folders.associate { it.id to it.role }
    }

    fun roleOf(folderServerId: String): FolderRole? {
        if (folderServerId !in roles) refreshFolderList()
        return roles[folderServerId]
    }

    /** The id of the folder with [role] (a system folder), or null when the account has none. */
    fun folderWithRole(role: FolderRole): String? {
        if (roles.isEmpty()) refreshFolderList()
        return roles.entries.firstOrNull { it.value == role }?.key
    }

    private fun update(folders: List<Folder>) {
        remember(folders)
        val foldersById = folders.associateBy { it.id }
        val localFolderServerIds = backendStorage.getFolderServerIds().toSet()
        val (existing, created) = folders.partition { it.id in localFolderServerIds }

        backendStorage.updateFolders {
            for (folder in existing) {
                changeFolder(folder.id, folder.path(foldersById), folder.role.toFolderType())
            }
            createFolders(
                created.map { folder ->
                    FolderInfo(
                        serverId = folder.id,
                        name = folder.path(foldersById),
                        type = folder.role.toFolderType(),
                        folderPathDelimiter = FOLDER_DEFAULT_PATH_DELIMITER,
                    )
                },
            )
            deleteFolders((localFolderServerIds - foldersById.keys).toList())
        }
    }

    private fun Folder.path(foldersById: Map<String, Folder>): String {
        val names = ArrayDeque<String>()
        var folder: Folder? = this
        while (folder != null && names.size < MAX_DEPTH) {
            names.addFirst(folder.label)
            folder = folder.parent?.let(foldersById::get)
        }
        return names.joinToString(FOLDER_DEFAULT_PATH_DELIMITER)
    }

    private fun FolderRole.toFolderType(): FolderType = when (this) {
        FolderRole.INBOX -> FolderType.INBOX
        FolderRole.SENT -> FolderType.SENT
        FolderRole.TRASH -> FolderType.TRASH
        FolderRole.ARCHIVE -> FolderType.ARCHIVE
        FolderRole.JUNK -> FolderType.SPAM
        FolderRole.DRAFTS -> FolderType.DRAFTS
        FolderRole.OTHER -> FolderType.REGULAR
    }

    private companion object {
        /** Deeper than any real tree; stops a parent loop. */
        const val MAX_DEPTH = 32
    }
}
