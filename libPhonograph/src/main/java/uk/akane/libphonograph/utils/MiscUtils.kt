package uk.akane.libphonograph.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import java.io.File
import uk.akane.libphonograph.ALLOWED_EXT
import uk.akane.libphonograph.TAG
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.artistId

object MiscUtils {
    data class FileNodeImpl(
        override val folderName: String
    ) : FileNode {
        override val folderList = hashMapOf<String, FileNode>()
        override val songList = mutableListOf<MediaItem>()
        override var albumId: Long? = null
        fun addSong(item: MediaItem, id: Long?) {
            if (albumId != null && id != albumId) {
                albumId = null
            } else if (albumId == null && songList.isEmpty()) {
                albumId = id
            }
            songList.add(item)
        }
    }

    fun handleMediaFolder(path: String, rootNode: FileNode): FileNode {
        val newPath = if (path.endsWith('/')) path.substring(1, path.length - 1)
        else path.substring(1)
        val splitPath = newPath.split('/')
        var node: FileNode = rootNode
        for (fld in splitPath.subList(0, splitPath.size - 1)) {
            var newNode = node.folderList[fld]
            if (newNode == null) {
                newNode = FileNodeImpl(fld)
                (node.folderList as HashMap)[newNode.folderName] = newNode
            }
            node = newNode
        }
        return node
    }

    fun handleShallowMediaItem(
        mediaItem: MediaItem,
        albumId: Long?,
        path: String,
        shallowFolder: FileNode
    ) {
        val newPath = if (path.endsWith('/')) path.substring(0, path.length - 1) else path
        val splitPath = newPath.split('/')
        if (splitPath.size < 2) throw IllegalArgumentException("splitPath.size < 2: $newPath")
        val lastFolderName = splitPath[splitPath.size - 2]
        var folder = (shallowFolder.folderList as HashMap)[lastFolderName]
        if (folder == null) {
            folder = FileNodeImpl(lastFolderName)
            (shallowFolder.folderList as HashMap)[folder.folderName] = folder
        }
        (folder as FileNodeImpl).addSong(mediaItem, albumId)
    }

    fun findBestCover(songFolder: File): File? {
        var bestScore = 0
        var bestFile: File? = null
        try {
            val files = songFolder.listFiles() ?: return null
            for (file in files) {
                if (file.extension !in ALLOWED_EXT)
                    continue
                var score = 1
                when (file.extension) {
                    "jpg" -> score += 3
                    "png" -> score += 2
                    "jpeg" -> score += 1
                }
                if (file.nameWithoutExtension.contentEquals("albumart", true)) score += 24
                else if (file.nameWithoutExtension.contentEquals("cover", true)) score += 20
                else if (file.nameWithoutExtension.startsWith("albumart", true)) score += 16
                else if (file.nameWithoutExtension.startsWith("cover", true)) score += 12
                else if (file.nameWithoutExtension.contains("albumart", true)) score += 8
                else if (file.nameWithoutExtension.contains("cover", true)) score += 4
                if (bestScore < score) {
                    bestScore = score
                    bestFile = file
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
        }
        // allow .jpg or .png files with any name, but only permit more exotic
        // formats if name contains either cover or albumart
        if (bestScore >= 3) {
            return bestFile
        }
        return null
    }

    fun findBestAlbumArtist(songs: List<MediaItem>, artistToIdMap: Map<String?, Long?>): Pair<String, Long?>? {
        val foundAlbumArtists = songs.map { it.mediaMetadata.albumArtist?.toString() }
            .groupBy { it }.mapValues { it.value.size }
        if (foundAlbumArtists.size > 2
            || (foundAlbumArtists.size == 2 && !foundAlbumArtists.containsKey(null))) {
            Log.w(TAG, "Odd, album artists: $foundAlbumArtists for one album exceed 1, " +
                    "MediaStore usually doesn't do that")
            return null
        }
        val theAlbumArtist = foundAlbumArtists.keys.find { it != null }
        if (theAlbumArtist != null) {
            // We got at least one album artist tag.
            val countWithNull = foundAlbumArtists[null] ?: 0
            val countWithAlbumArtist = foundAlbumArtists[theAlbumArtist]!!
            if (countWithAlbumArtist < countWithNull) return null
            return Pair(
                theAlbumArtist,
                // If the album artist made some song on the album, using the ID from the song will be
                // more accurate than just using any ID which matches the name. Well, it's a best guess.
                songs.firstOrNull { it.mediaMetadata.artist == theAlbumArtist }?.mediaMetadata?.artistId
                    ?: artistToIdMap[theAlbumArtist]
            )
        } else {
            // Meh, let's guess based on artist tag.
            val foundArtists = songs.map { it.mediaMetadata.artist?.toString() }
                .groupBy { it }.mapValues { it.value.size }.entries.sortedByDescending { it.value }
            val bestMatch = foundArtists.firstOrNull() ?: return null
            if (bestMatch.key == null) return null
            // If less than 60% of songs have said artist, we can't reasonably assume the best match
            // is the actual album artist.
            if ((bestMatch.value.toFloat() / songs.size) < 0.6f) return null
            // Let's go with this.
            return Pair(bestMatch.key!!, artistToIdMap[bestMatch.key])
        }
    }

    data class AlbumImpl(
        override val id: Long?,
        override val title: String?,
        override var albumArtist: String?,
        override var albumArtistId: Long?,
        override var cover: Uri?,
        override val songList: MutableList<MediaItem>
    ) : Album
}