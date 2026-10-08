package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val userGoal: String,
    val status: String, // IDLE, RUNNING, PAUSED, WAITING_CONFIRMATION, COMPLETED, FAILED, CANCELLED
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val currentStep: Int = 0,
    val maxSteps: Int = 25,
    val resultSummary: String? = null,
    val requiresRiskConfirmation: Boolean = false,
    val pendingRiskActionJson: String? = null
)

@Entity(tableName = "task_steps")
data class TaskStepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: String,
    val stepIndex: Int,
    val reasoning: String,
    val actionType: String,
    val actionPayloadJson: String,
    val observationSummary: String,
    val verificationResult: String,
    val status: String, // SUCCESS, FAILED, RETRYING, SKIPPED
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_checkpoints")
data class CheckpointEntity(
    @PrimaryKey
    val taskId: String,
    val stepIndex: Int,
    val snapshotJson: String,
    val timestamp: Long = System.currentTimeMillis()
)
