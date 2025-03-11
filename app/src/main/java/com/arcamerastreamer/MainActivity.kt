package com.arcamerastreamer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.arcamerastreamer.databinding.ActivityMainBinding
import com.arcamerastreamer.streaming.StreamingService
import com.arcamerastreamer.util.PermissionUtils
import com.google.ar.core.ArCoreApk
import com.permissionx.guolindev.PermissionX

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // ARCore install request launcher
    private val installARCoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Successfully installed ARCore, check availability again
            checkAndRequestARCoreInstall()
        } else {
            Toast.makeText(this, R.string.error_ar_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Setup bottom navigation with controller
        binding.bottomNavigation.setupWithNavController(navController)

        // Request permissions
        requestRequiredPermissions()

        // Check if ARCore needs installation
        if (ARCameraStreamerApplication.isARSupported && !ARCameraStreamerApplication.isARCoreAvailable) {
            checkAndRequestARCoreInstall()
        }

        // If app was launched via service notification, navigate to camera fragment
        if (intent.getBooleanExtra(StreamingService.EXTRA_FROM_NOTIFICATION, false)) {
            navController.navigate(R.id.cameraFragment)
        } else {
            // Show streaming options fragment on first launch
            navController.navigate(R.id.streamingOptionsFragment)
        }
    }

    /**
     * Request all permissions required by the app
     */
    private fun requestRequiredPermissions() {
        PermissionX.init(this)
            .permissions(PermissionUtils.getRequiredPermissions())
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    getString(R.string.permission_required),
                    getString(R.string.permission_grant),
                    getString(R.string.permission_cancel)
                )
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(
                    deniedList,
                    getString(R.string.permission_required),
                    getString(R.string.permission_settings),
                    getString(R.string.permission_cancel)
                )
            }
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    Toast.makeText(
                        this,
                        R.string.permission_denied,
                        Toast.LENGTH_LONG
                    ).show()

                    // If permissions are not granted, the app may not function properly
                    // Inform user and proceed, specific features will check permissions again
                }
            }
    }

    /**
     * Check and request ARCore installation if needed
     */
    private fun checkAndRequestARCoreInstall() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)

        if (availability.isTransient) {
            // Wait for a bit before trying again
            Handler(Looper.getMainLooper()).postDelayed({
                checkAndRequestARCoreInstall()
            }, 200)
            return
        }

        // If AR is supported but not available (not installed or too old)
        if (availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD) {
            try {
                // Request ARCore installation or update
                val arCoreInstallIntent = Intent(Intent.ACTION_VIEW)
                arCoreInstallIntent.data = android.net.Uri.parse("market://details?id=com.google.ar.core")
                installARCoreLauncher.launch(arCoreInstallIntent)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_ar_unavailable, Toast.LENGTH_LONG).show()
            }
        } else if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            // ARCore is installed and up to date
            updateARCoreAvailability(true)
        }
    }

    /**
     * Update ARCore availability in the application
     */
    private fun updateARCoreAvailability(available: Boolean) {
        // Get the companion object via reflection
        val companionField = ARCameraStreamerApplication::class.java.getDeclaredField("Companion")
        companionField.isAccessible = true
        val companion = companionField.get(null)

        // Find the setter method
        val setterMethod = companion.javaClass.getDeclaredMethod("setARCoreAvailable", Boolean::class.java)
        setterMethod.isAccessible = true

        // Invoke the setter
        setterMethod.invoke(companion, available)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Handle notification click
        if (intent.getBooleanExtra(StreamingService.EXTRA_FROM_NOTIFICATION, false)) {
            navController.navigate(R.id.cameraFragment)
        }
    }
}