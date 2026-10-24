package com.shahaabapps.obscura.vault

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

enum class FileKind { PHOTO, VIDEO, AUDIO, DOCUMENT, OTHER }

/** One sealed file. Its name, dates and per-file key live only inside the encrypted database. */
@Entity(tableName = "files", indices = [Index("kind"), Index("takenAt"), Index("sha256"), Index("albumId")])
data class VaultFile(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String,
    val kind: FileKind,
    val sizeBytes: Long,
    /** When the photo/video was taken, or the file's own date; used for month grouping. */
    val takenAt: Long,
    val sealedAt: Long,
    /** Per-file key wrapped by the master key. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val wrappedKey: ByteArray,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val favorite: Boolean = false,
    val albumId: String? = null,
    /** 64-bit visual fingerprint (dHash) of photos, for finding look-alikes. Computed on demand. */
    val phash: Long? = null,
) {
    override fun equals(other: Any?) = other is VaultFile && other.id == id && other.sha256 == sha256 && other.name == name &&
        other.favorite == favorite && other.albumId == albumId

    override fun hashCode() = id.hashCode()
}

data class KindTotal(val kind: FileKind, val count: Int, val bytes: Long)

/** A named group of files. Names live only inside the encrypted database. */
@Entity(tableName = "albums")
data class Album(
    @PrimaryKey val id: String,
    val name: String,
    /** ARGB colour of the album card. */
    val color: Long,
    val createdAt: Long,
)

data class AlbumSummary(
    val id: String,
    val name: String,
    val color: Long,
    val count: Int,
    val bytes: Long,
    /** Newest photo or video in the album, used as its cover. */
    val coverId: String?,
)

@Dao
abstract class AlbumDao {
    @Insert
    abstract suspend fun insert(album: Album)

    @Query("UPDATE albums SET name = :name, color = :color WHERE id = :id")
    abstract suspend fun update(id: String, name: String, color: Long)

    /** Deleting an album only ungroups its files; nothing is shredded. */
    @Query("UPDATE files SET albumId = NULL WHERE albumId = :id")
    protected abstract suspend fun releaseFiles(id: String)

    @Query("DELETE FROM albums WHERE id = :id")
    protected abstract suspend fun deleteRow(id: String)

    @androidx.room.Transaction
    open suspend fun delete(id: String) {
        releaseFiles(id)
        deleteRow(id)
    }

    @Query(
        """
        SELECT a.id, a.name, a.color,
               COUNT(f.id) AS count,
               COALESCE(SUM(f.sizeBytes), 0) AS bytes,
               (SELECT c.id FROM files c WHERE c.albumId = a.id AND c.kind IN ('PHOTO', 'VIDEO') ORDER BY c.takenAt DESC LIMIT 1) AS coverId
        FROM albums a LEFT JOIN files f ON f.albumId = a.id
        GROUP BY a.id ORDER BY a.createdAt DESC
        """,
    )
    abstract fun observeSummaries(): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM albums WHERE id = :id")
    abstract fun observe(id: String): Flow<Album?>
}

@Dao
interface FileDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(file: VaultFile)

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun get(id: String): VaultFile?

    @Query("SELECT * FROM files ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<VaultFile>>

    @Query("SELECT kind, COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes FROM files GROUP BY kind")
    fun observeTotals(): Flow<List<KindTotal>>

    @Query("SELECT COUNT(*) FROM files WHERE sha256 = :sha256")
    suspend fun countWithHash(sha256: String): Int

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE files SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE files SET albumId = :albumId WHERE id = :id")
    suspend fun setAlbum(id: String, albumId: String?)

    /** Bulk versions for multi-select; callers pass at most [BULK_CHUNK] ids at a time. */
    @Query("UPDATE files SET favorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavoriteAll(ids: List<String>, favorite: Boolean)

    @Query("UPDATE files SET albumId = :albumId WHERE id IN (:ids)")
    suspend fun setAlbumAll(ids: List<String>, albumId: String?)

    @Query("UPDATE files SET phash = :phash WHERE id = :id")
    suspend fun setPhash(id: String, phash: Long)

    @Query("SELECT * FROM files")
    suspend fun all(): List<VaultFile>

    @Query("SELECT * FROM files WHERE albumId = :albumId ORDER BY takenAt DESC")
    fun observeInAlbum(albumId: String): Flow<List<VaultFile>>

    /**
     * Case-insensitive name search. [pattern] must already be a LIKE pattern with `%`, `_` and
     * `\` escaped (see [likePattern]).
     */
    @Query("SELECT * FROM files WHERE name LIKE :pattern ESCAPE '\\' ORDER BY takenAt DESC LIMIT 300")
    fun search(pattern: String): Flow<List<VaultFile>>
}

/** "50% off_sale" → "%50\% off\_sale%": matches the text literally anywhere in a name. */
fun likePattern(query: String): String =
    "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

@Database(
    entities = [VaultFile::class, Album::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2), androidx.room.AutoMigration(from = 2, to = 3)],
)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun files(): FileDao
    abstract fun albums(): AlbumDao

    companion object {
        init {
            System.loadLibrary("sqlcipher")
        }

        /**
         * Opens the SQLCipher database with [key] (256 bits derived from the master key).
         * SQLCipher reuses the same array whenever it opens another connection, so the caller
         * must keep it intact until the database is closed, and wipe it afterwards.
         */
        fun open(context: Context, file: File, key: ByteArray): IndexDatabase {
            file.parentFile?.mkdirs()
            return Room.databaseBuilder(context, IndexDatabase::class.java, file.absolutePath)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
        }
    }
}

/** SQLite caps the number of bound parameters; bulk updates go in chunks of this size. */
const val BULK_CHUNK = 500
