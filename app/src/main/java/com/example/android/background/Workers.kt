package com.example.android.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.PhoneBridgeApplication

class OutboxFlushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PhoneBridgeApplication).container
        return runCatching {
            container.outboxEngine.flushPendingBatch()
            val remaining = container.database.outboxDao().listPendingOrWaiting()
            if (remaining.isNotEmpty() && runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.success()
            }
        }.getOrElse {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

class InboxSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PhoneBridgeApplication).container
        return runCatching {
            container.inboxEngine.syncFromRelay()
            container.inboxEngine.flushPendingAcks()
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}

class RetentionCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PhoneBridgeApplication).container
        container.outboxEngine.runRetentionCleanup()
        container.inboxEngine.runRetentionCleanup()
        return Result.success()
    }
}
