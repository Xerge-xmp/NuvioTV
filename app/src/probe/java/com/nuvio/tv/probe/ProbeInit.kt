package com.nuvio.tv.probe

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.nuvio.tv.core.probe.AppProbe

/** Switches the probe on at process start, before any screen exists, so every Compose window is seen from its creation. */
class ProbeInit : ContentProvider() {
    override fun onCreate(): Boolean {
        AppProbe.enabled = true
        ProbeUi.install(context!!.applicationContext as Application)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
