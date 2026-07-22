package com.jarvis.huddash.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.jarvis.huddash.panel.PanelContent
import com.jarvis.huddash.panel.PanelId
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

sealed class ExpandedClickResult {
    data class ItemClicked(val panel: PanelId, val index: Int) : ExpandedClickResult()
    data object ClickedOutside : ExpandedClickResult()
}

private data class ExpandedLayout(
    val rect: RectF,
    val headerHeight: Float,
    val padding: Float,
    val rowHeight: Float,
    val iconSize: Int,
    val textLeft: Float,
    val textMaxWidth: Float,
)

/** Geometry for a pinned panel's own (possibly enlarged) widget — shared by drawing and action hit-testing so they can never drift apart. */
private data class PinnedContentLayout(
    val px: Float,
    val py: Float,
    val radius: Float,
    val maxTextWidth: Float,
    val iconCenterY: Float,
    val titleY: Float,
    val primaryStartY: Float,
    val primaryLines: List<String>,
    val secondaryStartY: Float,
    val secondaryLines: List<String>,
    val detailStartY: Float,
    val detailLines: List<String>,
    val progressY: Float,
    val contentBottom: Float,
    val actionsRowY: Float,
    val actionButtons: List<RectF>,
)

/** Greedy word-wrap capped at [maxLines], with the final visible line ellipsized if the text overflows. */
private fun wrapText(text: String, maxWidth: Float, paint: Paint, maxLines: Int): List<String> {
    if (text.isEmpty() || maxLines <= 0) return emptyList()

    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
            current = StringBuilder(candidate)
        } else {
            lines.add(current.toString())
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines.add(current.toString())

    if (lines.size <= maxLines) return lines

    val visible = lines.take(maxLines).toMutableList()
    var last = visible[maxLines - 1]
    while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) {
        last = last.dropLast(1)
    }
    visible[maxLines - 1] = "$last…"
    return visible
}

/**
 * Draws the 3 panels at their fixed clock-angle positions, elliptical orbit (wider
 * horizontally than vertically, since the display is landscape) rather than a plain
 * circle — so the 10/2 o'clock panels sit farther toward the screen edges, while 6
 * o'clock stays within the shorter vertical space. Opacity/scale per panel come
 * entirely from the reveal weights the InputController computes — this view has no
 * gesture or input logic of its own.
 *
 * Holographic HUD look (thin glowing rings + corner brackets, no filled shapes) —
 * requires a software layer for the glow (Paint.setShadowLayer on text/strokes isn't
 * reliably hardware-accelerated), which is fine at this scale (3 small panels).
 */
class SpatialRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Frame-rate fallback: set false to drop scale animation and keep opacity-only reveal. */
    var useScaleAnimation: Boolean = true

    var panelContents: Map<PanelId, PanelContent> = emptyMap()
        set(value) { field = value; invalidate() }

    var reveals: Map<PanelId, Float> = emptyMap()
        set(value) { field = value; invalidate() }

    /**
     * Panels that stay visible for as long as their underlying session is active,
     * independent of trackpad gesture state — e.g. Nav, for the duration of an active
     * Google Maps route. Gesture-driven reveal still applies on top (nudging toward an
     * ambient panel can still fully sharpen/pin it); this only sets the visibility floor.
     */
    var ambientPanels: Set<PanelId> = emptySet()
        set(value) { field = value; invalidate() }

    var pinnedPanel: PanelId? = null
        set(value) { field = value; invalidate() }

    /** Wear-test telemetry summary (see MainActivity/DebugTelemetry) — null hides the overlay. */
    var debugOverlayText: String? = null
        set(value) { field = value; invalidate() }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val glowColor = Color.rgb(0x29, 0xE0, 0xFF)
    private val dimColor = Color.rgb(0x7A, 0xC8, 0xD9)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        style = Paint.Style.STROKE
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        style = Paint.Style.STROKE
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.15f
    }
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        style = Paint.Style.STROKE
    }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        style = Paint.Style.STROKE
    }
    private val expandedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val expandedDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        style = Paint.Style.STROKE
    }
    // Left-aligned counterparts for expanded-view rows (compact panels stay centered).
    private val expandedHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.15f
    }
    private val expandedTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
    }
    private val expandedBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
    }
    // Separate from glyphPaint so drawing an enlarged action-button glyph never needs to
    // mutate-then-restore the shared compact glyph size used by every other panel.
    private val actionGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    // Smaller panels, elliptical orbit: wide horizontally (landscape screen has the
    // room), tighter vertically (bounded by the shorter dimension).
    private val baseRadiusPx get() = min(width, height) * 0.11f
    private val orbitRadiusXPx get() = width * 0.42f
    private val orbitRadiusYPx get() = height * 0.34f

    /** How much a pinned panel with inline extras (actions/detail lines) grows over its compact size. */
    private val enlargedRadiusMultiplier = 1.7f

    /**
     * Per-panel pixel nudge (fraction of width/height) applied on top of the normal
     * angle-based orbit position — purely a draw-position adjustment for panels that
     * were clipping against a screen edge. Deliberately NOT applied to the gesture
     * wedge math in InputController, which still targets the panel's true
     * [PanelId.clockAngleDegrees] — only where the widget visually renders shifts.
     */
    private val panelPositionBias: Map<PanelId, Pair<Float, Float>> = mapOf(
        PanelId.TIME to (0.07f to 0f),   // right
        PanelId.MEDIA to (0f to -0.07f), // up
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val centerX = width / 2f
        val centerY = height / 2f

        glyphPaint.textSize = baseRadiusPx * 0.55f
        titlePaint.textSize = baseRadiusPx * 0.24f
        primaryPaint.textSize = baseRadiusPx * 0.30f
        secondaryPaint.textSize = baseRadiusPx * 0.22f
        expandedHeaderPaint.textSize = baseRadiusPx * 0.26f
        expandedTitlePaint.textSize = baseRadiusPx * 0.22f
        expandedBodyPaint.textSize = baseRadiusPx * 0.20f
        debugPaint.textSize = baseRadiusPx * 0.18f

        // Expanded ("full investigation") view draws last so it sits on top of any
        // other still-visible compact panels, and is drawn centered/full-size — the
        // pinned panel's own compact ring node is skipped in favor of it.
        var expanded: Pair<PanelContent, PanelId>? = null

        for (panel in PanelId.entries) {
            val gestureReveal = reveals[panel] ?: 0f
            val ambientReveal = if (panel in ambientPanels) 1f else 0f
            val content = panelContents[panel] ?: continue
            val isPinned = pinnedPanel == panel
            val effectiveReveal = if (isPinned) 1f else maxOf(gestureReveal, ambientReveal)
            if (effectiveReveal <= 0.01f) continue

            if (isPinned && content.expandedItems.isNotEmpty()) {
                expanded = content to panel
                continue
            }
            drawPanel(canvas, panel, content, effectiveReveal, isPinned)
        }

        expanded?.let { (content, _) ->
            drawExpandedPanel(canvas, centerX, centerY, content.title, content.expandedItems)
        }

        debugOverlayText?.let { text ->
            debugPaint.alpha = 220
            canvas.drawText(text, baseRadiusPx * 0.2f, baseRadiusPx * 0.4f, debugPaint)
        }
    }

    /**
     * Shared geometry for a panel's own widget — same function drives both [drawPanel]
     * and [hitTestPinnedActions], so the action buttons drawn on screen and the regions
     * that respond to clicks can never drift apart (same discipline as [computeExpandedLayout]).
     * When pinned with inline extras ([PanelContent.actions] or [PanelContent.pinnedDetailLines]),
     * the widget enlarges to fit — it stays the same widget, just bigger, rather than
     * being replaced by a separate menu box.
     */
    private fun computePinnedContentLayout(panel: PanelId, content: PanelContent, reveal: Float, isPinned: Boolean): PinnedContentLayout {
        val centerX = width / 2f
        val centerY = height / 2f
        val angleRad = Math.toRadians(panel.clockAngleDegrees.toDouble())
        // Panels drift in from slightly further out along the orbit as they're
        // revealed, settling into their true position once fully shown (or pinned) —
        // real motion on top of the existing opacity/scale reveal, not just a fade.
        val orbitMultiplier = 1f + (1f - if (isPinned) 1f else reveal) * 0.18f
        val bias = panelPositionBias[panel]
        val px = centerX + orbitRadiusXPx * orbitMultiplier * sin(angleRad).toFloat() + (bias?.first ?: 0f) * width
        val py = centerY - orbitRadiusYPx * orbitMultiplier * cos(angleRad).toFloat() + (bias?.second ?: 0f) * height

        val scale = if (useScaleAnimation) 0.7f + 0.3f * reveal else 1f
        val hasInlineExtras = content.actions.isNotEmpty() || content.pinnedDetailLines.isNotEmpty()
        val enlarge = isPinned && hasInlineExtras
        val radius = baseRadiusPx * scale * (if (enlarge) enlargedRadiusMultiplier else 1f)

        val maxTextWidth = radius * 2.4f
        val primaryLines = wrapText(content.primaryText, maxTextWidth, primaryPaint, maxLines = 2)
        val secondaryLines = content.secondaryText?.let {
            wrapText(it, maxTextWidth, secondaryPaint, maxLines = 2)
        } ?: emptyList()
        val detailLines = if (enlarge) content.pinnedDetailLines else emptyList()

        // Text height is unbounded (title + up to 2 primary + up to 2 secondary lines
        // routinely exceeds the ring's diameter), so text can end up vertically
        // overlapping the ring's own stroke, or spilling past the corner brackets
        // entirely — laying out every Y-position up front (rather than drawing inline
        // as we go) lets a backing plate be sized to the ACTUAL content and drawn
        // before any text, so legibility never depends on what's behind it.
        val iconCenterY = py - radius * 0.4f
        val titleY = iconCenterY + radius * 0.45f
        var cursorY = titleY + titlePaint.textSize * 1.3f
        val primaryStartY = cursorY
        cursorY += primaryLines.size * primaryPaint.textSize * 1.2f
        val secondaryStartY = cursorY
        cursorY += secondaryLines.size * secondaryPaint.textSize * 1.2f
        val detailStartY = cursorY
        cursorY += detailLines.size * secondaryPaint.textSize * 1.2f
        val progressY = cursorY + radius * 0.08f
        cursorY = if (content.progressFraction != null) progressY + radius * 0.06f else cursorY

        var actionsRowY = cursorY
        val actionButtons = mutableListOf<RectF>()
        if (enlarge && content.actions.isNotEmpty()) {
            actionsRowY = cursorY + radius * 0.24f
            val buttonRadius = radius * 0.26f
            val spacing = buttonRadius * 2.6f
            val totalWidth = spacing * (content.actions.size - 1)
            val startX = px - totalWidth / 2f
            for (i in content.actions.indices) {
                val bx = startX + spacing * i
                actionButtons += RectF(bx - buttonRadius, actionsRowY - buttonRadius, bx + buttonRadius, actionsRowY + buttonRadius)
            }
            cursorY = actionsRowY + buttonRadius
        }

        return PinnedContentLayout(
            px = px,
            py = py,
            radius = radius,
            maxTextWidth = maxTextWidth,
            iconCenterY = iconCenterY,
            titleY = titleY,
            primaryStartY = primaryStartY,
            primaryLines = primaryLines,
            secondaryStartY = secondaryStartY,
            secondaryLines = secondaryLines,
            detailStartY = detailStartY,
            detailLines = detailLines,
            progressY = progressY,
            contentBottom = cursorY,
            actionsRowY = actionsRowY,
            actionButtons = actionButtons,
        )
    }

    private fun drawPanel(
        canvas: Canvas,
        panel: PanelId,
        content: PanelContent,
        reveal: Float,
        isPinned: Boolean,
    ) {
        val layout = computePinnedContentLayout(panel, content, reveal, isPinned)
        val px = layout.px
        val py = layout.py
        val radius = layout.radius
        val alpha = (reveal * 255).toInt().coerceIn(0, 255)
        val glowRadius = if (isPinned) radius * 0.35f else radius * 0.18f

        ringPaint.alpha = alpha
        ringPaint.strokeWidth = radius * 0.05f
        ringPaint.setShadowLayer(glowRadius, 0f, 0f, glowColor)
        canvas.drawCircle(px, py, radius, ringPaint)
        if (isPinned) {
            canvas.drawCircle(px, py, radius * 1.15f, ringPaint)
        }

        bracketPaint.alpha = alpha
        bracketPaint.strokeWidth = radius * 0.05f
        bracketPaint.setShadowLayer(glowRadius, 0f, 0f, glowColor)
        drawCornerBrackets(canvas, px, py, radius * 1.35f)

        glyphPaint.alpha = alpha
        titlePaint.alpha = alpha
        primaryPaint.alpha = alpha
        secondaryPaint.alpha = alpha
        progressTrackPaint.alpha = (alpha * 0.6f).toInt()
        progressFillPaint.alpha = alpha
        glyphPaint.setShadowLayer(glowRadius, 0f, 0f, glowColor)

        if (alpha > 10) {
            val plateRect = RectF(
                px - layout.maxTextWidth / 2f - radius * 0.12f,
                layout.titleY - titlePaint.textSize * 0.95f,
                px + layout.maxTextWidth / 2f + radius * 0.12f,
                layout.contentBottom + radius * 0.05f,
            )
            expandedFillPaint.alpha = (alpha * 0.6f).toInt()
            canvas.drawRoundRect(plateRect, radius * 0.15f, radius * 0.15f, expandedFillPaint)
        }

        val icon = content.iconDrawable
        if (icon != null) {
            val iconSize = (radius * 0.5f).toInt()
            icon.setBounds(
                (px - iconSize / 2f).toInt(),
                (layout.iconCenterY - iconSize / 2f).toInt(),
                (px + iconSize / 2f).toInt(),
                (layout.iconCenterY + iconSize / 2f).toInt(),
            )
            icon.alpha = alpha
            icon.draw(canvas)
        } else {
            canvas.drawText(content.glyph, px, layout.iconCenterY + glyphPaint.textSize * 0.35f, glyphPaint)
        }

        canvas.drawText(content.title.uppercase(), px, layout.titleY, titlePaint)

        var cursorY = layout.primaryStartY
        for (line in layout.primaryLines) {
            canvas.drawText(line, px, cursorY, primaryPaint)
            cursorY += primaryPaint.textSize * 1.2f
        }

        cursorY = layout.secondaryStartY
        for (line in layout.secondaryLines) {
            canvas.drawText(line, px, cursorY, secondaryPaint)
            cursorY += secondaryPaint.textSize * 1.2f
        }

        cursorY = layout.detailStartY
        for (line in layout.detailLines) {
            canvas.drawText(line, px, cursorY, secondaryPaint)
            cursorY += secondaryPaint.textSize * 1.2f
        }

        content.progressFraction?.let { fraction ->
            drawProgressBar(canvas, px, layout.progressY, radius * 1.1f, fraction.coerceIn(0f, 1f))
        }

        for ((index, rect) in layout.actionButtons.withIndex()) {
            val action = content.actions.getOrNull(index) ?: continue
            val bcx = rect.centerX()
            val bcy = rect.centerY()
            val buttonRadius = rect.width() / 2f

            ringPaint.alpha = alpha
            ringPaint.strokeWidth = buttonRadius * 0.12f
            ringPaint.setShadowLayer(buttonRadius * 0.3f, 0f, 0f, glowColor)
            canvas.drawCircle(bcx, bcy, buttonRadius, ringPaint)

            actionGlyphPaint.alpha = alpha
            actionGlyphPaint.textSize = buttonRadius * 1.1f
            actionGlyphPaint.setShadowLayer(buttonRadius * 0.25f, 0f, 0f, glowColor)
            canvas.drawText(action.glyph, bcx, bcy + actionGlyphPaint.textSize * 0.35f, actionGlyphPaint)
        }
    }

    /** Same geometry used by both drawing and click hit-testing — never compute this twice, they'd drift. */
    private fun computeExpandedLayout(cx: Float, cy: Float, itemCount: Int): ExpandedLayout {
        val boxWidth = width * 0.5f
        val padding = baseRadiusPx * 0.3f
        val iconSize = (baseRadiusPx * 0.5f).toInt()
        val rowHeight = baseRadiusPx * 1.0f
        val headerHeight = expandedHeaderPaint.textSize * 1.8f
        val boxHeight = (headerHeight + padding * 2 + rowHeight * itemCount).coerceAtMost(height * 0.85f)

        val rect = RectF(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f)
        val textLeft = rect.left + padding * 2 + iconSize
        val textMaxWidth = rect.right - padding - textLeft

        return ExpandedLayout(rect, headerHeight, padding, rowHeight, iconSize, textLeft, textMaxWidth)
    }

    /**
     * "Full investigation" detail view for a pinned panel with [PanelContent.expandedItems] —
     * always centered on screen (not at the originating ring position) so it never clips
     * off-screen, since E/W panels sit near the display edges on the elliptical orbit.
     */
    private fun drawExpandedPanel(canvas: Canvas, cx: Float, cy: Float, headerTitle: String, items: List<PanelContent>) {
        val layout = computeExpandedLayout(cx, cy, items.size)
        val rect = layout.rect
        val corner = layout.padding * 0.6f

        expandedFillPaint.alpha = 170
        canvas.drawRoundRect(rect, corner, corner, expandedFillPaint)

        ringPaint.alpha = 255
        ringPaint.strokeWidth = baseRadiusPx * 0.045f
        ringPaint.setShadowLayer(baseRadiusPx * 0.3f, 0f, 0f, glowColor)
        canvas.drawRoundRect(rect, corner, corner, ringPaint)

        expandedHeaderPaint.alpha = 255
        canvas.drawText(headerTitle.uppercase(), rect.left + layout.padding, rect.top + layout.padding + expandedHeaderPaint.textSize, expandedHeaderPaint)

        if (items.isEmpty()) {
            expandedBodyPaint.alpha = 255
            canvas.drawText("Nothing here", rect.left + layout.padding, rect.top + layout.headerHeight + layout.padding + expandedBodyPaint.textSize, expandedBodyPaint)
            return
        }

        var rowY = rect.top + layout.headerHeight + layout.padding

        expandedTitlePaint.alpha = 255
        expandedBodyPaint.alpha = 255
        expandedDividerPaint.alpha = 90
        expandedDividerPaint.strokeWidth = 2f

        for ((index, item) in items.withIndex()) {
            val iconTop = rowY + layout.rowHeight * 0.08f
            val icon = item.iconDrawable
            if (icon != null) {
                icon.setBounds(
                    (rect.left + layout.padding).toInt(),
                    iconTop.toInt(),
                    (rect.left + layout.padding + layout.iconSize).toInt(),
                    (iconTop + layout.iconSize).toInt(),
                )
                icon.alpha = 255
                icon.draw(canvas)
            } else {
                canvas.drawText(item.glyph, rect.left + layout.padding, iconTop + layout.iconSize * 0.75f, expandedHeaderPaint)
            }

            var textY = rowY + expandedTitlePaint.textSize
            canvas.drawText(item.title.uppercase(), layout.textLeft, textY, expandedTitlePaint)
            textY += expandedTitlePaint.textSize * 1.2f

            for (line in wrapText(item.primaryText, layout.textMaxWidth, expandedBodyPaint, maxLines = 1)) {
                canvas.drawText(line, layout.textLeft, textY, expandedBodyPaint)
                textY += expandedBodyPaint.textSize * 1.15f
            }
            item.secondaryText?.let { secondary ->
                for (line in wrapText(secondary, layout.textMaxWidth, expandedBodyPaint, maxLines = 1)) {
                    canvas.drawText(line, layout.textLeft, textY, expandedBodyPaint)
                    textY += expandedBodyPaint.textSize * 1.15f
                }
            }

            rowY += layout.rowHeight
            if (index != items.lastIndex) {
                canvas.drawLine(rect.left + layout.padding, rowY - layout.rowHeight * 0.06f, rect.right - layout.padding, rowY - layout.rowHeight * 0.06f, expandedDividerPaint)
            }
        }
    }

    /**
     * Hit-test a click against the currently-shown expanded menu, if any. Returns null
     * when no panel is pinned-with-a-menu right now (caller should treat the click as a
     * normal ring-level click instead — see MainActivity.handleClick).
     */
    fun hitTestExpanded(x: Float, y: Float): ExpandedClickResult? {
        val panel = pinnedPanel ?: return null
        val content = panelContents[panel] ?: return null
        if (content.expandedItems.isEmpty()) return null

        val centerX = width / 2f
        val centerY = height / 2f
        val layout = computeExpandedLayout(centerX, centerY, content.expandedItems.size)

        if (!layout.rect.contains(x, y)) return ExpandedClickResult.ClickedOutside

        val relativeY = y - (layout.rect.top + layout.headerHeight + layout.padding)
        if (relativeY < 0f) return ExpandedClickResult.ClickedOutside // click landed on the header row

        val index = (relativeY / layout.rowHeight).toInt()
        if (index !in content.expandedItems.indices) return ExpandedClickResult.ClickedOutside

        return ExpandedClickResult.ItemClicked(panel, index)
    }

    /**
     * Hit-test a click against the pinned panel's own inline action buttons, if any —
     * see [PanelContent.actions]. Returns null when the pinned panel has no actions
     * (caller should treat the click as a normal ring-level click instead — mirrors
     * [hitTestExpanded]'s contract exactly, just for the enlarged-in-place widget
     * instead of the screen-centered list menu).
     */
    fun hitTestPinnedActions(x: Float, y: Float): ExpandedClickResult? {
        val panel = pinnedPanel ?: return null
        val content = panelContents[panel] ?: return null
        if (content.actions.isEmpty()) return null

        val layout = computePinnedContentLayout(panel, content, reveal = 1f, isPinned = true)
        for ((index, rect) in layout.actionButtons.withIndex()) {
            if (rect.contains(x, y)) return ExpandedClickResult.ItemClicked(panel, index)
        }
        return ExpandedClickResult.ClickedOutside
    }

    private fun drawProgressBar(canvas: Canvas, cx: Float, y: Float, halfWidth: Float, fraction: Float) {
        progressTrackPaint.strokeWidth = halfWidth * 0.06f
        progressFillPaint.strokeWidth = halfWidth * 0.06f
        canvas.drawLine(cx - halfWidth, y, cx + halfWidth, y, progressTrackPaint)
        if (fraction > 0f) {
            canvas.drawLine(cx - halfWidth, y, cx - halfWidth + halfWidth * 2f * fraction, y, progressFillPaint)
        }
    }

    /** Four short L-shaped ticks at the corners of a square around the panel — HUD reticle look. */
    private fun drawCornerBrackets(canvas: Canvas, cx: Float, cy: Float, half: Float) {
        val tick = half * 0.35f
        val corners = listOf(
            Triple(cx - half, cy - half, Pair(1, 1)),   // top-left
            Triple(cx + half, cy - half, Pair(-1, 1)),  // top-right
            Triple(cx - half, cy + half, Pair(1, -1)),  // bottom-left
            Triple(cx + half, cy + half, Pair(-1, -1)), // bottom-right
        )
        for ((x, y, dir) in corners) {
            val (dx, dy) = dir
            canvas.drawLine(x, y, x + tick * dx, y, bracketPaint)
            canvas.drawLine(x, y, x, y + tick * dy, bracketPaint)
        }
    }
}
