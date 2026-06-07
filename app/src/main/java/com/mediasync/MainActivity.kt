package com.mediasync

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mediasync.data.SyncDatabase
import com.mediasync.databinding.ActivityMainBinding
import com.mediasync.sync.SyncWorker
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs:   SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            log("Разрешения получены")
            updateStats()
        } else {
            log("⚠ Часть разрешений отклонена")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("mediasync_prefs", MODE_PRIVATE)

        // Restore saved settings
        binding.editHost.setText(prefs.getString("host", "192.168.1.100"))
        binding.editPort.setText(prefs.getInt("port", 9876).toString())
        binding.editUsername.setText(prefs.getString("username", "user"))
        binding.editPassword.setText(prefs.getString("password", ""))

        binding.btnSync.setOnClickListener { startSync() }
        binding.btnPermissions.setOnClickListener { requestMediaPermissions() }
        binding.btnGallery.setOnClickListener { openGallery() }

        observeSyncWork()
        updateStats()

        if (!hasMediaPermissions()) requestMediaPermissions()
    }

    private fun openGallery() {
        val host = prefs.getString("host", "192.168.1.100")
        val token = prefs.getString("admin_token", "CHANGE_ME_admin_token_2026") ?: ""
        val username = prefs.getString("username", "user") ?: ""
        if (host.isNullOrEmpty() || token.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Заполни настройки сервера, имя пользователя и admin_token", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, com.mediasync.GalleryActivity::class.java).apply {
            putExtra("server_host", host)
            putExtra("admin_token", token)
            putExtra("username", username)
        }
        startActivity(intent)
    }

    private fun startSync() {
        val host     = binding.editHost.text.toString().trim()
        val port     = binding.editPort.text.toString().toIntOrNull() ?: 9876
        val username = binding.editUsername.text.toString().trim().ifEmpty { "user" }
        val password = binding.editPassword.text.toString()

        if (host.isEmpty()) {
            Toast.makeText(this, "Укажи IP сервера", Toast.LENGTH_SHORT).show()
            return
        }

        if (TextUtils.isEmpty(password) && prefs.contains("password")) {
            // If password was previously saved, use it
        }

        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("username", username)
            .putString("password", password)
            .apply()

        SyncWorker.schedulePeriodicSync(this, host, port, username, password)
        SyncWorker.syncNow(this, host, port, username, password, days = 0)

        log("Запускаю синхронизацию → $host:$port (user=$username)")
        binding.btnSync.isEnabled = false
    }

    private fun observeSyncWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("mediasync_now")
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe

                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnSync.isEnabled = false

                        val cur  = info.progress.getInt("progress_current", 0)
                        val tot  = info.progress.getInt("progress_total", 0)
                        val name = info.progress.getString("progress_filename") ?: ""
                        if (tot > 0) {
                            binding.textProgress.visibility = View.VISIBLE
                            binding.textProgress.text = "[$cur/$tot] $name"
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.progressBar.visibility  = View.GONE
                        binding.textProgress.visibility = View.GONE
                        binding.btnSync.isEnabled = true

                        val synced  = info.outputData.getInt("synced", 0)
                        val skipped = info.outputData.getInt("skipped", 0)
                        val errors  = info.outputData.getInt("errors", 0)
                        val detail  = info.outputData.getString("error_detail") ?: ""

                        log("✓ Готово: отправлено=$synced пропущено=$skipped ошибок=$errors")
                        if (detail.isNotEmpty()) {
                            log("Ошибки:\n$detail")
                        }
                        updateStats()
                    }
                    WorkInfo.State.FAILED -> {
                        binding.progressBar.visibility  = View.GONE
                        binding.textProgress.visibility = View.GONE
                        binding.btnSync.isEnabled = true
                        val error = info.outputData.getString("error") ?: "неизвестная ошибка"
                        log("✗ Ошибка: $error")
                    }
                    WorkInfo.State.CANCELLED -> {
                        binding.progressBar.visibility  = View.GONE
                        binding.textProgress.visibility = View.GONE
                        binding.btnSync.isEnabled = true
                        log("Синхронизация отменена")
                    }
                    else -> {}
                }
            }
    }

    private fun updateStats() {
        lifecycleScope.launch {
            val dao = SyncDatabase.getInstance(this@MainActivity).syncedFileDao()
            val total  = dao.count()
            val videos = dao.countVideos()
            val photos = dao.countPhotos()
            binding.textStats.text = "В базе: $total файлов ($videos видео / $photos фото)"
        }
    }

    private fun log(message: String) {
        val time = DateFormat.format("HH:mm:ss", Date()).toString()
        val current = binding.textLog.text.toString()
        val newText = "[$time] $message\n$current"
        binding.textLog.text = newText.lines().take(50).joinToString("\n")
    }

    private fun hasMediaPermissions(): Boolean =
        mediaPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestMediaPermissions() = permissionLauncher.launch(mediaPermissions())

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}
