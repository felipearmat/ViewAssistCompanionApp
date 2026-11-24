package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.core.app.ActivityCompat

class CustomWebChromeClient(private val context: Context) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) {
        val alreadyGranted = ArrayList<String>()
        val toBeGranted = ArrayList<String>()
        request?.resources?.forEach {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        alreadyGranted.add(it)
                    } else {
                        toBeGranted.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        alreadyGranted.add(it)
                    } else {
                        toBeGranted.add(android.Manifest.permission.CAMERA)
                    }
                }
            }
        }
        // Protected (DRM) media, e.g. a music service's web player, over https only: the WebView
        // only shows pages HA sends it, and DRM needs a secure origin anyway.
        if (request?.resources?.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) == true &&
            request.origin?.scheme == "https") {
            alreadyGranted.add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
        }
        if (alreadyGranted.isNotEmpty()) {
            request?.grant(alreadyGranted.toTypedArray())
        }
    }
}