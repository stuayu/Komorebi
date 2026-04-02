package com.beeregg2001.komorebi.data.worker

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class RecordSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncEngine: RecordSyncEngine
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("RecordSyncWorker", "Periodic sync triggered by WorkManager")
        return try {
            if (!syncEngine.isInitialBuildCompleted()) {
                Log.i(
                    "RecordSyncWorker",
                    "Initial build is not completed yet. Skipping background sync to save device resources."
                )
                return Result.success()
            }

            // ====================================================================
            // ★ 魔法①: 「再生空気読み機能」 (メディア再生中は同期をスキップ)
            // ====================================================================
            // ExoPlayerなどで動画やライブ視聴をしている最中は、必ず AudioManager がアクティブになります。
            // 視聴中に重いDB処理を走らせて映像がカクつく（GCスパイク）のを防ぐため、
            // 音が鳴っている場合は潔く諦めて、15分後の次回実行まで処理をスキップします。
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isMusicActive) {
                Log.i(
                    "RecordSyncWorker",
                    "Media playback detected (Live/Video). Skipping background sync to prevent stuttering."
                )
                // Result.retry() にするとすぐ再実行されてしまうため、successで「終わったフリ」をして15分後に回す
                return Result.success()
            }

            // ====================================================================
            // ★ 魔法②: 全件同期ではなく「スマート同期」を利用する
            // ====================================================================
            // 15分に1回の定期チェックでは、何ページも読み込む syncAllRecords ではなく、
            // 1ページ目の差分だけを確認する超軽量な smartSync() を呼び出すのがベストプラクティスです。
            syncEngine.smartSync()

            Result.success()
        } catch (e: Exception) {
            Log.e("RecordSyncWorker", "Error during periodic sync", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "recorded_program_sync_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RecordSyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}