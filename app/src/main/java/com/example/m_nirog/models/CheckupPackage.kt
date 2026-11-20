package com.example.m_nirog.models

data class CheckupPackage(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val pricePaisa: Int,
    val isActive: Boolean,
    val includedTests: List<String> = emptyList(),
) {
    val priceLabel: String get() = "NPR %,d".format(pricePaisa / 100)

    val testsSummary: String
        get() = when {
            includedTests.isNotEmpty() -> includedTests.joinToString(", ")
            code.contains("BASIC", ignoreCase = true) ->
                "Hemoglobin, Fasting Blood Sugar, Total Cholesterol"
            code.contains("FULL", ignoreCase = true) || code.contains("COMPREHENSIVE", ignoreCase = true) ->
                "CBC, Lipid Profile, Liver & Kidney Function, Thyroid, HbA1c"
            else -> description
        }
}
