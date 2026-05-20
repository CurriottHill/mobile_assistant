package com.example.mobile_assistant

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * Reads the full accessibility tree from the active app window and assigns
 * stable semantic node references that can be reused across turns.
 */
object ScreenReader {

    private data class ScreenLine(val text: String)
    private data class IndexedNode(
        val fingerprint: NodeFingerprint,
        val line: ScreenLine
    )

    private data class BestNodeMatch(
        val node: AccessibilityNodeInfo,
        val fingerprint: NodeFingerprint,
        val score: Int
    )

    private data class EditableCandidate(
        val node: AccessibilityNodeInfo,
        val fingerprint: NodeFingerprint,
        val label: String,
        val focused: Boolean
    )

    private data class TapTypeContext(
        val fingerprint: NodeFingerprint,
        val label: String,
        val tapped: Boolean,
        val directlyEditable: Boolean
    )

    private sealed interface NodeLookupResult {
        data class Found(
            val node: AccessibilityNodeInfo,
            val fingerprint: NodeFingerprint
        ) : NodeLookupResult

        data class Failed(val error: String) : NodeLookupResult
    }

    internal sealed interface NodeRefResolution {
        data class Exact(
            val nodeRef: String,
            val fingerprint: NodeFingerprint
        ) : NodeRefResolution

        data class Remapped(
            val requestedNodeRef: String,
            val nodeRef: String,
            val fingerprint: NodeFingerprint,
            val score: Int
        ) : NodeRefResolution

        data class Failed(
            val error: String,
            val fingerprint: NodeFingerprint? = null
        ) : NodeRefResolution
    }

    private enum class DuplicateLabelState {
        NON_INTERACTIVE,
        INTERACTIVE
    }

    private const val MAX_ANCESTOR_LABELS = 4
    private const val MAX_KNOWN_FINGERPRINTS = 4096
    private const val MIN_MATCH_SCORE = 48
    private const val MATCH_AMBIGUITY_MARGIN = 10
    private const val EXACT_MATCH_SCORE = 1_000
    private const val TAG = "ScreenReader"
    // Debug switch: bypass pruning and dump every visible node in the app package.
    private const val USE_FULL_PACKAGE_TREE_DUMP = false
    private const val INCLUDE_ANCESTORS_IN_TREE_DUMP = false
    private const val TAP_TYPE_TARGET_ATTEMPTS = 5
    private const val TAP_TYPE_TARGET_DELAY_MS = 120L
    private val NODE_REF_REGEX = Regex("""\[(nf_[a-f0-9]+)]""")
    // Captures the value inside text="...", desc="...", hint="...", label="..." on a tree line.
    private val ATTRIBUTE_REGEX = Regex("""(?:text|desc|hint|label)="([^"]*)"""")

    private val knownFingerprints = object : LinkedHashMap<String, NodeFingerprint>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NodeFingerprint>?): Boolean {
            return size > MAX_KNOWN_FINGERPRINTS
        }
    }

    fun readScreen(service: AssistantAccessibilityService): String? {
        val root = AssistantUiTargeting.findAppRoot(service) ?: return null
        return try {
            val pkg = root.packageName?.toString() ?: "unknown"
            val indexedNodes = collectIndexedNodes(root, pkg)
            if (indexedNodes.isEmpty()) {
                null
            } else {
                indexedNodes.forEach { registerFingerprint(it.fingerprint) }
                formatTree(pkg, indexedNodes.map { it.line })
            }
        } finally {
            root.recycle()
        }
    }

    internal fun fingerprintsForDump(dump: String?): Map<String, NodeFingerprint> {
        if (dump.isNullOrBlank()) return emptyMap()

        val refs = NODE_REF_REGEX.findAll(dump)
            .map { normalizeNodeRef(it.groupValues[1]) }
            .distinct()
            .toList()

        if (refs.isEmpty()) return emptyMap()

        val fingerprints = linkedMapOf<String, NodeFingerprint>()
        synchronized(knownFingerprints) {
            refs.forEach { ref ->
                knownFingerprints[ref]?.let { fingerprint ->
                    fingerprints[ref] = fingerprint
                }
            }
        }
        return fingerprints
    }

    internal fun resolveNodeRef(latestDump: String?, requestedNodeRef: String): NodeRefResolution {
        val normalizedRef = normalizeNodeRef(requestedNodeRef)
        if (!looksLikeNodeRef(normalizedRef)) {
            return NodeRefResolution.Failed("Invalid node ref '$requestedNodeRef'.")
        }

        val latestFingerprints = fingerprintsForDump(latestDump)
        if (latestFingerprints.isEmpty()) {
            return NodeRefResolution.Failed(
                "Could not resolve node ref '$normalizedRef' because the latest screen tree is unavailable."
            )
        }

        latestFingerprints[normalizedRef]?.let { fingerprint ->
            return NodeRefResolution.Exact(
                nodeRef = normalizedRef,
                fingerprint = fingerprint
            )
        }

        val targetFingerprint = lookupFingerprint(normalizedRef)
            ?: return NodeRefResolution.Failed(
                error = "Unknown node ref '$normalizedRef'. Read the latest screen and use an exact node_ref from it."
            )

        var bestMatch: NodeFingerprint? = null
        var bestScore = Int.MIN_VALUE
        var secondBestScore = Int.MIN_VALUE

        latestFingerprints.values
            .asSequence()
            .filter { candidate ->
                candidate.packageName.isNullOrBlank() ||
                    targetFingerprint.packageName.isNullOrBlank() ||
                    candidate.packageName == targetFingerprint.packageName
            }
            .forEach { candidate ->
                val score = scoreFingerprint(targetFingerprint, candidate)
                if (score > bestScore) {
                    secondBestScore = bestScore
                    bestScore = score
                    bestMatch = candidate
                } else if (score > secondBestScore) {
                    secondBestScore = score
                }
            }

        val resolvedFingerprint = bestMatch
            ?: return NodeRefResolution.Failed(
                error = "Node ref '$normalizedRef' is stale and no current match was found on the latest screen.",
                fingerprint = targetFingerprint
            )

        if (bestScore < MIN_MATCH_SCORE) {
            return NodeRefResolution.Failed(
                error = "Node ref '$normalizedRef' is stale and could not be matched confidently on the latest screen.",
                fingerprint = targetFingerprint
            )
        }

        if (bestScore < EXACT_MATCH_SCORE && secondBestScore >= bestScore - MATCH_AMBIGUITY_MARGIN) {
            return NodeRefResolution.Failed(
                error = "Node ref '$normalizedRef' is stale and matches multiple elements on the latest screen. Read the screen again and use the newest node_ref.",
                fingerprint = targetFingerprint
            )
        }

        registerFingerprint(resolvedFingerprint)
        return NodeRefResolution.Remapped(
            requestedNodeRef = normalizedRef,
            nodeRef = resolvedFingerprint.nodeRef,
            fingerprint = resolvedFingerprint,
            score = bestScore
        )
    }

    internal fun tapNode(service: AssistantAccessibilityService, nodeRef: String): NodeTapResult {
        val normalizedRef = normalizeNodeRef(nodeRef)
        if (!looksLikeNodeRef(normalizedRef)) {
            return NodeTapResult(tapped = false, error = "Invalid node ref '$nodeRef'.")
        }

        val targetFingerprint = lookupFingerprint(normalizedRef)
            ?: return NodeTapResult(
                tapped = false,
                error = "Unknown node ref '$normalizedRef'. Read the latest screen and use an exact node_ref from it."
            )

        val root = AssistantUiTargeting.findAppRoot(service)
            ?: return NodeTapResult(tapped = false, error = "Could not read current screen.")

        return try {
            when (val lookup = findNodeByFingerprint(root, targetFingerprint)) {
                is NodeLookupResult.Failed -> NodeTapResult(
                    tapped = false,
                    label = targetFingerprint.primaryLabel,
                    error = lookup.error
                )

                is NodeLookupResult.Found -> {
                    registerFingerprint(lookup.fingerprint)
                    val tappableNode = findClickableAncestor(lookup.node)
                    val label = resolveActionLabel(lookup.node, tappableNode)
                    val tapped = tappableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

                    tappableNode?.recycle()
                    lookup.node.recycle()

                    if (tapped) {
                        NodeTapResult(
                            tapped = true,
                            label = label,
                            matchedNodeRef = lookup.fingerprint.nodeRef
                        )
                    } else {
                        NodeTapResult(
                            tapped = false,
                            label = label,
                            matchedNodeRef = lookup.fingerprint.nodeRef,
                            error = "Node ref '$normalizedRef' resolved to a non-clickable element."
                        )
                    }
                }
            }
        } finally {
            root.recycle()
        }
    }

    internal fun longPressNode(service: AssistantAccessibilityService, nodeRef: String): NodeLongPressResult {
        val normalizedRef = normalizeNodeRef(nodeRef)
        if (!looksLikeNodeRef(normalizedRef)) {
            return NodeLongPressResult(pressed = false, error = "Invalid node ref '$nodeRef'.")
        }

        val targetFingerprint = lookupFingerprint(normalizedRef)
            ?: return NodeLongPressResult(
                pressed = false,
                error = "Unknown node ref '$normalizedRef'. Read the latest screen and use an exact node_ref from it."
            )

        val root = AssistantUiTargeting.findAppRoot(service)
            ?: return NodeLongPressResult(pressed = false, error = "Could not read current screen.")

        return try {
            when (val lookup = findNodeByFingerprint(root, targetFingerprint)) {
                is NodeLookupResult.Failed -> NodeLongPressResult(
                    pressed = false,
                    label = targetFingerprint.primaryLabel,
                    error = lookup.error
                )

                is NodeLookupResult.Found -> {
                    registerFingerprint(lookup.fingerprint)
                    val longClickableNode = findLongClickableAncestor(lookup.node)
                    val label = resolveActionLabel(lookup.node, longClickableNode)
                    val pressed = longClickableNode?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true

                    longClickableNode?.recycle()
                    lookup.node.recycle()

                    if (pressed) {
                        NodeLongPressResult(
                            pressed = true,
                            label = label,
                            matchedNodeRef = lookup.fingerprint.nodeRef
                        )
                    } else {
                        NodeLongPressResult(
                            pressed = false,
                            label = label,
                            matchedNodeRef = lookup.fingerprint.nodeRef,
                            error = "Node ref '$normalizedRef' resolved to a non-long-clickable element."
                        )
                    }
                }
            }
        } finally {
            root.recycle()
        }
    }

    internal fun scrollNode(
        service: AssistantAccessibilityService,
        nodeRef: String,
        direction: NodeScrollDirection
    ): NodeScrollResult {
        val normalizedRef = normalizeNodeRef(nodeRef)
        if (!looksLikeNodeRef(normalizedRef)) {
            return NodeScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                error = "Invalid node ref '$nodeRef'."
            )
        }

        val targetFingerprint = lookupFingerprint(normalizedRef)
            ?: return NodeScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                error = "Unknown node ref '$normalizedRef'. Read the latest screen and use an exact node_ref from it."
            )

        val root = AssistantUiTargeting.findAppRoot(service)
            ?: return NodeScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                error = "Could not read current screen."
            )

        return try {
            when (val lookup = findNodeByFingerprint(root, targetFingerprint)) {
                is NodeLookupResult.Failed -> NodeScrollResult(
                    scrolled = false,
                    direction = direction.wireValue,
                    label = targetFingerprint.primaryLabel,
                    error = lookup.error
                )

                is NodeLookupResult.Found -> {
                    registerFingerprint(lookup.fingerprint)
                    val label = resolveActionLabel(lookup.node, lookup.node)
                    val matchedNodeRef = lookup.fingerprint.nodeRef
                    if (!lookup.node.isScrollable || !lookup.node.isEnabled) {
                        lookup.node.recycle()
                        return NodeScrollResult(
                            scrolled = false,
                            direction = direction.wireValue,
                            label = label,
                            matchedNodeRef = matchedNodeRef,
                            error = "Node ref '$normalizedRef' resolved to an element that is not scrollable."
                        )
                    }

                    val scrolled = lookup.node.performAction(direction.accessibilityAction)
                    lookup.node.recycle()

                    if (scrolled) {
                        NodeScrollResult(
                            scrolled = true,
                            direction = direction.wireValue,
                            label = label,
                            matchedNodeRef = matchedNodeRef
                        )
                    } else {
                        NodeScrollResult(
                            scrolled = false,
                            direction = direction.wireValue,
                            label = label,
                            matchedNodeRef = matchedNodeRef,
                            error = "Node ref '$normalizedRef' could not scroll ${direction.wireValue}. The element may already be at its limit."
                        )
                    }
                }
            }
        } finally {
            root.recycle()
        }
    }

    internal fun tapAndTypeText(
        service: AssistantAccessibilityService,
        nodeRef: String,
        text: String
    ): NodeTapTypeTextResult {
        val normalizedRef = normalizeNodeRef(nodeRef)
        if (!looksLikeNodeRef(normalizedRef)) {
            return NodeTapTypeTextResult(updated = false, tapped = false, error = "Invalid node ref '$nodeRef'.")
        }

        val targetFingerprint = lookupFingerprint(normalizedRef)
            ?: return NodeTapTypeTextResult(
                updated = false,
                tapped = false,
                error = "Unknown node ref '$normalizedRef'. Read the latest screen and use an exact node_ref from it."
            )

        val root = AssistantUiTargeting.findAppRoot(service)
            ?: return NodeTapTypeTextResult(
                updated = false,
                tapped = false,
                error = "Could not read current screen."
            )

        val tapContext = try {
            when (val lookup = findNodeByFingerprint(root, targetFingerprint)) {
                is NodeLookupResult.Failed -> {
                    return NodeTapTypeTextResult(
                        updated = false,
                        tapped = false,
                        label = targetFingerprint.primaryLabel,
                        error = lookup.error
                    )
                }

                is NodeLookupResult.Found -> {
                    registerFingerprint(lookup.fingerprint)
                    val tappableNode = findClickableAncestor(lookup.node)
                    val editableNode = findEditableAncestor(lookup.node)
                    val label = resolveActionLabel(lookup.node, editableNode ?: tappableNode)
                    val tapped = tappableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    val directlyEditable = editableNode != null

                    tappableNode?.recycle()
                    editableNode?.recycle()
                    lookup.node.recycle()

                    TapTypeContext(
                        fingerprint = lookup.fingerprint,
                        label = label,
                        tapped = tapped,
                        directlyEditable = directlyEditable
                    )
                }
            }
        } finally {
            root.recycle()
        }

        val matchedFingerprint = tapContext.fingerprint
        val fallbackLabel = tapContext.label
        val tapped = tapContext.tapped
        val canProceedToType = tapped || tapContext.directlyEditable

        if (!canProceedToType) {
            return NodeTapTypeTextResult(
                updated = false,
                tapped = false,
                label = fallbackLabel,
                matchedNodeRef = matchedFingerprint.nodeRef,
                error = "Node ref '$normalizedRef' resolved to a non-clickable, non-editable element."
            )
        }

        val editableCandidate = findEditableTargetWithRetries(
            service = service,
            preferredFingerprint = matchedFingerprint,
            delayBeforeFirstAttempt = tapped
        ) ?: return NodeTapTypeTextResult(
            updated = false,
            tapped = tapped,
            label = fallbackLabel,
            matchedNodeRef = matchedFingerprint.nodeRef,
            error = if (tapped) {
                "Tap completed but no editable field was available to receive text."
            } else {
                "Node ref '$normalizedRef' resolved to an editable field, but text entry could not target it."
            }
        )

        val updated = try {
            performTextSet(editableCandidate.node, text)
        } finally {
            editableCandidate.node.recycle()
        }

        return if (updated) {
            NodeTapTypeTextResult(
                updated = true,
                tapped = tapped,
                label = editableCandidate.label.ifBlank { fallbackLabel },
                matchedNodeRef = matchedFingerprint.nodeRef,
                typedNodeRef = editableCandidate.fingerprint.nodeRef
            )
        } else {
            NodeTapTypeTextResult(
                updated = false,
                tapped = tapped,
                label = editableCandidate.label.ifBlank { fallbackLabel },
                matchedNodeRef = matchedFingerprint.nodeRef,
                typedNodeRef = editableCandidate.fingerprint.nodeRef,
                error = "Failed to enter text into the focused field."
            )
        }
    }

    internal fun typeText(
        service: AssistantAccessibilityService,
        nodeRef: String,
        text: String
    ): NodeTextResult {
        val normalizedRef = normalizeNodeRef(nodeRef)
        if (!looksLikeNodeRef(normalizedRef)) {
            return NodeTextResult(updated = false, error = "Invalid node ref '$nodeRef'.")
        }

        val targetFingerprint = lookupFingerprint(normalizedRef)
            ?: return NodeTextResult(
                updated = false,
                error = "Unknown node ref '$normalizedRef'. Read the latest screen and use an exact node_ref from it."
            )

        val root = AssistantUiTargeting.findAppRoot(service)
            ?: return NodeTextResult(updated = false, error = "Could not read current screen.")

        return try {
            when (val lookup = findNodeByFingerprint(root, targetFingerprint)) {
                is NodeLookupResult.Failed -> NodeTextResult(
                    updated = false,
                    label = targetFingerprint.primaryLabel,
                    error = lookup.error
                )

                is NodeLookupResult.Found -> {
                    registerFingerprint(lookup.fingerprint)
                    val editableNode = findEditableAncestor(lookup.node)
                    val label = resolveActionLabel(lookup.node, editableNode)
                    val updated = editableNode?.let { node -> performTextSet(node, text) } == true

                    editableNode?.recycle()
                    lookup.node.recycle()

                    if (updated) {
                        NodeTextResult(
                            updated = true,
                            label = label,
                            matchedNodeRef = lookup.fingerprint.nodeRef
                        )
                    } else {
                        NodeTextResult(
                            updated = false,
                            label = label,
                            matchedNodeRef = lookup.fingerprint.nodeRef,
                            error = "Node ref '$normalizedRef' resolved to a non-editable element."
                        )
                    }
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun collectIndexedNodes(
        root: AccessibilityNodeInfo,
        targetPackage: String
    ): List<IndexedNode> {
        val lines = mutableListOf<IndexedNode>()
        val duplicateLabelStates = if (USE_FULL_PACKAGE_TREE_DUMP) {
            null
        } else {
            collectDuplicateLabelStates(root, targetPackage)
        }
        val seenNonInteractiveLabels = if (USE_FULL_PACKAGE_TREE_DUMP) {
            null
        } else {
            mutableSetOf<String>()
        }

        walkTree(
            node = root,
            depth = 0,
            lines = lines,
            targetPackage = targetPackage,
            duplicateLabelStates = duplicateLabelStates,
            seenNonInteractiveLabels = seenNonInteractiveLabels,
            ancestorLabels = emptyList(),
            pathSegments = emptyList()
        )

        return lines
    }

    private fun walkTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        lines: MutableList<IndexedNode>,
        targetPackage: String,
        duplicateLabelStates: MutableMap<String, DuplicateLabelState>?,
        seenNonInteractiveLabels: MutableSet<String>?,
        ancestorLabels: List<String>,
        pathSegments: List<Int>
    ) {
        if (!belongsToTargetApp(node, targetPackage)) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val currentPath = if (pathSegments.isEmpty()) listOf(0) else pathSegments
        if (USE_FULL_PACKAGE_TREE_DUMP || shouldIncludeNode(node, duplicateLabelStates, seenNonInteractiveLabels)) {
            val fingerprint = buildFingerprint(
                node = node,
                depth = depth,
                ancestorLabels = ancestorLabels,
                pathSegments = currentPath
            )
            lines.add(
                IndexedNode(
                    fingerprint = fingerprint,
                    line = ScreenLine(formatNodeLine(fingerprint, node))
                )
            )
        }

        val currentLabel = labelForContext(node)
        val nextAncestorLabels = if (currentLabel.isNullOrBlank()) {
            ancestorLabels
        } else {
            (ancestorLabels + currentLabel).takeLast(MAX_ANCESTOR_LABELS)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!belongsToTargetApp(child, targetPackage)) {
                child.recycle()
                continue
            }
            try {
                walkTree(
                    node = child,
                    depth = depth + 1,
                    lines = lines,
                    targetPackage = targetPackage,
                    duplicateLabelStates = duplicateLabelStates,
                    seenNonInteractiveLabels = seenNonInteractiveLabels,
                    ancestorLabels = nextAncestorLabels,
                    pathSegments = currentPath + i
                )
            } finally {
                child.recycle()
            }
        }
    }

    private fun buildFingerprint(
        node: AccessibilityNodeInfo,
        depth: Int,
        ancestorLabels: List<String>,
        pathSegments: List<Int>
    ): NodeFingerprint {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString()?.trim()
        val text = node.text?.toString()?.trim()
        val contentDescription = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()
        val primaryLabel = resolveSemanticLabel(node)
        val siblingOrdinal = pathSegments.lastOrNull() ?: 0
        val pathSignature = pathSegments.joinToString(".")

        val material = buildString {
            append(normalizeForComparison(node.packageName?.toString()))
            append('|')
            append(normalizeForComparison(className))
            append('|')
            append(normalizeForComparison(primaryLabel))
            append('|')
            append(normalizeForComparison(text))
            append('|')
            append(normalizeForComparison(contentDescription))
            append('|')
            append(normalizeForComparison(hint))
            append('|')
            append(node.isClickable)
            append('|')
            append(node.isEditable)
            append('|')
            append(node.isCheckable)
            append('|')
            append(node.isSelected)
            append('|')
            append(depth)
            append('|')
            append(siblingOrdinal)
            append('|')
            append(pathSignature)
            append('|')
            ancestorLabels.forEach { label ->
                append(normalizeForComparison(label))
                append('>')
            }
        }

        return NodeFingerprint(
            nodeRef = "nf_${sha256Hex(material).take(18)}",
            packageName = node.packageName?.toString()?.trim(),
            className = className,
            primaryLabel = primaryLabel,
            text = text,
            contentDescription = contentDescription,
            hint = hint,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            enabled = node.isEnabled,
            scrollable = node.isScrollable,
            depth = depth,
            siblingOrdinal = siblingOrdinal,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            ancestorLabels = ancestorLabels,
            pathSignature = pathSignature
        )
    }

    private fun formatTree(pkg: String, lines: List<ScreenLine>): String {
        if (lines.isEmpty()) return ""

        return buildString {
            append("[App: ")
            append(pkg)
            append("]")
            append('\n')
            append("[Node refs: each visible element in this tree includes an exact node_ref fingerprint, for example node_ref=\"nf_123abc456def7890\". When a tool requires node_ref, use the exact value shown here.]")
            if (USE_FULL_PACKAGE_TREE_DUMP) {
                append('\n')
                append("[Debug: full visible tree dump enabled for the foreground app package]")
            }
            for (line in lines) {
                append('\n')
                append(line.text)
            }
        }
    }

    private fun formatNodeLine(fingerprint: NodeFingerprint, node: AccessibilityNodeInfo): String {
        val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()
        val semanticLabel = resolveSemanticLabel(node)
        val selectionState = resolveSelectionState(node)

        return buildString {
            append('[')
            append(fingerprint.nodeRef)
            append("] ")
            append("depth=")
            append(fingerprint.depth)
            append(" class=")
            append(shortClass)
            if (!text.isNullOrBlank()) {
                append(" text=\"")
                append(text)
                append('"')
            }
            if (!desc.isNullOrBlank() && desc != text) {
                append(" desc=\"")
                append(desc)
                append('"')
            }
            if (!hint.isNullOrBlank() && hint != text) {
                append(" hint=\"")
                append(hint)
                append('"')
            }
            if (!semanticLabel.isNullOrBlank() &&
                semanticLabel != text &&
                semanticLabel != desc &&
                semanticLabel != hint
            ) {
                append(" label=\"")
                append(semanticLabel)
                append('"')
            }
            if (INCLUDE_ANCESTORS_IN_TREE_DUMP && fingerprint.ancestorLabels.isNotEmpty()) {
                append(" ancestors=\"")
                append(fingerprint.ancestorLabels.joinToString(" > "))
                append('"')
            }
            if (selectionState != null) {
                append(" selection=")
                append(selectionState)
            }

            val flags = mutableListOf<String>()
            if (node.isClickable) flags.add("clickable")
            if (node.isLongClickable) flags.add("long-click")
            if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "unchecked")
            if (node.isEditable) flags.add("editable")
            if (node.isFocused) flags.add("focused")
            if (node.isSelected) flags.add("selected")
            if (!node.isEnabled) flags.add("disabled")
            if (node.isScrollable) flags.add("scrollable")
            if (flags.isNotEmpty()) {
                append(" flags=")
                append(flags.joinToString(","))
            }
        }
    }

    private fun findNodeByFingerprint(
        root: AccessibilityNodeInfo,
        targetFingerprint: NodeFingerprint
    ): NodeLookupResult {
        val targetPackage = root.packageName?.toString()
        val duplicateLabelStates = collectDuplicateLabelStates(root, targetPackage)
        val seenNonInteractiveLabels = mutableSetOf<String>()

        var bestMatch: BestNodeMatch? = null
        var secondBestScore = Int.MIN_VALUE

        fun considerCandidate(node: AccessibilityNodeInfo, fingerprint: NodeFingerprint, score: Int) {
            val currentBest = bestMatch
            if (currentBest == null || score > currentBest.score) {
                secondBestScore = maxOf(secondBestScore, currentBest?.score ?: Int.MIN_VALUE)
                currentBest?.node?.recycle()
                bestMatch = BestNodeMatch(AccessibilityNodeInfo.obtain(node), fingerprint, score)
            } else {
                secondBestScore = maxOf(secondBestScore, score)
            }
        }

        fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
            ancestorLabels: List<String>,
            pathSegments: List<Int>
        ) {
            if (!belongsToTargetApp(node, targetPackage)) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val currentPath = if (pathSegments.isEmpty()) listOf(0) else pathSegments
            if (shouldIncludeNode(node, duplicateLabelStates, seenNonInteractiveLabels)) {
                val fingerprint = buildFingerprint(
                    node = node,
                    depth = depth,
                    ancestorLabels = ancestorLabels,
                    pathSegments = currentPath
                )
                val score = scoreFingerprint(targetFingerprint, fingerprint)
                considerCandidate(node, fingerprint, score)
            }

            val currentLabel = labelForContext(node)
            val nextAncestorLabels = if (currentLabel.isNullOrBlank()) {
                ancestorLabels
            } else {
                (ancestorLabels + currentLabel).takeLast(MAX_ANCESTOR_LABELS)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(
                        node = child,
                        depth = depth + 1,
                        ancestorLabels = nextAncestorLabels,
                        pathSegments = currentPath + i
                    )
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, depth = 0, ancestorLabels = emptyList(), pathSegments = emptyList())

        val match = bestMatch ?: return NodeLookupResult.Failed(
            "Node ref '${targetFingerprint.nodeRef}' was not found on the current screen."
        )

        if (match.score < MIN_MATCH_SCORE) {
            match.node.recycle()
            return NodeLookupResult.Failed(
                "Node ref '${targetFingerprint.nodeRef}' could not be matched confidently on the current screen."
            )
        }

        if (match.score < EXACT_MATCH_SCORE && secondBestScore >= match.score - MATCH_AMBIGUITY_MARGIN) {
            match.node.recycle()
            return NodeLookupResult.Failed(
                "Node ref '${targetFingerprint.nodeRef}' is ambiguous on the current screen. Read the screen again and use the latest node_ref."
            )
        }

        return NodeLookupResult.Found(
            node = match.node,
            fingerprint = match.fingerprint
        )
    }

    private fun scoreFingerprint(target: NodeFingerprint, candidate: NodeFingerprint): Int {
        if (target.nodeRef == candidate.nodeRef) return EXACT_MATCH_SCORE

        var score = 0

        if (sameNormalized(target.packageName, candidate.packageName)) score += 6
        if (sameNormalized(target.className, candidate.className)) score += 18
        if (sameNormalized(target.primaryLabel, candidate.primaryLabel)) score += 28
        if (sameNormalized(target.text, candidate.text)) score += 14
        if (sameNormalized(target.contentDescription, candidate.contentDescription)) score += 12
        if (sameNormalized(target.hint, candidate.hint)) score += 8
        if (target.clickable == candidate.clickable) score += 6
        if (target.longClickable == candidate.longClickable) score += 2
        if (target.editable == candidate.editable) score += 8
        if (target.checkable == candidate.checkable) score += 3
        if (target.checked == candidate.checked) score += 3
        if (target.selected == candidate.selected) score += 3
        if (target.enabled == candidate.enabled) score += 2
        if (target.scrollable == candidate.scrollable) score += 2
        if (target.siblingOrdinal == candidate.siblingOrdinal) score += 1
        if (target.pathSignature == candidate.pathSignature) score += 1

        val depthDistance = abs(target.depth - candidate.depth)
        score += when {
            depthDistance == 0 -> 3
            depthDistance == 1 -> 1
            else -> 0
        }

        score += matchingAncestorSuffixScore(target.ancestorLabels, candidate.ancestorLabels) * 4

        return score
    }

    private fun matchingAncestorSuffixScore(left: List<String>, right: List<String>): Int {
        var score = 0
        var leftIndex = left.lastIndex
        var rightIndex = right.lastIndex
        while (leftIndex >= 0 && rightIndex >= 0) {
            if (!sameNormalized(left[leftIndex], right[rightIndex])) break
            score++
            leftIndex--
            rightIndex--
        }
        return score
    }

    private fun resolveSelectionState(node: AccessibilityNodeInfo): String? {
        return when {
            node.isCheckable -> if (node.isChecked) "selected" else "not_selected"
            node.isSelected -> "selected"
            else -> null
        }
    }

    private fun resolveSemanticLabel(node: AccessibilityNodeInfo): String? {
        ownLabel(node)?.let { return it }
        descendantLabel(node)?.let { return it }
        siblingLabel(node)?.let { return it }
        parentLabel(node)?.let { return it }
        return null
    }

    private fun labelForContext(node: AccessibilityNodeInfo): String? {
        return ownLabel(node) ?: resolveSemanticLabel(node)
    }

    private fun ownLabel(node: AccessibilityNodeInfo): String? {
        return firstNonBlank(
            node.text?.toString()?.trim(),
            node.contentDescription?.toString()?.trim(),
            node.hintText?.toString()?.trim()
        )
    }

    private fun descendantLabel(node: AccessibilityNodeInfo): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val label = ownLabel(child) ?: descendantLabel(child)
                if (!label.isNullOrBlank()) return label
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun siblingLabel(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null
        try {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                try {
                    val sameNode = sibling.hashCode() == node.hashCode()
                    val label = if (!sameNode) ownLabel(sibling) ?: descendantLabel(sibling) else null
                    if (!label.isNullOrBlank()) return label
                } finally {
                    sibling.recycle()
                }
            }
            return null
        } finally {
            parent.recycle()
        }
    }

    private fun parentLabel(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null
        return try {
            ownLabel(parent)
        } finally {
            parent.recycle()
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun normalizeNodeRef(rawRef: String): String {
        return rawRef.trim().removePrefix("[").removeSuffix("]")
    }

    private fun looksLikeNodeRef(nodeRef: String): Boolean {
        return nodeRef.startsWith("nf_") && nodeRef.length >= 8
    }

    private fun registerFingerprint(fingerprint: NodeFingerprint) {
        synchronized(knownFingerprints) {
            knownFingerprints[fingerprint.nodeRef] = fingerprint
        }
    }

    private fun lookupFingerprint(nodeRef: String): NodeFingerprint? {
        synchronized(knownFingerprints) {
            return knownFingerprints[nodeRef]
        }
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizeForComparison(value: String?): String {
        return value
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }

    private fun sameNormalized(left: String?, right: String?): Boolean {
        val normalizedLeft = normalizeForComparison(left)
        val normalizedRight = normalizeForComparison(right)
        return normalizedLeft.isNotBlank() && normalizedLeft == normalizedRight
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                return current
            }

            val parent = current.parent?.let { AccessibilityNodeInfo.obtain(it) }
            current.recycle()
            current = parent
        }
        return null
    }

    private fun findLongClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isLongClickable && current.isEnabled) {
                return current
            }

            val parent = current.parent?.let { AccessibilityNodeInfo.obtain(it) }
            current.recycle()
            current = parent
        }
        return null
    }

    private fun findEditableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isEditable && current.isEnabled) {
                return current
            }

            val parent = current.parent?.let { AccessibilityNodeInfo.obtain(it) }
            current.recycle()
            current = parent
        }
        return null
    }

    private fun findEditableTargetWithRetries(
        service: AssistantAccessibilityService,
        preferredFingerprint: NodeFingerprint,
        delayBeforeFirstAttempt: Boolean
    ): EditableCandidate? {
        if (delayBeforeFirstAttempt) {
            SystemClock.sleep(TAP_TYPE_TARGET_DELAY_MS)
        }

        repeat(TAP_TYPE_TARGET_ATTEMPTS) { attempt ->
            val root = AssistantUiTargeting.findAppRoot(service)
            val candidate = root?.let { activeRoot ->
                try {
                    findEditableTarget(activeRoot, preferredFingerprint)
                } finally {
                    activeRoot.recycle()
                }
            }

            if (candidate != null) {
                return candidate
            }

            if (attempt < TAP_TYPE_TARGET_ATTEMPTS - 1) {
                SystemClock.sleep(TAP_TYPE_TARGET_DELAY_MS)
            }
        }

        return null
    }

    private fun findEditableTarget(
        root: AccessibilityNodeInfo,
        preferredFingerprint: NodeFingerprint
    ): EditableCandidate? {
        when (val lookup = findNodeByFingerprint(root, preferredFingerprint)) {
            is NodeLookupResult.Failed -> Unit

            is NodeLookupResult.Found -> {
                try {
                    val editableNode = findEditableAncestor(lookup.node)
                    if (editableNode != null) {
                        registerFingerprint(lookup.fingerprint)
                        return EditableCandidate(
                            node = editableNode,
                            fingerprint = lookup.fingerprint,
                            label = resolveActionLabel(lookup.node, editableNode),
                            focused = editableNode.isFocused || editableNode.isAccessibilityFocused
                        )
                    }
                } finally {
                    lookup.node.recycle()
                }
            }
        }

        val targetPackage = root.packageName?.toString()
        var focusedCandidate: EditableCandidate? = null
        var firstEditableCandidate: EditableCandidate? = null
        var multipleEditableCandidates = false

        fun recordCandidate(node: AccessibilityNodeInfo, fingerprint: NodeFingerprint) {
            registerFingerprint(fingerprint)
            val candidate = EditableCandidate(
                node = AccessibilityNodeInfo.obtain(node),
                fingerprint = fingerprint,
                label = resolveActionLabel(node, node),
                focused = node.isFocused || node.isAccessibilityFocused
            )

            when {
                candidate.focused -> {
                    focusedCandidate?.node?.recycle()
                    firstEditableCandidate?.node?.recycle()
                    firstEditableCandidate = null
                    focusedCandidate = candidate
                }

                focusedCandidate != null -> candidate.node.recycle()
                firstEditableCandidate == null -> firstEditableCandidate = candidate
                else -> {
                    multipleEditableCandidates = true
                    candidate.node.recycle()
                }
            }
        }

        fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
            ancestorLabels: List<String>,
            pathSegments: List<Int>
        ) {
            if (!belongsToTargetApp(node, targetPackage)) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val currentPath = if (pathSegments.isEmpty()) listOf(0) else pathSegments
            if (node.isEditable && node.isEnabled) {
                val fingerprint = buildFingerprint(
                    node = node,
                    depth = depth,
                    ancestorLabels = ancestorLabels,
                    pathSegments = currentPath
                )
                recordCandidate(node, fingerprint)
            }

            if (focusedCandidate != null) {
                return
            }

            val currentLabel = labelForContext(node)
            val nextAncestorLabels = if (currentLabel.isNullOrBlank()) {
                ancestorLabels
            } else {
                (ancestorLabels + currentLabel).takeLast(MAX_ANCESTOR_LABELS)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(
                        node = child,
                        depth = depth + 1,
                        ancestorLabels = nextAncestorLabels,
                        pathSegments = currentPath + i
                    )
                } finally {
                    child.recycle()
                }

                if (focusedCandidate != null) {
                    return
                }
            }
        }

        visit(root, depth = 0, ancestorLabels = emptyList(), pathSegments = emptyList())

        return when {
            focusedCandidate != null -> focusedCandidate
            multipleEditableCandidates -> {
                firstEditableCandidate?.node?.recycle()
                null
            }

            else -> firstEditableCandidate
        }
    }

    private fun performTextSet(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun shouldIncludeNode(
        node: AccessibilityNodeInfo,
        duplicateLabelStates: Map<String, DuplicateLabelState>? = null,
        seenNonInteractiveLabels: MutableSet<String>? = null
    ): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()

        val hasContent = !text.isNullOrBlank() || !desc.isNullOrBlank() || !hint.isNullOrBlank()
        val isInteractive = isInteractiveNode(node)
        val duplicateKey = duplicateLabelKey(node)
        if (!duplicateKey.isNullOrBlank() && duplicateLabelStates != null && !isInteractive) {
            when (duplicateLabelStates[duplicateKey]) {
                DuplicateLabelState.INTERACTIVE -> return false
                DuplicateLabelState.NON_INTERACTIVE -> {
                    if (seenNonInteractiveLabels != null && !seenNonInteractiveLabels.add(duplicateKey)) {
                        return false
                    }
                }

                null -> {
                    if (seenNonInteractiveLabels != null && !seenNonInteractiveLabels.add(duplicateKey)) {
                        return false
                    }
                }
            }
        }
        return hasContent || isInteractive
    }

    private fun collectDuplicateLabelStates(
        root: AccessibilityNodeInfo,
        targetPackage: String?
    ): MutableMap<String, DuplicateLabelState> {
        val states = mutableMapOf<String, DuplicateLabelState>()

        fun visit(node: AccessibilityNodeInfo) {
            if (!belongsToTargetApp(node, targetPackage)) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val key = duplicateLabelKey(node)
                if (!key.isNullOrBlank()) {
                    val nextState = if (isInteractiveNode(node)) {
                        DuplicateLabelState.INTERACTIVE
                    } else {
                        DuplicateLabelState.NON_INTERACTIVE
                    }
                    val currentState = states[key]
                    if (currentState != DuplicateLabelState.INTERACTIVE || nextState == DuplicateLabelState.INTERACTIVE) {
                        states[key] = nextState
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root)
        return states
    }

    private fun isInteractiveNode(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable
    }

    private fun duplicateLabelKey(node: AccessibilityNodeInfo): String? {
        return firstNonBlank(
            resolveSemanticLabel(node),
            node.text?.toString()?.trim(),
            node.contentDescription?.toString()?.trim(),
            node.hintText?.toString()?.trim()
        )?.lowercase()
    }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()
        val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "node"

        return when {
            !text.isNullOrBlank() -> "$shortClass \"$text\""
            !desc.isNullOrBlank() -> "$shortClass ($desc)"
            !hint.isNullOrBlank() -> "$shortClass hint:\"$hint\""
            else -> shortClass
        }
    }

    private fun resolveActionLabel(
        targetNode: AccessibilityNodeInfo,
        actionNode: AccessibilityNodeInfo?
    ): String {
        val semantic = resolveSemanticLabel(targetNode)
            ?: actionNode?.let { resolveSemanticLabel(it) }
            ?: ownLabel(targetNode)
            ?: actionNode?.let { ownLabel(it) }

        if (!semantic.isNullOrBlank()) {
            return semantic
        }

        val actionDescription = actionNode?.let { describeNode(it) }
        if (!actionDescription.isNullOrBlank() && actionDescription != "View" && actionDescription != "node") {
            return actionDescription
        }

        return describeNode(targetNode)
    }

    /** One way to locate a send/save button on a messaging or composer screen. */
    internal data class SendButtonSelector(
        val viewId: String? = null,
        val descEquals: String? = null,
        val textEquals: String? = null
    )

    private data class SendButtonCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int
    )

    private val WHATSAPP_SEND_SELECTORS = listOf(
        SendButtonSelector(viewId = "com.whatsapp:id/send"),
        SendButtonSelector(viewId = "com.whatsapp:id/send_to_button"),
        SendButtonSelector(textEquals = "Send"),
        SendButtonSelector(descEquals = "Send"),
        SendButtonSelector(textEquals = "Next"),
        SendButtonSelector(descEquals = "Next")
    )

    /**
     * Generic ordered send/save button tap. Tries each [selectors] entry in order: a view id
     * is matched exactly via the resource id; a desc/text is matched via the accessibility
     * text search and the first clickable node (or a clickable ancestor) is actioned.
     */
    internal fun tapSendButton(
        service: AssistantAccessibilityService,
        selectors: List<SendButtonSelector>
    ): Boolean {
        val root = service.getUnderlyingAppRoot() ?: return false
        return try {
            for (selector in selectors) {
                if (!selector.viewId.isNullOrBlank()) {
                    val byId = root.findAccessibilityNodeInfosByViewId(selector.viewId)
                    if (!byId.isNullOrEmpty()) {
                        val target = byId.firstOrNull { it.isEnabled } ?: byId[0]
                        val clicked = clickOrGestureTapNode(
                            service = service,
                            node = target,
                            expectedPackage = root.packageName?.toString()
                        )
                        byId.forEach { it.recycle() }
                        if (clicked) return true
                    }
                }
                val query = selector.textEquals ?: selector.descEquals
                if (!query.isNullOrBlank()) {
                    val byText = root.findAccessibilityNodeInfosByText(query)
                    if (!byText.isNullOrEmpty()) {
                        val match = byText.firstOrNull { node ->
                            node.isClickable ||
                                node.text?.toString()?.equals(query, ignoreCase = true) == true ||
                                node.contentDescription?.toString()?.equals(query, ignoreCase = true) == true
                        }
                        val clicked = match?.let {
                            clickOrGestureTapNode(
                                service = service,
                                node = it,
                                expectedPackage = root.packageName?.toString()
                            )
                        } == true
                        byText.forEach { it.recycle() }
                        if (clicked) return true
                    }
                }
            }
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * Gmail-tailored Send tap. Walks the compose tree, collects candidates by viewId,
     * text, and contentDescription, and picks the best one by position (top-right action
     * bar) plus label/id. More resilient than the generic selector-based [tapSendButton]
     * when Gmail's view ids drift between versions or the Send glyph has only a desc.
     */
    internal fun tapGmailSendButton(service: AssistantAccessibilityService): Boolean {
        val root = service.getUnderlyingAppRoot() ?: return false
        val ownedNodes = mutableListOf<AccessibilityNodeInfo>()
        return try {
            val rootBounds = Rect()
            root.getBoundsInScreen(rootBounds)

            root.findAccessibilityNodeInfosByViewId("com.google.android.gm:id/send")
                ?.let { ownedNodes += it }
            root.findAccessibilityNodeInfosByText("Send")
                ?.let { ownedNodes += it }
            collectNodesByLabel(root, "send", ownedNodes)

            val best = ownedNodes
                .asSequence()
                .filter { it.isVisibleToUser && it.isEnabled }
                .mapNotNull { node ->
                    val score = scoreGmailSendCandidate(node, rootBounds)
                    if (score > Int.MIN_VALUE) SendButtonCandidate(node, score) else null
                }
                .maxByOrNull { it.score }

            best?.let { candidate ->
                clickOrGestureTapNode(
                    service = service,
                    node = candidate.node,
                    expectedPackage = root.packageName?.toString()
                )
            } == true
        } finally {
            ownedNodes.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun scoreGmailSendCandidate(node: AccessibilityNodeInfo, rootBounds: Rect): Int {
        val bounds = bestGestureBoundsForNode(node) ?: return Int.MIN_VALUE
        val label = resolveSemanticLabel(node)?.lowercase(Locale.US).orEmpty()
        val viewId = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()

        // Filter look-alikes that are not the compose send action.
        if ("schedule" in label) return Int.MIN_VALUE
        if ("feedback" in label) return Int.MIN_VALUE
        if ("send as" in label) return Int.MIN_VALUE

        var score = 0
        if (viewId.endsWith(":id/send")) score += 500
        if (label == "send") score += 400
        if (node.isClickable) score += 80

        val rootWidth = rootBounds.width().coerceAtLeast(1)
        val rootHeight = rootBounds.height().coerceAtLeast(1)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val xRatio = (centerX - rootBounds.left).toFloat() / rootWidth
        val yRatio = (centerY - rootBounds.top).toFloat() / rootHeight

        // Gmail's Send glyph lives in the top action bar, right side.
        if (yRatio < 0.20f) score += 250
        if (yRatio < 0.10f) score += 150
        if (xRatio > 0.55f) score += 150
        if (xRatio > 0.72f) score += 200

        // Heavy penalty for anything in the body region.
        if (yRatio > 0.30f) score -= 400
        return score
    }

    private fun collectNodesByLabel(
        node: AccessibilityNodeInfo,
        needleLower: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()?.lowercase(Locale.US)
        val desc = node.contentDescription?.toString()?.lowercase(Locale.US)
        if ((text != null && needleLower in text) || (desc != null && needleLower in desc)) {
            out += AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesByLabel(child, needleLower, out)
            child.recycle()
        }
    }

    internal fun tapWhatsAppSendButton(service: AssistantAccessibilityService): Boolean {
        val root = service.getUnderlyingAppRoot() ?: return false
        val ownedNodes = mutableListOf<AccessibilityNodeInfo>()
        return try {
            val rootBounds = Rect()
            root.getBoundsInScreen(rootBounds)

            WHATSAPP_SEND_SELECTORS.forEach { selector ->
                if (!selector.viewId.isNullOrBlank()) {
                    root.findAccessibilityNodeInfosByViewId(selector.viewId)
                        ?.let { nodes -> ownedNodes += nodes }
                }

                val query = selector.textEquals ?: selector.descEquals
                if (!query.isNullOrBlank()) {
                    root.findAccessibilityNodeInfosByText(query)
                        ?.let { nodes -> ownedNodes += nodes }
                }
            }

            val best = ownedNodes
                .asSequence()
                .filter { it.isVisibleToUser && it.isEnabled }
                .mapNotNull { node ->
                    val score = scoreWhatsAppSendCandidate(node, rootBounds)
                    if (score > Int.MIN_VALUE) SendButtonCandidate(node, score) else null
                }
                .maxByOrNull { it.score }

            best?.let { candidate ->
                clickOrGestureTapNode(
                    service = service,
                    node = candidate.node,
                    expectedPackage = root.packageName?.toString()
                )
            } == true
        } finally {
            ownedNodes.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun scoreWhatsAppSendCandidate(node: AccessibilityNodeInfo, rootBounds: Rect): Int {
        val bounds = bestGestureBoundsForNode(node) ?: return Int.MIN_VALUE
        val label = resolveSemanticLabel(node)
            ?.lowercase(Locale.US)
            .orEmpty()
        val viewId = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()

        if ("status" in label) return Int.MIN_VALUE
        if ("my status" in label) return Int.MIN_VALUE

        var score = 0
        if (viewId.endsWith(":id/send") || viewId.endsWith(":id/send_to_button")) score += 500
        if (label == "send" || label == "next") score += 400
        if (node.isClickable) score += 80

        val rootWidth = rootBounds.width().coerceAtLeast(1)
        val rootHeight = rootBounds.height().coerceAtLeast(1)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val xRatio = (centerX - rootBounds.left).toFloat() / rootWidth
        val yRatio = (centerY - rootBounds.top).toFloat() / rootHeight

        if (yRatio > 0.55f) score += 250
        if (yRatio > 0.75f) score += 250
        if (xRatio > 0.50f) score += 120
        if (xRatio > 0.72f) score += 180

        if (bounds.height() > 120 && yRatio < 0.45f) score -= 500
        return score
    }

    private fun clickOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var current: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (current != null && hops < 6) {
            val handled = current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next = current.parent
            current.recycle()
            if (handled) return true
            current = next
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickOrGestureTapNode(
        service: AssistantAccessibilityService,
        node: AccessibilityNodeInfo,
        expectedPackage: String?
    ): Boolean {
        if (clickOrClickableAncestor(node)) return true

        val tapPoints = gestureTapPointsForNode(node)
        return tapPoints.any { point ->
            ScreenGestureDispatcher.tapFullScreenPoint(
                service = service,
                point = point,
                expectedPackage = expectedPackage
            ).tapped
        }
    }

    private fun gestureTapPointsForNode(node: AccessibilityNodeInfo): List<ScreenPixelPoint> {
        val bounds = bestGestureBoundsForNode(node) ?: return emptyList()
        val centerY = bounds.top + (bounds.height() / 2)
        val centerX = bounds.left + (bounds.width() / 2)
        val leftRowX = bounds.left + (bounds.width() * 0.28f).toInt()
        val rightRowX = bounds.right - (bounds.width() * 0.18f).toInt()
        return listOf(
            ScreenPixelPoint(centerX, centerY),
            ScreenPixelPoint(leftRowX.coerceIn(bounds.left + 1, bounds.right - 1), centerY),
            ScreenPixelPoint(rightRowX.coerceIn(bounds.left + 1, bounds.right - 1), centerY)
        ).distinct()
    }

    private fun bestGestureBoundsForNode(node: AccessibilityNodeInfo): Rect? {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)
        var best = nodeBounds.takeIf { it.width() > 0 && it.height() > 0 }?.let { Rect(it) }

        var current: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (current != null && hops < 6) {
            val bounds = Rect()
            current.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val plausibleRow = bounds.height() in 40..260 &&
                    (best == null || bounds.width() >= best.width())
                if (plausibleRow) {
                    best = Rect(bounds)
                }
            }
            val next = current.parent
            current.recycle()
            current = next
            hops++
        }
        return best
    }

    /**
     * Scans a formatted tree [dump] for the first element whose visible text/desc/hint/label
     * contains [query] (case-insensitive) and returns its node_ref, or null if not present.
     */
    internal fun findNodeByText(dump: String?, query: String): String? {
        if (dump.isNullOrBlank()) return null
        val needle = query.trim().lowercase(Locale.US)
        if (needle.isBlank()) return null
        for (line in dump.lineSequence()) {
            val refMatch = NODE_REF_REGEX.find(line) ?: continue
            val attributes = ATTRIBUTE_REGEX.findAll(line)
                .map { it.groupValues[1].lowercase(Locale.US) }
                .joinToString(" ")
            if (attributes.contains(needle)) {
                return normalizeNodeRef(refMatch.groupValues[1])
            }
        }
        return null
    }

    /** Types [text] into the currently focused editable node, or the first editable node. */
    internal fun typeInFocusedEditable(
        service: AssistantAccessibilityService,
        text: String
    ): Boolean {
        val root = service.getUnderlyingAppRoot() ?: return false
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
            val target = focused ?: firstEditableNode(root)
            if (target == null) {
                false
            } else {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                val typed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (target != focused) target.recycle()
                focused?.recycle()
                typed
            }
        } finally {
            root.recycle()
        }
    }

    private fun firstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isVisibleToUser) return AccessibilityNodeInfo.obtain(root)
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = firstEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /**
     * Sets [text] on an editable located by [viewId] (e.g. WhatsApp's picker search field).
     * Falls back to the focused/first editable via [typeInFocusedEditable] when the view id
     * is absent or not editable, so version-brittle ids degrade gracefully.
     */
    internal fun setTextByViewIdOrFocused(
        service: AssistantAccessibilityService,
        viewId: String?,
        text: String
    ): Boolean {
        if (!viewId.isNullOrBlank()) {
            val root = service.getUnderlyingAppRoot()
            if (root != null) {
                try {
                    val byId = root.findAccessibilityNodeInfosByViewId(viewId)
                    val target = byId?.firstOrNull { it.isEditable } ?: byId?.firstOrNull()
                    if (target != null) {
                        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = android.os.Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text
                            )
                        }
                        val typed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        byId.forEach { it.recycle() }
                        if (typed) return true
                    } else {
                        byId?.forEach { it.recycle() }
                    }
                } finally {
                    root.recycle()
                }
            }
        }
        return typeInFocusedEditable(service, text)
    }

    /**
     * Taps a chat/contact row whose visible text equals [name] (case-insensitive), climbing
     * to the clickable row. Falls back to the first contact/chat row by well-known WhatsApp
     * view ids, then to the first clickable node containing [name].
     */
    internal fun tapChatRowByName(
        service: AssistantAccessibilityService,
        name: String
    ): Boolean {
        val root = service.getUnderlyingAppRoot() ?: return false
        val needle = name.trim()
        return try {
            if (needle.isNotBlank()) {
                val byText = root.findAccessibilityNodeInfosByText(needle)
                if (!byText.isNullOrEmpty()) {
                    val exact = byText.firstOrNull { node ->
                        node.text?.toString()?.trim()?.equals(needle, ignoreCase = true) == true ||
                            node.contentDescription?.toString()?.trim()?.equals(needle, ignoreCase = true) == true
                    }
                    val target = exact ?: byText.first()
                    val clicked = clickOrGestureTapNode(
                        service = service,
                        node = target,
                        expectedPackage = root.packageName?.toString()
                    )
                    byText.forEach { it.recycle() }
                    if (clicked) return true
                }
            }
            for (rowId in CHAT_ROW_VIEW_IDS) {
                val rows = root.findAccessibilityNodeInfosByViewId(rowId)
                if (!rows.isNullOrEmpty()) {
                    val clicked = clickOrGestureTapNode(
                        service = service,
                        node = rows.first(),
                        expectedPackage = root.packageName?.toString()
                    )
                    rows.forEach { it.recycle() }
                    if (clicked) return true
                }
            }
            false
        } finally {
            root.recycle()
        }
    }

    private val CHAT_ROW_VIEW_IDS = listOf(
        "com.whatsapp:id/contactpicker_row_name",
        "com.whatsapp:id/conversations_row_contact_name",
        "com.whatsapp:id/contact_row_container"
    )

    private fun belongsToTargetApp(node: AccessibilityNodeInfo, targetPackage: String?): Boolean {
        val nodePackage = node.packageName?.toString()
        if (AssistantUiTargeting.isIgnoredPackage(nodePackage)) return false
        if (targetPackage.isNullOrBlank()) return nodePackage == null || !AssistantUiTargeting.isIgnoredPackage(nodePackage)
        return nodePackage == null || nodePackage == targetPackage
    }
}
