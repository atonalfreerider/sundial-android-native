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
import androidx.work.workDataOf
import com.primesoftwaresystems.sundial.ui.SundialView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DailyWallpaperWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = try {
        when (inputData.getString(INPUT_TARGET)) {
            TARGET_HOME -> WallpaperRenderer.applyHome(applicationContext)
            TARGET_LOCK -> if (DailyWallpaperScheduler.usesLockScreen(applicationContext)) {
                WallpaperRenderer.applyLock(applicationContext)
            }
            else -> WallpaperRenderer.apply(applicationContext)
        }
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    }

    companion object {
        const val INPUT_TARGET = "wallpaper_target"
        const val TARGET_HOME = "home"
        const val TARGET_LOCK = "lock"
    }
}

object DailyWallpaperScheduler {
    private const val PREFERENCES = "daily_heliocentric_wallpaper"
    private const val ENABLED = "enabled"
    private const val USE_LOCK_SCREEN = "use_lock_screen"
    private const val LEGACY_PERIODIC_WORK = "daily_heliocentric_wallpaper_periodic"
    private const val HOME_PERIODIC_WORK = "daily_heliocentric_wallpaper_home"
    private const val LOCK_PERIODIC_WORK = "daily_heliocentric_wallpaper_lock"
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

    fun usesLockScreen(context: Context): Boolean =
        preferences(context).getBoolean(USE_LOCK_SCREEN, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) {
            schedule(context)
            applyNow(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(HOME_PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(LOCK_PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        }
    }

    fun setUseLockScreen(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(USE_LOCK_SCREEN, enabled).apply()
        if (isEnabled(context)) {
            schedule(context)
            applyNow(context)
        }
    }

    fun applyNow(context: Context) {
        val request = OneTimeWorkRequest.Builder(DailyWallpaperWorker::class.java).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        val homeRequest = PeriodicWorkRequest.Builder(DailyWallpaperWorker::class.java, 24, TimeUnit.HOURS)
            .setInputData(workDataOf(DailyWallpaperWorker.INPUT_TARGET to DailyWallpaperWorker.TARGET_HOME))
            .build()
        manager.enqueueUniquePeriodicWork(
            HOME_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            homeRequest,
        )
        if (usesLockScreen(context)) {
            // WorkManager's minimum periodic interval is 15 minutes.
            val lockRequest = PeriodicWorkRequest.Builder(DailyWallpaperWorker::class.java, 15, TimeUnit.MINUTES)
                .setInputData(workDataOf(DailyWallpaperWorker.INPUT_TARGET to DailyWallpaperWorker.TARGET_LOCK))
                .build()
            manager.enqueueUniquePeriodicWork(
                LOCK_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                lockRequest,
            )
        } else {
            manager.cancelUniqueWork(LOCK_PERIODIC_WORK)
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

internal object WallpaperRenderer {
    fun apply(context: Context) {
        applyHome(context)
        if (DailyWallpaperScheduler.usesLockScreen(context)) applyLock(context)
    }

    fun applyHome(context: Context) {
        val dimensions = displayDimensions(context)
        val manager = WallpaperManager.getInstance(context)
        setFrame(context, manager, dimensions, SundialView.ViewState.HELIOCENTRIC, WallpaperManager.FLAG_SYSTEM)
    }

    fun applyLock(context: Context) {
        val dimensions = displayDimensions(context)
        val manager = WallpaperManager.getInstance(context)
        setFrame(context, manager, dimensions, SundialView.ViewState.GEOCENTRIC, WallpaperManager.FLAG_LOCK)
    }

    private fun setFrame(
        context: Context,
        manager: WallpaperManager,
        dimensions: Pair<Int, Int>,
        state: SundialView.ViewState,
        target: Int,
    ) {
        val bitmap = renderOnMainThread(context, dimensions.first, dimensions.second, state)
        try {
            manager.setBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), false, target)
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

    private fun renderOnMainThread(
        context: Context,
        width: Int,
        height: Int,
        state: SundialView.ViewState,
    ): Bitmap {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return SundialView(context).renderWallpaperBitmap(
                width,
                height,
                wallpaperState = state,
            )
        }
        val bitmap = AtomicReference<Bitmap>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                bitmap.set(SundialView(context).renderWallpaperBitmap(
                    width,
                    height,
                    wallpaperState = state,
                ))
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
