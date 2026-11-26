package app.farol.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Documento JSON baixado (latest, tendências, dias do arquivo). */
@Entity(tableName = "cached_documents")
data class CachedDocument(@PrimaryKey val key: String, val json: String, val fetchedAt: Long)

/** Notícia salva: guarda uma cópia do JSON para funcionar mesmo depois que sair do latest.json. */
@Entity(tableName = "saved_articles")
data class SavedArticle(
    @PrimaryKey val id: String,
    val title: String,
    val sourceName: String,
    val savedAt: Long,
    val json: String,
)

/** Notícias abertas pelo usuário — usado só para mostrar o progresso da edição. */
@Entity(tableName = "read_articles")
data class ReadArticle(@PrimaryKey val id: String, val readAt: Long)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cached_documents WHERE `key` = :key")
    fun observe(key: String): Flow<CachedDocument?>

    @Query("SELECT * FROM cached_documents WHERE `key` = :key")
    suspend fun get(key: String): CachedDocument?

    @Upsert
    suspend fun put(doc: CachedDocument)

    @Query("DELETE FROM cached_documents")
    suspend fun clear()
}

@Dao
interface SavedDao {
    @Query("SELECT * FROM saved_articles ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedArticle>>

    @Query("SELECT * FROM saved_articles WHERE id = :id")
    suspend fun get(id: String): SavedArticle?

    @Upsert
    suspend fun put(item: SavedArticle)

    @Query("DELETE FROM saved_articles WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReadDao {
    @Query("SELECT id FROM read_articles")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markRead(item: ReadArticle)

    @Query("DELETE FROM read_articles WHERE id IN (:ids)")
    suspend fun markUnread(ids: List<String>)

    @Query("DELETE FROM read_articles")
    suspend fun clear()

    @Query("DELETE FROM read_articles WHERE readAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [CachedDocument::class, SavedArticle::class, ReadArticle::class], version = 1, exportSchema = false)
abstract class FarolDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun savedDao(): SavedDao
    abstract fun readDao(): ReadDao

    companion object {
        fun create(context: Context): FarolDatabase =
            Room.databaseBuilder(context, FarolDatabase::class.java, "farol.db").build()
    }
}
