package com.beeregg2001.komorebi.data.repository

import android.os.Build
import android.util.Base64
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelResponse
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.Normalizer // ★追加: 表記揺れ吸収のための正規化ライブラリ
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

data class EpgSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel
)

interface KonomiTvApiService {
    @GET("api/programs/timetable")
    suspend fun getEpgPrograms(
        @Query("start_time") startTime: String? = null,
        @Query("end_time") endTime: String? = null,
        @Query("channel_type") channelType: String? = null,
        @Query("pinned_channel_ids") pinnedChannelIds: String? = null
    ): EpgChannelResponse
}

class EpgRepository @Inject constructor(
    private val apiService: KonomiTvApiService,
    private val epgCacheDao: EpgCacheDao,
    private val gson: Gson
) {
    private val memoryCache = ConcurrentHashMap<String, List<EpgChannelWrapper>>()

    private fun compress(data: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun decompress(compressed: String): String {
        if (compressed.startsWith("[{") || compressed.startsWith("{")) return compressed
        return try {
            val bytes = Base64.decode(compressed, Base64.NO_WRAP)
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            compressed
        }
    }

    // ==========================================
    // ★追加: 曖昧検索（表記揺れ吸収）のための正規化ツール
    // ==========================================
    private fun normalizeForSearch(text: String): String {
        // NFKC正規化により、全角英数字→半角、半角カナ→全角、全角スペース→半角スペース等に統一
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        // 大文字・小文字を区別せずに検索できるよう、すべて小文字に変換
        return normalized.lowercase()
    }
    // ==========================================

    // ==========================================
    // ★ 修正: 将来の「詳細検索UI」にも完全対応する最強の検索エンジン
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.O)
    fun searchFuturePrograms(
        query: String = "",
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<EpgSearchResultItem> {
        val normalizedQuery = normalizeForSearch(query)
        val keywords = normalizedQuery.split(Regex("[\\s]+")).filter { it.isNotBlank() }

        // 全ての条件が空の場合は、処理をスキップして空リストを返す（負荷対策）
        if (keywords.isEmpty() && genre.isNullOrBlank() && dateStr.isNullOrBlank() && channelName.isNullOrBlank() && !isLiveOnly) {
            return emptyList()
        }

        val results = mutableListOf<EpgSearchResultItem>()
        val nowMs = System.currentTimeMillis()

        // 日付のパース
        val targetTvDate = try {
            dateStr?.takeIf { it.isNotBlank() }?.let {
                java.time.LocalDate.parse(it.replace("/", "-"))
            }
        } catch (e: Exception) {
            null
        }

        memoryCache.values.flatten().forEach { wrapper ->
            // 1. チャンネルフィルター (部分一致)
            if (!channelName.isNullOrBlank() && !wrapper.channel.name.contains(
                    channelName,
                    ignoreCase = true
                )
            ) {
                return@forEach // チャンネル名が一致しなければスキップ
            }

            wrapper.programs.forEach { prog ->
                try {
                    val startTimeMs =
                        OffsetDateTime.parse(prog.start_time).toInstant().toEpochMilli()
                    if (startTimeMs > nowMs) {

                        // 2. 日付フィルター (テレビ的1日: 朝4時区切り)
                        if (targetTvDate != null) {
                            val startDt = OffsetDateTime.parse(prog.start_time)
                            val base = startDt.withHour(4).withMinute(0).withSecond(0).withNano(0)
                            val tvDate = if (startDt.hour < 4) base.minusDays(1)
                                .toLocalDate() else base.toLocalDate()
                            if (tvDate != targetTvDate) return@forEach
                        }

                        // 3. ジャンルフィルター
                        if (!genre.isNullOrBlank()) {
                            val matchGenre = (prog.genres?.any {
                                it.major.contains(genre) || it.middle.contains(genre)
                            } == true) || prog.title.contains(genre)
                            if (!matchGenre) return@forEach
                        }

                        // 4. 生放送フィルター
                        if (isLiveOnly) {
                            val detailText =
                                prog.detail?.entries?.joinToString(" ") { "${it.key} ${it.value}" }
                                    ?: ""
                            val title = prog.title ?: ""
                            val desc = prog.description ?: ""
                            val matchLive =
                                title.contains("[生]") || title.contains("【生】") || title.contains("生中継") || title.contains(
                                    "生放送"
                                ) || title.contains("LIVE", ignoreCase = true) ||
                                        desc.contains("生中継") || desc.contains("生放送") || detailText.contains(
                                    "生中継"
                                ) || detailText.contains("生放送")
                            if (!matchLive) return@forEach
                        }

                        // 5. キーワードフィルター (AND検索)
                        if (keywords.isNotEmpty()) {
                            val detailText =
                                prog.detail?.entries?.joinToString(" ") { "${it.key} ${it.value}" }
                                    ?: ""
                            // ★ チャンネル名も検索対象に含めることで柔軟性アップ
                            val combinedDesc =
                                "${wrapper.channel.name} ${prog.title} ${prog.description} $detailText"
                            val normalizedDesc = normalizeForSearch(combinedDesc)
                            val isMatch = keywords.all { k -> normalizedDesc.contains(k) }
                            if (!isMatch) return@forEach
                        }

                        results.add(EpgSearchResultItem(prog, wrapper.channel))
                    }
                } catch (e: Exception) { /* ignore */
                }
            }
        }

        // 時間順にソートして返す
        return results.sortedBy {
            try {
                OffsetDateTime.parse(it.program.start_time).toInstant().toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }
    }

    fun hasCacheForType(channelType: String): Boolean {
        return memoryCache.containsKey(channelType)
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchAndCacheEpgDataSilently(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ) {
        if (hasCacheForType(channelType)) return

        var isFreshCacheAvailable = false

        try {
            val cache = epgCacheDao.getCache(channelType)
            if (cache != null) {
                val json = decompress(cache.dataJson)
                val listType = object : TypeToken<List<EpgChannelWrapper>>() {}.type
                val data: List<EpgChannelWrapper> = gson.fromJson(json, listType)
                memoryCache[channelType] = data

                val oneDayLater = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
                val isFresh = data.flatMap { it.programs }.any {
                    try {
                        OffsetDateTime.parse(it.start_time).toInstant().toEpochMilli() > oneDayLater
                    } catch (e: Exception) {
                        false
                    }
                }

                if (isFresh) {
                    isFreshCacheAvailable = true
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Cache Read Error for $channelType (Healing with new fetch)", e)
        }

        if (isFreshCacheAvailable) return

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val response = apiService.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            memoryCache[channelType] = response.channels

            val rawJson = gson.toJson(response.channels)
            val compressedJson = compress(rawJson)

            epgCacheDao.insertOrUpdate(
                EpgCacheEntity(
                    channelType,
                    compressedJson,
                    System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Silent Fetch Error for $channelType", e)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun getEpgDataStream(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ): Flow<Result<List<EpgChannelWrapper>>> = flow {

        memoryCache[channelType]?.let { emit(Result.success(it)) }

        if (memoryCache[channelType] == null) {
            try {
                val cache = epgCacheDao.getCache(channelType)
                if (cache != null) {
                    val json = decompress(cache.dataJson)
                    val listType = object : TypeToken<List<EpgChannelWrapper>>() {}.type
                    val data: List<EpgChannelWrapper> = gson.fromJson(json, listType)
                    memoryCache[channelType] = data
                    emit(Result.success(data))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("EPG", "Cache Read Error", e)
            }
        }

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val response = apiService.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )

            memoryCache[channelType] = response.channels
            emit(Result.success(response.channels))

            CoroutineScope(Dispatchers.IO).launch {
                val rawJson = gson.toJson(response.channels)
                val compressedJson = compress(rawJson)
                epgCacheDao.insertOrUpdate(
                    EpgCacheEntity(
                        channelType,
                        compressedJson,
                        System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            if (memoryCache[channelType] == null) emit(Result.failure(e))
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchEpgData(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String? = null
    ): Result<List<EpgChannelWrapper>> {
        return try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val response = apiService.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            Result.success(response.channels)
        } catch (e: Exception) {
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            Result.failure(e)
        }
    }

    suspend fun fetchPinnedChannels(pinnedIds: List<String>): Result<List<EpgChannelWrapper>> {
        return try {
            val response = apiService.getEpgPrograms(pinnedChannelIds = pinnedIds.joinToString(","))
            Result.success(response.channels)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}