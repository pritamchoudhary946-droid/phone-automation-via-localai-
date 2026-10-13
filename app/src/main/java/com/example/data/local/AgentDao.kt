package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("UPDATE agent_tasks SET status = :status, updatedAt = :timestamp WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM agent_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("DELETE FROM agent_tasks")
    suspend fun clearAllTasks()
}

@Dao
interface TaskStepDao {
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    fun getStepsForTask(taskId: String): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun getStepsForTaskList(taskId: String): List<TaskStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: TaskStepEntity)

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteStepsForTask(taskId: String)

    @Query("DELETE FROM task_steps")
    suspend fun clearAllSteps()
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM task_checkpoints WHERE taskId = :taskId LIMIT 1")
    suspend fun getCheckpoint(taskId: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: CheckpointEntity)

    @Query("DELETE FROM task_checkpoints WHERE taskId = :taskId")
    suspend fun deleteCheckpoint(taskId: String)

    @Query("DELETE FROM task_checkpoints")
    suspend fun clearAllCheckpoints()
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: UserMemoryEntity)

    @Query("SELECT * FROM user_memory ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memory WHERE key = :key LIMIT 1")
    suspend fun getMemory(key: String): UserMemoryEntity?

    @Query("DELETE FROM user_memory")
    suspend fun clearMemories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutcome(outcome: TaskOutcomeEntity)

    @Query("SELECT * FROM task_outcomes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOutcomes(limit: Int): List<TaskOutcomeEntity>

    @Query("SELECT * FROM task_outcomes WHERE goal = :goal ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getOutcomesForGoal(goal: String, limit: Int): List<TaskOutcomeEntity>

    @Query("DELETE FROM task_outcomes")
    suspend fun clearOutcomes()
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: SavedSkillEntity)

    @Query("SELECT * FROM saved_skills ORDER BY createdAt DESC")
    fun getAllSkills(): Flow<List<SavedSkillEntity>>

    @Query("SELECT * FROM saved_skills WHERE name = :name LIMIT 1")
    suspend fun getSkill(name: String): SavedSkillEntity?

    @Query("UPDATE saved_skills SET runCount = runCount + 1, lastRunAt = :timestamp WHERE name = :name")
    suspend fun markSkillRun(name: String, timestamp: Long)

    @Query("DELETE FROM saved_skills WHERE name = :name")
    suspend fun deleteSkill(name: String)
}

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: KnowledgeNoteEntity)

    @Query("SELECT * FROM knowledge_notes ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentNotes(limit: Int): List<KnowledgeNoteEntity>

    @Query("SELECT * FROM knowledge_notes")
    suspend fun getAllNotes(): List<KnowledgeNoteEntity>

    @Query("DELETE FROM knowledge_notes")
    suspend fun clearNotes()
}

@Dao
interface AutomationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutomation(automation: AutomationEntity): Long

    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    fun getAllAutomations(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations WHERE enabled = 1")
    suspend fun getEnabledAutomations(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getAutomationById(id: Long): AutomationEntity?

    @Query("SELECT * FROM automations WHERE skillName = :skillName")
    suspend fun getAutomationsForSkill(skillName: String): List<AutomationEntity>

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    suspend fun setAutomationEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE automations SET lastRunAtMs = :timestamp WHERE id = :id")
    suspend fun markAutomationRun(id: Long, timestamp: Long)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun deleteAutomation(id: Long)
}

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: EpisodeEntity)

    /** Most relevant episodes for a goal: same goal first, then most recent (plan 5.9). */
    @Query("SELECT * FROM episodes WHERE goal = :goal ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getEpisodesForGoal(goal: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEpisodes(limit: Int): List<EpisodeEntity>

    @Query("DELETE FROM episodes")
    suspend fun clearEpisodes()
}
