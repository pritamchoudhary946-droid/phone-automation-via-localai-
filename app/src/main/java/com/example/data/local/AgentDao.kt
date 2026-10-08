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
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM task_checkpoints WHERE taskId = :taskId LIMIT 1")
    suspend fun getCheckpoint(taskId: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: CheckpointEntity)

    @Query("DELETE FROM task_checkpoints WHERE taskId = :taskId")
    suspend fun deleteCheckpoint(taskId: String)
}
