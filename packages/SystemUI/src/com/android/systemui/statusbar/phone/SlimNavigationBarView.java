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
package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityManagerNative;
import android.app.admin.DevicePolicyManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.slim.SlimKeyButtonView;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.util.aicp.ActionConfig;
import com.android.internal.util.aicp.ActionConstants;
import com.android.internal.util.aicp.ActionHelper;
import com.android.internal.util.aicp.ImageHelper;
import com.android.internal.util.omni.DeviceUtils;

public class SlimNavigationBarView extends NavigationBarView {

    // Definitions for navbar menu button customization
    private final static int SHOW_RIGHT_MENU = 0;
    private final static int SHOW_LEFT_MENU = 1;
    private final static int SHOW_BOTH_MENU = 2;

    private final static int MENU_VISIBILITY_ALWAYS = 0;
    private final static int MENU_VISIBILITY_NEVER = 1;
    private final static int MENU_VISIBILITY_SYSTEM = 2;

    private static final int KEY_MENU_RIGHT = 0;
    private static final int KEY_MENU_LEFT = 1;
    private static final int KEY_IME_SWITCHER = 2;

    private int mMenuVisibility;
    private int mMenuSetting;
    private boolean mOverrideMenuKeys;
    private boolean mIsImeButtonVisible = false;
    private boolean mWakeAndUnlocking;
    private boolean mLayoutTransitionsEnabled = true;

    private DeadZone mDeadZone;

    private Drawable mBackIcon, mBackLandIcon;

    private int mRippleColor;

    private int mNavBarButtonColor;
    private int mNavBarButtonColorMode;
    private boolean mAppIsBinded = false;

    private FrameLayout mRot0;
    private FrameLayout mRot90;

    private ArrayList<ActionConfig> mButtonsConfig;
    private List<Integer> mButtonIdList;

    private SlimKeyButtonView.LongClickCallback mCallback;

    // performs manual animation in sync with layout transitions
    private final SlimNavTransitionListener mTransitionListener = new SlimNavTransitionListener();

    private class SlimNavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mHomeAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = true;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = false;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = false;
            }
        }

        public void onBackAltCleared() {
            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            if (getBackButton() == null || getHomeButton() == null) return;
            if (!mBackTransitioning && getBackButton().getVisibility() == VISIBLE
                    && mHomeAppearing && getHomeButton().getAlpha() == 0) {
                getBackButton().setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(getBackButton(), "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    private final OnClickListener mImeSwitcherClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            ((InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showInputMethodPicker(true /* showAuxiliarySubtypes */);
        }
    };

    public SlimNavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mButtonsConfig = ActionHelper.getNavBarConfig(mContext);
        mButtonIdList = new ArrayList<Integer>();

        getIcons(context.getResources());
    }

    public List<Integer> getButtonIdList() {
        return mButtonIdList;
    }

    public View getLeftMenuButton() {
        return mCurrentView.findViewById(R.id.menu_left);
    }

    public View getRightMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getCustomButton(int buttonId) {
        return mCurrentView.findViewById(buttonId);
    }

    public ViewGroup getNavButtons() {
        return (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
    }

    public void setOverrideMenuKeys(boolean b) {
        mOverrideMenuKeys = b;
        setMenuVisibility(mShowMenu, true /* force */);
    }

    private void getIcons(Resources res) {
        Drawable backIcon, backIconLand;
        ActionConfig actionConfig;
        String backIconUri = ActionConstants.ICON_EMPTY;
        for (int j = 0; j < mButtonsConfig.size(); j++) {
            actionConfig = mButtonsConfig.get(j);
            final String action = actionConfig.getClickAction();
            if (action.equals(ActionConstants.ACTION_BACK)) {
                backIconUri = actionConfig.getIcon();
            }
        }

        backIcon = ActionHelper.getActionIconImage(mContext,
                ActionConstants.ACTION_BACK, backIconUri);
        backIconLand = backIcon;

        boolean shouldColor = true;
        if (backIconUri != null && !backIconUri.equals(ActionConstants.ICON_EMPTY)
                && !backIconUri.startsWith(ActionConstants.SYSTEM_ICON_IDENTIFIER)
                && mNavBarButtonColorMode == 1) {
            shouldColor = false;
        }

        // update back buttons color
        if (shouldColor && mNavBarButtonColorMode != 3) {
            backIcon.mutate();
            backIcon.setTintMode(PorterDuff.Mode.MULTIPLY);
            backIcon.setTint(mNavBarButtonColor);

            backIconLand.mutate();
            backIconLand.setTintMode(PorterDuff.Mode.MULTIPLY);
            backIconLand.setTint(mNavBarButtonColor);
        }

        mBackIcon     = backIcon;
        mBackLandIcon = backIconLand;
    }

    @Override
    public void updateIcons(Context ctx, Configuration oldConfig, Configuration newConfig) {
        super.updateIcons(Context ctx, Configuration oldConfig, Configuration newConfig);
        getIcons(getContext().getResources());
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        updateSettings(true);
        super.setLayoutDirection(layoutDirection);
    }

    public void setPinningCallback(SlimKeyButtonView.LongClickCallback c) {
        mCallback = c;
    }

    private void makeBar() {
        if (mButtonsConfig.isEmpty() || mButtonsConfig == null) {
            return;
        }

        mButtonIdList.clear();

        mRippleColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_GLOW_TINT, -2, UserHandle.USER_CURRENT);

        ((LinearLayout) mRot0.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) mRot0.findViewById(R.id.lights_out)).removeAllViews();
        ((LinearLayout) mRot90.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) mRot90.findViewById(R.id.lights_out)).removeAllViews();

        for (int i = 0; i <= 1; i++) {
            boolean landscape = (i == 1);

            LinearLayout navButtonLayout = (LinearLayout) (landscape ? mRot90
                    .findViewById(R.id.nav_buttons) : mRot0
                    .findViewById(R.id.nav_buttons));

            LinearLayout lightsOut = (LinearLayout) (landscape ? mRot90
                    .findViewById(R.id.lights_out) : mRot0
                    .findViewById(R.id.lights_out));

            // add left menu
            SlimKeyButtonView leftMenuKeyView = generateMenuKey(landscape, KEY_MENU_LEFT);
            leftMenuKeyView.setLongClickCallback(mCallback);
            addButton(navButtonLayout, leftMenuKeyView, landscape);
            addLightsOutButton(lightsOut, leftMenuKeyView, landscape, true);

            mAppIsBinded = false;
            ActionConfig actionConfig;

            for (int j = 0; j < mButtonsConfig.size(); j++) {
                actionConfig = mButtonsConfig.get(j);
                SlimKeyButtonView v = generateKey(landscape,
                        actionConfig.getClickAction(),
                        actionConfig.getLongpressAction(),
                        actionConfig.getDoubleTapAction(),
                        actionConfig.getIcon());
                v.setTag((landscape ? "key_land_" : "key_") + j);

                addButton(navButtonLayout, v, landscape);
                addLightsOutButton(lightsOut, v, landscape, false);

                if (mButtonsConfig.size() == 3
                        && j != (mButtonsConfig.size() - 1)) {
                    // add separator view here
                    View separator = new View(mContext);
                    separator.setLayoutParams(getSeparatorLayoutParams(landscape));
                    addButton(navButtonLayout, separator, landscape);
                    addLightsOutButton(lightsOut, separator, landscape, true);
                }

            }

            SlimKeyButtonView rightMenuKeyView = generateMenuKey(landscape, KEY_MENU_RIGHT);
            rightMenuKeyView.setLongClickCallback(mCallback);
            addButton(navButtonLayout, rightMenuKeyView, landscape);
            addLightsOutButton(lightsOut, rightMenuKeyView, landscape, true);

            View imeSwitcher = generateMenuKey(landscape, KEY_IME_SWITCHER);
            addButton(navButtonLayout, imeSwitcher, landscape);
            addLightsOutButton(lightsOut, imeSwitcher, landscape, true);
        }
        setMenuVisibility(mShowMenu, true);
    }

    public void recreateNavigationBar() {
        updateSettings(true);
    }

    public void updateNavigationBarSettings() {
        updateSettings(false);
    }

    private SlimKeyButtonView generateKey(boolean landscape, String clickAction,
            String longpress, String doubletap,
            String iconUri) {

        SlimKeyButtonView v = new SlimKeyButtonView(mContext, null);
        v.setClickAction(clickAction);
        v.setLongpressAction(longpress);
        v.setDoubleTapAction(doubletap);
        int i = mContext.getResources().getDimensionPixelSize(R.dimen.navigation_key_width);
        v.setLayoutParams(getLayoutParams(landscape, i));

        if (clickAction.equals(ActionConstants.ACTION_BACK)) {
            v.setId(R.id.back);
        } else if (clickAction.equals(ActionConstants.ACTION_HOME)) {
            v.setId(R.id.home);
        } else if (clickAction.equals(ActionConstants.ACTION_RECENTS)) {
            v.setId(R.id.recent_apps);
        } else {
            int buttonId = v.generateViewId();
            v.setId(buttonId);
            mButtonIdList.add(buttonId);
        }

        if (clickAction.startsWith("**")) {
            v.setScaleType(SlimKeyButtonView.ScaleType.CENTER_INSIDE);
        }

        boolean colorize = true;
        if (iconUri != null && !iconUri.equals(ActionConstants.ICON_EMPTY)
                && !iconUri.startsWith(ActionConstants.SYSTEM_ICON_IDENTIFIER)
                && mNavBarButtonColorMode == 1) {
            colorize = false;
        } else if (!clickAction.startsWith("**")) {
            final int[] appIconPadding = getAppIconPadding();
            if (landscape) {
                v.setPaddingRelative(appIconPadding[1], appIconPadding[0],
                        appIconPadding[3], appIconPadding[2]);
            } else {
                v.setPaddingRelative(appIconPadding[0], appIconPadding[1],
                        appIconPadding[2], appIconPadding[3]);
            }
            if (mNavBarButtonColorMode != 0) {
                colorize = false;
            }
            mAppIsBinded = true;
        }

        Drawable d = ActionHelper.getActionIconImage(mContext, clickAction, iconUri);

        if (d != null) {
            d.mutate();
            if (colorize && mNavBarButtonColorMode != 3) {
                d = ImageHelper.getColoredDrawable(d, mNavBarButtonColor);
            }
            v.setImageBitmap(ImageHelper.drawableToBitmap(d));
        }
        v.setRippleColor(mRippleColor);
        return v;
    }

    private SlimKeyButtonView generateMenuKey(boolean landscape, int keyId) {
        Drawable d = null;
        SlimKeyButtonView v = new SlimKeyButtonView(mContext, null);
        int width = mContext.getResources().getDimensionPixelSize(
                R.dimen.navigation_extra_key_width);
        v.setLayoutParams(getLayoutParams(landscape, width));
        v.setScaleType(SlimKeyButtonView.ScaleType.CENTER_INSIDE);
        if (keyId == KEY_MENU_LEFT || keyId == KEY_MENU_RIGHT) {
            v.setClickAction(ActionConstants.ACTION_MENU);
            v.setLongpressAction(ActionConstants.ACTION_NULL);
            if (keyId == KEY_MENU_LEFT) {
                v.setId(R.id.menu_left);
            } else {
                v.setId(R.id.menu);
            }
            v.setVisibility(View.INVISIBLE);
            v.setContentDescription(getResources().getString(R.string.accessibility_menu));
            d = mContext.getResources().getDrawable(R.drawable.ic_sysbar_menu);
        } else if (keyId == KEY_IME_SWITCHER) {
            v.setClickAction(ActionConstants.ACTION_IME);
            v.setId(R.id.ime_switcher);
            v.setVisibility(View.GONE);
            d = mContext.getResources().getDrawable(R.drawable.ic_ime_switcher_default);
        }

        if (d != null) {
            d.mutate();
            if (mNavBarButtonColorMode != 3) {
                if (d instanceof VectorDrawable) {
                    d.setTint(mNavBarButtonColor);
                } else {
                    d = ImageHelper.getColoredDrawable(d, mNavBarButtonColor);
                }
            }
            v.setImageBitmap(ImageHelper.drawableToBitmap(d));
        }
        v.setRippleColor(mRippleColor);

        return v;
    }

    private int[] getAppIconPadding() {
        int[] padding = new int[4];
        // left
        padding[0] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // top
        padding[1] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources()
                .getDisplayMetrics());
        // right
        padding[2] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // bottom
        padding[3] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5,
                getResources()
                        .getDisplayMetrics());
        return padding;
    }

    private LayoutParams getLayoutParams(boolean landscape, int dp) {
        float px = dp * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, dp, 1f) :
                new LayoutParams(dp, LayoutParams.MATCH_PARENT, 1f);
    }

    private LayoutParams getSeparatorLayoutParams(boolean landscape) {
        float px = 25 * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT);
    }

    private void addLightsOutButton(LinearLayout root, View v, boolean landscape, boolean empty) {
        ImageView addMe = new ImageView(mContext);
        addMe.setLayoutParams(v.getLayoutParams());
        addMe.setImageResource(empty ? R.drawable.ic_sysbar_lights_out_dot_large
                : R.drawable.ic_sysbar_lights_out_dot_small);
        addMe.setScaleType(ImageView.ScaleType.CENTER);
        addMe.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);

        if (landscape) {
            root.addView(addMe, 0);
        } else {
            root.addView(addMe);
        }
    }

    private void addButton(ViewGroup root, View addMe, boolean landscape) {
        if (landscape) {
            root.addView(addMe, 0);
        } else {
            root.addView(addMe);
        }
    }

    @Override
    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 && !backAlt) {
            mTransitionListener.onBackAltCleared();
        }
        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;

        if (getBackButton() != null ) {
            ((ImageView) getBackButton()).setImageDrawable(null);
            ((ImageView) getBackButton()).setImageDrawable(mVertical ? mBackLandIcon : mBackIcon);
        }

        final boolean showImeButton = ((hints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);
        if (getImeSwitchButton() != null) {
            getImeSwitchButton().setVisibility(showImeButton ? View.VISIBLE : View.GONE);
            mIsImeButtonVisible = showImeButton;
        }

        // Update menu button in case the IME state has changed.
        setMenuVisibility(mShowMenu, true);

        setDisabledFlags(mDisabledFlags, true);
    }

    @Override
    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean keyguardProbablyEnabled =
                (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0;


        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack);
        }

        final ViewGroup navButtons = getNavButtons();
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
            }
        }
        if (inLockTask() && disableRecent && !disableHome) {
            // Don't hide recents when in lock task, it is used for exiting.
            // Unless home is hidden, then in DPM locked mode and no exit available.
            disableRecent = false;
        }

        if (mButtonsConfig != null && !mButtonsConfig.isEmpty()) {
            for (int j = 0; j < mButtonsConfig.size(); j++) {
                View v = (View) findViewWithTag((mVertical ? "key_land_" : "key_") + j);
                if (v != null) {
                    int vid = v.getId();
                    if (vid == R.id.back) {
                        v.setVisibility(disableBack ? View.INVISIBLE : View.VISIBLE);
                    } else if (vid == R.id.recent_apps) {
                        v.setVisibility(disableRecent ? View.INVISIBLE : View.VISIBLE);
                    } else { // treat all other buttons as same rule as home
                        v.setVisibility(disableHome ? View.INVISIBLE : View.VISIBLE);
                    }
                }
            }
        }
    }

    private boolean inLockTask() {
        try {
            return ActivityManagerNative.getDefault().isInLockTaskMode();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTaskSwitchHelper.onTouchEvent(event)) {
            return true;
        }
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        return super.onTouchEvent(event);
    }

    protected void setUseFadingAnimations(boolean useFadingAnimations) {
        if (!isAttachedToWindow()) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean old = lp.windowAnimations != 0;
            if (!old && useFadingAnimations) {
                lp.windowAnimations = R.style.Animation_NavigationBarFadeIn;
            } else if (old && !useFadingAnimations) {
                lp.windowAnimations = 0;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    @Override
    public void setSlippery(boolean newSlippery) {
        if (!isAttachedToWindow()) return;
        super.setSlippery(newSlippery);
    }

    @Override
    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) {
            return;
        }

        View leftMenuKeyView = getLeftMenuButton();
        View rightMenuKeyView = getRightMenuButton();

        // Only show Menu if IME switcher not shown.
        final boolean shouldShow =
                ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0);
        boolean showLeftMenuButton = ((mMenuVisibility == MENU_VISIBILITY_ALWAYS || show)
                && (mMenuSetting == SHOW_LEFT_MENU || mMenuSetting == SHOW_BOTH_MENU)
                && (mMenuVisibility != MENU_VISIBILITY_NEVER))
                || mOverrideMenuKeys;
        boolean showRightMenuButton = ((mMenuVisibility == MENU_VISIBILITY_ALWAYS || show)
                && (mMenuSetting == SHOW_RIGHT_MENU || mMenuSetting == SHOW_BOTH_MENU)
                && (mMenuVisibility != MENU_VISIBILITY_NEVER)
                && shouldShow)
                || mOverrideMenuKeys;

        leftMenuKeyView.setVisibility(showLeftMenuButton ? View.VISIBLE : View.INVISIBLE);
        rightMenuKeyView.setVisibility(showRightMenuButton ? View.VISIBLE
                : (mIsImeButtonVisible ? View.GONE : View.INVISIBLE));
        mShowMenu = show;
    }

    @Override
    public void onFinishInflate() {
        mRot0 = (FrameLayout) findViewById(R.id.rot0);
        mRot90 = (FrameLayout) findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);
        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_270] = mRotatedViews[Surface.ROTATION_90];

        mCurrentView = mRotatedViews[Surface.ROTATION_0];
        updateSettings(true);

        if (getImeSwitchButton() != null)
            getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        updateRTLOrder();
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }

        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_CAN_MOVE,
                DeviceUtils.isPhone(mContext) ? 1 : 0, UserHandle.USER_CURRENT) != 1) {
            mCurrentView = mRotatedViews[Surface.ROTATION_0];
        } else {
            mCurrentView = mRotatedViews[rot];
        }
        mCurrentView.setVisibility(View.VISIBLE);
        updateLayoutTransitionsEnabled();

        if (getImeSwitchButton() != null)
            getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        updateTaskSwitchHelper();

        setNavigationIconHints(mNavigationIconHints, true);
    }

    private void updateTaskSwitchHelper() {
        boolean isRtl = (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        mTaskSwitchHelper.setBarState(mVertical, isRtl);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        List<View> views = new ArrayList<View>();
        final View back = getBackButton();
        final View home = getHomeButton();
        final View recent = getRecentsButton();
        if (back != null) {
            views.add(back);
        }
        if (home != null) {
            views.add(home);
        }
        if (recent != null) {
            views.add(recent);
        }
        for (int i = 0; i < mButtonIdList.size(); i++) {
            final View customButton = getCustomButton(mButtonIdList.get(i));
            if (customButton != null) {
                views.add(customButton);
            }
        }
    }

    @Override
    public void setLayoutTransitionsEnabled(boolean enabled) {
        mLayoutTransitionsEnabled = enabled;
        updateLayoutTransitionsEnabled();
    }

    @Override
    public void setWakeAndUnlocking(boolean wakeAndUnlocking) {
        setUseFadingAnimations(wakeAndUnlocking);
        mWakeAndUnlocking = wakeAndUnlocking;
        updateLayoutTransitionsEnabled();
    }

    private void updateLayoutTransitionsEnabled() {
        boolean enabled = !mWakeAndUnlocking && mLayoutTransitionsEnabled;
        ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
        LayoutTransition lt = navButtons.getLayoutTransition();
        if (lt != null) {
            if (enabled) {
                lt.enableTransitionType(LayoutTransition.APPEARING);
                lt.enableTransitionType(LayoutTransition.DISAPPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            } else {
                lt.disableTransitionType(LayoutTransition.APPEARING);
                lt.disableTransitionType(LayoutTransition.DISAPPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            }
        }
    }

    private void adjustExtraKeyGravity(View navBar, boolean isLayoutRtl) {
        View menu = navBar.findViewById(R.id.menu);
        View imeSwitcher = navBar.findViewById(R.id.ime_switcher);

        /**
         * AOSP navbar places these views inside a FrameLayout, but slim's implementation
         * adds them to the LinearLayout, causing a ClassCastException for the parameters.
         * So, we need to determine which ViewGroup class the LayoutParams belongs to before
         * casting it to a subclass (FrameLayout.LayoutParams or LinearLayout.LayoutParams)
         */
        if (menu != null) {
            if (menu.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) menu.getLayoutParams();
                lp.gravity = isLayoutRtl ? Gravity.BOTTOM : Gravity.TOP;
                menu.setLayoutParams(lp);
            } else {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) menu.getLayoutParams();
                lp.gravity = isLayoutRtl ? Gravity.BOTTOM : Gravity.TOP;
                menu.setLayoutParams(lp);
            }
        }
        if (imeSwitcher != null) {
            if (imeSwitcher.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) imeSwitcher.getLayoutParams();
                lp.gravity = isLayoutRtl ? Gravity.BOTTOM : Gravity.TOP;
                imeSwitcher.setLayoutParams(lp);
            } else {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) imeSwitcher.getLayoutParams();
                lp.gravity = isLayoutRtl ? Gravity.BOTTOM : Gravity.TOP;
                imeSwitcher.setLayoutParams(lp);
            }
        }
    }

    private void updateSettings(boolean recreate) {
        ContentResolver resolver = mContext.getContentResolver();

        mNavBarButtonColor = Settings.System.getIntForUser(resolver,
                Settings.System.NAVIGATION_BAR_BUTTON_TINT, -2, UserHandle.USER_CURRENT);

        if (mNavBarButtonColor == -2) {
            mNavBarButtonColor = mContext.getResources()
                    .getColor(R.color.navigationbar_button_default_color);
        }

        mNavBarButtonColorMode = Settings.System.getIntForUser(resolver,
                Settings.System.NAVIGATION_BAR_BUTTON_TINT_MODE, 0, UserHandle.USER_CURRENT);

        mButtonsConfig = ActionHelper.getNavBarConfig(mContext);

        mMenuSetting = Settings.System.getIntForUser(resolver,
                Settings.System.MENU_LOCATION, SHOW_RIGHT_MENU,
                UserHandle.USER_CURRENT);

        mMenuVisibility = Settings.System.getIntForUser(resolver,
                Settings.System.MENU_VISIBILITY, MENU_VISIBILITY_SYSTEM,
                UserHandle.USER_CURRENT);

        getIcons(getContext().getResources());

        // construct the navigationbar
        if (recreate) {
            makeBar();
        }

    }
}
