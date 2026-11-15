package com.gfpv.lapwing.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "races")
data class RaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val pilot: String,
    val frequency: Int,
)

@Entity(
    tableName = "laps",
    foreignKeys = [ForeignKey(entity = RaceEntity::class, parentColumns = ["id"], childColumns = ["raceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("raceId")],
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: Long,
    val lapIndex: Int,
    val timeMs: Long,
)

data class RaceWithLaps(
    @Embedded val race: RaceEntity,
    @Relation(parentColumn = "id", entityColumn = "raceId") val laps: List<LapEntity>,
)

@Dao
interface RaceDao {
    @Insert
    suspend fun insertRace(race: RaceEntity): Long

    @Insert
    suspend fun insertLap(lap: LapEntity)

    @Query("UPDATE races SET endedAt = :endedAt WHERE id = :id")
    suspend fun finishRace(id: Long, endedAt: Long)

    @Query("SELECT COUNT(*) FROM laps WHERE raceId = :id")
    suspend fun lapCount(id: Long): Int

    @Query("DELETE FROM races WHERE id = :id")
    suspend fun deleteRace(id: Long)

    @Transaction
    @Query("SELECT * FROM races ORDER BY startedAt DESC")
    fun observeRaces(): Flow<List<RaceWithLaps>>
}

@Database(entities = [RaceEntity::class, LapEntity::class], version = 1, exportSchema = false)
abstract class RaceDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, RaceDatabase::class.java, "races.db").build()
    }
}
