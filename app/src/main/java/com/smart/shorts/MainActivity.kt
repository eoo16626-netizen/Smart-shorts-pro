package com.smart.shorts

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

// ── UI references ────────────────────────────────────────────────────────
private lateinit var etVideoUrl: EditText
private lateinit var etGroqApiKey: EditText
private lateinit var btnStartProcessing: Button
private lateinit var tvStatus: TextView
private lateinit var progressBar: ProgressBar
private lateinit var rootView: View

// ── State ────────────────────────────────────────────────────────────────
private var isProcessing = false

// ────────────────────────────────────────────────────────────────────────
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)

bindViews()
initPython()
setupClickListeners()
updateStatus("Ready. Enter a video URL and your Groq API key, then tap Start.", StatusLevel.IDLE)
}

// ── View binding (manual, no generated binding class required) ───────────
private fun bindViews() {
etVideoUrl = findViewById(R.id.etVideoUrl)
etGroqApiKey = findViewById(R.id.etGroqApiKey)
btnStartProcessing = findViewById(R.id.btnStartProcessing)
tvStatus = findViewById(R.id.tvStatus)
progressBar = findViewById(R.id.progressBar)
rootView = findViewById(android.R.id.content)
}

// ── Initialise Chaquopy Python runtime (idempotent) ──────────────────────
private fun initPython() {
if (!Python.isStarted()) {
Python.start(AndroidPlatform(this))
}
}

// ── Button listener ──────────────────────────────────────────────────────
private fun setupClickListeners() {
btnStartProcessing.setOnClickListener {
if (isProcessing) return@setOnClickListener

val videoUrl = etVideoUrl.text.toString().trim()
val groqApiKey = etGroqApiKey.text.toString().trim()

if (videoUrl.isEmpty()) {
showSnackbar("Please enter a video URL.")
return@setOnClickListener
}
if (groqApiKey.isEmpty()) {
showSnackbar("Please enter your Groq API key.")
return@setOnClickListener
}

startPipeline(videoUrl, groqApiKey)
}
}

// ── Coroutine-based pipeline launcher ────────────────────────────────────
private fun startPipeline(videoUrl: String, groqApiKey: String) {
isProcessing = true
setUiBusy(true)
updateStatus("Initialising pipeline…", StatusLevel.INFO)

lifecycleScope.launch {
val result = withContext(Dispatchers.IO) {
runPythonPipeline(videoUrl, groqApiKey)
}

// Back on Main thread
isProcessing = false
setUiBusy(false)

when (result) {
is PipelineResult.Success -> {
updateStatus("✓ Pipeline complete.\n\nlatex
{result.message}", StatusLevel.SUCCESS) } is PipelineResult.Failure -&gt; { updateStatus("✗ Pipeline failed.\n\n

{result.error}", StatusLevel.ERROR)
showSnackbar("Processing failed — see status for details.")
}
}
}
}

// ── Core Python invocation (runs on IO dispatcher) ───────────────────────
private fun runPythonPipeline(videoUrl: String, groqApiKey: String): PipelineResult {
return try {
val py = Python.getInstance()
val module = py.getModule("smart_shorts")

// Write a config file inside filesDir so the Python script can
// read it without needing Android context directly.
val configFile = File(filesDir, "pipeline_config.txt")
configFile.writeText(
"VIDEO_URL=latex
videoUrl\n" + "GROQ_API_KEY=

groqApiKey\n" +
"WORK_DIR=${filesDir.absolutePath}\n"
)

// Invoke the main() entry-point of smart_shorts.py.
// The script reads pipeline_config.txt from the path we pass.
val resultObj = module.callAttr(
"main",
filesDir.absolutePath,
videoUrl,
groqApiKey
)

val message = resultObj?.toString() ?: "Pipeline returned no output."
PipelineResult.Success(message)

} catch (e: Exception) {
val trace = e.localizedMessage ?: e.javaClass.simpleName
PipelineResult.Failure(trace)
}
}

// ── UI helpers ───────────────────────────────────────────────────────────

/** Toggle interactive UI elements and progress indicator. */
private fun setUiBusy(busy: Boolean) {
btnStartProcessing.isEnabled = !busy
btnStartProcessing.text = if (busy) "Processing…" else "Start Processing"
progressBar.visibility = if (busy) View.VISIBLE else View.GONE
etVideoUrl.isEnabled = !busy
etGroqApiKey.isEnabled = !busy
}

/** Update the status TextView with coloured feedback on the main thread. */
private fun updateStatus(message: String, level: StatusLevel) {
// This function is always called from the main thread or the
// lifecycleScope continuation on Main, so direct assignment is safe.
tvStatus.text = message
tvStatus.setTextColor(
when (level) {
StatusLevel.IDLE -> Color.parseColor("#9E9E9E") // grey
StatusLevel.INFO -> Color.parseColor("#1565C0") // blue
StatusLevel.SUCCESS -> Color.parseColor("#2E7D32") // green
StatusLevel.ERROR -> Color.parseColor("#C62828") // red
}
)
}

private fun showSnackbar(message: String) {
Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
}

// ── Sealed result type ───────────────────────────────────────────────────
sealed class PipelineResult {
data class Success(val message: String) : PipelineResult()
data class Failure(val error: String) : PipelineResult()
}

// ── Status level enum ────────────────────────────────────────────────────
enum class StatusLevel { IDLE, INFO, SUCCESS, ERROR }
}
