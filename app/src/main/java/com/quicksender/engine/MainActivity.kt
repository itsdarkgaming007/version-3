package com.quicksender.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val BASE_URL = "http://127.0.0.1:3000"
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvPairingCode: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvEngineStatus)
        tvPairingCode = findViewById(R.id.tvPairingCodeDisplay)
        val etPhone = findViewById<EditText>(R.id.etPhoneNumber)
        val btnGetPairing = findViewById<Button>(R.id.btnGetPairingCode)

        val etTarget = findViewById<EditText>(R.id.etTargetNumber)
        val etMessage = findViewById<EditText>(R.id.etMessageBody)
        val btnSend = findViewById<Button>(R.id.btnSendMessage)

        startEngineService()
        promptBatteryOptimization()
        startStatusPolling()

        btnGetPairing.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.length < 10) {
                Toast.makeText(this, "Enter a valid phone number with country code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvPairingCode.text = "Generating code..."
            requestPairing(phone)
        }

        btnSend.setOnClickListener {
            val target = etTarget.text.toString().trim()
            val message = etMessage.text.toString().trim()
            if (target.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Recipient and message cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBackgroundMessage(target, message)
        }
    }

    private fun startEngineService() {
        val intent = Intent(this, LocalEngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun promptBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun startStatusPolling() {
        val runnable = object : Runnable {
            override fun run() {
                checkConnection()
                mainHandler.postDelayed(this, 3000)
            }
        }
        mainHandler.post(runnable)
    }

    private fun checkConnection() {
        Thread {
            try {
                val req = Request.Builder().url("$BASE_URL/status").get().build()
                httpClient.newCall(req).execute().use { res ->
                    val body = res.body?.string() ?: ""
                    val isConn = JSONObject(body).optBoolean("connected")
                    mainHandler.post {
                        if (isConn) {
                            tvStatus.text = "Status: WhatsApp Connected & Ready"
                            tvStatus.setTextColor(0xFF00A884.toInt())
                        } else {
                            tvStatus.text = "Status: Engine Active (Waiting for Link)"
                            tvStatus.setTextColor(0xFFE9EDEF.toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvStatus.text = "Status: Waiting for Engine..."
                    tvStatus.setTextColor(0xFF8696A0.toInt())
                }
            }
        }.start()
    }

    private fun requestPairing(phone: String) {
        Thread {
            try {
                val payload = JSONObject().apply { put("phone", phone) }
                val body = payload.toString().toRequestBody(JSON)
                val req = Request.Builder().url("$BASE_URL/pair").post(body).build()

                httpClient.newCall(req).execute().use { res ->
                    val resp = res.body?.string() ?: ""
                    val obj = JSONObject(resp)
                    val code = obj.optString("code")
                    mainHandler.post {
                        if (code.isNotEmpty()) {
                            tvPairingCode.text = "Code: $code"
                            Toast.makeText(this@MainActivity, "Enter code in WhatsApp -> Linked Devices", Toast.LENGTH_LONG).show()
                        } else {
                            tvPairingCode.text = "Failed to get pairing code"
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvPairingCode.text = "Error: ${e.message}"
                }
            }
        }.start()
    }

    private fun sendBackgroundMessage(target: String, message: String) {
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("phone", target)
                    put("message", message)
                }
                val body = payload.toString().toRequestBody(JSON)
                val req = Request.Builder().url("$BASE_URL/send").post(body).build()

                httpClient.newCall(req).execute().use { res ->
                    mainHandler.post {
                        if (res.isSuccessful) {
                            Toast.makeText(this@MainActivity, "Message sent silently!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed: Device not linked", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "Dispatch error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
