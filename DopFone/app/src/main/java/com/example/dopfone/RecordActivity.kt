package com.example.dopfone

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.opencsv.CSVWriter
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class RecordActivity : AppCompatActivity() {

    companion object {
        private const val REQ_AUDIO      = 123
        private const val SAMPLE_RATE    = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
    }

    private lateinit var timerView: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var sampleRateView: TextView

    private var isRecording   = false
    private var dialogShown   = false
    private var tempPcm       = ByteArray(0)
    private var recorder: AudioRecord? = null
    private var player: MediaPlayer?    = null

    private lateinit var patientId:   String
    private lateinit var groundTruth: String
    private lateinit var gestation:   String

    private var startTimeMs: Long = 0
    private val timerHandler = Handler()
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
                timerView.text = "Recorded: %02d:%02d".format(elapsed / 60, elapsed % 60)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO
            )
        }

        patientId   = intent.getStringExtra("ID")!!
        groundTruth = intent.getStringExtra("GT")!!
        gestation   = intent.getStringExtra("GP")!!
        findViewById<TextView>(R.id.tvInfo).text =
            "PatientID: $patientId   GT: $groundTruth   GW: $gestation"

        timerView       = findViewById(R.id.tvTimer)
        btnRecord       = findViewById(R.id.btnRecord)
        btnStop         = findViewById(R.id.btnStop)
        sampleRateView  = findViewById(R.id.tvSampleRate)

        btnStop.isEnabled = false

        btnRecord.setOnClickListener {
            dialogShown = false
            isRecording = true
            startTimeMs = System.currentTimeMillis()

            startPlayAndRecord()

            btnRecord.isEnabled = false
            btnStop.isEnabled   = true
            timerHandler.post(timerRunnable)
        }

        btnStop.setOnClickListener {
            if (!dialogShown) {
                dialogShown = true
                stopPlayAndRecord()
                showSaveDialog()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPlayAndRecord() {
        try {
            recorder?.release()
            val bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
            ).apply {
                if (state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord init failed")
                }
            }

            val actualRate = recorder!!.sampleRate
            Log.d("RecordActivity", "Requested sample rate: $SAMPLE_RATE, actual: $actualRate")
            runOnUiThread {
                sampleRateView.text = "Requested: $SAMPLE_RATE Hz\nActual: $actualRate Hz"
            }

            recorder!!.startRecording()

            Thread {
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(bufSize)
                while (isRecording) {
                    val read = recorder!!.read(buffer, 0, buffer.size)
                    if (read > 0) baos.write(buffer, 0, read)
                }
                tempPcm = baos.toByteArray()
                baos.close()
            }.start()

        } catch (e: Exception) {
            Toast.makeText(this,
                "Recorder error: ${e.message}", Toast.LENGTH_LONG
            ).show()
            btnRecord.isEnabled = true
            btnStop.isEnabled = false
            return
        }

        try {
            player?.release()
            player = MediaPlayer.create(this, R.raw.tone18khz)
            player?.setOnCompletionListener {
                if (isRecording) {
                    it.seekTo(0)
                    it.start()
                }
            }
            player?.start()
        } catch (e: Exception) {
            Toast.makeText(this,
                "Playback error: ${e.message}", Toast.LENGTH_LONG
            ).show()
            stopPlayAndRecord()
        }
    }

    private fun stopPlayAndRecord() {
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)

        player?.apply {
            try { stop() } catch (_: Throwable) {}
            release()
        }
        recorder?.apply {
            try { stop() } catch (_: Throwable) {}
            release()
        }

        btnRecord.isEnabled = true
        btnStop.isEnabled   = false
    }

    private fun showSaveDialog() {
        runOnUiThread {
            val notesInput = EditText(this).apply {
                hint = "Comments"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            AlertDialog.Builder(this)
                .setTitle("Recording Ended")
                .setView(notesInput)
                .setCancelable(false)
                .setPositiveButton("Save") { d,_ ->
                    saveRecording(notesInput.text.toString(), false)
                    d.dismiss()
                    finish()
                }
                .setNegativeButton("Discard") { d,_ ->
                    d.dismiss()
                }
                .show()
        }
    }

    private fun saveRecording(notes: String, incomplete: Boolean) {
        val ts     = SimpleDateFormat("MMddyyyy_HHmmss", Locale.US).format(Date())
        val suffix = if (incomplete) "_INCOMPLETE" else ""
        val fn     = "PatientID_${patientId}_Timestamp_${ts}${suffix}_18KHz.wav"

        val parentDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Dopfone_Recordings")
        parentDir.mkdirs()
        val outFile = File(parentDir, fn)

        FileOutputStream(outFile).use { fos ->
            fos.write(createWavHeader(
                tempPcm.size.toLong(), SAMPLE_RATE, 1, 16
            ))
            fos.write(tempPcm)
        }
        Toast.makeText(this,
            "Saved: ${outFile.absolutePath}", Toast.LENGTH_LONG
        ).show()

        val csv = File(parentDir, "records.csv")
        val needHeader = !csv.exists() || csv.length() == 0L
        CSVWriter(FileWriter(csv, true)).use { w ->
            if (needHeader) {
                w.writeNext(arrayOf(
                    "Filename","PatientID","GroundTruth","GestationWeek","Notes"
                ))
            }
            w.writeNext(arrayOf(fn, patientId, groundTruth, gestation, notes))
        }

    }

    private fun createWavHeader(
        pcmSize: Long, sampleRate: Int, channels: Int, bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val riffSize = 36 + pcmSize
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(riffSize.toInt())
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(byteRate)
            .putShort((channels * bitsPerSample / 8).toShort())
            .putShort(bitsPerSample.toShort())
            .put("data".toByteArray())
            .putInt(pcmSize.toInt())
            .array()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this,
                "Audio permission is required", Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
}