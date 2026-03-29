package com.pixelforge.app.bridge

import android.util.Log

/**
 * Handles native library loading with error reporting.
 * Call [load] once during Application.onCreate().
 */
object NativeLoader {
    private const val TAG = "NativeLoader"
    private const val LIB_NAME = "pixelforge-native"

    var isLoaded: Boolean = false
        private set

    /**
     * Load the native library. Safe to call multiple times.
     *
     * @return true if loaded successfully, false on failure
     */
    fun load(): Boolean {
        if (isLoaded) return true

        return try {
            try {
                // Try loading OpenMP manually if it's available as a shared library
                System.loadLibrary("omp")
            } catch (ignored: UnsatisfiedLinkError) {
                // Not a fatal error if it's already loaded or handled by system
            }

            System.loadLibrary(LIB_NAME)
            isLoaded = true
            Log.i(TAG, "Native library '$LIB_NAME' loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library '$LIB_NAME'. This is often due to a missing 'libomp.so' dependency. Please clean and rebuild the project.", e)
            false
        }
            }
            }
