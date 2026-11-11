package com.quicksender.engine

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var layoutEmpty: View
    private lateinit var layoutWorkspace: View
    private lateinit var spGroups: Spinner
    private lateinit var tvGroupStats: TextView
    private lateinit var etMessageInput: EditText
    private lateinit var tvPreviewText: TextView
    private lateinit var ivPreviewMedia: ImageView
    private lateinit var tvMediaStatus: TextView
    private lateinit var tvDelayDisplay: TextView
    private lateinit var sbDelay: SeekBar
    private lateinit var layoutPreviewBubble: View

    private val groupList = mutableListOf<String>()
    private var selectedMediaUri: Uri? = null
    private var currentGroupDelay = 6

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { importNumbersFromFile(it) } }

    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { attachMedia(it) } }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> checkFloatingWindowPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("QuickSenderData", Context.MODE_PRIVATE)

        // Apply saved theme
        val isDark = prefs.getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        setContentView(R.layout.activity_main)

        initViews()
        requestSystemPermissions()
        refreshGroups()
    }

    private fun initViews() {
        layoutEmpty = findViewById(R.id.layoutEmptyState)
        layoutWorkspace = findViewById(R.id.layoutWorkspace)
        spGroups = findViewById(R.id.spGroups)
        tvGroupStats = findViewById(R.id.tvGroupStats)
        etMessageInput = findViewById(R.id.etMessageInput)
        tvPreviewText = findViewById(R.id.tvPreviewText)
        ivPreviewMedia = findViewById(R.id.ivPreviewMedia)
        tvMediaStatus = findViewById(R.id.tvMediaStatus)
        tvDelayDisplay = findViewById(R.id.tvDelayDisplay)
        sbDelay = findViewById(R.id.sbDelay)
        layoutPreviewBubble = findViewById(R.id.layoutPreviewBubble)

        findViewById<TextView>(R.id.tvPreviewTime).text =
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        // Live Chat Bubble Sync
        etMessageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tvPreviewText.text = if (s.isNullOrEmpty()) "Hello, preview here..." else s
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Group-level Anti-Ban Delay Slider
        sbDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                currentGroupDelay = if (progress < 4) 4 else progress
                tvDelayDisplay.text = "Delay: $currentGroupDelay to ${currentGroupDelay + 5} seconds (Human Behavior)"
                val grp = spGroups.selectedItem?.toString()
                if (grp != null) prefs.edit().putInt("delay_$grp", currentGroupDelay).apply()
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        // Add Group Listeners
        findViewById<View>(R.id.fabCreateFirstGroup).setOnClickListener { showCreateGroupDialog() }
        findViewById<View>(R.id.fabAddGroupCorner).setOnClickListener { showCreateGroupDialog() }

        findViewById<Button>(R.id.btnAddNumbersModal).setOnClickListener { showAddNumbersMenu() }
        findViewById<TextView>(R.id.btnViewMembersList).setOnClickListener { showMembersManagerDialog() }
        findViewById<Button>(R.id.btnAttachMedia).setOnClickListener { mediaPickerLauncher.launch("image/*") }
        findViewById<ImageView>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        // Start Broadcast with Floating Capsule
        findViewById<Button>(R.id.btnStartDispatch).setOnClickListener {
            val grp = spGroups.selectedItem?.toString() ?: return@setOnClickListener
            val recipients = getGroupNumbers(grp)
            val msg = etMessageInput.text.toString().trim()

            if (recipients.isEmpty()) {
                Toast.makeText(this, "Add numbers to $grp first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (msg.isEmpty() && selectedMediaUri == null) {
                Toast.makeText(this, "Enter a message or attach media", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isAccessibilityServiceEnabled()) {
                showAccessibilityPrompt()
                return@setOnClickListener
            }

            launchBroadcast(recipients, msg)
        }

        spGroups.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                val grp = groupList[pos]
                updateGroupUI(grp)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun showCreateGroupDialog() {
        val input = EditText(this).apply { hint = "Group Name (e.g. Leads A)" }
        AlertDialog.Builder(this)
            .setTitle("Create New Group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !groupList.contains(name)) {
                    groupList.add(name)
                    saveGroupNumbers(name, mutableListOf())
                    prefs.edit().putInt("delay_$name", 6).apply()
                    refreshGroups()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddNumbersMenu() {
        val options = arrayOf(
            "Multi-Select Phone Contacts",
            "Direct Paste Numbers",
            "Import CSV / TXT File"
        )
        AlertDialog.Builder(this)
            .setTitle("Add Members (300 Max)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMultiContactPicker()
                    1 -> showPasteDialog()
                    2 -> filePickerLauncher.launch("*/*")
                }
            }.show()
    }

    private fun showMultiContactPicker() {
        val contacts = fetchPhonebookContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(this, "No contacts found on device", Toast.LENGTH_SHORT).show()
            return
        }

        val names = contacts.map { "${it.first} (${it.second})" }.toTypedArray()
        val checked = BooleanArray(names.size)
        val selected = mutableListOf<String>()

        AlertDialog.Builder(this)
            .setTitle("Select Contacts")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val num = contacts[which].second
                if (isChecked) selected.add(num) else selected.remove(num)
            }
            .setPositiveButton("Add Selected") { _, _ ->
                addBatchNumbers(selected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchPhonebookContacts(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Contact"
                val num = it.getString(numIdx)?.replace("[^0-9]".toRegex(), "") ?: ""
                if (num.length >= 10) list.add(Pair(name, num))
            }
        }
        return list
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "Paste numbers separated by commas, spaces, or lines"
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Paste Numbers")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString()
                val split = text.split("[,\\s\n]+".toRegex())
                val list = split.map { it.replace("[^0-9]".toRegex(), "") }.filter { it.length >= 10 }
                addBatchNumbers(list)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importNumbersFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                val found = mutableListOf<String>()
                var line = reader.readLine()
                while (line != null) {
                    val clean = line.replace("[^0-9]".toRegex(), "")
                    if (clean.length >= 10) found.add(clean)
                    line = reader.readLine()
                }
                addBatchNumbers(found)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addBatchNumbers(numbers: List<String>) {
        val grp = spGroups.selectedItem?.toString() ?: return
        val current = getGroupNumbers(grp)
        var added = 0

        for (num in numbers) {
            val formatted = if (num.length == 10) "91$num" else num
            if (current.size < 300 && !current.contains(formatted)) {
                current.add(formatted)
                added++
            }
        }

        saveGroupNumbers(grp, current)
        updateGroupUI(grp)
        Toast.makeText(this, "Added $added numbers ($current.size / 300)", Toast.LENGTH_SHORT).show()
    }

    private fun showMembersManagerDialog() {
        val grp = spGroups.selectedItem?.toString() ?: return
        val members = getGroupNumbers(grp)

        if (members.isEmpty()) {
            Toast.makeText(this, "No members in $grp", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, members)
        val list = ListView(this).apply { this.adapter = adapter }

        val dialog = AlertDialog.Builder(this)
            .setTitle("$grp Members (${members.size} / 300)")
            .setView(list)
            .setPositiveButton("Done", null)
            .create()

        list.setOnItemClickListener { _, _, pos, _ ->
            val num = members[pos]
            AlertDialog.Builder(this)
                .setTitle("Remove Member")
                .setMessage("Remove $num from $grp?")
                .setPositiveButton("Remove") { _, _ ->
                    members.removeAt(pos)
                    saveGroupNumbers(grp, members)
                    adapter.notifyDataSetChanged()
                    updateGroupUI(grp)
                    if (members.isEmpty()) dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun updateGroupUI(grp: String) {
        val members = getGroupNumbers(grp)
        tvGroupStats.text = "${members.size} / 300 members"
        val delay = prefs.getInt("delay_$grp", 6)
        sbDelay.progress = delay
        tvDelayDisplay.text = "Delay: $delay to ${delay + 5} seconds (Human Behavior)"
    }

    private fun refreshGroups() {
        groupList.clear()
        val all = prefs.all
        for (k in all.keys) {
            if (k.startsWith("grp_")) groupList.add(k.removePrefix("grp_"))
        }

        if (groupList.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            layoutWorkspace.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            layoutWorkspace.visibility = View.VISIBLE
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupList)
            spGroups.adapter = adapter
            updateGroupUI(groupList[0])
        }
    }

    private fun attachMedia(uri: Uri) {
        selectedMediaUri = uri
        ivPreviewMedia.visibility = View.VISIBLE
        ivPreviewMedia.setImageURI(uri)
        tvMediaStatus.text = "Attached: Media Ready"
        tvMediaStatus.setTextColor(0xFF00A884.toInt())
    }

    private fun launchBroadcast(recipients: List<String>, message: String) {
        BroadcastEngineCoordinator.reset()

        // 1. Start Floating Capsule Service
        val intent = Intent(this, FloatingControlsService::class.java)
        startService(intent)

        // 2. Dispatch Campaign Loop
        Thread {
            val total = recipients.size
            for ((idx, num) in recipients.withIndex()) {
                if (BroadcastEngineCoordinator.isCancelled.get()) break
                while (BroadcastEngineCoordinator.isPaused.get()) {
                    Thread.sleep(300)
                    if (BroadcastEngineCoordinator.isCancelled.get()) break
                }
                if (BroadcastEngineCoordinator.isCancelled.get()) break

                // Trigger WhatsApp URI Intent
                val waIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$num&text=${Uri.encode(message)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(waIntent)

                // Human delay interval
                val waitTime = Random.nextLong(currentGroupDelay.toLong() * 1000, (currentGroupDelay + 5).toLong() * 1000)
                val steps = waitTime / 200
                for (s in 0 until steps) {
                    if (BroadcastEngineCoordinator.isCancelled.get()) break
                    while (BroadcastEngineCoordinator.isPaused.get()) {
                        Thread.sleep(200)
                        if (BroadcastEngineCoordinator.isCancelled.get()) break
                    }
                    Thread.sleep(200)
                }

                BroadcastEngineCoordinator.progressFlow.value = Pair(idx + 1, total)
            }
        }.start()
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "Toggle Dark / Light Theme",
            "Language: English",
            "மொழி: தமிழ் (Tamil)",
            "भाषा: हिंदी (Hindi)",
            "Share App"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleTheme()
                    1 -> changeLocale("en")
                    2 -> changeLocale("ta")
                    3 -> changeLocale("hi")
                    4 -> shareApp()
                }
            }.show()
    }

    private fun toggleTheme() {
        val isDark = prefs.getBoolean("dark_theme", true)
        prefs.edit().putBoolean("dark_theme", !isDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun changeLocale(lang: String) {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        recreate()
    }

    private fun shareApp() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Use QuickSender Pro for WhatsApp bulk messaging with floating controls!")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share QuickSender Pro"))
    }

    private fun requestSystemPermissions() {
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
            checkFloatingWindowPermission()
        }
    }

    private fun checkFloatingWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Floating Controls Permission")
                .setMessage("QuickSender Pro needs 'Display over other apps' to show the progress capsule with Pause/Cancel controls.")
                .setPositiveButton("Enable") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (info in list) {
            if (info.id.contains(packageName)) return true
        }
        return false
    }

    private fun showAccessibilityPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Enable Automation Engine")
            .setMessage("Please enable 'QuickSender Pro' in Accessibility Settings so messages can be sent automatically.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getGroupNumbers(grp: String): MutableList<String> {
        val raw = prefs.getString("grp_$grp", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    private fun saveGroupNumbers(grp: String, list: List<String>) {
        val arr = JSONArray(list)
        prefs.edit().putString("grp_$grp", arr.toString()).apply()
    }
}
