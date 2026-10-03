package com.m36.mediaserver.data

import android.content.Context
import android.net.Uri
import java.util.UUID

class ServerPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var sharedTreeUri: Uri?
        get() = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        set(value) {
            preferences.edit().putString(KEY_TREE_URI, value?.toString()).apply()
        }

    val deviceUuid: String
        get() {
            val existing = preferences.getString(KEY_DEVICE_UUID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_DEVICE_UUID, created).apply()
            return created
        }

    companion object {
        private const val PREFERENCES_NAME = "m36_media_server"
        private const val KEY_TREE_URI = "shared_tree_uri"
        private const val KEY_DEVICE_UUID = "device_uuid"
    }
}
