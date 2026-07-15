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

    // Smaller panels, elliptical orbit: wide horizontally (landscape screen has the
    // room), tighter vertically (bounded by the shorter dimension).
    private val baseRadiusPx get() = min(width, height) * 0.11f
    private val orbitRadiusXPx get() = width * 0.42f
    private val orbitRadiusYPx get() = height * 0.34f

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
            drawPanel(canvas, centerX, centerY, panel, content, effectiveReveal, isPinned)
        }

        expanded?.let { (content, _) ->
            drawExpandedPanel(canvas, centerX, centerY, content.title, content.expandedItems)
        }
    }

    private fun drawPanel(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        panel: PanelId,
        content: PanelContent,
        reveal: Float,
        isPinned: Boolean,
    ) {
        val angleRad = Math.toRadians(panel.clockAngleDegrees.toDouble())
        val px = centerX + orbitRadiusXPx * sin(angleRad).toFloat()
        val py = centerY - orbitRadiusYPx * cos(angleRad).toFloat()

        val scale = if (useScaleAnimation) 0.7f + 0.3f * reveal else 1f
        val radius = baseRadiusPx * scale
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

        val maxTextWidth = radius * 2.4f
        var cursorY = py - radius * 0.4f

        val icon = content.iconDrawable
        if (icon != null) {
            val iconSize = (radius * 0.5f).toInt()
            icon.setBounds(
                (px - iconSize / 2f).toInt(),
                (cursorY - iconSize / 2f).toInt(),
                (px + iconSize / 2f).toInt(),
                (cursorY + iconSize / 2f).toInt(),
            )
            icon.alpha = alpha
            icon.draw(canvas)
        } else {
            canvas.drawText(content.glyph, px, cursorY + glyphPaint.textSize * 0.35f, glyphPaint)
        }
        cursorY += radius * 0.45f

        canvas.drawText(content.title.uppercase(), px, cursorY, titlePaint)
        cursorY += titlePaint.textSize * 1.3f

        for (line in wrapText(content.primaryText, maxTextWidth, primaryPaint, maxLines = 2)) {
            canvas.drawText(line, px, cursorY, primaryPaint)
            cursorY += primaryPaint.textSize * 1.2f
        }

        content.secondaryText?.let { secondary ->
            for (line in wrapText(secondary, maxTextWidth, secondaryPaint, maxLines = 2)) {
                canvas.drawText(line, px, cursorY, secondaryPaint)
                cursorY += secondaryPaint.textSize * 1.2f
            }
        }

        content.progressFraction?.let { fraction ->
            drawProgressBar(canvas, px, cursorY + radius * 0.08f, radius * 1.1f, fraction.coerceIn(0f, 1f))
        }
    }

    /**
     * "Full investigation" detail view for a pinned panel with [PanelContent.expandedItems] —
     * always centered on screen (not at the originating ring position) so it never clips
     * off-screen, since E/W panels sit near the display edges on the elliptical orbit.
     */
    private fun drawExpandedPanel(canvas: Canvas, cx: Float, cy: Float, headerTitle: String, items: List<PanelContent>) {
        val boxWidth = width * 0.5f
        val padding = baseRadiusPx * 0.3f
        val iconSize = (baseRadiusPx * 0.5f).toInt()
        val rowHeight = baseRadiusPx * 1.0f
        val headerHeight = expandedHeaderPaint.textSize * 1.8f
        val boxHeight = (headerHeight + padding * 2 + rowHeight * items.size).coerceAtMost(height * 0.85f)

        val rect = RectF(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f)
        val corner = padding * 0.6f

        expandedFillPaint.alpha = 170
        canvas.drawRoundRect(rect, corner, corner, expandedFillPaint)

        ringPaint.alpha = 255
        ringPaint.strokeWidth = baseRadiusPx * 0.045f
        ringPaint.setShadowLayer(baseRadiusPx * 0.3f, 0f, 0f, glowColor)
        canvas.drawRoundRect(rect, corner, corner, ringPaint)

        expandedHeaderPaint.alpha = 255
        canvas.drawText(headerTitle.uppercase(), rect.left + padding, rect.top + padding + expandedHeaderPaint.textSize, expandedHeaderPaint)

        if (items.isEmpty()) {
            expandedBodyPaint.alpha = 255
            canvas.drawText("Nothing here", rect.left + padding, rect.top + headerHeight + padding + expandedBodyPaint.textSize, expandedBodyPaint)
            return
        }

        val textLeft = rect.left + padding * 2 + iconSize
        val textMaxWidth = rect.right - padding - textLeft
        var rowY = rect.top + headerHeight + padding

        expandedTitlePaint.alpha = 255
        expandedBodyPaint.alpha = 255
        expandedDividerPaint.alpha = 90
        expandedDividerPaint.strokeWidth = 2f

        for ((index, item) in items.withIndex()) {
            val iconTop = rowY + rowHeight * 0.08f
            val icon = item.iconDrawable
            if (icon != null) {
                icon.setBounds(
                    (rect.left + padding).toInt(),
                    iconTop.toInt(),
                    (rect.left + padding + iconSize).toInt(),
                    (iconTop + iconSize).toInt(),
                )
                icon.alpha = 255
                icon.draw(canvas)
            } else {
                canvas.drawText(item.glyph, rect.left + padding, iconTop + iconSize * 0.75f, expandedHeaderPaint)
            }

            var textY = rowY + expandedTitlePaint.textSize
            canvas.drawText(item.title.uppercase(), textLeft, textY, expandedTitlePaint)
            textY += expandedTitlePaint.textSize * 1.2f

            for (line in wrapText(item.primaryText, textMaxWidth, expandedBodyPaint, maxLines = 1)) {
                canvas.drawText(line, textLeft, textY, expandedBodyPaint)
                textY += expandedBodyPaint.textSize * 1.15f
            }
            item.secondaryText?.let { secondary ->
                for (line in wrapText(secondary, textMaxWidth, expandedBodyPaint, maxLines = 1)) {
                    canvas.drawText(line, textLeft, textY, expandedBodyPaint)
                    textY += expandedBodyPaint.textSize * 1.15f
                }
            }

            rowY += rowHeight
            if (index != items.lastIndex) {
                canvas.drawLine(rect.left + padding, rowY - rowHeight * 0.06f, rect.right - padding, rowY - rowHeight * 0.06f, expandedDividerPaint)
            }
        }
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
