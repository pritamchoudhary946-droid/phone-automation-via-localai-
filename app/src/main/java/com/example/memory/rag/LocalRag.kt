package com.example.memory.rag

import com.example.data.local.KnowledgeDao
import com.example.data.local.KnowledgeNoteEntity

/** A bounded snippet of the user's own notes/history retrieved for a query. */
data class RetrievedNote(val source: String, val content: String, val score: Int)

/**
 * On-device retrieval over the user's notes/past tasks (plan 4.15).
 *
 * Deliberately dependency-free: a token-overlap scorer instead of a vector DB, so
 * it runs on any 8 GB device with no extra native library. Swappable for an
 * embedding-backed store later without touching callers.
 */
interface Retriever {
    suspend fun retrieve(query: String, limit: Int = DEFAULT_LIMIT): List<RetrievedNote>

    companion object {
        const val DEFAULT_LIMIT = 3
    }
}

/** Pure tokenizer/overlap logic, unit-testable without a database. */
object RagScorer {
    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "is", "it",
        "my", "me", "i", "please", "then", "with", "this", "that", "do", "open"
    )

    fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    /** Number of shared tokens between the query and a note's stored tokens. */
    fun score(queryTokens: Set<String>, noteTokens: Set<String>): Int =
        queryTokens.count { it in noteTokens }
}

/** Room-backed retriever that reads notes and ranks them by token overlap. */
class TokenOverlapRetriever(
    private val dao: KnowledgeDao
) : Retriever {

    override suspend fun retrieve(query: String, limit: Int): List<RetrievedNote> {
        val queryTokens = RagScorer.tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()
        return dao.getAllNotes()
            .mapNotNull { note ->
                val noteTokens = note.tokens.split(" ").filter { it.isNotBlank() }.toSet()
                val score = RagScorer.score(queryTokens, noteTokens)
                if (score > 0) RetrievedNote(note.source, note.content, score) else null
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Stores a note, computing its searchable tokens once at write time. */
    suspend fun remember(source: String, content: String) {
        dao.insertNote(
            KnowledgeNoteEntity(
                source = source,
                content = content,
                tokens = RagScorer.tokenize(content).joinToString(" ")
            )
        )
    }
}
