package com.example.etfbuyalert.ui.screen.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.data.model.NotificationLog

// 履歴タブ — 過去に出した通知の一覧
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    notifications: List<NotificationLog>,
    onReload: () -> Unit,
    onClear: () -> Unit
) {
    // 画面表示のたびに最新の履歴を読み直す（StateFlow外で書かれるため）
    LaunchedEffect(Unit) { onReload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知履歴", fontWeight = FontWeight.Bold) },
                actions = {
                    if (notifications.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("クリア") }
                    }
                }
            )
        }
    ) { pad ->
        if (notifications.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("通知履歴はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(notifications) { log -> NotificationCard(log) }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun NotificationCard(log: NotificationLog) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(log.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${log.date} ${log.time}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(log.message, fontSize = 13.sp)
        }
    }
}
