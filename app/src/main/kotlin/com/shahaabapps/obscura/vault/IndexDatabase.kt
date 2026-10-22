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
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

enum class FileKind { PHOTO, VIDEO, AUDIO, DOCUMENT, OTHER }

/** One sealed file. Its name, dates and per-file key live only inside the encrypted database. */
@Entity(tableName = "files", indices = [Index("kind"), Index("takenAt"), Index("sha256")])
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
) {
    override fun equals(other: Any?) = other is VaultFile && other.id == id && other.sha256 == sha256 && other.name == name &&
        other.favorite == favorite && other.albumId == albumId

    override fun hashCode() = id.hashCode()
}

data class KindTotal(val kind: FileKind, val count: Int, val bytes: Long)

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
}

@Database(entities = [VaultFile::class], version = 1, exportSchema = true)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun files(): FileDao

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
