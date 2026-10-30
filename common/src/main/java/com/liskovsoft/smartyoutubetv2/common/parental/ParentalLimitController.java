package com.liskovsoft.smartyoutubetv2.common.parental;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import java.util.concurrent.TimeUnit;

/**
 * Enforces a parental time limit on the SmartTube app.
 *
 * <p>State machine:
 * <pre>
 *     ┌─────────┐  limit reached  ┌──────────┐  rest elapsed  ┌─────────┐
 *     │ READY   │ ──────────────▶ │ RESTING  │ ─────────────▶ │ READY   │
 *     └────▲────┘                 └──────────┘                 └─────────┘
 *          │         foreground / background transitions do
 *          │         not change READY/RESTING; only the ticker is gated.
 *          │
 *     ┌─────────┐  parent PIN  ┌────────────────┐
 *     │ ACTIVE  │ ───────────▶ │ PARENT_OVERRIDE│ (extends current session)
 *     └─────────┘              └────────────────┘
 * </pre>
 *
 * <p>Time accumulation:
 * <ul>
 *   <li>Foreground time is measured against {@link SystemClock#elapsedRealtime()},
 *       which is monotonic and unaffected by wall-clock changes.</li>
 *   <li>On every tick we persist {@code usedMs} so that an OS-initiated process
 *       kill doesn't lose the user's progress toward the limit.</li>
 *   <li>The rest deadline is stored in wall-clock millis because rest must end
 *       at a real wall-clock moment — the app cannot rely on elapsed realtime
 *       across a reboot.</li>
 * </ul>
 *
 * <p>Restart semantics:
 * <ul>
 *   <li>If a saved {@code sessionStartElapsed} is still recent, we trust the
 *       persisted {@code usedMs} and resume from there.</li>
 *   <li>If the rest deadline is in the future at process start, we keep the
 *       lock and resume the countdown.</li>
 * </ul>
 *
 * <p>This class is deliberately self-contained and registered globally via
 * {@link Application.ActivityLifecycleCallbacks} so it sees every Activity,
 * including {@link com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity},
 * {@code BrowseActivity}, dialogs, and PiP.
 */
public final class ParentalLimitController implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "ParentalLimit";

    /** Re-poll every 500 ms while foreground. */
    private static final long TICK_MS = 500;
    /**
     * How often the live stretch is committed into the accumulated total.
     * A process kill therefore loses at most this much progress.
     */
    private static final long COMMIT_INTERVAL_MS = 5_000;
    /**
     * Sanity bound for a single live stretch (24 h). A stretch longer than
     * this means the stored start time is stale or corrupt - for example after
     * the process was killed without the lifecycle callback running - so the
     * accumulated total is used instead.
     */
    private static final long MAX_SANE_STRETCH_MS = TimeUnit.HOURS.toMillis(24);
    /** Warn the user this long before the quota runs out. */
    private static final long WARNING_LEAD_MS = 60_000;

    public enum State { DISABLED, READY, ACTIVE, RESTING }

    public interface Listener {
        void onStateChanged(State newState);
    }

    @SuppressLint("StaticFieldLeak")
    private static ParentalLimitController sInstance;

    private final Application mApp;
    private final ParentalLimitData mData;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTicker = this::tick;

    private int mForegroundCount;
    private Activity mCurrentActivity;
    private boolean mLockLaunched;
    private boolean mWarningShown;
    private long mLastSavedUsedMs;
    /**
     * Class of the lock activity to launch when the quota is exhausted. Set
     * via {@link #setLockActivityClass(Class)} from the tv module so this
     * common module doesn't need a hard dependency on the tv module's
     * ParentalLimitActivity class.
     */
    private static volatile Class<?> sLockActivityClass;

    private ParentalLimitController(Application app) {
        mApp = app;
        mData = ParentalLimitData.instance(app);
        app.registerActivityLifecycleCallbacks(this);

        // Clean up stale "lock launched" guards whenever we (re)construct the controller.
        mLockLaunched = false;
    }

    public static synchronized ParentalLimitController init(Application app) {
        if (sInstance == null) {
            sInstance = new ParentalLimitController(app);
        }
        return sInstance;
    }

    public static synchronized @Nullable ParentalLimitController get() {
        return sInstance;
    }

    /**
     * Register the lock screen Activity class. Called from
     * {@code MainApplication.onCreate()} so the common module doesn't need a
     * compile-time dependency on the tv module.
     */
    public static synchronized void setLockActivityClass(Class<?> clazz) {
        sLockActivityClass = clazz;
    }

    // ─── State queries ─────────────────────────────────────────────────────

    public State getState() {
        if (!mData.isEnabled()) {
            return State.DISABLED;
        }
        if (isResting()) {
            return State.RESTING;
        }
        if (mForegroundCount > 0) {
            return State.ACTIVE;
        }
        return State.READY;
    }

    public boolean isEnabled() {
        return mData.isEnabled();
    }

    public boolean isResting() {
        return System.currentTimeMillis() < mData.getRestUntilWall();
    }

    public long getRestRemainingMs() {
        long until = mData.getRestUntilWall();
        long now = System.currentTimeMillis();
        return Math.max(0, until - now);
    }

    public long getUsedMs() {
        return computeCurrentUsedMs();
    }

    public long getLimitMs() {
        return mData.getLimitMs();
    }

    public long getRestMs() {
        return mData.getRestMs();
    }

    public long getRemainingMs() {
        return Math.max(0, getLimitMs() - getUsedMs());
    }

    public boolean isPinConfigured() {
        return mData.isPinConfigured();
    }

    // ─── Configuration (PIN-gated by caller) ────────────────────────────────

    public void setEnabled(boolean enabled) {
        mData.setEnabled(enabled);
        if (!enabled) {
            // Don't keep stale state lying around once feature is off.
            mData.resetTimerState();
        }
    }

    public void setLimitMs(long ms) {
        mData.setLimitMs(ms);
    }

    public void setRestMs(long ms) {
        mData.setRestMs(ms);
    }

    public void setPin(String pin) {
        mData.setPin(pin);
    }

    public boolean checkPin(String pin) {
        return mData.checkPin(pin);
    }

    /**
     * Reset everything (cumulative timer, rest deadline, configuration, PIN).
     * Caller must verify PIN first.
     */
    public void resetAll() {
        mData.resetAll();
    }

    /**
     * Reset only the timer state (cumulative time + rest deadline) without
     * touching the configured limits or PIN. Caller must verify PIN first.
     */
    public void resetTimer() {
        mData.resetTimerState();
        mLastSavedUsedMs = 0;
        // resetTimerState() wipes the session start. If the app is still in the
        // foreground we have to restore it, otherwise the timer would stop
        // accumulating and the kid would get unlimited time.
        restartForegroundSession();
    }

    /**
     * Skip the current rest period. Caller must verify PIN first.
     */
    public void skipRest() {
        mData.setRestUntilWall(0);
        // Also clear the unused counter so the kid gets the full configured window.
        mData.setUsedMs(0);
        mData.setSessionStartElapsed(0);
        mData.setPlayingStartElapsed(0);
        mLastSavedUsedMs = 0;
        // Same as above: keep counting while the app stays in the foreground.
        restartForegroundSession();
    }

    /**
     * Restarts the foreground session clock so the timer keeps running when
     * the app is already in the foreground.
     */
    private void restartForegroundSession() {
        if (mForegroundCount <= 0) {
            return;
        }
        mData.setSessionStartElapsed(SystemClock.elapsedRealtime());
        mLastSavedUsedMs = mData.getUsedMs();
        // Make sure the ticker is running so the reset takes effect right away.
        mHandler.removeCallbacks(mTicker);
        mHandler.post(mTicker);
    }

    /**
     * Grant the user an additional {@code extraMs} of foreground time without
     * resetting the counter. Caller must verify PIN first. Used for the
     * "Extend current session" parent action.
     */
    public void extendCurrentSession(long extraMs) {
        // The accumulated total is the committed time; the live stretch is
        // added on top by computeCurrentUsedMs(). To give the user `extraMs`
        // more time we lower the committed total and restart the stretch from
        // now, so the new total is (total - extraMs) + elapsed.
        long newUsed = Math.max(0, computeCurrentUsedMs() - extraMs);
        mData.setUsedMs(newUsed);
        mData.setSessionStartElapsed(SystemClock.elapsedRealtime());
        mData.setPlayingStartElapsed(SystemClock.elapsedRealtime());
        mLastSavedUsedMs = newUsed;
    }

    // ─── Internal lifecycle ─────────────────────────────────────────────────

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        // No-op
    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (isLockActivity(activity)) {
            // The lock screen itself is never "user foreground" for timer purposes.
            return;
        }

        mCurrentActivity = activity;

        if (mForegroundCount == 0) {
            // Coming back to foreground from background.
            mData.setSessionStartElapsed(SystemClock.elapsedRealtime());
            mLastSavedUsedMs = mData.getUsedMs();
        }
        mForegroundCount++;
        mWarningShown = false;

        if (!mData.isEnabled()) {
            return;
        }

        // The rest period may have ended while the app was closed.
        refreshRestState();

        // If we're already resting (e.g. user relaunched the app mid-rest), lock immediately.
        if (isResting()) {
            launchLockIfNeeded(activity);
            return;
        }

        // Start ticking so we react to the limit being reached.
        mHandler.removeCallbacks(mTicker);
        mHandler.post(mTicker);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // No-op
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // No-op
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (isLockActivity(activity)) {
            return;
        }

        // IMPORTANT: snapshot usedMs BEFORE decrementing the counter, so the
        // timer's arithmetic is not disturbed by the boundary tick.
        long snapshot = computeCurrentUsedMs();
        mForegroundCount = Math.max(0, mForegroundCount - 1);

        if (mForegroundCount == 0) {
            // Close the live stretch into the accumulated total. The snapshot
            // above is only a fallback for the (impossible in practice) case
            // where the commit below did not run.
            commitAllLiveTime();

            long total = computeCurrentUsedMs();
            if (total < snapshot) {
                // Defensive: never lose time.
                mData.setUsedMs(snapshot);
                mLastSavedUsedMs = snapshot;
            }

            mHandler.removeCallbacks(mTicker);
            mCurrentActivity = null;
            // Don't clear mLockLaunched here — once we've shown the lock, the kid
            // shouldn't be able to dismiss it by navigating away. The lock
            // activity itself resets it on finish.
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        // No-op
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        // No-op
    }

    // ─── Lock screen launching ─────────────────────────────────────────────

    /** Called by the lock activity once the kid dismisses it normally. */
    public void onLockDismissed() {
        mLockLaunched = false;
    }

    private static boolean isLockActivity(Activity activity) {
        Class<?> lockClass = sLockActivityClass;
        if (lockClass == null || activity == null) {
            return false;
        }
        return lockClass.isInstance(activity);
    }

    private void launchLockIfNeeded(Activity fromActivity) {
        if (mLockLaunched) {
            return;
        }
        Class<?> lockClass = sLockActivityClass;
        if (lockClass == null) {
            // Defensive: the lock activity wasn't registered. Don't trap the user.
            return;
        }
        mLockLaunched = true;
        Intent intent = new Intent(mApp, lockClass);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            // Stop the video before showing the lock, otherwise the audio keeps
            // playing behind the lock screen.
            pausePlayback();
            fromActivity.startActivity(intent);
            showLockedToast();
        } catch (Exception e) {
            // ActivityNotFoundException etc. — reset so a retry is possible.
            mLockLaunched = false;
        }
    }

    /**
     * Tell the user straight away how long the rest lasts. The lock screen shows
     * a live countdown too, but a toast makes the reason obvious even when the
     * lock Activity ends up behind something else.
     */
    private void showLockedToast() {
        long restMs = mData.getRestMs();
        String duration = formatRestDuration(restMs);
        try {
            MessageHelpers.showLongMessage(
                    mApp,
                    mApp.getString(R.string.parental_limit_locked_toast, duration));
        } catch (Exception e) {
            // Never let a toast break the lock flow.
        }
    }

    /** "30 minutes" / "1 hour 30 minutes" / "45 seconds" — locale aware. */
    private static String formatRestDuration(long ms) {
        long totalMinutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(ms));
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0 && minutes > 0) {
            return String.format(java.util.Locale.getDefault(), "%d h %d min", hours, minutes);
        }
        if (hours > 0) {
            return String.format(java.util.Locale.getDefault(), "%d h", hours);
        }
        return String.format(java.util.Locale.getDefault(), "%d min", minutes);
    }

    // ─── Tick loop ─────────────────────────────────────────────────────────

    @MainThread
    private void tick() {
        if (!mData.isEnabled() || mForegroundCount == 0) {
            return;
        }

        // In playback-only mode, sync the playing stretch with the engine FIRST.
        // Doing it after computing `used` would bank the same stretch twice.
        syncPlaybackState();

        // Close the live stretch into the accumulated total. This must happen
        // BEFORE reading the total, otherwise the stretch would be counted
        // twice (the total would already include it).
        commitDueStretch();

        long used = computeCurrentUsedMs();

        if (isResting()) {
            // Already in rest; ensure lock is showing.
            if (mCurrentActivity != null) {
                launchLockIfNeeded(mCurrentActivity);
            }
            return;
        }

        // The rest may have ended (wall-clock) - give the user a fresh quota.
        refreshRestState();

        long limit = mData.getLimitMs();

        if (used >= limit) {
            beginRestAndLock();
            return;
        }

        // Warn the user shortly before the quota runs out so the lock isn't a
        // total surprise.
        if (!mWarningShown && used >= limit - WARNING_LEAD_MS) {
            mWarningShown = true;
            showWarningToast(limit - used);
        }

        mHandler.postDelayed(mTicker, TICK_MS);
    }

    /** "1 minute" style warning shown before the lock kicks in. */
    private void showWarningToast(long remainingMs) {
        try {
            MessageHelpers.showLongMessage(
                    mApp,
                    mApp.getString(R.string.parental_limit_warning_toast,
                            formatRestDuration(remainingMs)));
        } catch (Exception e) {
            // Never let a toast break the timer.
        }
    }

    /**
     * Stop whatever is playing when the lock engages. Without this the audio
     * would keep going behind the lock screen.
     */
    private void pausePlayback() {
        try {
            PlaybackPresenter presenter = PlaybackPresenter.instance(mApp);
            PlaybackView view = presenter.getPlayer();
            if (view != null && view.isPlaying()) {
                view.setPlayWhenReady(false);
            }
        } catch (Exception e) {
            // Playback not running - nothing to pause.
        }
    }

    private void beginRestAndLock() {
        // Close the live stretch first so the accumulated total is exact, then
        // pin it to the limit and start the rest deadline.
        commitAllLiveTime();
        mData.setUsedMs(mData.getLimitMs());
        mLastSavedUsedMs = mData.getLimitMs();
        mData.setRestUntilWall(System.currentTimeMillis() + mData.getRestMs());
        mData.setSessionStartElapsed(0);
        mData.setPlayingStartElapsed(0);

        if (mCurrentActivity != null) {
            launchLockIfNeeded(mCurrentActivity);
        }
    }

    /**
     * Ends the rest period once its wall-clock deadline has passed and grants
     * a fresh quota.
     *
     * <p>This is what makes the rest survive the TV being switched off: the
     * deadline is stored as an absolute wall-clock instant, so when the app
     * starts again (minutes or hours later) the elapsed rest time is simply
     * {@code now - restUntilWall}.</p>
     *
     * <p>It also has to be called explicitly, because the regular ticker stops
     * while the lock screen is the only foreground Activity - without this the
     * quota would stay exhausted and the user would be locked out forever.</p>
     */
    public void refreshRestState() {
        long restUntil = mData.getRestUntilWall();
        if (restUntil <= 0) {
            clearStaleQuotaIfNeeded();
            return;
        }
        if (System.currentTimeMillis() < restUntil) {
            return; // still resting
        }

        // The rest period is over - the user earned a fresh session.
        mData.setRestUntilWall(0);
        mData.setUsedMs(0);
        mData.setSessionStartElapsed(0);
        mData.setPlayingStartElapsed(0);
        mLastSavedUsedMs = 0;
        restartForegroundSession();
    }

    /**
     * Clears a stale exhausted quota.
     *
     * <p>SharedPreferences survive an overwrite install, so a build with a
     * time-accounting bug can leave an accumulated time that is already at (or
     * beyond) the limit. With correct accounting that state is impossible: the
     * quota is only ever exhausted together with an active rest period.</p>
     *
     * <p>So: quota exhausted AND no rest in effect means the stored state is
     * stale - drop it and let the user start fresh.</p>
     */
    private void clearStaleQuotaIfNeeded() {
        if (mData.getUsedMs() < mData.getLimitMs()) {
            return;
        }
        mData.setUsedMs(0);
        mData.setSessionStartElapsed(0);
        mData.setPlayingStartElapsed(0);
        mLastSavedUsedMs = 0;
        restartForegroundSession();
    }

    /**
     * Returns the current cumulative time: the accumulated total plus the
     * live stretch measured by the monotonic clock.
     *
     * <p>The accumulated total ({@code usedMs}) only ever holds
     * <em>completed</em> time - see
     * {@link ParentalLimitData#commitStretch(long, boolean)}. Adding the live
     * stretch on top of a total that already included it would double-count
     * and make the counted time grow quadratically.</p>
     *
     * <p>In {@link ParentalLimitData#COUNTING_MODE_FOREGROUND} the stretch is
     * the whole foreground session. In
     * {@link ParentalLimitData#COUNTING_MODE_PLAYBACK} it is only the current
     * uninterrupted playback stretch.</p>
     */
    @AnyThread
    long computeCurrentUsedMs() {
        if (mData.getCountingMode() == ParentalLimitData.COUNTING_MODE_PLAYBACK) {
            return computePlaybackUsedMs();
        }

        long start = mData.getSessionStartElapsed();
        if (start <= 0 || mForegroundCount == 0) {
            return mData.getUsedMs();
        }
        long delta = SystemClock.elapsedRealtime() - start;
        if (delta < 0 || delta > MAX_SANE_STRETCH_MS) {
            // Clock anomaly (e.g. the process was restarted and the elapsed
            // realtime base moved) or a corrupted stretch. Trust the
            // accumulated total rather than producing a bogus value.
            return mData.getUsedMs();
        }
        return mData.getUsedMs() + delta;
    }

    /** Accumulated total plus the currently running playback stretch. */
    private long computePlaybackUsedMs() {
        long base = mData.getUsedMs();
        long playingStart = mData.getPlayingStartElapsed();
        if (playingStart <= 0) {
            return base;
        }
        long delta = SystemClock.elapsedRealtime() - playingStart;
        if (delta < 0 || delta > MAX_SANE_STRETCH_MS) {
            return base;
        }
        return base + delta;
    }

    /**
     * Closes the live stretch into the accumulated total once it has been
     * running for {@link #COMMIT_INTERVAL_MS}. Called from the tick so that
     * an OS-initiated process kill loses at most a few seconds.
     */
    private void commitDueStretch() {
        long now = SystemClock.elapsedRealtime();

        if (mData.getCountingMode() == ParentalLimitData.COUNTING_MODE_PLAYBACK) {
            long start = mData.getPlayingStartElapsed();
            if (start > 0 && now - start >= COMMIT_INTERVAL_MS) {
                mData.commitStretch(now - start, true);
                mLastSavedUsedMs = mData.getUsedMs();
            }
            return;
        }

        long start = mData.getSessionStartElapsed();
        if (start > 0 && now - start >= COMMIT_INTERVAL_MS) {
            mData.commitStretch(now - start, false);
            mLastSavedUsedMs = mData.getUsedMs();
        }
    }

    /** Closes both live stretches (foreground session and playback run). */
    private void commitAllLiveTime() {
        long now = SystemClock.elapsedRealtime();

        long start = mData.getSessionStartElapsed();
        if (start > 0) {
            long delta = now - start;
            if (delta > 0) {
                mData.commitStretch(delta, false);
            }
        }

        long playingStart = mData.getPlayingStartElapsed();
        if (playingStart > 0) {
            long delta = now - playingStart;
            if (delta > 0) {
                mData.commitStretch(delta, true);
            }
        }

        mData.setSessionStartElapsed(0);
        mData.setPlayingStartElapsed(0);
        mLastSavedUsedMs = mData.getUsedMs();
    }

    /**
     * Keeps the playback stretch in sync with the engine: starts counting when
     * a video begins playing, commits the elapsed time when it stops.
     *
     * <p>Only counts while the app is in the foreground - a video left playing
     * in the background (PiP) does not accumulate.</p>
     */
    private void syncPlaybackState() {
        if (mData.getCountingMode() != ParentalLimitData.COUNTING_MODE_PLAYBACK) {
            return;
        }

        boolean isPlaying = mForegroundCount > 0 && isPlayerPlaying();
        long playingStart = mData.getPlayingStartElapsed();

        if (isPlaying) {
            if (playingStart <= 0) {
                mData.setPlayingStartElapsed(SystemClock.elapsedRealtime());
            }
            return;
        }

        if (playingStart > 0) {
            long delta = SystemClock.elapsedRealtime() - playingStart;
            mData.commitStretch(delta, true);
            mLastSavedUsedMs = mData.getUsedMs();
        }
    }

    /** True when the playback engine currently reports an active playback. */
    private boolean isPlayerPlaying() {
        try {
            PlaybackPresenter presenter = PlaybackPresenter.instance(mApp);
            PlaybackView view = presenter.getPlayer();
            return view != null && view.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }
}