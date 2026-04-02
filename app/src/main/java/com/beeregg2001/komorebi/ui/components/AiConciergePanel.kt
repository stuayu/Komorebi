@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.main.AiFocusTicket
import com.beeregg2001.komorebi.ui.main.AiFocusTicketManager
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AiConciergePanel(
    isOpen: Boolean,
    chatHistory: List<ChatMessage>,
    isSpeechSupported: Boolean,
    isRecording: Boolean,
    ticketManager: AiFocusTicketManager,
    onClose: () -> Unit,
    onMicLongPressStart: () -> Unit,
    onMicLongPressEnd: () -> Unit,
    onKeyboardClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val micFocusRequester = remember { FocusRequester() }
    val keyboardFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    val scrollState = rememberScrollState()
    var isScrollAreaFocused by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val defaultRequester = if (isSpeechSupported) micFocusRequester else keyboardFocusRequester

    LaunchedEffect(isOpen) {
        if (isOpen) {
            delay(300); runCatching { defaultRequester.requestFocus() }
        }
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime, isOpen) {
        if (isOpen && ticketManager.currentTicket == AiFocusTicket.PANEL_DEFAULT) {
            delay(150); defaultRequester.safeRequestFocusWithRetry("AiPanelDefault")
            ticketManager.consume(AiFocusTicket.PANEL_DEFAULT)
        }
    }

    LaunchedEffect(chatHistory.size, isRecording) {
        delay(100); scrollState.animateScrollTo(scrollState.maxValue)
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        BackHandler(enabled = isOpen) { onClose() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.key == Key.Back || event.key == Key.Escape) return@onKeyEvent false
                    val isDpad = event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                            event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    isDpad
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.35f)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                colors.background.copy(
                                    alpha = 0.85f
                                ), colors.background.copy(alpha = 0.98f)
                            )
                        )
                    )
                    .border(width = 1.dp, color = colors.textPrimary.copy(alpha = 0.1f))
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .focusGroup()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "AI",
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "AI コンシェルジュ",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(
                            width = 2.dp,
                            color = if (isScrollAreaFocused) colors.accent.copy(alpha = 0.6f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(contentFocusRequester)
                            .onFocusChanged { isScrollAreaFocused = it.isFocused || it.hasFocus }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && isScrollAreaFocused) {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            if (scrollState.value > 0) {
                                                coroutineScope.launch {
                                                    scrollState.animateScrollTo(
                                                        scrollState.value - 400
                                                    )
                                                }; return@onKeyEvent true
                                            }
                                        }

                                        Key.DirectionDown -> {
                                            if (scrollState.value < scrollState.maxValue - 5) {
                                                coroutineScope.launch {
                                                    scrollState.animateScrollTo(
                                                        scrollState.value + 400
                                                    )
                                                }; return@onKeyEvent true
                                            } else {
                                                runCatching { defaultRequester.requestFocus() }; return@onKeyEvent true
                                            }
                                        }
                                    }
                                }
                                false
                            }
                            .verticalScroll(scrollState)
                            .focusable()
                            .padding(8.dp)
                    ) {
                        if (chatHistory.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .background(
                                        colors.textPrimary.copy(alpha = 0.05f),
                                        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                val greetingText = if (isSpeechSupported) {
                                    "こんにちは！何かお手伝いできることはありますか？\n\n・マイクボタンを「長押し」して話す\n・文字入力ボタンでキーボードを使う\n\nどちらでも対応可能です！"
                                } else {
                                    "こんにちは！何かお手伝いできることはありますか？\n\n文字入力ボタンからキーボードを開いて、質問や指示を入力してください！"
                                }
                                TypingText(text = greetingText, scrollState = scrollState)
                            }
                        } else {
                            chatHistory.forEach { msg ->
                                key(msg.id) {
                                    Box(
                                        modifier = Modifier
                                            .align(if (msg.isUser) Alignment.End else Alignment.Start)
                                            .background(
                                                if (msg.isUser) colors.textPrimary.copy(alpha = 0.1f) else colors.accent.copy(
                                                    alpha = 0.15f
                                                ),
                                                if (msg.isUser) RoundedCornerShape(
                                                    16.dp,
                                                    16.dp,
                                                    4.dp,
                                                    16.dp
                                                ) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        if (msg.isThinking) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(
                                                    color = colors.accent,
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    "考え中...",
                                                    color = colors.textPrimary,
                                                    fontSize = 14.sp
                                                )
                                            }
                                        } else {
                                            if (msg.isUser) Text(
                                                msg.text,
                                                color = colors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                            else TypingText(msg.text, scrollState)
                                        }
                                    }
                                }
                            }
                        }

                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .background(
                                        colors.textPrimary.copy(alpha = 0.1f),
                                        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("録音中...", color = colors.textPrimary, fontSize = 14.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                var isMicLongPressHandled by remember { mutableStateOf(false) }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    // --- 音声入力ボタン ---
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            onClick = { /* クリック時は何もしない */ },
                            enabled = isSpeechSupported,
                            modifier = Modifier
                                .size(56.dp)
                                .focusRequester(micFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    val isCenterKey =
                                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                    if (isCenterKey) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            if (event.nativeKeyEvent.isLongPress && !isMicLongPressHandled) {
                                                isMicLongPressHandled = true
                                                onMicLongPressStart()
                                                return@onPreviewKeyEvent true
                                            }
                                            if (isMicLongPressHandled) return@onPreviewKeyEvent true
                                        } else if (event.type == KeyEventType.KeyUp) {
                                            if (isMicLongPressHandled) {
                                                isMicLongPressHandled = false
                                                onMicLongPressEnd()
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                    }

                                    // ★ 修正: 強制的にフォーカスをワープさせる
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                runCatching { contentFocusRequester.requestFocus() }
                                                return@onPreviewKeyEvent true
                                            }

                                            Key.DirectionRight -> {
                                                runCatching { keyboardFocusRequester.requestFocus() }
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                    }
                                    false
                                },
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isRecording) Color(0xFFFF5252) else if (isSpeechSupported) colors.accent.copy(
                                    alpha = 0.2f
                                ) else colors.textPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = if (isRecording) Color(0xFFFF5252) else colors.accent,
                                disabledContainerColor = colors.textPrimary.copy(alpha = 0.05f),
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "音声",
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isSpeechSupported) Color.White else colors.textPrimary.copy(
                                        alpha = 0.3f
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (isSpeechSupported) "音声入力(長押し)" else "非対応",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = colors.textSecondary
                        )
                    }

                    // --- 文字入力ボタン ---
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            onClick = onKeyboardClick,
                            modifier = Modifier
                                .size(56.dp)
                                .focusRequester(keyboardFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    // ★ 修正: 強制的にフォーカスをワープさせる
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                runCatching { contentFocusRequester.requestFocus() }
                                                return@onPreviewKeyEvent true
                                            }

                                            Key.DirectionLeft -> {
                                                if (isSpeechSupported) {
                                                    runCatching { micFocusRequester.requestFocus() }
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                        }
                                    }
                                    false
                                },
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(
                                    alpha = 0.1f
                                ), focusedContainerColor = colors.textPrimary
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Keyboard,
                                    contentDescription = "文字入力",
                                    modifier = Modifier.size(24.dp),
                                    tint = if (colors.isDark) Color.Black else Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "文字入力",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingText(text: String, scrollState: ScrollState) {
    var displayedText by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        displayedText = ""
        for (i in text.indices) {
            displayedText += text[i]
            delay(15)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    Text(
        text = displayedText,
        color = KomorebiTheme.colors.textPrimary,
        fontSize = 14.sp,
        lineHeight = 22.sp
    )
}