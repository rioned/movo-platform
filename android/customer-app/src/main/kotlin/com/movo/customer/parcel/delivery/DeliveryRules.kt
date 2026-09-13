package com.movo.customer.parcel.delivery

data class PaymentOption(val id: String, val title: String, val subtitle: String = "", val enabled: Boolean = true)
fun selectPayment(current: String, option: PaymentOption): String = if (option.enabled) option.id else current
fun canConfirmPayment(selected: String, options: List<PaymentOption>): Boolean = options.any { it.id == selected && it.enabled }
fun validRecipient(name: String, phone: String): Boolean = name.isNotBlank() &&
    phone.matches(Regex("\\+?[0-9 ()-]+")) && phone.count(Char::isDigit) in 8..15
