package com.example.m_nirog.data

import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.NepalTime
import com.example.m_nirog.core.SessionStore
import com.example.m_nirog.models.Booking
import com.example.m_nirog.models.BookingListItem
import com.example.m_nirog.models.BookingRequest
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.CheckupPackage
import com.example.m_nirog.models.City
import com.example.m_nirog.models.KhaltiCheckoutSession
import com.example.m_nirog.models.KhaltiVerifyResult
import com.example.m_nirog.models.SlotDay
import com.example.m_nirog.models.TimeSlot
import org.json.JSONArray
import org.json.JSONObject

class RemoteBookingRepository(store: SessionStore) : BookingRepository {
    private val api = ApiClient(store)
    private val cachedBookings = linkedMapOf<String, Booking>()

    override suspend fun getPackages(): List<CheckupPackage> {
        val arr = JSONArray(api.get("/packages"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val testsArr = o.optJSONArray("includedTests")
                val tests = buildList {
                    if (testsArr != null) {
                        for (j in 0 until testsArr.length()) add(testsArr.getString(j))
                    }
                }
                add(
                    CheckupPackage(
                        id = o.getString("id"),
                        code = o.optString("code", o.getString("id").uppercase()),
                        name = o.getString("name"),
                        description = o.getString("description"),
                        pricePaisa = o.getInt("pricePaisa"),
                        isActive = o.optBoolean("isActive", true),
                        includedTests = tests,
                    ),
                )
            }
        }.distinctBy { it.name }
    }

    override suspend fun getDays(): List<SlotDay> {
        val arr = JSONArray(api.get("/slots/days"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(SlotDay(isoDate = o.getString("dateIso"), label = o.getString("label")))
            }
        }
    }

    override suspend fun getSlots(city: City, dateIso: String): List<TimeSlot> {
        val arr = JSONArray(api.get("/slots?city=${city.code}&date=$dateIso"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    TimeSlot(
                        id = o.getString("id"),
                        label = o.getString("label"),
                        remaining = o.getInt("remaining"),
                    ),
                )
            }
        }
    }

    override suspend fun createBooking(
        request: BookingRequest,
        dateLabel: String,
        slotLabel: String,
    ): Booking {
        val body = JSONObject()
            .put("packageId", request.packageId)
            .put("slotId", request.slotId)
            .put("city", request.city.code)
            .put("addressLine", request.address)
        val b = parseBooking(JSONObject(api.post("/bookings", body)))
        cachedBookings[b.id] = b
        return b
    }

    fun clearCache() { cachedBookings.clear() }

    suspend fun initiateKhalti(bookingId: String): KhaltiCheckoutSession {
        val res = JSONObject(api.post("/bookings/$bookingId/pay/khalti/initiate", JSONObject()))
        return KhaltiCheckoutSession(
            pidx = res.getString("pidx"),
            paymentUrl = res.getString("paymentUrl"),
            returnUrl = res.getString("returnUrl"),
            amountPaisa = res.getInt("amountPaisa"),
        )
    }

    suspend fun verifyKhalti(bookingId: String, pidx: String?): KhaltiVerifyResult {
        val body = JSONObject().apply { if (!pidx.isNullOrBlank()) put("pidx", pidx) }
        val res = JSONObject(api.post("/bookings/$bookingId/pay/khalti/verify", body))
        val booking = parseBooking(res.getJSONObject("booking"))
        cachedBookings[booking.id] = booking
        return KhaltiVerifyResult(
            paymentStatus = res.getString("paymentStatus"),
            transactionId = if (res.isNull("transactionId")) null else res.optString("transactionId").takeIf { it.isNotBlank() },
            booking = booking,
        )
    }

    override suspend fun verifyPayment(bookingId: String): Booking {
        api.post("/bookings/$bookingId/pay/dev", JSONObject())
        return getBooking(bookingId)
    }

    override suspend fun getBooking(bookingId: String): Booking {
        val b = parseBooking(JSONObject(api.get("/bookings/$bookingId")))
        cachedBookings[b.id] = b
        return b
    }

    override suspend fun getMyBookings(): List<BookingListItem> {
        val arr = JSONArray(api.get("/bookings"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = parseBooking(o)
                cachedBookings[b.id] = b
                add(
                    BookingListItem(
                        id = b.id,
                        packageName = b.packageName,
                        dateLabel = b.dateLabel,
                        slotLabel = b.slotLabel,
                        status = b.status,
                        lastUpdatedLabel = NepalTime.formatDateTime(o.getLong("updatedAtMs")),
                    ),
                )
            }
        }
    }

    override suspend fun cancelBooking(bookingId: String): Booking {
        api.post("/bookings/$bookingId/cancel", JSONObject())
        return getBooking(bookingId)
    }

    override suspend fun expireBooking(bookingId: String): Booking {
        val current = runCatching { getBooking(bookingId) }.getOrNull() ?: cachedBookings[bookingId]
        return current?.let {
            if (it.status == BookingStatus.PENDING_PAYMENT) it.copy(status = BookingStatus.EXPIRED) else it
        } ?: throw AppError("NOT_FOUND")
    }

    override suspend fun devSetStatus(bookingId: String, status: BookingStatus): Booking {
        val body = JSONObject().put("status", status.name)
        val b = parseBooking(JSONObject(api.post("/bookings/$bookingId/dev/status", body)))
        cachedBookings[b.id] = b
        return b
    }

    suspend fun devSetUrgent(bookingId: String): Booking {
        val body = JSONObject()
            .put("status", BookingStatus.COMPLETED.name)
            .put("urgent", true)
        val b = parseBooking(JSONObject(api.post("/bookings/$bookingId/dev/status", body)))
        cachedBookings[b.id] = b
        return b
    }

    override suspend fun devAdvance(bookingId: String): Booking {
        val b = parseBooking(JSONObject(api.post("/bookings/$bookingId/dev/advance", JSONObject())))
        cachedBookings[b.id] = b
        return b
    }

    private fun parseBooking(o: JSONObject): Booking {
        val hold = if (o.isNull("holdExpiresAtMs")) 0L else o.optLong("holdExpiresAtMs", 0L)
        return Booking(
            id = o.getString("id"),
            packageName = o.getString("packageName"),
            pricePaisa = o.getInt("pricePaisa"),
            dateLabel = o.getString("dateLabel"),
            slotLabel = o.getString("slotLabel"),
            address = o.getString("addressLine"),
            status = BookingStatus.fromServer(o.getString("status")),
            holdExpiresAtMillis = hold,
            pickerName = o.optString("pickerName").takeIf { it.isNotBlank() && !o.isNull("pickerName") },
            pickerPhone = o.optString("pickerPhone").takeIf { it.isNotBlank() && !o.isNull("pickerPhone") },
            recollectionReason = o.optString("recollectionReason").takeIf { it.isNotBlank() && !o.isNull("recollectionReason") },
            recollectionCount = o.optInt("recollectionCount", 0),
            isUrgent = o.optBoolean("isUrgent", false),
        )
    }
}
