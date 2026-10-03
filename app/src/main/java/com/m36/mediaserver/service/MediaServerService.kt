package com.m36.mediaserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import com.m36.mediaserver.MainActivity
import com.m36.mediaserver.data.ServerPreferences
import com.m36.mediaserver.domain.ServerDiagnostics
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Foreground owner of the local UPnP server, so playback survives Activity backgrounding. */
class MediaServerService : Service() {
    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<(ServerDiagnostics) -> Unit>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()
    private val preferences by lazy { ServerPreferences(applicationContext) }

    @Volatile private var engine: MediaServerEngine? = null
    @Volatile private var lastDiagnostics = ServerDiagnostics()
    @Volatile private var statusText = "Stopped"
    @Volatile private var operationGeneration = 0L
    private var ticker: Runnable? = null
    private var foreground = false

    inner class LocalBinder : Binder() {
        fun observe(listener: (ServerDiagnostics) -> Unit) {
            listeners.addIfAbsent(listener)
            listener(getDiagnostics())
        }

        fun removeObserver(listener: (ServerDiagnostics) -> Unit) {
            listeners.remove(listener)
        }

        fun getDiagnostics(): ServerDiagnostics = this@MediaServerService.getDiagnostics()

        fun stopServer() = this@MediaServerService.stopServer()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val uri = intent?.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse) ?: preferences.sharedTreeUri
                if (uri == null) {
                    statusText = "Choose a shared folder before starting the server"
                    publish()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startServer(uri)
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        operationGeneration++
        ticker?.let { android.os.Handler(mainLooper).removeCallbacks(it) }
        ticker = null
        engine?.let { current -> runCatching { current.close() } }
        engine = null
        serviceScope.cancel()
        super.onDestroy()
    }

    fun currentDiagnostics(): ServerDiagnostics = getDiagnostics()

    private fun startServer(treeUri: Uri) {
        if (engine != null) {
            publish()
            return
        }
        val generation = ++operationGeneration
        statusText = "Starting server…"
        lastDiagnostics = lastDiagnostics.copy(serverStatus = statusText, selectedFolder = folderLabel(treeUri))
        startForegroundNow()
        publish()

        val newEngine = MediaServerEngine(applicationContext, treeUri, preferences.deviceUuid)
        engine = newEngine
        serviceScope.launch(Dispatchers.IO) {
            var failure: Throwable? = null
            lifecycleMutex.withLock {
                if (generation != operationGeneration) return@withLock
                try {
                    newEngine.start()
                } catch (error: Throwable) {
                    failure = error
                    runCatching { newEngine.close() }
                }
                if (generation != operationGeneration) runCatching { newEngine.close() }
            }
            withContext(Dispatchers.Main) {
                if (generation != operationGeneration || engine !== newEngine) return@withContext
                if (failure == null) {
                    statusText = "Running"
                    lastDiagnostics = newEngine.snapshot()
                    startDiagnosticsTicker()
                    updateNotification("Serving local media")
                } else {
                    statusText = "Start failed: ${failure?.message ?: failure?.javaClass?.simpleName}"
                    lastDiagnostics = newEngine.snapshot().copy(running = false, serverStatus = statusText)
                    engine = null
                    stopDiagnosticsTicker()
                    stopForegroundCompat()
                    stopSelf()
                }
                publish()
            }
        }
    }

    private fun stopServer() {
        val stopping = engine
        if (stopping == null) {
            statusText = "Stopped"
            stopDiagnosticsTicker()
            stopForegroundCompat()
            publish()
            stopSelf()
            return
        }
        val generation = ++operationGeneration
        engine = null
        statusText = "Stopping…"
        publish()
        serviceScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                runCatching { stopping.close() }
            }
            val finalSnapshot = stopping.snapshot()
            withContext(Dispatchers.Main) {
                if (generation != operationGeneration) return@withContext
                lastDiagnostics = finalSnapshot.copy(running = false, serverStatus = "Stopped")
                statusText = "Stopped"
                stopDiagnosticsTicker()
                stopForegroundCompat()
                publish()
                stopSelf()
            }
        }
    }

    private fun getDiagnostics(): ServerDiagnostics {
        val current = engine
        val live = current?.snapshot()
        return if (live != null) live.copy(serverStatus = statusText.takeIf { it == "Starting server…" } ?: live.serverStatus)
        else lastDiagnostics.copy(running = false, serverStatus = statusText)
    }

    private fun publish() {
        val snapshot = getDiagnostics()
        lastDiagnostics = snapshot
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun startDiagnosticsTicker() {
        stopDiagnosticsTicker()
        val handler = android.os.Handler(mainLooper)
        val task = object : Runnable {
            override fun run() {
                if (engine == null) return
                publish()
                handler.postDelayed(this, DIAGNOSTICS_INTERVAL_MILLIS)
            }
        }
        ticker = task
        handler.post(task)
    }

    private fun stopDiagnosticsTicker() {
        ticker?.let { android.os.Handler(mainLooper).removeCallbacks(it) }
        ticker = null
    }

    private fun startForegroundNow() {
        val notification = buildNotification("Starting server…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun updateNotification(message: String) {
        if (!foreground) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MediaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("M36 Media Server")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop Server", stopIntent).build())
        return builder.build()
    }

    private fun stopForegroundCompat() {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foreground = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "M36 Media Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Visible while the local UPnP media server is running"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun folderLabel(uri: Uri): String = runCatching {
        val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val documentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        contentResolver.query(
            documentUri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }.getOrNull() ?: "Selected folder"

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_START = "com.m36.mediaserver.action.START"
        const val ACTION_STOP = "com.m36.mediaserver.action.STOP"
        const val EXTRA_TREE_URI = "tree_uri"
        private const val NOTIFICATION_ID = 3601
        private const val NOTIFICATION_CHANNEL_ID = "m36-media-server"
        private const val DIAGNOSTICS_INTERVAL_MILLIS = 1000L
    }
}
