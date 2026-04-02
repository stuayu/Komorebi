package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.content
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

sealed class AiConciergeAction {
    data class PlayLive(val channelId: String) : AiConciergeAction()
    data class PlayRecorded(val videoId: Int) : AiConciergeAction()
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val isThinking: Boolean = false
)

@HiltViewModel
class AiConciergeViewModel @Inject constructor() : ViewModel() {

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

    private val _pendingAction = MutableSharedFlow<AiConciergeAction>()
    val pendingAction = _pendingAction.asSharedFlow()

    private val apiKey = ""

    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = apiKey,
        systemInstruction = content {
            text(
                "あなたはTVアプリ「Komorebi」の優秀で親しみやすいAIコンシェルジュです。\n" +
                        "ユーザーの視聴履歴や録画リストを知っています。\n" +
                        "番組を勧める際は「1. 番組名」のように番号を振ってください。\n\n" +
                        "【超重要：再生アクションの実行方法】\n" +
                        "ユーザーが番組の再生を指示した場合、回答テキストの最後に以下の形式の【特殊コマンドタグ】を出力してください。\n" +
                        "ライブ放送の場合: [PLAY_LIVE:提供されたLIVE_ID]\n" +
                        "録画番組の場合: [PLAY_REC:提供されたREC_ID]\n\n" +
                        "※【警告1】ユーザーが「1番を再生して」と指示した場合、直前の会話履歴から1番として提案した番組を読み取り、その番組の実際の REC_ID または LIVE_ID をコンテキストから探し出してタグに入れてください。絶対に [PLAY_REC:1] のようにリスト番号をそのまま出力しないでください。\n" +
                        "※【警告2】コンテキストで提供された情報の中に、ユーザーが求める番組のIDが存在しない場合は、絶対にコマンドタグを出力せず、「申し訳ありません。その番組は直近のリストに見当たらないため、お手数ですがビデオタブから探してみてください。」と正直に案内してください。"
            )
        }
    )

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendTextWithContext(
        userInput: String,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        viewModelScope.launch {
            val userMsg = ChatMessage(isUser = true, text = userInput)
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            val historyText = buildHistoryText()
            _chatHistory.value = _chatHistory.value + userMsg + aiThinkingMsg

            try {
                val contextPrompt = withContext(Dispatchers.Default) {
                    buildContextPrompt(
                        liveChannels,
                        recentRecordings,
                        groupedSeries,
                        activeReserves
                    )
                }
                val response =
                    generativeModel.generateContent(content { text("$contextPrompt\n$historyText\n\n現在のユーザーの指示: 「$userInput」") })
                handleAiResponse(response.text, aiThinkingMsg.id, null)
            } catch (e: Exception) {
                handleAiError(e, aiThinkingMsg.id)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendAudioWithContext(
        audioBytes: ByteArray,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        viewModelScope.launch {
            // ★ 初期状態は「解析中」にしておく
            val userMsgId = UUID.randomUUID().toString()
            val userMsg = ChatMessage(id = userMsgId, isUser = true, text = "🎤 音声を解析中...")
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            val historyText = buildHistoryText()
            _chatHistory.value = _chatHistory.value + userMsg + aiThinkingMsg

            try {
                val contextPrompt = withContext(Dispatchers.Default) {
                    buildContextPrompt(
                        liveChannels,
                        recentRecordings,
                        groupedSeries,
                        activeReserves
                    )
                }
                val response = generativeModel.generateContent(content {
                    blob("audio/wav", audioBytes)
                    text(
                        "$contextPrompt\n$historyText\n\n" +
                                "【超重要】添付された音声の指示内容を理解し、要望に答えてください。再生指示があった場合は特殊コマンドタグを出力してください。\n" +
                                "また、必ずレスポンスの1行目に「認識結果: (ユーザーが言った言葉)」という形式で、聞き取った言葉を出力してから、次の行にAIとしての回答を続けてください。"
                    )
                })
                handleAiResponse(response.text, aiThinkingMsg.id, userMsgId)
            } catch (e: Exception) {
                handleAiError(e, aiThinkingMsg.id)
            }
        }
    }

    private suspend fun handleAiResponse(rawText: String?, messageId: String, userMsgId: String?) {
        var responseText = rawText ?: ""

        // ★ 1. Geminiに言わせた「認識結果」を抽出してユーザーの吹き出しを更新
        if (userMsgId != null) {
            val recognizedRegex = Regex("""認識結果:\s*(.+)""")
            val recognizedMatch = recognizedRegex.find(responseText)
            if (recognizedMatch != null) {
                val recognizedText = recognizedMatch.groupValues[1]
                _chatHistory.value = _chatHistory.value.map {
                    if (it.id == userMsgId) it.copy(text = "🎤 「$recognizedText」") else it
                }
                responseText = responseText.replace(recognizedRegex, "").trim()
            } else {
                _chatHistory.value = _chatHistory.value.map {
                    if (it.id == userMsgId) it.copy(text = "🎤 (音声入力)") else it
                }
            }
        }

        // 2. コマンドタグの抽出
        val liveRegex = Regex("""\[PLAY_LIVE:(.+?)\]""")
        val recRegex = Regex("""\[PLAY_REC:(.+?)\]""")

        val liveMatch = liveRegex.find(responseText)
        val recMatch = recRegex.find(responseText)

        if (liveMatch != null) responseText = responseText.replace(liveRegex, "").trim()
        if (recMatch != null) responseText = responseText.replace(recRegex, "").trim()

        if (responseText.isBlank() && (liveMatch != null || recMatch != null)) {
            responseText = "はい、再生を開始します！"
        } else if (responseText.isBlank()) {
            responseText = "すみません、うまく処理できませんでした。"
        }

        // 3. AIの吹き出しを更新
        _chatHistory.value = _chatHistory.value.map {
            if (it.id == messageId) it.copy(text = responseText, isThinking = false) else it
        }

        // 4. 文字が表示されきるまで待機してから再生アクションを実行
        val displayDelayMs = (responseText.length * 15L) + 1500L

        if (liveMatch != null) {
            viewModelScope.launch {
                delay(displayDelayMs)
                _pendingAction.emit(AiConciergeAction.PlayLive(liveMatch.groupValues[1]))
            }
        } else if (recMatch != null) {
            val id = recMatch.groupValues[1].toIntOrNull()
            if (id != null) {
                viewModelScope.launch {
                    delay(displayDelayMs)
                    _pendingAction.emit(AiConciergeAction.PlayRecorded(id))
                }
            }
        }
    }

    private fun handleAiError(e: Exception, messageId: String) {
        val errorText =
            if (e is QuotaExceededException) "【API制限】短時間にリクエストが集中しました。少し待ってから再度お試しください。" else "エラーが発生しました。通信環境を確認してください。"
        _chatHistory.value = _chatHistory.value.map {
            if (it.id == messageId) it.copy(
                text = errorText,
                isThinking = false
            ) else it
        }
    }

    private fun buildHistoryText(): String {
        val recentHistory = _chatHistory.value.filter { !it.isThinking }.takeLast(6)
        if (recentHistory.isEmpty()) return ""
        val sb = StringBuilder("\n【直近の会話履歴】\n")
        recentHistory.forEach { msg -> sb.append("${if (msg.isUser) "ユーザー" else "AI"}: ${msg.text}\n") }
        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildContextPrompt(
        liveChannels: Map<String, List<Channel>>, recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>, activeReserves: List<ReserveItem>
    ): String {
        val sb = StringBuilder(2000)
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        sb.append("【現在の情報】\n時刻: ${LocalDateTime.now().format(formatter)}\n\n")
        sb.append("【現在放送中の番組（抜粋）】\n")
        liveChannels.values.flatten().filter { it.programPresent != null }.take(15)
            .forEach { ch -> sb.append("- ${ch.name}: ${ch.programPresent?.title} [LIVE_ID: ${ch.id}]\n") }
        sb.append("\n【録画済み番組（最新30件）】\n")
        recentRecordings.take(30).forEach { sb.append("- ${it.title} [REC_ID: ${it.id}]\n") }
        return sb.toString()
    }

    fun resetState() {
        _chatHistory.value = emptyList()
    }
}