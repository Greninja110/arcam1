package com.arcamerastreamer

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.arcamerastreamer.databinding.ActivityMainBinding
import com.arcamerastreamer.util.PermissionUtils
import com.permissionx.guolindev.PermissionX

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            // Setup navigation
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            navController = navHostFragment.navController

            // Setup bottom navigation with controller
            binding.bottomNavigation.setupWithNavController(navController)

            // Request required permissions
            requestRequiredPermissions()

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MainActivity: ${e.message}", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
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

                    // Log for debugging
                    Log.w(TAG, "Some permissions were denied")
                } else {
                    Log.d(TAG, "All required permissions granted")
                }
            }
    }
}