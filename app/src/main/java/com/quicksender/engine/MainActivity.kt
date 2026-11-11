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
import androidx.core.os.LocaleListCompat
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var viewHome: View
    private lateinit var viewDetail: View
    private lateinit var btnBack: ImageView
    private lateinit var lvGroupStack: ListView
    private lateinit var tvNoGroups: TextView
    private lateinit var fabAddGroup: View
    private lateinit var tvCurrentGroupName: TextView
    private lateinit var tvGroupMembersCount: TextView
    private lateinit var etMessageBody: EditText
    private lateinit var tvMediaStatus: TextView
    private lateinit var ivPreviewMedia: ImageView
    private lateinit var tvPreviewAudio: TextView
    private lateinit var tvPreviewText: TextView
    private lateinit var tvDelayDisplay: TextView
    private lateinit var sbDelay: SeekBar
    private lateinit var rbBusinessWA: RadioButton

    private var activeGroup: String = ""
    private var selectedMediaUri: Uri? = null
    private var selectedMediaType: String = "" // "image", "video", "audio"
    private var currentGroupDelay = 6
    private val groupList = mutableListOf<String>()

    // File Pickers
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { bindMedia(it, "image") }
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { bindMedia(it, "video") }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { bindMedia(it, "audio") }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { parseFileNumbers(it) }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checkOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("QuickSenderData", Context.MODE_PRIVATE)

        // Apply theme from preferences
        val isDark = prefs.getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        setContentView(R.layout.activity_main)

        initViews()
        requestSystemPermissions()
        loadGroupsStack()
    }

    private fun initViews() {
        viewHome = findViewById(R.id.viewHomeGroups)
        viewDetail = findViewById(R.id.viewGroupDetail)
        btnBack = findViewById(R.id.btnBackToGroups)
        lvGroupStack = findViewById(R.id.lvGroupStack)
        tvNoGroups = findViewById(R.id.tvNoGroupsNotice)
        fabAddGroup = findViewById(R.id.fabAddGroup)

        tvCurrentGroupName = findViewById(R.id.tvCurrentGroupName)
        tvGroupMembersCount = findViewById(R.id.tvGroupMembersCount)
        etMessageBody = findViewById(R.id.etMessageBody)
        tvMediaStatus = findViewById(R.id.tvMediaStatus)
        ivPreviewMedia = findViewById(R.id.ivPreviewMedia)
        tvPreviewAudio = findViewById(R.id.tvPreviewAudio)
        tvPreviewText = findViewById(R.id.tvPreviewText)
        tvDelayDisplay = findViewById(R.id.tvDelayDisplay)
        sbDelay = findViewById(R.id.sbDelay)
        rbBusinessWA = findViewById(R.id.rbBusinessWA)

        findViewById<TextView>(R.id.tvPreviewClock).text =
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        // Back from detail to group stack
        btnBack.setOnClickListener {
            viewDetail.visibility = View.GONE
            viewHome.visibility = View.VISIBLE
            btnBack.visibility = View.GONE
            fabAddGroup.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvAppTitle).text = "QuickSender Pro"
            loadGroupsStack()
        }

        fabAddGroup.setOnClickListener { showCreateGroupDialog() }

        // Live preview typing
        etMessageBody.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tvPreviewText.text = if (s.isNullOrEmpty()) "Hello, preview here..." else s
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Anti-ban delay slider
        sbDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                currentGroupDelay = if (progress < 4) 4 else progress
                tvDelayDisplay.text = "Delay: $currentGroupDelay to ${currentGroupDelay + 5} seconds"
                if (activeGroup.isNotEmpty()) {
                    prefs.edit().putInt("delay_$activeGroup", currentGroupDelay).apply()
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnAttachImage).setOnClickListener { imagePicker.launch("image/*") }
        findViewById<Button>(R.id.btnAttachVideo).setOnClickListener { videoPicker.launch("video/*") }
        findViewById<Button>(R.id.btnAttachAudio).setOnClickListener { audioPicker.launch("audio/*") }

        findViewById<Button>(R.id.btnAddMembersModal).setOnClickListener { showAddMembersMenu() }
        findViewById<TextView>(R.id.btnManageMembers).setOnClickListener { showMembersManagerDialog() }
        findViewById<ImageView>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        findViewById<Button>(R.id.btnStartDispatch).setOnClickListener {
            val members = getGroupNumbers(activeGroup)
            val msg = etMessageBody.text.toString().trim()
            if (members.isEmpty()) {
                Toast.makeText(this, "Group has no members", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (msg.isEmpty() && selectedMediaUri == null) {
                Toast.makeText(this, "Add a message or media", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                showAccessibilityPrompt()
                return@setOnClickListener
            }
            launchCampaign(members, msg)
        }
    }

    private fun loadGroupsStack() {
        groupList.clear()
        val all = prefs.all
        for (k in all.keys) {
            if (k.startsWith("grp_")) groupList.add(k.removePrefix("grp_"))
        }

        if (groupList.isEmpty()) {
            tvNoGroups.visibility = View.VISIBLE
            lvGroupStack.visibility = View.GONE
        } else {
            tvNoGroups.visibility = View.GONE
            lvGroupStack.visibility = View.VISIBLE

            val displayList = groupList.map { "$it  (${getGroupNumbers(it).size} / 300)" }
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
            lvGroupStack.adapter = adapter

            // Click group to open its detailed compose screen
            lvGroupStack.setOnItemClickListener { _, _, pos, _ ->
                openGroupWorkspace(groupList[pos])
            }
        }
    }

    private fun openGroupWorkspace(grp: String) {
        activeGroup = grp
        viewHome.visibility = View.GONE
        viewDetail.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        fabAddGroup.visibility = View.GONE
        findViewById<TextView>(R.id.tvAppTitle).text = grp

        tvCurrentGroupName.text = grp
        val count = getGroupNumbers(grp).size
        tvGroupMembersCount.text = "$count / 300 members"

        currentGroupDelay = prefs.getInt("delay_$grp", 6)
        sbDelay.progress = currentGroupDelay
        tvDelayDisplay.text = "Delay: $currentGroupDelay to ${currentGroupDelay + 5} seconds"
    }

    private fun bindMedia(uri: Uri, type: String) {
        selectedMediaUri = uri
        selectedMediaType = type

        ivPreviewMedia.visibility = View.GONE
        tvPreviewAudio.visibility = View.GONE

        when (type) {
            "image", "video" -> {
                ivPreviewMedia.visibility = View.VISIBLE
                ivPreviewMedia.setImageURI(uri)
                tvMediaStatus.text = "Attached: ${type.uppercase()} Ready"
            }
            "audio" -> {
                tvPreviewAudio.visibility = View.VISIBLE
                tvMediaStatus.text = "Attached: AUDIO Ready"
            }
        }
    }

    private fun launchCampaign(recipients: List<String>, message: String) {
        BroadcastEngineCoordinator.reset()

        val overlayIntent = Intent(this, FloatingControlsService::class.java)
        startService(overlayIntent)

        val targetPackage = if (rbBusinessWA.isChecked) "com.whatsapp.w4b" else "com.whatsapp"

        Thread {
            val total = recipients.size
            for ((idx, phone) in recipients.withIndex()) {
                if (BroadcastEngineCoordinator.isCancelled.get()) break
                while (BroadcastEngineCoordinator.isPaused.get()) {
                    Thread.sleep(300)
                    if (BroadcastEngineCoordinator.isCancelled.get()) break
                }
                if (BroadcastEngineCoordinator.isCancelled.get()) break

                // Format phone: e.g. 919876543210@s.whatsapp.net
                val jid = "$phone@s.whatsapp.net"

                if (selectedMediaUri != null) {
                    // Send Media + Caption
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, selectedMediaUri)
                        putExtra(Intent.EXTRA_TEXT, message)
                        putExtra("jid", jid)
                        type = when (selectedMediaType) {
                            "video" -> "video/*"
                            "audio" -> "audio/*"
                            else -> "image/*"
                        }
                        setPackage(targetPackage)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(shareIntent)
                } else {
                    // Text-only direct dispatch
                    val waIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}")
                        setPackage(targetPackage)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(waIntent)
                }

                // Anti-ban randomized delay
                val sleepTime = Random.nextLong(currentGroupDelay * 1000L, (currentGroupDelay + 5) * 1000L)
                val steps = sleepTime / 200
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

    private fun showCreateGroupDialog() {
        val input = EditText(this).apply { hint = "New Group Name" }
        AlertDialog.Builder(this)
            .setTitle("Create Group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !groupList.contains(name)) {
                    saveGroupNumbers(name, mutableListOf())
                    prefs.edit().putInt("delay_$name", 6).apply()
                    loadGroupsStack()
                    openGroupWorkspace(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddMembersMenu() {
        val opts = arrayOf("Multi-Select Phone Contacts", "Direct Paste Numbers", "Import CSV / TXT File")
        AlertDialog.Builder(this)
            .setTitle("Add Members")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showMultiContactPicker()
                    1 -> showPasteDialog()
                    2 -> filePicker.launch("*/*")
                }
            }.show()
    }

    private fun showMultiContactPicker() {
        val contacts = fetchContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(this, "No contacts found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = contacts.map { "${it.first} (${it.second})" }.toTypedArray()
        val checked = BooleanArray(names.size)
        val selected = mutableListOf<String>()

        AlertDialog.Builder(this)
            .setTitle("Select Contacts (300 Max)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val num = contacts[which].second
                if (isChecked) selected.add(num) else selected.remove(num)
            }
            .setPositiveButton("Add Selected") { _, _ -> addBatchNumbers(selected) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchContacts(): List<Pair<String, String>> {
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
            hint = "Paste numbers (comma, space, or line separated)"
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

    private fun parseFileNumbers(uri: Uri) {
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
        val current = getGroupNumbers(activeGroup)
        var added = 0
        for (num in numbers) {
            val formatted = if (num.length == 10) "91$num" else num
            if (current.size < 300 && !current.contains(formatted)) {
                current.add(formatted)
                added++
            }
        }
        saveGroupNumbers(activeGroup, current)
        tvGroupMembersCount.text = "${current.size} / 300 members"
        Toast.makeText(this, "Added $added numbers", Toast.LENGTH_SHORT).show()
    }

    private fun showMembersManagerDialog() {
        val members = getGroupNumbers(activeGroup)
        if (members.isEmpty()) {
            Toast.makeText(this, "Group is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, members)
        val list = ListView(this).apply { this.adapter = adapter }

        val dialog = AlertDialog.Builder(this)
            .setTitle("$activeGroup (${members.size} / 300)")
            .setView(list)
            .setPositiveButton("Done", null)
            .create()

        list.setOnItemClickListener { _, _, pos, _ ->
            val num = members[pos]
            AlertDialog.Builder(this)
                .setTitle("Remove Contact")
                .setMessage("Remove $num from $activeGroup?")
                .setPositiveButton("Remove") { _, _ ->
                    members.removeAt(pos)
                    saveGroupNumbers(activeGroup, members)
                    adapter.notifyDataSetChanged()
                    tvGroupMembersCount.text = "${members.size} / 300 members"
                    if (members.isEmpty()) dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val opts = arrayOf(
            "Toggle Dark / Light Theme",
            "Language: English",
            "மொழி: தமிழ் (Tamil)",
            "भाषा: हिंदी (Hindi)"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> toggleTheme()
                    1 -> switchLanguage("en")
                    2 -> switchLanguage("ta")
                    3 -> switchLanguage("hi")
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

    private fun switchLanguage(langTag: String) {
        val appLocales = LocaleListCompat.forLanguageTags(langTag)
        AppCompatDelegate.setApplicationLocales(appLocales)
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
            permLauncher.launch(needed.toTypedArray())
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Floating Controls")
                .setMessage("Enable 'Display over other apps' to use the floating pause/cancel capsule.")
                .setPositiveButton("Enable") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return list.any { it.id.contains(packageName) }
    }

    private fun showAccessibilityPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Enable Automation Engine")
            .setMessage("Enable 'QuickSender Pro' in Accessibility Settings so it can send messages automatically.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
