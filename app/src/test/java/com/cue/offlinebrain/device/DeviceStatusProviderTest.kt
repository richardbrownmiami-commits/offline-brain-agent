package com.cue.offlinebrain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusProviderTest {
    @Test fun summaryAndCoreQueriesAreRecognized() {
        assertEquals(DeviceStatusProvider.Request.SUMMARY, DeviceStatusProvider.classify("device status"))
        assertEquals(DeviceStatusProvider.Request.BATTERY, DeviceStatusProvider.classify("what is my battery level"))
        assertEquals(DeviceStatusProvider.Request.CHARGING, DeviceStatusProvider.classify("is the phone charging"))
        assertEquals(DeviceStatusProvider.Request.STORAGE, DeviceStatusProvider.classify("how much storage is left"))
        assertEquals(DeviceStatusProvider.Request.RAM, DeviceStatusProvider.classify("free RAM"))
        assertEquals(DeviceStatusProvider.Request.NETWORK, DeviceStatusProvider.classify("am I online"))
        assertEquals(DeviceStatusProvider.Request.VOLUME, DeviceStatusProvider.classify("media volume level"))
    }

    @Test fun systemSettingsQueriesRouteToManualSettingsRatherThanClaimingControl() {
        assertEquals(DeviceStatusProvider.Request.WIFI_NAME, DeviceStatusProvider.classify("which Wi-Fi am I on"))
        assertEquals(DeviceStatusProvider.Request.MOBILE_DATA, DeviceStatusProvider.classify("turn mobile data on"))
        assertEquals(DeviceStatusProvider.Request.BLUETOOTH, DeviceStatusProvider.classify("Bluetooth on"))
        assertEquals(DeviceStatusProvider.Request.DO_NOT_DISTURB, DeviceStatusProvider.classify("open do not disturb"))
        assertEquals(DeviceStatusProvider.Request.BRIGHTNESS, DeviceStatusProvider.classify("screen brightness"))
        assertEquals(DeviceStatusProvider.Request.BRIGHTNESS_SETTINGS, DeviceStatusProvider.classify("make brightness brighter"))
    }

    @Test fun explicitTorchAndVolumeActionsAreRecognized() {
        assertEquals(DeviceStatusProvider.Request.FLASHLIGHT_ON, DeviceStatusProvider.classify("turn flashlight on"))
        assertEquals(DeviceStatusProvider.Request.FLASHLIGHT_OFF, DeviceStatusProvider.classify("turn torch off"))
        assertEquals(DeviceStatusProvider.Request.VOLUME_UP, DeviceStatusProvider.classify("volume up"))
        assertEquals(DeviceStatusProvider.Request.VOLUME_DOWN, DeviceStatusProvider.classify("decrease volume"))
        assertTrue(DeviceStatusProvider.needsCameraPermission("turn flashlight on"))
        assertTrue(DeviceStatusProvider.needsCameraPermission("turn flashlight off"))
        assertFalse(DeviceStatusProvider.needsCameraPermission("show battery"))
    }

    @Test fun basicDeviceDetailsAreRecognizedAndUnrelatedChatIsNotConsumed() {
        assertEquals(DeviceStatusProvider.Request.PHONE_MODEL, DeviceStatusProvider.classify("phone model"))
        assertEquals(DeviceStatusProvider.Request.ANDROID_VERSION, DeviceStatusProvider.classify("which Android version"))
        assertEquals(DeviceStatusProvider.Request.UPTIME, DeviceStatusProvider.classify("phone uptime"))
        assertEquals(DeviceStatusProvider.Request.TEMPERATURE, DeviceStatusProvider.classify("battery temperature"))
        assertEquals(DeviceStatusProvider.Request.INSTALLED_APPS, DeviceStatusProvider.classify("installed apps count"))
        assertEquals(DeviceStatusProvider.Request.BIGGEST_APPS, DeviceStatusProvider.classify("largest apps"))
        assertEquals(DeviceStatusProvider.Request.NETWORK_OPERATOR, DeviceStatusProvider.classify("network operator"))
        assertEquals(DeviceStatusProvider.Request.IP_ADDRESS, DeviceStatusProvider.classify("local IP address"))
        assertNull(DeviceStatusProvider.classify("tell me a story about batteries"))
        assertNull(DeviceStatusProvider.classify("weather in Delhi"))
    }
}
