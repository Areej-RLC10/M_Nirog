package com.example.m_nirog.ui.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.PdfHelper
import com.example.m_nirog.core.SessionStore
import com.example.m_nirog.models.City
import com.example.m_nirog.models.HealthRecord
import com.example.m_nirog.models.NotificationItem
import com.example.m_nirog.models.PatientProfile
import com.example.m_nirog.models.RecordType
import com.example.m_nirog.models.SavedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ProfileUiState(
    val profile: PatientProfile = PatientProfile(),
    val records: List<HealthRecord> = emptyList(),
    val notifications: List<NotificationItem> = emptyList(),
    val unreadNotificationsCount: Int = 0,
    val pushTestMessage: String? = null,
    val selectedFileName: String? = null,
    val selectedFileUri: String? = null,
    val saved: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val api = ApiClient(SessionStore(app))

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    fun onName(v: String) = _state.update { it.copy(profile = it.profile.copy(fullName = v), statusMessage = null) }
    fun onDob(v: String) = _state.update { it.copy(profile = it.profile.copy(dob = v), statusMessage = null) }
    fun onGender(v: String) = _state.update { it.copy(profile = it.profile.copy(gender = v), statusMessage = null) }

    fun load() {
        viewModelScope.launch {
            runCatching {
                val pJson = JSONObject(api.get("/me/profile"))
                val rArr = JSONArray(api.get("/me/records"))
                parseProfile(pJson) to parseRecords(rArr)
            }.onSuccess { (profile, records) ->
                _state.update { it.copy(profile = profile, records = records, error = null) }
            }
        }
        loadNotifications()
    }

    fun loadNotifications() {
        viewModelScope.launch {
            runCatching {
                val res = JSONObject(api.get("/me/notifications"))
                val unread = res.optInt("unreadCount", 0)
                val arr = res.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val bId = if (o.isNull("bookingId")) null else o.optString("bookingId").takeIf { it.isNotBlank() }
                        add(
                            NotificationItem(
                                id = o.getString("id"),
                                bookingId = bId,
                                title = o.getString("title"),
                                body = o.getString("body"),
                                category = o.getString("category"),
                                isRead = o.optBoolean("isRead", false),
                                createdAtMs = o.optLong("createdAtMs", 0L),
                            ),
                        )
                    }
                }
                unread to items
            }.onSuccess { (unread, items) ->
                _state.update { it.copy(unreadNotificationsCount = unread, notifications = items) }
            }
        }
    }

    fun markNotificationRead(notifId: String) {
        viewModelScope.launch {
            runCatching {
                api.post("/me/notifications/$notifId/read", JSONObject())
            }.onSuccess {
                _state.update { s ->
                    val updated = s.notifications.map {
                        if (it.id == notifId) it.copy(isRead = true) else it
                    }
                    s.copy(
                        notifications = updated,
                        unreadNotificationsCount = updated.count { !it.isRead },
                    )
                }
            }
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            runCatching {
                api.post("/me/notifications/read-all", JSONObject())
            }.onSuccess {
                _state.update { s ->
                    s.copy(
                        unreadNotificationsCount = 0,
                        notifications = s.notifications.map { it.copy(isRead = true) },
                    )
                }
            }
        }
    }

    fun sendTestPush() {
        viewModelScope.launch {
            _state.update { it.copy(pushTestMessage = "Sending test push...") }
            runCatching {
                JSONObject(api.post("/me/notifications/test", JSONObject()))
            }.onSuccess { res ->
                val detail = res.optString("detail", "Done")
                _state.update { it.copy(pushTestMessage = detail) }
                loadNotifications()
            }.onFailure { e ->
                _state.update { it.copy(pushTestMessage = e.message ?: "Test push failed") }
            }
        }
    }

    fun saveProfile() {
        val p = _state.value.profile
        val name = p.fullName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(error = "Full name is required.", statusMessage = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, statusMessage = null) }
            runCatching {
                val body = JSONObject()
                    .put("fullName", name)
                    .put("dob", p.dob.trim())
                    .put("gender", p.gender.trim())
                parseProfile(JSONObject(api.put("/me/profile", body)))
            }.onSuccess { updated ->
                _state.update { it.copy(profile = updated, busy = false, statusMessage = "Profile saved") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not save profile") }
            }
        }
    }

    fun addAddress(cityInput: String, street: String) {
        val city = when {
            cityInput.trim().equals("Kathmandu", ignoreCase = true) -> City.KTM
            cityInput.trim().equals("Pokhara", ignoreCase = true) -> City.PKR
            else -> {
                _state.update { it.copy(error = "Service is not available in ${cityInput.trim()} yet.") }
                return
            }
        }
        if (street.isBlank()) {
            _state.update { it.copy(error = "Please enter an address.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val body = JSONObject()
                    .put("city", city.code)
                    .put("street", street.trim())
                    .put("landmark", "")
                val res = JSONObject(api.post("/me/addresses", body))
                SavedAddress(
                    id = res.getString("id"),
                    city = if (res.getString("city") == "PKR") City.PKR else City.KTM,
                    street = res.getString("street"),
                )
            }.onSuccess { savedAddr ->
                _state.update { s ->
                    s.copy(
                        profile = s.profile.copy(addresses = listOf(savedAddr)),
                        busy = false,
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not save address") }
            }
        }
    }

    fun onFilePicked(fileName: String, fileUri: String? = null) {
        _state.update { it.copy(selectedFileName = fileName, selectedFileUri = fileUri, error = null) }
    }

    fun saveRecord(title: String, type: RecordType, date: String) {
        if (title.isBlank() || date.isBlank()) {
            _state.update { it.copy(error = "Title and date are required.") }
            return
        }
        val uriRaw = _state.value.selectedFileUri
        val fileName = _state.value.selectedFileName.orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                var mime = "application/octet-stream"
                var b64 = ""
                if (!uriRaw.isNullOrBlank()) {
                    val uri = Uri.parse(uriRaw)
                    val readResult = withContext(Dispatchers.IO) {
                        val detectedMime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        detectedMime to bytes
                    }
                    if (readResult.second.size > 6 * 1024 * 1024) {
                        throw IllegalStateException("File too large (max 6 MB).")
                    }
                    mime = readResult.first
                    if (readResult.second.isNotEmpty()) {
                        b64 = Base64.encodeToString(readResult.second, Base64.NO_WRAP)
                    }
                }
                val body = JSONObject()
                    .put("title", title.trim())
                    .put("recordType", type.name)
                    .put("recordDate", date.trim())
                    .put("fileName", fileName)
                    .put("mimeType", mime)
                    .put("fileBase64", b64)
                parseRecord(JSONObject(api.post("/me/records", body)))
            }.onSuccess { rec ->
                _state.update { s ->
                    s.copy(
                        records = listOf(rec) + s.records,
                        selectedFileName = null,
                        selectedFileUri = null,
                        saved = true,
                        busy = false,
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not save record") }
            }
        }
    }

    fun openRecordFile(rec: HealthRecord, onReady: (Uri, String) -> Unit) {
        val raw = rec.fileUrl ?: return
        if (raw.startsWith("content://")) {
            val uri = Uri.parse(raw)
            val mime = appContext.contentResolver.getType(uri) ?: "*/*"
            onReady(uri, mime)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val (mime, bytes) = api.getBytes(raw)
                val ext = when {
                    mime.contains("pdf", ignoreCase = true) -> "pdf"
                    mime.contains("png", ignoreCase = true) -> "png"
                    mime.contains("image", ignoreCase = true) -> "jpg"
                    else -> "bin"
                }
                val safeTitle = rec.title.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "record" }
                val localUri = withContext(Dispatchers.IO) {
                    PdfHelper.saveDownloadedFile(appContext, "$safeTitle-${rec.id.take(6)}.$ext", mime, bytes)
                } ?: throw IllegalStateException("Could not save downloaded file.")
                localUri to mime
            }.onSuccess { (uri, mime) ->
                _state.update { it.copy(busy = false) }
                onReady(uri, mime)
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not open file") }
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }
    fun reset() { _state.value = ProfileUiState() }

    private fun parseProfile(o: JSONObject): PatientProfile {
        val addrArr = o.optJSONArray("addresses")
        val addresses = buildList {
            if (addrArr != null) {
                for (i in 0 until addrArr.length()) {
                    val a = addrArr.getJSONObject(i)
                    add(
                        SavedAddress(
                            id = a.getString("id"),
                            city = if (a.getString("city") == "PKR") City.PKR else City.KTM,
                            street = a.getString("street"),
                        )
                    )
                }
            }
        }
        return PatientProfile(
            fullName = o.optString("fullName", ""),
            dob = o.optString("dob", ""),
            gender = o.optString("gender", ""),
            addresses = addresses,
            phone = o.optString("phone", ""),
        )
    }

    private fun parseRecords(arr: JSONArray): List<HealthRecord> = buildList {
        for (i in 0 until arr.length()) add(parseRecord(arr.getJSONObject(i)))
    }

    private fun parseRecord(o: JSONObject): HealthRecord {
        val type = runCatching { RecordType.valueOf(o.getString("recordType")) }.getOrDefault(RecordType.OTHER)
        val url = if (o.isNull("fileUrl")) null else o.optString("fileUrl").takeIf { it.isNotBlank() }
        return HealthRecord(
            id = o.getString("id"),
            title = o.getString("title"),
            type = type,
            dateLabel = o.getString("recordDate"),
            fileUrl = url,
        )
    }
}
