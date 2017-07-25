/*
 * Copyright (C) 2016 SlimRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.slim;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SlimNavigationBarView;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;

public class SlimStatusBar extends PhoneStatusBar {

    private static final String TAG = SlimStatusBar.class.getSimpleName();

    private SlimNavigationBarView mSlimNavigationBarView;

    private boolean mHasNavigationBar = false;
    private boolean mNavigationBarAttached = false;
    private boolean mDisableHomeLongpress = false;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_BUTTON_TINT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_BUTTON_TINT_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_GLOW_TINT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_SHOW),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_CONFIG),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_CAN_MOVE),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);

            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_BUTTON_TINT))
                || uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_BUTTON_TINT_MODE))
                || uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_CONFIG))
                || uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_GLOW_TINT))) {
                if (mSlimNavigationBarView != null) {
                    mSlimNavigationBarView.recreateNavigationBar();
                    prepareNavigationBarView();
                }
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_CAN_MOVE))) {
                prepareNavigationBarView();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_SHOW))) {
                updateNavigationBarVisibility();
            }
        }
    }

    @Override
    public void start() {
        super.start();

        updateNavigationBarVisibility();

        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();
    }

    @Override
    protected PhoneStatusBarView makeStatusBarView() {
        PhoneStatusBarView statusBarView = super.makeStatusBarView();
        return mStatusBarView;
    }

    @Override
    protected void createNavigationBarView(Context context) {
        if (mSlimNavigationBarView == null) {
            mSlimNavigationBarView = (SlimNavigationBarView)
                    View.inflate(mContext, R.layout.slim_navigation_bar, null);
        }
        mSlimNavigationBarView.setDisabledFlags(mDisabled1);
        //mSlimNavigationBarView.setBar(this);
        mSlimNavigationBarView.setOnVerticalChangedListener(
                new NavigationBarView.OnVerticalChangedListener() {
            @Override
            public void onVerticalChanged(boolean isVertical) {
                if (mAssistManager != null) {
                    mAssistManager.onConfigurationChanged();
                }
                mNotificationPanel.setQsScrimEnabled(!isVertical);
            }
        });
        mSlimNavigationBarView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                checkUserAutohide(v, event);
                return false;
            }
        });

        if (mNavigationBarView != mSlimNavigationBarView) {
            mNavigationBarView = mSlimNavigationBarView;
        }
    }

    private void updateNavigationBarVisibility() {
        final int showByDefault = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_showNavigationBar) ? 1 : 0;
        mHasNavigationBar = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.NAVIGATION_BAR_SHOW, showByDefault,
                    UserHandle.USER_CURRENT) == 1;

        if (mHasNavigationBar) {
            addNavigationBar();
        } else {
            if (mNavigationBarAttached) {
                mNavigationBarAttached = false;
                mWindowManager.removeView(mSlimNavigationBarView);
            }
        }
    }

    @Override
    protected void prepareNavigationBarView() {
        mSlimNavigationBarView.reorient();

        mSlimNavigationBarView.setPinningCallback(mLongClickCallback);

        mAssistManager.onConfigurationChanged();
    }

    @Override
    protected void addNavigationBar() {
        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + mSlimNavigationBarView);
        if (mSlimNavigationBarView == null) {
            createNavigationBarView(mContext);
        }

        prepareNavigationBarView();

        if (!mNavigationBarAttached) {
            mNavigationBarAttached = true;
            try {
                mWindowManager.addView(mSlimNavigationBarView, getNavigationBarLayoutParams());
            } catch (Exception e) {}
        }
    }

    @Override
    protected void repositionNavigationBar() {
        if (mSlimNavigationBarView == null
                || !mSlimNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout(mSlimNavigationBarView, getNavigationBarLayoutParams());
    }

    private WindowManager.LayoutParams getNavigationBarLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    0
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        // this will allow the navbar to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.setTitle("NavigationBar");
        lp.windowAnimations = 0;
        return lp;
    }

    private long mLastLockToAppLongPress;
    private SlimKeyButtonView.LongClickCallback mLongClickCallback =
            new SlimKeyButtonView.LongClickCallback() {
        @Override
        public boolean onLongClick(View v) {
            return handleLongPressBackRecents(v);
        }
    };

    private boolean handleLongPressBackRecents(View v) {
        try {
            boolean sendBackLongPress = false;
            IActivityManager activityManager = ActivityManagerNative.getDefault();
            boolean isAccessiblityEnabled = mAccessibilityManager.isEnabled();
            if (activityManager.isInLockTaskMode() && !isAccessiblityEnabled) {
                // If we recently long-pressed the other button then they were
                // long-pressed 'together'
                if (mSlimNavigationBarView.getRightMenuButton().isPressed()
                        && mSlimNavigationBarView.getLeftMenuButton().isPressed()) {
                    //activityManager.stopLockTaskModeOnCurrent();
                    // When exiting refresh disabled flags.
                    mSlimNavigationBarView.setDisabledFlags(mDisabled1, true);
                    mSlimNavigationBarView.setOverrideMenuKeys(false);
                } else if ((v.getId() == mSlimNavigationBarView.getLeftMenuButton().getId())
                        && !mSlimNavigationBarView.getRightMenuButton().isPressed()) {
                    // If we aren't pressing recents right now then they presses
                    // won't be together, so send the standard long-press action.
                    sendBackLongPress = true;
                }
            } else {
                // If this is back still need to handle sending the long-press event.
                long time = System.currentTimeMillis();
                if (( time - mLastLockToAppLongPress) < 2000) {
                    if (v.getId() == mSlimNavigationBarView.getLeftMenuButton().getId()
                        || v.getId() == mSlimNavigationBarView.getRightMenuButton().getId()) {
                        sendBackLongPress = true;
                    }
                } else if (isAccessiblityEnabled && activityManager.isInLockTaskMode()) {
                    // When in accessibility mode a long press that is recents (not back)
                    // should stop lock task.
                    //activityManager.stopLockTaskModeOnCurrent();
                    // When exiting refresh disabled flags.
                    mSlimNavigationBarView.setDisabledFlags(mDisabled1, true);
                    mSlimNavigationBarView.setOverrideMenuKeys(false);
                }
                mLastLockToAppLongPress = time;
            }
            return sendBackLongPress;
        } catch (RemoteException e) {
            Log.d(TAG, "Unable to reach activity manager", e);
            return false;
        }
    }
}
