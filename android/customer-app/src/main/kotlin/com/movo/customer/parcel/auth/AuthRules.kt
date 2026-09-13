package com.movo.customer.parcel.auth

fun rwandaPhone(input: String): String? {
    val text = input.replace(" ", "").replace("-", "")
    val local = when {
        text.startsWith("+250") -> text.removePrefix("+250")
        text.startsWith("0") -> text.drop(1)
        else -> text
    }
    return local.takeIf { it.matches(Regex("7[2389][0-9]{7}")) }?.let { "+250$it" }
}

fun pastedOtp(input: String): String? = Regex("(?<![0-9])[0-9]{6}(?![0-9])")
    .findAll(input).map { it.value }.toList().singleOrNull()
