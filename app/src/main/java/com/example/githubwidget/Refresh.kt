package com.example.githubwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refresh policy + the one background thread every widget refresh and
 * repaint runs on. One fetch serves every widget (the username is global),
 * automatic refreshes skip the network while the cache is fresh, and
 * unchanged data costs a 304 thanks to the stored ETag.
 */
object Refresher {

    /** What a fetch attempt ended with. */
    enum class Outcome { UPDATED, UNCHANGED, SKIPPED, OFFLINE, NOT_FOUND, FAILED }

    /** Short timeouts inside a broadcast's goAsync window; jobs may wait longer. */
    const val TIMEOUT_INTERACTIVE_MS = 6_000
    const val TIMEOUT_BACKGROUND_MS = 15_000

    /** Broadcasts must finish well within the system's ~10 s receiver limit. */
    private const val RECEIVER_BUDGET_MS = 8_500L

    /** A fetch this recent counts as "just refreshed", even for manual taps. */
    private const val DEDUPE_MS = 15_000L

    private const val MIN_FRESH_MS = 15 * 60_000L

    /** Single low-priority worker: renders and fetches never pile up in parallel. */
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "gh-widget-refresh")
    }

    /**
     * Runs [work] on [executor] for a goAsync() receiver and always calls
     * finish() — when the work ends, or after the receiver budget at the latest.
     */
    fun runAsync(pending: BroadcastReceiver.PendingResult, work: () -> Unit) {
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        val handler = Handler(Looper.getMainLooper())
        val watchdog = Runnable { finish() }
        handler.postDelayed(watchdog, RECEIVER_BUDGET_MS)
        try {
            executor.execute {
                try {
                    work()
                } catch (_: Exception) {
                    // Widgets keep their last good paint.
                } finally {
                    handler.removeCallbacks(watchdog)
                    finish()
                }
            }
        } catch (_: Exception) {
            handler.removeCallbacks(watchdog)
            finish()
        }
    }

    /** True when there is a usable network (an unmetered one when [unmeteredOnly]). */
    fun isOnline(context: Context, unmeteredOnly: Boolean = false): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!unmeteredOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        } catch (_: Exception) {
            true // If we can't tell, try the fetch — failure paths keep the cache.
        }
    }

    /** Data Saver or battery saver is on: automatic refreshes stand down. */
    fun isBackgroundRestricted(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ||
                pm.isPowerSaveMode
        } catch (_: Exception) {
            false
        }
    }

    /** Cached data younger than this skips automatic fetches. */
    fun freshWindowMs(hours: Int): Long {
        val interval = hours * 3_600_000L
        // Periodic jobs may run up to a flex window early; stay under that.
        return (interval - RefreshScheduler.flexMs(interval) - 10 * 60_000L).coerceAtLeast(MIN_FRESH_MS)
    }

    fun hasCacheFor(context: Context, username: String): Boolean =
        Cache.load(context)?.first?.username.equals(username, ignoreCase = true)

    /**
     * Should an automatic (system / scheduled) refresh hit the network?
     * Never without a username or when the cache is still fresh; with
     * refresh set to off, only to fill an empty cache.
     */
    fun shouldAutoFetch(context: Context): Boolean {
        val username = Prefs.getUsername(context)
        if (username.isBlank()) return false
        if (isBackgroundRestricted(context)) return false
        val hours = Prefs.getRefreshHours(context)
        val hasCache = hasCacheFor(context, username)
        if (hasCache) {
            if (hours == 0) return false
            val age = System.currentTimeMillis() - Cache.fetchedAt(context)
            if (age in 0 until freshWindowMs(hours)) return false
        }
        return isOnline(context, unmeteredOnly = Prefs.isWifiOnly(context))
    }

    /**
     * One conditional fetch shared by every widget and the app. Serialized,
     * and deduplicated: a second request seconds after the first reuses it.
     */
    @Synchronized
    fun fetch(context: Context, username: String, timeoutMs: Int): Outcome {
        val app = context.applicationContext
        if (username.isBlank()) return Outcome.SKIPPED
        GithubApi.init(app)
        if (hasCacheFor(app, username) &&
            System.currentTimeMillis() - Cache.fetchedAt(app) in 0 until DEDUPE_MS
        ) {
            return Outcome.UNCHANGED
        }
        if (!isOnline(app)) return Outcome.OFFLINE
        return try {
            val response = GithubApi.fetchConditional(username, Cache.etagFor(app, username), timeoutMs)
            val result = response.result
            if (result == null) {
                Cache.touch(app, response.etag)
                Outcome.UNCHANGED
            } else {
                Cache.save(app, result, response.etag)
                Outcome.UPDATED
            }
        } catch (_: UserNotFoundException) {
            Outcome.NOT_FOUND
        } catch (_: Exception) {
            Outcome.FAILED
        }
    }

    /** App-screen fetch: same shared path, but returns data or throws like [GithubApi.fetch]. */
    fun fetchForApp(context: Context, username: String): ContributionsResult {
        return when (fetch(context, username, TIMEOUT_BACKGROUND_MS)) {
            Outcome.UPDATED, Outcome.UNCHANGED ->
                Cache.load(context)?.first ?: throw RuntimeException("Cache unavailable")
            Outcome.NOT_FOUND -> throw UserNotFoundException(username.trim().trimStart('@'))
            else -> throw RuntimeException("Couldn't refresh.")
        }
    }
}

/**
 * Periodic background refresh via JobScheduler (no WorkManager): runs only
 * with a network (unmetered when "Wi-Fi only") and battery not low, and
 * survives reboots. Scheduling is idempotent so the period timer isn't reset.
 */
object RefreshScheduler {

    private const val JOB_ID = 0x6757 // "gW"

    /** Lets the system batch the job with other work. */
    fun flexMs(intervalMs: Long): Long =
        (intervalMs / 4).coerceAtLeast(JobInfo.getMinFlexMillis())

    /** Schedules, keeps or cancels the job to match widgets, username and settings. */
    fun sync(context: Context) {
        val app = context.applicationContext
        try {
            val js = app.getSystemService(JobScheduler::class.java) ?: return
            val hours = Prefs.getRefreshHours(app)
            val hasWidgets = AppWidgetManager.getInstance(app)
                .getAppWidgetIds(ComponentName(app, ContributionWidgetProvider::class.java))
                .isNotEmpty()
            if (hours == 0 || !hasWidgets || Prefs.getUsername(app).isBlank()) {
                if (js.getPendingJob(JOB_ID) != null) js.cancel(JOB_ID)
                return
            }
            val interval = hours * 3_600_000L
            val info = JobInfo.Builder(JOB_ID, ComponentName(app, RefreshJobService::class.java))
                .setPeriodic(interval, flexMs(interval))
                .setRequiredNetworkType(
                    if (Prefs.isWifiOnly(app)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
                )
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            val existing = js.getPendingJob(JOB_ID)
            if (existing != null && same(existing, info)) return
            js.schedule(info)
        } catch (_: Exception) {
            // Scheduling is best-effort; manual refresh still works.
        }
    }

    fun cancel(context: Context) {
        try {
            context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        } catch (_: Exception) {
            // Nothing scheduled.
        }
    }

    @Suppress("DEPRECATION") // networkType is fine for comparing our own jobs.
    private fun same(a: JobInfo, b: JobInfo): Boolean =
        a.service == b.service &&
            a.intervalMillis == b.intervalMillis &&
            a.flexMillis == b.flexMillis &&
            a.networkType == b.networkType &&
            a.isRequireBatteryNotLow == b.isRequireBatteryNotLow &&
            a.isPersisted == b.isPersisted
}

/** Scheduled refresh: fetch if stale (and allowed), then repaint every widget. */
class RefreshJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        return try {
            Refresher.executor.execute {
                try {
                    val mgr = AppWidgetManager.getInstance(app)
                    val ids = mgr.getAppWidgetIds(ComponentName(app, ContributionWidgetProvider::class.java))
                    ContributionWidgetProvider().runBatch(
                        app, ids, ContributionWidgetProvider.Fetch.AUTO, Refresher.TIMEOUT_BACKGROUND_MS
                    )
                } catch (_: Exception) {
                    // Next period tries again.
                } finally {
                    jobFinished(params, false)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
