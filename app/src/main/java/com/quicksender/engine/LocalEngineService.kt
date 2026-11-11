package com.quicksender.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class LocalEngineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var engineProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startServiceNotification()
        acquireWakeLock()
        launchLocalNode()
    }

    private fun startServiceNotification() {
        val channelId = "engine_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WhatsApp Engine Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("QuickSender Engine Active")
            .setContentText("Local background dispatch service is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickSender:EngineLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun launchLocalNode() {
        Thread {
            try {
                val projectDir = File(filesDir, "nodejs-project")
                if (!projectDir.exists()) projectDir.mkdirs()

                copyAssetFolder("nodejs-project", projectDir)

                val mainScript = File(projectDir, "main.js")
                val builder = ProcessBuilder("node", mainScript.absolutePath)
                builder.directory(projectDir)
                builder.environment()["NODE_FILES_DIR"] = filesDir.absolutePath
                engineProcess = builder.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun copyAssetFolder(srcName: String, dstDir: File) {
        val assetList = assets.list(srcName) ?: return
        if (assetList.isEmpty()) {
            copyAssetFile(srcName, dstDir)
        } else {
            val subDir = File(dstDir, File(srcName).name)
            subDir.mkdirs()
            for (item in assetList) {
                copyAssetFolder("$srcName/$item", subDir)
            }
        }
    }

    private fun copyAssetFile(srcPath: String, dstDir: File) {
        val outFile = File(dstDir, File(srcPath).name)
        assets.open(srcPath).use { inStream ->
            FileOutputStream(outFile).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engineProcess?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
