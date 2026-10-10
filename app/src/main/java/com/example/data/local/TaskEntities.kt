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

/**
 * A single remembered fact about the user: a preference, an app they use for a
 * given purpose, or a phrasing they tend to use. [key] is unique so re-learning
 * the same fact updates it rather than duplicating.
 */
@Entity(tableName = "user_memory")
data class UserMemoryEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val kind: String, // PREFERENCE, APP_USAGE, PHRASING
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * The outcome of one completed/failed task, so the planner can recall what worked
 * (or did not) for a similar goal.
 */
@Entity(tableName = "task_outcomes")
data class TaskOutcomeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val goal: String,
    val status: String, // COMPLETED, FAILED
    val summary: String,
    val stepsUsed: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A saved, re-runnable task ("skill"/routine): the goal plus its successful plan,
 * so it can be replayed through the normal verified resume path by name (plan 4.13).
 */
@Entity(tableName = "saved_skills")
data class SavedSkillEntity(
    @PrimaryKey
    val name: String,
    val goal: String,
    val planJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val runCount: Int = 0,
    val lastRunAt: Long = 0
)

/** A simple, on-device retrieved-context note for local RAG (plan 4.15). */
@Entity(tableName = "knowledge_notes")
data class KnowledgeNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val source: String,
    val content: String,
    val tokens: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** A saved automation that runs a skill on a schedule or on a system condition (plan 4.14). */
@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val skillName: String,
    val triggerType: String, // INTERVAL, WIFI_CONNECTED
    val triggerValue: String, // interval minutes, or an SSID (blank = any wifi)
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
