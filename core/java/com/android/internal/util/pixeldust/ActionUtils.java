package com.android.internal.util.pixeldust;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import android.view.HapticFeedbackConstants;
import android.widget.Toast;
import com.android.internal.R;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ActionUtils {
    private static final boolean DEBUG = false;
    private static final String TAG = ActionUtils.class.getSimpleName();
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * Kills the top most / most recent user application, but leaves out the launcher.
     * This is function governed by {@link Settings.Secure.KILL_APP_LONGPRESS_BACK}.
     *
     * @param context the current context, used to retrieve the package manager.
     * @param userId the ID of the currently active user
     * @return {@code true} when a user application was found and closed.
     */
    public static String killForegroundApp(Context context, int userId) {
        try {
            return killForegroundAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not kill foreground app");
        }
        return null;
    }

    private static String killForegroundAppInternal(Context context, int userId)
            throws RemoteException {
        try {
            PackageManager packageManager = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            String defaultHomePackage = "com.android.launcher";
            intent.addCategory(Intent.CATEGORY_HOME);
            final ResolveInfo res = packageManager.resolveActivity(intent, 0);

            if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
                defaultHomePackage = res.activityInfo.packageName;
            }

            // Use UsageStats to determine foreground app
            UsageStatsManager usageStatsManager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
            long current = System.currentTimeMillis();
            long past = current - (1000 * 60 * 60); // uses snapshot of usage over past 60 minutes

            // Get the list, then sort it chronilogically so most recent usage is at start of list
            List<UsageStats> recentApps = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, past, current);
            Collections.sort(recentApps, new Comparator<UsageStats>() {
                @Override
                public int compare(UsageStats lhs, UsageStats rhs) {
                    long timeLHS = lhs.getLastTimeUsed();
                    long timeRHS = rhs.getLastTimeUsed();
                    if (timeLHS > timeRHS) {
                        return -1;
                    } else if (timeLHS < timeRHS) {
                        return 1;
                     }
                    return 0;
                }
            });

            IActivityManager am = ActivityManagerNative.getDefault();
            if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return null;
            // Look for most recent usagestat with lastevent == 1 and grab package name
            // ...this seems to map to the UsageEvents.Event.MOVE_TO_FOREGROUND
            String pkg = null;
            for (int i = 0; i < recentApps.size(); i++) {
                UsageStats mostRecent = recentApps.get(i);
                if (mostRecent.mLastEvent == 1) {
                    pkg = mostRecent.mPackageName;
                    break;
                 }
             }

            if (pkg != null && !pkg.equals("com.android.systemui")
                    && !pkg.equals(defaultHomePackage)) {
                am.forceStopPackage(pkg, UserHandle.USER_CURRENT);
                try {
                    String appName = (String) packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA));
                    return appName;
                } catch (PackageManager.NameNotFoundException e) {
                    // Just use pkg if issues getting appName
                    return pkg;
                }
            } else {
                return null;
            }
        } catch (RemoteException remoteException) {
            // Do nothing; just let it go.
        }
        return null;
    }

    /**
     * Attempt to bring up the last activity in the stack before the current active one.
     *
     * @param context
     * @return whether an activity was found to switch to
     */
    public static boolean switchToLastApp(Context context, int userId) {
        try {
            return switchToLastAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not switch to last app");
        }
        return false;
    }

    private static boolean switchToLastAppInternal(Context context, int userId)
            throws RemoteException {
        ActivityManager.RecentTaskInfo lastTask = getLastTask(context, userId);

        if (lastTask == null) {
            return false;
        }

        final Intent lastAppIntent = lastTask.baseIntent;
        final String packageName = lastAppIntent.getComponent().getPackageName();
        final IActivityManager am = ActivityManagerNative.getDefault();
        final ActivityOptions opts = ActivityOptions.makeCustomAnimation(context,
                com.android.internal.R.anim.last_app_in,
                com.android.internal.R.anim.last_app_out);

        if (DEBUG) Log.d(TAG, "switching to " + packageName);
        if (lastTask.id > 0) {
            am.moveTaskToFront(lastTask.id, ActivityManager.MOVE_TASK_NO_USER_ACTION, opts.toBundle());
        } else if (lastAppIntent != null) {
            // last task is dead, restart it.
            lastAppIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            try {
                context.startActivityAsUser(lastAppIntent, opts.toBundle(), UserHandle.CURRENT);
            } catch (ActivityNotFoundException e) {
                Log.w("Recent", "Unable to launch recent task", e);
            }
        }

        return true;
    }

    private static ActivityManager.RecentTaskInfo getLastTask(Context context, int userId)
            throws RemoteException {
        final String defaultHomePackage = resolveCurrentLauncherPackage(context, userId);
        final IActivityManager am = ActivityManagerNative.getDefault();
        final List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(5,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId).getList();

        for (int i = 1; i < tasks.size(); i++) {
            ActivityManager.RecentTaskInfo task = tasks.get(i);
            if (task.origActivity != null) {
                task.baseIntent.setComponent(task.origActivity);
            }
            String packageName = task.baseIntent.getComponent().getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals(SYSTEMUI_PACKAGE)) {
                return tasks.get(i);
            }
        }

        return null;
    }

    private static String resolveCurrentLauncherPackage(Context context, int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivityAsUser(launcherIntent, 0, userId);
        return launcherInfo.activityInfo.packageName;
    }
}
