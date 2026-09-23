package com.movo.customer.parcel.data

import com.movo.customer.parcel.domain.*

internal typealias JsonObject = Map<String, Any?>
internal fun JsonObject.text(key: String): String? = this[key] as? String
internal fun JsonObject.number(key: String): Double? = (this[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
@Suppress("UNCHECKED_CAST") internal fun Any?.objectValue(): JsonObject = this as? Map<String, Any?> ?: throw ParcelException("invalid_response", "Invalid server response")
internal fun JsonObject.required(key: String): String = text(key) ?: throw ParcelException("invalid_response", "Missing server field: $key")
internal fun JsonObject.requiredNumber(key: String): Double = number(key) ?: throw ParcelException("invalid_response", "Missing server field: $key")
internal object ParcelWire {
    fun unwrap(o: JsonObject): Any {
        if (o["success"] != true) throw ParcelException(o.text("code") ?: "api_error", o.text("error") ?: "Invalid server response")
        return o["data"] ?: throw ParcelException("invalid_response", "Missing server data")
    }
    fun quoteBody(d: ParcelDraft): JsonObject {
        validateDraft(d)
        if (d.tier != "standard" || d.size != "small" || d.extraStops.isNotEmpty() || d.cashOnDelivery != 0.0 || d.photoUri != null || d.notifySms)
            throw ParcelException("unsupported", "Live API does not support tier, size, extra stops, cash on delivery, parcel photo or SMS options. Use standard small parcel or explicitly try demo.")
        return mapOf("pickup_lat" to d.pickup!!.latitude, "pickup_lng" to d.pickup.longitude, "dest_lat" to d.destination!!.latitude, "dest_lng" to d.destination.longitude, "service_type" to d.serviceType)
    }
    fun booking(d: ParcelDraft): JsonObject {
        validateContacts(d)
        return quoteBody(d) + mapOf("pickup_address" to d.pickup!!.address,"pickup_name" to d.senderName,"pickup_phone" to d.senderPhone,
            "dest_address" to d.destination!!.address,"dest_name" to d.recipientName,"dest_phone" to d.recipientPhone,
            "item_description" to d.description,"item_category" to d.category,"special_instructions" to d.instructions,"payment_method" to d.paymentMethod) +
            // Only sent when the customer actually picked someone, so "any available
            // rider" stays the untouched automatic dispatch path.
            (d.preferredRiderId?.let { mapOf("preferred_rider_id" to it) } ?: emptyMap())
    }
    fun estimate(o: JsonObject) = ParcelEstimate(o.requiredNumber("totalCharge"), o.requiredNumber("distance_km"), o.number("eta_minutes")?.toInt(), o.text("currency") ?: "RWF")
    fun profile(o: JsonObject): ParcelProfile {
        if (o.required("role") != "customer") throw ParcelException("wrong_role", "Sign in with a customer account")
        return ParcelProfile(o.required("id"),o.required("full_name"),o.required("phone"),o.text("email"))
    }
    fun delivery(o: JsonObject): ParcelDelivery {
        fun place(prefix: String) = ParcelPlace("${o.required("id")}-$prefix",o.required("${prefix}_address"),o.required("${prefix}_address"),o.requiredNumber("${prefix}_lat"),o.requiredNumber("${prefix}_lng"))
        return ParcelDelivery(o.required("id"),o.required("order_no"),o.required("status"),place("pickup"),place("dest"),o.text("dest_name") ?: "",o.text("dest_phone") ?: "",o.text("item_description") ?: "",o.requiredNumber("total_charge"),o.required("created_at"),o.text("currency") ?: "RWF",pickupCode=o.text("pickup_otp"),deliveryCode=o.text("delivery_otp"),rating=o.number("customer_rating")?.toInt())
    }
}
