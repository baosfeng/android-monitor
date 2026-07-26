package com.bsfeng.monitor.domain.model

data class Record(
    val id: String,
    val type: RecordType,
    val action: String,
    val timestamp: Long,
    val duration: Long? = null,
    val label: String,
    val detail: Map<String, String>? = null,
    val owner: String = ""
)
