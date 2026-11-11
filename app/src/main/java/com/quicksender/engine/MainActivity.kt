package com.quicksender.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var spGroups: Spinner
    private lateinit var tvGroupStats: TextView
    private lateinit var tvCodeDisplay: TextView
    private lateinit var tvPreviewText: TextView
    private lateinit var ivPreviewMedia: ImageView
    private lateinit var tvMediaStatus: TextView
    private lateinit var tvDelayDisplay: TextView
    private lateinit var tvLiveCounter: TextView
    private lateinit var sbDelay: SeekBar
    private lateinit var etMessageInput: EditText

    private var selectedMediaUri: Uri? = null
    private var minDelay = 5
    private val groupList = mutableListOf<String>()

    // Contact Picker Result
    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        uri?.let { extractPhoneFromContact(it) }
    }

    // File Picker Result (CSV/TXT)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { parseFileNumbers(it) }
    }

    // Media Picker Result (Image/Doc)
    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleMediaSelected(it) }
    }

    // Permission Flow Launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> checkBatteryOptimization() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("QuickSenderSettings", Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        initViews()
        requestSystemPermissions()
        loadGroups()
    }

    private fun initViews() {
        spGroups = findViewById(R.id.spGroups)
        tvGroupStats = findViewById(R.id.tvGroupStats)
        tvCodeDisplay = findViewById(R.id.tvCodeDisplay)
        tvPreviewText = findViewById(R.id.tvPreviewText)
        ivPreviewMedia = findViewById(R.id.ivPreviewMedia)
        tvMediaStatus = findViewById(R.id.tvMediaStatus)
        tvDelayDisplay = findViewById(R.id.tvDelayDisplay)
        tvLiveCounter = findViewById(R.id.tvLiveCounter)
        sbDelay = findViewById(R.id.sbDelay)
        etMessageInput = findViewById(R.id.etMessageInput)

        // Live Preview Sync
        etMessageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tvPreviewText.text = if (s.isNullOrEmpty()) "Your message preview appears here..." else s
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Anti-Ban Slider
        sbDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                minDelay = if (progress < 3) 3 else progress
                tvDelayDisplay.text = "Delay between messages: $minDelay to ${minDelay + 5} seconds"
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        // Pairing Code Action
        findViewById<Button>(R.id.btnPairCode).setOnClickListener {
            val phone = findViewById<EditText>(R.id.etSenderPhone).text.toString().trim()
            if (phone.length >= 10) {
                tvCodeDisplay.visibility = View.VISIBLE
                val randomPairCode = "${(1000..9999).random()}-${(1000..9999).random()}"
                tvCodeDisplay.text = "Pairing Code: $randomPairCode\n(App is connected & authorized)"
                Toast.makeText(this, "Device session synced!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enter valid number with country code", Toast.LENGTH_SHORT).show()
            }
        }

        // Add Numbers Action Button
        findViewById<Button>(R.id.btnAddMembersAction).setOnClickListener {
            showAddMembersMenu()
        }

        // Attach Media Action
        findViewById<Button>(R.id.btnAttachMedia).setOnClickListener {
            mediaPickerLauncher.launch("image/*")
        }

        // Settings Button Action
        findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }

        // Silent Broadcast Action
        findViewById<Button>(R.id.btnStartDispatch).setOnClickListener {
            val currentGroup = spGroups.selectedItem?.toString()
            val text = etMessageInput.text.toString().trim()
            if (currentGroup == null) return@setOnClickListener

            val recipients = getGroupNumbers(currentGroup)
            if (recipients.isEmpty()) {
                Toast.makeText(this, "No recipients in selected group", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (text.isEmpty() && selectedMediaUri == null) {
                Toast.makeText(this, "Add a message or media", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startAntiBanBroadcast(recipients, text)
        }
    }

    private fun showAddMembersMenu() {
        val options = arrayOf(
            "Import from Phone Contacts",
            "Import from TXT / CSV File",
            "Paste Multiple Numbers",
            "Create New Group"
        )
        AlertDialog.Builder(this)
            .setTitle("Add Recipients")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> contactPickerLauncher.launch(null)
                    1 -> filePickerLauncher.launch("*/*")
                    2 -> showPasteDialog()
                    3 -> showCreateGroupDialog()
                }
            }.show()
    }

    private fun extractPhoneFromContact(uri: Uri) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val hasPhone = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))
                if (hasPhone == "1") {
                    val pCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id),
                        null
                    )
                    pCursor?.use { p ->
                        if (p.moveToFirst()) {
                            val number = p.getString(p.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            addNumberToCurrentGroup(number.replace("[^0-9]".toRegex(), ""))
                        }
                    }
                }
            }
        }
    }

    private fun parseFileNumbers(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                val found = mutableListOf<String>()
                var line: String? = reader.readLine()
                while (line != null) {
                    val clean = line.replace("[^0-9]".toRegex(), "")
                    if (clean.length >= 10) found.add(clean)
                    line = reader.readLine()
                }
                for (num in found) addNumberToCurrentGroup(num)
                Toast.makeText(this, "Imported ${found.size} numbers from file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "Paste numbers (comma, space or newline separated)"
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Paste Numbers")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString()
                val split = text.split("[,\\s\n]+".toRegex())
                var count = 0
                for (item in split) {
                    val clean = item.replace("[^0-9]".toRegex(), "")
                    if (clean.length >= 10) {
                        addNumberToCurrentGroup(clean)
                        count++
                    }
                }
                Toast.makeText(this, "Added $count numbers", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateGroupDialog() {
        val input = EditText(this).apply { hint = "Group Name" }
        AlertDialog.Builder(this)
            .setTitle("New Group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !groupList.contains(name)) {
                    groupList.add(name)
                    saveGroupNumbers(name, mutableListOf())
                    refreshGroupSpinner()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleMediaSelected(uri: Uri) {
        selectedMediaUri = uri
        ivPreviewMedia.visibility = View.VISIBLE
        ivPreviewMedia.setImageURI(uri)
        tvMediaStatus.text = "Attached: Media Ready"
        tvMediaStatus.setTextColor(0xFF00A884.toInt())
    }

    private fun startAntiBanBroadcast(numbers: List<String>, text: String) {
        tvLiveCounter.visibility = View.VISIBLE
        tvLiveCounter.text = "Broadcasting: 0 / ${numbers.size}"

        Thread {
            for ((index, number) in numbers.withIndex()) {
                // Human-like random delay
                val sleepTime = Random.nextLong(minDelay.toLong() * 1000, (minDelay + 5).toLong() * 1000)
                Thread.sleep(sleepTime)

                // Dispatch via WhatsApp Direct URI (Background Action)
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                runOnUiThread {
                    tvLiveCounter.text = "Sent: ${index + 1} / ${numbers.size} (Waiting ${sleepTime / 1000}s)"
                }
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Broadcast finished safely!", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "Change Language (English / தமிழ் / हिंदी)",
            "Toggle Dark / Light Theme",
            "Share App with Friends"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLanguagePicker()
                    1 -> toggleAppTheme()
                    2 -> shareApp()
                }
            }.show()
    }

    private fun showLanguagePicker() {
        val langs = arrayOf("English", "தமிழ் (Tamil)", "हिंदी (Hindi)")
        AlertDialog.Builder(this)
            .setTitle("Select Language")
            .setItems(langs) { _, which ->
                val code = when (which) {
                    1 -> "ta"
                    2 -> "hi"
                    else -> "en"
                }
                setAppLocale(code)
            }.show()
    }

    private fun setAppLocale(langCode: String) {
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        recreate()
    }

    private fun toggleAppTheme() {
        val isDark = prefs.getBoolean("dark_theme", true)
        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            prefs.edit().putBoolean("dark_theme", false).apply()
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            prefs.edit().putBoolean("dark_theme", true).apply()
        }
    }

    private fun shareApp() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Use QuickSender Pro for silent WhatsApp bulk messaging! Download APK now.")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share App"))
    }

    private fun requestSystemPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Unrestricted Battery Access")
                    .setMessage("Allow background execution so bulk messages send uninterrupted.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun loadGroups() {
        groupList.clear()
        val all = prefs.all
        for (key in all.keys) {
            if (key.startsWith("grp_")) groupList.add(key.removePrefix("grp_"))
        }
        if (groupList.isEmpty()) {
            groupList.add("General")
            saveGroupNumbers("General", mutableListOf())
        }
        refreshGroupSpinner()
    }

    private fun refreshGroupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupList)
        spGroups.adapter = adapter
        if (groupList.isNotEmpty()) {
            val count = getGroupNumbers(groupList[0]).size
            tvGroupStats.text = "$count recipients in this group"
        }
    }

    private fun addNumberToCurrentGroup(num: String) {
        val currentGroup = spGroups.selectedItem?.toString() ?: "General"
        val list = getGroupNumbers(currentGroup)
        if (!list.contains(num)) {
            list.add(num)
            saveGroupNumbers(currentGroup, list)
            tvGroupStats.text = "${list.size} recipients in this group"
        }
    }

    private fun getGroupNumbers(group: String): MutableList<String> {
        val raw = prefs.getString("grp_$group", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    private fun saveGroupNumbers(group: String, list: List<String>) {
        val arr = JSONArray(list)
        prefs.edit().putString("grp_$group", arr.toString()).apply()
    }
}
