package pt.fslart.bms.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseDate: String? = null,
    val title: String? = null,
    val changelog: String? = null,
    val apkUrl: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val file: File, val info: UpdateInfo) : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val REMOTE_VERSION_URL = "https://raw.githubusercontent.com/FSLART/BMS_UI/android-app/version.json"
    private const val FALLBACK_VERSION_URL = "https://github.com/FSLART/BMS_UI/raw/android-app/version.json"

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun checkForUpdate(context: Context, scope: CoroutineScope, silent: Boolean = true) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            return
        }

        _updateState.value = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            try {
                val jsonString = fetchUrlWithRedirects(REMOTE_VERSION_URL)
                    ?: fetchUrlWithRedirects(FALLBACK_VERSION_URL)

                if (jsonString.isNullOrBlank()) {
                    if (!silent) {
                        _updateState.value = UpdateState.Error("Não foi possível verificar versões no GitHub.")
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                    return@launch
                }

                val updateInfo = Gson().fromJson(jsonString, UpdateInfo::class.java)
                val currentCode = getCurrentVersionCode(context)
                val currentName = getCurrentVersionName(context)

                Log.d(TAG, "Current versionCode: $currentCode, Remote versionCode: ${updateInfo.versionCode}")

                if (updateInfo.versionCode > currentCode) {
                    _updateState.value = UpdateState.Available(updateInfo)
                } else {
                    if (!silent) {
                        _updateState.value = UpdateState.UpToDate(currentName)
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                if (!silent) {
                    _updateState.value = UpdateState.Error("Falha na ligação: ${e.localizedMessage ?: "Erro desconhecido"}")
                } else {
                    _updateState.value = UpdateState.Idle
                }
            }
        }
    }

    fun startDownloadAndInstall(context: Context, scope: CoroutineScope, info: UpdateInfo) {
        _updateState.value = UpdateState.Downloading(0f, 0L, 0L)

        scope.launch(Dispatchers.IO) {
            try {
                val destinationFile = File(context.cacheDir, "LART_BMS_update.apk")
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }

                downloadFile(info.apkUrl, destinationFile) { progress, downloaded, total ->
                    _updateState.value = UpdateState.Downloading(progress, downloaded, total)
                }

                _updateState.value = UpdateState.ReadyToInstall(destinationFile, info)
                withContext(Dispatchers.Main) {
                    installApk(context, destinationFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update", e)
                _updateState.value = UpdateState.Error("Erro ao transferir atualização: ${e.localizedMessage}")
            }
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            val authority = "${context.packageName}.provider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start APK installer", e)
            _updateState.value = UpdateState.Error("Não foi possível iniciar o instalador: ${e.localizedMessage}")
        }
    }

    fun dismiss() {
        _updateState.value = UpdateState.Idle
    }

    private fun fetchUrlWithRedirects(urlString: String, maxRedirects: Int = 5): String? {
        var currentUrl = urlString
        var redirects = 0

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "LART-BMS-Android-App")
                instanceFollowRedirects = false
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val newUrl = connection.getHeaderField("Location")
                if (newUrl != null) {
                    currentUrl = newUrl
                    redirects++
                    connection.disconnect()
                    continue
                }
            }

            if (status == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            }
            connection.disconnect()
            break
        }
        return null
    }

    private fun downloadFile(
        urlString: String,
        targetFile: File,
        onProgress: (Float, Long, Long) -> Unit
    ) {
        var currentUrl = urlString
        var redirects = 0
        var connection: HttpURLConnection? = null
        val maxRedirects = 6

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("User-Agent", "LART-BMS-Android-App")
                instanceFollowRedirects = false
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val newUrl = connection.getHeaderField("Location")
                if (newUrl != null) {
                    currentUrl = newUrl
                    redirects++
                    connection.disconnect()
                    continue
                }
            }
            break
        }

        if (connection == null || connection.responseCode != HttpURLConnection.HTTP_OK) {
            val code = connection?.responseCode ?: -1
            throw IllegalStateException("Servidor devolveu código HTTP $code ao descarregar APK.")
        }

        val totalLength = connection.contentLengthLong
        var downloadedBytes = 0L

        connection.inputStream.use { input: InputStream ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var lastProgressUpdate = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100 || downloadedBytes == totalLength) {
                        lastProgressUpdate = now
                        val pct = if (totalLength > 0) downloadedBytes.toFloat() / totalLength else 0f
                        onProgress(pct, downloadedBytes, totalLength)
                    }
                }
            }
        }
    }
}
