package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.OffsetDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _liveRows = MutableStateFlow<List<LiveRowState>>(emptyList())
    val liveRows: StateFlow<List<LiveRowState>> = _liveRows.asStateFlow()

    private val _groupedChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Channel>>> = _groupedChannels

    // ★修正: HomeViewModelの精巧な野球判定ロジックをライブチャンネル抽出にも適用
    private val baseballKeywords = listOf(
        "阪神", "タイガース", "広島", "カープ", "DeNA", "ベイスターズ",
        "巨人", "ジャイアンツ", "ヤクルト", "スワローズ", "中日", "ドラゴンズ",
        "オリックス", "バファローズ", "ロッテ", "マリーンズ", "ソフトバンク", "ホークス",
        "楽天", "イーグルス", "西武", "ライオンズ", "日本ハム", "ファイターズ", "プロ野球"
    )

    private val excludeKeywords = listOf(
        "特集", "ヴィンテージ", "ハイライト", "ダイジェスト", "ニュース",
        "名勝負", "傑作選", "セレクション", "トラリンク", "ガンガン！",
        "伝説", "回顧", "すぽると", "熱闘", "プロ野球ニュース"
    )

    private val matchKeywords = listOf("中継", "対", "×", "vs", "戦", "生放送", "LIVE")

    val baseballGroupedChannels: StateFlow<Map<String, List<Channel>>> =
        _groupedChannels.map { grouped ->
            val filtered = grouped.mapValues { (_, channels) ->
                channels.filter { ch ->
                    val title = ch.programPresent?.title ?: ""
                    val desc = ch.programPresent?.description ?: ""
                    val nextTitle = ch.programFollowing?.title ?: ""
                    val nextDesc = ch.programFollowing?.description ?: ""

                    // 1. 球団名または「プロ野球」が含まれているか
                    val hasKeyword = baseballKeywords.any { keyword ->
                        title.contains(keyword) || desc.contains(keyword) ||
                                nextTitle.contains(keyword) || nextDesc.contains(keyword)
                    }
                    if (!hasKeyword) return@filter false

                    // 2. 過去の試合やニュース番組ではないか（除外キーワード）
                    val isExcluded = excludeKeywords.any { keyword ->
                        title.contains(keyword) || nextTitle.contains(keyword)
                    }
                    if (isExcluded) return@filter false

                    // 3. 実際の「試合中継」であるか（マッチキーワード）
                    val isMatch = matchKeywords.any { keyword ->
                        title.contains(keyword, ignoreCase = true) ||
                                desc.contains(keyword, ignoreCase = true) ||
                                nextTitle.contains(keyword, ignoreCase = true) ||
                                nextDesc.contains(keyword, ignoreCase = true)
                    }

                    // スポーツジャンルの判定（EpgProgramと違い、ChannelのProgramにはGenre情報が文字列やIDで来るケースがあるため、
                    // 今回はタイトルと説明文による精度の高いキーワードマッチング（isMatch）を最終判定の軸とします）
                    isMatch
                }
            }.filterValues { it.isNotEmpty() }

            if (filtered.isEmpty()) grouped else filtered
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading

    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()

    private var pollingJob: Job? = null
    private var progressUpdateJob: Job? = null

    private var fetchJob: Job? = null

    init {
        startPolling()
        startProgressUpdater()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun transformToUiState(grouped: Map<String, List<Channel>>): List<LiveRowState> =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val orderedTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")

            val sortedKeys = grouped.keys.sortedBy { key ->
                val index = orderedTypes.indexOf(key)
                if (index >= 0) index else Int.MAX_VALUE
            }

            sortedKeys.mapNotNull { type ->
                val channels = grouped[type] ?: return@mapNotNull null
                LiveRowState(
                    genreId = type,
                    genreLabel = when (type) {
                        "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; "BS4K" -> "BS4K"; "SKY" -> "スカパー"; else -> type
                    },
                    channels = channels.map { ch ->
                        val start = ch.programPresent?.startTime?.let {
                            runCatching {
                                OffsetDateTime.parse(it).toInstant().toEpochMilli()
                            }.getOrNull()
                        } ?: 0L
                        val dur = ch.programPresent?.duration ?: 0
                        val progress = if (start > 0 && dur > 0) {
                            ((now - start).toFloat() / (dur * 1000).toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        UiChannelState(
                            channel = ch,
                            displayChannelId = ch.displayChannelId,
                            name = ch.name,
                            programTitle = ch.programPresent?.title ?: "放送休止中",
                            progress = progress,
                            hasProgram = ch.programPresent != null,
                            jikkyoForce = ch.jikkyoForce
                        )
                    }
                )
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun fetchChannelsInternal() {
        try {
            _connectionError.value = false
            val response = repository.getChannels()

            val processed = withContext(Dispatchers.Default) {
                val rawChannels = listOfNotNull(
                    response.terrestrial, response.bs, response.cs, response.sky, response.bs4k
                ).flatten()

                val allChannels = rawChannels.map { apiChannel ->
                    Channel(
                        id = apiChannel.id,
                        name = apiChannel.name,
                        type = apiChannel.type,
                        channelNumber = apiChannel.channelNumber,
                        networkId = apiChannel.networkId,
                        serviceId = apiChannel.serviceId,
                        displayChannelId = apiChannel.displayChannelId ?: apiChannel.id,
                        isWatchable = apiChannel.isWatchable,
                        isDisplay = apiChannel.isDisplay,
                        programPresent = apiChannel.programPresent,
                        programFollowing = apiChannel.programFollowing,
                        remocon_Id = apiChannel.remocon_Id,
                        jikkyoForce = apiChannel.jikkyoForce
                    )
                }

                val hotCount = allChannels.count { (it.jikkyoForce ?: 0) > 0 }
                Log.i(
                    "ChannelViewModel",
                    "Fetched channels. Total: ${allChannels.size}, Hot(force > 0): $hotCount"
                )

                allChannels.filter { it.isDisplay }.groupBy { it.type }
            }
            _groupedChannels.value = processed
            _liveRows.value = transformToUiState(processed)
        } catch (e: CancellationException) {
            Log.d("ChannelViewModel", "fetchChannelsInternal cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("ChannelViewModel", "Error fetching channels", e)
            _connectionError.value = true
        } finally {
            _isLoading.value = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                if (_groupedChannels.value.isNotEmpty()) {
                    _liveRows.value = transformToUiState(_groupedChannels.value)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun fetchChannels() {
        _isLoading.value = true
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            fetchChannelsInternal()
        }
    }

    fun fetchRecentRecordings() {
        _isRecordingLoading.value = true
        viewModelScope.launch {
            try {
                val response = repository.getRecordedPrograms(page = 1)
                _recentRecordings.value = response.recordedPrograms
            } catch (e: Exception) {
                Log.e("ChannelViewModel", "Error recordings", e)
            } finally {
                _isRecordingLoading.value = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchChannelsInternal()
                delay(60_000L) // 1分待機
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        progressUpdateJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        fetchJob?.cancel()
    }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            val entity = KonomiDataMapper.toEntity(program)
            repository.saveToLocalHistory(entity)
        }
    }
}