package com.liskovsoft.smartyoutubetv2.tv.parental;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.parental.ParentalLimitController;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Fullscreen lock activity that appears when the daily quota is exhausted.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Displays the remaining rest time as a live countdown.</li>
 *   <li>While resting, the Back key is a no-op so the kid can't dismiss the
 *       lock. Only the parent's PIN-gated "Parent options" path can end it.</li>
 *   <li>While the quota is reached but the user hasn't entered rest yet
 *       (e.g. configuration edge case), the Back key still exits to home —
 *       we don't trap the user in the app forever.</li>
 * </ul>
 */
public class ParentalLimitActivity extends Activity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mCountdownView;
    private TextView mMessageView;
    private TextView mRemainingView;
    private Button mParentButton;
    private ParentalLimitController mController;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mController = ParentalLimitController.get();
        if (mController == null) {
            // Defensive: feature not initialized for whatever reason — don't trap user.
            finish();
            return;
        }

        setContentView(buildLayout());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mController != null) {
            mController.onLockDismissed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Block Back during rest so the kid can't bypass the lock. Allow Home so
        // the user can leave the app.
        if (keyCode == KeyEvent.KEYCODE_BACK && mController != null && mController.isResting()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101010"));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText(R.string.parental_limit_title);
        title.setTextSize(36);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, lp(LinearLayout.LayoutParams.WRAP_CONTENT));

        mMessageView = new TextView(this);
        mMessageView.setTextSize(22);
        mMessageView.setTextColor(Color.parseColor("#CCCCCC"));
        mMessageView.setGravity(Gravity.CENTER);
        mMessageView.setPadding(0, 32, 0, 0);
        root.addView(mMessageView, lp(LinearLayout.LayoutParams.WRAP_CONTENT));

        mCountdownView = new TextView(this);
        mCountdownView.setTextSize(80);
        mCountdownView.setTextColor(Color.WHITE);
        mCountdownView.setGravity(Gravity.CENTER);
        mCountdownView.setPadding(0, 48, 0, 48);
        mCountdownView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(mCountdownView, lp(LinearLayout.LayoutParams.WRAP_CONTENT));

        mRemainingView = new TextView(this);
        mRemainingView.setTextSize(24);
        mRemainingView.setTextColor(Color.parseColor("#9AE6B4"));
        mRemainingView.setGravity(Gravity.CENTER);
        mRemainingView.setPadding(0, 0, 0, 24);
        root.addView(mRemainingView, lp(LinearLayout.LayoutParams.WRAP_CONTENT));

        mParentButton = new Button(this);
        mParentButton.setText(R.string.parental_limit_parent_options);
        mParentButton.setTextSize(18);
        mParentButton.setFocusable(true);
        mParentButton.setOnClickListener(v -> onParentButtonClicked());
        LinearLayout.LayoutParams btnLp = lp(LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = 64;
        root.addView(mParentButton, btnLp);

        // Make sure focus lands on something sensible for the D-pad.
        mParentButton.requestFocus();

        return root;
    }

    private void refresh() {
        if (mController == null) {
            return;
        }

        // The rest deadline is wall-clock based, so it expires even while the
        // TV is switched off. Ask the controller to end the rest if it passed.
        mController.refreshRestState();

        if (mController.isResting()) {
            long remaining = mController.getRestRemainingMs();
            mMessageView.setText(R.string.parental_limit_rest_message);
            mCountdownView.setText(formatDuration(remaining));
            mRemainingView.setText(getString(R.string.parental_limit_rest_remaining,
                    formatMinutes(remaining)));
            mRemainingView.setVisibility(View.VISIBLE);
            mParentButton.setVisibility(View.VISIBLE);
            mParentButton.setText(R.string.parental_limit_parent_options);
            return;
        }

        // The rest period is over. If the quota is no longer exhausted the user
        // is free to continue - close the lock screen automatically instead of
        // trapping them on a dead-end screen.
        if (mController.getUsedMs() < mController.getLimitMs()) {
            finish();
            return;
        }

        // Not resting — either quota just exhausted (transition) or state is invalid.
        // Show a generic "time's up" prompt and let parent options unlock.
        mMessageView.setText(R.string.parental_limit_quota_message);
        mCountdownView.setText("00:00");
        mRemainingView.setVisibility(View.GONE);
        mParentButton.setVisibility(View.VISIBLE);
    }

    private void onParentButtonClicked() {
        if (mController == null || !mController.isPinConfigured()) {
            // Without a PIN, this button should normally be unreachable. If it is,
            // just send the user home rather than trapping them.
            MessageHelpers.showMessage(this, R.string.parental_limit_no_pin);
            goHome();
            return;
        }
        SimpleEditDialog.showPassword(
                this,
                getString(R.string.parental_limit_enter_pin),
                null,
                pin -> {
                    if (mController.checkPin(pin)) {
                        showParentMenu();
                        return true;
                    } else {
                        MessageHelpers.showMessage(this, R.string.parental_limit_wrong_pin);
                        return false;
                    }
                });
    }

    private void showParentMenu() {
        // Use the app's own dialog implementation. A plain framework
        // android.app.AlertDialog renders its list items greyed out and
        // unusable with a D-pad under the leanback theme.
        AppDialogPresenter presenter = AppDialogPresenter.instance(this);
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(
                getString(R.string.parental_limit_action_extend_10),
                option -> {
                    mController.extendCurrentSession(TimeUnit.MINUTES.toMillis(10));
                    presenter.closeDialog();
                    finish();
                }));

        options.add(UiOptionItem.from(
                getString(R.string.parental_limit_action_extend_30),
                option -> {
                    mController.extendCurrentSession(TimeUnit.MINUTES.toMillis(30));
                    presenter.closeDialog();
                    finish();
                }));

        options.add(UiOptionItem.from(
                getString(R.string.parental_limit_action_skip_rest),
                option -> {
                    mController.skipRest();
                    presenter.closeDialog();
                    finish();
                }));

        options.add(UiOptionItem.from(
                getString(R.string.parental_limit_action_reset_timer),
                option -> {
                    mController.resetTimer();
                    presenter.closeDialog();
                    finish();
                }));

        options.add(UiOptionItem.from(
                getString(R.string.parental_limit_action_disable_feature),
                option -> {
                    mController.setEnabled(false);
                    presenter.closeDialog();
                    goHome();
                }));

        options.add(UiOptionItem.from(
                getString(R.string.parental_limit_action_cancel),
                option -> presenter.closeDialog()));

        presenter.appendStringsCategory(
                getString(R.string.parental_limit_parent_options), options);
        presenter.showDialog(getString(R.string.parental_limit_parent_options));
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (Exception ignored) {
        }
        finish();
    }

    private static LinearLayout.LayoutParams lp(int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height);
        p.gravity = Gravity.CENTER;
        return p;
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "00:00:00";
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** "30 分钟" / "1 小时 30 分钟" style duration for the remaining time. */
    private static String formatMinutes(long ms) {
        long totalMinutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(ms));
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0 && minutes > 0) {
            return String.format(Locale.getDefault(), "%d 小时 %d 分钟", hours, minutes);
        }
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d 小时", hours);
        }
        return String.format(Locale.getDefault(), "%d 分钟", minutes);
    }
}