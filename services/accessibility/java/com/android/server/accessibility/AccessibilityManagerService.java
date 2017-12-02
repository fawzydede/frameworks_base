/*
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.IFingerprintService;
import android.media.AudioManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.provider.SettingsStringUtil;
import android.provider.SettingsStringUtil.ComponentNameSet;
import android.provider.SettingsStringUtil.SettingStringHelper;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.View;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityCache;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IntPair;
import com.android.server.LocalServices;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.AccessibilityShortcutController.ToggleableFrameworkFeatureInfo;
import com.android.server.wm.WindowManagerInternal;

import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * This class is instantiated by the system as a system level service and can be
 * accessed only by the system. The task of this service is to be a centralized
 * event dispatch for {@link AccessibilityEvent}s generated across all processes
 * on the device. Events are dispatched to {@link AccessibilityService}s.
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub
        implements AccessibilityClientConnection.SystemSupport {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    private static final int WAIT_WINDOWS_TIMEOUT_MILLIS = 5000;

    // TODO: Restructure service initialization so services aren't connected before all of
    //       their capabilities are ready.
    private static final int WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS = 1000;

    private static final String FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE =
        "registerUiTestAutomationService";

    private static final String TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED =
            "temporaryEnableAccessibilityStateUntilKeyguardRemoved";

    private static final String GET_WINDOW_TOKEN = "getWindowToken";

    private static final String SET_PIP_ACTION_REPLACEMENT =
            "setPictureInPictureActionReplacingConnection";

    private static final String FUNCTION_DUMP = "dump";

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    // Each service has an ID. Also provide one for magnification gesture handling
    public static final int MAGNIFICATION_GESTURE_HANDLER_ID = 0;

    private static int sIdCounter = MAGNIFICATION_GESTURE_HANDLER_ID + 1;

    private static int sNextWindowId;

    private final Context mContext;

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final Rect mTempRect = new Rect();

    private final Rect mTempRect1 = new Rect();

    private final Point mTempPoint = new Point();

    private final PackageManager mPackageManager;

    private final PowerManager mPowerManager;

    private final WindowManagerInternal mWindowManagerService;

    private AppWidgetManagerInternal mAppWidgetService;

    private final SecurityPolicy mSecurityPolicy;

    private final MainHandler mMainHandler;

    private final GlobalActionPerformer mGlobalActionPerformer;

    private MagnificationController mMagnificationController;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private KeyEventDispatcher mKeyEventDispatcher;

    private MotionEventInjector mMotionEventInjector;

    private FingerprintGestureDispatcher mFingerprintGestureDispatcher;

    private final Set<ComponentName> mTempComponentNameSet = new HashSet<>();

    private final List<AccessibilityServiceInfo> mTempAccessibilityServiceInfoList =
            new ArrayList<>();

    private final IntArray mTempIntArray = new IntArray(0);

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<>();

    private final SparseArray<RemoteAccessibilityConnection> mGlobalInteractionConnections =
            new SparseArray<>();

    private RemoteAccessibilityConnection mPictureInPictureActionReplacingConnection;

    private final SparseArray<IBinder> mGlobalWindowTokens = new SparseArray<>();

    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    private final UserManager mUserManager;

    private final UiAutomationManager mUiAutomationManager = new UiAutomationManager();

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    //TODO: Remove this hack
    private boolean mInitialized;

    private WindowsForAccessibilityCallback mWindowsForAccessibilityCallback;

    private boolean mIsAccessibilityButtonShown;

    private UserState getCurrentUserStateLocked() {
        return getUserStateLocked(mCurrentUserId);
    }

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mSecurityPolicy = new SecurityPolicy();
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mGlobalActionPerformer = new GlobalActionPerformer(mContext, mWindowManagerService);

        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                context.getContentResolver());
    }

    @Override
    public int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    @Override
    public boolean isAccessibilityButtonShown() {
        return mIsAccessibilityButtonShown;
    }

    @Nullable
    public FingerprintGestureDispatcher getFingerprintGestureDispatcher() {
        return mFingerprintGestureDispatcher;
    }

    private UserState getUserStateLocked(int userId) {
        UserState state = mUserStates.get(userId);
        if (state == null) {
            state = new UserState(userId);
            mUserStates.put(userId, state);
        }
        return state;
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (getChangingUserId() != mCurrentUserId) {
                        return;
                    }
                    // We will update when the automation service dies.
                    UserState userState = getCurrentUserStateLocked();
                    // We have to reload the installed services since some services may
                    // have different attributes, resolve info (does not support equals),
                    // etc. Remove them then to force reload.
                    userState.mInstalledServices.clear();
                    if (readConfigurationForUserStateLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                // Unbind all services from this package, and then update the user state to
                // re-bind new versions of them.
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    UserState userState = getUserStateLocked(userId);
                    boolean unboundAService = false;
                    for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                        AccessibilityServiceConnection boundService =
                                userState.mBoundServices.get(i);
                        String servicePkg = boundService.mComponentName.getPackageName();
                        if (servicePkg.equals(packageName)) {
                            boundService.unbindLocked();
                            unboundAService = true;
                        }
                    }
                    if (unboundAService) {
                        onUserStateChangedLocked(userState);
                    }
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    UserState userState = getUserStateLocked(userId);
                    Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        if (compPkg.equals(packageName)) {
                            it.remove();
                            // Update the enabled services setting.
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                    userState.mEnabledServices, userId);
                            // Update the touch exploration granted services setting.
                            userState.mTouchExplorationGrantedServices.remove(comp);
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.
                                    TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                    userState.mTouchExplorationGrantedServices, userId);
                            onUserStateChangedLocked(userState);
                            return;
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (userId != mCurrentUserId) {
                        return false;
                    }
                    UserState userState = getUserStateLocked(userId);
                    Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        for (String pkg : packages) {
                            if (compPkg.equals(pkg)) {
                                if (!doit) {
                                    return true;
                                }
                                it.remove();
                                persistComponentNamesToSettingLocked(
                                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                        userState.mEnabledServices, userId);
                                onUserStateChangedLocked(userState);
                            }
                        }
                    }
                    return false;
                }
            }
        };

        // package changes
        monitor.register(mContext, null,  UserHandle.ALL, true);

        // user change and unlock
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(Intent.ACTION_SETTING_RESTORED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    unlockUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    // We will update when the automation service dies.
                    synchronized (mLock) {
                        UserState userState = getCurrentUserStateLocked();
                        if (readConfigurationForUserStateLocked(userState)) {
                            onUserStateChangedLocked(userState);
                        }
                    }
                } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                    final String which = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(which)) {
                        synchronized (mLock) {
                            restoreEnabledAccessibilityServicesLocked(
                                    intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE),
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE));
                        }
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    @Override
    public long addClient(IAccessibilityManagerClient client, int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // If the client is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            UserState userState = getUserStateLocked(resolvedUserId);
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                mGlobalClients.register(client);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global client for pid:" + Binder.getCallingPid());
                }
                return IntPair.of(
                        userState.getClientState(), userState.mLastSentRelevantEventTypes);
            } else {
                userState.mUserClients.register(client);
                // If this client is not for the current user we do not
                // return a state since it is not for the foreground user.
                // We will send the state to the client on a user switch.
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + mCurrentUserId);
                }
                return IntPair.of(
                        (resolvedUserId == mCurrentUserId) ? userState.getClientState() : 0,
                        userState.mLastSentRelevantEventTypes);
            }
        }
    }

    @Override
    public void sendAccessibilityEvent(AccessibilityEvent event, int userId) {
        boolean dispatchEvent = false;

        synchronized (mLock) {
            if (event.getWindowId() ==
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID) {
                // The replacer window isn't shown to services. Move its events into the pip.
                AccessibilityWindowInfo pip = mSecurityPolicy.getPictureInPictureWindow();
                if (pip != null) {
                    int pipId = pip.getId();
                    event.setWindowId(pipId);
                }
            }

            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // Make sure the reported package is one the caller has access to.
            event.setPackageName(mSecurityPolicy.resolveValidReportedPackageLocked(
                    event.getPackageName(), UserHandle.getCallingAppId(), resolvedUserId));

            // This method does nothing for a background user.
            if (resolvedUserId == mCurrentUserId) {
                if (mSecurityPolicy.canDispatchAccessibilityEventLocked(event)) {
                    mSecurityPolicy.updateActiveAndAccessibilityFocusedWindowLocked(
                            event.getWindowId(), event.getSourceNodeId(),
                            event.getEventType(), event.getAction());
                    mSecurityPolicy.updateEventSourceLocked(event);
                    dispatchEvent = true;
                }
                if (mHasInputFilter && mInputFilter != null) {
                    mMainHandler.obtainMessage(
                            MainHandler.MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER,
                            AccessibilityEvent.obtain(event)).sendToTarget();
                }
            }
        }

        if (dispatchEvent) {
            // Make sure clients receiving this event will be able to get the
            // current state of the windows as the window manager may be delaying
            // the computation for performance reasons.
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && mWindowsForAccessibilityCallback != null) {
                WindowManagerInternal wm = LocalServices.getService(WindowManagerInternal.class);
                wm.computeWindowsForAccessibility();
            }
            synchronized (mLock) {
                notifyAccessibilityServicesDelayedLocked(event, false);
                notifyAccessibilityServicesDelayedLocked(event, true);
                mUiAutomationManager.sendAccessibilityEventLocked(event);
            }
        }

        if (OWN_PROCESS_ID != Binder.getCallingPid()) {
            event.recycle();
        }
    }

    @Override
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            return getUserStateLocked(resolvedUserId).mInstalledServices;
        }
    }

    @Override
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType,
            int userId) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // The automation service can suppress other services.
            final UserState userState = getUserStateLocked(resolvedUserId);
            if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                return Collections.emptyList();
            }

            final List<AccessibilityServiceConnection> services = userState.mBoundServices;
            final int serviceCount = services.size();
            final List<AccessibilityServiceInfo> result = new ArrayList<>(serviceCount);
            for (int i = 0; i < serviceCount; ++i) {
                final AccessibilityServiceConnection service = services.get(i);
                if ((service.mFeedbackType & feedbackType) != 0) {
                    result.add(service.mAccessibilityServiceInfo);
                }
            }
            return result;
        }
    }

    @Override
    public void interrupt(int userId) {
        List<IAccessibilityServiceClient> interfacesToInterrupt;
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return;
            }
            List<AccessibilityServiceConnection> services =
                    getUserStateLocked(resolvedUserId).mBoundServices;
            int numServices = services.size();
            interfacesToInterrupt = new ArrayList<>(numServices);
            for (int i = 0; i < numServices; i++) {
                AccessibilityServiceConnection service = services.get(i);
                IBinder a11yServiceBinder = service.mService;
                IAccessibilityServiceClient a11yServiceInterface = service.mServiceInterface;
                if ((a11yServiceBinder != null) && (a11yServiceInterface != null)) {
                    interfacesToInterrupt.add(a11yServiceInterface);
                }
            }
        }
        for (int i = 0, count = interfacesToInterrupt.size(); i < count; i++) {
            try {
                interfacesToInterrupt.get(i).onInterrupt();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending interrupt request to "
                        + interfacesToInterrupt.get(i), re);
            }
        }
    }

    @Override
    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection, String packageName,
            int userId) throws RemoteException {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            final int resolvedUid = UserHandle.getUid(resolvedUserId, UserHandle.getCallingAppId());

            // Make sure the reported package is one the caller has access to.
            packageName = mSecurityPolicy.resolveValidReportedPackageLocked(
                    packageName, UserHandle.getCallingAppId(), resolvedUserId);

            final int windowId = sNextWindowId++;
            // If the window is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, UserHandle.USER_ALL);
                wrapper.linkToDeath();
                mGlobalInteractionConnections.put(windowId, wrapper);
                mGlobalWindowTokens.put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and  token: "
                            + windowToken.asBinder());
                }
            } else {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, resolvedUserId);
                wrapper.linkToDeath();
                UserState userState = getUserStateLocked(resolvedUserId);
                userState.mInteractionConnections.put(windowId, wrapper);
                userState.mWindowTokens.put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and userId:" + mCurrentUserId
                            + " and  token: " + windowToken.asBinder());
                }
            }
            return windowId;
        }
    }

    @Override
    public void removeAccessibilityInteractionConnection(IWindow window) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                    UserHandle.getCallingUserId());
            IBinder token = window.asBinder();
            final int removedWindowId = removeAccessibilityInteractionConnectionInternalLocked(
                    token, mGlobalWindowTokens, mGlobalInteractionConnections);
            if (removedWindowId >= 0) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Removed global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + removedWindowId + " and token: " + window.asBinder());
                }
                return;
            }
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                UserState userState = mUserStates.valueAt(i);
                final int removedWindowIdForUser =
                        removeAccessibilityInteractionConnectionInternalLocked(
                        token, userState.mWindowTokens, userState.mInteractionConnections);
                if (removedWindowIdForUser >= 0) {
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Removed user connection for pid:" + Binder.getCallingPid()
                                + " with windowId: " + removedWindowIdForUser + " and userId:"
                                + mUserStates.keyAt(i) + " and token: " + window.asBinder());
                    }
                    return;
                }
            }
        }
    }

    private int removeAccessibilityInteractionConnectionInternalLocked(IBinder windowToken,
            SparseArray<IBinder> windowTokens,
            SparseArray<RemoteAccessibilityConnection> interactionConnections) {
        final int count = windowTokens.size();
        for (int i = 0; i < count; i++) {
            if (windowTokens.valueAt(i) == windowToken) {
                final int windowId = windowTokens.keyAt(i);
                windowTokens.removeAt(i);
                RemoteAccessibilityConnection wrapper = interactionConnections.get(windowId);
                wrapper.unlinkToDeath();
                interactionConnections.remove(windowId);
                return windowId;
            }
        }
        return -1;
    }

    @Override
    public void setPictureInPictureActionReplacingConnection(
            IAccessibilityInteractionConnection connection) throws RemoteException {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.MODIFY_ACCESSIBILITY_DATA,
                SET_PIP_ACTION_REPLACEMENT);
        synchronized (mLock) {
            if (mPictureInPictureActionReplacingConnection != null) {
                mPictureInPictureActionReplacingConnection.unlinkToDeath();
                mPictureInPictureActionReplacingConnection = null;
            }
            if (connection != null) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID,
                        connection, "foo.bar.baz", Process.SYSTEM_UID, UserHandle.USER_ALL);
                mPictureInPictureActionReplacingConnection = wrapper;
                wrapper.linkToDeath();
            }
            mSecurityPolicy.notifyWindowsChanged();
        }
    }

    @Override
    public void registerUiTestAutomationService(IBinder owner,
            IAccessibilityServiceClient serviceClient,
            AccessibilityServiceInfo accessibilityServiceInfo,
            int flags) {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.RETRIEVE_WINDOW_CONTENT,
                FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE);

        synchronized (mLock) {
            mUiAutomationManager.registerUiTestAutomationServiceLocked(owner, serviceClient,
                    mContext, accessibilityServiceInfo, sIdCounter++, mMainHandler, mLock,
                    mSecurityPolicy, this, mWindowManagerService, mGlobalActionPerformer, flags);
            onUserStateChangedLocked(getCurrentUserStateLocked());
        }
    }

    @Override
    public void unregisterUiTestAutomationService(IAccessibilityServiceClient serviceClient) {
        synchronized (mLock) {
            mUiAutomationManager.unregisterUiTestAutomationServiceLocked(serviceClient);
            onUserStateChangedLocked(getCurrentUserStateLocked());
        }
    }

    @Override
    public void temporaryEnableAccessibilityStateUntilKeyguardRemoved(
            ComponentName service, boolean touchExplorationEnabled) {
        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.TEMPORARY_ENABLE_ACCESSIBILITY,
                TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED);
        if (!mWindowManagerService.isKeyguardLocked()) {
            return;
        }
        synchronized (mLock) {
            // Set the temporary state.
            UserState userState = getCurrentUserStateLocked();

            userState.mIsTouchExplorationEnabled = touchExplorationEnabled;
            userState.mIsDisplayMagnificationEnabled = false;
            userState.mIsNavBarMagnificationEnabled = false;
            userState.mIsAutoclickEnabled = false;
            userState.mEnabledServices.clear();
            userState.mEnabledServices.add(service);
            userState.mBindingServices.clear();
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.add(service);

            // User the current state instead settings.
            onUserStateChangedLocked(userState);
        }
    }

    @Override
    public IBinder getWindowToken(int windowId, int userId) {
        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.RETRIEVE_WINDOW_TOKEN,
                GET_WINDOW_TOKEN);
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            if (resolvedUserId != mCurrentUserId) {
                return null;
            }
            if (mSecurityPolicy.findA11yWindowInfoById(windowId) == null) {
                return null;
            }
            IBinder token = mGlobalWindowTokens.get(windowId);
            if (token != null) {
                return token;
            }
            return getCurrentUserStateLocked().mWindowTokens.get(windowId);
        }
    }

    /**
     * Invoked remotely over AIDL by SysUi when the accessibility button within the system's
     * navigation area has been clicked.
     */
    @Override
    public void notifyAccessibilityButtonClicked() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR_SERVICE);
        }
        synchronized (mLock) {
            notifyAccessibilityButtonClickedLocked();
        }
    }

    /**
     * Invoked remotely over AIDL by SysUi when the visibility of the accessibility
     * button within the system's navigation area has changed.
     *
     * @param shown {@code true} if the accessibility button is shown to the
     *                  user, {@code false} otherwise
     */
    @Override
    public void notifyAccessibilityButtonVisibilityChanged(boolean shown) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR_SERVICE);
        }
        synchronized (mLock) {
            notifyAccessibilityButtonVisibilityChangedLocked(shown);
        }
    }


    boolean onGesture(int gestureId) {
        synchronized (mLock) {
            boolean handled = notifyGestureLocked(gestureId, false);
            if (!handled) {
                handled = notifyGestureLocked(gestureId, true);
            }
            return handled;
        }
    }

    @VisibleForTesting
    public boolean notifyKeyEvent(KeyEvent event, int policyFlags) {
        synchronized (mLock) {
            List<AccessibilityServiceConnection> boundServices =
                    getCurrentUserStateLocked().mBoundServices;
            if (boundServices.isEmpty()) {
                return false;
            }
            return getKeyEventDispatcher().notifyKeyEventLocked(event, policyFlags, boundServices);
        }
    }

    /**
     * Called by the MagnificationController when the state of display
     * magnification changes.
     *
     * @param region the new magnified region, may be empty if
     *               magnification is not enabled (e.g. scale is 1)
     * @param scale the new scale
     * @param centerX the new screen-relative center X coordinate
     * @param centerY the new screen-relative center Y coordinate
     */
    public void notifyMagnificationChanged(@NonNull Region region,
            float scale, float centerX, float centerY) {
        synchronized (mLock) {
            notifyClearAccessibilityCacheLocked();
            notifyMagnificationChangedLocked(region, scale, centerX, centerY);
        }
    }

    /**
     * Called by AccessibilityInputFilter when it creates or destroys the motionEventInjector.
     * Not using a getter because the AccessibilityInputFilter isn't thread-safe
     *
     * @param motionEventInjector The new value of the motionEventInjector. May be null.
     */
    void setMotionEventInjector(MotionEventInjector motionEventInjector) {
        synchronized (mLock) {
            mMotionEventInjector = motionEventInjector;
            // We may be waiting on this object being set
            mLock.notifyAll();
        }
    }

    @Override
    public MotionEventInjector getMotionEventInjectorLocked() {
        final long endMillis = SystemClock.uptimeMillis() + WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS;
        while ((mMotionEventInjector == null) && (SystemClock.uptimeMillis() < endMillis)) {
            try {
                mLock.wait(endMillis - SystemClock.uptimeMillis());
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
        if (mMotionEventInjector == null) {
            Slog.e(LOG_TAG, "MotionEventInjector installation timed out");
        }
        return mMotionEventInjector;
    }

    /**
     * Gets a point within the accessibility focused node where we can send down
     * and up events to perform a click.
     *
     * @param outPoint The click point to populate.
     * @return Whether accessibility a click point was found and set.
     */
    // TODO: (multi-display) Make sure this works for multiple displays.
    boolean getAccessibilityFocusClickPointInScreen(Point outPoint) {
        return getInteractionBridge().getAccessibilityFocusClickPointInScreenNotLocked(outPoint);
    }

    /**
     * Perform an accessibility action on the view that currently has accessibility focus.
     * Has no effect if no item has accessibility focus, if the item with accessibility
     * focus does not expose the specified action, or if the action fails.
     *
     * @param actionId The id of the action to perform.
     *
     * @return {@code true} if the action was performed. {@code false} if it was not.
     */
    public boolean performActionOnAccessibilityFocusedItem(
            AccessibilityNodeInfo.AccessibilityAction action) {
        return getInteractionBridge().performActionOnAccessibilityFocusedItemNotLocked(action);
    }

    /**
     * Gets the bounds of a window.
     *
     * @param outBounds The output to which to write the bounds.
     */
    boolean getWindowBounds(int windowId, Rect outBounds) {
        IBinder token;
        synchronized (mLock) {
            token = mGlobalWindowTokens.get(windowId);
            if (token == null) {
                token = getCurrentUserStateLocked().mWindowTokens.get(windowId);
            }
        }
        mWindowManagerService.getWindowFrame(token, outBounds);
        if (!outBounds.isEmpty()) {
            return true;
        }
        return false;
    }

    boolean accessibilityFocusOnlyInActiveWindow() {
        synchronized (mLock) {
            return mWindowsForAccessibilityCallback == null;
        }
    }

    int getActiveWindowId() {
        return mSecurityPolicy.getActiveWindowId();
    }

    void onTouchInteractionStart() {
        mSecurityPolicy.onTouchInteractionStart();
    }

    void onTouchInteractionEnd() {
        mSecurityPolicy.onTouchInteractionEnd();
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId && mInitialized) {
                return;
            }

            // Disconnect from services for the old user.
            UserState oldUserState = getCurrentUserStateLocked();
            oldUserState.onSwitchToAnotherUser();

            // Disable the local managers for the old user.
            if (oldUserState.mUserClients.getRegisteredCallbackCount() > 0) {
                mMainHandler.obtainMessage(MainHandler.MSG_SEND_CLEARED_STATE_TO_CLIENTS_FOR_USER,
                        oldUserState.mUserId, 0).sendToTarget();
            }

            // Announce user changes only if more that one exist.
            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            final boolean announceNewUser = userManager.getUsers().size() > 1;

            // The user changed.
            mCurrentUserId = userId;

            UserState userState = getCurrentUserStateLocked();

            readConfigurationForUserStateLocked(userState);
            // Even if reading did not yield change, we have to update
            // the state since the context in which the current user
            // state was used has changed since it was inactive.
            onUserStateChangedLocked(userState);

            if (announceNewUser) {
                // Schedule announcement of the current user if needed.
                mMainHandler.sendEmptyMessageDelayed(MainHandler.MSG_ANNOUNCE_NEW_USER_IF_NEEDED,
                        WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS);
            }
        }
    }

    private void unlockUser(int userId) {
        synchronized (mLock) {
            int parentUserId = mSecurityPolicy.resolveProfileParentLocked(userId);
            if (parentUserId == mCurrentUserId) {
                UserState userState = getUserStateLocked(mCurrentUserId);
                onUserStateChangedLocked(userState);
            }
        }
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            mUserStates.remove(userId);
        }
    }

    // Called only during settings restore; currently supports only the owner user
    // TODO: http://b/22388012
    void restoreEnabledAccessibilityServicesLocked(String oldSetting, String newSetting) {
        readComponentNamesFromStringLocked(oldSetting, mTempComponentNameSet, false);
        readComponentNamesFromStringLocked(newSetting, mTempComponentNameSet, true);

        UserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
        userState.mEnabledServices.clear();
        userState.mEnabledServices.addAll(mTempComponentNameSet);
        persistComponentNamesToSettingLocked(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mEnabledServices,
                UserHandle.USER_SYSTEM);
        onUserStateChangedLocked(userState);
    }

    private InteractionBridge getInteractionBridge() {
        synchronized (mLock) {
            if (mInteractionBridge == null) {
                mInteractionBridge = new InteractionBridge();
            }
            return mInteractionBridge;
        }
    }

    private boolean notifyGestureLocked(int gestureId, boolean isDefault) {
        // TODO: Now we are giving the gestures to the last enabled
        //       service that can handle them which is the last one
        //       in our list since we write the last enabled as the
        //       last record in the enabled services setting. Ideally,
        //       the user should make the call which service handles
        //       gestures. However, only one service should handle
        //       gestures to avoid user frustration when different
        //       behavior is observed from different combinations of
        //       enabled accessibility services.
        UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            if (service.mRequestTouchExplorationMode && service.mIsDefault == isDefault) {
                service.notifyGesture(gestureId);
                return true;
            }
        }
        return false;
    }

    private void notifyClearAccessibilityCacheLocked() {
        UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyClearAccessibilityNodeInfoCache();
        }
    }

    private void notifyMagnificationChangedLocked(@NonNull Region region,
            float scale, float centerX, float centerY) {
        final UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyMagnificationChangedLocked(region, scale, centerX, centerY);
        }
    }

    private void notifySoftKeyboardShowModeChangedLocked(int showMode) {
        final UserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifySoftKeyboardShowModeChangedLocked(showMode);
        }
    }

    private void notifyAccessibilityButtonClickedLocked() {
        final UserState state = getCurrentUserStateLocked();

        int potentialTargets = state.mIsNavBarMagnificationEnabled ? 1 : 0;
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                potentialTargets++;
            }
        }

        if (potentialTargets == 0) {
            return;
        }
        if (potentialTargets == 1) {
            if (state.mIsNavBarMagnificationEnabled) {
                mMainHandler.obtainMessage(
                        MainHandler.MSG_SEND_ACCESSIBILITY_BUTTON_TO_INPUT_FILTER).sendToTarget();
                return;
            } else {
                for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                    final AccessibilityServiceConnection service = state.mBoundServices.get(i);
                    if (service.mRequestAccessibilityButton) {
                        service.notifyAccessibilityButtonClickedLocked();
                        return;
                    }
                }
            }
        } else {
            if (state.mServiceAssignedToAccessibilityButton == null
                    && !state.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                mMainHandler.obtainMessage(
                        MainHandler.MSG_SHOW_ACCESSIBILITY_BUTTON_CHOOSER).sendToTarget();
            } else if (state.mIsNavBarMagnificationEnabled
                    && state.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                mMainHandler.obtainMessage(
                        MainHandler.MSG_SEND_ACCESSIBILITY_BUTTON_TO_INPUT_FILTER).sendToTarget();
                return;
            } else {
                for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                    final AccessibilityServiceConnection service = state.mBoundServices.get(i);
                    if (service.mRequestAccessibilityButton && (service.mComponentName.equals(
                            state.mServiceAssignedToAccessibilityButton))) {
                        service.notifyAccessibilityButtonClickedLocked();
                        return;
                    }
                }
            }
            // The user may have turned off the assigned service or feature
            mMainHandler.obtainMessage(
                    MainHandler.MSG_SHOW_ACCESSIBILITY_BUTTON_CHOOSER).sendToTarget();
        }
    }

    private void notifyAccessibilityButtonVisibilityChangedLocked(boolean available) {
        final UserState state = getCurrentUserStateLocked();
        mIsAccessibilityButtonShown = available;
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection clientConnection = state.mBoundServices.get(i);
            if (clientConnection.mRequestAccessibilityButton) {
                clientConnection.notifyAccessibilityButtonAvailabilityChangedLocked(
                        clientConnection.isAccessibilityButtonAvailableLocked(state));
            }
        }
    }

    /**
     * Removes an AccessibilityInteractionConnection.
     *
     * @param windowId The id of the window to which the connection is targeted.
     * @param userId The id of the user owning the connection. UserHandle.USER_ALL
     *     if global.
     */
    private void removeAccessibilityInteractionConnectionLocked(int windowId, int userId) {
        if (userId == UserHandle.USER_ALL) {
            mGlobalWindowTokens.remove(windowId);
            mGlobalInteractionConnections.remove(windowId);
        } else {
            UserState userState = getCurrentUserStateLocked();
            userState.mWindowTokens.remove(windowId);
            userState.mInteractionConnections.remove(windowId);
        }
        if (DEBUG) {
            Slog.i(LOG_TAG, "Removing interaction connection to windowId: " + windowId);
        }
    }

    private boolean readInstalledAccessibilityServiceLocked(UserState userState) {
        mTempAccessibilityServiceInfoList.clear();

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServicesAsUser(
                new Intent(AccessibilityService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES
                        | PackageManager.GET_META_DATA
                        | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                mCurrentUserId);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ResolveInfo resolveInfo = installedServices.get(i);
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (!android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(
                    serviceInfo.permission)) {
                Slog.w(LOG_TAG, "Skipping accessibilty service " + new ComponentName(
                        serviceInfo.packageName, serviceInfo.name).flattenToShortString()
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE);
                continue;
            }
            AccessibilityServiceInfo accessibilityServiceInfo;
            try {
                accessibilityServiceInfo = new AccessibilityServiceInfo(resolveInfo, mContext);
                mTempAccessibilityServiceInfoList.add(accessibilityServiceInfo);
            } catch (XmlPullParserException | IOException xppe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", xppe);
            }
        }

        if (!mTempAccessibilityServiceInfoList.equals(userState.mInstalledServices)) {
            userState.mInstalledServices.clear();
            userState.mInstalledServices.addAll(mTempAccessibilityServiceInfoList);
            mTempAccessibilityServiceInfoList.clear();
            return true;
        }

        mTempAccessibilityServiceInfoList.clear();
        return false;
    }

    private boolean readEnabledAccessibilityServicesLocked(UserState userState) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mUserId, mTempComponentNameSet);
        if (!mTempComponentNameSet.equals(userState.mEnabledServices)) {
            userState.mEnabledServices.clear();
            userState.mEnabledServices.addAll(mTempComponentNameSet);
            mTempComponentNameSet.clear();
            return true;
        }
        mTempComponentNameSet.clear();
        return false;
    }

    private boolean readTouchExplorationGrantedAccessibilityServicesLocked(
            UserState userState) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(
                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                userState.mUserId, mTempComponentNameSet);
        if (!mTempComponentNameSet.equals(userState.mTouchExplorationGrantedServices)) {
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.addAll(mTempComponentNameSet);
            mTempComponentNameSet.clear();
            return true;
        }
        mTempComponentNameSet.clear();
        return false;
    }

    /**
     * Performs {@link AccessibilityService}s delayed notification. The delay is configurable
     * and denotes the period after the last event before notifying the service.
     *
     * @param event The event.
     * @param isDefault True to notify default listeners, not default services.
     */
    private void notifyAccessibilityServicesDelayedLocked(AccessibilityEvent event,
            boolean isDefault) {
        try {
            UserState state = getCurrentUserStateLocked();
            for (int i = 0, count = state.mBoundServices.size(); i < count; i++) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);

                if (service.mIsDefault == isDefault) {
                    service.notifyAccessibilityEvent(event);
                }
            }
        } catch (IndexOutOfBoundsException oobe) {
            // An out of bounds exception can happen if services are going away
            // as the for loop is running. If that happens, just bail because
            // there are no more services to notify.
        }
    }

    private void updateRelevantEventsLocked(UserState userState) {
        int relevantEventTypes = AccessibilityCache.CACHE_CRITICAL_EVENTS_MASK;
        for (AccessibilityServiceConnection service : userState.mBoundServices) {
            relevantEventTypes |= service.mEventTypes;
        }
        relevantEventTypes |= mUiAutomationManager.getRequestedEventMaskLocked();
        int finalRelevantEventTypes = relevantEventTypes;

        if (userState.mLastSentRelevantEventTypes != finalRelevantEventTypes) {
            userState.mLastSentRelevantEventTypes = finalRelevantEventTypes;
            mMainHandler.obtainMessage(MainHandler.MSG_SEND_RELEVANT_EVENTS_CHANGED_TO_CLIENTS,
                    userState.mUserId, finalRelevantEventTypes);
            mMainHandler.post(() -> {
                broadcastToClients(userState, (client) -> {
                    try {
                        client.setRelevantEventTypes(finalRelevantEventTypes);
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                });
            });
        }
    }

    private void broadcastToClients(
            UserState userState, Consumer<IAccessibilityManagerClient> clientAction) {
        mGlobalClients.broadcast(clientAction);
        userState.mUserClients.broadcast(clientAction);
    }

    private void unbindAllServicesLocked(UserState userState) {
        List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int count = services.size(); count > 0; count--) {
            // When the service is unbound, it disappears from the list, so there's no need to
            // keep track of the index
            services.get(0).unbindLocked();
        }
    }

    /**
     * Populates a set with the {@link ComponentName}s stored in a colon
     * separated value setting for a given user.
     *
     * @param settingName The setting to parse.
     * @param userId The user id.
     * @param outComponentNames The output component names.
     */
    private void readComponentNamesFromSettingLocked(String settingName, int userId,
            Set<ComponentName> outComponentNames) {
        String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                settingName, userId);
        readComponentNamesFromStringLocked(settingValue, outComponentNames, false);
    }

    /**
     * Populates a set with the {@link ComponentName}s contained in a colon-delimited string.
     *
     * @param names The colon-delimited string to parse.
     * @param outComponentNames The set of component names to be populated based on
     *    the contents of the <code>names</code> string.
     * @param doMerge If true, the parsed component names will be merged into the output
     *    set, rather than replacing the set's existing contents entirely.
     */
    private void readComponentNamesFromStringLocked(String names,
            Set<ComponentName> outComponentNames,
            boolean doMerge) {
        if (!doMerge) {
            outComponentNames.clear();
        }
        if (names != null) {
            TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(names);
            while (splitter.hasNext()) {
                String str = splitter.next();
                if (str == null || str.length() <= 0) {
                    continue;
                }
                ComponentName enabledService = ComponentName.unflattenFromString(str);
                if (enabledService != null) {
                    outComponentNames.add(enabledService);
                }
            }
        }
    }

    @Override
    public void persistComponentNamesToSettingLocked(String settingName,
            Set<ComponentName> componentNames, int userId) {
        StringBuilder builder = new StringBuilder();
        for (ComponentName componentName : componentNames) {
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(componentName.flattenToShortString());
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    settingName, builder.toString(), userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void updateServicesLocked(UserState userState) {
        Map<ComponentName, AccessibilityServiceConnection> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isUnlockingOrUnlocked = LocalServices.getService(UserManagerInternal.class)
                    .isUserUnlockingOrUnlocked(userState.mUserId);

        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());

            AccessibilityServiceConnection service = componentNameToServiceMap.get(componentName);

            // Ignore non-encryption-aware services until user is unlocked
            if (!isUnlockingOrUnlocked && !installedService.isDirectBootAware()) {
                Slog.d(LOG_TAG, "Ignoring non-encryption-aware service " + componentName);
                continue;
            }

            // Wait for the binding if it is in process.
            if (userState.mBindingServices.contains(componentName)) {
                continue;
            }
            if (userState.mEnabledServices.contains(componentName)
                    && !mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                if (service == null) {
                    service = new AccessibilityServiceConnection(userState, mContext, componentName,
                            installedService, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                            this, mWindowManagerService, mGlobalActionPerformer);
                } else if (userState.mBoundServices.contains(service)) {
                    continue;
                }
                service.bindLocked();
            } else {
                if (service != null) {
                    service.unbindLocked();
                }
            }
        }

        final int count = userState.mBoundServices.size();
        mTempIntArray.clear();
        for (int i = 0; i < count; i++) {
            final ResolveInfo resolveInfo =
                    userState.mBoundServices.get(i).mAccessibilityServiceInfo.getResolveInfo();
            if (resolveInfo != null) {
                mTempIntArray.add(resolveInfo.serviceInfo.applicationInfo.uid);
            }
        }
        // Calling out with lock held, but to a lower-level service
        final AudioManagerInternal audioManager =
                LocalServices.getService(AudioManagerInternal.class);
        if (audioManager != null) {
            audioManager.setAccessibilityServiceUids(mTempIntArray);
        }
        updateAccessibilityEnabledSetting(userState);
    }

    private void scheduleUpdateClientsIfNeededLocked(UserState userState) {
        final int clientState = userState.getClientState();
        if (userState.mLastSentClientState != clientState
                && (mGlobalClients.getRegisteredCallbackCount() > 0
                        || userState.mUserClients.getRegisteredCallbackCount() > 0)) {
            userState.mLastSentClientState = clientState;
            mMainHandler.obtainMessage(MainHandler.MSG_SEND_STATE_TO_CLIENTS,
                    clientState, userState.mUserId).sendToTarget();
        }
    }

    private void showAccessibilityButtonTargetSelection() {
        Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivityAsUser(intent, UserHandle.of(mCurrentUserId));
    }

    private void scheduleNotifyClientsOfServicesStateChange(UserState userState) {
        mMainHandler.obtainMessage(MainHandler.MSG_SEND_SERVICES_STATE_CHANGED_TO_CLIENTS,
                userState.mUserId).sendToTarget();
    }

    private void scheduleUpdateInputFilter(UserState userState) {
        mMainHandler.obtainMessage(MainHandler.MSG_UPDATE_INPUT_FILTER, userState).sendToTarget();
    }

    private void scheduleUpdateFingerprintGestureHandling(UserState userState) {
        mMainHandler.obtainMessage(MainHandler.MSG_UPDATE_FINGERPRINT, userState).sendToTarget();
    }

    private void updateInputFilter(UserState userState) {
        if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) return;

        boolean setInputFilter = false;
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            int flags = 0;
            if (userState.mIsDisplayMagnificationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_SCREEN_MAGNIFIER;
            }
            if (userState.mIsNavBarMagnificationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TRIGGERED_SCREEN_MAGNIFIER;
            }
            if (userHasMagnificationServicesLocked(userState)) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER;
            }
            // Touch exploration without accessibility makes no sense.
            if (userState.isHandlingAccessibilityEvents() && userState.mIsTouchExplorationEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION;
            }
            if (userState.mIsFilterKeyEventsEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_FILTER_KEY_EVENTS;
            }
            if (userState.mIsAutoclickEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_AUTOCLICK;
            }
            if (userState.mIsPerformGesturesEnabled) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_INJECT_MOTION_EVENTS;
            }
            if (flags != 0) {
                if (!mHasInputFilter) {
                    mHasInputFilter = true;
                    if (mInputFilter == null) {
                        mInputFilter = new AccessibilityInputFilter(mContext,
                                AccessibilityManagerService.this);
                    }
                    inputFilter = mInputFilter;
                    setInputFilter = true;
                }
                mInputFilter.setUserAndEnabledFeatures(userState.mUserId, flags);
            } else {
                if (mHasInputFilter) {
                    mHasInputFilter = false;
                    mInputFilter.setUserAndEnabledFeatures(userState.mUserId, 0);
                    inputFilter = null;
                    setInputFilter = true;
                }
            }
        }
        if (setInputFilter) {
            mWindowManagerService.setInputFilter(inputFilter);
        }
    }

    private void showEnableTouchExplorationDialog(final AccessibilityServiceConnection service) {
        synchronized (mLock) {
            String label = service.getServiceInfo().getResolveInfo()
                    .loadLabel(mContext.getPackageManager()).toString();

            final UserState userState = getCurrentUserStateLocked();
            if (userState.mIsTouchExplorationEnabled) {
                return;
            }
            if (mEnableTouchExplorationDialog != null
                    && mEnableTouchExplorationDialog.isShowing()) {
                return;
            }
            mEnableTouchExplorationDialog = new AlertDialog.Builder(mContext)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok, new OnClickListener() {
                     @Override
                     public void onClick(DialogInterface dialog, int which) {
                         // The user allowed the service to toggle touch exploration.
                         userState.mTouchExplorationGrantedServices.add(service.mComponentName);
                         persistComponentNamesToSettingLocked(
                                 Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                 userState.mTouchExplorationGrantedServices, userState.mUserId);
                         // Enable touch exploration.
                         userState.mIsTouchExplorationEnabled = true;
                         final long identity = Binder.clearCallingIdentity();
                         try {
                             Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                     Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1,
                                     userState.mUserId);
                         } finally {
                             Binder.restoreCallingIdentity(identity);
                         }
                         onUserStateChangedLocked(userState);
                     }
                 })
                 .setNegativeButton(android.R.string.cancel, new OnClickListener() {
                     @Override
                     public void onClick(DialogInterface dialog, int which) {
                         dialog.dismiss();
                     }
                 })
                 .setTitle(R.string.enable_explore_by_touch_warning_title)
                 .setMessage(mContext.getString(
                         R.string.enable_explore_by_touch_warning_message, label))
                 .create();
             mEnableTouchExplorationDialog.getWindow().setType(
                     WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
             mEnableTouchExplorationDialog.getWindow().getAttributes().privateFlags
                     |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
             mEnableTouchExplorationDialog.setCanceledOnTouchOutside(true);
             mEnableTouchExplorationDialog.show();
        }
    }

    /**
     * Called when any property of the user state has changed.
     *
     * @param userState the new user state
     */
    private void onUserStateChangedLocked(UserState userState) {
        // TODO: Remove this hack
        mInitialized = true;
        updateLegacyCapabilitiesLocked(userState);
        updateServicesLocked(userState);
        updateAccessibilityShortcutLocked(userState);
        updateWindowsForAccessibilityCallbackLocked(userState);
        updateAccessibilityFocusBehaviorLocked(userState);
        updateFilterKeyEventsLocked(userState);
        updateTouchExplorationLocked(userState);
        updatePerformGesturesLocked(userState);
        updateDisplayDaltonizerLocked(userState);
        updateDisplayInversionLocked(userState);
        updateMagnificationLocked(userState);
        updateSoftKeyboardShowModeLocked(userState);
        scheduleUpdateFingerprintGestureHandling(userState);
        scheduleUpdateInputFilter(userState);
        scheduleUpdateClientsIfNeededLocked(userState);
        updateRelevantEventsLocked(userState);
        updateAccessibilityButtonTargetsLocked(userState);
    }

    private void updateAccessibilityFocusBehaviorLocked(UserState userState) {
        // If there is no service that can operate with interactive windows
        // then we keep the old behavior where a window loses accessibility
        // focus if it is no longer active. This still changes the behavior
        // for services that do not operate with interactive windows and run
        // at the same time as the one(s) which does. In practice however,
        // there is only one service that uses accessibility focus and it
        // is typically the one that operates with interactive windows, So,
        // this is fine. Note that to allow a service to work across windows
        // we have to allow accessibility focus stay in any of them. Sigh...
        List<AccessibilityServiceConnection> boundServices = userState.mBoundServices;
        final int boundServiceCount = boundServices.size();
        for (int i = 0; i < boundServiceCount; i++) {
            AccessibilityServiceConnection boundService = boundServices.get(i);
            if (boundService.canRetrieveInteractiveWindowsLocked()) {
                userState.mAccessibilityFocusOnlyInActiveWindow = false;
                return;
            }
        }
        userState.mAccessibilityFocusOnlyInActiveWindow = true;
    }

    private void updateWindowsForAccessibilityCallbackLocked(UserState userState) {
        // We observe windows for accessibility only if there is at least
        // one bound service that can retrieve window content that specified
        // it is interested in accessing such windows. For services that are
        // binding we do an update pass after each bind event, so we run this
        // code and register the callback if needed.

        boolean observingWindows = mUiAutomationManager.canRetrieveInteractiveWindowsLocked();
        List<AccessibilityServiceConnection> boundServices = userState.mBoundServices;
        final int boundServiceCount = boundServices.size();
        for (int i = 0; !observingWindows && (i < boundServiceCount); i++) {
            AccessibilityServiceConnection boundService = boundServices.get(i);
            if (boundService.canRetrieveInteractiveWindowsLocked()) {
                observingWindows = true;
            }
        }

        if (observingWindows) {
            if (mWindowsForAccessibilityCallback == null) {
                mWindowsForAccessibilityCallback = new WindowsForAccessibilityCallback();
                mWindowManagerService.setWindowsForAccessibilityCallback(
                        mWindowsForAccessibilityCallback);
            }
            return;
        }

        if (mWindowsForAccessibilityCallback != null) {
            mWindowsForAccessibilityCallback = null;
            mWindowManagerService.setWindowsForAccessibilityCallback(null);
            // Drop all windows we know about.
            mSecurityPolicy.clearWindowsLocked();
        }
    }

    private void updateLegacyCapabilitiesLocked(UserState userState) {
        // Up to JB-MR1 we had a white list with services that can enable touch
        // exploration. When a service is first started we show a dialog to the
        // use to get a permission to white list the service.
        final int installedServiceCount = userState.mInstalledServices.size();
        for (int i = 0; i < installedServiceCount; i++) {
            AccessibilityServiceInfo serviceInfo = userState.mInstalledServices.get(i);
            ResolveInfo resolveInfo = serviceInfo.getResolveInfo();
            if ((serviceInfo.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) == 0
                    && resolveInfo.serviceInfo.applicationInfo.targetSdkVersion
                        <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                ComponentName componentName = new ComponentName(
                        resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
                if (userState.mTouchExplorationGrantedServices.contains(componentName)) {
                    serviceInfo.setCapabilities(serviceInfo.getCapabilities()
                            | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION);
                }
            }
        }
    }

    private void updatePerformGesturesLocked(UserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if ((service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) {
                userState.mIsPerformGesturesEnabled = true;
                return;
            }
        }
        userState.mIsPerformGesturesEnabled = false;
    }

    private void updateFilterKeyEventsLocked(UserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestFilterKeyEvents
                    && (service.mAccessibilityServiceInfo.getCapabilities()
                            & AccessibilityServiceInfo
                            .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0) {
                userState.mIsFilterKeyEventsEnabled = true;
                return;
            }
        }
        userState.mIsFilterKeyEventsEnabled = false;
    }

    private boolean readConfigurationForUserStateLocked(UserState userState) {
        boolean somethingChanged = readInstalledAccessibilityServiceLocked(userState);
        somethingChanged |= readEnabledAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationGrantedAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationEnabledSettingLocked(userState);
        somethingChanged |= readHighTextContrastEnabledSettingLocked(userState);
        somethingChanged |= readMagnificationEnabledSettingsLocked(userState);
        somethingChanged |= readAutoclickEnabledSettingLocked(userState);
        somethingChanged |= readAccessibilityShortcutSettingLocked(userState);
        somethingChanged |= readAccessibilityButtonSettingsLocked(userState);
        return somethingChanged;
    }

    private void updateAccessibilityEnabledSetting(UserState userState) {
        final long identity = Binder.clearCallingIdentity();
        final boolean isA11yEnabled = mUiAutomationManager.isUiAutomationRunningLocked()
                || userState.isHandlingAccessibilityEvents();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    (isA11yEnabled) ? 1 : 0,
                    userState.mUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean readTouchExplorationEnabledSettingLocked(UserState userState) {
        final boolean touchExplorationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId) == 1;
        if (touchExplorationEnabled != userState.mIsTouchExplorationEnabled) {
            userState.mIsTouchExplorationEnabled = touchExplorationEnabled;
            return true;
        }
        return false;
    }

    private boolean readMagnificationEnabledSettingsLocked(UserState userState) {
        final boolean displayMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
        final boolean navBarMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                0, userState.mUserId) == 1;
        if ((displayMagnificationEnabled != userState.mIsDisplayMagnificationEnabled)
                || (navBarMagnificationEnabled != userState.mIsNavBarMagnificationEnabled)) {
            userState.mIsDisplayMagnificationEnabled = displayMagnificationEnabled;
            userState.mIsNavBarMagnificationEnabled = navBarMagnificationEnabled;
            return true;
        }
        return false;
    }

    private boolean readAutoclickEnabledSettingLocked(UserState userState) {
        final boolean autoclickEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
                0, userState.mUserId) == 1;
        if (autoclickEnabled != userState.mIsAutoclickEnabled) {
            userState.mIsAutoclickEnabled = autoclickEnabled;
            return true;
        }
        return false;
    }

    private boolean readHighTextContrastEnabledSettingLocked(UserState userState) {
        final boolean highTextContrastEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, 0,
                userState.mUserId) == 1;
        if (highTextContrastEnabled != userState.mIsTextHighContrastEnabled) {
            userState.mIsTextHighContrastEnabled = highTextContrastEnabled;
            return true;
        }
        return false;
    }

    private boolean readSoftKeyboardShowModeChangedLocked(UserState userState) {
        final int softKeyboardShowMode = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0,
                userState.mUserId);
        if (softKeyboardShowMode != userState.mSoftKeyboardShowMode) {
            userState.mSoftKeyboardShowMode = softKeyboardShowMode;
            return true;
        }
        return false;
    }

    private void updateTouchExplorationLocked(UserState userState) {
        boolean enabled = mUiAutomationManager.isTouchExplorationEnabledLocked();
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (canRequestAndRequestsTouchExplorationLocked(service, userState)) {
                enabled = true;
                break;
            }
        }
        if (enabled != userState.mIsTouchExplorationEnabled) {
            userState.mIsTouchExplorationEnabled = enabled;
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.TOUCH_EXPLORATION_ENABLED, enabled ? 1 : 0,
                        userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private boolean readAccessibilityShortcutSettingLocked(UserState userState) {
        String componentNameToEnableString = AccessibilityShortcutController
                .getTargetServiceComponentNameString(mContext, userState.mUserId);
        if ((componentNameToEnableString == null) || componentNameToEnableString.isEmpty()) {
            if (userState.mServiceToEnableWithShortcut == null) {
                return false;
            }
            userState.mServiceToEnableWithShortcut = null;
            return true;
        }
        ComponentName componentNameToEnable =
            ComponentName.unflattenFromString(componentNameToEnableString);
        if ((componentNameToEnable != null)
                && componentNameToEnable.equals(userState.mServiceToEnableWithShortcut)) {
            return false;
        }
        userState.mServiceToEnableWithShortcut = componentNameToEnable;
        return true;
    }

    private boolean readAccessibilityButtonSettingsLocked(UserState userState) {
        String componentId = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT, userState.mUserId);
        if (TextUtils.isEmpty(componentId)) {
            if ((userState.mServiceAssignedToAccessibilityButton == null)
                    && !userState.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                return false;
            }
            userState.mServiceAssignedToAccessibilityButton = null;
            userState.mIsNavBarMagnificationAssignedToAccessibilityButton = false;
            return true;
        }

        if (componentId.equals(MagnificationController.class.getName())) {
            if (userState.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                return false;
            }
            userState.mServiceAssignedToAccessibilityButton = null;
            userState.mIsNavBarMagnificationAssignedToAccessibilityButton = true;
            return true;
        }

        ComponentName componentName = ComponentName.unflattenFromString(componentId);
        if (componentName.equals(userState.mServiceAssignedToAccessibilityButton)) {
            return false;
        }
        userState.mServiceAssignedToAccessibilityButton = componentName;
        userState.mIsNavBarMagnificationAssignedToAccessibilityButton = false;
        return true;
    }

    /**
     * Check if the service that will be enabled by the shortcut is installed. If it isn't,
     * clear the value and the associated setting so a sideloaded service can't spoof the
     * package name of the default service.
     *
     * @param userState
     */
    private void updateAccessibilityShortcutLocked(UserState userState) {
        if (userState.mServiceToEnableWithShortcut == null) {
            return;
        }
        boolean shortcutServiceIsInstalled =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap()
                        .containsKey(userState.mServiceToEnableWithShortcut);
        for (int i = 0; !shortcutServiceIsInstalled && (i < userState.mInstalledServices.size());
                i++) {
            if (userState.mInstalledServices.get(i).getComponentName()
                    .equals(userState.mServiceToEnableWithShortcut)) {
                shortcutServiceIsInstalled = true;
            }
        }
        if (!shortcutServiceIsInstalled) {
            userState.mServiceToEnableWithShortcut = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, null,
                        userState.mUserId);

                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_ENABLED, 0, userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private boolean canRequestAndRequestsTouchExplorationLocked(
            AccessibilityServiceConnection service, UserState userState) {
        // Service not ready or cannot request the feature - well nothing to do.
        if (!service.canReceiveEventsLocked() || !service.mRequestTouchExplorationMode) {
            return false;
        }
        if (service.getServiceInfo().getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // Up to JB-MR1 we had a white list with services that can enable touch
            // exploration. When a service is first started we show a dialog to the
            // use to get a permission to white list the service.
            if (userState.mTouchExplorationGrantedServices.contains(service.mComponentName)) {
                return true;
            } else if (mEnableTouchExplorationDialog == null
                    || !mEnableTouchExplorationDialog.isShowing()) {
                mMainHandler.obtainMessage(
                        MainHandler.MSG_SHOW_ENABLED_TOUCH_EXPLORATION_DIALOG,
                        service).sendToTarget();
            }
        } else {
            // Starting in JB-MR2 we request an accessibility service to declare
            // certain capabilities in its meta-data to allow it to enable the
            // corresponding features.
            if ((service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) != 0) {
                return true;
            }
        }
        return false;
    }

    private void updateDisplayDaltonizerLocked(UserState userState) {
        DisplayAdjustmentUtils.applyDaltonizerSetting(mContext, userState.mUserId);
    }

    private void updateDisplayInversionLocked(UserState userState) {
        DisplayAdjustmentUtils.applyInversionSetting(mContext, userState.mUserId);
    }

    private void updateMagnificationLocked(UserState userState) {
        if (userState.mUserId != mCurrentUserId) {
            return;
        }

        if (!mUiAutomationManager.suppressingAccessibilityServicesLocked()
                && (userState.mIsDisplayMagnificationEnabled
                        || userState.mIsNavBarMagnificationEnabled
                        || userHasListeningMagnificationServicesLocked(userState))) {
            // Initialize the magnification controller if necessary
            getMagnificationController();
            mMagnificationController.register();
        } else if (mMagnificationController != null) {
            mMagnificationController.unregister();
        }
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification.
     */
    private boolean userHasMagnificationServicesLocked(UserState userState) {
        final List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final AccessibilityServiceConnection service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification and are actively listening for magnification updates.
     */
    private boolean userHasListeningMagnificationServicesLocked(UserState userState) {
        final List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final AccessibilityServiceConnection service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)
                    && service.isMagnificationCallbackEnabled()) {
                return true;
            }
        }
        return false;
    }

    private void updateSoftKeyboardShowModeLocked(UserState userState) {
        final int userId = userState.mUserId;
        // Only check whether we need to reset the soft keyboard mode if it is not set to the
        // default.
        if ((userId == mCurrentUserId) && (userState.mSoftKeyboardShowMode != 0)) {
            // Check whether the last AccessibilityService that changed the soft keyboard mode to
            // something other than the default is still enabled and, if not, remove flag and
            // reset to the default soft keyboard behavior.
            boolean serviceChangingSoftKeyboardModeIsEnabled =
                    userState.mEnabledServices.contains(userState.mServiceChangingSoftKeyboardMode);

            if (!serviceChangingSoftKeyboardModeIsEnabled) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                            0,
                            userState.mUserId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                userState.mSoftKeyboardShowMode = 0;
                userState.mServiceChangingSoftKeyboardMode = null;
                notifySoftKeyboardShowModeChangedLocked(userState.mSoftKeyboardShowMode);
            }
        }
    }

    private void updateFingerprintGestureHandling(UserState userState) {
        final List<AccessibilityServiceConnection> services;
        synchronized (mLock) {
            services = userState.mBoundServices;
            if ((mFingerprintGestureDispatcher == null)
                    &&  mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                // Only create the controller when a service wants to use the feature
                int numServices = services.size();
                for (int i = 0; i < numServices; i++) {
                    if (services.get(i).isCapturingFingerprintGestures()) {
                        final long identity = Binder.clearCallingIdentity();
                        IFingerprintService service = null;
                        try {
                            service = IFingerprintService.Stub.asInterface(
                                    ServiceManager.getService(Context.FINGERPRINT_SERVICE));
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                        if (service != null) {
                            mFingerprintGestureDispatcher = new FingerprintGestureDispatcher(
                                    service, mLock);
                            break;
                        }
                    }
                }
            }
        }
        if (mFingerprintGestureDispatcher != null) {
            mFingerprintGestureDispatcher.updateClientList(services);
        }
    }

    private void updateAccessibilityButtonTargetsLocked(UserState userState) {
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                service.notifyAccessibilityButtonAvailabilityChangedLocked(
                        service.isAccessibilityButtonAvailableLocked(userState));
            }
        }
    }

    @GuardedBy("mLock")
    @Override
    public MagnificationSpec getCompatibleMagnificationSpecLocked(int windowId) {
        IBinder windowToken = mGlobalWindowTokens.get(windowId);
        if (windowToken == null) {
            windowToken = getCurrentUserStateLocked().mWindowTokens.get(windowId);
        }
        if (windowToken != null) {
            return mWindowManagerService.getCompatibleMagnificationSpecForWindow(
                    windowToken);
        }
        return null;
    }

    @Override
    public KeyEventDispatcher getKeyEventDispatcher() {
        if (mKeyEventDispatcher == null) {
            mKeyEventDispatcher = new KeyEventDispatcher(
                    mMainHandler, MainHandler.MSG_SEND_KEY_EVENT_TO_INPUT_FILTER, mLock,
                    mPowerManager);
        }
        return mKeyEventDispatcher;
    }

    @Override
    public PendingIntent getPendingIntentActivity(Context context, int requestCode, Intent intent,
            int flags) {
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }
    /**
     * AIDL-exposed method to be called when the accessibility shortcut is enabled. Requires
     * permission to write secure settings, since someone with that permission can enable
     * accessibility services themselves.
     */
    public void performAccessibilityShortcut() {
        if ((UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)
                && (mContext.checkCallingPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException(
                    "performAccessibilityShortcut requires the WRITE_SECURE_SETTINGS permission");
        }
        final Map<ComponentName, ToggleableFrameworkFeatureInfo> frameworkFeatureMap =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap();
        synchronized(mLock) {
            final UserState userState = getUserStateLocked(mCurrentUserId);
            final ComponentName serviceName = userState.mServiceToEnableWithShortcut;
            if (serviceName == null) {
                return;
            }
            if (frameworkFeatureMap.containsKey(serviceName)) {
                // Toggle the requested framework feature
                ToggleableFrameworkFeatureInfo featureInfo = frameworkFeatureMap.get(serviceName);
                SettingStringHelper setting = new SettingStringHelper(mContext.getContentResolver(),
                        featureInfo.getSettingKey(), mCurrentUserId);
                // Assuming that the default state will be to have the feature off
                if (!TextUtils.equals(featureInfo.getSettingOnValue(), setting.read())) {
                    setting.write(featureInfo.getSettingOnValue());
                } else {
                    setting.write(featureInfo.getSettingOffValue());
                }
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                if (userState.mComponentNameToServiceMap.get(serviceName) == null) {
                    enableAccessibilityServiceLocked(serviceName, mCurrentUserId);
                } else {
                    disableAccessibilityServiceLocked(serviceName, mCurrentUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    };

    /**
     * Enables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void enableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        final SettingStringHelper setting =
                new SettingStringHelper(
                        mContext.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        userId);
        setting.write(ComponentNameSet.add(setting.read(), componentName));

        UserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.add(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    /**
     * Disables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void disableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        final SettingsStringUtil.SettingStringHelper setting =
                new SettingStringHelper(
                        mContext.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        userId);
        setting.write(ComponentNameSet.remove(setting.read(), componentName));

        UserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.remove(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    /**
     * AIDL-exposed method. System only.
     * Inform accessibility that a fingerprint gesture was performed
     *
     * @param gestureKeyCode The key code corresponding to the fingerprint gesture.
     * @return {@code true} if accessibility consumes the fingerprint gesture, {@code false} if it
     * doesn't.
     */
    @Override
    public boolean sendFingerprintGesture(int gestureKeyCode) {
        synchronized(mLock) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
                throw new SecurityException("Only SYSTEM can call sendFingerprintGesture");
            }
        }
        if (mFingerprintGestureDispatcher == null) {
            return false;
        }
        return mFingerprintGestureDispatcher.onFingerprintGesture(gestureKeyCode);
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.println("ACCESSIBILITY MANAGER (dumpsys accessibility)");
            pw.println();
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                UserState userState = mUserStates.valueAt(i);
                pw.append("User state[attributes:{id=" + userState.mUserId);
                pw.append(", currentUser=" + (userState.mUserId == mCurrentUserId));
                pw.append(", touchExplorationEnabled=" + userState.mIsTouchExplorationEnabled);
                pw.append(", displayMagnificationEnabled="
                        + userState.mIsDisplayMagnificationEnabled);
                pw.append(", navBarMagnificationEnabled="
                        + userState.mIsNavBarMagnificationEnabled);
                pw.append(", autoclickEnabled=" + userState.mIsAutoclickEnabled);
                if (mUiAutomationManager.isUiAutomationRunningLocked()) {
                    pw.append(", ");
                    mUiAutomationManager.dumpUiAutomationService(fd, pw, args);
                    pw.println();
                }
                pw.append("}");
                pw.println();
                pw.append("           services:{");
                final int serviceCount = userState.mBoundServices.size();
                for (int j = 0; j < serviceCount; j++) {
                    if (j > 0) {
                        pw.append(", ");
                        pw.println();
                        pw.append("                     ");
                    }
                    AccessibilityServiceConnection service = userState.mBoundServices.get(j);
                    service.dump(fd, pw, args);
                }
                pw.println("}]");
                pw.println();
            }
            if (mSecurityPolicy.mWindows != null) {
                final int windowCount = mSecurityPolicy.mWindows.size();
                for (int j = 0; j < windowCount; j++) {
                    if (j > 0) {
                        pw.append(',');
                        pw.println();
                    }
                    pw.append("Window[");
                    AccessibilityWindowInfo window = mSecurityPolicy.mWindows.get(j);
                    pw.append(window.toString());
                    pw.append(']');
                }
            }
        }
    }

    class RemoteAccessibilityConnection implements DeathRecipient {
        private final int mUid;
        private final String mPackageName;
        private final int mWindowId;
        private final int mUserId;
        private final IAccessibilityInteractionConnection mConnection;

        RemoteAccessibilityConnection(int windowId,
                IAccessibilityInteractionConnection connection,
                String packageName, int uid, int userId) {
            mWindowId = windowId;
            mPackageName = packageName;
            mUid = uid;
            mUserId = userId;
            mConnection = connection;
        }

        public int getUid() {
            return  mUid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public IAccessibilityInteractionConnection getRemote() {
            return mConnection;
        }

        public void linkToDeath() throws RemoteException {
            mConnection.asBinder().linkToDeath(this, 0);
        }

        public void unlinkToDeath() {
            mConnection.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            unlinkToDeath();
            synchronized (mLock) {
                removeAccessibilityInteractionConnectionLocked(mWindowId, mUserId);
            }
        }
    }

    final class MainHandler extends Handler {
        public static final int MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER = 1;
        public static final int MSG_SEND_STATE_TO_CLIENTS = 2;
        public static final int MSG_SEND_CLEARED_STATE_TO_CLIENTS_FOR_USER = 3;
        public static final int MSG_ANNOUNCE_NEW_USER_IF_NEEDED = 5;
        public static final int MSG_UPDATE_INPUT_FILTER = 6;
        public static final int MSG_SHOW_ENABLED_TOUCH_EXPLORATION_DIALOG = 7;
        public static final int MSG_SEND_KEY_EVENT_TO_INPUT_FILTER = 8;
        public static final int MSG_CLEAR_ACCESSIBILITY_FOCUS = 9;
        public static final int MSG_SEND_SERVICES_STATE_CHANGED_TO_CLIENTS = 10;
        public static final int MSG_UPDATE_FINGERPRINT = 11;
        public static final int MSG_SEND_RELEVANT_EVENTS_CHANGED_TO_CLIENTS = 12;
        public static final int MSG_SEND_ACCESSIBILITY_BUTTON_TO_INPUT_FILTER = 13;
        public static final int MSG_SHOW_ACCESSIBILITY_BUTTON_CHOOSER = 14;
        public static final int MSG_INIT_SERVICE = 15;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int type = msg.what;
            switch (type) {
                case MSG_SEND_ACCESSIBILITY_EVENT_TO_INPUT_FILTER: {
                    AccessibilityEvent event = (AccessibilityEvent) msg.obj;
                    synchronized (mLock) {
                        if (mHasInputFilter && mInputFilter != null) {
                            mInputFilter.notifyAccessibilityEvent(event);
                        }
                    }
                    event.recycle();
                } break;

                case MSG_SEND_KEY_EVENT_TO_INPUT_FILTER: {
                    KeyEvent event = (KeyEvent) msg.obj;
                    final int policyFlags = msg.arg1;
                    synchronized (mLock) {
                        if (mHasInputFilter && mInputFilter != null) {
                            mInputFilter.sendInputEvent(event, policyFlags);
                        }
                    }
                    event.recycle();
                } break;

                case MSG_SEND_STATE_TO_CLIENTS: {
                    final int clientState = msg.arg1;
                    final int userId = msg.arg2;
                    sendStateToClients(clientState, mGlobalClients);
                    sendStateToClients(clientState, getUserClientsForId(userId));
                } break;

                case MSG_SEND_CLEARED_STATE_TO_CLIENTS_FOR_USER: {
                    final int userId = msg.arg1;
                    sendStateToClients(0, getUserClientsForId(userId));
                } break;

                case MSG_ANNOUNCE_NEW_USER_IF_NEEDED: {
                    announceNewUserIfNeeded();
                } break;

                case MSG_UPDATE_INPUT_FILTER: {
                    UserState userState = (UserState) msg.obj;
                    updateInputFilter(userState);
                } break;

                case MSG_SHOW_ENABLED_TOUCH_EXPLORATION_DIALOG: {
                    AccessibilityServiceConnection service =
                            (AccessibilityServiceConnection) msg.obj;
                    showEnableTouchExplorationDialog(service);
                } break;

                case MSG_CLEAR_ACCESSIBILITY_FOCUS: {
                    final int windowId = msg.arg1;
                    getInteractionBridge().clearAccessibilityFocusNotLocked(windowId);
                } break;

                case MSG_SEND_SERVICES_STATE_CHANGED_TO_CLIENTS: {
                    final int userId = msg.arg1;
                    notifyClientsOfServicesStateChange(mGlobalClients);
                    notifyClientsOfServicesStateChange(getUserClientsForId(userId));
                } break;

                case MSG_UPDATE_FINGERPRINT: {
                    updateFingerprintGestureHandling((UserState) msg.obj);
                } break;

                case MSG_SEND_RELEVANT_EVENTS_CHANGED_TO_CLIENTS: {
                    final int userId = msg.arg1;
                    final int relevantEventTypes = msg.arg2;
                    final UserState userState;
                    synchronized (mLock) {
                        userState = getUserStateLocked(userId);
                    }
                    broadcastToClients(userState, (client) -> {
                        try {
                            client.setRelevantEventTypes(relevantEventTypes);
                        } catch (RemoteException re) {
                            /* ignore */
                        }
                    });
                } break;

               case MSG_SEND_ACCESSIBILITY_BUTTON_TO_INPUT_FILTER: {
                    synchronized (mLock) {
                        if (mHasInputFilter && mInputFilter != null) {
                            mInputFilter.notifyAccessibilityButtonClicked();
                        }
                    }
                } break;

                case MSG_SHOW_ACCESSIBILITY_BUTTON_CHOOSER: {
                    showAccessibilityButtonTargetSelection();
                } break;

                case MSG_INIT_SERVICE: {
                    final AccessibilityServiceConnection service =
                            (AccessibilityServiceConnection) msg.obj;
                    service.initializeService();
                } break;
            }
        }

        private void announceNewUserIfNeeded() {
            synchronized (mLock) {
                UserState userState = getCurrentUserStateLocked();
                if (userState.isHandlingAccessibilityEvents()) {
                    UserManager userManager = (UserManager) mContext.getSystemService(
                            Context.USER_SERVICE);
                    String message = mContext.getString(R.string.user_switched,
                            userManager.getUserInfo(mCurrentUserId).name);
                    AccessibilityEvent event = AccessibilityEvent.obtain(
                            AccessibilityEvent.TYPE_ANNOUNCEMENT);
                    event.getText().add(message);
                    sendAccessibilityEvent(event, mCurrentUserId);
                }
            }
        }

        private RemoteCallbackList<IAccessibilityManagerClient> getUserClientsForId(int userId) {
            final UserState userState;
            synchronized (mLock) {
                userState = getUserStateLocked(userId);
            }
            return userState.mUserClients;
        }

        private void sendStateToClients(int clientState,
                RemoteCallbackList<IAccessibilityManagerClient> clients) {
            clients.broadcast((client) -> {
                try {
                    client.setState(clientState);
                } catch (RemoteException re) {
                    /* ignore */
                }
            });
        }

        private void notifyClientsOfServicesStateChange(
                RemoteCallbackList<IAccessibilityManagerClient> clients) {
            try {
                final int userClientCount = clients.beginBroadcast();
                for (int i = 0; i < userClientCount; i++) {
                    IAccessibilityManagerClient client = clients.getBroadcastItem(i);
                    try {
                        client.notifyServicesStateChanged();
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                }
            } finally {
                clients.finishBroadcast();
            }
        }
    }

    private int findWindowIdLocked(IBinder token) {
        final int globalIndex = mGlobalWindowTokens.indexOfValue(token);
        if (globalIndex >= 0) {
            return mGlobalWindowTokens.keyAt(globalIndex);
        }
        UserState userState = getCurrentUserStateLocked();
        final int userIndex = userState.mWindowTokens.indexOfValue(token);
        if (userIndex >= 0) {
            return userState.mWindowTokens.keyAt(userIndex);
        }
        return -1;
    }

    @Override
    public void ensureWindowsAvailableTimed() {
        synchronized (mLock) {
            if (mSecurityPolicy.mWindows != null) {
                return;
            }
            // If we have no registered callback, update the state we
            // we may have to register one but it didn't happen yet.
            if (mWindowsForAccessibilityCallback == null) {
                UserState userState = getCurrentUserStateLocked();
                onUserStateChangedLocked(userState);
            }
            // We have no windows but do not care about them, done.
            if (mWindowsForAccessibilityCallback == null) {
                return;
            }

            // Wait for the windows with a timeout.
            final long startMillis = SystemClock.uptimeMillis();
            while (mSecurityPolicy.mWindows == null) {
                final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
                final long remainMillis = WAIT_WINDOWS_TIMEOUT_MILLIS - elapsedMillis;
                if (remainMillis <= 0) {
                    return;
                }
                try {
                    mLock.wait(remainMillis);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }
    }

    @Override
    public MagnificationController getMagnificationController() {
        synchronized (mLock) {
            if (mMagnificationController == null) {
                mMagnificationController = new MagnificationController(mContext, this, mLock);
                mMagnificationController.setUserId(mCurrentUserId);
            }
            return mMagnificationController;
        }
    }

    @Override
    public boolean performAccessibilityAction(int resolvedWindowId,
            long accessibilityNodeId, int action, Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int fetchFlags,
            long interrogatingTid) {
        RemoteAccessibilityConnection connection;
        IBinder activityToken = null;
        synchronized (mLock) {
            connection = getConnectionLocked(resolvedWindowId);
            if (connection == null)  {
                return false;
            }
            final boolean isA11yFocusAction = (action == ACTION_ACCESSIBILITY_FOCUS)
                    || (action == ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            final AccessibilityWindowInfo a11yWindowInfo =
                    mSecurityPolicy.findA11yWindowInfoById(resolvedWindowId);
            if (!isA11yFocusAction) {
                final WindowInfo windowInfo =
                        mSecurityPolicy.findWindowInfoById(resolvedWindowId);
                if (windowInfo != null) activityToken = windowInfo.activityToken;
            }
            if ((a11yWindowInfo != null) && a11yWindowInfo.isInPictureInPictureMode()
                    && (mPictureInPictureActionReplacingConnection != null) && !isA11yFocusAction) {
                connection = mPictureInPictureActionReplacingConnection;
            }
        }
        final int interrogatingPid = Binder.getCallingPid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            // Regardless of whether or not the action succeeds, it was generated by an
            // accessibility service that is driven by user actions, so note user activity.
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY, 0);

            if (activityToken != null) {
                LocalServices.getService(ActivityManagerInternal.class)
                        .setFocusedActivity(activityToken);
            }
            connection.mConnection.performAccessibilityAction(accessibilityNodeId, action,
                    arguments, interactionId, callback, fetchFlags, interrogatingPid,
                    interrogatingTid);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling performAccessibilityAction: " + re);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
        return true;
    }

    @Override
    public RemoteAccessibilityConnection getConnectionLocked(int windowId) {
        if (DEBUG) {
            Slog.i(LOG_TAG, "Trying to get interaction connection to windowId: " + windowId);
        }
        RemoteAccessibilityConnection connection =
                mGlobalInteractionConnections.get(windowId);
        if (connection == null) {
            connection = getCurrentUserStateLocked().mInteractionConnections.get(windowId);
        }
        if (connection != null && connection.mConnection != null) {
            return connection;
        }
        if (DEBUG) {
            Slog.e(LOG_TAG, "No interaction connection to window: " + windowId);
        }
        return null;
    }

    @Override
    public IAccessibilityInteractionConnectionCallback replaceCallbackIfNeeded(
            IAccessibilityInteractionConnectionCallback originalCallback,
            int resolvedWindowId, int interactionId, int interrogatingPid,
            long interrogatingTid) {
        AccessibilityWindowInfo windowInfo =
                mSecurityPolicy.findA11yWindowInfoById(resolvedWindowId);
        if ((windowInfo == null) || !windowInfo.isInPictureInPictureMode()
                || (mPictureInPictureActionReplacingConnection == null)) {
            return originalCallback;
        }
        return new ActionReplacingCallback(originalCallback,
                mPictureInPictureActionReplacingConnection.mConnection, interactionId,
                interrogatingPid, interrogatingTid);
    }

    @Override
    public void onClientChange(boolean serviceInfoChanged) {
        AccessibilityManagerService.UserState userState = getUserStateLocked(mCurrentUserId);
        onUserStateChangedLocked(userState);
        if (serviceInfoChanged) {
            scheduleNotifyClientsOfServicesStateChange(userState);
        }
    }

    private AppWidgetManagerInternal getAppWidgetManager() {
        synchronized (mLock) {
            if (mAppWidgetService == null
                    && mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)) {
                mAppWidgetService = LocalServices.getService(AppWidgetManagerInternal.class);
            }
            return mAppWidgetService;
        }
    }

    final class WindowsForAccessibilityCallback implements
            WindowManagerInternal.WindowsForAccessibilityCallback {

        @Override
        public void onWindowsForAccessibilityChanged(List<WindowInfo> windows) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Windows changed: " + windows);
                }

                // Let the policy update the focused and active windows.
                mSecurityPolicy.updateWindowsLocked(windows);

                // Someone may be waiting for the windows - advertise it.
                mLock.notifyAll();
            }
        }

        private AccessibilityWindowInfo populateReportedWindow(WindowInfo window) {
            final int windowId = findWindowIdLocked(window.token);
            if (windowId < 0) {
                return null;
            }

            AccessibilityWindowInfo reportedWindow = AccessibilityWindowInfo.obtain();

            reportedWindow.setId(windowId);
            reportedWindow.setType(getTypeForWindowManagerWindowType(window.type));
            reportedWindow.setLayer(window.layer);
            reportedWindow.setFocused(window.focused);
            reportedWindow.setBoundsInScreen(window.boundsInScreen);
            reportedWindow.setTitle(window.title);
            reportedWindow.setAnchorId(window.accessibilityIdOfAnchor);
            reportedWindow.setPictureInPicture(window.inPictureInPicture);

            final int parentId = findWindowIdLocked(window.parentToken);
            if (parentId >= 0) {
                reportedWindow.setParentId(parentId);
            }

            if (window.childTokens != null) {
                final int childCount = window.childTokens.size();
                for (int i = 0; i < childCount; i++) {
                    IBinder childToken = window.childTokens.get(i);
                    final int childId = findWindowIdLocked(childToken);
                    if (childId >= 0) {
                        reportedWindow.addChild(childId);
                    }
                }
            }

            return reportedWindow;
        }

        private int getTypeForWindowManagerWindowType(int windowType) {
            switch (windowType) {
                case WindowManager.LayoutParams.TYPE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_STARTING:
                case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_BASE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION:
                case WindowManager.LayoutParams.TYPE_PHONE:
                case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                case WindowManager.LayoutParams.TYPE_TOAST:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_APPLICATION;
                }

                case WindowManager.LayoutParams.TYPE_INPUT_METHOD:
                case WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                }

                case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                case WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SCREENSHOT: {
                    return AccessibilityWindowInfo.TYPE_SYSTEM;
                }

                case WindowManager.LayoutParams.TYPE_DOCK_DIVIDER: {
                    return AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER;
                }

                case TYPE_ACCESSIBILITY_OVERLAY: {
                    return AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
                }

                default: {
                    return -1;
                }
            }
        }
    }

    private final class InteractionBridge {
        private final ComponentName COMPONENT_NAME =
                new ComponentName("com.android.server.accessibility", "InteractionBridge");

        private final Display mDefaultDisplay;
        private final int mConnectionId;
        private final AccessibilityInteractionClient mClient;

        public InteractionBridge() {
            final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            final UserState userState;
            synchronized (mLock) {
                userState = getCurrentUserStateLocked();
            }
            AccessibilityServiceConnection service = new AccessibilityServiceConnection(
                    userState, mContext,
                    COMPONENT_NAME, info, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                    AccessibilityManagerService.this, mWindowManagerService,
                    mGlobalActionPerformer) {
                @Override
                public boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
                    return true;
                }
            };

            mConnectionId = service.mId;

            mClient = AccessibilityInteractionClient.getInstance();
            mClient.addConnection(mConnectionId, service);

            //TODO: (multi-display) We need to support multiple displays.
            DisplayManager displayManager = (DisplayManager)
                    mContext.getSystemService(Context.DISPLAY_SERVICE);
            mDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }

        public void clearAccessibilityFocusNotLocked(int windowId) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked(windowId);
            if (focus != null) {
                focus.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            }
        }

        /**
         * Perform an accessibility action on the view that currently has accessibility focus.
         * Has no effect if no item has accessibility focus, if the item with accessibility
         * focus does not expose the specified action, or if the action fails.
         *
         * @param actionId The id of the action to perform.
         *
         * @return {@code true} if the action was performed. {@code false} if it was not.
         */
        public boolean performActionOnAccessibilityFocusedItemNotLocked(
                AccessibilityNodeInfo.AccessibilityAction action) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked();
            if ((focus == null) || !focus.getActionList().contains(action)) {
                return false;
            }
            return focus.performAction(action.getId());
        }

        public boolean getAccessibilityFocusClickPointInScreenNotLocked(Point outPoint) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked();
            if (focus == null) {
                return false;
            }

            synchronized (mLock) {
                Rect boundsInScreen = mTempRect;
                focus.getBoundsInScreen(boundsInScreen);

                // Apply magnification if needed.
                MagnificationSpec spec = getCompatibleMagnificationSpecLocked(focus.getWindowId());
                if (spec != null && !spec.isNop()) {
                    boundsInScreen.offset((int) -spec.offsetX, (int) -spec.offsetY);
                    boundsInScreen.scale(1 / spec.scale);
                }

                // Clip to the window bounds.
                Rect windowBounds = mTempRect1;
                getWindowBounds(focus.getWindowId(), windowBounds);
                if (!boundsInScreen.intersect(windowBounds)) {
                    return false;
                }

                // Clip to the screen bounds.
                Point screenSize = mTempPoint;
                mDefaultDisplay.getRealSize(screenSize);
                if (!boundsInScreen.intersect(0, 0, screenSize.x, screenSize.y)) {
                    return false;
                }

                outPoint.set(boundsInScreen.centerX(), boundsInScreen.centerY());
            }

            return true;
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked() {
            final int focusedWindowId;
            synchronized (mLock) {
                focusedWindowId = mSecurityPolicy.mAccessibilityFocusedWindowId;
                if (focusedWindowId == SecurityPolicy.INVALID_WINDOW_ID) {
                    return null;
                }
            }
            return getAccessibilityFocusNotLocked(focusedWindowId);
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked(int windowId) {
            return mClient.findFocus(mConnectionId,
                    windowId, AccessibilityNodeInfo.ROOT_NODE_ID,
                    AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        }
    }

    public class SecurityPolicy {
        public static final int INVALID_WINDOW_ID = -1;

        private static final int RETRIEVAL_ALLOWING_EVENT_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED
            | AccessibilityEvent.TYPE_VIEW_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
            | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SELECTED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
            | AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;

        // In Z order
        public List<AccessibilityWindowInfo> mWindows;
        public SparseArray<AccessibilityWindowInfo> mA11yWindowInfoById = new SparseArray<>();
        public SparseArray<WindowInfo> mWindowInfoById = new SparseArray<>();

        public int mActiveWindowId = INVALID_WINDOW_ID;
        public int mFocusedWindowId = INVALID_WINDOW_ID;
        public int mAccessibilityFocusedWindowId = INVALID_WINDOW_ID;
        public long mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;

        private boolean mTouchInteractionInProgress;

        private boolean canDispatchAccessibilityEventLocked(AccessibilityEvent event) {
            final int eventType = event.getEventType();
            switch (eventType) {
                // All events that are for changes in a global window
                // state should *always* be dispatched.
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                case AccessibilityEvent.TYPE_ANNOUNCEMENT:
                // All events generated by the user touching the
                // screen should *always* be dispatched.
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START:
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_START:
                case AccessibilityEvent.TYPE_GESTURE_DETECTION_END:
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT:
                // Also always dispatch the event that assist is reading context.
                case AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT:
                // Also windows changing should always be anounced.
                case AccessibilityEvent.TYPE_WINDOWS_CHANGED: {
                    return true;
                }
                // All events for changes in window content should be
                // dispatched *only* if this window is one of the windows
                // the accessibility layer reports which are windows
                // that a sighted user can touch.
                default: {
                    return isRetrievalAllowingWindow(event.getWindowId());
                }
            }
        }

        private boolean isValidPackageForUid(String packageName, int uid) {
            try {
                return uid == mPackageManager.getPackageUidAsUser(
                        packageName, UserHandle.getUserId(uid));
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        String resolveValidReportedPackageLocked(CharSequence packageName, int appId, int userId) {
            // Okay to pass no package
            if (packageName == null) {
                return null;
            }
            // The system gets to pass any package
            if (appId == Process.SYSTEM_UID) {
                return packageName.toString();
            }
            // Passing a package in your UID is fine
            final String packageNameStr = packageName.toString();
            final int resolvedUid = UserHandle.getUid(userId, appId);
            if (isValidPackageForUid(packageNameStr, resolvedUid)) {
                return packageName.toString();
            }
            // Appwidget hosts get to pass packages for widgets they host
            final AppWidgetManagerInternal appWidgetManager = getAppWidgetManager();
            if (appWidgetManager != null && ArrayUtils.contains(appWidgetManager
                            .getHostedWidgetPackages(resolvedUid), packageNameStr)) {
                return packageName.toString();
            }
            // Otherwise, set the package to the first one in the UID
            final String[] packageNames = mPackageManager.getPackagesForUid(resolvedUid);
            if (ArrayUtils.isEmpty(packageNames)) {
                return null;
            }
            // Okay, the caller reported a package it does not have access to.
            // Instead of crashing the caller for better backwards compatibility
            // we report the first package in the UID. Since most of the time apps
            // don't use shared user id, this will yield correct results and for
            // the edge case of using a shared user id we may report the wrong
            // package but this is fine since first, this is a cheating app and
            // second there is no way to get the correct package anyway.
            return packageNames[0];
        }

        String[] computeValidReportedPackages(int callingUid,
                String targetPackage, int targetUid) {
            if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
                // Empty array means any package is Okay
                return EmptyArray.STRING;
            }
            // IMPORTANT: The target package is already vetted to be in the target UID
            String[] uidPackages = new String[]{targetPackage};
            // Appwidget hosts get to pass packages for widgets they host
            final AppWidgetManagerInternal appWidgetManager = getAppWidgetManager();
            if (appWidgetManager != null) {
                final ArraySet<String> widgetPackages = appWidgetManager
                        .getHostedWidgetPackages(targetUid);
                if (widgetPackages != null && !widgetPackages.isEmpty()) {
                    final String[] validPackages = new String[uidPackages.length
                            + widgetPackages.size()];
                    System.arraycopy(uidPackages, 0, validPackages, 0, uidPackages.length);
                    final int widgetPackageCount = widgetPackages.size();
                    for (int i = 0; i < widgetPackageCount; i++) {
                        validPackages[uidPackages.length + i] = widgetPackages.valueAt(i);
                    }
                    return validPackages;
                }
            }
            return uidPackages;
        }

        public void clearWindowsLocked() {
            List<WindowInfo> windows = Collections.emptyList();
            final int activeWindowId = mActiveWindowId;
            updateWindowsLocked(windows);
            mActiveWindowId = activeWindowId;
            mWindows = null;
        }

        public void updateWindowsLocked(List<WindowInfo> windows) {
            if (mWindows == null) {
                mWindows = new ArrayList<>();
            }

            final int oldWindowCount = mWindows.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                mWindows.remove(i).recycle();
            }
            mA11yWindowInfoById.clear();

            for (int i = 0; i < mWindowInfoById.size(); i++) {
                mWindowInfoById.valueAt(i).recycle();
            }
            mWindowInfoById.clear();

            mFocusedWindowId = INVALID_WINDOW_ID;
            if (!mTouchInteractionInProgress) {
                mActiveWindowId = INVALID_WINDOW_ID;
            }

            // If the active window goes away while the user is touch exploring we
            // reset the active window id and wait for the next hover event from
            // under the user's finger to determine which one is the new one. It
            // is possible that the finger is not moving and the input system
            // filters out such events.
            boolean activeWindowGone = true;

            final int windowCount = windows.size();
            if (windowCount > 0) {
                for (int i = 0; i < windowCount; i++) {
                    WindowInfo windowInfo = windows.get(i);
                    AccessibilityWindowInfo window = (mWindowsForAccessibilityCallback != null)
                            ? mWindowsForAccessibilityCallback.populateReportedWindow(windowInfo)
                            : null;
                    if (window != null) {
                        final int windowId = window.getId();
                        if (window.isFocused()) {
                            mFocusedWindowId = windowId;
                            if (!mTouchInteractionInProgress) {
                                mActiveWindowId = windowId;
                                window.setActive(true);
                            } else if (windowId == mActiveWindowId) {
                                activeWindowGone = false;
                            }
                        }
                        mWindows.add(window);
                        mA11yWindowInfoById.put(windowId, window);
                        mWindowInfoById.put(windowId, WindowInfo.obtain(windowInfo));
                    }
                }

                if (mTouchInteractionInProgress && activeWindowGone) {
                    mActiveWindowId = mFocusedWindowId;
                }

                // Focused window may change the active one, so set the
                // active window once we decided which it is.
                final int accessibilityWindowCount = mWindows.size();
                for (int i = 0; i < accessibilityWindowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == mActiveWindowId) {
                        window.setActive(true);
                    }
                    if (window.getId() == mAccessibilityFocusedWindowId) {
                        window.setAccessibilityFocused(true);
                    }
                }
            }

            notifyWindowsChanged();
        }

        public boolean computePartialInteractiveRegionForWindowLocked(int windowId,
                Region outRegion) {
            if (mWindows == null) {
                return false;
            }

            // Windows are ordered in z order so start from the bottom and find
            // the window of interest. After that all windows that cover it should
            // be subtracted from the resulting region. Note that for accessibility
            // we are returning only interactive windows.
            Region windowInteractiveRegion = null;
            boolean windowInteractiveRegionChanged = false;

            final int windowCount = mWindows.size();
            for (int i = windowCount - 1; i >= 0; i--) {
                AccessibilityWindowInfo currentWindow = mWindows.get(i);
                if (windowInteractiveRegion == null) {
                    if (currentWindow.getId() == windowId) {
                        Rect currentWindowBounds = mTempRect;
                        currentWindow.getBoundsInScreen(currentWindowBounds);
                        outRegion.set(currentWindowBounds);
                        windowInteractiveRegion = outRegion;
                        continue;
                    }
                } else if (currentWindow.getType()
                        != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    Rect currentWindowBounds = mTempRect;
                    currentWindow.getBoundsInScreen(currentWindowBounds);
                    if (windowInteractiveRegion.op(currentWindowBounds, Region.Op.DIFFERENCE)) {
                        windowInteractiveRegionChanged = true;
                    }
                }
            }

            return windowInteractiveRegionChanged;
        }

        public void updateEventSourceLocked(AccessibilityEvent event) {
            if ((event.getEventType() & RETRIEVAL_ALLOWING_EVENT_TYPES) == 0) {
                event.setSource((View) null);
            }
        }

        public void updateActiveAndAccessibilityFocusedWindowLocked(int windowId, long nodeId,
                int eventType, int eventAction) {
            // The active window is either the window that has input focus or
            // the window that the user is currently touching. If the user is
            // touching a window that does not have input focus as soon as the
            // the user stops touching that window the focused window becomes
            // the active one. Here we detect the touched window and make it
            // active. In updateWindowsLocked() we update the focused window
            // and if the user is not touching the screen, we make the focused
            // window the active one.
            switch (eventType) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    // If no service has the capability to introspect screen,
                    // we do not register callback in the window manager for
                    // window changes, so we have to ask the window manager
                    // what the focused window is to update the active one.
                    // The active window also determined events from which
                    // windows are delivered.
                    synchronized (mLock) {
                        if (mWindowsForAccessibilityCallback == null) {
                            mFocusedWindowId = getFocusedWindowId();
                            if (windowId == mFocusedWindowId) {
                                mActiveWindowId = windowId;
                            }
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER: {
                    // Do not allow delayed hover events to confuse us
                    // which the active window is.
                    synchronized (mLock) {
                        if (mTouchInteractionInProgress && mActiveWindowId != windowId) {
                            setActiveWindowLocked(windowId);
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                    synchronized (mLock) {
                        if (mAccessibilityFocusedWindowId != windowId) {
                            mMainHandler.obtainMessage(MainHandler.MSG_CLEAR_ACCESSIBILITY_FOCUS,
                                    mAccessibilityFocusedWindowId, 0).sendToTarget();
                            mSecurityPolicy.setAccessibilityFocusedWindowLocked(windowId);
                            mAccessibilityFocusNodeId = nodeId;
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                    synchronized (mLock) {
                        if (mAccessibilityFocusNodeId == nodeId) {
                            mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                        }
                        // Clear the window with focus if it no longer has focus and we aren't
                        // just moving focus from one view to the other in the same window
                        if ((mAccessibilityFocusNodeId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID)
                                && (mAccessibilityFocusedWindowId == windowId)
                                && (eventAction != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                                ) {
                            mAccessibilityFocusedWindowId = INVALID_WINDOW_ID;
                        }
                    }
                } break;
            }
        }

        public void onTouchInteractionStart() {
            synchronized (mLock) {
                mTouchInteractionInProgress = true;
            }
        }

        public void onTouchInteractionEnd() {
            synchronized (mLock) {
                mTouchInteractionInProgress = false;
                // We want to set the active window to be current immediately
                // after the user has stopped touching the screen since if the
                // user types with the IME he should get a feedback for the
                // letter typed in the text view which is in the input focused
                // window. Note that we always deliver hover accessibility events
                // (they are a result of user touching the screen) so change of
                // the active window before all hover accessibility events from
                // the touched window are delivered is fine.
                final int oldActiveWindow = mSecurityPolicy.mActiveWindowId;
                setActiveWindowLocked(mFocusedWindowId);

                // If there is no service that can operate with active windows
                // we keep accessibility focus behavior to constrain it only in
                // the active window. Look at updateAccessibilityFocusBehaviorLocked
                // for details.
                if (oldActiveWindow != mSecurityPolicy.mActiveWindowId
                        && mAccessibilityFocusedWindowId == oldActiveWindow
                        && getCurrentUserStateLocked().mAccessibilityFocusOnlyInActiveWindow) {
                    mMainHandler.obtainMessage(MainHandler.MSG_CLEAR_ACCESSIBILITY_FOCUS,
                            oldActiveWindow, 0).sendToTarget();
                }
            }
        }

        public int getActiveWindowId() {
            if (mActiveWindowId == INVALID_WINDOW_ID && !mTouchInteractionInProgress) {
                mActiveWindowId = getFocusedWindowId();
            }
            return mActiveWindowId;
        }

        private void setActiveWindowLocked(int windowId) {
            if (mActiveWindowId != windowId) {
                mActiveWindowId = windowId;
                if (mWindows != null) {
                    final int windowCount = mWindows.size();
                    for (int i = 0; i < windowCount; i++) {
                        AccessibilityWindowInfo window = mWindows.get(i);
                        window.setActive(window.getId() == windowId);
                    }
                }
                notifyWindowsChanged();
            }
        }

        private void setAccessibilityFocusedWindowLocked(int windowId) {
            if (mAccessibilityFocusedWindowId != windowId) {
                mAccessibilityFocusedWindowId = windowId;
                if (mWindows != null) {
                    final int windowCount = mWindows.size();
                    for (int i = 0; i < windowCount; i++) {
                        AccessibilityWindowInfo window = mWindows.get(i);
                        window.setAccessibilityFocused(window.getId() == windowId);
                    }
                }

                notifyWindowsChanged();
            }
        }

        public void notifyWindowsChanged() {
            if (mWindowsForAccessibilityCallback == null) {
                return;
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                // Let the client know the windows changed.
                AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED);
                event.setEventTime(SystemClock.uptimeMillis());
                sendAccessibilityEvent(event, mCurrentUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public boolean canGetAccessibilityNodeInfoLocked(
                AccessibilityClientConnection service, int windowId) {
            return canRetrieveWindowContentLocked(service) && isRetrievalAllowingWindow(windowId);
        }

        public boolean canRetrieveWindowsLocked(AccessibilityClientConnection service) {
            return canRetrieveWindowContentLocked(service) && service.mRetrieveInteractiveWindows;
        }

        public boolean canRetrieveWindowContentLocked(AccessibilityClientConnection service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
        }

        public boolean canControlMagnification(AccessibilityClientConnection service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION) != 0;
        }

        public boolean canPerformGestures(AccessibilityServiceConnection service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
        }

        public boolean canCaptureFingerprintGestures(AccessibilityServiceConnection service) {
            return (service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES) != 0;
        }

        private int resolveProfileParentLocked(int userId) {
            if (userId != mCurrentUserId) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    UserInfo parent = mUserManager.getProfileParent(userId);
                    if (parent != null) {
                        return parent.getUserHandle().getIdentifier();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return userId;
        }

        public int resolveCallingUserIdEnforcingPermissionsLocked(int userId) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == 0
                    || callingUid == Process.SYSTEM_UID
                    || callingUid == Process.SHELL_UID) {
                if (userId == UserHandle.USER_CURRENT
                        || userId == UserHandle.USER_CURRENT_OR_SELF) {
                    return mCurrentUserId;
                }
                return resolveProfileParentLocked(userId);
            }
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (callingUserId == userId) {
                return resolveProfileParentLocked(userId);
            }
            final int callingUserParentId = resolveProfileParentLocked(callingUserId);
            if (callingUserParentId == mCurrentUserId &&
                    (userId == UserHandle.USER_CURRENT
                            || userId == UserHandle.USER_CURRENT_OR_SELF)) {
                return mCurrentUserId;
            }
            if (!hasPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                    && !hasPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)) {
                throw new SecurityException("Call from user " + callingUserId + " as user "
                        + userId + " without permission INTERACT_ACROSS_USERS or "
                        + "INTERACT_ACROSS_USERS_FULL not allowed.");
            }
            if (userId == UserHandle.USER_CURRENT
                    || userId == UserHandle.USER_CURRENT_OR_SELF) {
                return mCurrentUserId;
            }
            throw new IllegalArgumentException("Calling user can be changed to only "
                    + "UserHandle.USER_CURRENT or UserHandle.USER_CURRENT_OR_SELF.");
        }

        public boolean isCallerInteractingAcrossUsers(int userId) {
            final int callingUid = Binder.getCallingUid();
            return (Binder.getCallingPid() == android.os.Process.myPid()
                    || callingUid == Process.SHELL_UID
                    || userId == UserHandle.USER_CURRENT
                    || userId == UserHandle.USER_CURRENT_OR_SELF);
        }

        private boolean isRetrievalAllowingWindow(int windowId) {
            // The system gets to interact with any window it wants.
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                return true;
            }
            if (windowId == mActiveWindowId) {
                return true;
            }
            return findA11yWindowInfoById(windowId) != null;
        }

        public AccessibilityWindowInfo findA11yWindowInfoById(int windowId) {
            return mA11yWindowInfoById.get(windowId);
        }

        private WindowInfo findWindowInfoById(int windowId) {
            return mWindowInfoById.get(windowId);
        }

        private AccessibilityWindowInfo getPictureInPictureWindow() {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.inPictureInPicture()) {
                        return window;
                    }
                }
            }
            return null;
        }

        private void enforceCallingPermission(String permission, String function) {
            if (OWN_PROCESS_ID == Binder.getCallingPid()) {
                return;
            }
            if (!hasPermission(permission)) {
                throw new SecurityException("You do not have " + permission
                        + " required to call " + function + " from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            }
        }

        private boolean hasPermission(String permission) {
            return mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }

        private int getFocusedWindowId() {
            IBinder token = mWindowManagerService.getFocusedWindowToken();
            synchronized (mLock) {
                return findWindowIdLocked(token);
            }
        }
    }

    public class UserState {
        public final int mUserId;

        // Non-transient state.

        public final RemoteCallbackList<IAccessibilityManagerClient> mUserClients =
            new RemoteCallbackList<>();

        public final SparseArray<RemoteAccessibilityConnection> mInteractionConnections =
                new SparseArray<>();

        public final SparseArray<IBinder> mWindowTokens = new SparseArray<>();

        // Transient state.

        public final CopyOnWriteArrayList<AccessibilityServiceConnection> mBoundServices =
                new CopyOnWriteArrayList<>();

        public int mLastSentRelevantEventTypes = AccessibilityEvent.TYPES_ALL_MASK;

        public final Map<ComponentName, AccessibilityServiceConnection> mComponentNameToServiceMap =
                new HashMap<>();

        public final List<AccessibilityServiceInfo> mInstalledServices =
                new ArrayList<>();

        private final Set<ComponentName> mBindingServices = new HashSet<>();

        public final Set<ComponentName> mEnabledServices = new HashSet<>();

        public final Set<ComponentName> mTouchExplorationGrantedServices =
                new HashSet<>();

        public ComponentName mServiceChangingSoftKeyboardMode;

        public ComponentName mServiceToEnableWithShortcut;

        public int mLastSentClientState = -1;

        public int mSoftKeyboardShowMode = 0;

        public boolean mIsNavBarMagnificationAssignedToAccessibilityButton;
        public ComponentName mServiceAssignedToAccessibilityButton;

        public boolean mIsTouchExplorationEnabled;
        public boolean mIsTextHighContrastEnabled;
        public boolean mIsDisplayMagnificationEnabled;
        public boolean mIsNavBarMagnificationEnabled;
        public boolean mIsAutoclickEnabled;
        public boolean mIsPerformGesturesEnabled;
        public boolean mIsFilterKeyEventsEnabled;
        public boolean mAccessibilityFocusOnlyInActiveWindow;

        public UserState(int userId) {
            mUserId = userId;
        }

        public int getClientState() {
            int clientState = 0;
            final boolean a11yEnabled = (mUiAutomationManager.isUiAutomationRunningLocked()
                    || isHandlingAccessibilityEvents());
            if (a11yEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
            }
            // Touch exploration relies on enabled accessibility.
            if (a11yEnabled && mIsTouchExplorationEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
            }
            if (mIsTextHighContrastEnabled) {
                clientState |= AccessibilityManager.STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED;
            }
            return clientState;
        }

        public boolean isHandlingAccessibilityEvents() {
            return !mBoundServices.isEmpty() || !mBindingServices.isEmpty();
        }

        public void onSwitchToAnotherUser() {
            // Unbind all services.
            unbindAllServicesLocked(this);

            // Clear service management state.
            mBoundServices.clear();
            mBindingServices.clear();

            // Clear event management state.
            mLastSentClientState = -1;

            // Clear state persisted in settings.
            mEnabledServices.clear();
            mTouchExplorationGrantedServices.clear();
            mIsTouchExplorationEnabled = false;
            mIsDisplayMagnificationEnabled = false;
            mIsNavBarMagnificationEnabled = false;
            mServiceAssignedToAccessibilityButton = null;
            mIsNavBarMagnificationAssignedToAccessibilityButton = false;
            mIsAutoclickEnabled = false;
            mSoftKeyboardShowMode = 0;
        }

        public void addServiceLocked(AccessibilityServiceConnection serviceConnection) {
            if (!mBoundServices.contains(serviceConnection)) {
                serviceConnection.onAdded();
                mBoundServices.add(serviceConnection);
                mComponentNameToServiceMap.put(serviceConnection.mComponentName, serviceConnection);
                scheduleNotifyClientsOfServicesStateChange(this);
            }
        }

        /**
         * Removes a service.
         *
         * @param serviceConnection The service.
         */
        public void removeServiceLocked(AccessibilityServiceConnection serviceConnection) {
            mBoundServices.remove(serviceConnection);
            serviceConnection.onRemoved();
            // It may be possible to bind a service twice, which confuses the map. Rebuild the map
            // to make sure we can still reach a service
            mComponentNameToServiceMap.clear();
            for (int i = 0; i < mBoundServices.size(); i++) {
                AccessibilityServiceConnection boundClient = mBoundServices.get(i);
                mComponentNameToServiceMap.put(boundClient.mComponentName, boundClient);
            }
            scheduleNotifyClientsOfServicesStateChange(this);
        }

        public Set<ComponentName> getBindingServicesLocked() {
            return mBindingServices;
        }
    }

    private final class AccessibilityContentObserver extends ContentObserver {

        private final Uri mTouchExplorationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);

        private final Uri mDisplayMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);

        private final Uri mNavBarMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);

        private final Uri mAutoclickEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED);

        private final Uri mEnabledAccessibilityServicesUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        private final Uri mTouchExplorationGrantedAccessibilityServicesUri = Settings.Secure
                .getUriFor(Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES);

        private final Uri mDisplayInversionEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);

        private final Uri mDisplayDaltonizerEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);

        private final Uri mDisplayDaltonizerUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER);

        private final Uri mHighTextContrastUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED);

        private final Uri mAccessibilitySoftKeyboardModeUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE);

        private final Uri mAccessibilityShortcutServiceIdUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);

        private final Uri mAccessibilityButtonComponentIdUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT);

        public AccessibilityContentObserver(Handler handler) {
            super(handler);
        }

        public void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mTouchExplorationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mDisplayMagnificationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mNavBarMagnificationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mAutoclickEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mEnabledAccessibilityServicesUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mTouchExplorationGrantedAccessibilityServicesUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mDisplayInversionEnabledUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mDisplayDaltonizerEnabledUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mDisplayDaltonizerUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mHighTextContrastUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilitySoftKeyboardModeUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityShortcutServiceIdUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityButtonComponentIdUri, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                // Profiles share the accessibility state of the parent. Therefore,
                // we are checking for changes only the parent settings.
                UserState userState = getCurrentUserStateLocked();

                if (mTouchExplorationEnabledUri.equals(uri)) {
                    if (readTouchExplorationEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayMagnificationEnabledUri.equals(uri)
                        || mNavBarMagnificationEnabledUri.equals(uri)) {
                    if (readMagnificationEnabledSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAutoclickEnabledUri.equals(uri)) {
                    if (readAutoclickEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mEnabledAccessibilityServicesUri.equals(uri)) {
                    if (readEnabledAccessibilityServicesLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mTouchExplorationGrantedAccessibilityServicesUri.equals(uri)) {
                    if (readTouchExplorationGrantedAccessibilityServicesLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mDisplayDaltonizerEnabledUri.equals(uri)
                        || mDisplayDaltonizerUri.equals(uri)) {
                    updateDisplayDaltonizerLocked(userState);
                } else if (mDisplayInversionEnabledUri.equals(uri)) {
                    updateDisplayInversionLocked(userState);
                } else if (mHighTextContrastUri.equals(uri)) {
                    if (readHighTextContrastEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilitySoftKeyboardModeUri.equals(uri)) {
                    if (readSoftKeyboardShowModeChangedLocked(userState)) {
                        notifySoftKeyboardShowModeChangedLocked(userState.mSoftKeyboardShowMode);
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityShortcutServiceIdUri.equals(uri)) {
                    if (readAccessibilityShortcutSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityButtonComponentIdUri.equals(uri)) {
                    if (readAccessibilityButtonSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                }
            }
        }
    }
}
