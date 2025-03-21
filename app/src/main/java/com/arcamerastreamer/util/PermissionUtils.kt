package com.arcamerastreamer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Utility class for handling permissions
 */
object PermissionUtils {

    /**
     * Get all permissions required by the app
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )

        // Add audio permission
        permissions.add(Manifest.permission.RECORD_AUDIO)

        // Add storage permissions for older Android versions
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return permissions
    }

    /**
     * Get permissions needed for camera
     */
    fun getCameraPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )

        // Add storage permissions for older Android versions
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return permissions
    }

    /**
     * Get permissions needed for audio
     */
    fun getAudioPermissions(): List<String> {
        return listOf(
            Manifest.permission.RECORD_AUDIO
        )
    }

    /**
     * Check if all required permissions are granted
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if camera permission is granted
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get only the permissions that are not granted yet
     */
    fun getMissingPermissions(context: Context, permissions: List<String>): List<String> {
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
}