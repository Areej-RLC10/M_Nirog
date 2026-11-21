package com.example.m_nirog.core

import com.example.m_nirog.BuildConfig

/** Automatically disabled in release builds. Gates dev-only shortcuts. */
object DevConfig {
    val ENABLED: Boolean get() = BuildConfig.DEBUG
}
