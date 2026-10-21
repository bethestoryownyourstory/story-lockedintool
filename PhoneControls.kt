package com.story.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Dashboard's Controls and Media output are the phone's REAL ones (owner: everything real - they used to only flip a
 * switch on screen, and Media output listed made-up devices). What Android lets an app change itself is changed right
 * there (flashlight, Do Not Disturb, rotation lock); what only the phone may change (Wi-Fi, Bluetooth, airplane mode,
 * where other apps' sound plays) opens the phone's own switch for it, and Lockedintool comes straight back after.
 */
object PhoneControls {
    @Volatile private var torchOn = false
    private var torchWatched = false

    @Volatile private var torchLooked = false
    @Volatile private var torchCam: String? = null
    private fun torchId(c: Context): String? {
        if (torchLooked) return torchCam
        torchCam = findTorch(c); torchLooked = true
        return torchCam
    }
    private fun findTorch(c: Context): String? {
        val cm = c.getSystemService(CameraManager::class.java) ?: return null
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        fun flash(id: String) = runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
        fun back(id: String) = runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }.getOrDefault(false)
        return ids.firstOrNull { flash(it) && back(it) } ?: ids.firstOrNull { flash(it) }
    }

    /** Follows the flashlight (also when it's switched from the phone's own panel). Only listens - nothing runs. */
    fun watch(c: Context) {
        if (torchWatched) return
        val cm = c.getSystemService(CameraManager::class.java) ?: return
        val id = torchId(c) ?: return
        torchWatched = true
        runCatching {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == id && torchOn != enabled) { torchOn = enabled; OverlayService.instance?.controlsChanged() }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    fun state(c: Context): JSONObject {
        val cr = c.contentResolver
        val wifi = runCatching { c.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
        val bt = runCatching { Settings.Global.getInt(cr, Settings.Global.BLUETOOTH_ON, 0) == 1 }.getOrDefault(false)
        val nm = c.getSystemService(NotificationManager::class.java)
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val airplane = runCatching { Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrDefault(false)
        val rotationLock = runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0 }.getOrDefault(false)
        return JSONObject().put("wifi", wifi).put("bt", bt).put("dnd", dnd).put("airplane", airplane)
            .put("flashlight", torchOn).put("rotation", rotationLock).put("torch", torchId(c) != null)
    }

    /** Changes a control. Returns null when it was changed right here, or the phone's own screen to open for it. */
    fun toggle(c: Context, id: String): Intent? {
        val cr = c.contentResolver
        return when (id) {
            "flashlight" -> {
                val cm = c.getSystemService(CameraManager::class.java)
                val tid = torchId(c)
                if (cm != null && tid != null) runCatching { cm.setTorchMode(tid, !torchOn); torchOn = !torchOn }
                null
            }
            "dnd" -> {
                val nm = c.getSystemService(NotificationManager::class.java)
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    runCatching { nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
                    null
                } else Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            "rotation" -> {
                if (Settings.System.canWrite(c)) {
                    val auto = runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1 }.getOrDefault(true)
                    runCatching { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (auto) 0 else 1) }
                    null
                } else Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + c.packageName))
            }
            "wifi" -> {
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
                else {
                    runCatching { c.applicationContext.getSystemService(WifiManager::class.java)?.let { it.setWifiEnabled(!it.isWifiEnabled) } }
                    null
                }
            }
            "bt" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> null
        }
    }

    private val SINKS = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_DOCK, 26 /* BLE headset */, 27 /* BLE speaker */)

    private fun outName(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "This Phone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: when (d.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            else -> "Bluetooth audio"
        }
    }

    /** The phone's real sound outputs right now, and the one other apps' sound plays on. */
    fun outputs(c: Context): JSONObject {
        val am = c.getSystemService(AudioManager::class.java)
        val list = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { it.type in SINKS } ?: emptyList()
        val names = LinkedHashSet<String>(); list.forEach { names.add(outName(it)) }
        if (names.isEmpty()) names.add("This Phone")
        // Where media plays: what Android routes music to (Android 13+), else the same order Android uses.
        var active: String? = null
        if (Build.VERSION.SDK_INT >= 33 && am != null) runCatching {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { a ->
                list.firstOrNull { it.type == a.type && (a.address.isEmpty() || it.address == a.address) }?.let { active = outName(it) }
                    ?: run { if (a.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) active = "This Phone" }
            }
        }
        if (active == null) {
            val order = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_HDMI)
            active = order.firstNotNullOfOrNull { t -> list.firstOrNull { it.type == t } }?.let { outName(it) } ?: "This Phone"
        }
        return JSONObject().put("list", JSONArray(names.toList())).put("active", active)
    }

    /** Only the phone can move other apps' sound to another output: its own output switcher (Android 14+), else its
     *  Bluetooth screen. Returns the screen to open, or null when the phone showed its own switcher. */
    fun outputSwitcher(c: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 34 && runCatching { android.media.MediaRouter2.getInstance(c).showSystemOutputSwitcher() }.getOrDefault(false)) return null
        return Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", c.packageName)
            .takeIf { it.resolveActivity(c.packageManager) != null } ?: Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }
}
