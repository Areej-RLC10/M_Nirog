package com.example.m_nirog.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.SessionStore
import com.example.m_nirog.models.LabValue
import com.example.m_nirog.models.ReportDetail
import com.example.m_nirog.models.ResultFlag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ReportUiState(
    val report: ReportDetail? = null,
    val verifiedDone: Boolean = false,
    val flaggedDone: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class ReportViewModel(app: Application) : AndroidViewModel(app) {
    private val api = ApiClient(SessionStore(app))
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    fun load(bookingId: String) {
        _state.update { it.copy(verifiedDone = false, flaggedDone = false, error = null) }
        if (bookingId.startsWith("demo-")) {
            _state.update { it.copy(report = fallbackReport(bookingId), busy = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { parseReport(JSONObject(api.get("/bookings/$bookingId/report"))) }
                .onSuccess { report -> _state.update { it.copy(report = report, busy = false) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Failed to load report") } }
        }
    }

    fun flagValue(valueId: String, note: String) {
        val trimmed = note.trim()
        if (trimmed.isBlank()) {
            _state.update { it.copy(error = "Please add a short note describing the mismatch.") }
            return
        }
        val bookingId = _state.value.report?.bookingId ?: return
        if (bookingId.startsWith("demo-")) {
            _state.update { s ->
                val updated = s.report?.values?.map {
                    if (it.id == valueId) it.copy(flaggedByPatient = true, patientNote = trimmed) else it
                }.orEmpty()
                s.copy(report = s.report?.copy(values = updated), error = null, flaggedDone = true)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val body = JSONObject()
                    .put("valueId", valueId)
                    .put("note", trimmed)
                api.post("/bookings/$bookingId/report/flag", body)
            }.onSuccess {
                _state.update { s ->
                    val updated = s.report?.values?.map {
                        if (it.id == valueId) it.copy(flaggedByPatient = true, patientNote = trimmed) else it
                    }.orEmpty()
                    s.copy(report = s.report?.copy(values = updated), busy = false, flaggedDone = true)
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not flag value") }
            }
        }
    }

    fun confirmAll() {
        val bookingId = _state.value.report?.bookingId ?: return
        if (bookingId.startsWith("demo-")) {
            _state.update { it.copy(busy = false, verifiedDone = true, error = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { api.post("/bookings/$bookingId/report/verify", JSONObject()) }
                .onSuccess { _state.update { it.copy(busy = false, verifiedDone = true) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Could not verify report") } }
        }
    }

    fun consumeDone() {
        _state.update { it.copy(verifiedDone = false, flaggedDone = false) }
    }

    private fun parseReport(o: JSONObject): ReportDetail {
        val arr = o.optJSONArray("values")
        val values = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    val flag = runCatching { ResultFlag.valueOf(v.optString("flag", "NORMAL")) }
                        .getOrDefault(ResultFlag.NORMAL)
                    add(
                        LabValue(
                            id = v.getString("id"),
                            name = v.getString("name"),
                            value = v.getString("value"),
                            unit = v.getString("unit"),
                            referenceLow = v.getString("referenceLow"),
                            referenceHigh = v.getString("referenceHigh"),
                            flag = flag,
                            flaggedByPatient = v.optBoolean("flaggedByPatient", false),
                            patientNote = v.optString("patientNote").takeIf { it.isNotBlank() && !v.isNull("patientNote") },
                        ),
                    )
                }
            }
        }
        return ReportDetail(
            bookingId = o.getString("bookingId"),
            packageName = o.getString("packageName"),
            dateLabel = o.getString("dateLabel"),
            pdfUrl = null,
            values = values,
            doctorName = o.optString("doctorName").takeIf { it.isNotBlank() && !o.isNull("doctorName") },
            doctorNmcNumber = o.optString("doctorNmcNumber").takeIf { it.isNotBlank() && !o.isNull("doctorNmcNumber") },
            doctorNote = o.optString("doctorNote").takeIf { it.isNotBlank() && !o.isNull("doctorNote") },
            isUrgent = o.optBoolean("isUrgent", false),
        )
    }

    private fun fallbackReport(bookingId: String) = ReportDetail(
        bookingId = bookingId,
        packageName = "Basic Health",
        dateLabel = "25 Nov",
        pdfUrl = null,
        values = listOf(
            LabValue("1", "Hemoglobin", "13.8", "g/dL", "13.0", "17.0", ResultFlag.NORMAL),
            LabValue("2", "Fasting Blood Sugar", "112", "mg/dL", "70", "99", ResultFlag.HIGH),
            LabValue("3", "Total Cholesterol", "185", "mg/dL", "0", "200", ResultFlag.NORMAL),
        ),
        doctorName = "Suman Adhikari",
        doctorNmcNumber = "14892",
        doctorNote = "Fasting sugar is mildly elevated. Repeat HbA1c in 3 months and reduce refined sugar.",
    )
}
