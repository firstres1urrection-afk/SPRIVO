package com.youngchun.sprivo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallNotificationListener : NotificationListenerService() {

    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sourcePackage = sbn.packageName ?: ""
        val extras = sbn.notification.extras ?: android.os.Bundle()

        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val tickerText = sbn.notification.tickerText?.toString().orEmpty()
        val textLines = extras.getCharSequenceArray("android.textLines")?.joinToString(" ") { it.toString() }.orEmpty()

        logNotificationPayload(
            pkg = sourcePackage,
            postTime = sbn.postTime,
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            tickerText = tickerText,
            textLines = textLines,
            extras = extras
        )

        val source = listOf(title, text, subText, bigText, tickerText, textLines)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (sourcePackage == applicationContext.packageName) {
            Log.d("SPRIVO", "자기 앱 알림 무시: $sourcePackage / $title / $text")
            return
        }

        if (!isAllowedCallApp(sourcePackage)) {
            Log.d("SPRIVO", "전화 앱 아님, 무시: $sourcePackage / $source")
            return
        }

        Log.d("SPRIVO", "알림 감지: [$sourcePackage] $source")

        if (!isMissedCallNotification(sbn, source)) {
            Log.d("SPRIVO", "부재중 전화 알림 아님")
            return
        }

        val phoneNumber = extractNumber(source) ?: resolveLatestMissedNumberFromCallLog(sbn.postTime)
        if (phoneNumber == null) {
            Log.d("SPRIVO", "번호 추출 실패")
            return
        }

        val prefs = getSharedPreferences("sprivo", MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", false)
        val mode = normalizeLegacyModeForDispatch(prefs.getString("mode", "운전 중") ?: "운전 중")

        if (!isActive) {
            Log.d("SPRIVO", "자동응답 OFF 상태라 전송 안 함")
            return
        }

        val normalizedNumber = normalizePhoneNumber(phoneNumber)
        val message = getMessageForMode(mode)

        if (shouldSkipDuplicate(normalizedNumber, mode)) {
            Log.d("SPRIVO", "중복 부재중 처리 무시: number=$normalizedNumber, mode=$mode")
            return
        }

        Log.d("SPRIVO", "전송 시작: number=$normalizedNumber, mode=$mode, message=$message")

        sendToServer(
            number = normalizedNumber,
            message = message,
            mode = mode
        )
    }

    private fun normalizeLegacyModeForDispatch(mode: String): String {
        return when (mode) {
            "회의 중" -> "고객 응대 중"
            else -> mode
        }
    }

    private fun isAllowedCallApp(packageName: String): Boolean {
        val allowedPackages = setOf(
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.skt.prod.dialer"
        )

        return packageName in allowedPackages ||
                packageName.contains("dialer", ignoreCase = true) ||
                packageName.contains("incallui", ignoreCase = true)
    }

    private fun isMissedCallNotification(sbn: StatusBarNotification, source: String): Boolean {
        val category = sbn.notification.category.orEmpty(); val categoryMatch = category == android.app.Notification.CATEGORY_MISSED_CALL; val textMatch = source.contains("\ubd80\uc7ac\uc911") || source.contains("Missed", ignoreCase = true); val result = categoryMatch || textMatch; val reason = when { categoryMatch -> "category"; textMatch -> "text"; else -> "none" }; Log.d("SPRIVO", "missed-call detection: category=$category result=$result reason=$reason"); return result
    }

    private fun logNotificationPayload(
        pkg: String,
        postTime: Long,
        title: String,
        text: String,
        subText: String,
        bigText: String,
        tickerText: String,
        textLines: String,
        extras: android.os.Bundle
    ) {
        Log.d(
            "SPRIVO",
            """
                Notification payload
                pkg=$pkg
                postTime=$postTime
                title=$title
                text=$text
                subText=$subText
                bigText=$bigText
                tickerText=$tickerText
                textLines=$textLines
                extrasKeys=${extras.keySet()}
            """.trimIndent()
        )
    }

    private fun getMessageForMode(mode: String): String {
        val prefs = getSharedPreferences("sprivo", MODE_PRIVATE)

        val normalizedMode = normalizeLegacyModeForDispatch(mode)

        val defaultMessage = when (normalizedMode) {
            "운전 중" -> "현재 운전 중입니다. 문자로 연락 부탁드립니다."
            "현장 작업 중" -> "현재 현장 작업 중입니다. 문자로 연락 부탁드립니다."
            "고객 응대 중" -> "현재 고객 응대 중입니다. 종료 후 연락드리겠습니다."
            else -> "현재 해외 체류 중입니다. 문자로 연락 부탁드립니다."
        }

        val messageKey = when (normalizedMode) {
            "운전 중" -> "message_driving"
            "현장 작업 중" -> "message_on_site"
            "고객 응대 중" -> "message_customer_busy"
            else -> "message_overseas"
        }

        val savedMessage = prefs.getString(messageKey, defaultMessage)?.trim().orEmpty()
        val migratedLegacyMessage = if (normalizedMode == "고객 응대 중" && savedMessage.isBlank()) {
            prefs.getString("message_meeting", "")?.trim().orEmpty()
        } else {
            ""
        }

        if (migratedLegacyMessage.isNotBlank()) {
            prefs.edit().putString("message_customer_busy", migratedLegacyMessage).apply()
        }

        val resolvedMessage = if (savedMessage.isBlank()) migratedLegacyMessage else savedMessage
        return if (resolvedMessage.isBlank()) defaultMessage else resolvedMessage
    }

    private fun extractNumber(text: String): String? {
        val sanitized = text
            .replace(Regex("\\(\\d+\\)"), "")
            .replace(Regex("[·•]"), " ")
            .trim()

        val regexList = listOf(
            Regex("""\+82\s?10[- ]?\d{4}[- ]?\d{4}"""),
            Regex("""\+8210\d{8}"""),
            Regex("""010[- ]?\d{4}[- ]?\d{4}"""),
            Regex("""01[016789][- ]?\d{3,4}[- ]?\d{4}"""),
            Regex("""0\d{1,2}[- ]?\d{3,4}[- ]?\d{4}"""),
            Regex("""070[- ]?\d{3,4}[- ]?\d{4}"""),
            Regex("""15\d{2}[- ]?\d{4}""")
        )

        for (regex in regexList) {
            val match = regex.find(sanitized)
            if (match != null) return match.value
        }
        return null
    }

    private fun normalizePhoneNumber(number: String): String {
        val digitsOnly = number.replace(Regex("""[^\d+]"""), "")

        return when {
            digitsOnly.startsWith("+8210") -> "0" + digitsOnly.removePrefix("+82")
            digitsOnly.startsWith("8210") -> "0" + digitsOnly.removePrefix("82")
            digitsOnly.startsWith("+82") -> "0" + digitsOnly.removePrefix("+82")
            digitsOnly.startsWith("82") && digitsOnly.length >= 10 -> "0" + digitsOnly.removePrefix("82")
            else -> digitsOnly
        }
    }

    private fun shouldSkipDuplicate(phoneNumber: String, mode: String): Boolean {
        val prefs = getSharedPreferences("sprivo_listener_meta", MODE_PRIVATE)
        val currentKey = "$phoneNumber|$mode"
        val lastKey = prefs.getString("last_key", "") ?: ""
        val lastAt = prefs.getLong("last_at", 0L)
        val now = System.currentTimeMillis()

        val isDuplicate = currentKey == lastKey && (now - lastAt) < 15_000L

        if (!isDuplicate) {
            prefs.edit()
                .putString("last_key", currentKey)
                .putLong("last_at", now)
                .apply()
        }

        return isDuplicate
    }

    private fun sendToServer(number: String, message: String, mode: String) {
        val payload = JSONObject().apply {
            put("phone", number)
            put("number", number)
            put("message", message)
            put("mode", mode)
            put("detectedAt", System.currentTimeMillis())
            put("appVersion", "1.0")
        }

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://project-3cnvx.vercel.app/api/call")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SPRIVO", "전송 실패", e)

                saveCallHistory(
                    context = this@CallNotificationListener,
                    phoneNumber = number,
                    mode = mode,
                    status = "fail",
                    reason = "NETWORK_ERROR",
                    groupId = "",
                    logId = "",
                    providerStatus = ""
                )

                showLocalNotification(
                    title = "SPRIVO 부재중 알림 실패",
                    message = "${formatPhoneNumber(number)}에 부재중 알림을 전송하지 못했습니다."
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = it.body?.string().orEmpty()

                    Log.d("SPRIVO", "응답 코드: ${it.code}")
                    Log.d("SPRIVO", "응답 바디: $bodyString")

                    if (!it.isSuccessful) {
                        saveCallHistory(
                            context = this@CallNotificationListener,
                            phoneNumber = number,
                            mode = mode,
                            status = "fail",
                            reason = "HTTP_${it.code}",
                            groupId = "",
                            logId = "",
                            providerStatus = ""
                        )

                        showLocalNotification(
                            title = "SPRIVO 부재중 알림 실패",
                            message = "${formatPhoneNumber(number)}에 부재중 알림을 전송하지 못했습니다."
                        )
                        return
                    }

                    if (bodyString.isBlank()) {
                        saveCallHistory(
                            context = this@CallNotificationListener,
                            phoneNumber = number,
                            mode = mode,
                            status = "fail",
                            reason = "EMPTY_RESPONSE",
                            groupId = "",
                            logId = "",
                            providerStatus = ""
                        )

                        showLocalNotification(
                            title = "SPRIVO 부재중 알림 실패",
                            message = "${formatPhoneNumber(number)}에 부재중 알림을 전송하지 못했습니다."
                        )
                        return
                    }

                    try {
                        val json = JSONObject(bodyString)

                        val status = json.optString("status", "fail")
                        val reason = json.optString("reason", "")
                        val logId = json.optString("logId", "")
                        val groupId = json.optString("groupId", "")
                        val providerStatus = json.optString("providerStatus", "")

                        Log.d("SPRIVO", "parsed status=$status")
                        Log.d("SPRIVO", "parsed reason=$reason")
                        Log.d("SPRIVO", "parsed logId=$logId")
                        Log.d("SPRIVO", "parsed groupId=$groupId")
                        Log.d("SPRIVO", "parsed providerStatus=$providerStatus")

                        when (status) {
                            "pending" -> {
                                showLocalNotification(
                                    title = "SPRIVO 부재중 알림",
                                    message = "${formatPhoneNumber(number)}에 부재중 알림을 전송 중입니다. 잠시 후 결과를 확인해주세요."
                                )
                            }

                            "success" -> {
                                showLocalNotification(
                                    title = "SPRIVO 부재중 알림",
                                    message = "${formatPhoneNumber(number)}에 부재중 알림을 전송했습니다."
                                )
                            }

                            "fail" -> {
                                showLocalNotification(
                                    title = "SPRIVO 부재중 알림 실패",
                                    message = "${formatPhoneNumber(number)}에 부재중 알림을 전송하지 못했습니다."
                                )
                            }

                            "skipped" -> {
                                showLocalNotification(
                                    title = "SPRIVO 부재중 알림",
                                    message = "${formatPhoneNumber(number)}에는 부재중 알림을 보내지 않았습니다."
                                )
                            }

                            else -> {
                                showLocalNotification(
                                    title = "SPRIVO 처리 오류",
                                    message = "${formatPhoneNumber(number)} 처리 중 알 수 없는 응답을 받았습니다."
                                )
                            }
                        }

                        saveCallHistory(
                            context = this@CallNotificationListener,
                            phoneNumber = number,
                            mode = mode,
                            status = status,
                            reason = reason,
                            groupId = groupId,
                            logId = logId,
                            providerStatus = providerStatus
                        )
                    } catch (e: Exception) {
                        Log.e("SPRIVO", "JSON 파싱 실패", e)

                        saveCallHistory(
                            context = this@CallNotificationListener,
                            phoneNumber = number,
                            mode = mode,
                            status = "fail",
                            reason = "JSON_PARSE_ERROR",
                            groupId = "",
                            logId = "",
                            providerStatus = ""
                        )

                        showLocalNotification(
                            title = "SPRIVO 처리 오류",
                            message = "${formatPhoneNumber(number)} 처리 중 응답 오류가 발생했습니다."
                        )
                    }
                }
            }
        })
    }

    private fun saveCallHistory(
        context: Context,
        phoneNumber: String,
        mode: String,
        status: String,
        reason: String,
        groupId: String,
        logId: String,
        providerStatus: String,
        classification: String = "unknown",
        classificationReason: String = "INITIAL_DEFAULT",
        callbackNeeded: Boolean = true,
        callbackStatus: String = "todo",
        autoReplySuppressed: Boolean = false
    ) {
        val prefs = context.getSharedPreferences("sprivo", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("call_history", "[]") ?: "[]"
        val historyArray = JSONArray(historyJson)

        val timeString =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val item = JSONObject().apply {
            put("time", timeString)
            put("phoneNumber", phoneNumber)
            put("mode", mode)
            put("status", status)
            put("reason", reason)
            put("groupId", groupId)
            put("logId", logId)
            put("providerStatus", providerStatus)
            put("classification", classification)
            put("classificationReason", classificationReason)
            put("callbackNeeded", callbackNeeded)
            put("callbackStatus", callbackStatus)
            put("autoReplySuppressed", autoReplySuppressed)
        }

        historyArray.put(item)

        while (historyArray.length() > 50) {
            historyArray.remove(0)
        }

        prefs.edit().putString("call_history", historyArray.toString()).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sprivo_status",
                "SPRIVO 상태 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "부재중 자동응답 결과 알림"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showLocalNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, "sprivo_status")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        } catch (e: SecurityException) {
            Log.e("SPRIVO", "로컬 알림 권한 오류", e)
        }
    }

    private fun formatPhoneNumber(phoneNumber: String): String {
        val digits = phoneNumber.filter { it.isDigit() }

        return when {
            digits.startsWith("02") && digits.length == 9 ->
                "${digits.substring(0, 2)}-${digits.substring(2, 5)}-${digits.substring(5)}"

            digits.startsWith("02") && digits.length == 10 ->
                "${digits.substring(0, 2)}-${digits.substring(2, 6)}-${digits.substring(6)}"

            digits.length == 11 ->
                "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"

            digits.length == 10 ->
                "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"

            digits.length == 8 ->
                "${digits.substring(0, 4)}-${digits.substring(4)}"

            else -> phoneNumber
        }
    }
}

private fun Context.resolveLatestMissedNumberFromCallLog(
    postTime: Long,
    windowMs: Long = 90_000L
): String? {
    if (androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_CALL_LOG
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        Log.d("SPRIVO", "READ_CALL_LOG 권한 없음 - CallLog fallback 불가")
        return null
    }

    val attemptDelaysMs = listOf(0L, 900L, 1800L)

    attemptDelaysMs.forEachIndexed { attemptIndex, delayMs ->
        if (delayMs > 0L) {
            Log.d(
                "SPRIVO",
                "CallLog fallback retry attempt=${attemptIndex + 1} delay=${delayMs}ms"
            )
            try {
                Thread.sleep(delayMs)
            } catch (ignored: InterruptedException) {
                Log.d("SPRIVO", "CallLog fallback interrupted")
                return null
            }
        } else {
            Log.d("SPRIVO", "CallLog fallback attempt=1 (immediate)")
        }

        val number = try {
            contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.TYPE
                ),
                "${android.provider.CallLog.Calls.TYPE}=? AND ${android.provider.CallLog.Calls.DATE}>=?",
                arrayOf(
                    android.provider.CallLog.Calls.MISSED_TYPE.toString(),
                    (postTime - windowMs).toString()
                ),
                "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e("SPRIVO", "CallLog fallback 실패", e)
            null
        }

        if (number != null) {
            Log.d(
                "SPRIVO",
                "CallLog fallback success attempt=${attemptIndex + 1}: $number"
            )
            return number
        } else {
            Log.d(
                "SPRIVO",
                "CallLog fallback attempt=${attemptIndex + 1} returned null"
            )
        }
    }

    Log.d("SPRIVO", "CallLog fallback failed after ${attemptDelaysMs.size} attempts")
    return null
}
