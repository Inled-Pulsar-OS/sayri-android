package es.inled.sayri.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import es.inled.sayri.service.SayriAccessibilityService

data class AppInfo(
    val label: String,
    val packageName: String,
    val isSystem: Boolean = false
)

data class DiscoveredIntent(
    val action: String,
    val category: String? = null,
    val mimeType: String? = null,
    val targetApp: String? = null,
    val description: String
)

/**
 * Handles device actions, App Functions, App Intents and Accessibility control.
 */
class DeviceToolManager(private val context: Context) {

    private val tag = "DeviceToolManager"

    /**
     * Dynamically queries all launchable applications installed on the device.
     */
    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)
        return resolveList.mapNotNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm)?.toString()?.trim() ?: ""
            val pkg = resolveInfo.activityInfo?.packageName ?: ""
            if (label.isNotEmpty() && pkg.isNotEmpty()) {
                val isSys = (resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                AppInfo(label, pkg, isSys)
            } else null
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }

    private fun normalizeString(s: String): String {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .trim()
    }

    /**
     * Finds and launches an application installed on the device dynamically by label or package.
     */
    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        val raw = appName.trim().lowercase()

        // Strip common command verbs and articles
        val query = raw
            .replace(Regex("^(abre|abrir|open|lanza|lanzar|ejecuta|ejecutar|inicia|iniciar)\\s+"), "")
            .replace(Regex("^(la\\s+app\\s+de|la\\s+aplicacion\\s+de|la\\s+aplicación\\s+de|el\\s+|la\\s+|los\\s+|las\\s+|the\\s+|app\\s+|app\\s+de\\s+)"), "")
            .trim()

        if (query.isEmpty()) return false
        val normQuery = normalizeString(query)

        Log.i(tag, "Attempting to open app: '$appName' (normalized: '$normQuery')")

        // 1. Direct package match
        val directIntent = pm.getLaunchIntentForPackage(query)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return true
        }

        // 2. Dynamic lookup in installed launcher applications
        val apps = getInstalledApps()
        
        // Exact label match (with and without accent normalization)
        var target = apps.firstOrNull { it.label.equals(query, ignoreCase = true) }
            ?: apps.firstOrNull { normalizeString(it.label) == normQuery }

        // Starts-with match
        if (target == null) {
            target = apps.firstOrNull { normalizeString(it.label).startsWith(normQuery) }
        }

        // Substring / contains match
        if (target == null) {
            target = apps.firstOrNull { normalizeString(it.label).contains(normQuery) }
        }

        // Package name match
        if (target == null) {
            target = apps.firstOrNull { it.packageName.lowercase().contains(normQuery) }
        }

        if (target != null) {
            val launchIntent = pm.getLaunchIntentForPackage(target.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(tag, "Successfully launched: ${target.label} (${target.packageName})")
                return true
            }
        }

        Log.w(tag, "No installed app found matching '$query' among ${apps.size} packages")
        return false
    }

    /**
     * Discovers available App Intents across installed system and user applications.
     */
    fun discoverAvailableIntents(): List<DiscoveredIntent> {
        val pm = context.packageManager
        val list = mutableListOf<DiscoveredIntent>()

        list.add(DiscoveredIntent(Intent.ACTION_SEND, mimeType = "text/plain", description = "Share text or send message via WhatsApp, Telegram, Gmail, SMS"))
        list.add(DiscoveredIntent(Intent.ACTION_VIEW, description = "Open URLs, videos, maps, or app deep-links"))
        list.add(DiscoveredIntent(Intent.ACTION_DIAL, description = "Dial a phone number"))
        list.add(DiscoveredIntent(Intent.ACTION_WEB_SEARCH, description = "Search the web"))
        list.add(DiscoveredIntent(AlarmClock.ACTION_SET_TIMER, description = "Set a timer"))
        list.add(DiscoveredIntent(AlarmClock.ACTION_SET_ALARM, description = "Set an alarm"))
        list.add(DiscoveredIntent(MediaStore.ACTION_IMAGE_CAPTURE, description = "Take a photo"))

        val sendIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val sendReceivers = pm.queryIntentActivities(sendIntent, 0)
        for (r in sendReceivers.take(15)) {
            val appName = r.loadLabel(pm).toString()
            list.add(DiscoveredIntent(Intent.ACTION_SEND, mimeType = "text/plain", targetApp = r.activityInfo.packageName, description = "Send text directly via $appName"))
        }

        return list
    }

    /**
     * Executes an Android App Intent with optional parameters, data, and extras.
     */
    fun executeAppIntent(
        action: String,
        data: String? = null,
        mimeType: String? = null,
        packageName: String? = null,
        extras: Map<String, String>? = null
    ): Boolean {
        return try {
            val intent = Intent(action).apply {
                if (!data.isNullOrEmpty()) {
                    if (!mimeType.isNullOrEmpty()) {
                        setDataAndType(Uri.parse(data), mimeType)
                    } else {
                        setData(Uri.parse(data))
                    }
                } else if (!mimeType.isNullOrEmpty()) {
                    type = mimeType
                }
                if (!packageName.isNullOrEmpty()) {
                    setPackage(packageName)
                }
                extras?.forEach { (k, v) ->
                    putExtra(k, v)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to execute intent: $action", e)
            false
        }
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
     * Parses key="value" attributes from an action string.
     */
    private fun parseAttributes(input: String): Map<String, String> {
        val regex = Regex("([a-zA-Z_]+)=\"([^\"]*)\"")
        val map = mutableMapOf<String, String>()
        for (m in regex.findAll(input)) {
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

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
            "CLICK_TEXT" -> if (param.isNotEmpty()) executed = clickScreenElement(param)
            "CLICK_COORDS" -> {
                val coords = param.split(Regex("\\s+"))
                if (coords.size >= 2) {
                    val x = coords[0].toFloatOrNull() ?: 0f
                    val y = coords[1].toFloatOrNull() ?: 0f
                    SayriAccessibilityService.instance?.clickCoordinates(x, y)
                    executed = true
                }
            }
            "TYPE_TEXT" -> {
                val parts = param.split(" ", limit = 2)
                if (parts.size == 2) {
                    executed = typeTextOnScreen(parts[0], parts[1])
                }
            }
            "SCROLL" -> {
                val forward = !param.contains("up", ignoreCase = true)
                executed = SayriAccessibilityService.instance?.scroll(forward) ?: false
            }
            "INTENT", "APP_INTENT" -> {
                val attrs = parseAttributes(param)
                if (attrs.isNotEmpty()) {
                    val act = attrs["action"] ?: Intent.ACTION_VIEW
                    val data = attrs["data"]
                    val pkg = attrs["pkg"] ?: attrs["package"]
                    val mime = attrs["type"] ?: attrs["mimeType"]
                    val extras = mutableMapOf<String, String>()
                    if (attrs.containsKey("text")) extras[Intent.EXTRA_TEXT] = attrs["text"]!!
                    if (attrs.containsKey("subject")) extras[Intent.EXTRA_SUBJECT] = attrs["subject"]!!
                    executed = executeAppIntent(act, data, mime, pkg, extras)
                } else if (param.startsWith("SEND", ignoreCase = true)) {
                    val body = param.substringAfter("SEND", "").trim().removeSurrounding("\"")
                    executed = executeAppIntent(Intent.ACTION_SEND, mimeType = "text/plain", extras = mapOf(Intent.EXTRA_TEXT to body))
                }
            }
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
