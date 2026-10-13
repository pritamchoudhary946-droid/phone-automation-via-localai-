package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class,
        TaskStepEntity::class,
        CheckpointEntity::class,
        UserMemoryEntity::class,
        TaskOutcomeEntity::class,
        SavedSkillEntity::class,
        KnowledgeNoteEntity::class,
        AutomationEntity::class,
        EpisodeEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskStepDao(): TaskStepDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun memoryDao(): MemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun automationDao(): AutomationDao
    abstract fun episodeDao(): EpisodeDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "omni_agent_db"
                ).fallbackToDestructiveMigration(true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
