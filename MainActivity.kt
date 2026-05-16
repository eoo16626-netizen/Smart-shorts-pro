package com.smartshortspro.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.textfield.TextInputEditText
import com.smartshortspro.automation.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // ============================================================================
    // PROPERTIES & INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityMainBinding
    private var isProcessing = false
    private var isPythonReady = false

    companion object {
        private const val TAG = "SmartShortsPro"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val DEPS_MARKER_FILE = ".smart_shorts_deps_installed"
        private const val FFMPEG_MARKER_FILE = ".smart_shorts_ffmpeg_ready"
        private const val PYTHON_PACKAGES = """
            requests==2.31.0
            urllib3==2.1.0
            moviepy==1.0.3
            pillow==10.1.0
            numpy==1.24.3
        """
    }

    // ============================================================================
    // LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "MainActivity created")

        // Setup UI Components
        setupUI()

        // Request necessary permissions
        requestRequiredPermissions()

        // Initialize Python environment (non-blocking)
        initializePythonEnvironment()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity resumed")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity paused")
    }

    // ============================================================================
    // UI SETUP & INPUT HANDLING
    // ============================================================================

    private fun setupUI() {
        binding.apply {
            // Configure input fields with text watchers
            editVideoUrl.addTextChangedListener(createInputTextWatcher())
            editApiKey.addTextChangedListener(createInputTextWatcher())

            // Configure Start Processing Button
            buttonStartProcessing.apply {
                isEnabled = false
                setOnClickListener {
                    handleStartProcessingClick()
                }
            }

            // Initial status
            textStatus.text = "Status: Idle"
            textStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_idle))
            progressBar.visibility = View.GONE
        }
    }

    private fun createInputTextWatcher(): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateAndUpdateButtonState()
            }
        }
    }

    private fun validateAndUpdateButtonState() {
        val videoUrl = binding.editVideoUrl.text.toString().trim()
        val apiKey = binding.editApiKey.text.toString().trim()

        val isInputValid = videoUrl.isNotEmpty() && apiKey.isNotEmpty()
        val canProcess = isInputValid && !isProcessing && isPythonReady

        binding.buttonStartProcessing.isEnabled = canProcess

        Log.d(TAG, "Button state updated - Input valid: $isInputValid, Python ready: $isPythonReady, Processing: $isProcessing")
    }

    // ============================================================================
    // PERMISSION HANDLING
    // ============================================================================

    private fun requestRequiredPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        // Android 13+ media permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.apply {
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.contentToString()}")
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "All permissions already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d(TAG, "All permissions granted by user")
            } else {
                Log.w(TAG, "Some permissions denied by user")
                Toast.makeText(
                    this,
                    "Some permissions are required for this app to work properly",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================================
    // PYTHON ENVIRONMENT INITIALIZATION
    // ============================================================================

    private fun initializePythonEnvironment() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "Initializing Python environment...")
                updateStatus("Checking environment...")

                // Step 1: Initialize Chaquopy
                if (!Python.isStarted()) {
                    Log.d(TAG, "Starting Chaquopy Python runtime...")
                    Python.start(AndroidPlatform(this@MainActivity))
                }

                // Step 2: Get or create private directory
                val pythonPrivateDir = File(filesDir, "python_env")
                if (!pythonPrivateDir.exists()) {
                    pythonPrivateDir.mkdirs()
                    Log.d(TAG, "Created Python private directory: ${pythonPrivateDir.absolutePath}")
                }

                // Step 3: Check if dependencies are already installed
                val depsMarker = File(filesDir, DEPS_MARKER_FILE)

                if (!depsMarker.exists()) {
                    Log.d(TAG, "Dependencies not found, installing silently...")
                    updateStatus("Setting up environment...")
                    installDependenciesSilently()
                    depsMarker.createNewFile()
                    Log.d(TAG, "Dependencies installation marker created")
                } else {
                    Log.d(TAG, "Dependencies already installed")
                }

                // Step 4: Prepare FFmpeg (placeholder for actual binary)
                prepareFfmpegBinary()

                // Mark as ready
                isPythonReady = true
                validateAndUpdateButtonState()
                updateStatus("Environment ready ✓")
                Log.d(TAG, "Python environment fully initialized and ready")

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Python environment", e)
                updateStatus("Setup failed: ${e.message?.take(30)}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to initialize: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private suspend fun installDependenciesSilently() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting silent dependency installation...")
                val python = Python.getInstance()

                // Create Python script to install packages
                val installScript = """
import subprocess
import sys
import os

packages = [
    'requests==2.31.0',
    'urllib3==2.1.0',
    'moviepy==1.0.3',
    'pillow==10.1.0',
    'numpy==1.24.3'
]

installed_count = 0
failed_count = 0

for package in packages:
    try:
        result = subprocess.run(
            [sys.executable, '-m', 'pip', 'install', '--quiet', '--no-warn-script-location', package],
            capture_output=True,
            timeout=300
        )
        if result.returncode == 0:
            installed_count += 1
            print(f'✓ {package}')
        else:
            failed_count += 1
            print(f'✗ {package}')
    except Exception as e:
        failed_count += 1
        print(f'✗ {package}: {str(e)}')

print(f'Installation complete: {installed_count} succeeded, {failed_count} failed')
""".trimIndent()

                Log.d(TAG, "Executing pip installation script...")
                val result = python.eval(installScript)
                Log.d(TAG, "Installation result: $result")

            } catch (e: Exception) {
                Log.e(TAG, "Error during dependency installation", e)
                throw e
            }
        }
    }

    private suspend fun prepareFfmpegBinary() {
        withContext(Dispatchers.IO) {
            try {
                val ffmpegDir = File(filesDir, "ffmpeg_bin")
                val ffmpegMarker = File(filesDir, FFMPEG_MARKER_FILE)

                if (!ffmpegMarker.exists()) {
                    if (!ffmpegDir.exists()) {
                        ffmpegDir.mkdirs()
                        Log.d(TAG, "Created FFmpeg binary directory: ${ffmpegDir.absolutePath}")
                    }

                    Log.d(TAG, "FFmpeg binary preparation initialized")
                    // In production, download actual mobile FFmpeg binary here
                    // This is sandboxed and silent - no system alerts
                    ffmpegMarker.createNewFile()
                    Log.d(TAG, "FFmpeg binary marker created")
                } else {
                    Log.d(TAG, "FFmpeg binary already prepared")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing FFmpeg", e)
                // Non-critical, continue anyway
            }
        }
    }

    // ============================================================================
    // VIDEO PROCESSING HANDLER
    // ============================================================================

    private fun handleStartProcessingClick() {
        val videoUrl = binding.editVideoUrl.text.toString().trim()
        val apiKey = binding.editApiKey.text.toString().trim()

        // Validate inputs
        if (!validateInputs(videoUrl, apiKey)) {
            return
        }

        // Start processing
        Log.d(TAG, "User initiated video processing")
        isProcessing = true
        validateAndUpdateButtonState()
        startVideoProcessing(videoUrl, apiKey)
    }

    private fun validateInputs(videoUrl: String, apiKey: String): Boolean {
        when {
            videoUrl.isEmpty() -> {
                Toast.makeText(this, "Please enter a video URL", Toast.LENGTH_SHORT).show()
                return false
            }
            apiKey.isEmpty() -> {
                Toast.makeText(this, "Please enter your Groq API Key", Toast.LENGTH_SHORT).show()
                return false
            }
            !isValidUrl(videoUrl) -> {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                return false
            }
            !isPythonReady -> {
                Toast.makeText(this, "Environment still initializing, please wait...", Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            URL(url)
            url.startsWith("http://") || url.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }

    private fun startVideoProcessing(videoUrl: String, apiKey: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "Video processing started for: $videoUrl")
                updateStatus("Downloading video...")
                showProgressBar(true)

                // Create working directory
                val workDir = File(filesDir, "processing_${System.currentTimeMillis()}")
                workDir.mkdirs()
                Log.d(TAG, "Created working directory: ${workDir.absolutePath}")

                // Execute Python processing script
                val success = executeVideoProcessing(videoUrl, apiKey, workDir)

                if (success) {
                    val outputFile = File(workDir, "output.mp4")
                    if (outputFile.exists()) {
                        val outputSize = outputFile.length() / (1024 * 1024) // Size in MB
                        Log.d(TAG, "Video processing completed: ${outputFile.absolutePath} (${outputSize}MB)")
                        updateStatus("Processing complete ✓")
                        showProgressBar(false)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Output: ${outputFile.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        // Optional: Auto-cleanup working directory after delay
                        scheduleCleanup(workDir)
                    } else {
                        updateStatus("Error: Output file not created")
                        Log.e(TAG, "Output file not found")
                    }
                } else {
                    updateStatus("Processing failed")
                    Log.e(TAG, "Video processing returned failure")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Processing failed", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception during video processing", e)
                updateStatus("Error: ${e.message?.take(25)}")
                showProgressBar(false)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message?.take(50)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } finally {
                isProcessing = false
                validateAndUpdateButtonState()
                showProgressBar(false)
            }
        }
    }

    private suspend fun executeVideoProcessing(
        videoUrl: String,
        apiKey: String,
        workDir: File
    ): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val python = Python.getInstance()

                // Main processing script - fully local execution
                val processingScript = """
import sys
import os
import json
from pathlib import Path

# Set working directory
work_dir = r"${workDir.absolutePath}"
os.chdir(work_dir)

try:
    # Import modules (silently installed)
    import requests
    from moviepy.editor import VideoFileClip, concatenate_videoclips
    from PIL import Image
    import numpy as np
    
    print("Modules imported successfully")
    
    # Configuration
    VIDEO_URL = r"$videoUrl"
    API_KEY = r"$apiKey"
    INPUT_VIDEO = os.path.join(work_dir, "input_video.mp4")
    OUTPUT_VIDEO = os.path.join(work_dir, "output.mp4")
    
    print("=" * 60)
    print("SMART SHORTS PRO - LOCAL VIDEO PROCESSING")
    print("=" * 60)
    
    # STEP 1: DOWNLOAD VIDEO
    print("\n[1/4] Downloading video from URL...")
    try:
        session = requests.Session()
        session.headers.update({'User-Agent': 'Mozilla/5.0'})
        
        response = session.get(VIDEO_URL, stream=True, timeout=300)
        response.raise_for_status()
        
        total_size = int(response.headers.get('content-length', 0))
        downloaded = 0
        chunk_size = 8192
        
        with open(INPUT_VIDEO, 'wb') as f:
            for chunk in response.iter_content(chunk_size=chunk_size):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
                    if total_size:
                        progress = int((downloaded / total_size) * 100)
                        print(f"  Download progress: {progress}%", end='\\r')
        
        print(f"\n  ✓ Video downloaded ({total_size // (1024*1024)}MB)")
        
    except Exception as e:
        print(f"  ✗ Download failed: {str(e)}")
        sys.exit(1)
    
    # STEP 2: LOAD AND ANALYZE VIDEO
    print("\n[2/4] Loading and analyzing video...")
    try:
        video = VideoFileClip(INPUT_VIDEO)
        duration = video.duration
        fps = video.fps or 30
        resolution = video.size
        
        print(f"  ✓ Video loaded")
        print(f"    - Duration: {duration:.1f}s")
        print(f"    - FPS: {fps}")
        print(f"    - Resolution: {resolution[0]}x{resolution[1]}")
        
    except Exception as e:
        print(f"  ✗ Failed to load video: {str(e)}")
        sys.exit(1)
    
    # STEP 3: PROCESS VIDEO (Cut into shorts)
    print("\n[3/4] Processing video into shorts...")
    try:
        shorts_duration = 60  # 60-second clips
        clips = []
        segment_count = 0
        
        current_time = 0
        while current_time < duration:
            end_time = min(current_time + shorts_duration, duration)
            clip = video.subclipped(current_time, end_time)
            clips.append(clip)
            segment_count += 1
            print(f"  ✓ Segment {segment_count}: {current_time:.1f}s - {end_time:.1f}s")
            current_time = end_time
        
        print(f"  ✓ Created {segment_count} segments")
        
    except Exception as e:
        print(f"  ✗ Segmentation failed: {str(e)}")
        sys.exit(1)
    
    # STEP 4: WRITE OUTPUT VIDEO
    print("\n[4/4] Rendering final video...")
    try:
        if clips:
            # Concatenate clips
            if len(clips) > 1:
                final_video = concatenate_videoclips(clips)
            else:
                final_video = clips[0]
            
            print(f"  Rendering to {OU
