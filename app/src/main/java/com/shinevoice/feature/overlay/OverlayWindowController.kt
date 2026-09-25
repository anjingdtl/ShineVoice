package com.shinevoice.feature.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds and manages the overlay window: a draggable circle button that snaps
 * to the nearest screen edge, and an expandable panel (recent clips, play/stop,
 * delay chips, close). Plain Android views — no focus stealing (FLAG_NOT_FOCUSABLE),
 * touch consumed only within the view bounds, and removeView is idempotent.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayWindowController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPlayRequested()
        fun onStopRequested()
        fun onClipPicked(item: OverlayClip)
        fun onDelaySelected(seconds: Int)
        /** "倒计时播放" armed from the panel; the service runs the countdown. */
        fun onCountdownPlayRequested()
        fun onCountdownTap()
        fun onCountdownStarted(seconds: Int)
        fun onPanelVisibilityChanged(visible: Boolean)
        fun onPositionChanged(xRatio: Float, yRatio: Float)
        fun onClosed()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var buttonView: FrameLayout? = null
    private var panelView: LinearLayout? = null
    private var buttonLabel: TextView? = null
    private var panelBody: LinearLayout? = null
    private var panelStatus: TextView? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var lastNonNegativeX = 0
    private var lastNonNegativeY = 0
    private var countdownSeconds = -1

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private fun windowType(): Int = if (android.os.Build.VERSION.SDK_INT >= 26) {
        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        android.view.WindowManager.LayoutParams.TYPE_PHONE
    }

    /** Adds the collapsed floating button at the persisted ratio position. */
    fun addButton(posXRatio: Float, posYRatio: Float) {
        if (buttonView != null) return
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = context.resources.displayMetrics
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
        val size = dp(BUTTON_SIZE_DP)
        val startX = (posXRatio * screenWidth - size / 2f).roundToInt()
            .coerceIn(0, (screenWidth - size).coerceAtLeast(0))
        val startY = (posYRatio * screenHeight - size / 2f).roundToInt()
            .coerceIn(0, (screenHeight - size).coerceAtLeast(0))

        val container = FrameLayout(context)
        val circle = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0A0E14"))
            textSize = 20f
            text = "▶"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6F7C400"))
                setStroke(dp(2), Color.parseColor("#CC00E5FF"))
            }
        }
        buttonLabel = circle
        container.addView(
            circle,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        container.layoutParams = FrameLayout.LayoutParams(size, size)

        val params = WindowManager.LayoutParams(
            size,
            size,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        attachDrag(container, params, size)
        try {
            windowManager.addView(container, params)
            buttonView = container
            lastNonNegativeX = params.x
            lastNonNegativeY = params.y
        } catch (t: Throwable) {
            // BadTokenException / SecurityException: permission revoked or
            // window not allowed — report, never crash the process.
            buttonView = null
            callbacks.onClosed()
        }
    }

    private fun attachDrag(view: ViewGroup, params: WindowManager.LayoutParams, size: Int) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = dp(8)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).roundToInt()
                        params.y = (startY + dy).roundToInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToEdge(params, size)
                        runCatching { windowManager.updateViewLayout(view, params) }
                        persistPosition(params, size)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onButtonTapped()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onButtonTapped() {
        if (countdownSeconds > 0) {
            callbacks.onCountdownTap()
        } else if (panelView != null) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, size: Int) {
        val centerX = params.x + size / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - size
        params.x = targetX.coerceIn(0, (screenWidth - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - size).coerceAtLeast(0))
    }

    private fun persistPosition(params: WindowManager.LayoutParams, size: Int) {
        val xRatio = (params.x + size / 2f) / screenWidth
        val yRatio = (params.y + size / 2f) / screenHeight
        callbacks.onPositionChanged(xRatio.coerceIn(0f, 1f), yRatio.coerceIn(0f, 1f))
    }

    /** Expands the control panel anchored beside the button. */
    fun showPanel() {
        if (panelView != null) return
        val button = buttonView ?: return
        val params = button.layoutParams as? WindowManager.LayoutParams ?: return

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#F00A0E14"))
                setStroke(dp(1), Color.parseColor("#9900E5FF"))
            }
        }
        panelBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        panelStatus = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#9900E5FF"))
        }
        panel.addView(panelStatus)
        panel.addView(panelBody)

        val width = dp(PANEL_WIDTH_DP)
        val height = WindowManager.LayoutParams.WRAP_CONTENT
        val panelParams = WindowManager.LayoutParams(
            width,
            height,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            y = (params.y + dp(BUTTON_SIZE_DP) + dp(8))
                .coerceIn(0, (screenHeight - dp(160)).coerceAtLeast(0))
        }
        // The panel body consumes touches inside its own bounds only.
        panel.setOnTouchListener { _, _ -> false }
        try {
            windowManager.addView(panel, panelParams)
            panelView = panel
            callbacks.onPanelVisibilityChanged(true)
        } catch (_: Throwable) {
            panelView = null
        }
    }

    fun hidePanel() {
        panelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelView = null
        panelBody = null
        panelStatus = null
        callbacks.onPanelVisibilityChanged(false)
    }

    /** Rebuilds the panel content from the given recent-clip descriptors. */
    fun renderPanelContent(items: List<OverlayClip>, playingId: String?, selectedId: String?, delaySeconds: Int) {
        val body = panelBody ?: return
        body.removeAllViews()
        val status = panelStatus ?: return
        status.text = when {
            playingId != null -> "正在播放（点按钮停止）"
            else -> "选择音频 → 倒计时 → 切到微信按住说话"
        }
        if (items.isEmpty()) {
            body.addView(label("暂无已生成的音频，请先在应用内生成。"))
        } else {
            items.take(RECENT_LIMIT).forEach { item ->
                val row = TextView(context).apply {
                    text = item.title
                    textSize = 13f
                    maxLines = 1
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setTextColor(
                        when (item.id) {
                            playingId -> Color.parseColor("#FFF7C400")
                            selectedId -> Color.parseColor("#FF00E5FF")
                            else -> Color.parseColor("#E6FFFFFF")
                        },
                    )
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(
                            if (item.id == selectedId) Color.parseColor("#2200E5FF") else Color.TRANSPARENT,
                        )
                    }
                    setOnClickListener { callbacks.onClipPicked(item) }
                }
                body.addView(row)
            }
        }
        // Delay chips
        val delayRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0, 2, 3, 5).forEach { seconds ->
            val chip = TextView(context).apply {
                text = if (seconds == 0) "立即" else "${seconds}s"
                textSize = 12f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setTextColor(if (seconds == delaySeconds) Color.parseColor("#FF0A0E14") else Color.parseColor("#FF00E5FF"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(
                        if (seconds == delaySeconds) Color.parseColor("#FF00E5FF") else Color.parseColor("#2200E5FF"),
                    )
                }
                setOnClickListener { callbacks.onDelaySelected(seconds) }
            }
            delayRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        body.addView(delayRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // Play now / countdown play / close
        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(actionButton("▶ 立即") { callbacks.onPlayRequested() })
        actionRow.addView(actionButton("⏱ 倒计时") { callbacks.onCountdownPlayRequested() })
        actionRow.addView(actionButton("✕ 关闭") { callbacks.onClosed() })
        body.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(Color.parseColor("#FF0A0E14"))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#F7C400"))
            }
            setOnClickListener { onClick() }
        }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#B3FFFFFF"))
        setPadding(0, dp(6), 0, dp(6))
    }

    /** Shows the remaining countdown on the button; -1 clears it. */
    fun showCountdown(secondsRemaining: Int) {
        countdownSeconds = secondsRemaining
        buttonLabel?.text = if (secondsRemaining > 0) secondsRemaining.toString() else "▶"
        buttonLabel?.setTextColor(
            if (secondsRemaining > 0) Color.parseColor("#FFFF3B3B") else Color.parseColor("#0A0E14"),
        )
    }

    fun setPlaying(playing: Boolean) {
        if (countdownSeconds <= 0) {
            buttonLabel?.text = if (playing) "■" else "▶"
        }
    }

    fun remove() {
        hidePanel()
        buttonView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        buttonView = null
        buttonLabel = null
        countdownSeconds = -1
    }

    data class OverlayClip(val id: String, val title: String, val filePath: String)

    companion object {
        const val BUTTON_SIZE_DP = 56
        const val PANEL_WIDTH_DP = 240
        const val RECENT_LIMIT = 5
    }
}
