package com.mediasync

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.ImageLoader
import coil.load
import com.mediasync.api.*
import com.mediasync.databinding.ActivityGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    internal lateinit var galleryApi: GalleryApi
    internal lateinit var imageLoader: ImageLoader
    private val adapter = GalleryAdapter()
    private var currentPage = 0
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intentHost = intent.getStringExtra("server_host")
        val intentUsername = intent.getStringExtra("username")
        val intentToken = intent.getStringExtra("admin_token")

        val prefs = getSharedPreferences("mediasync_prefs", MODE_PRIVATE)
        val host = intentHost ?: prefs.getString("host", "192.168.1.100")!!
        val port = prefs.getInt("port", 9877)
        val username = intentUsername ?: prefs.getString("username", "user")!!
        val token = intentToken ?: prefs.getString("admin_token", "CHANGE_ME_admin_token_2026")!!

        galleryApi = GalleryApi(host, port, token, username)

        // Coil ImageLoader with auth interceptor
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .build()

        imageLoader = ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .build()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { refreshGallery() }

        binding.storageInfo.setOnClickListener { refreshStorage() }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val layoutManager = rv.layoutManager as GridLayoutManager
                val visible = layoutManager.findLastCompletelyVisibleItemPosition()
                val total = layoutManager.itemCount
                if (visible >= total - 6 && !isLoading) {
                    loadMoreIfNeeded()
                }
            }
        })

        refreshGallery()
        refreshStorage()
    }

    private fun refreshGallery() {
        currentPage = 0
        adapter.items.clear()
        loadGalleryPage()
    }

    private fun loadGalleryPage() {
        if (isLoading) return
        isLoading = true

        lifecycleScope.launch(Dispatchers.IO) {
            val response = galleryApi.fetchGallery(page = currentPage, limit = 50)
            withContext(Dispatchers.Main) {
                isLoading = false
                binding.swipeRefresh.isRefreshing = false

                if (response != null) {
                    adapter.items.addAll(response.files)
                    adapter.notifyDataSetChanged()
                    currentPage++
                    binding.emptyView.visibility =
                        if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(this@GalleryActivity, "Не удалось загрузить галерею", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshStorage() {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = galleryApi.fetchStorage()
            withContext(Dispatchers.Main) {
                if (storage != null) {
                    binding.storageInfo.text = "Хранилище: ${storage.used_human} | ${storage.file_count} файлов"
                } else {
                    binding.storageInfo.text = "Хранилище: не доступно"
                }
            }
        }
    }

    private fun loadMoreIfNeeded() {
        if (!isLoading) loadGalleryPage()
    }

    fun downloadFile(item: GalleryItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "Нет доступа к хранилищу", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val destFile = File(downloadsDir, item.original_name)
            val result = galleryApi.downloadFile(item.sha256, destFile)

            withContext(Dispatchers.Main) {
                when (result) {
                    is DownloadResult.Success -> Toast.makeText(this@GalleryActivity, "Сохранено: ${item.original_name}", Toast.LENGTH_SHORT).show()
                    is DownloadResult.Error -> Toast.makeText(this@GalleryActivity, "Ошибка: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// ── Adapter ──────────────────────────────────────────────────────────────

class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {
    val items = mutableListOf<GalleryItem>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.imageThumbnail)
        val name: TextView = view.findViewById(R.id.textFileName)
        val date: TextView = view.findViewById(R.id.textFileDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val activity = holder.itemView.context as GalleryActivity

        val url = activity.galleryApi.getThumbnailUrl(item.sha256)
        holder.thumbnail.load(url, activity.imageLoader) {
            placeholder(R.drawable.ic_image_placeholder)
            error(R.drawable.ic_broken_image)
            crossfade(true)
        }

        holder.name.text = truncateName(item.original_name)
        holder.date.text = formatDate(item.shot_at)

        holder.itemView.setOnClickListener { activity.downloadFile(item) }
        holder.itemView.setOnLongClickListener { activity.downloadFile(item); true }
    }

    private fun truncateName(name: String): String {
        return if (name.length > 15) name.take(12) + "..." else name
    }
}

private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
private fun formatDate(ts: Long) = if (ts > 0) dateFormat.format(Date(ts * 1000)) else "—"
