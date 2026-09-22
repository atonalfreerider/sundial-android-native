package com.primesoftwaresystems.sundial.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.primesoftwaresystems.sundial.ui.SundialView
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DailyWallpaperWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = try {
        WallpaperRenderer.apply(applicationContext)
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    }
}

object DailyWallpaperScheduler {
    private const val PREFERENCES = "daily_heliocentric_wallpaper"
    private const val ENABLED = "enabled"
    private const val PERIODIC_WORK = "daily_heliocentric_wallpaper_periodic"
    private const val IMMEDIATE_WORK = "daily_heliocentric_wallpaper_now"

    fun configureForRequest(context: Context) {
        val preferences = preferences(context)
        if (!preferences.contains(ENABLED)) {
            preferences.edit().putBoolean(ENABLED, true).apply()
            schedule(context)
            applyNow(context)
        } else if (preferences.getBoolean(ENABLED, false)) {
            schedule(context)
        }
    }

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) {
            schedule(context)
            applyNow(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        }
    }

    fun applyNow(context: Context) {
        val request = OneTimeWorkRequest.Builder(DailyWallpaperWorker::class.java).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        val nextUpdate = now.toLocalDate().plusDays(1).atTime(0, 5).atZone(now.zone)
        val initialDelay = Duration.between(now, nextUpdate).toMillis().coerceAtLeast(0L)
        val request = PeriodicWorkRequest.Builder(DailyWallpaperWorker::class.java, 24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

internal object WallpaperRenderer {
    fun apply(context: Context) {
        val dimensions = displayDimensions(context)
        val bitmap = renderOnMainThread(context, dimensions.first, dimensions.second)
        try {
            WallpaperManager.getInstance(context).setBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                false,
                WallpaperManager.FLAG_SYSTEM,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayDimensions(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.run { widthPixels to heightPixels }
        }
    }

    private fun renderOnMainThread(context: Context, width: Int, height: Int): Bitmap {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return SundialView(context).renderWallpaperBitmap(width, height)
        }
        val bitmap = AtomicReference<Bitmap>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                bitmap.set(SundialView(context).renderWallpaperBitmap(width, height))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(20, TimeUnit.SECONDS)) { "Wallpaper rendering timed out" }
        failure.get()?.let { throw it }
        return checkNotNull(bitmap.get())
    }
}
