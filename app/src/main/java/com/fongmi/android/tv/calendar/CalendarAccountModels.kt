package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.bean.Vod

data class CalendarAccountToken(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0
)

data class CalendarFollow(
    val provider: CalendarProvider,
    val remoteId: Long,
    val media: Vod,
    val status: Int = 0
)

data class CalendarDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int
)
