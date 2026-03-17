package com.spectalk.app.conversations

data class ConversationItem(
    val id: String,
    val state: String,
    val lastTurnSummary: String?,
    val pendingResumeCount: Int,
    val createdAt: String,
    val updatedAt: String,
)
