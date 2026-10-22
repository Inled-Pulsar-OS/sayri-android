package es.inled.sayri.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import es.inled.sayri.service.SayriAccessibilityService

/**
 * Handles device actions and tools inspired by Dicio and Private-Agent.
 */
class DeviceToolManager(private val context: Context) {

    /**
     * Finds and launches an application installed on the device by name.
     */
    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        val query = appName.trim().lowercase()

        // 1. Direct package match
        val directIntent = pm.getLaunchIntentForPackage(appName)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return true
        }

        // 2. Search installed applications by label
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var bestMatchPkg: String? = null

        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label == query || label.contains(query)) {
                bestMatchPkg = app.packageName
                break
            }
        }

        if (bestMatchPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(bestMatchPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return true
            }
        }
        return false
    }

    /**
     * Sets a countdown timer via the system Clock app.
     */
    fun setTimer(seconds: Int, label: String = "Sayri"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sets an alarm for a specific hour and minute.
     */
    fun setAlarm(hour: Int, minute: Int, label: String = "Sayri"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Dials a telephone number.
     */
    fun dialNumber(number: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${number.trim()}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Toggles device flashlight on/off.
     */
    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList[0]
            camManager.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens a web link or searches the web.
     */
    fun openWeb(queryOrUrl: String): Boolean {
        return try {
            val uri = if (queryOrUrl.startsWith("http://") || queryOrUrl.startsWith("https://")) {
                Uri.parse(queryOrUrl)
            } else {
                Uri.parse("https://www.google.com/search?q=${Uri.encode(queryOrUrl)}")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens GPS navigation / directions to destination.
     */
    fun openNavigation(destination: String): Boolean {
        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Inspects active mobile screen using Accessibility Service (Private-Agent).
     */
    fun inspectCurrentScreen(userIntent: String) =
        SayriAccessibilityService.instance?.dumpScreenHierarchy(userIntent)

    /**
     * Clicks an element on screen by text or resource ID.
     */
    fun clickScreenElement(target: String): Boolean =
        SayriAccessibilityService.instance?.clickElement(target) ?: false

    /**
     * Types text into an active editable field on screen.
     */
    fun typeTextOnScreen(target: String, text: String): Boolean =
        SayriAccessibilityService.instance?.setTextInField(target, text) ?: false

    /**
     * Parses [ACTION: TYPE param] blocks output by Sayri Core and executes them on device.
     */
    fun parseAndExecuteAction(text: String): Pair<String, Boolean> {
        val actionPattern = Regex("\\[ACTION:\\s*([A-Z_]+)(?:\\s+(.*?))?\\]")
        val match = actionPattern.find(text) ?: return Pair(text, false)
        val actionType = match.groupValues[1]
        val param = match.groupValues.getOrNull(2)?.trim() ?: ""
        var executed = false
        when (actionType) {
            "OPEN_APP" -> if (param.isNotEmpty()) executed = openApp(param)
            "FLASHLIGHT" -> executed = setFlashlight(param.equals("on", ignoreCase = true) || param.equals("true", ignoreCase = true) || param.contains("enciende", ignoreCase = true))
            "TIMER" -> {
                val sec = param.split(" ").firstOrNull()?.toIntOrNull() ?: 300
                val label = param.substringAfter(" ", "Sayri Timer")
                executed = setTimer(sec, label)
            }
            "ALARM" -> {
                val parts = param.split(":")
                if (parts.size >= 2) {
                    val h = parts[0].trim().toIntOrNull() ?: 8
                    val m = parts[1].trim().toIntOrNull() ?: 0
                    executed = setAlarm(h, m)
                }
            }
            "WEB" -> if (param.isNotEmpty()) executed = openWeb(param)
            "NAVIGATE" -> if (param.isNotEmpty()) executed = openNavigation(param)
        }
        val cleanText = text.replace(match.value, "").trim()
        return Pair(cleanText, executed)
    }
}
