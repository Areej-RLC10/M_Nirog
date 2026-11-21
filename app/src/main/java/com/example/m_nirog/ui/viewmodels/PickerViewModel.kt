package com.example.m_nirog.ui.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.core.SessionStore
import com.example.m_nirog.models.City
import com.example.m_nirog.models.PickerJobStatus
import com.example.m_nirog.models.PickerPickup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PickerUiState(
    val pickups: List<PickerPickup> = demoPickups(),
    val busy: Boolean = false,
    val liveMode: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    fun find(id: String): PickerPickup? = pickups.firstOrNull { it.id == id }
}

private fun demoPickups() = listOf(
    PickerPickup(
        id = "pk-101",
        bookingId = "demo-verify",
        patientName = "Sita Sharma",
        patientPhone = "+9779841000001",
        packageName = "Basic Health",
        dateLabel = "Wed 25 Nov",
        slotLabel = "07:00-09:00",
        address = "Jhamsikhel, Patan",
        city = City.KTM,
    ),
    PickerPickup(
        id = "pk-102",
        bookingId = "demo-done",
        patientName = "Ram Gurung",
        patientPhone = "+9779841000002",
        packageName = "Basic Health",
        dateLabel = "Wed 25 Nov",
        slotLabel = "09:00-11:00",
        address = "Lakeside Road 6",
        city = City.PKR,
    ),
)

internal class PickerController(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val isStaffSession: () -> Boolean,
    private val readUriBytes: suspend (String) -> ByteArray,
    private val encodeBase64: (ByteArray) -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(PickerUiState())
    val state: StateFlow<PickerUiState> = _state.asStateFlow()

    fun load() {
        if (!isStaffSession()) {
            _state.update { it.copy(pickups = demoPickups(), liveMode = false, error = null) }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, liveMode = true, error = null) }
            runCatching { parseList(JSONArray(api.get("/picker/pickups"))) }
                .onSuccess { list -> _state.update { it.copy(pickups = list, busy = false, liveMode = true) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Failed to load pickups") } }
        }
    }

    fun assignPaidBookings() {
        if (!_state.value.liveMode) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { parseList(JSONArray(api.post("/picker/assign-paid", JSONObject()))) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(pickups = list, busy = false, message = "Checked & assigned paid bookings.")
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun markArrived(pickupId: String) {
        if (!_state.value.liveMode) {
            updatePickup(pickupId) {
                it.copy(status = PickerJobStatus.ARRIVED, arrivedAtMillis = nowMs())
            }
            _state.update { it.copy(error = null, message = "Arrival recorded. 15-minute wait timer started.") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { parsePickup(JSONObject(api.post("/picker/pickups/$pickupId/arrive", JSONObject()))) }
                .onSuccess { updated ->
                    val existing = _state.value.find(pickupId)
                    replacePickup(updated.copy(noShowPhotoUri = updated.noShowPhotoUri ?: existing?.noShowPhotoUri))
                    _state.update { it.copy(busy = false, message = "Arrival saved. 15-minute wait timer started.") }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun devCompleteWait(pickupId: String) {
        if (!DevConfig.ENABLED) return
        if (!_state.value.liveMode) {
            updatePickup(pickupId) {
                it.copy(
                    status = PickerJobStatus.ARRIVED,
                    arrivedAtMillis = nowMs() - 16 * 60 * 1000L,
                )
            }
            _state.update { it.copy(error = null, message = "Dev: 15-minute wait completed.") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { parsePickup(JSONObject(api.post("/picker/pickups/$pickupId/dev-complete-wait", JSONObject()))) }
                .onSuccess { updated ->
                    val existing = _state.value.find(pickupId)
                    replacePickup(updated.copy(noShowPhotoUri = updated.noShowPhotoUri ?: existing?.noShowPhotoUri))
                    _state.update { it.copy(busy = false, message = "Dev: 15-minute wait completed on server.") }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun collectSample(pickupId: String, barcode: String) {
        val code = barcode.trim()
        if (code.length < 4) {
            _state.update { it.copy(error = "Scan or enter a valid sample tube barcode.") }
            return
        }
        if (!_state.value.liveMode) {
            updatePickup(pickupId) { it.copy(status = PickerJobStatus.COLLECTED, sampleCode = code) }
            _state.update { it.copy(error = null, message = "Sample $code marked as collected.") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val body = JSONObject().put("sampleCode", code)
                parsePickup(JSONObject(api.post("/picker/pickups/$pickupId/collect", body)))
            }.onSuccess { updated ->
                replacePickup(updated)
                _state.update { it.copy(busy = false, message = "Sample $code collected & saved to database.") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not save sample collection") }
            }
        }
    }

    suspend fun fetchNoShowPhotoBytes(pickupId: String): ByteArray? {
        if (!_state.value.liveMode) return null
        return runCatching { api.getBytes("/picker/pickups/$pickupId/photo").second }.getOrNull()
    }

    fun attachNoShowPhoto(pickupId: String, uri: String) {
        updatePickup(pickupId) { it.copy(noShowPhotoUri = uri) }
        _state.update { it.copy(error = null, message = "Doorway photo proof attached.") }
    }

    fun markNoShow(pickupId: String) {
        val job = _state.value.find(pickupId) ?: return
        val arrived = job.arrivedAtMillis
        val waitedMs = if (arrived == null) 0L else nowMs() - arrived
        if (waitedMs < 15 * 60 * 1000L) {
            _state.update { it.copy(error = "You must wait 15 minutes before marking no-show.") }
            return
        }
        val photoUri = job.noShowPhotoUri
        if (photoUri.isNullOrBlank()) {
            _state.update { it.copy(error = "Attach doorway photo proof first.") }
            return
        }
        if (!_state.value.liveMode) {
            updatePickup(pickupId) { it.copy(status = PickerJobStatus.NO_SHOW) }
            _state.update { it.copy(error = null, message = "Marked as patient no-show.") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val bytes = readUriBytes(photoUri)
                val b64 = encodeBase64(bytes)
                val body = JSONObject().put("photoBase64", b64)
                parsePickup(JSONObject(api.post("/picker/pickups/$pickupId/no-show", body)))
            }.onSuccess { updated ->
                replacePickup(updated.copy(noShowPhotoUri = photoUri))
                _state.update { it.copy(busy = false, message = "Patient no-show & doorway photo saved to server.") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not submit no-show") }
            }
        }
    }

    fun clearFeedback() = _state.update { it.copy(error = null, message = null) }

    fun reset() {
        _state.value = PickerUiState(pickups = if (DevConfig.ENABLED) demoPickups() else emptyList())
    }

    private fun replacePickup(updated: PickerPickup) {
        updatePickup(updated.id) { updated }
    }

    private fun updatePickup(id: String, change: (PickerPickup) -> PickerPickup) {
        _state.update { s ->
            s.copy(pickups = s.pickups.map { if (it.id == id) change(it) else it })
        }
    }

    private fun parseList(arr: JSONArray): List<PickerPickup> = buildList {
        for (i in 0 until arr.length()) add(parsePickup(arr.getJSONObject(i)))
    }

    private fun parsePickup(o: JSONObject): PickerPickup {
        val status = runCatching { PickerJobStatus.valueOf(o.getString("status")) }
            .getOrDefault(PickerJobStatus.ASSIGNED)
        val city = if (o.getString("city") == "PKR") City.PKR else City.KTM
        val arrived = if (o.isNull("arrivedAtMs")) null else o.optLong("arrivedAtMs")
        val code = if (o.isNull("sampleCode")) null else o.optString("sampleCode").takeIf { it.isNotBlank() }
        val photo = if (o.optBoolean("hasPhotoProof", false)) "server-uploaded" else null
        return PickerPickup(
            id = o.getString("id"),
            bookingId = o.getString("bookingId"),
            patientName = o.getString("patientName"),
            patientPhone = o.getString("patientPhone"),
            packageName = o.getString("packageName"),
            dateLabel = o.getString("dateLabel"),
            slotLabel = o.getString("slotLabel"),
            address = o.getString("address"),
            city = city,
            status = status,
            sampleCode = code,
            arrivedAtMillis = arrived,
            noShowPhotoUri = photo,
        )
    }
}

class PickerViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val store = SessionStore(app)
    private val controller = PickerController(
        scope = viewModelScope,
        api = ApiClient(store),
        isStaffSession = { store.role() == "PICKER" || store.role() == "ADMIN" },
        readUriBytes = { uri ->
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            } ?: throw IllegalStateException("Could not read doorway photo.")
        },
        encodeBase64 = { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) },
    )

    val state: StateFlow<PickerUiState> = controller.state

    fun load() = controller.load()
    fun assignPaidBookings() = controller.assignPaidBookings()
    fun markArrived(pickupId: String) = controller.markArrived(pickupId)
    fun devCompleteWait(pickupId: String) = controller.devCompleteWait(pickupId)
    fun collectSample(pickupId: String, barcode: String) = controller.collectSample(pickupId, barcode)
    suspend fun fetchNoShowPhotoBytes(pickupId: String): ByteArray? = controller.fetchNoShowPhotoBytes(pickupId)
    fun attachNoShowPhoto(pickupId: String, uri: String) = controller.attachNoShowPhoto(pickupId, uri)
    fun markNoShow(pickupId: String) = controller.markNoShow(pickupId)
    fun clearFeedback() = controller.clearFeedback()
    fun reset() = controller.reset()
}
