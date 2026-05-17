package com.androce.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.app.NotificationCompat
import com.androce.MainActivity
import com.androce.R
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingIconService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDragging = false
    private var isVisible = true
    private var progressRing: ProgressBar? = null
    private var progressUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                removeFloatingIcon()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> {
                hideFloatingIcon()
                return START_STICKY
            }
            ACTION_SHOW -> {
                showFloatingIcon()
                return START_STICKY
            }
            else -> {
                if (floatingView != null) {
                    // Already running
                    return START_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                showFloatingIcon()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressUpdateJob?.cancel()
        removeFloatingIcon()
        super.onDestroy()
    }

    private fun startProgressUpdate() {
        progressUpdateJob?.cancel()
        progressUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                try {
                    val isScanning = AppPrefs.isScanning
                    val progress = AppPrefs.scanProgress

                    progressRing?.let { ring ->
                        if (isScanning) {
                            ring.visibility = View.VISIBLE
                            ring.progress = progress
                        } else {
                            ring.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
                delay(500) // Update every 500ms
            }
        }
    }

    private fun createFloatingIconView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, ICON_SIZE_DP, resources.displayMetrics
        ).roundToInt()

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            val savedX = AppPrefs.floatingIconX
            val savedY = AppPrefs.floatingIconY
            if (savedX >= 0 && savedY >= 0) {
                x = savedX
                y = savedY
            } else {
                // Default: top-right corner with some padding
                val marginPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
                ).roundToInt()
                x = resources.displayMetrics.widthPixels - sizePx - marginPx
                y = marginPx + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager?.currentWindowMetrics?.bounds?.top ?: 0
                } else {
                    @Suppress("DEPRECATION")
                    resources.displayMetrics.heightPixels - resources.displayMetrics.heightPixels // 0
                }
            }
        }

        val iconView = ImageView(this).apply {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFA67FFF.toInt()) // Primary color
            }
            background = drawable
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).roundToInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).roundToInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).roundToInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).roundToInt()
            )
            alpha = 0.85f
        }

        // Progress ring indicator (circular)
        progressRing = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        // Wrap in FrameLayout to layer progress ring behind icon
        floatingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            addView(progressRing!!)
            addView(iconView)
        }

        // Start periodic progress update
        startProgressUpdate()

        iconView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).roundToInt()
                    val dy = (event.rawY - touchDownY).roundToInt()
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params!!.x = initialX + dx
                        params!!.y = initialY + dy
                        windowManager!!.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        openApp()
                    } else {
                        // Save position
                        AppPrefs.floatingIconX = params!!.x
                        AppPrefs.floatingIconY = params!!.y
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = iconView
        windowManager!!.addView(floatingView, params)
    }

    private fun removeFloatingIcon() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        floatingView = null
        windowManager = null
    }

    private fun hideFloatingIcon() {
        if (!isVisible) return
        floatingView?.let {
            try {
                windowManager?.removeView(it)
                isVisible = false
            } catch (_: Exception) {}
        }
    }

    private fun showFloatingIcon() {
        if (isVisible && floatingView != null) return
        if (floatingView == null) {
            createFloatingIconView()
        }
        floatingView?.let {
            try {
                windowManager?.addView(it, params)
                isVisible = true
            } catch (_: Exception) {}
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.freeze_notification_title))
            .setContentText("Floating shortcut active")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.freeze_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "androCE floating icon background service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "androce_freeze"
        const val NOTIFICATION_ID = 1002
        const val ICON_SIZE_DP = 56f
        const val DRAG_THRESHOLD_PX = 15
        const val ACTION_HIDE = "com.androce.FloatingIconService.HIDE"
        const val ACTION_SHOW = "com.androce.FloatingIconService.SHOW"
        const val ACTION_STOP = "com.androce.FloatingIconService.STOP"
    }
}
