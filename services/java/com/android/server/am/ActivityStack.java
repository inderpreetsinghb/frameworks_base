/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.util.Objects;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.wm.AppTransition;
import com.android.server.wm.TaskGroup;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IThumbnailReceiver;
import android.app.IThumbnailRetriever;
import android.app.IApplicationThread;
import android.app.ResultInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityManager.WaitResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;
import android.view.Display;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * State and management of a single stack of activities.
 */
final class ActivityStack {
    static final String TAG = ActivityManagerService.TAG;
    static final boolean localLOGV = ActivityManagerService.localLOGV;
    static final boolean DEBUG_SWITCH = ActivityManagerService.DEBUG_SWITCH;
    static final boolean DEBUG_PAUSE = ActivityManagerService.DEBUG_PAUSE;
    static final boolean DEBUG_VISBILITY = ActivityManagerService.DEBUG_VISBILITY;
    static final boolean DEBUG_USER_LEAVING = ActivityManagerService.DEBUG_USER_LEAVING;
    static final boolean DEBUG_TRANSITION = ActivityManagerService.DEBUG_TRANSITION;
    static final boolean DEBUG_RESULTS = ActivityManagerService.DEBUG_RESULTS;
    static final boolean DEBUG_CONFIGURATION = ActivityManagerService.DEBUG_CONFIGURATION;
    static final boolean DEBUG_TASKS = ActivityManagerService.DEBUG_TASKS;
    static final boolean DEBUG_CLEANUP = ActivityManagerService.DEBUG_CLEANUP;
    static final boolean DEBUG_STACK = ActivityManagerService.DEBUG_STACK;

    static final boolean DEBUG_STATES = ActivityStackSupervisor.DEBUG_STATES;
    static final boolean DEBUG_ADD_REMOVE = ActivityStackSupervisor.DEBUG_ADD_REMOVE;
    static final boolean DEBUG_SAVED_STATE = ActivityStackSupervisor.DEBUG_SAVED_STATE;
    static final boolean DEBUG_APP = ActivityStackSupervisor.DEBUG_APP;

    static final boolean VALIDATE_TOKENS = ActivityManagerService.VALIDATE_TOKENS;

    // How long we wait until giving up on the last activity telling us it
    // is idle.
    static final int IDLE_TIMEOUT = 10*1000;

    // Ticks during which we check progress while waiting for an app to launch.
    static final int LAUNCH_TICK = 500;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    static final int PAUSE_TIMEOUT = 500;

    // How long we wait for the activity to tell us it has stopped before
    // giving up.  This is a good amount of time because we really need this
    // from the application in order to get its saved state.
    static final int STOP_TIMEOUT = 10*1000;

    // How long we can hold the sleep wake lock before giving up.
    static final int SLEEP_TIMEOUT = 5*1000;

    // How long we can hold the launch wake lock before giving up.
    static final int LAUNCH_TIMEOUT = 10*1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    static final int DESTROY_TIMEOUT = 10*1000;

    // How long until we reset a task when the user returns to it.  Currently
    // disabled.
    static final long ACTIVITY_INACTIVE_RESET_TIME = 0;

    // How long between activity launches that we consider safe to not warn
    // the user about an unexpected activity being launched on top.
    static final long START_WARN_TIME = 5*1000;

    // Set to false to disable the preview that is shown while a new activity
    // is being started.
    static final boolean SHOW_APP_STARTING_PREVIEW = true;

    enum ActivityState {
        INITIALIZING,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED
    }

    final ActivityManagerService mService;

    final Context mContext;

    /**
     * The back history of all previous (and possibly still
     * running) activities.  It contains #TaskRecord objects.
     */
    private ArrayList<TaskRecord> mTaskHistory = new ArrayList<TaskRecord>();

    /**
     * Used for validating app tokens with window manager.
     */
    final ArrayList<TaskGroup> mValidateAppTokens = new ArrayList<TaskGroup>();

    /**
     * List of running activities, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains HistoryRecord objects.
     */
    final ArrayList<ActivityRecord> mLRUActivities = new ArrayList<ActivityRecord>();

    /**
     * List of activities that are in the process of going to sleep.
     */
    final ArrayList<ActivityRecord> mGoingToSleepActivities
            = new ArrayList<ActivityRecord>();

    /**
     * Animations that for the current transition have requested not to
     * be considered for the transition animation.
     */
    final ArrayList<ActivityRecord> mNoAnimActivities
            = new ArrayList<ActivityRecord>();

    /**
     * List of activities that are ready to be finished, but waiting
     * for the previous activity to settle down before doing so.  It contains
     * HistoryRecord objects.
     */
    final ArrayList<ActivityRecord> mFinishingActivities
            = new ArrayList<ActivityRecord>();

    /**
     * List of people waiting to find out about the next launched activity.
     */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityLaunched
            = new ArrayList<IActivityManager.WaitResult>();

    /**
     * List of people waiting to find out about the next visible activity.
     */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityVisible
            = new ArrayList<IActivityManager.WaitResult>();

    final ArrayList<UserStartedState> mStartingUsers
            = new ArrayList<UserStartedState>();

    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    final PowerManager.WakeLock mGoingToSleep;

    /**
     * We don't want to allow the device to go to sleep while in the process
     * of launching an activity.  This is primarily to allow alarm intent
     * receivers to launch an activity and get that to run before the device
     * goes back to sleep.
     */
    final PowerManager.WakeLock mLaunchingActivity;

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     */
    ActivityRecord mPausingActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    ActivityRecord mLastPausedActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     */
    ActivityRecord mResumedActivity = null;

    /**
     * This is the last activity that has been started.  It is only used to
     * identify when multiple activities are started at once so that the user
     * can be warned they may not be in the activity they think they are.
     */
    ActivityRecord mLastStartedActivity = null;

    /**
     * Set when we know we are going to be calling updateConfiguration()
     * soon, so want to skip intermediate config checks.
     */
    boolean mConfigWillChange;

    long mInitialStartTime = 0;

    /**
     * Set when we have taken too long waiting to go to sleep.
     */
    boolean mSleepTimeout = false;

    /**
     * Save the most recent screenshot for reuse. This keeps Recents from taking two identical
     * screenshots, one for the Recents thumbnail and one for the pauseActivity thumbnail.
     */
    private ActivityRecord mLastScreenshotActivity = null;
    private Bitmap mLastScreenshotBitmap = null;

    /**
     * List of ActivityRecord objects that have been finished and must
     * still report back to a pending thumbnail receiver.
     */
    private final ArrayList<ActivityRecord> mCancelledThumbnails = new ArrayList<ActivityRecord>();

    int mThumbnailWidth = -1;
    int mThumbnailHeight = -1;

    private int mCurrentUser;

    final int mStackId;

    /** Run all ActivityStacks through this */
    final ActivityStackSupervisor mStackSupervisor;

    static final int SLEEP_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG;
    static final int PAUSE_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 1;
    static final int IDLE_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 2;
    static final int IDLE_NOW_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 3;
    static final int LAUNCH_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 4;
    static final int DESTROY_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 5;
    static final int RESUME_TOP_ACTIVITY_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 6;
    static final int LAUNCH_TICK_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 7;
    static final int STOP_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 8;
    static final int DESTROY_ACTIVITIES_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 9;

    static class ScheduleDestroyArgs {
        final ProcessRecord mOwner;
        final boolean mOomAdj;
        final String mReason;
        ScheduleDestroyArgs(ProcessRecord owner, boolean oomAdj, String reason) {
            mOwner = owner;
            mOomAdj = oomAdj;
            mReason = reason;
        }
    }

    final Handler mHandler;

    final class ActivityStackHandler extends Handler {
        //public Handler() {
        //    if (localLOGV) Slog.v(TAG, "Handler started!");
        //}
        public ActivityStackHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SLEEP_TIMEOUT_MSG: {
                    synchronized (mService) {
                        if (mService.isSleepingOrShuttingDown()) {
                            Slog.w(TAG, "Sleep timeout!  Sleeping now.");
                            mSleepTimeout = true;
                            checkReadyForSleepLocked();
                        }
                    }
                } break;
                case PAUSE_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity pause timeout for " + r);
                    synchronized (mService) {
                        if (r.app != null) {
                            mService.logAppTooSlow(r.app, r.pauseTime,
                                    "pausing " + r);
                        }

                        activityPausedLocked(r != null ? r.appToken : null, true);
                    }
                } break;
                case IDLE_TIMEOUT_MSG: {
                    if (mService.mDidDexOpt) {
                        mService.mDidDexOpt = false;
                        Message nmsg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG);
                        nmsg.obj = msg.obj;
                        mHandler.sendMessageDelayed(nmsg, IDLE_TIMEOUT);
                        return;
                    }
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    Slog.w(TAG, "Activity idle timeout for " + r);
                    synchronized (mService) {
                        activityIdleInternalLocked(r != null ? r.appToken : null, true, null);
                    }
                } break;
                case LAUNCH_TICK_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    synchronized (mService) {
                        if (r.continueLaunchTickingLocked()) {
                            mService.logAppTooSlow(r.app, r.launchTickTime,
                                    "launching " + r);
                        }
                    }
                } break;
                case DESTROY_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity destroy timeout for " + r);
                    synchronized (mService) {
                        activityDestroyedLocked(r != null ? r.appToken : null);
                    }
                } break;
                case IDLE_NOW_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    synchronized (mService) {
                        activityIdleInternalLocked(r != null ? r.appToken : null, false, null);
                    }
                } break;
                case LAUNCH_TIMEOUT_MSG: {
                    if (mService.mDidDexOpt) {
                        mService.mDidDexOpt = false;
                        Message nmsg = mHandler.obtainMessage(LAUNCH_TIMEOUT_MSG);
                        mHandler.sendMessageDelayed(nmsg, LAUNCH_TIMEOUT);
                        return;
                    }
                    synchronized (mService) {
                        if (mLaunchingActivity.isHeld()) {
                            Slog.w(TAG, "Launch timeout has expired, giving up wake lock!");
                            mLaunchingActivity.release();
                        }
                    }
                } break;
                case RESUME_TOP_ACTIVITY_MSG: {
                    synchronized (mService) {
                        mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
                    }
                } break;
                case STOP_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity stop timeout for " + r);
                    synchronized (mService) {
                        if (r.isInHistory()) {
                            activityStoppedLocked(r, null, null, null);
                        }
                    }
                } break;
                case DESTROY_ACTIVITIES_MSG: {
                    ScheduleDestroyArgs args = (ScheduleDestroyArgs)msg.obj;
                    synchronized (mService) {
                        destroyActivitiesLocked(args.mOwner, args.mOomAdj, args.mReason);
                    }
                }
            }
        }
    }

    private int numActivities() {
        int count = 0;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            count += mTaskHistory.get(taskNdx).mActivities.size();
        }
        return count;
    }

    ActivityStack(ActivityManagerService service, Context context, Looper looper, int stackId,
            ActivityStackSupervisor supervisor, int userId) {
        mHandler = new ActivityStackHandler(looper);
        mService = service;
        mContext = context;
        PowerManager pm =
            (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mGoingToSleep = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        mLaunchingActivity = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Launch");
        mLaunchingActivity.setReferenceCounted(false);
        mStackId = stackId;
        mStackSupervisor = supervisor;
        mCurrentUser = userId;
    }

    private boolean okToShow(ActivityRecord r) {
        return r.userId == mCurrentUser
                || (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0;
    }

    final ActivityRecord topRunningActivityLocked(ActivityRecord notTop) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && r != notTop && okToShow(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    final ActivityRecord topRunningNonDelayedActivityLocked(ActivityRecord notTop) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && !r.delayedResume && r != notTop && okToShow(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * This is a simplified version of topRunningActivityLocked that provides a number of
     * optional skip-over modes.  It is intended for use with the ActivityController hook only.
     *
     * @param token If non-null, any history records matching this token will be skipped.
     * @param taskId If non-zero, we'll attempt to skip over records with the same task ID.
     *
     * @return Returns the HistoryRecord of the next activity on the stack.
     */
    final ActivityRecord topRunningActivityLocked(IBinder token, int taskId) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.taskId == taskId) {
                continue;
            }
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int i = activities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = activities.get(i);
                // Note: the taskId check depends on real taskId fields being non-zero
                if (!r.finishing && (token != r.appToken) && okToShow(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    final ActivityRecord topActivity() {
        // Iterate to find the first non-empty task stack. Note that this code can
        // be simplified once we stop storing tasks with empty mActivities lists.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                return activities.get(activityNdx);
            }
        }
        return null;
    }

    TaskRecord taskForIdLocked(int id) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.taskId == id) {
                return task;
            }
        }
        return null;
    }

    ActivityRecord isInStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forToken(token);
        if (r != null) {
            final TaskRecord task = r.task;
            if (task.mActivities.contains(r) && mTaskHistory.contains(task)) {
                if (task.stack != this) Slog.w(TAG,
                    "Illegal state! task does not point to stack it is in.");
                return r;
            }
        }
        return null;
    }

    final boolean updateLRUListLocked(ActivityRecord r) {
        final boolean hadit = mLRUActivities.remove(r);
        mLRUActivities.add(r);
        return hadit;
    }

    final boolean isHomeStack() {
        return mStackId == HOME_STACK_ID;
    }

    /**
     * Returns the top activity in any existing task matching the given
     * Intent.  Returns null if no such task is found.
     */
    ActivityRecord findTaskLocked(Intent intent, ActivityInfo info) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        final int userId = UserHandle.getUserId(info.applicationInfo.uid);

        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ActivityRecord r = task.getTopActivity();
            if (r == null || r.finishing || r.userId != userId ||
                    r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                continue;
            }

            //Slog.i(TAG, "Comparing existing cls=" + r.task.intent.getComponent().flattenToShortString()
            //        + "/aff=" + r.task.affinity + " to new cls="
            //        + intent.getComponent().flattenToShortString() + "/aff=" + taskAffinity);
            if (task.affinity != null) {
                if (task.affinity.equals(info.taskAffinity)) {
                    //Slog.i(TAG, "Found matching affinity!");
                    return r;
                }
            } else if (task.intent != null && task.intent.getComponent().equals(cls)) {
                //Slog.i(TAG, "Found matching class!");
                //dump();
                //Slog.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                return r;
            } else if (task.affinityIntent != null
                    && task.affinityIntent.getComponent().equals(cls)) {
                //Slog.i(TAG, "Found matching class!");
                //dump();
                //Slog.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                return r;
            }
        }

        return null;
    }

    /**
     * Returns the first activity (starting from the top of the stack) that
     * is the same as the given activity.  Returns null if no such activity
     * is found.
     */
    ActivityRecord findActivityLocked(Intent intent, ActivityInfo info) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        final int userId = UserHandle.getUserId(info.applicationInfo.uid);

        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && r.intent.getComponent().equals(cls) && r.userId == userId) {
                    //Slog.i(TAG, "Found matching class!");
                    //dump();
                    //Slog.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                }
            }
        }

        return null;
    }

    /*
     * Move the activities around in the stack to bring a user to the foreground.
     * @return whether there are any activities for the specified user.
     */
    final boolean switchUserLocked(int userId, UserStartedState uss) {
        if (VALIDATE_TOKENS) {
            validateAppTokensLocked();
        }
        mStartingUsers.add(uss);
        if (mCurrentUser == userId) {
            return true;
        }
        mCurrentUser = userId;

        // Move userId's tasks to the top.
        boolean haveActivities = false;
        TaskRecord task = null;
        int index = mTaskHistory.size();
        for (int i = 0; i < index; ++i) {
            task = mTaskHistory.get(i);
            if (task.userId == userId) {
                haveActivities = true;
                mTaskHistory.remove(i);
                mTaskHistory.add(task);
                --index;
            }
        }

        // task is now the original topmost TaskRecord. Transition from the old top to the new top.
        ActivityRecord top = task != null ? task.getTopActivity() : null;
        resumeTopActivityLocked(top);
        return haveActivities;
    }

    void minimalResumeActivityLocked(ActivityRecord r) {
        r.state = ActivityState.RESUMED;
        if (DEBUG_STATES) Slog.v(TAG, "Moving to RESUMED: " + r
                + " (starting new instance)");
        r.stopped = false;
        mResumedActivity = r;
        r.task.touchActiveTime();
        mService.addRecentTaskLocked(r.task);
        completeResumeLocked(r);
        checkReadyForSleepLocked();
        if (DEBUG_SAVED_STATE) Slog.i(TAG, "Launch completed; removing icicle of " + r.icicle);
    }

    void setLaunchTime(ActivityRecord r) {
        if (r.launchTime == 0) {
            r.launchTime = SystemClock.uptimeMillis();
            if (mInitialStartTime == 0) {
                mInitialStartTime = r.launchTime;
            }
        } else if (mInitialStartTime == 0) {
            mInitialStartTime = SystemClock.uptimeMillis();
        }
    }

    void stopIfSleepingLocked() {
        if (mService.isSleepingOrShuttingDown()) {
            if (!mGoingToSleep.isHeld()) {
                mGoingToSleep.acquire();
                if (mLaunchingActivity.isHeld()) {
                    mLaunchingActivity.release();
                    mService.mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                }
            }
            mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
            Message msg = mHandler.obtainMessage(SLEEP_TIMEOUT_MSG);
            mHandler.sendMessageDelayed(msg, SLEEP_TIMEOUT);
            checkReadyForSleepLocked();
        }
    }

    void awakeFromSleepingLocked() {
        mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
        mSleepTimeout = false;
        if (mGoingToSleep.isHeld()) {
            mGoingToSleep.release();
        }
        // Ensure activities are no longer sleeping.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                activities.get(activityNdx).setSleeping(false);
            }
        }
        mGoingToSleepActivities.clear();
    }

    void activitySleptLocked(ActivityRecord r) {
        mGoingToSleepActivities.remove(r);
        checkReadyForSleepLocked();
    }

    // Checked.
    void checkReadyForSleepLocked() {
        if (!mService.isSleepingOrShuttingDown()) {
            // Do not care.
            return;
        }

        if (!mSleepTimeout) {
            if (mResumedActivity != null) {
                // Still have something resumed; can't sleep until it is paused.
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep needs to pause " + mResumedActivity);
                if (DEBUG_USER_LEAVING) Slog.v(TAG, "Sleep => pause with userLeaving=false");
                startPausingLocked(false, true);
                return;
            }
            if (mPausingActivity != null) {
                // Still waiting for something to pause; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep still waiting to pause " + mPausingActivity);
                return;
            }

            if (mStackSupervisor.mStoppingActivities.size() > 0) {
                // Still need to tell some activities to stop; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep still need to stop "
                        + mStackSupervisor.mStoppingActivities.size() + " activities");
                scheduleIdleLocked();
                return;
            }

            ensureActivitiesVisibleLocked(null, 0);

            // Make sure any stopped but visible activities are now sleeping.
            // This ensures that the activity's onStop() is called.
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
                for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                    final ActivityRecord r = activities.get(activityNdx);
                    if (r.state == ActivityState.STOPPING || r.state == ActivityState.STOPPED) {
                        r.setSleeping(true);
                    }
                }
            }

            if (mGoingToSleepActivities.size() > 0) {
                // Still need to tell some activities to sleep; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep still need to sleep "
                        + mGoingToSleepActivities.size() + " activities");
                return;
            }
        }

        mHandler.removeMessages(SLEEP_TIMEOUT_MSG);

        if (mGoingToSleep.isHeld()) {
            mGoingToSleep.release();
        }
        if (mService.mShuttingDown) {
            mService.notifyAll();
        }
    }

    public final Bitmap screenshotActivities(ActivityRecord who) {
        if (who.noDisplay) {
            return null;
        }

        Resources res = mService.mContext.getResources();
        int w = mThumbnailWidth;
        int h = mThumbnailHeight;
        if (w < 0) {
            mThumbnailWidth = w =
                res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_width);
            mThumbnailHeight = h =
                res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_height);
        }

        if (w > 0) {
            if (who != mLastScreenshotActivity || mLastScreenshotBitmap == null
                    || mLastScreenshotBitmap.getWidth() != w
                    || mLastScreenshotBitmap.getHeight() != h) {
                mLastScreenshotActivity = who;
                mLastScreenshotBitmap = mService.mWindowManager.screenshotApplications(
                        who.appToken, Display.DEFAULT_DISPLAY, w, h);
            }
            if (mLastScreenshotBitmap != null) {
                return mLastScreenshotBitmap.copy(Config.ARGB_8888, true);
            }
        }
        return null;
    }

    private final void startPausingLocked(boolean userLeaving, boolean uiSleeping) {
        if (mPausingActivity != null) {
            Slog.e(TAG, "Trying to pause when pause is already pending for "
                  + mPausingActivity, new RuntimeException("here").fillInStackTrace());
        }
        ActivityRecord prev = mResumedActivity;
        if (prev == null) {
            Slog.e(TAG, "Trying to pause when nothing is resumed",
                    new RuntimeException("here").fillInStackTrace());
            mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
            return;
        }
        if (DEBUG_STATES) Slog.v(TAG, "Moving to PAUSING: " + prev);
        else if (DEBUG_PAUSE) Slog.v(TAG, "Start pausing: " + prev);
        mResumedActivity = null;
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        prev.state = ActivityState.PAUSING;
        prev.task.touchActiveTime();
        prev.updateThumbnail(screenshotActivities(prev), null);

        mService.updateCpuStats();

        if (prev.app != null && prev.app.thread != null) {
            if (DEBUG_PAUSE) Slog.v(TAG, "Enqueueing pending pause: " + prev);
            try {
                EventLog.writeEvent(EventLogTags.AM_PAUSE_ACTIVITY,
                        prev.userId, System.identityHashCode(prev),
                        prev.shortComponentName);
                prev.app.thread.schedulePauseActivity(prev.appToken, prev.finishing,
                        userLeaving, prev.configChangeFlags);
                if (mStackSupervisor.isFrontStack(this)) {
                    mService.updateUsageStats(prev, false);
                }
            } catch (Exception e) {
                // Ignore exception, if process died other code will cleanup.
                Slog.w(TAG, "Exception thrown during pause", e);
                mPausingActivity = null;
                mLastPausedActivity = null;
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!mService.isSleepingOrShuttingDown()) {
            mLaunchingActivity.acquire();
            if (!mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
                // To be safe, don't allow the wake lock to be held for too long.
                Message msg = mHandler.obtainMessage(LAUNCH_TIMEOUT_MSG);
                mHandler.sendMessageDelayed(msg, LAUNCH_TIMEOUT);
            }
        }

        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG, "Key dispatch not paused for screen off");
            }

            // Schedule a pause timeout in case the app doesn't respond.
            // We don't give it much time because this directly impacts the
            // responsiveness seen by the user.
            Message msg = mHandler.obtainMessage(PAUSE_TIMEOUT_MSG);
            msg.obj = prev;
            prev.pauseTime = SystemClock.uptimeMillis();
            mHandler.sendMessageDelayed(msg, PAUSE_TIMEOUT);
            if (DEBUG_PAUSE) Slog.v(TAG, "Waiting for pause to complete...");
        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Slog.v(TAG, "Activity not running, resuming next.");
            mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        }
    }

    final void activityResumedLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forToken(token);
        if (DEBUG_SAVED_STATE) Slog.i(TAG, "Resumed activity; dropping state of: " + r);
        r.icicle = null;
        r.haveState = false;
    }

    final void activityPausedLocked(IBinder token, boolean timeout) {
        if (DEBUG_PAUSE) Slog.v(
            TAG, "Activity paused: token=" + token + ", timeout=" + timeout);

        final ActivityRecord r = isInStackLocked(token);
        if (r != null) {
            mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
            if (mPausingActivity == r) {
                if (DEBUG_STATES) Slog.v(TAG, "Moving to PAUSED: " + r
                        + (timeout ? " (due to timeout)" : " (pause complete)"));
                r.state = ActivityState.PAUSED;
                completePauseLocked();
            } else {
                EventLog.writeEvent(EventLogTags.AM_FAILED_TO_PAUSE,
                        r.userId, System.identityHashCode(r), r.shortComponentName,
                        mPausingActivity != null
                            ? mPausingActivity.shortComponentName : "(none)");
            }
        }
    }

    final void activityStoppedLocked(ActivityRecord r, Bundle icicle, Bitmap thumbnail,
            CharSequence description) {
        if (r.state != ActivityState.STOPPING) {
            Slog.i(TAG, "Activity reported stop, but no longer stopping: " + r);
            mHandler.removeMessages(STOP_TIMEOUT_MSG, r);
            return;
        }
        if (DEBUG_SAVED_STATE) Slog.i(TAG, "Saving icicle of " + r + ": " + icicle);
        if (icicle != null) {
            // If icicle is null, this is happening due to a timeout, so we
            // haven't really saved the state.
            r.icicle = icicle;
            r.haveState = true;
            r.launchCount = 0;
            r.updateThumbnail(thumbnail, description);
        }
        if (!r.stopped) {
            if (DEBUG_STATES) Slog.v(TAG, "Moving to STOPPED: " + r + " (stop complete)");
            mHandler.removeMessages(STOP_TIMEOUT_MSG, r);
            r.stopped = true;
            r.state = ActivityState.STOPPED;
            if (r.finishing) {
                r.clearOptionsLocked();
            } else {
                if (r.configDestroy) {
                    destroyActivityLocked(r, true, false, "stop-config");
                    mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
                } else {
                    // Now that this process has stopped, we may want to consider
                    // it to be the previous app to try to keep around in case
                    // the user wants to return to it.
                    ProcessRecord fgApp = null;
                    if (mResumedActivity != null) {
                        fgApp = mResumedActivity.app;
                    } else if (mPausingActivity != null) {
                        fgApp = mPausingActivity.app;
                    }
                    if (r.app != null && fgApp != null && r.app != fgApp
                            && r.lastVisibleTime > mService.mPreviousProcessVisibleTime
                            && r.app != mService.mHomeProcess) {
                        mService.mPreviousProcess = r.app;
                        mService.mPreviousProcessVisibleTime = r.lastVisibleTime;
                    }
                }
            }
        }
    }

    private final void completePauseLocked() {
        ActivityRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Slog.v(TAG, "Complete pause: " + prev);

        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Executing finish of activity: " + prev);
                prev = finishCurrentActivityLocked(prev, FINISH_AFTER_VISIBLE, false);
            } else if (prev.app != null) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Enqueueing pending stop: " + prev);
                if (prev.waitingVisible) {
                    prev.waitingVisible = false;
                    mStackSupervisor.mWaitingVisibleActivities.remove(prev);
                    if (DEBUG_SWITCH || DEBUG_PAUSE) Slog.v(
                            TAG, "Complete pause, no longer waiting: " + prev);
                }
                if (prev.configDestroy) {
                    // The previous is being paused because the configuration
                    // is changing, which means it is actually stopping...
                    // To juggle the fact that we are also starting a new
                    // instance right now, we need to first completely stop
                    // the current instance before starting the new one.
                    if (DEBUG_PAUSE) Slog.v(TAG, "Destroying after pause: " + prev);
                    destroyActivityLocked(prev, true, false, "pause-config");
                } else {
                    mStackSupervisor.mStoppingActivities.add(prev);
                    if (mStackSupervisor.mStoppingActivities.size() > 3 ||
                            prev.frontOfTask && mTaskHistory.size() <= 1) {
                        // If we already have a few activities waiting to stop,
                        // then give up on things going idle and start clearing
                        // them out. Or if r is the last of activity of the last task the stack
                        // will be empty and must be cleared immediately.
                        if (DEBUG_PAUSE) Slog.v(TAG, "To many pending stops, forcing idle");
                        scheduleIdleLocked();
                    } else {
                        checkReadyForSleepLocked();
                    }
                }
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG, "App died during pause, not stopping: " + prev);
                prev = null;
            }
            mPausingActivity = null;
        }

        final ActivityStack topStack = mStackSupervisor.getTopStack();
        if (!mService.isSleepingOrShuttingDown()) {
            topStack.resumeTopActivityLocked(prev);
        } else {
            checkReadyForSleepLocked();
            ActivityRecord top = topStack.topRunningActivityLocked(null);
            if (top == null || (prev != null && top != prev)) {
                // If there are no more activities available to run,
                // do resume anyway to start something.  Also if the top
                // activity on the stack is not the just paused activity,
                // we need to go ahead and resume it to ensure we complete
                // an in-flight app switch.
                topStack.resumeTopActivityLocked(null);
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
        }

        if (prev.app != null && prev.cpuTimeAtResume > 0
                && mService.mBatteryStatsService.isOnBattery()) {
            long diff = 0;
            synchronized (mService.mProcessStatsThread) {
                diff = mService.mProcessStats.getCpuTimeForPid(prev.app.pid)
                        - prev.cpuTimeAtResume;
            }
            if (diff > 0) {
                BatteryStatsImpl bsi = mService.mBatteryStatsService.getActiveStatistics();
                synchronized (bsi) {
                    BatteryStatsImpl.Uid.Proc ps =
                            bsi.getProcessStatsLocked(prev.info.applicationInfo.uid,
                            prev.info.packageName);
                    if (ps != null) {
                        ps.addForegroundTimeLocked(diff);
                    }
                }
            }
        }
        prev.cpuTimeAtResume = 0; // reset it
    }

    // Checked.
    /**
     * Once we know that we have asked an application to put an activity in
     * the resumed state (either by launching it or explicitly telling it),
     * this function updates the rest of our state to match that fact.
     */
    private final void completeResumeLocked(ActivityRecord next) {
        next.idle = false;
        next.results = null;
        next.newIntents = null;

        // schedule an idle timeout in case the app doesn't do it for us.
        Message msg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG);
        msg.obj = next;
        mHandler.sendMessageDelayed(msg, IDLE_TIMEOUT);

        if (false) {
            // The activity was never told to pause, so just keep
            // things going as-is.  To maintain our own state,
            // we need to emulate it coming back and saying it is
            // idle.
            msg = mHandler.obtainMessage(IDLE_NOW_MSG);
            msg.obj = next;
            mHandler.sendMessage(msg);
        }

        if (mStackSupervisor.isFrontStack(this)) {
            // TODO: Should this be done for all stacks, not just mMainStack?
            mService.reportResumedActivityLocked(next);
            mService.setFocusedActivityLocked(next);
        }
        if (mStackSupervisor.allResumedActivitiesComplete()) {
            next.resumeKeyDispatchingLocked();
            mStackSupervisor.ensureActivitiesVisibleLocked(null, 0);
            mService.mWindowManager.executeAppTransition();
            mNoAnimActivities.clear();
        }

        // Mark the point when the activity is resuming
        // TODO: To be more accurate, the mark should be before the onCreate,
        //       not after the onResume. But for subsequent starts, onResume is fine.
        if (next.app != null) {
            synchronized (mService.mProcessStatsThread) {
                next.cpuTimeAtResume = mService.mProcessStats.getCpuTimeForPid(next.app.pid);
            }
        } else {
            next.cpuTimeAtResume = 0; // Couldn't get the cpu time of process
        }
    }

    // Checked.
    /**
     * Make sure that all activities that need to be visible (that is, they
     * currently can be seen by the user) actually are.
     */
    final void ensureActivitiesVisibleLocked(ActivityRecord top,
            ActivityRecord starting, String onlyThisProcess, int configChanges) {
        if (DEBUG_VISBILITY) Slog.v(
                TAG, "ensureActivitiesVisible behind " + top
                + " configChanges=0x" + Integer.toHexString(configChanges));

        // If the top activity is not fullscreen, then we need to
        // make sure any activities under it are now visible.
        boolean aboveTop = true;
        boolean behindFullscreen = !mStackSupervisor.isFrontStack(this);
        int taskNdx;
        for (taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.finishing) {
                    continue;
                }
                if (aboveTop && r != top) {
                    continue;
                }
                aboveTop = false;
                if (!behindFullscreen) {
                    if (DEBUG_VISBILITY) Slog.v(
                            TAG, "Make visible? " + r + " finishing=" + r.finishing
                            + " state=" + r.state);

                    final boolean doThisProcess = onlyThisProcess == null
                            || onlyThisProcess.equals(r.processName);

                    // First: if this is not the current activity being started, make
                    // sure it matches the current configuration.
                    if (r != starting && doThisProcess) {
                        ensureActivityConfigurationLocked(r, 0);
                    }

                    if (r.app == null || r.app.thread == null) {
                        if (onlyThisProcess == null || onlyThisProcess.equals(r.processName)) {
                            // This activity needs to be visible, but isn't even
                            // running...  get it started, but don't resume it
                            // at this point.
                            if (DEBUG_VISBILITY) Slog.v(TAG, "Start and freeze screen for " + r);
                            if (r != starting) {
                                r.startFreezingScreenLocked(r.app, configChanges);
                            }
                            if (!r.visible) {
                                if (DEBUG_VISBILITY) Slog.v(
                                        TAG, "Starting and making visible: " + r);
                                mService.mWindowManager.setAppVisibility(r.appToken, true);
                            }
                            if (r != starting) {
                                mStackSupervisor.startSpecificActivityLocked(r, false, false);
                            }
                        }

                    } else if (r.visible) {
                        // If this activity is already visible, then there is nothing
                        // else to do here.
                        if (DEBUG_VISBILITY) Slog.v(TAG, "Skipping: already visible at " + r);
                        r.stopFreezingScreenLocked(false);

                    } else if (onlyThisProcess == null) {
                        // This activity is not currently visible, but is running.
                        // Tell it to become visible.
                        r.visible = true;
                        if (r.state != ActivityState.RESUMED && r != starting) {
                            // If this activity is paused, tell it
                            // to now show its window.
                            if (DEBUG_VISBILITY) Slog.v(
                                    TAG, "Making visible and scheduling visibility: " + r);
                            try {
                                mService.mWindowManager.setAppVisibility(r.appToken, true);
                                r.sleeping = false;
                                r.app.pendingUiClean = true;
                                r.app.thread.scheduleWindowVisibility(r.appToken, true);
                                r.stopFreezingScreenLocked(false);
                            } catch (Exception e) {
                                // Just skip on any failure; we'll make it
                                // visible when it next restarts.
                                Slog.w(TAG, "Exception thrown making visibile: "
                                        + r.intent.getComponent(), e);
                            }
                        }
                    }

                    // Aggregate current change flags.
                    configChanges |= r.configChangeFlags;

                    if (r.fullscreen) {
                        // At this point, nothing else needs to be shown
                        if (DEBUG_VISBILITY) Slog.v(
                                TAG, "Stopping: fullscreen at " + r);
                        behindFullscreen = true;
                    }
                } else {
                    if (DEBUG_VISBILITY) Slog.v(
                        TAG, "Make invisible? " + r + " finishing=" + r.finishing
                        + " state=" + r.state
                        + " behindFullscreen=" + behindFullscreen);
                    // Now for any activities that aren't visible to the user, make
                    // sure they no longer are keeping the screen frozen.
                    if (r.visible) {
                        if (DEBUG_VISBILITY) Slog.v(TAG, "Making invisible: " + r);
                        r.visible = false;
                        try {
                            mService.mWindowManager.setAppVisibility(r.appToken, false);
                            if ((r.state == ActivityState.STOPPING
                                    || r.state == ActivityState.STOPPED)
                                    && r.app != null && r.app.thread != null) {
                                if (DEBUG_VISBILITY) Slog.v(TAG, "Scheduling invisibility: " + r);
                                r.app.thread.scheduleWindowVisibility(r.appToken, false);
                            }
                        } catch (Exception e) {
                            // Just skip on any failure; we'll make it
                            // visible when it next restarts.
                            Slog.w(TAG, "Exception thrown making hidden: "
                                    + r.intent.getComponent(), e);
                        }
                    } else {
                        if (DEBUG_VISBILITY) Slog.v(TAG, "Already invisible: " + r);
                    }
                }
            }
        }
    }

    // Checked.
    /**
     * Version of ensureActivitiesVisible that can easily be called anywhere.
     */
    final void ensureActivitiesVisibleLocked(ActivityRecord starting,
            int configChanges) {
        ActivityRecord r = topRunningActivityLocked(null);
        if (r != null) {
            ensureActivitiesVisibleLocked(r, starting, null, configChanges);
        }
    }

    /**
     * Ensure that the top activity in the stack is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     */
    final boolean resumeTopActivityLocked(ActivityRecord prev) {
        return resumeTopActivityLocked(prev, null);
    }

    // Checked.
    final boolean resumeTopActivityLocked(ActivityRecord prev, Bundle options) {
        // Find the first activity that is not finishing.
        ActivityRecord next = topRunningActivityLocked(null);

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        final boolean userLeaving = mStackSupervisor.mUserLeaving;
        mStackSupervisor.mUserLeaving = false;

        if (next == null) {
            // There are no more activities!  Let's just start up the
            // Launcher...
            ActivityOptions.abort(options);
            return mService.startHomeActivityLocked(mCurrentUser);
        }

        next.delayedResume = false;

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.state == ActivityState.RESUMED &&
                    mStackSupervisor.allResumedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            mService.mWindowManager.executeAppTransition();
            mNoAnimActivities.clear();
            ActivityOptions.abort(options);
            return false;
        }

        if (prev != null && prev.mLaunchHomeTaskNext && prev.finishing &&
                prev.task.getTopActivity() == null) {
            prev.mLaunchHomeTaskNext = false;
            return mService.startHomeActivityLocked(mCurrentUser);
        }

        // If we are sleeping, and there is no resumed activity, and the top
        // activity is paused, well that is the state we want.
        if ((mService.isSleepingOrShuttingDown())
                && mLastPausedActivity == next
                && mStackSupervisor.allPausedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            mService.mWindowManager.executeAppTransition();
            mNoAnimActivities.clear();
            ActivityOptions.abort(options);
            return false;
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (mService.mStartedUsers.get(next.userId) == null) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.userId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStackSupervisor.mStoppingActivities.remove(next);
        mGoingToSleepActivities.remove(next);
        next.sleeping = false;
        mStackSupervisor.mWaitingVisibleActivities.remove(next);

        next.updateOptionsLocked(options);

        if (DEBUG_SWITCH) Slog.v(TAG, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything
        // until that is done.
        if (mPausingActivity != null) {
            if (DEBUG_SWITCH || DEBUG_PAUSE) Slog.v(TAG,
                    "Skip resume: pausing=" + mPausingActivity);
            return false;
        }

        // Okay we are now going to start a switch, to 'next'.  We may first
        // have to pause the current activity, but this is an important point
        // where we have decided to go to 'next' so keep track of that.
        // XXX "App Redirected" dialog is getting too many false positives
        // at this point, so turn off for now.
        if (false) {
            if (mLastStartedActivity != null && !mLastStartedActivity.finishing) {
                long now = SystemClock.uptimeMillis();
                final boolean inTime = mLastStartedActivity.startTime != 0
                        && (mLastStartedActivity.startTime + START_WARN_TIME) >= now;
                final int lastUid = mLastStartedActivity.info.applicationInfo.uid;
                final int nextUid = next.info.applicationInfo.uid;
                if (inTime && lastUid != nextUid
                        && lastUid != next.launchedFromUid
                        && mService.checkPermission(
                                android.Manifest.permission.STOP_APP_SWITCHES,
                                -1, next.launchedFromUid)
                        != PackageManager.PERMISSION_GRANTED) {
                    mService.showLaunchWarningLocked(mLastStartedActivity, next);
                } else {
                    next.startTime = now;
                    mLastStartedActivity = next;
                }
            } else {
                next.startTime = SystemClock.uptimeMillis();
                mLastStartedActivity = next;
            }
        }

        // We need to start pausing the current activity so the top one
        // can be resumed...
        final ActivityStack lastStack = mStackSupervisor.getLastStack();
        if ((isHomeStack() ^ lastStack.isHomeStack()) && lastStack.mResumedActivity != null) {
            if (DEBUG_SWITCH) Slog.v(TAG, "Skip resume: need to start pausing");
            // At this point we want to put the upcoming activity's process
            // at the top of the LRU list, since we know we will be needing it
            // very soon and it would be a waste to let it get killed if it
            // happens to be sitting towards the end.
            if (next.app != null && next.app.thread != null) {
                // No reason to do full oom adj update here; we'll let that
                // happen whenever it needs to later.
                mService.updateLruProcessLocked(next.app, false);
            }
            lastStack.startPausingLocked(userLeaving, false);
            return true;
        }

        // If the most recent activity was noHistory but was only stopped rather
        // than stopped+finished because the device went to sleep, we need to make
        // sure to finish it as we're making a new activity topmost.
        final ActivityRecord last = mLastPausedActivity;
        if (mService.mSleeping && last != null && !last.finishing) {
            if ((last.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                    || (last.info.flags&ActivityInfo.FLAG_NO_HISTORY) != 0) {
                if (DEBUG_STATES) {
                    Slog.d(TAG, "no-history finish of " + last + " on new resume");
                }
                requestFinishActivityLocked(last.appToken, Activity.RESULT_CANCELED, null,
                        "no-history", false);
            }
        }

        if (prev != null && prev != next) {
            if (!prev.waitingVisible && next != null && !next.nowVisible) {
                prev.waitingVisible = true;
                mStackSupervisor.mWaitingVisibleActivities.add(prev);
                if (DEBUG_SWITCH) Slog.v(
                        TAG, "Resuming top, waiting visible to hide: " + prev);
            } else {
                // The next activity is already visible, so hide the previous
                // activity's windows right now so we can show the new one ASAP.
                // We only do this if the previous is finishing, which should mean
                // it is on top of the one being resumed so hiding it quickly
                // is good.  Otherwise, we want to do the normal route of allowing
                // the resumed activity to be shown so we can decide if the
                // previous should actually be hidden depending on whether the
                // new one is found to be full-screen or not.
                if (prev.finishing) {
                    mService.mWindowManager.setAppVisibility(prev.appToken, false);
                    if (DEBUG_SWITCH) Slog.v(TAG, "Not waiting for visible to hide: "
                            + prev + ", waitingVisible="
                            + (prev != null ? prev.waitingVisible : null)
                            + ", nowVisible=" + next.nowVisible);
                } else {
                    if (DEBUG_SWITCH) Slog.v(TAG, "Previous already visible but still waiting to hide: "
                        + prev + ", waitingVisible="
                        + (prev != null ? prev.waitingVisible : null)
                        + ", nowVisible=" + next.nowVisible);
                }
            }
        }

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    next.packageName, false, next.userId); /* TODO: Verify if correct userid */
        } catch (RemoteException e1) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        boolean noAnim = false;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Slog.v(TAG,
                        "Prepare close transition: prev=" + prev);
                if (mNoAnimActivities.contains(prev)) {
                    mService.mWindowManager.prepareAppTransition(
                            AppTransition.TRANSIT_NONE, false);
                } else {
                    mService.mWindowManager.prepareAppTransition(prev.task == next.task
                            ? AppTransition.TRANSIT_ACTIVITY_CLOSE
                            : AppTransition.TRANSIT_TASK_CLOSE, false);
                }
                mService.mWindowManager.setAppWillBeHidden(prev.appToken);
                mService.mWindowManager.setAppVisibility(prev.appToken, false);
            } else {
                if (DEBUG_TRANSITION) Slog.v(TAG,
                        "Prepare open transition: prev=" + prev);
                if (mNoAnimActivities.contains(next)) {
                    noAnim = true;
                    mService.mWindowManager.prepareAppTransition(
                            AppTransition.TRANSIT_NONE, false);
                } else {
                    mService.mWindowManager.prepareAppTransition(prev.task == next.task
                            ? AppTransition.TRANSIT_ACTIVITY_OPEN
                            : AppTransition.TRANSIT_TASK_OPEN, false);
                }
            }
            if (false) {
                mService.mWindowManager.setAppWillBeHidden(prev.appToken);
                mService.mWindowManager.setAppVisibility(prev.appToken, false);
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG,
                    "Prepare open transition: no previous");
            if (mNoAnimActivities.contains(next)) {
                noAnim = true;
                mService.mWindowManager.prepareAppTransition(
                        AppTransition.TRANSIT_NONE, false);
            } else {
                mService.mWindowManager.prepareAppTransition(
                        AppTransition.TRANSIT_ACTIVITY_OPEN, false);
            }
        }
        if (!noAnim) {
            next.applyOptionsLocked();
        } else {
            next.clearOptionsLocked();
        }

        if (next.app != null && next.app.thread != null) {
            if (DEBUG_SWITCH) Slog.v(TAG, "Resume running: " + next);

            // This activity is now becoming visible.
            mService.mWindowManager.setAppVisibility(next.appToken, true);

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity = lastStack.mResumedActivity;
            ActivityState lastState = next.state;

            mService.updateCpuStats();

            if (DEBUG_STATES) Slog.v(TAG, "Moving to RESUMED: " + next + " (in existing)");
            next.state = ActivityState.RESUMED;
            mResumedActivity = next;
            next.task.touchActiveTime();
            mService.addRecentTaskLocked(next.task);
            mService.updateLruProcessLocked(next.app, true);
            updateLRUListLocked(next);

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            boolean updated = false;
            if (mStackSupervisor.isFrontStack(this)) {
                Configuration config = mService.mWindowManager.updateOrientationFromAppTokens(
                        mService.mConfiguration,
                        next.mayFreezeScreenLocked(next.app) ? next.appToken : null);
                if (config != null) {
                    next.frozenBeforeDestroy = true;
                }
                updated = mService.updateConfigurationLocked(config, next, false, false);
            }

            if (!updated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                ActivityRecord nextNext = topRunningActivityLocked(null);
                if (DEBUG_SWITCH) Slog.i(TAG,
                        "Activity config changed during resume: " + next
                        + ", new next: " + nextNext);
                if (nextNext != next) {
                    // Do over!
                    mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
                }
                if (mStackSupervisor.isFrontStack(this)) {
                    mService.setFocusedActivityLocked(next);
                }
                if (mStackSupervisor.allResumedActivitiesComplete()) {
                    ensureActivitiesVisibleLocked(null, 0);
                    mService.mWindowManager.executeAppTransition();
                    mNoAnimActivities.clear();
                    return true;
                }
                return false;
            }

            try {
                // Deliver all pending results.
                ArrayList<ResultInfo> a = next.results;
                if (a != null) {
                    final int N = a.size();
                    if (!next.finishing && N > 0) {
                        if (DEBUG_RESULTS) Slog.v(
                                TAG, "Delivering results to " + next
                                + ": " + a);
                        next.app.thread.scheduleSendResult(next.appToken, a);
                    }
                }

                if (next.newIntents != null) {
                    next.app.thread.scheduleNewIntent(next.newIntents, next.appToken);
                }

                EventLog.writeEvent(EventLogTags.AM_RESUME_ACTIVITY,
                        next.userId, System.identityHashCode(next),
                        next.task.taskId, next.shortComponentName);

                next.sleeping = false;
                mService.showAskCompatModeDialogLocked(next);
                next.app.pendingUiClean = true;
                next.app.thread.scheduleResumeActivity(next.appToken,
                        mService.isNextTransitionForward());

                checkReadyForSleepLocked();

            } catch (Exception e) {
                // Whoops, need to restart this activity!
                if (DEBUG_STATES) Slog.v(TAG, "Resume failed; resetting state to "
                        + lastState + ": " + next);
                next.state = lastState;
                lastStack.mResumedActivity = lastResumedActivity;
                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else {
                    if (SHOW_APP_STARTING_PREVIEW && mStackSupervisor.isFrontStack(lastStack)) {
                        mService.mWindowManager.setAppStartingWindow(
                                next.appToken, next.packageName, next.theme,
                                mService.compatibilityInfoForPackageLocked(
                                        next.info.applicationInfo),
                                next.nonLocalizedLabel,
                                next.labelRes, next.icon, next.windowFlags,
                                null, true);
                    }
                }
                mStackSupervisor.startSpecificActivityLocked(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.visible = true;
                completeResumeLocked(next);
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Slog.w(TAG, "Exception thrown during resume of " + next, e);
                requestFinishActivityLocked(next.appToken, Activity.RESULT_CANCELED, null,
                        "resume-exception", true);
                return true;
            }
            next.stopped = false;

        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_PREVIEW) {
                    mService.mWindowManager.setAppStartingWindow(
                            next.appToken, next.packageName, next.theme,
                            mService.compatibilityInfoForPackageLocked(
                                    next.info.applicationInfo),
                            next.nonLocalizedLabel,
                            next.labelRes, next.icon, next.windowFlags,
                            null, true);
                }
                if (DEBUG_SWITCH) Slog.v(TAG, "Restarting: " + next);
            }
            mStackSupervisor.startSpecificActivityLocked(next, true, true);
        }

        return true;
    }


    final void startActivityLocked(ActivityRecord r, boolean newTask,
            boolean doResume, boolean keepCurTransition, Bundle options) {
        TaskRecord task = null;
        TaskRecord rTask = r.task;
        final int taskId = rTask.taskId;
        if (taskForIdLocked(taskId) == null || newTask) {
            // Last activity in task had been removed or ActivityManagerService is reusing task.
            // Insert or replace.
            // Might not even be in.
            mTaskHistory.remove(rTask);
            // Now put task at top.
            mTaskHistory.add(rTask);
            mService.mWindowManager.moveTaskToTop(taskId);
        }
        if (!newTask) {
            // If starting in an existing task, find where that is...
            boolean startIt = true;
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                task = mTaskHistory.get(taskNdx);
                if (task == r.task) {
                    // Here it is!  Now, if this is not yet visible to the
                    // user, then just add it without starting; it will
                    // get started when the user navigates back to it.
                    if (!startIt) {
                        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to task "
                                + task, new RuntimeException("here").fillInStackTrace());
                        task.addActivityToTop(r);
                        r.putInHistory();
                        mService.mWindowManager.addAppToken(task.mActivities.indexOf(r),
                                r.appToken, r.task.taskId, mStackId, r.info.screenOrientation,
                                r.fullscreen,
                                (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0);
                        if (VALIDATE_TOKENS) {
                            validateAppTokensLocked();
                        }
                        ActivityOptions.abort(options);
                        return;
                    }
                    break;
                } else if (task.numFullscreen > 0) {
                    startIt = false;
                }
            }
        }

        // Place a new activity at top of stack, so it is next to interact
        // with the user.

        // If we are not placing the new activity frontmost, we do not want
        // to deliver the onUserLeaving callback to the actual frontmost
        // activity
        if (task == r.task && mTaskHistory.indexOf(task) != (mTaskHistory.size() - 1)) {
            mStackSupervisor.mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Slog.v(TAG,
                    "startActivity() behind front, mUserLeaving=false");
        }

        task = r.task;

        // Slot the activity into the history stack and proceed
        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to stack to task " + task,
                new RuntimeException("here").fillInStackTrace());
        task.addActivityToTop(r);

        r.putInHistory();
        r.frontOfTask = newTask;
        if (!isHomeStack() || numActivities() > 0) {
            // We want to show the starting preview window if we are
            // switching to a new task, or the next activity's process is
            // not currently running.
            boolean showStartingIcon = newTask;
            ProcessRecord proc = r.app;
            if (proc == null) {
                proc = mService.mProcessNames.get(r.processName, r.info.applicationInfo.uid);
            }
            if (proc == null || proc.thread == null) {
                showStartingIcon = true;
            }
            if (DEBUG_TRANSITION) Slog.v(TAG,
                    "Prepare open transition: starting " + r);
            if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                mService.mWindowManager.prepareAppTransition(
                        AppTransition.TRANSIT_NONE, keepCurTransition);
                mNoAnimActivities.add(r);
            } else {
                mService.mWindowManager.prepareAppTransition(newTask
                        ? AppTransition.TRANSIT_TASK_OPEN
                        : AppTransition.TRANSIT_ACTIVITY_OPEN, keepCurTransition);
                mNoAnimActivities.remove(r);
            }
            r.updateOptionsLocked(options);
            mService.mWindowManager.addAppToken(task.mActivities.indexOf(r),
                    r.appToken, r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                    (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0);
            boolean doShow = true;
            if (newTask) {
                // Even though this activity is starting fresh, we still need
                // to reset it to make sure we apply affinities to move any
                // existing activities from other tasks in to it.
                // If the caller has requested that the target task be
                // reset, then do so.
                if ((r.intent.getFlags()
                        &Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    resetTaskIfNeededLocked(r, r);
                    doShow = topRunningNonDelayedActivityLocked(null) == r;
                }
            }
            if (SHOW_APP_STARTING_PREVIEW && doShow) {
                // Figure out if we are transitioning from another activity that is
                // "has the same starting icon" as the next one.  This allows the
                // window manager to keep the previous window it had previously
                // created, if it still had one.
                ActivityRecord prev = mResumedActivity;
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.task != r.task) {
                        prev = null;
                    }
                    // (2) The current activity is already displayed.
                    else if (prev.nowVisible) {
                        prev = null;
                    }
                }
                mService.mWindowManager.setAppStartingWindow(
                        r.appToken, r.packageName, r.theme,
                        mService.compatibilityInfoForPackageLocked(
                                r.info.applicationInfo), r.nonLocalizedLabel,
                        r.labelRes, r.icon, r.windowFlags,
                        prev != null ? prev.appToken : null, showStartingIcon);
            }
        } else {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            mService.mWindowManager.addAppToken(task.mActivities.indexOf(r), r.appToken,
                    r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                    (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0);
            ActivityOptions.abort(options);
        }
        if (VALIDATE_TOKENS) {
            validateAppTokensLocked();
        }

        if (doResume) {
            mStackSupervisor.resumeTopActivitiesLocked();
        }
    }

    final void validateAppTokensLocked() {
        mValidateAppTokens.clear();
        mValidateAppTokens.ensureCapacity(numActivities());
        final int numTasks = mTaskHistory.size();
        for (int taskNdx = 0; taskNdx < numTasks; ++taskNdx) {
            TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            if (activities.size() == 0) {
                continue;
            }
            TaskGroup group = new TaskGroup();
            group.taskId = task.taskId;
            mValidateAppTokens.add(group);
            final int numActivities = activities.size();
            for (int activityNdx = 0; activityNdx < numActivities; ++activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                group.tokens.add(r.appToken);
            }
        }
        mService.mWindowManager.validateAppTokens(mStackId, mValidateAppTokens);
    }

    /**
     * Perform a reset of the given task, if needed as part of launching it.
     * Returns the new HistoryRecord at the top of the task.
     */
    /**
     * Helper method for #resetTaskIfNeededLocked.
     * We are inside of the task being reset...  we'll either finish this activity, push it out
     * for another task, or leave it as-is.
     * @param task The task containing the Activity (taskTop) that might be reset.
     * @param forceReset
     * @return An ActivityOptions that needs to be processed.
     */
    final ActivityOptions resetTargetTaskIfNeededLocked(TaskRecord task,
            boolean forceReset) {
        ActivityOptions topOptions = null;

        int replyChainEnd = -1;
        boolean canMoveOptions = true;

        // We only do this for activities that are not the root of the task (since if we finish
        // the root, we may no longer have the task!).
        final ArrayList<ActivityRecord> activities = task.mActivities;
        final int numActivities = activities.size();
        for (int i = numActivities - 1; i > 0; --i ) {
            ActivityRecord target = activities.get(i);

            final int flags = target.info.flags;
            final boolean finishOnTaskLaunch =
                    (flags & ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
            final boolean allowTaskReparenting =
                    (flags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;
            final boolean clearWhenTaskReset =
                    (target.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0;

            if (!finishOnTaskLaunch
                    && !clearWhenTaskReset
                    && target.resultTo != null) {
                // If this activity is sending a reply to a previous
                // activity, we can't do anything with it now until
                // we reach the start of the reply chain.
                // XXX note that we are assuming the result is always
                // to the previous activity, which is almost always
                // the case but we really shouldn't count on.
                if (replyChainEnd < 0) {
                    replyChainEnd = i;
                }
            } else if (!finishOnTaskLaunch
                    && !clearWhenTaskReset
                    && allowTaskReparenting
                    && target.taskAffinity != null
                    && !target.taskAffinity.equals(task.affinity)) {
                // If this activity has an affinity for another
                // task, then we need to move it out of here.  We will
                // move it as far out of the way as possible, to the
                // bottom of the activity stack.  This also keeps it
                // correctly ordered with any activities we previously
                // moved.
                TaskRecord bottomTask = mTaskHistory.get(0);
                ActivityRecord p = bottomTask.mActivities.get(0);
                if (target.taskAffinity != null
                        && target.taskAffinity.equals(p.task.affinity)) {
                    // If the activity currently at the bottom has the
                    // same task affinity as the one we are moving,
                    // then merge it into the same task.
                    target.setTask(p.task, p.thumbHolder, false);
                    if (DEBUG_TASKS) Slog.v(TAG, "Start pushing activity " + target
                            + " out to bottom task " + p.task);
                } else {
                    target.setTask(createTaskRecord(mStackSupervisor.getNextTaskId(), target.info,
                            null, false), null, false);
                    target.task.affinityIntent = target.intent;
                    if (DEBUG_TASKS) Slog.v(TAG, "Start pushing activity " + target
                            + " out to new task " + target.task);
                }

                final TaskRecord targetTask = target.task;
                final int targetTaskId = targetTask.taskId;
                mService.mWindowManager.setAppGroupId(target.appToken, targetTaskId);

                ThumbnailHolder curThumbHolder = target.thumbHolder;
                boolean gotOptions = !canMoveOptions;

                final int start = replyChainEnd < 0 ? i : replyChainEnd;
                for (int srcPos = start; srcPos >= i; --srcPos) {
                    p = activities.get(srcPos);
                    if (p.finishing) {
                        continue;
                    }

                    curThumbHolder = p.thumbHolder;
                    canMoveOptions = false;
                    if (!gotOptions && topOptions == null) {
                        topOptions = p.takeOptionsLocked();
                        if (topOptions != null) {
                            gotOptions = true;
                        }
                    }
                    if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Removing activity " + p + " from task="
                            + task + " adding to task=" + targetTask,
                            new RuntimeException("here").fillInStackTrace());
                    if (DEBUG_TASKS) Slog.v(TAG, "Pushing next activity " + p
                            + " out to target's task " + target.task);
                    p.setTask(targetTask, curThumbHolder, false);
                    targetTask.addActivityAtBottom(p);

                    mService.mWindowManager.setAppGroupId(p.appToken, targetTaskId);
                }

                mService.mWindowManager.moveTaskToBottom(targetTaskId);
                if (VALIDATE_TOKENS) {
                    validateAppTokensLocked();
                }

                replyChainEnd = -1;
            } else if (forceReset || finishOnTaskLaunch || clearWhenTaskReset) {
                // If the activity should just be removed -- either
                // because it asks for it, or the task should be
                // cleared -- then finish it and anything that is
                // part of its reply chain.
                int end;
                if (clearWhenTaskReset) {
                    // In this case, we want to finish this activity
                    // and everything above it, so be sneaky and pretend
                    // like these are all in the reply chain.
                    end = numActivities - 1;
                } else if (replyChainEnd < 0) {
                    end = i;
                } else {
                    end = replyChainEnd;
                }
                ActivityRecord p = null;
                boolean gotOptions = !canMoveOptions;
                for (int srcPos = i; srcPos <= end; srcPos++) {
                    p = activities.get(srcPos);
                    if (p.finishing) {
                        continue;
                    }
                    canMoveOptions = false;
                    if (!gotOptions && topOptions == null) {
                        topOptions = p.takeOptionsLocked();
                        if (topOptions != null) {
                            gotOptions = true;
                        }
                    }
                    if (DEBUG_TASKS) Slog.w(TAG,
                            "resetTaskIntendedTask: calling finishActivity on " + p);
                    if (finishActivityLocked(p, Activity.RESULT_CANCELED, null, "reset", false)) {
                        end--;
                        srcPos--;
                    }
                }
                replyChainEnd = -1;
            } else {
                // If we were in the middle of a chain, well the
                // activity that started it all doesn't want anything
                // special, so leave it all as-is.
                replyChainEnd = -1;
            }
        }

        return topOptions;
    }

    /**
     * Helper method for #resetTaskIfNeededLocked. Processes all of the activities in a given
     * TaskRecord looking for an affinity with the task of resetTaskIfNeededLocked.taskTop.
     * @param affinityTask The task we are looking for an affinity to.
     * @param task Task that resetTaskIfNeededLocked.taskTop belongs to.
     * @param topTaskIsHigher True if #task has already been processed by resetTaskIfNeededLocked.
     * @param forceReset Flag passed in to resetTaskIfNeededLocked.
     */
    private final int resetAffinityTaskIfNeededLocked(TaskRecord affinityTask, TaskRecord task,
            boolean topTaskIsHigher, boolean forceReset, int taskInsertionPoint) {
        int replyChainEnd = -1;
        final int taskId = task.taskId;
        final String taskAffinity = task.affinity;

        final ArrayList<ActivityRecord> activities = affinityTask.mActivities;
        final int numActivities = activities.size();
        // Do not operate on the root Activity.
        for (int i = numActivities - 1; i > 0; --i) {
            ActivityRecord target = activities.get(i);

            final int flags = target.info.flags;
            boolean finishOnTaskLaunch = (flags & ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
            boolean allowTaskReparenting = (flags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;

            if (target.resultTo != null) {
                // If this activity is sending a reply to a previous
                // activity, we can't do anything with it now until
                // we reach the start of the reply chain.
                // XXX note that we are assuming the result is always
                // to the previous activity, which is almost always
                // the case but we really shouldn't count on.
                if (replyChainEnd < 0) {
                    replyChainEnd = i;
                }
            } else if (topTaskIsHigher
                    && allowTaskReparenting
                    && taskAffinity != null
                    && taskAffinity.equals(target.taskAffinity)) {
                // This activity has an affinity for our task. Either remove it if we are
                // clearing or move it over to our task.  Note that
                // we currently punt on the case where we are resetting a
                // task that is not at the top but who has activities above
                // with an affinity to it...  this is really not a normal
                // case, and we will need to later pull that task to the front
                // and usually at that point we will do the reset and pick
                // up those remaining activities.  (This only happens if
                // someone starts an activity in a new task from an activity
                // in a task that is not currently on top.)
                if (forceReset || finishOnTaskLaunch) {
                    final int start = replyChainEnd >= 0 ? replyChainEnd : i;
                    if (DEBUG_TASKS) Slog.v(TAG, "Finishing task at index " + start + " to " + i);
                    for (int srcPos = start; srcPos >= i; --srcPos) {
                        final ActivityRecord p = activities.get(srcPos);
                        if (p.finishing) {
                            continue;
                        }
                        finishActivityLocked(p, Activity.RESULT_CANCELED, null, "reset", false);
                    }
                } else {
                    if (taskInsertionPoint < 0) {
                        taskInsertionPoint = task.mActivities.size();

                    }

                    final int start = replyChainEnd >= 0 ? replyChainEnd : i;
                    if (DEBUG_TASKS) Slog.v(TAG, "Reparenting from task=" + affinityTask + ":"
                            + start + "-" + i + " to task=" + task + ":" + taskInsertionPoint);
                    for (int srcPos = start; srcPos >= i; --srcPos) {
                        final ActivityRecord p = activities.get(srcPos);
                        p.setTask(task, null, false);
                        task.addActivityAtIndex(taskInsertionPoint, p);

                        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Removing and adding activity " + p
                                + " to stack at " + task,
                                new RuntimeException("here").fillInStackTrace());
                        if (DEBUG_TASKS) Slog.v(TAG, "Pulling activity " + p + " from " + srcPos
                                + " in to resetting task " + task);
                        mService.mWindowManager.setAppGroupId(p.appToken, taskId);
                    }
                    mService.mWindowManager.moveTaskToTop(taskId);
                    if (VALIDATE_TOKENS) {
                        validateAppTokensLocked();
                    }

                    // Now we've moved it in to place...  but what if this is
                    // a singleTop activity and we have put it on top of another
                    // instance of the same activity?  Then we drop the instance
                    // below so it remains singleTop.
                    if (target.info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
                        ArrayList<ActivityRecord> taskActivities = task.mActivities;
                        boolean found = false;
                        int targetNdx = taskActivities.indexOf(target);
                        if (targetNdx > 0) {
                            ActivityRecord p = taskActivities.get(targetNdx - 1);
                            if (p.intent.getComponent().equals(target.intent.getComponent())) {
                                finishActivityLocked(p, Activity.RESULT_CANCELED, null, "replace",
                                        false);
                            }
                        }
                    }
                }

                replyChainEnd = -1;
            }
        }
        return taskInsertionPoint;
    }

    final ActivityRecord resetTaskIfNeededLocked(ActivityRecord taskTop,
            ActivityRecord newActivity) {
        boolean forceReset =
                (newActivity.info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        if (ACTIVITY_INACTIVE_RESET_TIME > 0
                && taskTop.task.getInactiveDuration() > ACTIVITY_INACTIVE_RESET_TIME) {
            if ((newActivity.info.flags & ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE) == 0) {
                forceReset = true;
            }
        }

        final TaskRecord task = taskTop.task;

        /** False until we evaluate the TaskRecord associated with taskTop. Switches to true
         * for remaining tasks. Used for later tasks to reparent to task. */
        boolean taskFound = false;

        /** If ActivityOptions are moved out and need to be aborted or moved to taskTop. */
        ActivityOptions topOptions = null;

        // Preserve the location for reparenting in the new task.
        int reparentInsertionPoint = -1;

        for (int i = mTaskHistory.size() - 1; i >= 0; --i) {
            final TaskRecord targetTask = mTaskHistory.get(i);

            if (targetTask == task) {
                topOptions = resetTargetTaskIfNeededLocked(task, forceReset);
                taskFound = true;
            } else {
                reparentInsertionPoint = resetAffinityTaskIfNeededLocked(targetTask, task,
                        taskFound, forceReset, reparentInsertionPoint);
            }
        }

        int taskNdx = mTaskHistory.indexOf(task);
        do {
            taskTop = mTaskHistory.get(taskNdx--).getTopActivity();
        } while (taskTop == null && taskNdx >= 0);

        if (topOptions != null) {
            // If we got some ActivityOptions from an activity on top that
            // was removed from the task, propagate them to the new real top.
            if (taskTop != null) {
                taskTop.updateOptionsLocked(topOptions);
            } else {
                topOptions.abort();
            }
        }

        return taskTop;
    }

    /**
     * Find the activity in the history stack within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    final ActivityRecord findActivityInHistoryLocked(ActivityRecord r, TaskRecord task) {
        final ComponentName realActivity = r.realActivity;
        ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord candidate = activities.get(activityNdx);
            if (candidate.finishing) {
                continue;
            }
            if (candidate.realActivity.equals(realActivity)) {
                return candidate;
            }
        }
        return null;
    }

    void reportActivityLaunchedLocked(boolean timeout, ActivityRecord r,
            long thisTime, long totalTime) {
        for (int i=mWaitingActivityLaunched.size()-1; i>=0; i--) {
            WaitResult w = mWaitingActivityLaunched.get(i);
            w.timeout = timeout;
            if (r != null) {
                w.who = new ComponentName(r.info.packageName, r.info.name);
            }
            w.thisTime = thisTime;
            w.totalTime = totalTime;
        }
        mService.notifyAll();
    }

    void reportActivityVisibleLocked(ActivityRecord r) {
        for (int i=mWaitingActivityVisible.size()-1; i>=0; i--) {
            WaitResult w = mWaitingActivityVisible.get(i);
            w.timeout = false;
            if (r != null) {
                w.who = new ComponentName(r.info.packageName, r.info.name);
            }
            w.totalTime = SystemClock.uptimeMillis() - w.thisTime;
            w.thisTime = w.totalTime;
        }
        mService.notifyAll();
        mStackSupervisor.dismissKeyguard();
    }

    void sendActivityResultLocked(int callingUid, ActivityRecord r,
            String resultWho, int requestCode, int resultCode, Intent data) {

        if (callingUid > 0) {
            mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                    data, r.getUriPermissionsLocked());
        }

        if (DEBUG_RESULTS) Slog.v(TAG, "Send activity result to " + r
                + " : who=" + resultWho + " req=" + requestCode
                + " res=" + resultCode + " data=" + data);
        if (mResumedActivity == r && r.app != null && r.app.thread != null) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
                list.add(new ResultInfo(resultWho, requestCode,
                        resultCode, data));
                r.app.thread.scheduleSendResult(r.appToken, list);
                return;
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending result to " + r, e);
            }
        }

        r.addResultLocked(null, resultWho, requestCode, resultCode, data);
    }

    private final void stopActivityLocked(ActivityRecord r) {
        if (DEBUG_SWITCH) Slog.d(TAG, "Stopping: " + r);
        if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (r.info.flags&ActivityInfo.FLAG_NO_HISTORY) != 0) {
            if (!r.finishing) {
                if (!mService.mSleeping) {
                    if (DEBUG_STATES) {
                        Slog.d(TAG, "no-history finish of " + r);
                    }
                    requestFinishActivityLocked(r.appToken, Activity.RESULT_CANCELED, null,
                            "no-history", false);
                } else {
                    if (DEBUG_STATES) Slog.d(TAG, "Not finishing noHistory " + r
                            + " on stop because we're just sleeping");
                }
            }
        }

        if (r.app != null && r.app.thread != null) {
            if (mStackSupervisor.isFrontStack(this)) {
                if (mService.mFocusedActivity == r) {
                    mService.setFocusedActivityLocked(topRunningActivityLocked(null));
                }
            }
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                if (DEBUG_STATES) Slog.v(TAG, "Moving to STOPPING: " + r
                        + " (stop requested)");
                r.state = ActivityState.STOPPING;
                if (DEBUG_VISBILITY) Slog.v(
                        TAG, "Stopping visible=" + r.visible + " for " + r);
                if (!r.visible) {
                    mService.mWindowManager.setAppVisibility(r.appToken, false);
                }
                r.app.thread.scheduleStopActivity(r.appToken, r.visible, r.configChangeFlags);
                if (mService.isSleepingOrShuttingDown()) {
                    r.setSleeping(true);
                }
                Message msg = mHandler.obtainMessage(STOP_TIMEOUT_MSG);
                msg.obj = r;
                mHandler.sendMessageDelayed(msg, STOP_TIMEOUT);
            } catch (Exception e) {
                // Maybe just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                Slog.w(TAG, "Exception thrown during pause", e);
                // Just in case, assume it to be stopped.
                r.stopped = true;
                if (DEBUG_STATES) Slog.v(TAG, "Stop failed; moving to STOPPED: " + r);
                r.state = ActivityState.STOPPED;
                if (r.configDestroy) {
                    destroyActivityLocked(r, true, false, "stop-except");
                }
            }
        }
    }

    final void scheduleIdleLocked() {
        Message msg = Message.obtain();
        msg.what = IDLE_NOW_MSG;
        mHandler.sendMessage(msg);
    }

    // Checked.
    final ActivityRecord activityIdleInternalLocked(final IBinder token, boolean fromTimeout,
            Configuration config) {
        if (localLOGV) Slog.v(TAG, "Activity idle: " + token);

        ActivityRecord res = null;

        ArrayList<ActivityRecord> stops = null;
        ArrayList<ActivityRecord> finishes = null;
        ArrayList<UserStartedState> startingUsers = null;
        int NS = 0;
        int NF = 0;
        IApplicationThread sendThumbnail = null;
        boolean booting = false;
        boolean enableScreen = false;
        boolean activityRemoved = false;

        ActivityRecord r = ActivityRecord.forToken(token);
        if (r != null) {
            mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
            r.finishLaunchTickingLocked();
        }

        // Get the activity record.
        if (isInStackLocked(token) != null) {
            res = r;

            if (fromTimeout) {
                reportActivityLaunchedLocked(fromTimeout, r, -1, -1);
            }

            // This is a hack to semi-deal with a race condition
            // in the client where it can be constructed with a
            // newer configuration from when we asked it to launch.
            // We'll update with whatever configuration it now says
            // it used to launch.
            if (config != null) {
                r.configuration = config;
            }

            // No longer need to keep the device awake.
            if (mResumedActivity == r && mLaunchingActivity.isHeld()) {
                mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                mLaunchingActivity.release();
            }

            // We are now idle.  If someone is waiting for a thumbnail from
            // us, we can now deliver.
            r.idle = true;
            if (mStackSupervisor.allResumedActivitiesIdle()) {
                mService.scheduleAppGcsLocked();
            }
            if (r.thumbnailNeeded && r.app != null && r.app.thread != null) {
                sendThumbnail = r.app.thread;
                r.thumbnailNeeded = false;
            }

            // If this activity is fullscreen, set up to hide those under it.

            if (DEBUG_VISBILITY) Slog.v(TAG, "Idle activity for " + r);
            ensureActivitiesVisibleLocked(null, 0);

            //Slog.i(TAG, "IDLE: mBooted=" + mBooted + ", fromTimeout=" + fromTimeout);
            if (!mService.mBooted && mStackSupervisor.isFrontStack(this)) {
                mService.mBooted = true;
                enableScreen = true;
            }
        } else if (fromTimeout) {
            reportActivityLaunchedLocked(fromTimeout, null, -1, -1);
        }

        // Atomically retrieve all of the other things to do.
        stops = mStackSupervisor.processStoppingActivitiesLocked(true);
        NS = stops != null ? stops.size() : 0;
        if ((NF=mFinishingActivities.size()) > 0) {
            finishes = new ArrayList<ActivityRecord>(mFinishingActivities);
            mFinishingActivities.clear();
        }

        final ArrayList<ActivityRecord> thumbnails;
        final int NT = mCancelledThumbnails.size();
        if (NT > 0) {
            thumbnails = new ArrayList<ActivityRecord>(mCancelledThumbnails);
            mCancelledThumbnails.clear();
        } else {
            thumbnails = null;
        }

        if (mStackSupervisor.isFrontStack(this)) {
            booting = mService.mBooting;
            mService.mBooting = false;
        }

        if (mStartingUsers.size() > 0) {
            startingUsers = new ArrayList<UserStartedState>(mStartingUsers);
            mStartingUsers.clear();
        }

        // Perform the following actions from unsynchronized state.
        final IApplicationThread thumbnailThread = sendThumbnail;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (thumbnailThread != null) {
                    try {
                        thumbnailThread.requestThumbnail(token);
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception thrown when requesting thumbnail", e);
                        mService.sendPendingThumbnail(null, token, null, null, true);
                    }
                }

                // Report back to any thumbnail receivers.
                for (int i = 0; i < NT; i++) {
                    ActivityRecord r = thumbnails.get(i);
                    mService.sendPendingThumbnail(r, null, null, null, true);
                }
            }
        });

        // Stop any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NS; i++) {
            r = stops.get(i);
            if (r.finishing) {
                finishCurrentActivityLocked(r, FINISH_IMMEDIATELY, false);
            } else {
                stopActivityLocked(r);
            }
        }

        // Finish any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NF; i++) {
            r = finishes.get(i);
            activityRemoved |= destroyActivityLocked(r, true, false, "finish-idle");
        }

        if (booting) {
            mService.finishBooting();
        } else if (startingUsers != null) {
            for (int i = 0; i < startingUsers.size(); i++) {
                mService.finishUserSwitch(startingUsers.get(i));
            }
        }

        mService.trimApplications();
        //dump();
        //mWindowManager.dump();

        if (enableScreen) {
            mService.enableScreenAfterBoot();
        }

        if (activityRemoved) {
            mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        }

        return res;
    }

    /**
     * @return Returns true if the activity is being finished, false if for
     * some reason it is being left as-is.
     */
    final boolean requestFinishActivityLocked(IBinder token, int resultCode,
            Intent resultData, String reason, boolean oomAdj) {
        ActivityRecord r = isInStackLocked(token);
        if (DEBUG_RESULTS || DEBUG_STATES) Slog.v(
                TAG, "Finishing activity token=" + token + " r="
                + ", result=" + resultCode + ", data=" + resultData
                + ", reason=" + reason);
        if (r == null) {
            return false;
        }

        finishActivityLocked(r, resultCode, resultData, reason, oomAdj);
        return true;
    }

    final void finishSubActivityLocked(ActivityRecord self, String resultWho, int requestCode) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.resultTo == self && r.requestCode == requestCode) {
                    if ((r.resultWho == null && resultWho == null) ||
                        (r.resultWho != null && r.resultWho.equals(resultWho))) {
                        finishActivityLocked(r, Activity.RESULT_CANCELED, null, "request-sub",
                                false);
                    }
                }
            }
        }
        mService.updateOomAdjLocked();
    }

    final void finishTopRunningActivityLocked(ProcessRecord app) {
        ActivityRecord r = topRunningActivityLocked(null);
        if (r != null && r.app == app) {
            // If the top running activity is from this crashing
            // process, then terminate it to avoid getting in a loop.
            Slog.w(TAG, "  Force finishing activity "
                    + r.intent.getComponent().flattenToShortString());
            int taskNdx = mTaskHistory.indexOf(r.task);
            int activityNdx = r.task.mActivities.indexOf(r);
            finishActivityLocked(r, Activity.RESULT_CANCELED, null, "crashed", false);
            // Also terminate any activities below it that aren't yet
            // stopped, to avoid a situation where one will get
            // re-start our crashing activity once it gets resumed again.
            --activityNdx;
            if (activityNdx < 0) {
                do {
                    --taskNdx;
                    if (taskNdx < 0) {
                        break;
                    }
                    activityNdx = mTaskHistory.get(taskNdx).mActivities.size() - 1;
                } while (activityNdx < 0);
            }
            if (activityNdx >= 0) {
                r = mTaskHistory.get(taskNdx).mActivities.get(activityNdx);
                if (r.state == ActivityState.RESUMED
                        || r.state == ActivityState.PAUSING
                        || r.state == ActivityState.PAUSED) {
                    if (!r.isHomeActivity || mService.mHomeProcess != r.app) {
                        Slog.w(TAG, "  Force finishing activity "
                                + r.intent.getComponent().flattenToShortString());
                        finishActivityLocked(r, Activity.RESULT_CANCELED, null, "crashed", false);
                    }
                }
            }
        }
    }

    final boolean finishActivityAffinityLocked(ActivityRecord r) {
        ArrayList<ActivityRecord> activities = r.task.mActivities;
        for (int index = activities.indexOf(r); index >= 0; --index) {
            ActivityRecord cur = activities.get(index);
            if (!Objects.equal(cur.taskAffinity, r.taskAffinity)) {
                break;
            }
            finishActivityLocked(cur, Activity.RESULT_CANCELED, null, "request-affinity", true);
        }
        return true;
    }

    final void finishActivityResultsLocked(ActivityRecord r, int resultCode, Intent resultData) {
        // send the result
        ActivityRecord resultTo = r.resultTo;
        if (resultTo != null) {
            if (DEBUG_RESULTS) Slog.v(TAG, "Adding result to " + resultTo
                    + " who=" + r.resultWho + " req=" + r.requestCode
                    + " res=" + resultCode + " data=" + resultData);
            if (r.info.applicationInfo.uid > 0) {
                mService.grantUriPermissionFromIntentLocked(r.info.applicationInfo.uid,
                        resultTo.packageName, resultData,
                        resultTo.getUriPermissionsLocked());
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode,
                                     resultData);
            r.resultTo = null;
        }
        else if (DEBUG_RESULTS) Slog.v(TAG, "No result destination from " + r);

        // Make sure this HistoryRecord is not holding on to other resources,
        // because clients have remote IPC references to this object so we
        // can't assume that will go away and want to avoid circular IPC refs.
        r.results = null;
        r.pendingResults = null;
        r.newIntents = null;
        r.icicle = null;
    }

    /**
     * @return Returns true if this activity has been removed from the history
     * list, or false if it is still in the list and will be removed later.
     */
    final boolean finishActivityLocked(ActivityRecord r, int resultCode,
            Intent resultData, String reason, boolean oomAdj) {
        if (r.finishing) {
            Slog.w(TAG, "Duplicate finish request for " + r);
            return false;
        }

        r.makeFinishing();
        EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                r.userId, System.identityHashCode(r),
                r.task.taskId, r.shortComponentName, reason);
        final ArrayList<ActivityRecord> activities = r.task.mActivities;
        final int index = activities.indexOf(r);
        if (index < (activities.size() - 1)) {
            ActivityRecord next = activities.get(index+1);
            if (r.frontOfTask) {
                // The next activity is now the front of the task.
                next.frontOfTask = true;
            }
            if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                // If the caller asked that this activity (and all above it)
                // be cleared when the task is reset, don't lose that information,
                // but propagate it up to the next activity.
                next.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            }
        }

        r.pauseKeyDispatchingLocked();
        if (mStackSupervisor.isFrontStack(this)) {
            if (mService.mFocusedActivity == r) {
                mService.setFocusedActivityLocked(mStackSupervisor.topRunningActivityLocked());
            }
        }

        finishActivityResultsLocked(r, resultCode, resultData);

        if (mService.mPendingThumbnails.size() > 0) {
            // There are clients waiting to receive thumbnails so, in case
            // this is an activity that someone is waiting for, add it
            // to the pending list so we can correctly update the clients.
            mCancelledThumbnails.add(r);
        }

        if (mResumedActivity == r) {
            boolean endTask = index <= 0;
            if (DEBUG_TRANSITION) Slog.v(TAG,
                    "Prepare close transition: finishing " + r);
            mService.mWindowManager.prepareAppTransition(endTask
                    ? AppTransition.TRANSIT_TASK_CLOSE
                    : AppTransition.TRANSIT_ACTIVITY_CLOSE, false);

            // Tell window manager to prepare for this one to be removed.
            mService.mWindowManager.setAppVisibility(r.appToken, false);

            if (mPausingActivity == null) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Finish needs to pause: " + r);
                if (DEBUG_USER_LEAVING) Slog.v(TAG, "finish() => pause with userLeaving=false");
                startPausingLocked(false, false);
            }

        } else if (r.state != ActivityState.PAUSING) {
            // If the activity is PAUSING, we will complete the finish once
            // it is done pausing; else we can just directly finish it here.
            if (DEBUG_PAUSE) Slog.v(TAG, "Finish not pausing: " + r);
            return finishCurrentActivityLocked(r, FINISH_AFTER_PAUSE, oomAdj) == null;
        } else {
            if (DEBUG_PAUSE) Slog.v(TAG, "Finish waiting for pause of: " + r);
        }

        return false;
    }

    private static final int FINISH_IMMEDIATELY = 0;
    private static final int FINISH_AFTER_PAUSE = 1;
    private static final int FINISH_AFTER_VISIBLE = 2;

    private final ActivityRecord finishCurrentActivityLocked(ActivityRecord r,
            int mode, boolean oomAdj) {
        // First things first: if this activity is currently visible,
        // and the resumed activity is not yet visible, then hold off on
        // finishing until the resumed one becomes visible.
        if (mode == FINISH_AFTER_VISIBLE && r.nowVisible) {
            if (!mStackSupervisor.mStoppingActivities.contains(r)) {
                mStackSupervisor.mStoppingActivities.add(r);
                if (mStackSupervisor.mStoppingActivities.size() > 3
                        || r.frontOfTask && mTaskHistory.size() <= 1) {
                    // If we already have a few activities waiting to stop,
                    // then give up on things going idle and start clearing
                    // them out. Or if r is the last of activity of the last task the stack
                    // will be empty and must be cleared immediately.
                    scheduleIdleLocked();
                } else {
                    checkReadyForSleepLocked();
                }
            }
            if (DEBUG_STATES) Slog.v(TAG, "Moving to STOPPING: " + r
                    + " (finish requested)");
            r.state = ActivityState.STOPPING;
            if (oomAdj) {
                mService.updateOomAdjLocked();
            }
            return r;
        }

        // make sure the record is cleaned out of other places.
        mStackSupervisor.mStoppingActivities.remove(r);
        mGoingToSleepActivities.remove(r);
        mStackSupervisor.mWaitingVisibleActivities.remove(r);
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        final ActivityState prevState = r.state;
        if (DEBUG_STATES) Slog.v(TAG, "Moving to FINISHING: " + r);
        r.state = ActivityState.FINISHING;

        if (mode == FINISH_IMMEDIATELY
                || prevState == ActivityState.STOPPED
                || prevState == ActivityState.INITIALIZING) {
            // If this activity is already stopped, we can just finish
            // it right now.
            boolean activityRemoved = destroyActivityLocked(r, true,
                    oomAdj, "finish-imm");
            if (activityRemoved) {
                mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
            }
            return activityRemoved ? null : r;
        }

        // Need to go through the full pause cycle to get this
        // activity into the stopped state and then finish it.
        if (localLOGV) Slog.v(TAG, "Enqueueing pending finish: " + r);
        mFinishingActivities.add(r);
        mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        return r;
    }

    final boolean navigateUpToLocked(IBinder token, Intent destIntent, int resultCode,
            Intent resultData) {
        final ActivityRecord srec = ActivityRecord.forToken(token);
        final TaskRecord task = srec.task;
        final ArrayList<ActivityRecord> activities = task.mActivities;
        final int start = activities.indexOf(srec);
        if (!mTaskHistory.contains(task) || (start < 0)) {
            return false;
        }
        int finishTo = start - 1;
        ActivityRecord parent = finishTo < 0 ? null : activities.get(finishTo);
        boolean foundParentInTask = false;
        final ComponentName dest = destIntent.getComponent();
        if (start > 0 && dest != null) {
            for (int i = finishTo; i >= 0; i--) {
                ActivityRecord r = activities.get(i);
                if (r.info.packageName.equals(dest.getPackageName()) &&
                        r.info.name.equals(dest.getClassName())) {
                    finishTo = i;
                    parent = r;
                    foundParentInTask = true;
                    break;
                }
            }
        }

        IActivityController controller = mService.mController;
        if (controller != null) {
            ActivityRecord next = topRunningActivityLocked(srec.appToken, 0);
            if (next != null) {
                // ask watcher if this is allowed
                boolean resumeOK = true;
                try {
                    resumeOK = controller.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mService.mController = null;
                }

                if (!resumeOK) {
                    return false;
                }
            }
        }
        final long origId = Binder.clearCallingIdentity();
        for (int i = start; i > finishTo; i--) {
            ActivityRecord r = activities.get(i);
            requestFinishActivityLocked(r.appToken, resultCode, resultData, "navigate-up", true);
            // Only return the supplied result for the first activity finished
            resultCode = Activity.RESULT_CANCELED;
            resultData = null;
        }

        if (parent != null && foundParentInTask) {
            final int parentLaunchMode = parent.info.launchMode;
            final int destIntentFlags = destIntent.getFlags();
            if (parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TOP ||
                    (destIntentFlags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                parent.deliverNewIntentLocked(srec.info.applicationInfo.uid, destIntent);
            } else {
                try {
                    ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(
                            destIntent.getComponent(), 0, srec.userId);
                    int res = mStackSupervisor.startActivityLocked(srec.app.thread, destIntent,
                            null, aInfo, parent.appToken, null,
                            0, -1, parent.launchedFromUid, parent.launchedFromPackage,
                            0, null, true, null);
                    foundParentInTask = res == ActivityManager.START_SUCCESS;
                } catch (RemoteException e) {
                    foundParentInTask = false;
                }
                requestFinishActivityLocked(parent.appToken, resultCode,
                        resultData, "navigate-up", true);
            }
        }
        Binder.restoreCallingIdentity(origId);
        return foundParentInTask;
    }
    /**
     * Perform the common clean-up of an activity record.  This is called both
     * as part of destroyActivityLocked() (when destroying the client-side
     * representation) and cleaning things up as a result of its hosting
     * processing going away, in which case there is no remaining client-side
     * state to destroy so only the cleanup here is needed.
     */
    final void cleanUpActivityLocked(ActivityRecord r, boolean cleanServices,
            boolean setState) {
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        if (mService.mFocusedActivity == r) {
            mService.mFocusedActivity = null;
        }

        r.configDestroy = false;
        r.frozenBeforeDestroy = false;

        if (setState) {
            if (DEBUG_STATES) Slog.v(TAG, "Moving to DESTROYED: " + r + " (cleaning up)");
            r.state = ActivityState.DESTROYED;
            if (DEBUG_APP) Slog.v(TAG, "Clearing app during cleanUp for activity " + r);
            r.app = null;
        }

        // Make sure this record is no longer in the pending finishes list.
        // This could happen, for example, if we are trimming activities
        // down to the max limit while they are still waiting to finish.
        mFinishingActivities.remove(r);
        mStackSupervisor.mWaitingVisibleActivities.remove(r);

        // Remove any pending results.
        if (r.finishing && r.pendingResults != null) {
            for (WeakReference<PendingIntentRecord> apr : r.pendingResults) {
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    mService.cancelIntentSenderLocked(rec, false);
                }
            }
            r.pendingResults = null;
        }

        if (cleanServices) {
            cleanUpActivityServicesLocked(r);
        }

        if (mService.mPendingThumbnails.size() > 0) {
            // There are clients waiting to receive thumbnails so, in case
            // this is an activity that someone is waiting for, add it
            // to the pending list so we can correctly update the clients.
            mCancelledThumbnails.add(r);
        }

        // Get rid of any pending idle timeouts.
        removeTimeoutsForActivityLocked(r);
    }

    private void removeTimeoutsForActivityLocked(ActivityRecord r) {
        mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
        mHandler.removeMessages(STOP_TIMEOUT_MSG, r);
        mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
        mHandler.removeMessages(DESTROY_TIMEOUT_MSG, r);
        r.finishLaunchTickingLocked();
    }

    final void removeActivityFromHistoryLocked(ActivityRecord r) {
        finishActivityResultsLocked(r, Activity.RESULT_CANCELED, null);
        r.makeFinishing();
        if (DEBUG_ADD_REMOVE) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG, "Removing activity " + r + " from stack");
        }
        final TaskRecord task = r.task;
        if (task != null && task.removeActivity(r)) {
            if (DEBUG_STACK) Slog.i(TAG,
                    "removeActivityFromHistoryLocked: last activity removed from " + this);
            mStackSupervisor.removeTask(task);
        }
        r.takeFromHistory();
        removeTimeoutsForActivityLocked(r);
        if (DEBUG_STATES) Slog.v(TAG, "Moving to DESTROYED: " + r + " (removed from history)");
        r.state = ActivityState.DESTROYED;
        if (DEBUG_APP) Slog.v(TAG, "Clearing app during remove for activity " + r);
        r.app = null;
        mService.mWindowManager.removeAppToken(r.appToken);
        if (VALIDATE_TOKENS) {
            validateAppTokensLocked();
        }
        cleanUpActivityServicesLocked(r);
        r.removeUriPermissionsLocked();
    }

    /**
     * Perform clean-up of service connections in an activity record.
     */
    final void cleanUpActivityServicesLocked(ActivityRecord r) {
        // Throw away any services that have been bound by this activity.
        if (r.connections != null) {
            Iterator<ConnectionRecord> it = r.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                mService.mServices.removeConnectionLocked(c, null, r);
            }
            r.connections = null;
        }
    }

    final void scheduleDestroyActivities(ProcessRecord owner, boolean oomAdj, String reason) {
        Message msg = mHandler.obtainMessage(DESTROY_ACTIVITIES_MSG);
        msg.obj = new ScheduleDestroyArgs(owner, oomAdj, reason);
        mHandler.sendMessage(msg);
    }

    final void destroyActivitiesLocked(ProcessRecord owner, boolean oomAdj, String reason) {
        boolean lastIsOpaque = false;
        boolean activityRemoved = false;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.finishing) {
                    continue;
                }
                if (r.fullscreen) {
                    lastIsOpaque = true;
                }
                if (owner != null && r.app != owner) {
                    continue;
                }
                if (!lastIsOpaque) {
                    continue;
                }
                // We can destroy this one if we have its icicle saved and
                // it is not in the process of pausing/stopping/finishing.
                if (r.app != null && r != mResumedActivity && r != mPausingActivity
                        && r.haveState && !r.visible && r.stopped
                        && r.state != ActivityState.DESTROYING
                        && r.state != ActivityState.DESTROYED) {
                    if (DEBUG_SWITCH) Slog.v(TAG, "Destroying " + r + " in state " + r.state
                            + " resumed=" + mResumedActivity
                            + " pausing=" + mPausingActivity);
                    if (destroyActivityLocked(r, true, oomAdj, reason)) {
                        activityRemoved = true;
                    }
                }
            }
        }
        if (activityRemoved) {
            mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        }
    }

    /**
     * Destroy the current CLIENT SIDE instance of an activity.  This may be
     * called both when actually finishing an activity, or when performing
     * a configuration switch where we destroy the current client-side object
     * but then create a new client-side object for this same HistoryRecord.
     */
    final boolean destroyActivityLocked(ActivityRecord r,
            boolean removeFromApp, boolean oomAdj, String reason) {
        if (DEBUG_SWITCH || DEBUG_CLEANUP) Slog.v(
            TAG, "Removing activity from " + reason + ": token=" + r
              + ", app=" + (r.app != null ? r.app.processName : "(null)"));
        EventLog.writeEvent(EventLogTags.AM_DESTROY_ACTIVITY,
                r.userId, System.identityHashCode(r),
                r.task.taskId, r.shortComponentName, reason);

        boolean removedFromHistory = false;

        cleanUpActivityLocked(r, false, false);

        final boolean hadApp = r.app != null;

        if (hadApp) {
            if (removeFromApp) {
                r.app.activities.remove(r);
                if (mService.mHeavyWeightProcess == r.app && r.app.activities.size() <= 0) {
                    mService.mHeavyWeightProcess = null;
                    mService.mHandler.sendEmptyMessage(
                            ActivityManagerService.CANCEL_HEAVY_NOTIFICATION_MSG);
                }
                if (r.app.activities.size() == 0) {
                    // No longer have activities, so update oom adj.
                    mService.updateOomAdjLocked();
                }
            }

            boolean skipDestroy = false;

            try {
                if (DEBUG_SWITCH) Slog.i(TAG, "Destroying: " + r);
                r.app.thread.scheduleDestroyActivity(r.appToken, r.finishing,
                        r.configChangeFlags);
            } catch (Exception e) {
                // We can just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                //Slog.w(TAG, "Exception thrown during finish", e);
                if (r.finishing) {
                    removeActivityFromHistoryLocked(r);
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }

            r.nowVisible = false;

            // If the activity is finishing, we need to wait on removing it
            // from the list to give it a chance to do its cleanup.  During
            // that time it may make calls back with its token so we need to
            // be able to find it on the list and so we don't want to remove
            // it from the list yet.  Otherwise, we can just immediately put
            // it in the destroyed state since we are not removing it from the
            // list.
            if (r.finishing && !skipDestroy) {
                if (DEBUG_STATES) Slog.v(TAG, "Moving to DESTROYING: " + r
                        + " (destroy requested)");
                r.state = ActivityState.DESTROYING;
                Message msg = mHandler.obtainMessage(DESTROY_TIMEOUT_MSG);
                msg.obj = r;
                mHandler.sendMessageDelayed(msg, DESTROY_TIMEOUT);
            } else {
                if (DEBUG_STATES) Slog.v(TAG, "Moving to DESTROYED: " + r
                        + " (destroy skipped)");
                r.state = ActivityState.DESTROYED;
                if (DEBUG_APP) Slog.v(TAG, "Clearing app during destroy for activity " + r);
                r.app = null;
            }
        } else {
            // remove this record from the history.
            if (r.finishing) {
                removeActivityFromHistoryLocked(r);
                removedFromHistory = true;
            } else {
                if (DEBUG_STATES) Slog.v(TAG, "Moving to DESTROYED: " + r
                        + " (no app)");
                r.state = ActivityState.DESTROYED;
                if (DEBUG_APP) Slog.v(TAG, "Clearing app during destroy for activity " + r);
                r.app = null;
            }
        }

        r.configChangeFlags = 0;

        if (!mLRUActivities.remove(r) && hadApp) {
            Slog.w(TAG, "Activity " + r + " being finished, but not in LRU list");
        }

        return removedFromHistory;
    }

    final void activityDestroyedLocked(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            ActivityRecord r = ActivityRecord.forToken(token);
            if (r != null) {
                mHandler.removeMessages(DESTROY_TIMEOUT_MSG, r);
            }

            if (isInStackLocked(token) != null) {
                if (r.state == ActivityState.DESTROYING) {
                    cleanUpActivityLocked(r, true, false);
                    removeActivityFromHistoryLocked(r);
                }
            }
            mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void removeHistoryRecordsForAppLocked(ArrayList<ActivityRecord> list,
            ProcessRecord app, String listName) {
        int i = list.size();
        if (DEBUG_CLEANUP) Slog.v(
            TAG, "Removing app " + app + " from list " + listName
            + " with " + i + " entries");
        while (i > 0) {
            i--;
            ActivityRecord r = list.get(i);
            if (DEBUG_CLEANUP) Slog.v(TAG, "Record #" + i + " " + r);
            if (r.app == app) {
                if (DEBUG_CLEANUP) Slog.v(TAG, "---> REMOVING this entry!");
                list.remove(i);
                removeTimeoutsForActivityLocked(r);
            }
        }
    }

    boolean removeHistoryRecordsForAppLocked(ProcessRecord app) {
        removeHistoryRecordsForAppLocked(mLRUActivities, app, "mLRUActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mStoppingActivities, app,
                "mStoppingActivities");
        removeHistoryRecordsForAppLocked(mGoingToSleepActivities, app, "mGoingToSleepActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mWaitingVisibleActivities, app,
                "mWaitingVisibleActivities");
        removeHistoryRecordsForAppLocked(mFinishingActivities, app, "mFinishingActivities");

        boolean hasVisibleActivities = false;

        // Clean out the history list.
        int i = numActivities();
        if (DEBUG_CLEANUP) Slog.v(
            TAG, "Removing app " + app + " from history with " + i + " entries");
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                --i;
                if (DEBUG_CLEANUP) Slog.v(
                    TAG, "Record #" + i + " " + r + ": app=" + r.app);
                if (r.app == app) {
                    boolean remove;
                    if ((!r.haveState && !r.stateNotNeeded) || r.finishing) {
                        // Don't currently have state for the activity, or
                        // it is finishing -- always remove it.
                        remove = true;
                    } else if (r.launchCount > 2 &&
                            r.lastLaunchTime > (SystemClock.uptimeMillis()-60000)) {
                        // We have launched this activity too many times since it was
                        // able to run, so give up and remove it.
                        remove = true;
                    } else {
                        // The process may be gone, but the activity lives on!
                        remove = false;
                    }
                    if (remove) {
                        if (ActivityStack.DEBUG_ADD_REMOVE || DEBUG_CLEANUP) {
                            RuntimeException here = new RuntimeException("here");
                            here.fillInStackTrace();
                            Slog.i(TAG, "Removing activity " + r + " from stack at " + i
                                    + ": haveState=" + r.haveState
                                    + " stateNotNeeded=" + r.stateNotNeeded
                                    + " finishing=" + r.finishing
                                    + " state=" + r.state, here);
                        }
                        if (!r.finishing) {
                            Slog.w(TAG, "Force removing " + r + ": app died, no saved state");
                            EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                                    r.userId, System.identityHashCode(r),
                                    r.task.taskId, r.shortComponentName,
                                    "proc died without state saved");
                        }
                        removeActivityFromHistoryLocked(r);

                    } else {
                        // We have the current state for this activity, so
                        // it can be restarted later when needed.
                        if (localLOGV) Slog.v(
                            TAG, "Keeping entry, setting app to null");
                        if (r.visible) {
                            hasVisibleActivities = true;
                        }
                        if (DEBUG_APP) Slog.v(TAG, "Clearing app during removeHistory for activity "
                                + r);
                        r.app = null;
                        r.nowVisible = false;
                        if (!r.haveState) {
                            if (ActivityStack.DEBUG_SAVED_STATE) Slog.i(TAG,
                                    "App died, clearing saved state of " + r);
                            r.icicle = null;
                        }
                    }

                    cleanUpActivityLocked(r, true, true);
                }
            }
        }

        return hasVisibleActivities;
    }

    final void updateTransitLocked(int transit, Bundle options) {
        if (options != null) {
            ActivityRecord r = topRunningActivityLocked(null);
            if (r != null && r.state != ActivityState.RESUMED) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        mService.mWindowManager.prepareAppTransition(transit, false);
    }

    final boolean findTaskToMoveToFrontLocked(int taskId, int flags, Bundle options) {
        final TaskRecord task = taskForIdLocked(taskId);
        if (task != null) {
            if ((flags & ActivityManager.MOVE_TASK_NO_USER_ACTION) == 0) {
                mStackSupervisor.mUserLeaving = true;
            }
            if ((flags & ActivityManager.MOVE_TASK_WITH_HOME) != 0) {
                // Caller wants the home activity moved with it.  To accomplish this,
                // we'll just move the home task to the top first.
                task.mActivities.get(0).mLaunchHomeTaskNext = true;
            }
            moveTaskToFrontLocked(task, null, options);
            return true;
        }
        return false;
    }

    final void moveTaskToFrontLocked(TaskRecord tr, ActivityRecord reason, Bundle options) {
        if (DEBUG_SWITCH) Slog.v(TAG, "moveTaskToFront: " + tr);

        final int numTasks = mTaskHistory.size();
        final int index = mTaskHistory.indexOf(tr);
        if (numTasks == 0 || index < 0 || index == numTasks - 1)  {
            // nothing to do!
            if (reason != null &&
                    (reason.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(AppTransition.TRANSIT_TASK_TO_FRONT, options);
            }
            return;
        }

        // Shift all activities with this task up to the top
        // of the stack, keeping them in the same internal order.
        mTaskHistory.remove(tr);
        mTaskHistory.add(tr);

        if (DEBUG_TRANSITION) Slog.v(TAG, "Prepare to front transition: task=" + tr);
        if (reason != null &&
                (reason.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            mService.mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, false);
            ActivityRecord r = topRunningActivityLocked(null);
            if (r != null) {
                mNoAnimActivities.add(r);
            }
            ActivityOptions.abort(options);
        } else {
            updateTransitLocked(AppTransition.TRANSIT_TASK_TO_FRONT, options);
        }

        mService.mWindowManager.moveTaskToTop(tr.taskId);

        mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        EventLog.writeEvent(EventLogTags.AM_TASK_TO_FRONT, tr.userId, tr.taskId);

        if (VALIDATE_TOKENS) {
            validateAppTokensLocked();
        }
    }

    /**
     * Worker method for rearranging history stack.  Implements the function of moving all 
     * activities for a specific task (gathering them if disjoint) into a single group at the 
     * bottom of the stack.
     * 
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     * 
     * @param task The taskId to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    final boolean moveTaskToBackLocked(int task, ActivityRecord reason) {
        Slog.i(TAG, "moveTaskToBack: " + task);

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (mStackSupervisor.isFrontStack(this) && mService.mController != null) {
            ActivityRecord next = topRunningActivityLocked(null, task);
            if (next == null) {
                next = topRunningActivityLocked(null, 0);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mService.mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mService.mController = null;
                }
                if (!moveOK) {
                    return false;
                }
            }
        }

        if (DEBUG_TRANSITION) Slog.v(TAG,
                "Prepare to back transition: task=" + task);

        final TaskRecord tr = taskForIdLocked(task);
        if (tr == null) {
            return false;
        }
        mTaskHistory.remove(tr);
        mTaskHistory.add(0, tr);

        if (reason != null &&
                (reason.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            mService.mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, false);
            ActivityRecord r = topRunningActivityLocked(null);
            if (r != null) {
                mNoAnimActivities.add(r);
            }
        } else {
            mService.mWindowManager.prepareAppTransition(
                    AppTransition.TRANSIT_TASK_TO_BACK, false);
        }
        mService.mWindowManager.moveTaskToBottom(task);

        if (VALIDATE_TOKENS) {
            validateAppTokensLocked();
        }

        if (mResumedActivity != null && mResumedActivity.task == tr &&
                mResumedActivity.mLaunchHomeTaskNext) {
            mResumedActivity.mLaunchHomeTaskNext = false;
            return mService.startHomeActivityLocked(mCurrentUser);
        }

        mStackSupervisor.getTopStack().resumeTopActivityLocked(null);
        return true;
    }

    public ActivityManager.TaskThumbnails getTaskThumbnailsLocked(TaskRecord tr) {
        TaskAccessInfo info = getTaskAccessInfoLocked(tr, true);
        if (mResumedActivity != null && mResumedActivity.thumbHolder == tr) {
            info.mainThumbnail = screenshotActivities(mResumedActivity);
        }
        if (info.mainThumbnail == null) {
            info.mainThumbnail = tr.lastThumbnail;
        }
        return info;
    }

    public Bitmap getTaskTopThumbnailLocked(TaskRecord tr) {
        if (mResumedActivity != null && mResumedActivity.task == tr) {
            // This task is the current resumed task, we just need to take
            // a screenshot of it and return that.
            return screenshotActivities(mResumedActivity);
        }
        // Return the information about the task, to figure out the top
        // thumbnail to return.
        TaskAccessInfo info = getTaskAccessInfoLocked(tr, true);
        if (info.numSubThumbbails <= 0) {
            return info.mainThumbnail != null ? info.mainThumbnail : tr.lastThumbnail;
        }
        return info.subtasks.get(info.numSubThumbbails-1).holder.lastThumbnail;
    }

    public ActivityRecord removeTaskActivitiesLocked(int taskId, int subTaskIndex,
            boolean taskRequired) {
        final TaskRecord task = taskForIdLocked(taskId);
        if (task == null) {
            return null;
        }
        TaskAccessInfo info = getTaskAccessInfoLocked(task, false);
        if (info.root == null) {
            if (taskRequired) {
                Slog.w(TAG, "removeTaskLocked: unknown taskId " + taskId);
            }
            return null;
        }

        if (subTaskIndex < 0) {
            // Just remove the entire task.
            task.performClearTaskAtIndexLocked(info.rootIndex);
            return info.root;
        }

        if (subTaskIndex >= info.subtasks.size()) {
            if (taskRequired) {
                Slog.w(TAG, "removeTaskLocked: unknown subTaskIndex " + subTaskIndex);
            }
            return null;
        }

        // Remove all of this task's activities starting at the sub task.
        TaskAccessInfo.SubTask subtask = info.subtasks.get(subTaskIndex);
        task.performClearTaskAtIndexLocked(subtask.index);
        return subtask.activity;
    }

    public TaskAccessInfo getTaskAccessInfoLocked(TaskRecord task, boolean inclThumbs) {
        final TaskAccessInfo thumbs = new TaskAccessInfo();
        // How many different sub-thumbnails?
        final ArrayList<ActivityRecord> activities = task.mActivities;
        final int NA = activities.size();
        int j = 0;
        ThumbnailHolder holder = null;
        while (j < NA) {
            ActivityRecord ar = activities.get(j);
            if (!ar.finishing) {
                thumbs.root = ar;
                thumbs.rootIndex = j;
                holder = ar.thumbHolder;
                if (holder != null) {
                    thumbs.mainThumbnail = holder.lastThumbnail;
                }
                j++;
                break;
            }
            j++;
        }

        if (j >= NA) {
            return thumbs;
        }

        ArrayList<TaskAccessInfo.SubTask> subtasks = new ArrayList<TaskAccessInfo.SubTask>();
        thumbs.subtasks = subtasks;
        while (j < NA) {
            ActivityRecord ar = activities.get(j);
            j++;
            if (ar.finishing) {
                continue;
            }
            if (ar.thumbHolder != holder && holder != null) {
                thumbs.numSubThumbbails++;
                holder = ar.thumbHolder;
                TaskAccessInfo.SubTask sub = new TaskAccessInfo.SubTask();
                sub.holder = holder;
                sub.activity = ar;
                sub.index = j-1;
                subtasks.add(sub);
            }
        }
        if (thumbs.numSubThumbbails > 0) {
            thumbs.retriever = new IThumbnailRetriever.Stub() {
                @Override
                public Bitmap getThumbnail(int index) {
                    if (index < 0 || index >= thumbs.subtasks.size()) {
                        return null;
                    }
                    TaskAccessInfo.SubTask sub = thumbs.subtasks.get(index);
                    if (mResumedActivity != null && mResumedActivity.thumbHolder == sub.holder) {
                        return screenshotActivities(mResumedActivity);
                    }
                    return sub.holder.lastThumbnail;
                }
            };
        }
        return thumbs;
    }

    static final void logStartActivity(int tag, ActivityRecord r,
            TaskRecord task) {
        final Uri data = r.intent.getData();
        final String strData = data != null ? data.toSafeString() : null;

        EventLog.writeEvent(tag,
                r.userId, System.identityHashCode(r), task.taskId,
                r.shortComponentName, r.intent.getAction(),
                r.intent.getType(), strData, r.intent.getFlags());
    }

    /**
     * Make sure the given activity matches the current configuration.  Returns
     * false if the activity had to be destroyed.  Returns true if the
     * configuration is the same, or the activity will remain running as-is
     * for whatever reason.  Ensures the HistoryRecord is updated with the
     * correct configuration and all other bookkeeping is handled.
     */
    final boolean ensureActivityConfigurationLocked(ActivityRecord r,
            int globalChanges) {
        if (mConfigWillChange) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Skipping config check (will change): " + r);
            return true;
        }
        
        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                "Ensuring correct configuration: " + r);
        
        // Short circuit: if the two configurations are the exact same
        // object (the common case), then there is nothing to do.
        Configuration newConfig = mService.mConfiguration;
        if (r.configuration == newConfig && !r.forceNewConfig) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration unchanged in " + r);
            return true;
        }
        
        // We don't worry about activities that are finishing.
        if (r.finishing) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration doesn't matter in finishing " + r);
            r.stopFreezingScreenLocked(false);
            return true;
        }
        
        // Okay we now are going to make this activity have the new config.
        // But then we need to figure out how it needs to deal with that.
        Configuration oldConfig = r.configuration;
        r.configuration = newConfig;

        // Determine what has changed.  May be nothing, if this is a config
        // that has come back from the app after going idle.  In that case
        // we just want to leave the official config object now in the
        // activity and do nothing else.
        final int changes = oldConfig.diff(newConfig);
        if (changes == 0 && !r.forceNewConfig) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration no differences in " + r);
            return true;
        }

        // If the activity isn't currently running, just leave the new
        // configuration and it will pick that up next time it starts.
        if (r.app == null || r.app.thread == null) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration doesn't matter not running " + r);
            r.stopFreezingScreenLocked(false);
            r.forceNewConfig = false;
            return true;
        }
        
        // Figure out how to handle the changes between the configurations.
        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) {
            Slog.v(TAG, "Checking to restart " + r.info.name + ": changed=0x"
                    + Integer.toHexString(changes) + ", handles=0x"
                    + Integer.toHexString(r.info.getRealConfigChanged())
                    + ", newConfig=" + newConfig);
        }
        if ((changes&(~r.info.getRealConfigChanged())) != 0 || r.forceNewConfig) {
            // Aha, the activity isn't handling the change, so DIE DIE DIE.
            r.configChangeFlags |= changes;
            r.startFreezingScreenLocked(r.app, globalChanges);
            r.forceNewConfig = false;
            if (r.app == null || r.app.thread == null) {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Config is destroying non-running " + r);
                destroyActivityLocked(r, true, false, "config");
            } else if (r.state == ActivityState.PAUSING) {
                // A little annoying: we are waiting for this activity to
                // finish pausing.  Let's not do anything now, but just
                // flag that it needs to be restarted when done pausing.
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Config is skipping already pausing " + r);
                r.configDestroy = true;
                return true;
            } else if (r.state == ActivityState.RESUMED) {
                // Try to optimize this case: the configuration is changing
                // and we need to restart the top, resumed activity.
                // Instead of doing the normal handshaking, just say
                // "restart!".
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Config is relaunching resumed " + r);
                relaunchActivityLocked(r, r.configChangeFlags, true);
                r.configChangeFlags = 0;
            } else {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Config is relaunching non-resumed " + r);
                relaunchActivityLocked(r, r.configChangeFlags, false);
                r.configChangeFlags = 0;
            }
            
            // All done...  tell the caller we weren't able to keep this
            // activity around.
            return false;
        }
        
        // Default case: the activity can handle this new configuration, so
        // hand it over.  Note that we don't need to give it the new
        // configuration, since we always send configuration changes to all
        // process when they happen so it can just use whatever configuration
        // it last got.
        if (r.app != null && r.app.thread != null) {
            try {
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending new config to " + r);
                r.app.thread.scheduleActivityConfigurationChanged(r.appToken);
            } catch (RemoteException e) {
                // If process died, whatever.
            }
        }
        r.stopFreezingScreenLocked(false);
        
        return true;
    }

    private final boolean relaunchActivityLocked(ActivityRecord r,
            int changes, boolean andResume) {
        List<ResultInfo> results = null;
        List<Intent> newIntents = null;
        if (andResume) {
            results = r.results;
            newIntents = r.newIntents;
        }
        if (DEBUG_SWITCH) Slog.v(TAG, "Relaunching: " + r
                + " with results=" + results + " newIntents=" + newIntents
                + " andResume=" + andResume);
        EventLog.writeEvent(andResume ? EventLogTags.AM_RELAUNCH_RESUME_ACTIVITY
                : EventLogTags.AM_RELAUNCH_ACTIVITY, r.userId, System.identityHashCode(r),
                r.task.taskId, r.shortComponentName);
        
        r.startFreezingScreenLocked(r.app, 0);
        
        try {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG,
                    (andResume ? "Relaunching to RESUMED " : "Relaunching to PAUSED ")
                    + r);
            r.forceNewConfig = false;
            r.app.thread.scheduleRelaunchActivity(r.appToken, results, newIntents,
                    changes, !andResume, new Configuration(mService.mConfiguration));
            // Note: don't need to call pauseIfSleepingLocked() here, because
            // the caller will only pass in 'andResume' if this activity is
            // currently resumed, which implies we aren't sleeping.
        } catch (RemoteException e) {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG, "Relaunch failed", e);
        }

        if (andResume) {
            r.results = null;
            r.newIntents = null;
            if (mStackSupervisor.isFrontStack(this)) {
                mService.reportResumedActivityLocked(r);
            }
            r.state = ActivityState.RESUMED;
        } else {
            mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
            r.state = ActivityState.PAUSED;
        }

        return true;
    }

    boolean willActivityBeVisibleLocked(IBinder token) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.appToken == token) {
                        return true;
                }
                if (r.fullscreen && !r.finishing) {
                    return false;
                }
            }
        }
        return true;
    }

    void closeSystemDialogsLocked() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if ((r.info.flags&ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS) != 0) {
                    finishActivityLocked(r, Activity.RESULT_CANCELED, null, "close-sys", true);
                }
            }
        }
    }

    boolean forceStopPackageLocked(String name, boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        TaskRecord lastTask = null;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            int numActivities = activities.size();
            for (int activityNdx = 0; activityNdx < numActivities; ++activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                final boolean samePackage = r.packageName.equals(name)
                        || (name == null && r.userId == userId);
                if ((userId == UserHandle.USER_ALL || r.userId == userId)
                        && (samePackage || r.task == lastTask)
                        && (r.app == null || evenPersistent || !r.app.persistent)) {
                    if (!doit) {
                        if (r.finishing) {
                            // If this activity is just finishing, then it is not
                            // interesting as far as something to stop.
                            continue;
                        }
                        return true;
                    }
                    didSomething = true;
                    Slog.i(TAG, "  Force finishing activity " + r);
                    if (samePackage) {
                        if (r.app != null) {
                            r.app.removed = true;
                        }
                        r.app = null;
                    }
                    lastTask = r.task;
                    finishActivityLocked(r, Activity.RESULT_CANCELED, null, "force-stop", true);
                }
            }
        }
        return didSomething;
    }

    ActivityRecord getTasksLocked(int maxNum, IThumbnailReceiver receiver,
            PendingThumbnailsRecord pending, List<RunningTaskInfo> list) {
        ActivityRecord topRecord = null;
        for (int taskNdx = mTaskHistory.size() - 1; maxNum > 0 && taskNdx >= 0;
                --maxNum, --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            ActivityRecord r = null;
            ActivityRecord top = null;
            int numActivities = 0;
            int numRunning = 0;
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                r = activities.get(activityNdx);

                // Initialize state for next task if needed.
                if (top == null || (top.state == ActivityState.INITIALIZING)) {
                    top = r;
                    numActivities = numRunning = 0;
                }

                // Add 'r' into the current task.
                numActivities++;
                if (r.app != null && r.app.thread != null) {
                    numRunning++;
                }

                if (localLOGV) Slog.v(
                    TAG, r.intent.getComponent().flattenToShortString()
                    + ": task=" + r.task);
            }

            RunningTaskInfo ci = new RunningTaskInfo();
            ci.id = task.taskId;
            ci.baseActivity = r.intent.getComponent();
            ci.topActivity = top.intent.getComponent();
            if (top.thumbHolder != null) {
                ci.description = top.thumbHolder.lastDescription;
            }
            ci.numActivities = numActivities;
            ci.numRunning = numRunning;
            //System.out.println(
            //    "#" + maxNum + ": " + " descr=" + ci.description);
            if (receiver != null) {
                if (localLOGV) Slog.v(
                    TAG, "State=" + top.state + "Idle=" + top.idle
                    + " app=" + top.app
                    + " thr=" + (top.app != null ? top.app.thread : null));
                if (top.state == ActivityState.RESUMED || top.state == ActivityState.PAUSING) {
                    if (top.idle && top.app != null && top.app.thread != null) {
                        topRecord = top;
                    } else {
                        top.thumbnailNeeded = true;
                    }
                }
                pending.pendingRecords.add(top);
            }
            list.add(ci);
        }
        return topRecord;
    }

    public void unhandledBackLocked() {
        final int top = mTaskHistory.size() - 1;
        if (DEBUG_SWITCH) Slog.d(
            TAG, "Performing unhandledBack(): top activity at " + top);
        if (top >= 0) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(top).mActivities;
            int activityTop = activities.size() - 1;
            if (activityTop > 0) {
                finishActivityLocked(activities.get(activityTop), Activity.RESULT_CANCELED, null,
                        "unhandled-back", true);
            }
        }
    }

    void handleAppDiedLocked(ProcessRecord app, boolean restarting) {
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE || DEBUG_CLEANUP) Slog.v(TAG,
                    "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
        }

        // Remove this application's activities from active lists.
        boolean hasVisibleActivities = removeHistoryRecordsForAppLocked(app);

        if (!restarting) {
            if (!mStackSupervisor.getTopStack().resumeTopActivityLocked(null)) {
                // If there was nothing to resume, and we are not already
                // restarting this process, but there is a visible activity that
                // is hosted by the process...  then make sure all visible
                // activities are running, taking care of restarting this
                // process.
                if (hasVisibleActivities) {
                    ensureActivitiesVisibleLocked(null, 0);
                }
            }
        }
    }

    void handleAppCrashLocked(ProcessRecord app) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.app == app) {
                    Slog.w(TAG, "  Force finishing activity "
                            + r.intent.getComponent().flattenToShortString());
                    finishActivityLocked(r, Activity.RESULT_CANCELED, null, "crashed", false);
                }
            }
        }
    }

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            pw.print("  Task "); pw.print(taskNdx); pw.print(": id #"); pw.println(task.taskId);
            ActivityStackSupervisor.dumpHistoryList(fd, pw, mTaskHistory.get(taskNdx).mActivities,
                "    ", "Hist", true, !dumpAll, dumpClient, dumpPackage);
        }
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>();

        if ("all".equals(name)) {
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                activities.addAll(mTaskHistory.get(taskNdx).mActivities);
            }
        } else if ("top".equals(name)) {
            final int top = mTaskHistory.size() - 1;
            if (top >= 0) {
                final ArrayList<ActivityRecord> list = mTaskHistory.get(top).mActivities;
                int listTop = list.size() - 1;
                if (listTop >= 0) {
                    activities.add(list.get(listTop));
                }
            }
        } else {
            ItemMatcher matcher = new ItemMatcher();
            matcher.build(name);

            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                for (ActivityRecord r1 : mTaskHistory.get(taskNdx).mActivities) {
                    if (matcher.match(r1, r1.intent.getComponent())) {
                        activities.add(r1);
                    }
                }
            }
        }

        return activities;
    }

    ActivityRecord restartPackage(String packageName) {
        ActivityRecord starting = topRunningActivityLocked(null);

        // All activities that came from the package must be
        // restarted as if there was a config change.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord a = activities.get(activityNdx);
                if (a.info.packageName.equals(packageName)) {
                    a.forceNewConfig = true;
                    if (starting != null && a == starting && a.visible) {
                        a.startFreezingScreenLocked(starting.app,
                                ActivityInfo.CONFIG_SCREEN_LAYOUT);
                    }
                }
            }
        }

        return starting;
    }

    boolean removeTask(TaskRecord task) {
        mTaskHistory.remove(task);
        return mTaskHistory.size() == 0;
    }

    TaskRecord createTaskRecord(int taskId, ActivityInfo info, Intent intent, boolean toTop) {
        TaskRecord task = new TaskRecord(taskId, info, intent, this);
        if (toTop) {
            mTaskHistory.add(task);
        } else {
            mTaskHistory.add(0, task);
        }
        return task;
    }

    ArrayList<TaskRecord> getAllTasks() {
        return new ArrayList<TaskRecord>(mTaskHistory);
    }

    void moveTask(int taskId, boolean toTop) {
        final TaskRecord task = mStackSupervisor.anyTaskForIdLocked(taskId);
        if (task == null) {
            return;
        }
        task.stack.mTaskHistory.remove(task);
        task.stack = this;
        if (toTop) {
            mTaskHistory.add(task);
        } else {
            mTaskHistory.add(0, task);
        }
    }

    public int getStackId() {
        return mStackId;
    }

    @Override
    public String toString() {
        return "stackId=" + mStackId + " tasks=" + mTaskHistory;
    }
}
