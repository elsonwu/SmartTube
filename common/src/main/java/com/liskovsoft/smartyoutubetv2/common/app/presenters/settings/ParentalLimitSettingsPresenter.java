package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.parental.ParentalLimitController;
import com.liskovsoft.smartyoutubetv2.common.parental.ParentalLimitData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Settings UI for the parental time-limit feature.
 *
 * <p>Every privileged action (toggle feature, change limit/rest, change PIN,
 * reset all) goes through {@link #requirePin(Runnable, Runnable)} which
 * prompts for the configured PIN first. When no PIN is set yet, we instead
 * run the action directly — this is the only legitimate "first-use" flow that
 * lets a parent initialise the feature without being locked out by their own
 * (not-yet-existing) PIN.</p>
 *
 * <p>The presenter deliberately lives in the common module so it can be
 * surfaced from any settings entry point (general settings, sidebar, etc.).
 * The runtime side (controller + activity) is in the tv module to keep the
 * player code paths in {@code common} free of UI dependencies.</p>
 */
public class ParentalLimitSettingsPresenter extends BasePresenter<Void> {
    private final ParentalLimitData mData;

    public ParentalLimitSettingsPresenter(Context context) {
        super(context);
        mData = ParentalLimitData.instance(context);
    }

    public static ParentalLimitSettingsPresenter instance(Context context) {
        return new ParentalLimitSettingsPresenter(context);
    }

    public void show() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        appendEnableSwitch(dialog);
        appendCountingModeCategory(dialog);
        appendLimitCategory(dialog);
        appendRestCategory(dialog);
        appendPinCategory(dialog);
        appendResetCategory(dialog);

        dialog.showDialog(getContext().getString(R.string.parental_limit_settings_title));
    }

    // ─── Categories ────────────────────────────────────────────────────────

    /**
     * Lets the parent choose what the quota measures: any foreground time, or
     * only the time a video is actually playing.
     */
    private void appendCountingModeCategory(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();
        int current = mData.getCountingMode();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.parental_limit_counting_foreground),
                getContext().getString(R.string.parental_limit_counting_foreground_desc),
                option -> requirePin(
                        getContext().getString(R.string.parental_limit_confirm_change_counting),
                        () -> mData.setCountingMode(ParentalLimitData.COUNTING_MODE_FOREGROUND),
                        null),
                current == ParentalLimitData.COUNTING_MODE_FOREGROUND));

        options.add(UiOptionItem.from(
                getContext().getString(R.string.parental_limit_counting_playback),
                getContext().getString(R.string.parental_limit_counting_playback_desc),
                option -> requirePin(
                        getContext().getString(R.string.parental_limit_confirm_change_counting),
                        () -> mData.setCountingMode(ParentalLimitData.COUNTING_MODE_PLAYBACK),
                        null),
                current == ParentalLimitData.COUNTING_MODE_PLAYBACK));

        dialog.appendRadioCategory(getContext().getString(R.string.parental_limit_counting_mode), options);
    }

    private void appendEnableSwitch(AppDialogPresenter dialog) {
        dialog.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.parental_limit_enable),
                getContext().getString(R.string.parental_limit_enable_desc),
                option -> {
                    if (option.isSelected()) {
                        // Toggling on for the first time: ensure a PIN exists.
                        if (!mData.isPinConfigured()) {
                            promptSetPin(
                                    getContext().getString(R.string.parental_limit_set_pin_first_time),
                                    () -> setEnabled(true));
                        } else {
                            requirePin(
                                    getContext().getString(R.string.parental_limit_confirm_to_enable),
                                    () -> setEnabled(true),
                                    null);
                        }
                    } else {
                        requirePin(
                                getContext().getString(R.string.parental_limit_confirm_to_disable),
                                () -> setEnabled(false),
                                null);
                    }
                },
                mData.isEnabled()));
    }

    private void appendLimitCategory(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();
        long current = mData.getLimitMs();
        for (int minutes : new int[]{15, 20, 30, 45, 60, 90, 120}) {
            long ms = TimeUnit.MINUTES.toMillis(minutes);
            options.add(UiOptionItem.from(
                    formatMinutes(minutes),
                    option -> requirePin(
                            getContext().getString(R.string.parental_limit_confirm_change_limit),
                            () -> mData.setLimitMs(ms),
                            null),
                    current == ms));
        }
        dialog.appendRadioCategory(getContext().getString(R.string.parental_limit_view_limit), options);
    }

    private void appendRestCategory(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();
        long current = mData.getRestMs();
        for (int minutes : new int[]{15, 30, 45, 60, 90, 120}) {
            long ms = TimeUnit.MINUTES.toMillis(minutes);
            options.add(UiOptionItem.from(
                    formatMinutes(minutes),
                    option -> requirePin(
                            getContext().getString(R.string.parental_limit_confirm_change_rest),
                            () -> mData.setRestMs(ms),
                            null),
                    current == ms));
        }
        dialog.appendRadioCategory(getContext().getString(R.string.parental_limit_rest_length), options);
    }

    private void appendPinCategory(AppDialogPresenter dialog) {
        dialog.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.parental_limit_change_pin),
                optionItem -> promptChangePin()));
    }

    private void appendResetCategory(AppDialogPresenter dialog) {
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.parental_limit_reset_timer_only),
                option -> requirePin(
                        getContext().getString(R.string.parental_limit_confirm_reset_timer),
                        () -> {
                            ParentalLimitController c = ParentalLimitController.get();
                            if (c != null) {
                                c.resetTimer();
                            }
                            MessageHelpers.showMessage(getContext(),
                                    R.string.parental_limit_reset_done);
                        },
                        null)));

        options.add(UiOptionItem.from(
                getContext().getString(R.string.parental_limit_reset_all),
                getContext().getString(R.string.parental_limit_reset_all_desc),
                option -> requirePin(
                        getContext().getString(R.string.parental_limit_confirm_reset_all),
                        () -> {
                            ParentalLimitController c = ParentalLimitController.get();
                            if (c != null) {
                                c.resetAll();
                            }
                            MessageHelpers.showMessage(getContext(),
                                    R.string.parental_limit_reset_done);
                        },
                        null)));

        dialog.appendCheckedCategory(getContext().getString(R.string.parental_limit_reset), options);
    }

    // ─── PIN handling ──────────────────────────────────────────────────────

    /**
     * Prompt for the configured PIN before running {@code onSuccess}. If no PIN
     * is set yet, run {@code onFirstRun} instead — that's the only safe way to
     * bootstrap the feature without a chicken-and-egg problem.
     */
    private void requirePin(String prompt, Runnable onSuccess, Runnable onFirstUse) {
        if (!mData.isPinConfigured()) {
            // Bootstrap path: force the parent to set a PIN before any change.
            if (onFirstUse != null) {
                promptSetPin(prompt, onFirstUse);
            } else {
                promptSetPin(prompt, onSuccess);
            }
            return;
        }
        SimpleEditDialog.showPassword(
                getContext(),
                prompt,
                null,
                pin -> {
                    if (mData.checkPin(pin)) {
                        onSuccess.run();
                        return true;
                    }
                    MessageHelpers.showMessage(getContext(),
                            R.string.parental_limit_wrong_pin);
                    return false;
                });
    }

    private void requirePin(Runnable onSuccess, Runnable onFirstUse) {
        requirePin(getContext().getString(R.string.parental_limit_enter_pin),
                onSuccess, onFirstUse);
    }

    private void promptSetPin(String title, Runnable afterSet) {
        SimpleEditDialog.showPassword(
                getContext(),
                title,
                null,
                pin -> {
                    if (pin.length() < 4) {
                        MessageHelpers.showMessage(getContext(),
                                R.string.parental_limit_pin_too_short);
                        return false;
                    }
                    mData.setPin(pin);
                    ParentalLimitController c = ParentalLimitController.get();
                    if (c != null) {
                        c.setPin(pin);
                    }
                    if (afterSet != null) {
                        afterSet.run();
                    }
                    return true;
                });
    }

    private void promptChangePin() {
        if (!mData.isPinConfigured()) {
            promptSetPin(getContext().getString(R.string.parental_limit_set_pin), null);
            return;
        }
        SimpleEditDialog.showPassword(
                getContext(),
                getContext().getString(R.string.parental_limit_enter_current_pin),
                null,
                oldPin -> {
                    if (!mData.checkPin(oldPin)) {
                        MessageHelpers.showMessage(getContext(),
                                R.string.parental_limit_wrong_pin);
                        return false;
                    }
                    promptSetPin(
                            getContext().getString(R.string.parental_limit_enter_new_pin),
                            () -> MessageHelpers.showMessage(getContext(),
                                    R.string.parental_limit_pin_changed));
                    return true;
                });
    }

    private void setEnabled(boolean enabled) {
        ParentalLimitController c = ParentalLimitController.get();
        if (c != null) {
            c.setEnabled(enabled);
        } else {
            mData.setEnabled(enabled);
        }
        MessageHelpers.showMessage(getContext(),
                enabled ? R.string.parental_limit_enabled_msg
                        : R.string.parental_limit_disabled_msg);
    }

    private static String formatMinutes(int minutes) {
        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%d min", minutes);
        }
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? String.format(Locale.getDefault(), "%d h", h)
                      : String.format(Locale.getDefault(), "%d h %d min", h, m);
    }
}