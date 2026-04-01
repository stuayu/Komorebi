package com.beeregg2001.komorebi.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

/**
 * AIが1文字ずつ喋る（タイピングされる）エフェクトのコンポーネント
 */
@Composable
fun TypingText(text: String, modifier: Modifier = Modifier) {
    var displayedText by remember { mutableStateOf("") }

    LaunchedEffect(text) {
        displayedText = ""
        for (i in text.indices) {
            displayedText += text[i]
            delay(30)
        }
    }

    Text(
        text = displayedText,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), // ★文字を小さく
        color = KomorebiTheme.colors.textPrimary,
        modifier = modifier,
        lineHeight = 20.sp // ★行間もスッキリと
    )
}

/**
 * 画面右側からスライドインするモダンなAIチャットパネル
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AiConciergePanel(
    isOpen: Boolean,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val micFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            delay(300)
            runCatching { micFocusRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        BackHandler(enabled = isOpen) {
            onClose()
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.3f)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                colors.background.copy(alpha = 0.85f),
                                colors.background.copy(alpha = 0.98f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = colors.textPrimary.copy(alpha = 0.1f)
                    )
                    .padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                // --- ヘッダー ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI",
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI コンシェルジュ",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), // ★タイトルも小さく
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                // --- メッセージエリア ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // ユーザーの吹き出し
                        Box(
                            modifier = Modifier
                                .align(Alignment.End)
                                .background(
                                    colors.textPrimary.copy(alpha = 0.1f),
                                    RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "なんか面白いバラエティある？",
                                color = colors.textSecondary,
                                fontSize = 13.sp
                            )
                        }

                        // AIの吹き出し
                        Box(
                            modifier = Modifier
                                .align(Alignment.Start)
                                .background(
                                    colors.accent.copy(alpha = 0.15f),
                                    RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            if (isOpen) {
                                TypingText(
                                    text = "お疲れ様です！録画リストを確認しました。\n\n頭を空っぽにして笑える「水曜日のダウンタウン」の最新回が録画されていますよ！\n\n今すぐ再生しますか？"
                                )
                            }
                        }
                    }
                }

                // --- マイクボタン ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Surface(
                        onClick = { /* TODO: Gemini音声入力開始 */ },
                        modifier = Modifier
                            .size(56.dp) // ★ボタンもスリムに
                            .focusRequester(micFocusRequester),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = colors.accent.copy(alpha = 0.2f),
                            focusedContainerColor = colors.accent
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "音声入力",
                                modifier = Modifier.size(24.dp),
                                tint = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "ボタンを押して話しかける",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}