package com.vaelin.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.theme.ThemeMode

/**
 * 启动页。
 *
 * 规格书第 10 条的硬要求：「非官方修改版」与「本启动器由 AI 完成」两行
 * 必须原样、无条件地出现在启动页上。所以这两行是 SplashScreen 的固定内容，
 * 既不放进 if/else，也不做成「首次启动才显示」。
 *
 * 这里不做自动跳转：启动页只在用户按下「进入启动器」后离开，
 * 保证每次启动这两行标注都真的被看到，而不是闪过 1 秒。
 */
@Composable
fun SplashScreen(
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        TextButton(
            onClick = onCycleTheme,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) {
            Text("主题：${themeMode.displayName()}")
        }

        Column(
            modifier = Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Vaelin",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Minecraft Java 版启动器",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            // —— 规格书第 10 条：以下两行不可省略、不可条件显示 ——
            Text(
                text = "非官方修改版",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "本启动器由 AI 完成",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            // —— 硬要求结束 ——

            Spacer(Modifier.height(36.dp))

            Button(onClick = onEnter) {
                Text("进入启动器")
            }
        }
    }
}
