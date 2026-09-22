package com.algotix.wa_hub

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything WhatsApp Web needs from the device: attaching files, taking a photo,
 * recording a voice note, sharing location, and saving media it hands back.
 *
 * Without this, the attach button silently does nothing — the single most common
 * way a WebView-hosted WhatsApp feels broken.
 */
class MediaDelegate(private val activity: Activity) {

    private val nextCode = AtomicInteger(9000)

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fileRequestCode = 0
    private var cameraOutput: Uri? = null

    private val pendingPermissions = HashMap<Int, (granted: Boolean) -> Unit>()

    // ---- File chooser ----------------------------------------------------

    fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        fileRequestCode = nextCode.incrementAndGet()
        cameraOutput = null

        val content = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        val extras = buildCaptureIntents(params)
        val chooser = Intent.createChooser(content, params.title ?: "Choose a file").apply {
            // Kotlin arrays are invariant, so an Array<Intent> will not satisfy
            // the Array<Parcelable> this extra is read back as.
            if (extras.isNotEmpty()) {
                putExtra(
                    Intent.EXTRA_INITIAL_INTENTS,
                    Array<Parcelable>(extras.size) { extras[it] },
                )
            }
        }

        return try {
            activity.startActivityForResult(chooser, fileRequestCode)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    /** Offers "take a photo" / "record a video" alongside the file picker, like Chrome does. */
    private fun buildCaptureIntents(params: WebChromeClient.FileChooserParams): List<Intent> {
        val accepts = params.acceptTypes.orEmpty().joinToString(",")
        val wantsImage = accepts.isEmpty() || accepts.contains("image") || accepts.contains("*/*")
        val wantsVideo = accepts.isEmpty() || accepts.contains("video") || accepts.contains("*/*")
        if (!hasPermission(Manifest.permission.CAMERA)) return emptyList()

        val out = ArrayList<Intent>(2)
        if (wantsImage) {
            val file = File(cacheDir(), "capture-${System.currentTimeMillis()}.jpg")
            val uri = contentUriFor(file)
            cameraOutput = uri
            out += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        if (wantsVideo) {
            out += Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        }
        return out.filter { it.resolveActivity(activity.packageManager) != null }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != fileRequestCode) return false
        val callback = fileCallback ?: return true
        fileCallback = null

        if (resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return true
        }

        val uris = when {
            data?.clipData != null -> {
                val clip = data.clipData!!
                (0 until clip.itemCount).map { clip.getItemAt(it).uri }.toTypedArray()
            }
            data?.data != null -> arrayOf(data.data!!)
            cameraOutput != null -> arrayOf(cameraOutput!!)
            else -> null
        }
        callback.onReceiveValue(uris)
        return true
    }

    // ---- Web permissions -------------------------------------------------

    /** Maps a page's getUserMedia request onto the matching Android runtime permission. */
    fun resolveWebPermission(request: PermissionRequest) {
        val wanted = request.resources.orEmpty()
        val needed = LinkedHashSet<String>()
        if (wanted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (wanted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            needed += Manifest.permission.CAMERA
        }
        if (needed.isEmpty()) {
            request.deny()
            return
        }
        requestPermissions(needed.toTypedArray()) { granted ->
            activity.runOnUiThread {
                if (granted) request.grant(wanted) else request.deny()
            }
        }
    }

    fun resolveGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted ->
            activity.runOnUiThread { callback.invoke(origin, granted, false) }
        }
    }

    fun requestPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        val code = nextCode.incrementAndGet()
        pendingPermissions[code] = onResult
        ActivityCompat.requestPermissions(activity, missing.toTypedArray(), code)
    }

    fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ): Boolean {
        val handler = pendingPermissions.remove(code) ?: return false
        handler(results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED })
        return true
    }

    private fun hasPermission(name: String) =
        ContextCompat.checkSelfPermission(activity, name) == PackageManager.PERMISSION_GRANTED

    // ---- Saving media ----------------------------------------------------

    /** Writes a blob WhatsApp handed us into the shared Downloads collection. */
    fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Boolean = runCatching {
        val safeName = name.ifBlank { "whatsapp-file" }.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val resolvedMime = mime.ifBlank {
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(safeName.substringAfterLast('.', "")) ?: "application/octet-stream"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            FileOutputStream(File(dir, safeName)).use { it.write(bytes) }
        }
        true
    }.getOrDefault(false)

    // ---- Links -----------------------------------------------------------

    fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can open that link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cacheDir(): File =
        File(activity.cacheDir, "captures").apply { mkdirs() }

    private fun contentUriFor(file: File): Uri = FileProvider.getUriForFile(
        activity, "${activity.packageName}.fileprovider", file
    )
}
