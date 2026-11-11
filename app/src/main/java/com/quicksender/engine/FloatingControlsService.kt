package com.quicksender.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FloatingControlsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        inflateCapsule()
        observeProgress()
    }

    private fun startForegroundNotification() {
        val channelId = "floating_capsule"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Floating Controls", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("QuickSender Active")
            .setContentText("Broadcasting with floating controls")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForeground(3001, notification)
    }

    private fun inflateCapsule() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_control_pill, null)

        val btnPause = floatingView?.findViewById<ImageButton>(R.id.btnPauseResume)
        val btnCancel = floatingView?.findViewById<ImageButton>(R.id.btnCancelCampaign)
        val root = floatingView?.findViewById<View>(R.id.rootPillLayout)

        btnPause?.setOnClickListener {
            if (BroadcastEngineCoordinator.isPaused.get()) {
                BroadcastEngineCoordinator.resume()
                btnPause.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                BroadcastEngineCoordinator.pause()
                btnPause.setImageResource(android.R.drawable.ic_media_play)
            }
        }

        btnCancel?.setOnClickListener {
            BroadcastEngineCoordinator.cancel()
            stopSelf()
        }

        root?.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (event.rawX - touchX).toInt()
                        params.y = initY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun observeProgress() {
        val tvProgress = floatingView?.findViewById<TextView>(R.id.tvLiveProgressText)
        val pb = floatingView?.findViewById<ProgressBar>(R.id.pbMicroProgress)

        scope.launch {
            BroadcastEngineCoordinator.progressFlow.collect { (current, total) ->
                if (total > 0) {
                    tvProgress?.text = "$current / $total"
                    val pct = ((current.toFloat() / total) * 100).toInt()
                    pb?.progress = pct
                    if (current == total) {
                        delay(1200)
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        floatingView?.let { windowManager.removeView(it) }
    }
}
