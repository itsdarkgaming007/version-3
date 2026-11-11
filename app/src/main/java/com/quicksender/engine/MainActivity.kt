package com.quicksender.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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

    private lateinit var prefs: SharedPreferences
    private lateinit var tvEngineBadge: TextView
    private lateinit var tvPairingResult: TextView
    private lateinit var spGroups: Spinner
    private lateinit var tvGroupCount: TextView
    private lateinit var tvProgress: TextView

    private val groupList = mutableListOf<String>()
    private var isConnected = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkBatteryPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("QuickSenderGroups", Context.MODE_PRIVATE)

        initViews()
        requestAppPermissions()
        startEngineService()
        loadGroups()
        startStatusPolling()
    }

    private fun initViews() {
        tvEngineBadge = findViewById(R.id.tvEngineBadge)
        tvPairingResult = findViewById(R.id.tvPairingCodeResult)
        spGroups = findViewById(R.id.spGroups)
        tvGroupCount = findViewById(R.id.tvGroupMembersCount)
        tvProgress = findViewById(R.id.tvBroadcastProgress)

        val etSenderPhone = findViewById<EditText>(R.id.etSenderPhone)
        val btnGenerateCode = findViewById<Button>(R.id.btnGenerateCode)
        val etGroupName = findViewById<EditText>(R.id.etGroupName)
        val btnCreateGroup = findViewById<Button>(R.id.btnCreateGroup)
        val etContactNumber = findViewById<EditText>(R.id.etContactNumber)
        val btnAddNumber = findViewById<Button>(R.id.btnAddNumber)
        val etMessage = findViewById<EditText>(R.id.etBroadcastMessage)
        val btnStartBulk = findViewById<Button>(R.id.btnStartBulkSend)

        btnGenerateCode.setOnClickListener {
            val phone = etSenderPhone.text.toString().trim()
            if (phone.length < 10) {
                Toast.makeText(this, "Enter valid phone number with country code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvPairingResult.visibility = View.VISIBLE
            tvPairingResult.text = "Generating pairing code..."
            requestPairing(phone)
        }

        btnCreateGroup.setOnClickListener {
            val name = etGroupName.text.toString().trim()
            if (name.isNotEmpty() && !groupList.contains(name)) {
                groupList.add(name)
                saveGroupNumbers(name, mutableListOf())
                refreshGroupSpinner()
                etGroupName.text.clear()
                Toast.makeText(this, "Group created!", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddNumber.setOnClickListener {
            val selected = spGroups.selectedItem?.toString()
            val number = etContactNumber.text.toString().trim()
            if (selected != null && number.length >= 10) {
                val list = getGroupNumbers(selected)
                if (!list.contains(number)) {
                    list.add(number)
                    saveGroupNumbers(selected, list)
                    updateMemberCount(selected)
                    etContactNumber.text.clear()
                    Toast.makeText(this, "Number added to $selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        spGroups.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                updateMemberCount(groupList[pos])
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnStartBulk.setOnClickListener {
            val selected = spGroups.selectedItem?.toString()
            val message = etMessage.text.toString().trim()
            if (selected == null) {
                Toast.makeText(this, "Create a group first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val numbers = getGroupNumbers(selected)
            if (numbers.isEmpty()) {
                Toast.makeText(this, "Selected group has 0 numbers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (message.isEmpty()) {
                Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startBroadcast(numbers, message)
        }
    }

    private fun requestAppPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            checkBatteryPermission()
        }
    }

    private fun checkBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Allow Background Execution")
                    .setMessage("Enable unrestricted battery so QuickSender can dispatch messages silently without stopping.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
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

    private fun startStatusPolling() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                checkEngine()
                mainHandler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    private fun checkEngine() {
        Thread {
            try {
                val req = Request.Builder().url("$BASE_URL/status").get().build()
                httpClient.newCall(req).execute().use { res ->
                    val body = res.body?.string() ?: ""
                    val obj = JSONObject(body)
                    isConnected = obj.optBoolean("connected")
                    mainHandler.post {
                        if (isConnected) {
                            tvEngineBadge.text = "Connected & Ready"
                            tvEngineBadge.setTextColor(0xFF00A884.toInt())
                        } else {
                            tvEngineBadge.text = "Engine Online (Not Linked)"
                            tvEngineBadge.setTextColor(0xFF25D366.toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvEngineBadge.text = "Engine Offline"
                    tvEngineBadge.setTextColor(0xFFEF5350.toInt())
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
                    val code = JSONObject(resp).optString("code")
                    mainHandler.post {
                        if (code.isNotEmpty()) {
                            tvPairingResult.text = "Code: $code\n(Enter in WhatsApp -> Linked Devices)"
                        } else {
                            tvPairingResult.text = "Failed to fetch pairing code"
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvPairingResult.text = "Engine not reachable yet"
                }
            }
        }.start()
    }

    private fun startBroadcast(numbers: List<String>, message: String) {
        tvProgress.text = "Broadcasting: 0 / ${numbers.size}"
        Thread {
            var sent = 0
            for (num in numbers) {
                try {
                    val payload = JSONObject().apply {
                        put("phone", num)
                        put("message", message)
                    }
                    val body = payload.toString().toRequestBody(JSON)
                    val req = Request.Builder().url("$BASE_URL/send").post(body).build()
                    httpClient.newCall(req).execute().use { res ->
                        if (res.isSuccessful) sent++
                    }
                    mainHandler.post {
                        tvProgress.text = "Broadcasting: $sent / ${numbers.size}"
                    }
                    Thread.sleep(4000)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            mainHandler.post {
                Toast.makeText(this@MainActivity, "Broadcast completed!", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun loadGroups() {
        groupList.clear()
        val all = prefs.all
        for (key in all.keys) {
            groupList.add(key)
        }
        if (groupList.isEmpty()) {
            groupList.add("Default Group")
            saveGroupNumbers("Default Group", mutableListOf())
        }
        refreshGroupSpinner()
    }

    private fun refreshGroupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupList)
        spGroups.adapter = adapter
        if (groupList.isNotEmpty()) updateMemberCount(groupList[0])
    }

    private fun updateMemberCount(group: String) {
        val count = getGroupNumbers(group).size
        tvGroupCount.text = "Numbers in $group: $count"
    }

    private fun getGroupNumbers(group: String): MutableList<String> {
        val jsonStr = prefs.getString(group, "[]") ?: "[]"
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    private fun saveGroupNumbers(group: String, list: List<String>) {
        val arr = JSONArray(list)
        prefs.edit().putString(group, arr.toString()).apply()
    }
}
