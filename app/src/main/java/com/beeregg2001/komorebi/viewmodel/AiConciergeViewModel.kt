package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

sealed class AiConciergeAction {
    data class PlayLive(val channelId: String) : AiConciergeAction()
    data class PlayRecorded(val videoId: Int) : AiConciergeAction()
    data class SearchEpg(
        val keyword: String,
        val genre: String,
        val date: String,
        val isLiveOnly: Boolean,
        val channelName: String
    ) : AiConciergeAction()

    data class ReqEpgSearch(
        val keyword: String,
        val genre: String,
        val date: String,
        val isLiveOnly: Boolean,
        val channelName: String
    ) : AiConciergeAction()

    data class ReserveSingle(val programId: String) : AiConciergeAction()
    data class ReserveAuto(val keyword: String) : AiConciergeAction()
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val isThinking: Boolean = false,
    val isHidden: Boolean = false
)

data class AiContextData(
    val liveChannels: Map<String, List<Channel>>,
    val recentRecordings: List<RecordedProgram>,
    val groupedSeries: Map<String, List<SeriesInfo>>,
    val activeReserves: List<ReserveItem>
)

@HiltViewModel
class AiConciergeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _internalChatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _internalChatHistory
        .map { list -> list.filter { !it.isHidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingAction = MutableSharedFlow<AiConciergeAction>()
    val pendingAction = _pendingAction.asSharedFlow()

    private var lastContextData: AiContextData? = null

    private fun getGenerativeModel(apiKey: String): GenerativeModel {
        return GenerativeModel(
            modelName = "gemini-3.1-flash-lite-preview",
            apiKey = apiKey,
            systemInstruction = content {
                text(
                    "あなたはTVアプリ「Komorebi」の優秀で親しみやすいAIコンシェルジュです。\n" +
                            "ユーザーの意図を汲み取り、アプリの操作が必要な場合のみ【特殊コマンドタグ】を出力します。\n\n" +
                            "【重要: チャンネル切り替えの推論】\n" +
                            "「BS11にして」「日テレが見たい」などライブ視聴の要望があった場合、必ずコンテキストの【対応チャンネル一覧】から該当チャンネルを探し、名前の横にある LIVE_ID を使ってタグを出力してください。\n\n" +
                            "【重要: 番組のおすすめと裏側検索（Deep Dive）】\n" +
                            "ユーザーから「今やってる面白い映画ある？」「おすすめのアニメは？」など特定のジャンルや番組を尋ねられた場合、コンテキストの「現在放送中の主な番組」の中に該当するものが無ければ、\n" +
                            "すぐに諦めるのではなく、自ら `[REQ_EPG_SEARCH: | 映画 | | false | ]` のようにジャンルを指定して裏側検索を要求し、結果を待ってから回答してください。\n\n" +
                            "【重要: 回答の簡潔さ】\n" +
                            "録画リストや番組一覧などを聞かれた場合、コンテキストにある全ての番組を回答すると長すぎるため、代表的な3〜5件のみを抜粋して答えてください。\n\n" +
                            "【重要: 一般知識や雑談への対応（愛嬌と万能さ）】\n" +
                            "テレビ番組とは直接関係のない一般的な質問（楽曲情報、トリビア、天気、日常会話など）をされた場合、番組表にないことを謝罪する必要はありません。\n" +
                            "あなたは博識なアシスタントとして、その質問の答えを直接、明るく簡潔に教えてあげてください。\n\n" +
                            "【重要: タグを出力しないケース】\n" +
                            "「録画リストを教えて」「この番組について教えて」といった情報提供や、上記の「一般知識・雑談」など、画面遷移や再生・予約操作が不要な場合は、**例文であっても絶対にタグを出力しないでください**。\n\n" +
                            "【アプリ操作が必要な場合のタグ一覧】\n" +
                            "再生:\n" +
                            "・[PLAY_LIVE: LIVE_ID]  ※必ずLIVE_IDを指定\n" +
                            "・[PLAY_REC: REC_ID]\n\n" +
                            "番組検索・予約 (必ず [タグ名: キーワード | ジャンル | 日付 | 生中継(true/false) | チャンネル名] の形式。不要項目は空白にする):\n" +
                            "1. 検索結果を画面で見たい場合\n" +
                            "   ・[SEARCH_EPG: キーワード | ジャンル | 日付 | 生中継(true/false) | チャンネル名]\n" +
                            "   ※例：「明日の日テレのバラエティ」→ [SEARCH_EPG:  | バラエティ | 2026/04/03 | false | 日テレ]\n" +
                            "2. 録画予約したい場合、またはコンテキストに無いジャンルの現在放送中番組を探したい場合 (裏側で検索)\n" +
                            "   ・[REQ_EPG_SEARCH: キーワード | ジャンル | 日付 | 生中継(true/false) | チャンネル名]\n" +
                            "3. 予約の確定 (REQ_EPG_SEARCHの検索結果を受け取った後)\n" +
                            "   ・[RESERVE_SINGLE: PROGRAM_ID]\n"
                )
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendTextWithContext(
        userInput: String,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        viewModelScope.launch {
            lastContextData =
                AiContextData(liveChannels, recentRecordings, groupedSeries, activeReserves)
            val userMsg = ChatMessage(isUser = true, text = userInput)
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            val historyText = buildHistoryText()
            _internalChatHistory.value = _internalChatHistory.value + userMsg + aiThinkingMsg

            try {
                val rawApiKey = settingsRepository.geminiApiKey.first()
                val currentApiKey = rawApiKey.trim()
                Log.i(
                    "AI_Concierge",
                    "🔑 使用APIキー: [${currentApiKey}] (長さ: ${currentApiKey.length})"
                )

                if (currentApiKey.isBlank()) throw IllegalStateException("APIキーが設定されていません")

                val generativeModel = getGenerativeModel(currentApiKey)
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
            lastContextData =
                AiContextData(liveChannels, recentRecordings, groupedSeries, activeReserves)
            val userMsgId = UUID.randomUUID().toString()
            val userMsg = ChatMessage(id = userMsgId, isUser = true, text = "🎤 音声を解析中...")
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            val historyText = buildHistoryText()
            _internalChatHistory.value = _internalChatHistory.value + userMsg + aiThinkingMsg

            try {
                val rawApiKey = settingsRepository.geminiApiKey.first()
                val currentApiKey = rawApiKey.trim()
                Log.i(
                    "AI_Concierge",
                    "🔑 使用APIキー: [${currentApiKey}] (長さ: ${currentApiKey.length})"
                )

                if (currentApiKey.isBlank()) throw IllegalStateException("APIキーが設定されていません")

                val generativeModel = getGenerativeModel(currentApiKey)
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
                    text("$contextPrompt\n$historyText\n\n必ずレスポンスの1行目に「認識結果: (言葉)」と出力してから回答してください。")
                })
                handleAiResponse(response.text, aiThinkingMsg.id, userMsgId)
            } catch (e: Exception) {
                handleAiError(e, aiThinkingMsg.id)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun submitSilentSearchResult(keyword: String, results: List<UiSearchResultItem>) {
        viewModelScope.launch {
            val searchResultText = if (results.isEmpty()) {
                "システム: 条件に一致する検索結果は0件でした。謝罪するか、条件を緩めて再検索（[REQ_EPG_SEARCH: 別の単語 | | | false | ]）してください。"
            } else {
                val sb = java.lang.StringBuilder("システム: 検索結果は以下の通りです。\n")
                results.take(5).forEach { res ->
                    val p = res.program
                    sb.append("- ${p.title} (${p.start_time}) [PROGRAM_ID: ${p.id}] [LIVE_ID: ${res.channel.id}]\n")
                }
                sb.append("\nユーザーの意図に合うものがあれば [PLAY_LIVE: LIVE_ID] で再生、または [RESERVE_SINGLE: PROGRAM_ID] を出力し、毎週録画などの指定があれば [RESERVE_AUTO: キーワード] を出力して報告してください。")
                sb.toString()
            }

            _internalChatHistory.value = _internalChatHistory.value + ChatMessage(
                isUser = true,
                text = searchResultText,
                isHidden = true
            )
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            _internalChatHistory.value = _internalChatHistory.value + aiThinkingMsg

            try {
                val rawApiKey = settingsRepository.geminiApiKey.first()
                val currentApiKey = rawApiKey.trim()

                if (currentApiKey.isBlank()) throw IllegalStateException("APIキーが設定されていません")

                val generativeModel = getGenerativeModel(currentApiKey)
                val historyText = buildHistoryText()
                val contextData = lastContextData
                val contextPrompt = if (contextData != null) {
                    withContext(Dispatchers.Default) {
                        buildContextPrompt(
                            contextData.liveChannels,
                            contextData.recentRecordings,
                            contextData.groupedSeries,
                            contextData.activeReserves
                        )
                    }
                } else ""

                val response = generativeModel.generateContent(content {
                    text("$contextPrompt\n$historyText\n\nシステムからの検索結果を元に、ユーザーへ回答と最終的な操作タグを出力してください。")
                })
                handleAiResponse(response.text, aiThinkingMsg.id, null)
            } catch (e: Exception) {
                handleAiError(e, aiThinkingMsg.id)
            }
        }
    }

    private suspend fun handleAiResponse(rawText: String?, messageId: String, userMsgId: String?) {
        var responseText = rawText ?: ""

        Log.i("AI_Concierge", "🤖 AI Raw Output:\n$responseText")

        if (userMsgId != null) {
            val recognizedRegex = Regex("""認識結果:\s*(.+)""")
            val recognizedMatch = recognizedRegex.find(responseText)
            if (recognizedMatch != null) {
                val recognizedText = recognizedMatch.groupValues[1]
                _internalChatHistory.value = _internalChatHistory.value.map {
                    if (it.id == userMsgId) it.copy(text = "🎤 「$recognizedText」") else it
                }
                responseText = responseText.replace(recognizedRegex, "").trim()
            } else {
                _internalChatHistory.value = _internalChatHistory.value.map {
                    if (it.id == userMsgId) it.copy(text = "🎤 (音声入力)") else it
                }
            }
        }

        val liveRegex = Regex("""\[PLAY_LIVE:\s*([^\]\s]+)\]?""")
        val recRegex = Regex("""\[PLAY_REC:\s*([0-9]+)\]?""")
        val searchRegex = Regex("""\[SEARCH_EPG:\s*([^\]]+)\]""")
        val reqSearchRegex = Regex("""\[REQ_EPG_SEARCH:\s*([^\]]+)\]""")
        val resSingleRegex = Regex("""\[RESERVE_SINGLE:\s*([^\]\s]+).*?\]""")
//        val resAutoRegex = Regex("""\[RESERVE_AUTO:\s*([^\]\|]+).*?\]""")

        val liveMatch = liveRegex.find(responseText)
        val recMatch = recRegex.find(responseText)
        val searchMatch = searchRegex.find(responseText)
        val reqSearchMatch = reqSearchRegex.find(responseText)
        val resSingleMatches = resSingleRegex.findAll(responseText).toList()
//        val resAutoMatch = resAutoRegex.find(responseText)

        Log.i(
            "AI_Concierge", "🧩 Parsed Tags -> " +
                    "Live:${liveMatch?.groupValues}, Rec:${recMatch?.groupValues}, " +
                    "Search:${searchMatch?.groupValues}, ReqSearch:${reqSearchMatch?.groupValues}, " +
                    "ResSingle:${resSingleMatches.map { it.groupValues[1] }}"
        )

        listOf(
            liveRegex,
            recRegex,
            searchRegex,
            reqSearchRegex,
            resSingleRegex,
//            resAutoRegex
        ).forEach { regex ->
            responseText = responseText.replace(regex, "").trim()
        }

        if (responseText.isBlank() && (liveMatch != null || recMatch != null)) responseText =
            "はい、再生を開始します！"
        else if (responseText.isBlank() && searchMatch != null) responseText =
            "はい、番組表を検索します！"
        else if (responseText.isBlank() && (resSingleMatches.isNotEmpty())) responseText =
            "はい、予約を登録しました！"
        else if (responseText.isBlank() && reqSearchMatch == null) responseText =
            "すみません、うまく処理できませんでした。"

        _internalChatHistory.value = _internalChatHistory.value.map {
            if (it.id == messageId) it.copy(text = responseText, isThinking = false) else it
        }

        val displayDelayMs = (responseText.length * 15L) + 1500L

        if (liveMatch != null) {
            viewModelScope.launch {
                delay(displayDelayMs); _pendingAction.emit(
                AiConciergeAction.PlayLive(
                    liveMatch.groupValues[1].trim()
                )
            )
            }
        } else if (recMatch != null) {
            val id = recMatch.groupValues[1].trim().toIntOrNull()
            if (id != null) viewModelScope.launch {
                delay(displayDelayMs); _pendingAction.emit(
                AiConciergeAction.PlayRecorded(id)
            )
            }
        } else if (searchMatch != null) {
            val parts = searchMatch.groupValues[1].split("|").map { it.trim() }
            val kw = parts.getOrNull(0) ?: ""
            val gen = parts.getOrNull(1) ?: ""
            val date = parts.getOrNull(2) ?: ""
            val isLive = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false
            val channel = parts.getOrNull(4) ?: ""
            viewModelScope.launch {
                delay(displayDelayMs); _pendingAction.emit(
                AiConciergeAction.SearchEpg(
                    kw,
                    gen,
                    date,
                    isLive,
                    channel
                )
            )
            }
        } else if (reqSearchMatch != null) {
            val parts = reqSearchMatch.groupValues[1].split("|").map { it.trim() }
            val kw = parts.getOrNull(0) ?: ""
            val gen = parts.getOrNull(1) ?: ""
            val date = parts.getOrNull(2) ?: ""
            val isLive = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false
            val channel = parts.getOrNull(4) ?: ""
            _pendingAction.emit(AiConciergeAction.ReqEpgSearch(kw, gen, date, isLive, channel))
        } else if (resSingleMatches.isNotEmpty()) {
            viewModelScope.launch {
                delay(displayDelayMs)
                resSingleMatches.forEach { match ->
                    val id = match.groupValues[1].trim()
                    _pendingAction.emit(AiConciergeAction.ReserveSingle(id))
                }
            }
//        } else if (resAutoMatch != null) {
//            viewModelScope.launch {
//                delay(displayDelayMs); _pendingAction.emit(
//                AiConciergeAction.ReserveAuto(
//                    resAutoMatch.groupValues[1].trim()
//                )
//            )
//            }
        }
    }

    private fun handleAiError(e: Exception, messageId: String) {
        Log.e("AI_Concierge", "🚨 AI API通信エラー発生: ", e)
        val errorText = when {
            e is QuotaExceededException || e.message?.contains("429") == true -> "【API制限】少し待ってからお試しください。"
            e.message?.contains(
                "timeout",
                ignoreCase = true
            ) == true -> "【タイムアウト】Geminiサーバーからの応答がありません。"

            e.message?.contains(
                "API key not valid",
                ignoreCase = true
            ) == true -> "【認証エラー】APIキーが間違っています。設定画面を開いて登録し直してください。"

            e.message?.contains("APIキーが設定されていません") == true -> "【未設定】設定画面の「AIコンシェルジュ (Gemini)」からAPIキーを登録してください。"
            else -> "通信エラーが発生しました: ${e.localizedMessage}"
        }
        _internalChatHistory.value = _internalChatHistory.value.map {
            if (it.id == messageId) it.copy(
                text = errorText,
                isThinking = false
            ) else it
        }
    }

    private fun buildHistoryText(): String {
        val recentHistory = _internalChatHistory.value.filter { !it.isThinking }.takeLast(6)
        if (recentHistory.isEmpty()) return ""
        val sb = StringBuilder("\n【直近の会話履歴】\n")
        recentHistory.forEach { msg -> sb.append("${if (msg.isUser) "ユーザー(またはシステム)" else "AI"}: ${msg.text}\n") }
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

        // 全チャンネルの「名前:LIVE_ID」の辞書を生成（トークンを超節約）
        sb.append("【対応チャンネル一覧 (名前:LIVE_ID)】\n")
        liveChannels.forEach { (network, channels) ->
            val compressedCh = channels.joinToString(", ") { "${it.name}:${it.id}" }
            sb.append("- [$network] $compressedCh\n")
        }

        // ★ 修正: 放送波ごとに番組を「均等に」ピックアップして多様性を出す作戦
        sb.append("\n【現在放送中の主な番組（放送波ごとに抜粋）】\n")
        liveChannels.forEach { (network, channels) ->
            val activePrograms = channels.filter { it.programPresent != null }
            if (activePrograms.isNotEmpty()) {
                sb.append("[$network]\n")
                // 各放送波から最大4件ずつピックアップ
                activePrograms.take(4).forEach { ch ->
                    sb.append("- ${ch.name}: ${ch.programPresent?.title}\n")
                }
            }
        }

        sb.append("\n【録画済み番組（最新20件）】\n")
        recentRecordings.take(20).forEach { sb.append("- ${it.title} [REC_ID: ${it.id}]\n") }
        return sb.toString()
    }

    fun resetState() {
        _internalChatHistory.value = emptyList()
        lastContextData = null
    }
}