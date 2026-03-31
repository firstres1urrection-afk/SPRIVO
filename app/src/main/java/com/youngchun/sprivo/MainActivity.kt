package com.youngchun.sprivo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SprivoApp()
                }
            }
        }
    }
}

data class CallHistoryItem(
    val time: String,
    val phoneNumber: String,
    val mode: String,
    val status: String,
    val reason: String,
    val groupId: String = "",
    val logId: String = "",
    val providerStatus: String = ""
)

data class FinalNotificationPayload(
    val phoneNumber: String,
    val status: String
)

@Composable
fun SprivoApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sprivo", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf("home") }
    var selectedMode by remember {
        mutableStateOf(prefs.getString("mode", "해외 체류") ?: "해외 체류")
    }
    var isActive by remember {
        mutableStateOf(prefs.getBoolean("is_active", false))
    }

    var overseasMessage by remember {
        mutableStateOf(
            prefs.getString(
                "message_overseas",
                "현재 해외 체류 중입니다. 문자로 연락 부탁드립니다."
            ) ?: "현재 해외 체류 중입니다. 문자로 연락 부탁드립니다."
        )
    }

    var drivingMessage by remember {
        mutableStateOf(
            prefs.getString(
                "message_driving",
                "현재 운전 중입니다. 문자로 연락 부탁드립니다."
            ) ?: "현재 운전 중입니다. 문자로 연락 부탁드립니다."
        )
    }

    var meetingMessage by remember {
        mutableStateOf(
            prefs.getString(
                "message_meeting",
                "현재 회의 중입니다. 종료 후 연락드리겠습니다."
            ) ?: "현재 회의 중입니다. 종료 후 연락드리겠습니다."
        )
    }

    val historyItems = remember { mutableStateListOf<CallHistoryItem>() }
    var isRefreshingPending by remember { mutableStateOf(false) }

    fun reloadHistory() {
        historyItems.clear()
        historyItems.addAll(loadCallHistory(context))
    }

    LaunchedEffect(Unit) {
        reloadHistory()
        isRefreshingPending = true
        refreshPendingHistory(context)
        reloadHistory()
        isRefreshingPending = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "SPRIVO",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TabButton(
                text = "홈",
                selected = selectedTab == "home",
                onClick = { selectedTab = "home" },
                modifier = Modifier.weight(1f)
            )
            TabButton(
                text = "기록 보기",
                selected = selectedTab == "history",
                onClick = { selectedTab = "history" },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            "home" -> {
                HomeScreen(
                    selectedMode = selectedMode,
                    onModeChange = { mode ->
                        selectedMode = mode
                        prefs.edit().putString("mode", mode).apply()
                    },
                    isActive = isActive,
                    onActiveChange = { active ->
                        isActive = active
                        prefs.edit().putBoolean("is_active", active).apply()
                    },
                    overseasMessage = overseasMessage,
                    onOverseasMessageChange = {
                        overseasMessage = it
                        prefs.edit().putString("message_overseas", it).apply()
                    },
                    drivingMessage = drivingMessage,
                    onDrivingMessageChange = {
                        drivingMessage = it
                        prefs.edit().putString("message_driving", it).apply()
                    },
                    meetingMessage = meetingMessage,
                    onMeetingMessageChange = {
                        meetingMessage = it
                        prefs.edit().putString("message_meeting", it).apply()
                    },
                    latestHistory = historyItems.lastOrNull(),
                    isRefreshingPending = isRefreshingPending,
                    onOpenNotificationAccess = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    onRefreshPending = {
                        scope.launch {
                            isRefreshingPending = true
                            refreshPendingHistory(context)
                            reloadHistory()
                            isRefreshingPending = false
                        }
                    }
                )
            }

            "history" -> {
                HistoryScreen(
                    items = historyItems.reversed(),
                    isRefreshingPending = isRefreshingPending,
                    onRefresh = {
                        scope.launch {
                            isRefreshingPending = true
                            refreshPendingHistory(context)
                            reloadHistory()
                            isRefreshingPending = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    selectedMode: String,
    onModeChange: (String) -> Unit,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    overseasMessage: String,
    onOverseasMessageChange: (String) -> Unit,
    drivingMessage: String,
    onDrivingMessageChange: (String) -> Unit,
    meetingMessage: String,
    onMeetingMessageChange: (String) -> Unit,
    latestHistory: CallHistoryItem?,
    isRefreshingPending: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRefreshPending: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusCard(
            selectedMode = selectedMode,
            isActive = isActive,
            onActiveChange = onActiveChange
        )

        CardBox {
            Text(
                text = "상태 선택",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            listOf("해외 체류", "운전 중", "회의 중").forEach { mode ->
                ModeRow(
                    text = mode,
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        CardBox {
            Text(
                text = "문구 수정",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = overseasMessage,
                onValueChange = onOverseasMessageChange,
                label = { Text("해외 체류 문구") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = drivingMessage,
                onValueChange = onDrivingMessageChange,
                label = { Text("운전 중 문구") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = meetingMessage,
                onValueChange = onMeetingMessageChange,
                label = { Text("회의 중 문구") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        CardBox {
            Text(
                text = "권한 및 상태",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onOpenNotificationAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SPRIVO 시작하기")
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onRefreshPending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRefreshingPending) "발송 결과 확인 중..." else "발송 결과 새로고침")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "문자사 처리에 몇 초 걸릴 수 있습니다. 잠시 후 '발송 결과 새로고침'을 눌러 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF666666)
            )
        }

        CardBox {
            Text(
                text = "최근 처리",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (latestHistory == null) {
                Text("아직 처리 기록이 없습니다.")
            } else {
                HistorySummaryCard(item = latestHistory)
            }
        }
    }
}

@Composable
fun HistoryScreen(
    items: List<CallHistoryItem>,
    isRefreshingPending: Boolean,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRefreshingPending) "발송 결과 확인 중..." else "발송 결과 새로고침")
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty()) {
            CardBox {
                Text("저장된 기록이 없습니다.")
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items) { item ->
                HistorySummaryCard(item = item)
            }
        }
    }
}

@Composable
fun StatusCard(
    selectedMode: String,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFFEFFAF3) else Color(0xFFF7F7F7)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isActive) "부재중 알림 켜짐" else "부재중 알림 꺼짐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color(0xFF1B5E20) else Color(0xFF555555)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("현재 모드: $selectedMode")

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (isActive) "자동 발송 켜짐" else "자동 발송 꺼짐")
                Switch(
                    checked = isActive,
                    onCheckedChange = onActiveChange
                )
            }
        }
    }
}

@Composable
fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
    ) {
        Text(if (selected) "✓ $text" else text)
    }
}

@Composable
fun ModeRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFEFF6FF) else Color.White
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selected) "● $text" else "○ $text",
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun HistorySummaryCard(item: CallHistoryItem) {
    val displayReason = toKoreanReason(item.reason, item.providerStatus)
    val shouldShowReason = item.status.lowercase() != "success" && displayReason.isNotBlank()

    CardBox {
        Text(item.time, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text("번호: ${formatPhoneNumber(item.phoneNumber)}")
        Text("상태: ${item.mode}")
        Text("결과: ${toKoreanStatus(item.status)}")

        if (shouldShowReason) {
            Text("사유: $displayReason")
        }
    }
}

@Composable
fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

fun loadCallHistory(context: Context): List<CallHistoryItem> {
    val prefs = context.getSharedPreferences("sprivo", Context.MODE_PRIVATE)
    val historyJson = prefs.getString("call_history", "[]") ?: "[]"
    val array = JSONArray(historyJson)
    val result = mutableListOf<CallHistoryItem>()

    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        result.add(
            CallHistoryItem(
                time = obj.optString("time", ""),
                phoneNumber = obj.optString("phoneNumber", ""),
                mode = obj.optString("mode", ""),
                status = obj.optString("status", ""),
                reason = obj.optString("reason", ""),
                groupId = obj.optString("groupId", ""),
                logId = obj.optString("logId", ""),
                providerStatus = obj.optString("providerStatus", "")
            )
        )
    }

    return result
}

fun saveCallHistoryList(context: Context, items: List<CallHistoryItem>) {
    val prefs = context.getSharedPreferences("sprivo", Context.MODE_PRIVATE)
    val array = JSONArray()

    items.forEach { item ->
        val obj = JSONObject().apply {
            put("time", item.time)
            put("phoneNumber", item.phoneNumber)
            put("mode", item.mode)
            put("status", item.status)
            put("reason", item.reason)
            put("groupId", item.groupId)
            put("logId", item.logId)
            put("providerStatus", item.providerStatus)
        }
        array.put(obj)
    }

    prefs.edit().putString("call_history", array.toString()).apply()
}

suspend fun refreshPendingHistory(context: Context) {
    withContext(Dispatchers.IO) {
        val current = loadCallHistory(context).toMutableList()
        if (current.isEmpty()) return@withContext

        val client = OkHttpClient()
        var changed = false
        val notificationsToShow = mutableListOf<FinalNotificationPayload>()

        val updated = current.map { item ->
            if (item.status != "pending") {
                return@map item
            }

            if (item.groupId.isBlank()) {
                changed = true
                return@map item.copy(
                    reason = if (item.reason.isBlank()) {
                        "MISSING_GROUP_ID_OR_LOG_ID"
                    } else {
                        item.reason
                    }
                )
            }

            try {
                val urlBuilder = "https://project-3cnvx.vercel.app/api/call-status"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("groupId", item.groupId)

                if (item.logId.isNotBlank()) {
                    urlBuilder.addQueryParameter("logId", item.logId)
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        changed = true
                        notificationsToShow.add(
                            FinalNotificationPayload(
                                phoneNumber = item.phoneNumber,
                                status = "fail"
                            )
                        )
                        return@use item.copy(
                            status = "fail",
                            reason = "HTTP_${response.code}",
                            providerStatus = item.providerStatus
                        )
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        changed = true
                        notificationsToShow.add(
                            FinalNotificationPayload(
                                phoneNumber = item.phoneNumber,
                                status = "fail"
                            )
                        )
                        return@use item.copy(
                            status = "fail",
                            reason = "EMPTY_RESPONSE",
                            providerStatus = item.providerStatus
                        )
                    }

                    val json = JSONObject(body)
                    val newStatus = json.optString("status", item.status)
                    val newReason = json.optString("reason", item.reason)
                    val newProviderStatus = json.optString("providerStatus", item.providerStatus)

                    if (
                        newStatus != item.status ||
                        newReason != item.reason ||
                        newProviderStatus != item.providerStatus
                    ) {
                        changed = true
                    }

                    if (item.status == "pending" && (newStatus == "success" || newStatus == "fail")) {
                        notificationsToShow.add(
                            FinalNotificationPayload(
                                phoneNumber = item.phoneNumber,
                                status = newStatus
                            )
                        )
                    }

                    return@use item.copy(
                        status = newStatus,
                        reason = newReason,
                        providerStatus = newProviderStatus
                    )
                }
            } catch (_: Exception) {
                item
            }
        }

        if (changed) {
            saveCallHistoryList(context, updated)
        }

        notificationsToShow.forEach { payload ->
            showFinalStatusNotification(context, payload)
        }
    }
}

fun ensureSprivoNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "sprivo_status",
            "SPRIVO 상태 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "부재중 자동응답 결과 알림"
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

fun showFinalStatusNotification(context: Context, payload: FinalNotificationPayload) {
    val title: String
    val message: String

    when (payload.status) {
        "success" -> {
            title = "SPRIVO 부재중 알림"
            message = "${formatPhoneNumber(payload.phoneNumber)}에 부재중 알림을 전송했습니다."
        }
        "fail" -> {
            title = "SPRIVO 부재중 알림 실패"
            message = "${formatPhoneNumber(payload.phoneNumber)}에 부재중 알림을 전송하지 못했습니다."
        }
        else -> return
    }

    ensureSprivoNotificationChannel(context)

    val notification = NotificationCompat.Builder(context, "sprivo_status")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    try {
        NotificationManagerCompat.from(context)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    } catch (e: SecurityException) {
        Log.e("SPRIVO", "최종 상태 알림 권한 오류", e)
    }
}

fun toKoreanStatus(status: String): String {
    return when (status.lowercase()) {
        "pending" -> "확인 중"
        "success" -> "보냄"
        "fail" -> "안 보냄"
        "skipped" -> "보내지 않음"
        else -> "상태 확인 필요"
    }
}

fun toKoreanReason(reason: String, providerStatus: String = ""): String {
    return when (reason) {
        "SOLAPI_ACCEPTED_NOT_COMPLETED" -> "아직 확인 중"
        "STILL_PROCESSING" -> "아직 확인 중"
        "DELIVERY_CONFIRMED" -> "발송 확인됨"
        "DELIVERY_FAILED_OR_PROVIDER_ERROR" -> "발송 실패"
        "PHONE_NORMALIZATION_FAILED" -> "번호 형식 오류"
        "METHOD_NOT_ALLOWED" -> "잘못된 요청"
        "SERVER_CONFIG_ERROR" -> "서버 설정 오류"
        "SERVER_INTERNAL_ERROR" -> "서버 오류"
        "NETWORK_ERROR" -> "인터넷 오류"
        "EMPTY_RESPONSE" -> "응답 없음"
        "JSON_PARSE_ERROR" -> "응답 오류"
        "SOLAPI_ERROR" -> "문자 요청 오류"
        "SOLAPI_REGISTERED_ZERO" -> "문자 접수 실패"
        "MISSING_GROUP_ID_OR_LOG_ID" -> "조회 정보 없음"
        "UNKNOWN_RESPONSE" -> "알 수 없는 응답"
        else -> {
            when {
                reason.startsWith("HTTP_404") -> "서버 주소 오류"
                reason.startsWith("HTTP_") -> "서버 응답 오류"
                reason.isNotBlank() -> reason
                providerStatus.isNotBlank() -> providerStatus
                else -> ""
            }
        }
    }
}

fun formatPhoneNumber(phoneNumber: String): String {
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
