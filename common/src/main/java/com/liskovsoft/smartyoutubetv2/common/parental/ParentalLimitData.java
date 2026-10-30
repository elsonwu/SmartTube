package com.liskovsoft.smartyoutubetv2.common.parental;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * Simple prefs holder for the parental time-limit feature.
 *
 * <p>Uses a dedicated {@link SharedPreferences} file rather than {@code AppPrefs}
 * to avoid coupling with the existing settings serialization format and to keep
 * the change self-contained for this fork.</p>
 *
 * <p>PIN is stored as a salted SHA-256 hash (hex). The salt is a 16-byte
 * cryptographically random value. We deliberately do not use PBKDF2 here to
 * avoid pulling in extra crypto provider wiring; SHA-256 + salt is sufficient
 * to avoid storing a plaintext PIN in user-accessible storage, which is the
 * primary threat for this local-only feature.</p>
 */
public final class ParentalLimitData {
    public static final long DEFAULT_LIMIT_MS = TimeUnit.MINUTES.toMillis(30);
    public static final long DEFAULT_REST_MS = TimeUnit.MINUTES.toMillis(30);
    public static final long MIN_LIMIT_MS = TimeUnit.MINUTES.toMillis(5);
    public static final long MAX_LIMIT_MS = TimeUnit.HOURS.toMillis(4);
    public static final long MIN_REST_MS = TimeUnit.MINUTES.toMillis(5);
    public static final long MAX_REST_MS = TimeUnit.HOURS.toMillis(4);

    /** Count any time the app is in the foreground (browsing, searching, playing). */
    public static final int COUNTING_MODE_FOREGROUND = 0;
    /** Count only the time a video is actually playing. */
    public static final int COUNTING_MODE_PLAYBACK = 1;

    private static final String PREFS = "parental_limit";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LIMIT_MS = "limit_ms";
    private static final String KEY_REST_MS = "rest_ms";
    private static final String KEY_USED_MS = "used_ms";
    private static final String KEY_SESSION_START = "session_start_elapsed";
    private static final String KEY_REST_UNTIL = "rest_until_wall";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_LAST_OVERRIDE = "last_override_elapsed";
    private static final String KEY_COUNTING_MODE = "counting_mode";
    private static final String KEY_PLAYING_START = "playing_start_elapsed";

    @SuppressLint("StaticFieldLeak")
    private static ParentalLimitData sInstance;
    private final SharedPreferences mPrefs;

    private ParentalLimitData(Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized ParentalLimitData instance(Context context) {
        if (sInstance == null) {
            sInstance = new ParentalLimitData(context);
        }
        return sInstance;
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public long getLimitMs() {
        long v = mPrefs.getLong(KEY_LIMIT_MS, DEFAULT_LIMIT_MS);
        return clamp(v, MIN_LIMIT_MS, MAX_LIMIT_MS);
    }

    public void setLimitMs(long ms) {
        mPrefs.edit().putLong(KEY_LIMIT_MS, clamp(ms, MIN_LIMIT_MS, MAX_LIMIT_MS)).apply();
    }

    public long getRestMs() {
        long v = mPrefs.getLong(KEY_REST_MS, DEFAULT_REST_MS);
        return clamp(v, MIN_REST_MS, MAX_REST_MS);
    }

    public void setRestMs(long ms) {
        mPrefs.edit().putLong(KEY_REST_MS, clamp(ms, MIN_REST_MS, MAX_REST_MS)).apply();
    }

    public long getUsedMs() {
        return mPrefs.getLong(KEY_USED_MS, 0);
    }

    public void setUsedMs(long ms) {
        mPrefs.edit().putLong(KEY_USED_MS, Math.max(0, ms)).apply();
    }

    /**
     * The {@link android.os.SystemClock#elapsedRealtime()} value at which the
     * current foreground session started, or 0 if no session is active.
     */
    public long getSessionStartElapsed() {
        return mPrefs.getLong(KEY_SESSION_START, 0);
    }

    public void setSessionStartElapsed(long elapsed) {
        mPrefs.edit().putLong(KEY_SESSION_START, elapsed).apply();
    }

    /**
     * Wall-clock millis until which the rest lock is enforced.
     * Zero means "not currently resting".
     */
    public long getRestUntilWall() {
        return mPrefs.getLong(KEY_REST_UNTIL, 0);
    }

    public void setRestUntilWall(long wallMs) {
        mPrefs.edit().putLong(KEY_REST_UNTIL, wallMs).apply();
    }

    public boolean isPinConfigured() {
        return mPrefs.contains(KEY_PIN_HASH) && mPrefs.contains(KEY_PIN_SALT);
    }

    public void setPin(String pin) {
        if (pin == null || pin.isEmpty()) {
            mPrefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String hash = PinHasher.hash(pin, salt);
        mPrefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putString(KEY_PIN_SALT, PinHasher.toHex(salt))
                .apply();
    }

    public boolean checkPin(String pin) {
        if (!isPinConfigured() || pin == null || pin.isEmpty()) {
            return false;
        }
        String storedHash = mPrefs.getString(KEY_PIN_HASH, null);
        String saltHex = mPrefs.getString(KEY_PIN_SALT, null);
        if (storedHash == null || saltHex == null) {
            return false;
        }
        byte[] salt = PinHasher.fromHex(saltHex);
        String candidate = PinHasher.hash(pin, salt);
        return constantTimeEquals(storedHash, candidate);
    }

    /**
     * The {@link android.os.SystemClock#elapsedRealtime()} value at which the
     * parent last issued an "extend" override. Used to bound how long an
     * override can keep the app alive past the configured limit.
     */
    public long getLastOverrideElapsed() {
        return mPrefs.getLong(KEY_LAST_OVERRIDE, 0);
    }

    public void setLastOverrideElapsed(long elapsed) {
        mPrefs.edit().putLong(KEY_LAST_OVERRIDE, elapsed).apply();
    }

    /** {@link #COUNTING_MODE_FOREGROUND} or {@link #COUNTING_MODE_PLAYBACK}. */
    public int getCountingMode() {
        int mode = mPrefs.getInt(KEY_COUNTING_MODE, COUNTING_MODE_FOREGROUND);
        return mode == COUNTING_MODE_PLAYBACK ? COUNTING_MODE_PLAYBACK : COUNTING_MODE_FOREGROUND;
    }

    public void setCountingMode(int mode) {
        mPrefs.edit()
                .putInt(KEY_COUNTING_MODE, mode == COUNTING_MODE_PLAYBACK
                        ? COUNTING_MODE_PLAYBACK : COUNTING_MODE_FOREGROUND)
                .apply();
    }

    /**
     * The {@link android.os.SystemClock#elapsedRealtime()} value at which the
     * current uninterrupted playback stretch started, or 0 when nothing is
     * playing. Only used in {@link #COUNTING_MODE_PLAYBACK}.
     */
    public long getPlayingStartElapsed() {
        return mPrefs.getLong(KEY_PLAYING_START, 0);
    }

    public void setPlayingStartElapsed(long elapsed) {
        mPrefs.edit().putLong(KEY_PLAYING_START, elapsed).apply();
    }

    /**
     * Commits a live stretch into the accumulated total and closes that
     * stretch.
     *
     * <p>The accumulated total ({@code usedMs}) must only ever hold
     * <em>completed</em> time. The live stretch (foreground session or
     * playback run) is tracked separately via {@code sessionStartElapsed} /
     * {@code playingStartElapsed} and added on top when the total is
     * computed.</p>
     *
     * <p>Storing the running total instead would double-count: the next read
     * would add the stretch delta on top of a base that already included it,
     * making the counted time grow quadratically (42 seconds of real usage
     * would look like 30 minutes).</p>
     *
     * @param deltaMs   length of the stretch being closed
     * @param playback  true to close the playback stretch, false for the
     *                  foreground session stretch
     */
    public void commitStretch(long deltaMs, boolean playback) {
        long newUsed = getUsedMs() + Math.max(0, deltaMs);
        SharedPreferences.Editor editor = mPrefs.edit().putLong(KEY_USED_MS, newUsed);
        if (playback) {
            editor.putLong(KEY_PLAYING_START, 0);
        } else {
            editor.putLong(KEY_SESSION_START, 0);
        }
        editor.apply();
    }

    /** Reset all state to defaults. Caller must verify PIN first. */
    public void resetAll() {
        mPrefs.edit().clear().apply();
    }

    /** Reset only the timer/rest state, preserving PIN and configuration. */
    public void resetTimerState() {
        mPrefs.edit()
                .remove(KEY_USED_MS)
                .remove(KEY_SESSION_START)
                .remove(KEY_REST_UNTIL)
                .remove(KEY_LAST_OVERRIDE)
                .remove(KEY_PLAYING_START)
                .apply();
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}