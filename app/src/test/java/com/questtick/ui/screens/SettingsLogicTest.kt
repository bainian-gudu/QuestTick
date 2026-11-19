package com.questtick.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLogicTest {
    @Test
    fun exactAlarmIsUnsupportedBeforeAndroidTwelve() {
        assertEquals(ExactAlarmStatus.UNSUPPORTED, exactAlarmStatus(sdkInt = 30, canScheduleExactAlarms = false))
        assertEquals(ExactAlarmStatus.UNSUPPORTED, exactAlarmStatus(sdkInt = 30, canScheduleExactAlarms = true))
    }

    @Test
    fun exactAlarmReflectsGrantedPermission() {
        assertEquals(ExactAlarmStatus.GRANTED, exactAlarmStatus(sdkInt = 31, canScheduleExactAlarms = true))
        assertEquals(ExactAlarmStatus.DENIED, exactAlarmStatus(sdkInt = 31, canScheduleExactAlarms = false))
    }
}
