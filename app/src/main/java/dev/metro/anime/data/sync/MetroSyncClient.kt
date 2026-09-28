package dev.metro.anime.data.sync

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.model.WatchProgress
import dev.metro.anime.data.repository.AnimeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class MetroSyncClient(
    private val context: Context,
    private val animeRepo: AnimeRepository,
) {
    companion object {
        private const val TAG = "MetroSyncClient"
        private const val PREFS_NAME = "metro_sync_prefs"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "sync_token"
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_LAST_STATUS = "last_sync_status"
        private const val DEFAULT_PORT = 8765
        private const val UDP_DISCOVERY_PORT = 8766
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _isPaired = MutableStateFlow(!prefs.getString(KEY_TOKEN, null).isNullOrBlank())
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastStatus = MutableStateFlow(
        prefs.getString(KEY_LAST_STATUS, "Не привязано к ПК") ?: "Не привязано к ПК"
    )
    val lastStatus: StateFlow<String> = _lastStatus.asStateFlow()

    private val _serverHost = MutableStateFlow(prefs.getString(KEY_HOST, "") ?: "")
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    private val _autoSyncEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SYNC, true))
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
        _autoSyncEnabled.value = enabled
    }

    /**
     * Sends UDP broadcast to locate Desktop Metro Anime in the local Wi-Fi / LAN network.
     */
    suspend fun discoverServer(timeoutMs: Long = 3000): Pair<String, Int>? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = timeoutMs.toInt()

            val msg = "METRO_DISCOVER"
            val buffer = msg.toByteArray()

            // 1. Global broadcast
            try {
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(buffer, buffer.size, broadcastAddr, UDP_DISCOVERY_PORT))
            } catch (_: Exception) {}

            // 2. Subnet broadcasts on active Wi-Fi interfaces
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val broadcast = interfaceAddress.broadcast ?: continue
                        socket.send(DatagramPacket(buffer, buffer.size, broadcast, UDP_DISCOVERY_PORT))
                    }
                }
            } catch (_: Exception) {}

            val receiveData = ByteArray(512)
            val receivePacket = DatagramPacket(receiveData, receiveData.size)
            socket.receive(receivePacket)

            val reply = String(receivePacket.data, 0, receivePacket.length).trim()
            Log.d(TAG, "Discovery reply: $reply from ${receivePacket.address.hostAddress}")

            if (reply.startsWith("METRO_OFFER:")) {
                val host = receivePacket.address.hostAddress ?: return@withContext null
                val parts = reply.split(":")
                val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
                return@withContext Pair(host, port)
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Discovery timed out or error: ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }

    /**
     * Pair with Desktop using a 4-digit PIN.
     */
    suspend fun pairWithPin(host: String, port: Int = DEFAULT_PORT, pin: String): Result<String> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            val payload = JsonObject().apply {
                addProperty("pin", pin)
                val bookmarks = animeRepo.getAllBookmarks()
                val history = animeRepo.getAllHistory()
                addProperty("bookmarksJson", gson.toJson(bookmarks))
                addProperty("historyJson", gson.toJson(history))
            }

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("http://$host:$port/sync")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errMsg = if (response.code == 401) "Неверный PIN-код" else "Ошибка сервера: ${response.code}"
                    updateStatus(errMsg)
                    return@withContext Result.failure(Exception(errMsg))
                }

                val respStr = response.body?.string() ?: return@withContext Result.failure(Exception("Пустой ответ от сервера"))
                val respJson = gson.fromJson(respStr, JsonObject::class.java)

                val token = respJson.get("token")?.asString
                if (token.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("Сервер не вернул токен авторизации"))
                }

                applyMergedData(respJson)

                prefs.edit()
                    .putString(KEY_HOST, host)
                    .putInt(KEY_PORT, port)
                    .putString(KEY_TOKEN, token)
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply()

                _serverHost.value = host
                _isPaired.value = true
                val msg = "Успешно привязано к ПК ($host)"
                updateStatus(msg)
                Result.success(msg)
            }
        } catch (e: Exception) {
            val err = "Ошибка подключения: ${e.localizedMessage ?: e.message}"
            updateStatus(err)
            Result.failure(Exception(err))
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Sync history and bookmarks with Desktop.
     */
    suspend fun sync(explicitHost: String? = null, explicitPort: Int? = null): Result<String> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            var targetHost = explicitHost ?: prefs.getString(KEY_HOST, null)
            var targetPort = explicitPort ?: prefs.getInt(KEY_PORT, DEFAULT_PORT)
            val token = prefs.getString(KEY_TOKEN, null)

            if (token.isNullOrBlank()) {
                val err = "Приложение еще не привязано к ПК. Введите PIN."
                updateStatus(err)
                return@withContext Result.failure(Exception(err))
            }

            // If targetHost is null or blank, try discovery
            if (targetHost.isNullOrBlank()) {
                val found = discoverServer(1500)
                if (found != null) {
                    targetHost = found.first
                    targetPort = found.second
                } else {
                    val err = "ПК не найден в локальной сети"
                    updateStatus(err)
                    return@withContext Result.failure(Exception(err))
                }
            }

            var result = doSyncRequest(targetHost, targetPort, token)

            // If connection failed, perhaps Desktop's IP address changed via DHCP: try auto-discovery
            if (result.isFailure && explicitHost == null) {
                val redisovered = discoverServer(1500)
                if (redisovered != null && redisovered.first != targetHost) {
                    targetHost = redisovered.first
                    targetPort = redisovered.second
                    result = doSyncRequest(targetHost, targetPort, token)
                    if (result.isSuccess) {
                        prefs.edit().putString(KEY_HOST, targetHost).putInt(KEY_PORT, targetPort).apply()
                        _serverHost.value = targetHost
                    }
                }
            }

            result
        } finally {
            _isSyncing.value = false
        }
    }

    private fun doSyncRequest(host: String, port: Int, token: String): Result<String> {
        return try {
            val payload = JsonObject().apply {
                addProperty("token", token)
                val bookmarks = animeRepo.getAllBookmarks()
                val history = animeRepo.getAllHistory()
                addProperty("bookmarksJson", gson.toJson(bookmarks))
                addProperty("historyJson", gson.toJson(history))
            }

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("http://$host:$port/sync")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errMsg = if (response.code == 401) {
                        _isPaired.value = false
                        prefs.edit().remove(KEY_TOKEN).apply()
                        "Привязка аннулирована на ПК. Введите новый PIN."
                    } else {
                        "Ошибка сервера: ${response.code}"
                    }
                    updateStatus(errMsg)
                    return Result.failure(Exception(errMsg))
                }

                val respStr = response.body?.string() ?: return Result.failure(Exception("Пустой ответ от сервера"))
                val respJson = gson.fromJson(respStr, JsonObject::class.java)

                applyMergedData(respJson)

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val successMsg = "Синхронизировано в $timeStr"
                updateStatus(successMsg)
                Result.success(successMsg)
            }
        } catch (e: Exception) {
            val err = "Ошибка сети: ${e.localizedMessage ?: e.message}"
            updateStatus(err)
            Result.failure(Exception(err))
        }
    }

    private fun applyMergedData(root: JsonObject) {
        val listTypeB = object : TypeToken<List<AnimeTitle>>() {}.type
        val mergedBookmarks: List<AnimeTitle> = when {
            root.has("bookmarksJson") && !root.get("bookmarksJson").isJsonNull -> {
                gson.fromJson(root.get("bookmarksJson").asString, listTypeB) ?: emptyList()
            }
            root.has("bookmarks") && root.get("bookmarks").isJsonArray -> {
                gson.fromJson(root.get("bookmarks"), listTypeB) ?: emptyList()
            }
            else -> animeRepo.getAllBookmarks()
        }

        val listTypeH = object : TypeToken<List<WatchProgress>>() {}.type
        val mergedHistory: List<WatchProgress> = when {
            root.has("historyJson") && !root.get("historyJson").isJsonNull -> {
                gson.fromJson(root.get("historyJson").asString, listTypeH) ?: emptyList()
            }
            root.has("history") && root.get("history").isJsonArray -> {
                gson.fromJson(root.get("history"), listTypeH) ?: emptyList()
            }
            else -> animeRepo.getAllHistory()
        }

        animeRepo.restoreData(mergedBookmarks, mergedHistory)
    }

    /**
     * Silent, non-blocking auto-sync executed on launch / onResume.
     */
    suspend fun autoSyncIfPaired() = withContext(Dispatchers.IO) {
        if (!_isPaired.value || !_autoSyncEnabled.value) return@withContext
        try {
            sync()
        } catch (e: Exception) {
            Log.d(TAG, "Silent auto-sync skipped: ${e.message}")
        }
    }

    fun unpair() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .remove(KEY_LAST_SYNC)
            .apply()
        _isPaired.value = false
        _serverHost.value = ""
        updateStatus("Связь с ПК разорвана")
    }

    private fun updateStatus(status: String) {
        prefs.edit().putString(KEY_LAST_STATUS, status).apply()
        _lastStatus.value = status
    }
}
