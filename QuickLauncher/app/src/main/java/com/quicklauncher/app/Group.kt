package com.quicklauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * App Group panel, sized like a Nova Launcher folder: a small solid-black card that wraps its apps
 * (up to 4 columns x 4 rows, then it scrolls) and floats next to the group's tile. The main popup stays
 * visible and live underneath, so the finger can slide out of the panel onto another group and open that
 * one instead. Hover mode: release over an app to launch it. Touch mode: tap an app, drag to scroll.
 *
 * Pointer coordinates handed to [pointer] / [open] are LayerView coordinates; the view converts them with its own left/top.
 */
class GroupView(ctx: Context, private val mgr: OverlayManager) : View(ctx) {
    var onBack: () -> Unit = {}
    var onLaunch: (Item) -> Unit = {}
    /** False while the finger that opened the panel is still down (hover mode); true once it is a tap/scroll panel. */
    var touchMode = false
    var itemId = ""
        private set
    /** Popup edit mode: the shortcuts show delete / edit badges, can be dragged to sort, and a "+" cell adds more. */
    var editMode = false
    var onEditGroup: () -> Unit = {}
    var onDeleteGroup: () -> Unit = {}
    var onEditMember: (Item) -> Unit = {}
    var onAddMember: () -> Unit = {}
    var onDeleteMember: (Item) -> Unit = {}
    /** Drag and drop sorting: (moving member id, id of the member whose place it was dropped on). */
    var onReorder: (String, String) -> Unit = { _, _ -> }

    private class Cell(val label: String, val item: Item) { var text: CharSequence = label }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val headerH = 34 * d
    private val pad = 10 * d

    private var title = ""
    private var cells: List<Cell> = emptyList()
    private var scroll = 0f
    private var hover = -1
    private var armed = true
    private var openX = 0f
    private var openY = 0f
    private var lastX = -1f
    private var lastY = -1f
    private var autoDir = 0
    private var tMode = 0
    private var downX = 0f
    private var downY = 0f
    private var lastTY = 0f
    private var pressIdx = -1       // member under the finger when it went down (long press starts a drag)
    private var pressBadge = -1     // member whose delete badge was pressed
    private var dragIdx = -1
    private var dragX = 0f
    private var dragY = 0f
    private var overIdx = -1
    private val dash = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    private val longPress = Runnable {
        if (tMode != 1 || pressIdx !in cells.indices) return@Runnable
        tMode = 3; dragIdx = pressIdx; overIdx = pressIdx; dragX = downX; dragY = downY
        hover = -1
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    // metrics, recomputed by measureFor()
    private var colsN = 3
    private var rowsN = 1
    private var ip = 48 * d
    private var cw = 70 * d
    private var chh = 74 * d
    private var gridL = 0f
    private var wantW = 0
    private var wantH = 0

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rf = RectF()
    private val clip = Path()
    private val inv: () -> Unit = { invalidate() }

    private val auto = object : Runnable {
        override fun run() {
            if (autoDir == 0) return
            val before = scroll
            scroll += autoDir * 12 * d
            clampScroll()
            if (scroll == before) { autoDir = 0; return }
            if (armed && lastY > headerH) setHover(cellAt(lastX, lastY))
            invalidate()
            postOnAnimation(this)
        }
    }

    // ───── sizing ─────
    fun wantedW() = wantW
    fun wantedH() = wantH

    /** Works out the panel size for the current apps, limited to [maxW] x [maxH] px. */
    fun measureFor(maxW: Int, maxH: Int) {
        val s = mgr.popupSettings
        ip = s.icon.coerceIn(36f, 52f) * d          // the panel keeps usable icons even when the popup grid is very dense
        cw = ip + 22 * d
        chh = ip + (if (s.showLbl) 28 else 10) * d
        val n = max(total(), 1)
        var c = min(n, 4)
        while (c > 1 && c * cw + 2 * pad > maxW) c--
        colsN = c
        rowsN = ceil(n / c.toFloat()).toInt()
        val visRows = min(rowsN, 4)
        wantW = min(max((c * cw + 2 * pad).toInt(), (132 * d).toInt()), maxW)
        wantH = min((headerH + visRows * chh + pad).toInt(), maxH)
        gridL = (wantW - c * cw) / 2f
        layoutLabels()
    }

    /** Cells in the grid: every shortcut plus, in edit mode, one "+" cell. */
    private fun total() = cells.size + if (editMode) 1 else 0

    private fun contentH() = rowsN * chh + pad
    private fun maxScroll() = max(0f, contentH() - (height - headerH))
    private fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll()) }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < headerH) return -1
        val xx = x - gridL
        if (xx < 0f || xx >= colsN * cw) return -1
        val yy = y - headerH + scroll
        if (yy < 0f) return -1
        val col = (xx / cw).toInt()
        val row = (yy / chh).toInt()
        val i = row * colsN + col
        return if (col in 0 until colsN && i in 0 until total()) i else -1
    }

    private fun layoutLabels() {
        tp.textSize = mgr.popupSettings.lbl * d
        val w = cw - 4 * d
        cells.forEach { it.text = TextUtils.ellipsize(it.label, tp, w, TextUtils.TruncateAt.END) }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { clampScroll() }

    // ───── lifecycle ─────
    private fun buildCells(it: Item) {
        cells = it.memberItems.mapNotNull { m ->
            if (m.t == "app") Apps.labelOf(context, m.v)?.let { l -> Cell(m.n.ifBlank { l }, m) }       // uninstalled apps are skipped
            else Cell(m.n.ifBlank { TYPES[m.t]?.label ?: m.t }, m)
        }
    }

    /** The group was changed (edited, sorted ...) while its panel is open: rebuild the cells, keep the scroll position. */
    fun refresh(it: Item) {
        title = it.n
        buildCells(it)
        dragIdx = -1; overIdx = -1
        clampScroll(); invalidate()
    }

    fun open(it: Item, gesture: Boolean, px: Float, py: Float) {
        itemId = it.id
        title = it.n
        buildCells(it)
        dragIdx = -1; overIdx = -1
        scroll = 0f; hover = -1; autoDir = 0
        // A panel that opens under the finger must not react until the finger actually moves.
        armed = !gesture; openX = px; openY = py; lastX = -1f; lastY = -1f
        invalidate()
    }

    fun close() { removeCallbacks(auto); removeCallbacks(longPress); autoDir = 0; cells = emptyList(); itemId = ""; dragIdx = -1; overIdx = -1; tMode = 0 }

    override fun onDetachedFromWindow() { removeCallbacks(auto); removeCallbacks(longPress); super.onDetachedFromWindow() }

    fun clearHover() { setAuto(0); hover = -1; invalidate() }

    /** The finger came up on the panel: from now on it behaves as a touch panel. */
    fun enterTouch() { touchMode = true }

    // ───── hover mode ─────
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom

    /** Pointer in LayerView coordinates; may be outside the panel (then nothing is hovered). */
    fun pointer(rx: Float, ry: Float) {
        if (!armed) {
            if (hypot(rx - openX, ry - openY) < 14 * d) return
            armed = true
        }
        val x = rx - left; val y = ry - top
        lastX = x; lastY = y
        val inside = x >= 0f && x <= width && y >= 0f && y <= height
        setHover(if (inside && y >= headerH) cellAt(x, y) else -1)
        val edge = 22 * d
        val dir = when {
            !inside -> 0
            y > headerH && y < headerH + edge && scroll > 1f -> -1
            y > height - edge && scroll < maxScroll() - 1f -> 1
            else -> 0
        }
        setAuto(dir)
        invalidate()
    }

    /** True while the finger is down over one of the panel's apps. */
    fun hoveringApp() = armed && hover >= 0 && hover < cells.size

    private fun setAuto(dir: Int) {
        if (dir == autoDir) return
        autoDir = dir
        if (dir != 0) postOnAnimation(auto)
        invalidate()
    }

    private fun setHover(cell: Int) {
        if (cell == hover) return
        hover = cell
        if (cell >= 0) mgr.haptic(true)
    }

    /** Finger lifted. True when it was over the panel (the caller keeps the panel open in touch mode). */
    fun release(): Boolean {
        setAuto(0)
        if (!armed) return true
        val inside = lastX >= 0f && lastX <= width && lastY >= 0f && lastY <= height
        if (!inside) return false
        if (hover >= 0) cells.getOrNull(hover)?.let { onLaunch(it.item) }
        return true
    }

    // ───── touch mode ─────
    /** Index of the member whose delete badge is at (x, y), edit mode only. */
    private fun badgeAt(x: Float, y: Float): Int {
        if (!editMode || y < headerH) return -1
        var best = -1; var bd = badgeR() + 8 * d
        for (i in cells.indices) {
            val top = headerH + (i / colsN) * chh - scroll
            val bx = gridL + (i % colsN) * cw + cw / 2 + ip / 2 - 3 * d
            val dd = hypot(x - bx, y - (top + 3 * d))
            if (dd <= bd) { bd = dd; best = i }
        }
        return best
    }

    private fun badgeR() = (ip * 0.22f).coerceIn(9 * d, 13 * d)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastTY = e.y; tMode = 1
                parent?.requestDisallowInterceptTouchEvent(true)
                pressBadge = badgeAt(e.x, e.y)
                val i = if (e.y >= headerH) cellAt(e.x, e.y) else -1
                pressIdx = if (i in cells.indices && pressBadge < 0) i else -1
                hover = if (editMode) -1 else i
                if (pressIdx >= 0) postDelayed(longPress, 320)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tMode == 1 && hypot(e.x - downX, e.y - downY) > slop) { removeCallbacks(longPress); tMode = 2; hover = -1 }
                if (tMode == 2) { scroll -= (e.y - lastTY) * 1.6f; clampScroll(); invalidate() }
                if (tMode == 3) {
                    dragX = e.x; dragY = e.y
                    val i = cellAt(e.x, e.y)
                    overIdx = if (i in cells.indices) i else -1
                    if (e.y < headerH + 22 * d) scroll -= 10 * d else if (e.y > height - 22 * d) scroll += 10 * d
                    clampScroll(); invalidate()
                }
                lastTY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val mode = tMode
                tMode = 0; hover = -1
                when (mode) {
                    1 -> if (pressBadge >= 0) cells.getOrNull(pressBadge)?.let { onDeleteMember(it.item) } else tap(e.x, e.y)
                    3 -> {
                        val from = cells.getOrNull(dragIdx); val to = cells.getOrNull(overIdx)
                        if (from != null && to != null && from !== to) onReorder(from.item.id, to.item.id)
                    }
                }
                dragIdx = -1; overIdx = -1; pressBadge = -1; pressIdx = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                tMode = 0; hover = -1; dragIdx = -1; overIdx = -1; pressBadge = -1; pressIdx = -1; invalidate()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (y < headerH) {
            if (editMode && x < pad + 24 * d) { onDeleteGroup(); return }
            if (editMode && x > width - pad - 24 * d) { onEditGroup(); return }
            onBack(); return
        }
        val i = cellAt(x, y)
        if (editMode) {
            if (i == cells.size) onAddMember()
            else if (i in cells.indices) onEditMember(cells[i].item)
            return
        }
        if (i in cells.indices) { mgr.haptic(true); onLaunch(cells[i].item) }
    }

    // ───── drawing ─────
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = mgr.popupSettings
        val rad = min(mgr.state.settings.popRad * d, min(w, h) / 2f)     // the user's own corner radius (immersive mode zeroes it for the full-screen card only)
        rf.set(0f, 0f, w, h)
        bg.color = Color.argb((s.groupGlass / 100f * 255).toInt().coerceIn(0, 255), 0, 0, 0)   // "App group opacity" setting
        c.drawRoundRect(rf, rad, rad, bg)
        c.save()
        clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)
        val solid = Fa.typeface(context, false)

        c.save()
        c.clipRect(0f, headerH, w, h)
        if (cells.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = 11f * d; tp.color = 0x8CC8C8C8.toInt()
            c.drawText("No installed apps", w / 2, headerH + 30 * d, tp)
        } else drawGrid(c, h, s)
        c.restore()

        if (scroll > 1f) drawArrow(c, "\uf077", w / 2, headerH + 8 * d, autoDir == -1, solid)
        if (scroll < maxScroll() - 1f) drawArrow(c, "\uf078", w / 2, h - 8 * d, autoDir == 1, solid)
        drawHeader(c, w)
        c.restore()
    }

    private fun drawGrid(c: Canvas, h: Float, s: Settings) {
        val rad = ip * s.effRadius / 100f
        tp.textAlign = Paint.Align.CENTER; tp.textSize = s.lbl * d
        for (i in 0 until total()) {
            if (i == hover && !editMode) continue
            val top = headerH + (i / colsN) * chh - scroll
            if (top > h || top + chh < headerH) continue
            val cx = gridL + (i % colsN) * cw + cw / 2
            if (i == cells.size) { drawAddCell(c, cx, top, rad); continue }
            if (i == dragIdx) {
                c.saveLayerAlpha(cx - cw / 2, top, cx + cw / 2, top + chh, 70)
                drawCell(c, cells[i], cx, top, rad, false, s.showLbl)
                c.restore()
                continue
            }
            drawCell(c, cells[i], cx, top, rad, false, s.showLbl)
            if (editMode) drawBadges(c, cx, top)
        }
        if (dragIdx >= 0 && overIdx in cells.indices && overIdx != dragIdx) {        // drop target
            val l = gridL + (overIdx % colsN) * cw; val t = headerH + (overIdx / colsN) * chh - scroll
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = ACCENT
            rf.set(l + 2 * d, t, l + cw - 2 * d, t + chh - 2 * d); c.drawRoundRect(rf, 10 * d, 10 * d, p)
        }
        val hv = cells.getOrNull(hover)
        if (hv != null && !editMode) {
            val top = headerH + (hover / colsN) * chh - scroll
            drawCell(c, hv, gridL + (hover % colsN) * cw + cw / 2, top, rad, true, s.showLbl)
        }
        cells.getOrNull(dragIdx)?.let { drawCell(c, it, dragX, dragY - ip / 2, rad, true, s.showLbl) }   // the tile that follows the finger
    }

    private fun drawAddCell(c: Canvas, cx: Float, top: Float, rad: Float) {
        rf.set(cx - ip / 2, top, cx + ip / 2, top + ip)
        p.reset(); p.isAntiAlias = true; p.color = 0x12FFFFFF
        c.drawRoundRect(rf, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.pathEffect = dash; p.color = 0x40FFFFFF
        c.drawRoundRect(rf, rad, rad, p)
        gp.typeface = Fa.typeface(context, false); gp.textSize = 16 * d; gp.color = 0x99FFFFFF.toInt()
        c.drawText("\u002b", cx, top + ip / 2 - (gp.ascent() + gp.descent()) / 2, gp)
    }

    /** Edit mode badges on one inside shortcut: red delete (top right) and pencil (bottom right). */
    private fun drawBadges(c: Canvas, cx: Float, top: Float) {
        val br = badgeR(); val bx = cx + ip / 2 - 3 * d
        p.reset(); p.isAntiAlias = true; p.color = 0xFFE5534B.toInt()
        c.drawCircle(bx, top + 3 * d, br, p)
        gp.typeface = Fa.typeface(context, false); gp.color = Color.WHITE; gp.textSize = br * 1.1f
        c.drawText("\uf00d", bx, top + 3 * d - (gp.ascent() + gp.descent()) / 2, gp)
        val pr = br * 0.85f; val py = top + ip - 3 * d
        p.color = 0xFF3A3842.toInt()
        c.drawCircle(bx, py, pr, p)
        gp.textSize = pr
        c.drawText("\uf304", bx, py - (gp.ascent() + gp.descent()) / 2, gp)
    }

    private fun drawCell(c: Canvas, cell: Cell, cx: Float, top: Float, rad: Float, hov: Boolean, showLbl: Boolean) {
        c.save()
        if (hov) c.scale(1.2f, 1.2f, cx, top + ip / 2)
        rf.set(cx - ip / 2, top, cx + ip / 2, top + ip)
        val m = cell.item
        val bmp = if (m.hasBitmapIcon) Icons.peek(m, context, inv) else null
        p.reset(); p.isAntiAlias = true
        if (bmp != null) {
            c.save(); clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)
            p.isFilterBitmap = true
            c.drawBitmap(bmp, null, rf, p)
            c.restore()
        } else {
            p.color = if (hov) HOVER_TILE else TILE
            c.drawRoundRect(rf, rad, rad, p)
            if (hov) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = HOVER_EDGE
                c.drawRoundRect(rf, rad, rad, p)
                p.style = Paint.Style.FILL
            }
            if (!m.hasBitmapIcon || m.wantsFileThumb) drawGlyph(c, m, rf.centerX(), rf.centerY(), ip)   // call / web / system ... members
        }
        c.restore()
        if (showLbl) {
            tp.color = if (hov) Color.WHITE else 0xE6F6EFE4.toInt()
            val t = cell.text
            c.drawText(t, 0, t.length, cx, top + ip + 5 * d - tp.ascent(), tp)
        }
    }

    private fun drawGlyph(c: Canvas, m: Item, cx: Float, cy: Float, size: Float) {
        gp.color = Color.WHITE
        val g = if (m.i == "letter") null else Fa.glyph(m.i)
        if (g == null) {
            gp.typeface = MONO_BOLD; gp.textSize = size * .46f
            val ch = (m.n.firstOrNull() ?: '?').uppercaseChar().toString()
            c.drawText(ch, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
        } else {
            gp.typeface = Fa.typeface(context, g.brands); gp.textSize = size * .44f
            c.drawText(g.text, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
        }
    }

    private fun drawArrow(c: Canvas, glyph: String, cx: Float, cy: Float, active: Boolean, solid: Typeface) {
        gp.typeface = solid; gp.textSize = 11 * d
        gp.color = if (active) ACCENT else Color.WHITE
        c.drawText(glyph, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
    }

    /** Folder title, centred like Nova's. No divider, no buttons. */
    private fun drawHeader(c: Canvas, w: Float) {
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 12.5f * d; tp.color = 0xFFECECEC.toInt(); tp.isFakeBoldText = true
        val t1 = TextUtils.ellipsize(title, tp, w - 2 * pad - (if (editMode) 48 * d else 0f), TextUtils.TruncateAt.END)
        c.drawText(t1, 0, t1.length, w / 2, headerH / 2 - (tp.ascent() + tp.descent()) / 2 + 2 * d, tp)
        tp.isFakeBoldText = false
        if (editMode) {      // edit the group itself / delete it (both ask first); not on the group's tile in the popup
            gp.typeface = Fa.typeface(context, false); gp.textSize = 12 * d
            gp.color = 0xFFFF7B72.toInt(); c.drawText("\uf2ed", pad + 10 * d, headerH / 2 - (gp.ascent() + gp.descent()) / 2, gp)
            gp.color = 0xFFECECEC.toInt(); c.drawText("\uf304", w - pad - 10 * d, headerH / 2 - (gp.ascent() + gp.descent()) / 2, gp)
        }
    }
}
