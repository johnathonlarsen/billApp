package com.family.bankapp.update

data class AppUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releasedAt: String? = null,
    val notes: String? = null
)
