package com.m36.mediaserver

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.m36.mediaserver.data.ServerPreferences
import com.m36.mediaserver.domain.ServerDiagnostics
import com.m36.mediaserver.service.MediaServerService

/** Small native diagnostics/control screen; all media serving runs in MediaServerService. */
class MainActivity : Activity() {
    private lateinit var preferences: ServerPreferences
    private lateinit var statusView: TextView
    private lateinit var networkView: TextView
    private lateinit var ipView: TextView
    private lateinit var upnpView: TextView
    private lateinit var folderView: TextView
    private lateinit var detailsView: TextView
    private lateinit var detailsButton: Button
    private lateinit var selectFolderButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var detailsVisible = false
    private var serviceBound = false
    private var localBinder: MediaServerService.LocalBinder? = null
    private var currentDiagnostics = ServerDiagnostics()
    private var selectedFolderName = "Not selected"
    private var observer: ((ServerDiagnostics) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaServerService.LocalBinder ?: return
            localBinder = binder
            val callback: (ServerDiagnostics) -> Unit = { snapshot ->
                currentDiagnostics = snapshot
                if (snapshot.selectedFolder.isNotBlank() && snapshot.selectedFolder != "Not selected") {
                    selectedFolderName = snapshot.selectedFolder
                }
                render()
            }
            observer = callback
            binder.observe(callback)
            currentDiagnostics = binder.getDiagnostics()
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            observer?.let { localBinder?.removeObserver(it) }
            observer = null
            localBinder = null
            serviceBound = false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = ServerPreferences(applicationContext)
        selectedFolderName = preferences.sharedTreeUri?.let(::folderLabel) ?: "Not selected"
        buildUi()
        requestNotificationPermissionIfNeeded()
        render()
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(Intent(this, MediaServerService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        observer?.let { localBinder?.removeObserver(it) }
        observer = null
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false
        localBinder = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        selectedFolderName = preferences.sharedTreeUri?.let(::folderLabel) ?: "Not selected"
        render()
    }

    @Deprecated("Use the platform document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SELECT_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val permissionFlags = (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) or
            (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            if (permissionFlags != 0) contentResolver.takePersistableUriPermission(uri, permissionFlags)
            preferences.sharedTreeUri = uri
            selectedFolderName = folderLabel(uri)
            currentDiagnostics = currentDiagnostics.copy(selectedFolder = selectedFolderName)
            render()
        } catch (error: SecurityException) {
            Toast.makeText(this, "Could not keep access to that folder: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(26))
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "M36 Media Server"
            textSize = 27f
            setTextColor(0xFF15202B.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, matchWidthWrap())
        content.addView(TextView(this).apply {
            text = "Local UPnP / DLNA streaming for VLC"
            textSize = 14f
            setTextColor(0xFF56616D.toInt())
            setPadding(0, dp(3), 0, dp(18))
        }, matchWidthWrap())

        statusView = addField(content, "Status", "● Server stopped")
        networkView = addField(content, "Network", "Detecting…")
        ipView = addField(content, "IPv4 addresses", "No local IPv4 address detected")
        upnpView = addField(content, "UPnP", "SSDP stopped")
        folderView = addField(content, "Shared folder", "Not selected")

        selectFolderButton = Button(this).apply {
            text = "Select Folder"
            setOnClickListener { openFolderPicker() }
        }
        startButton = Button(this).apply {
            text = "Start Server"
            setOnClickListener { startServer() }
        }
        stopButton = Button(this).apply {
            text = "Stop Server"
            setOnClickListener { stopServer() }
        }
        content.addView(selectFolderButton, matchWidthWrap())
        content.addView(startButton, matchWidthWrap())
        content.addView(stopButton, matchWidthWrap())

        detailsButton = Button(this).apply {
            text = "Show Details"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                detailsVisible = !detailsVisible
                detailsButton.text = if (detailsVisible) "Hide Details" else "Show Details"
                detailsView.visibility = if (detailsVisible) View.VISIBLE else View.GONE
            }
        }
        content.addView(detailsButton, matchWidthWrap())
        detailsView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF35424E.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFE7ECF0.toInt())
            visibility = View.GONE
        }
        content.addView(detailsView, matchWidthWrap())
        content.addView(TextView(this).apply {
            text = "Only the selected folder is shared. Files are read-only and streamed directly; no transcoding or cloud access is used."
            textSize = 12f
            setTextColor(0xFF64717E.toInt())
            setPadding(0, dp(16), 0, 0)
        }, matchWidthWrap())
    }

    private fun addField(parent: LinearLayout, label: String, value: String): TextView {
        parent.addView(TextView(this).apply {
            text = label.uppercase()
            textSize = 11f
            setTextColor(0xFF687684.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(1))
        }, matchWidthWrap())
        return TextView(this).also { view ->
            view.text = value
            view.textSize = 16f
            view.setTextColor(0xFF1B2834.toInt())
            view.setPadding(0, 0, 0, dp(8))
            parent.addView(view, matchWidthWrap())
        }
    }

    private fun render() {
        if (!::statusView.isInitialized) return
        val diagnostics = currentDiagnostics
        val running = diagnostics.running
        statusView.text = if (running) "● Server running" else "● ${diagnostics.serverStatus}"
        statusView.setTextColor(if (running) 0xFF178344.toInt() else 0xFF485560.toInt())
        networkView.text = diagnostics.currentNetwork
        ipView.text = diagnostics.ipv4Addresses.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: "No local IPv4 address detected"
        upnpView.text = buildString {
            append(if (diagnostics.multicastGroupJoined) "● SSDP listening" else "○ SSDP not joined")
            append("\n")
            append(if (running) "● ContentDirectory / HTTP running" else "○ ContentDirectory stopped")
        }
        folderView.text = "[$selectedFolderName]"
        selectFolderButton.isEnabled = !running && !diagnostics.serverStatus.contains("Starting", true) &&
            !diagnostics.serverStatus.contains("Stopping", true)
        startButton.isEnabled = !running && preferences.sharedTreeUri != null &&
            !diagnostics.serverStatus.contains("Starting", true) && !diagnostics.serverStatus.contains("Stopping", true)
        stopButton.isEnabled = running || diagnostics.serverStatus.contains("Starting", true)
        detailsButton.text = if (detailsVisible) "Hide Details" else "Show Details"
        detailsView.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        detailsView.text = buildString {
            appendLine("Server: ${diagnostics.serverStatus}")
            appendLine("Network: ${diagnostics.currentNetwork}")
            appendLine("Selected server transport: ${diagnostics.activeTransport}")
            appendLine("Default network transport: ${diagnostics.defaultTransport}")
            appendLine("Active server interface: ${diagnostics.activeInterface}")
            appendLine("Active server IPv4: ${diagnostics.activeServerIpv4}")
            appendLine("All interfaces / IPv4 addresses:")
            if (diagnostics.ipv4Addresses.isEmpty()) {
                appendLine("  (none detected)")
            } else {
                diagnostics.ipv4Addresses.forEach { appendLine("  • $it") }
            }
            appendLine("HTTP address(es): ${diagnostics.httpBindAddress}:${diagnostics.httpPort}")
            appendLine("SSDP interface(s): ${diagnostics.ssdpInterface}")
            appendLine("SSDP status: ${diagnostics.ssdpStatus}")
            appendLine("Multicast group: ${diagnostics.multicastAddress}")
            appendLine("Multicast socket created: ${diagnostics.multicastSocketCreated}")
            appendLine("Multicast group joined: ${diagnostics.multicastGroupJoined}")
            appendLine("Socket details: ${diagnostics.multicastDetails}")
            appendLine("M-SEARCH count: ${diagnostics.mSearchCount}")
            appendLine("Last SSDP request: ${diagnostics.lastSsdpRequest}")
            appendLine("SSDP responses sent: ${diagnostics.ssdpResponsesSent}")
            appendLine("HTTP request count: ${diagnostics.httpRequestCount}")
            appendLine("Last HTTP request: ${diagnostics.lastHttpRequest}")
            appendLine("ContentDirectory Browse request: ${diagnostics.lastContentDirectoryBrowseRequest}")
            appendLine("ContentDirectory result: ${diagnostics.lastContentDirectoryBrowseResult}")
            appendLine("Last SAF traversal: ${diagnostics.lastSafEnumeration}")
            append("Selected shared folder: ${diagnostics.selectedFolder.takeIf { it != "Not selected" } ?: selectedFolderName}")
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_SELECT_FOLDER)
        } catch (error: Exception) {
            Toast.makeText(this, "No folder picker is available: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startServer() {
        val uri = preferences.sharedTreeUri
        if (uri == null) {
            Toast.makeText(this, "Select a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MediaServerService::class.java)
            .setAction(MediaServerService.ACTION_START)
            .putExtra(MediaServerService.EXTRA_TREE_URI, uri.toString())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not start server: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        localBinder?.stopServer() ?: run {
            startService(Intent(this, MediaServerService::class.java).setAction(MediaServerService.ACTION_STOP))
        }
    }

    private fun folderLabel(uri: Uri): String = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }.getOrNull() ?: "Selected folder"

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun matchWidthWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_SELECT_FOLDER = 4001
        private const val REQUEST_NOTIFICATIONS = 4002
    }
}
