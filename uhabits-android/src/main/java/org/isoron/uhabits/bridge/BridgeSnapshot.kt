package org.isoron.uhabits.bridge

data class BridgeSnapshot(
    val schemaVersion: Int = 5,
    val generatedAt: Long =
        System.currentTimeMillis(),
    val habits: List<BridgeHabit> =
        emptyList()
)

data class BridgeHabit(
    val id: Long,
    val name: String,
    val description: String,
    val question: String,
    val archived: Boolean,
    val habitType: String,
    val isNumerical: Boolean,
    val frequencyNumerator: Int,
    val frequencyDenominator: Int,
    val targetType: String,
    val targetValue: Double,
    val unit: String,
    val enteredToday: Boolean,
    val completedToday: Boolean,
    val manualCompletedToday: Boolean,
    val history: List<BridgeEntry>
)

data class BridgeEntry(
    val date: String,
    val value: Int,
    val status: String,
    val notes: String
)