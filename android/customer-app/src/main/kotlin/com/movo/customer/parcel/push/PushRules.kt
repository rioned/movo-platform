package com.movo.customer.parcel.push

import java.security.MessageDigest

data class PushRoute(val orderId: String, val offerId: String?)
enum class PushCategory(val key: String) { DELIVERY("delivery"), OFFERS("offers"), PROMOTIONS("promotions") }

object PushRules {
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")
    fun route(data: Map<String, String>): PushRoute? {
        val order = data["order_id"]?.takeIf(identifier::matches) ?: return null
        return PushRoute(order, data["offer_id"]?.takeIf(identifier::matches))
    }
    fun category(data: Map<String, String>): PushCategory? = when (data["category"]) {
        "delivery" -> PushCategory.DELIVERY
        "offers" -> PushCategory.OFFERS
        "promotions" -> PushCategory.PROMOTIONS
        null -> if (route(data)?.offerId != null) PushCategory.OFFERS else if (route(data) != null) PushCategory.DELIVERY else null
        else -> null
    }
    fun shouldNotify(permissionGranted: Boolean, systemEnabled: Boolean, categoryEnabled: Boolean) =
        permissionGranted && systemEnabled && categoryEnabled

    /** Length-prefixed, sorted data avoids ambiguous concatenation. Never retains payload/token text. */
    fun eventKey(messageId: String?, data: Map<String, String>): String {
        val canonical = messageId?.takeIf { it.isNotBlank() }?.let { "id:$it" }
            ?: data.toSortedMap().entries.joinToString("") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** In-memory filter; the Android store additionally persists a bounded recent-ID list. */
class PushDeduplicator(private val capacity: Int = 128) {
    init { require(capacity > 0) }
    private val recent = LinkedHashSet<String>()
    @Synchronized fun accept(key: String): Boolean {
        if (!recent.add(key)) return false
        while (recent.size > capacity) recent.remove(recent.first())
        return true
    }
}
