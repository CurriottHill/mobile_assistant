package com.example.mobile_assistant

data class NodeFingerprint(
    val nodeRef: String,
    val packageName: String?,
    val className: String?,
    val primaryLabel: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val depth: Int,
    val siblingOrdinal: Int,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val ancestorLabels: List<String>,
    val pathSignature: String
)
