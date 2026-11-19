package com.example.m_nirog.models

data class CheckupPackage(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val pricePaisa: Int,
    val isActive: Boolean,
) {
    val priceLabel: String get() = "NPR %,d".format(pricePaisa / 100)
}
