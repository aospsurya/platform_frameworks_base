/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.stats;

import static android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.getUidForPid;
import static android.os.storage.VolumeInfo.TYPE_PRIVATE;
import static android.os.storage.VolumeInfo.TYPE_PUBLIC;

import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.stats.pull.IonMemoryUtil.readProcessSystemIonHeapSizesFromDebugfs;
import static com.android.server.stats.pull.IonMemoryUtil.readSystemIonHeapSizeFromDebugfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.forEachPid;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readCmdlineFromProcfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readMemorySnapshotFromProcfs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalOpsRequest;
import android.app.AppOpsManager.HistoricalPackageOps;
import android.app.AppOpsManager.HistoricalUidOps;
import android.app.INotificationManager;
import android.app.ProcessMemoryState;
import android.app.StatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CoolingDevice;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IStatsCompanionService;
import android.os.IStatsd;
import android.os.IStoraged;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.StatsLogEventWrapper;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.stats.storage.StorageEnums;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.StatsLog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.IProcessStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.BinderCallsStats.ExportedCallStat;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.KernelCpuThreadReader;
import com.android.internal.os.KernelCpuThreadReaderDiff;
import com.android.internal.os.KernelCpuThreadReaderSettingsObserver;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.os.KernelWakelockReader;
import com.android.internal.os.KernelWakelockStats;
import com.android.internal.os.LooperStats;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.StoragedUidIoStatsReader;
import com.android.internal.util.DumpUtils;
import com.android.server.BinderCallsStatsService;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.am.MemoryStatUtil.MemoryStat;
import com.android.server.notification.NotificationManagerService;
import com.android.server.role.RoleManagerInternal;
import com.android.server.stats.pull.IonMemoryUtil.IonAllocations;
import com.android.server.stats.pull.ProcfsMemoryUtil.MemorySnapshot;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;

import com.google.android.collect.Sets;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 *
 * @hide
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {
    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;
    private static final long MILLIS_IN_A_DAY = TimeUnit.DAYS.toMillis(1);

    public static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";
    public static final String CONFIG_DIR = "/data/misc/stats-service";

    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = false;
    /**
     * Hard coded field ids of frameworks/base/cmds/statsd/src/uid_data.proto
     * to be used in ProtoOutputStream.
     */
    private static final int APPLICATION_INFO_FIELD_ID = 1;
    private static final int UID_FIELD_ID = 1;
    private static final int VERSION_FIELD_ID = 2;
    private static final int VERSION_STRING_FIELD_ID = 3;
    private static final int PACKAGE_NAME_FIELD_ID = 4;
    private static final int INSTALLER_FIELD_ID = 5;

    public static final int DEATH_THRESHOLD = 10;
    /**
     * Which native processes to snapshot memory for.
     *
     * <p>Processes are matched by their cmdline in procfs. Example: cat /proc/pid/cmdline returns
     * /system/bin/statsd for the stats daemon.
     */
    private static final Set<String> MEMORY_INTERESTING_NATIVE_PROCESSES = Sets.newHashSet(
            "/system/bin/statsd",  // Stats daemon.
            "/system/bin/surfaceflinger",
            "/system/bin/apexd",  // APEX daemon.
            "/system/bin/audioserver",
            "/system/bin/cameraserver",
            "/system/bin/drmserver",
            "/system/bin/healthd",
            "/system/bin/incidentd",
            "/system/bin/installd",
            "/system/bin/lmkd",  // Low memory killer daemon.
            "/system/bin/logd",
            "media.codec",
            "media.extractor",
            "media.metrics",
            "/system/bin/mediadrmserver",
            "/system/bin/mediaserver",
            "/system/bin/performanced",
            "/system/bin/tombstoned",
            "/system/bin/traced",  // Perfetto.
            "/system/bin/traced_probes",  // Perfetto.
            "webview_zygote",
            "zygote",
            "zygote64");
    /**
     * Lowest available uid for apps.
     *
     * <p>Used to quickly discard memory snapshots of the zygote forks from native process
     * measurements.
     */
    private static final int MIN_APP_UID = 10_000;

    private static final int CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES = 8;

    static final class CompanionHandler extends Handler {
        CompanionHandler(Looper looper) {
            super(looper);
        }
    }

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final INetworkStatsService mNetworkStatsService;
    @GuardedBy("sStatsdLock")
    private static IStatsd sStatsd;
    private static final Object sStatsdLock = new Object();

    private final OnAlarmListener mAnomalyAlarmListener = new AnomalyAlarmListener();
    private final OnAlarmListener mPullingAlarmListener = new PullingAlarmListener();
    private final OnAlarmListener mPeriodicAlarmListener = new PeriodicAlarmListener();
    private final BroadcastReceiver mAppUpdateReceiver;
    private final BroadcastReceiver mUserUpdateReceiver;
    private final ShutdownEventReceiver mShutdownEventReceiver;

    private StatsManagerService mStatsManagerService;

    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();
    private WifiManager mWifiManager = null;
    private TelephonyManager mTelephony = null;
    @GuardedBy("sStatsdLock")
    private final HashSet<Long> mDeathTimeMillis = new HashSet<>();
    @GuardedBy("sStatsdLock")
    private final HashMap<Long, String> mDeletedFiles = new HashMap<>();
    private final CompanionHandler mHandler;

    // Disables throttler on CPU time readers.
    private KernelCpuUidUserSysTimeReader mCpuUidUserSysTimeReader =
            new KernelCpuUidUserSysTimeReader(false);
    private KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    private KernelCpuUidFreqTimeReader mCpuUidFreqTimeReader =
            new KernelCpuUidFreqTimeReader(false);
    private KernelCpuUidActiveTimeReader mCpuUidActiveTimeReader =
            new KernelCpuUidActiveTimeReader(false);
    private KernelCpuUidClusterTimeReader mCpuUidClusterTimeReader =
            new KernelCpuUidClusterTimeReader(false);
    private StoragedUidIoStatsReader mStoragedUidIoStatsReader =
            new StoragedUidIoStatsReader();
    @Nullable
    private final KernelCpuThreadReaderDiff mKernelCpuThreadReader;

    private long mDebugElapsedClockPreviousValue = 0;
    private long mDebugElapsedClockPullCount = 0;
    private long mDebugFailingElapsedClockPreviousValue = 0;
    private long mDebugFailingElapsedClockPullCount = 0;
    private BatteryStatsHelper mBatteryStatsHelper = null;
    private static final int MAX_BATTERY_STATS_HELPER_FREQUENCY_MS = 1000;
    private long mBatteryStatsHelperTimestampMs = -MAX_BATTERY_STATS_HELPER_FREQUENCY_MS;

    private static IThermalService sThermalService;
    private File mBaseDir =
            new File(SystemServiceManager.ensureSystemDir(), "stats_companion");
    @GuardedBy("this")
    ProcessCpuTracker mProcessCpuTracker = null;

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mNetworkStatsService = INetworkStatsService.Stub.asInterface(
              ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        mBaseDir.mkdirs();
        mAppUpdateReceiver = new AppUpdateReceiver();
        mUserUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (sStatsdLock) {
                    if (sStatsd == null) {
                        Slog.w(TAG, "Could not access statsd for UserUpdateReceiver");
                        return;
                    }
                    try {
                        // Pull the latest state of UID->app name, version mapping.
                        // Needed since the new user basically has a version of every app.
                        informAllUidsLocked(context);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to inform statsd latest update of all apps", e);
                        forgetEverythingLocked();
                    }
                }
            }
        };
        mShutdownEventReceiver = new ShutdownEventReceiver();
        if (DEBUG) Slog.d(TAG, "Registered receiver for ACTION_PACKAGE_REPLACED and ADDED.");
        PowerProfile powerProfile = new PowerProfile(context);
        final int numClusters = powerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = powerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                    numSpeedSteps);
            firstCpuOfCluster += powerProfile.getNumCoresInCpuCluster(i);
        }

        // Enable push notifications of throttling from vendor thermal
        // management subsystem via thermalservice.
        IBinder b = ServiceManager.getService("thermalservice");

        if (b != null) {
            sThermalService = IThermalService.Stub.asInterface(b);
            try {
                sThermalService.registerThermalEventListener(
                        new ThermalEventListener());
                Slog.i(TAG, "register thermal listener successfully");
            } catch (RemoteException e) {
                // Should never happen.
                Slog.e(TAG, "register thermal listener error");
            }
        } else {
            Slog.e(TAG, "cannot find thermalservice, no throttling push notifications");
        }

        // Default NetworkRequest should cover all transport types.
        final NetworkRequest request = new NetworkRequest.Builder().build();
        final ConnectivityManager connectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.registerNetworkCallback(request, new ConnectivityStatsCallback());

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new CompanionHandler(handlerThread.getLooper());

        mKernelCpuThreadReader =
                KernelCpuThreadReaderSettingsObserver.getSettingsModifiedReader(mContext);
    }

    private final static int[] toIntArray(List<Integer> list) {
        int[] ret = new int[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    private final static long[] toLongArray(List<Long> list) {
        long[] ret = new long[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    // Assumes that sStatsdLock is held.
    @GuardedBy("sStatsdLock")
    private void informAllUidsLocked(Context context) throws RemoteException {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        PackageManager pm = context.getPackageManager();
        final List<UserHandle> users = um.getUserHandles(true);
        if (DEBUG) {
            Slog.d(TAG, "Iterating over " + users.size() + " userHandles.");
        }

        ParcelFileDescriptor[] fds;
        try {
            fds = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to create a pipe to send uid map data.", e);
            return;
        }
        sStatsd.informAllUidData(fds[0]);
        try {
            fds[0].close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to close the read side of the pipe.", e);
        }
        final ParcelFileDescriptor writeFd = fds[1];
        HandlerThread backgroundThread = new HandlerThread(
                "statsCompanionService.bg", THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        Handler handler = new Handler(backgroundThread.getLooper());
        handler.post(() -> {
            FileOutputStream fout = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd);
            try {
                ProtoOutputStream output = new ProtoOutputStream(fout);
                int numRecords = 0;
                // Add in all the apps for every user/profile.
                for (UserHandle userHandle : users) {
                    List<PackageInfo> pi =
                            pm.getInstalledPackagesAsUser(PackageManager.MATCH_UNINSTALLED_PACKAGES
                                            | PackageManager.MATCH_ANY_USER,
                                    userHandle.getIdentifier());
                    for (int j = 0; j < pi.size(); j++) {
                        if (pi.get(j).applicationInfo != null) {
                            String installer;
                            try {
                                installer = pm.getInstallerPackageName(pi.get(j).packageName);
                            } catch (IllegalArgumentException e) {
                                installer = "";
                            }
                            long applicationInfoToken =
                                    output.start(ProtoOutputStream.FIELD_TYPE_MESSAGE
                                            | ProtoOutputStream.FIELD_COUNT_REPEATED
                                                    | APPLICATION_INFO_FIELD_ID);
                            output.write(ProtoOutputStream.FIELD_TYPE_INT32
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE | UID_FIELD_ID,
                                            pi.get(j).applicationInfo.uid);
                            output.write(ProtoOutputStream.FIELD_TYPE_INT64
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                            | VERSION_FIELD_ID, pi.get(j).getLongVersionCode());
                            output.write(ProtoOutputStream.FIELD_TYPE_STRING
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                    | VERSION_STRING_FIELD_ID,
                                            pi.get(j).versionName);
                            output.write(ProtoOutputStream.FIELD_TYPE_STRING
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                            | PACKAGE_NAME_FIELD_ID, pi.get(j).packageName);
                            output.write(ProtoOutputStream.FIELD_TYPE_STRING
                                    | ProtoOutputStream.FIELD_COUNT_SINGLE
                                            | INSTALLER_FIELD_ID,
                                                    installer == null ? "" : installer);
                            numRecords++;
                            output.end(applicationInfoToken);
                        }
                    }
                }
                output.flush();
                if (DEBUG) {
                    Slog.d(TAG, "Sent data for " + numRecords + " apps");
                }
            } finally {
                IoUtils.closeQuietly(fout);
                backgroundThread.quit();
                backgroundThread.interrupt();
            }
        });
    }

    private final static class AppUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * App updates actually consist of REMOVE, ADD, and then REPLACE broadcasts. To avoid
             * waste, we ignore the REMOVE and ADD broadcasts that contain the replacing flag.
             * If we can't find the value for EXTRA_REPLACING, we default to false.
             */
            if (!intent.getAction().equals(Intent.ACTION_PACKAGE_REPLACED)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return; // Keep only replacing or normal add and remove.
            }
            if (DEBUG) Slog.d(TAG, "StatsCompanionService noticed an app was updated.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of an app update");
                    return;
                }
                try {
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        if (!replacing) {
                            // Don't bother sending an update if we're right about to get another
                            // intent for the new version that's added.
                            PackageManager pm = context.getPackageManager();
                            String app = intent.getData().getSchemeSpecificPart();
                            sStatsd.informOnePackageRemoved(app, uid);
                        }
                    } else {
                        PackageManager pm = context.getPackageManager();
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        String app = intent.getData().getSchemeSpecificPart();
                        PackageInfo pi = pm.getPackageInfo(app, PackageManager.MATCH_ANY_USER);
                        String installer;
                        try {
                            installer = pm.getInstallerPackageName(app);
                        } catch (IllegalArgumentException e) {
                            installer = "";
                        }
                        sStatsd.informOnePackage(
                                app,
                                uid,
                                pi.getLongVersionCode(),
                                pi.versionName == null ? "" : pi.versionName,
                                installer == null ? "" : installer);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to inform statsd of an app update", e);
                }
            }
        }
    }

    public final static class AnomalyAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            Slog.i(TAG, "StatsCompanionService believes an anomaly has occurred at time "
                    + System.currentTimeMillis() + "ms.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of anomaly alarm firing");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informAnomalyAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of anomaly alarm firing", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class PullingAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Slog.d(TAG, "Time to poll something.");
            }
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of pulling alarm firing.");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informPollAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of pulling alarm firing.", e);
                }
            }
        }
    }

    public final static class PeriodicAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Slog.d(TAG, "Time to trigger periodic alarm.");
            }
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of periodic alarm firing.");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informAlarmForSubscriberTriggeringFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of periodic alarm firing.", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class ShutdownEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * Skip immediately if intent is not relevant to device shutdown.
             */
            if (!intent.getAction().equals(Intent.ACTION_REBOOT)
                    && !(intent.getAction().equals(Intent.ACTION_SHUTDOWN)
                    && (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0)) {
                return;
            }

            Slog.i(TAG, "StatsCompanionService noticed a shutdown.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of a shutdown event.");
                    return;
                }
                try {
                    sStatsd.informDeviceShutdown();
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to inform statsd of a shutdown event.", e);
                }
            }
        }
    }

    @Override // Binder call
    public void setAnomalyAlarm(long timestampMs) {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) Slog.d(TAG, "Setting anomaly alarm for " + timestampMs);
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            // AlarmManager will automatically cancel any previous mAnomalyAlarmListener alarm.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, timestampMs, TAG + ".anomaly",
                    mAnomalyAlarmListener, mHandler);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAnomalyAlarm() {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) Slog.d(TAG, "Cancelling anomaly alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mAnomalyAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setAlarmForSubscriberTriggering(long timestampMs) {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) {
            Slog.d(TAG,
                    "Setting periodic alarm in about " + (timestampMs
                            - SystemClock.elapsedRealtime()));
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, timestampMs, TAG + ".periodic",
                    mPeriodicAlarmListener, mHandler);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAlarmForSubscriberTriggering() {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) {
            Slog.d(TAG, "Cancelling periodic alarm");
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPeriodicAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setPullingAlarm(long nextPullTimeMs) {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) {
            Slog.d(TAG, "Setting pulling alarm in about "
                    + (nextPullTimeMs - SystemClock.elapsedRealtime()));
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, nextPullTimeMs, TAG + ".pull",
                    mPullingAlarmListener, mHandler);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelPullingAlarm() {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) {
            Slog.d(TAG, "Cancelling pulling alarm");
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPullingAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void statsdReady() {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        if (DEBUG) {
            Slog.d(TAG, "learned that statsdReady");
        }
        sayHiToStatsd(); // tell statsd that we're ready too and link to it
        mContext.sendBroadcastAsUser(new Intent(StatsManager.ACTION_STATSD_STARTED)
                        .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND),
                UserHandle.SYSTEM, android.Manifest.permission.DUMP);
    }

    @Override
    public void triggerUidSnapshot() {
        StatsCompanion.enforceStatsCompanionPermission(mContext);
        synchronized (sStatsdLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                informAllUidsLocked(mContext);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to trigger uid snapshot.", e);
            } finally {
                restoreCallingIdentity(token);
            }
        }
    }


    // Statsd related code

    /**
     * Fetches the statsd IBinder service.
     * Note: This should only be called from sayHiToStatsd. All other clients should use the cached
     * sStatsd with a null check.
     */
    private static IStatsd fetchStatsdService() {
        return IStatsd.Stub.asInterface(ServiceManager.getService("stats"));
    }

    /**
     * Now that the android system is ready, StatsCompanion is ready too, so inform statsd.
     */
    void systemReady() {
        if (DEBUG) Slog.d(TAG, "Learned that systemReady");
        sayHiToStatsd();
    }

    void setStatsManagerService(StatsManagerService statsManagerService) {
        mStatsManagerService = statsManagerService;
    }

    /**
     * Tells statsd that statscompanion is ready. If the binder call returns, link to
     * statsd.
     */
    private void sayHiToStatsd() {
        synchronized (sStatsdLock) {
            if (sStatsd != null) {
                Slog.e(TAG, "Trying to fetch statsd, but it was already fetched",
                        new IllegalStateException(
                                "sStatsd is not null when being fetched"));
                return;
            }
            sStatsd = fetchStatsdService();
            if (sStatsd == null) {
                Slog.i(TAG,
                        "Could not yet find statsd to tell it that StatsCompanion is "
                                + "alive.");
                return;
            }
            mStatsManagerService.statsdReady(sStatsd);
            if (DEBUG) Slog.d(TAG, "Saying hi to statsd");
            try {
                sStatsd.statsCompanionReady();
                // If the statsCompanionReady two-way binder call returns, link to statsd.
                try {
                    sStatsd.asBinder().linkToDeath(new StatsdDeathRecipient(), 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath(StatsdDeathRecipient) failed", e);
                    forgetEverythingLocked();
                }
                // Setup broadcast receiver for updates.
                IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addDataScheme("package");
                mContext.registerReceiverForAllUsers(mAppUpdateReceiver, filter, null, null);

                // Setup receiver for user initialize (which happens once for a new user)
                // and
                // if a user is removed.
                filter = new IntentFilter(Intent.ACTION_USER_INITIALIZE);
                filter.addAction(Intent.ACTION_USER_REMOVED);
                mContext.registerReceiverForAllUsers(mUserUpdateReceiver, filter, null, null);

                // Setup receiver for device reboots or shutdowns.
                filter = new IntentFilter(Intent.ACTION_REBOOT);
                filter.addAction(Intent.ACTION_SHUTDOWN);
                mContext.registerReceiverForAllUsers(
                        mShutdownEventReceiver, filter, null, null);
                final long token = Binder.clearCallingIdentity();
                try {
                    // Pull the latest state of UID->app name, version mapping when
                    // statsd starts.
                    informAllUidsLocked(mContext);
                } finally {
                    restoreCallingIdentity(token);
                }
                Slog.i(TAG, "Told statsd that StatsCompanionService is alive.");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to inform statsd that statscompanion is ready", e);
                forgetEverythingLocked();
            }
        }
    }

    private class StatsdDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.i(TAG, "Statsd is dead - erase all my knowledge, except pullers");
            synchronized (sStatsdLock) {
                long now = SystemClock.elapsedRealtime();
                for (Long timeMillis : mDeathTimeMillis) {
                    long ageMillis = now - timeMillis;
                    if (ageMillis > MILLIS_IN_A_DAY) {
                        mDeathTimeMillis.remove(timeMillis);
                    }
                }
                for (Long timeMillis : mDeletedFiles.keySet()) {
                    long ageMillis = now - timeMillis;
                    if (ageMillis > MILLIS_IN_A_DAY * 7) {
                        mDeletedFiles.remove(timeMillis);
                    }
                }
                mDeathTimeMillis.add(now);
                if (mDeathTimeMillis.size() >= DEATH_THRESHOLD) {
                    mDeathTimeMillis.clear();
                    File[] configs = FileUtils.listFilesOrEmpty(new File(CONFIG_DIR));
                    if (configs.length > 0) {
                        String fileName = configs[0].getName();
                        if (configs[0].delete()) {
                            mDeletedFiles.put(now, fileName);
                        }
                    }
                }
                forgetEverythingLocked();
            }
        }
    }

    @GuardedBy("StatsCompanionService.sStatsdLock")
    private void forgetEverythingLocked() {
        sStatsd = null;
        mContext.unregisterReceiver(mAppUpdateReceiver);
        mContext.unregisterReceiver(mUserUpdateReceiver);
        mContext.unregisterReceiver(mShutdownEventReceiver);
        cancelAnomalyAlarm();
        cancelPullingAlarm();

        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats != null) {
            binderStats.reset();
        }

        LooperStats looperStats = LocalServices.getService(LooperStats.class);
        if (looperStats != null) {
            looperStats.reset();
        }
        mStatsManagerService.statsdNotReady();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        synchronized (sStatsdLock) {
            writer.println(
                    "Number of configuration files deleted: " + mDeletedFiles.size());
            if (mDeletedFiles.size() > 0) {
                writer.println("  timestamp, deleted file name");
            }
            long lastBootMillis =
                    SystemClock.currentThreadTimeMillis() - SystemClock.elapsedRealtime();
            for (Long elapsedMillis : mDeletedFiles.keySet()) {
                long deletionMillis = lastBootMillis + elapsedMillis;
                writer.println(
                        "  " + deletionMillis + ", " + mDeletedFiles.get(elapsedMillis));
            }
        }
    }

    // Thermal event received from vendor thermal management subsystem
    private static final class ThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(Temperature temp) {
            StatsLog.write(StatsLog.THERMAL_THROTTLING_SEVERITY_STATE_CHANGED, temp.getType(),
                    temp.getName(), (int) (temp.getValue() * 10), temp.getStatus());
        }
    }

    private static final class ConnectivityStatsCallback extends
            ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            StatsLog.write(StatsLog.CONNECTIVITY_STATE_CHANGED, network.netId,
                    StatsLog.CONNECTIVITY_STATE_CHANGED__STATE__CONNECTED);
        }

        @Override
        public void onLost(Network network) {
            StatsLog.write(StatsLog.CONNECTIVITY_STATE_CHANGED, network.netId,
                    StatsLog.CONNECTIVITY_STATE_CHANGED__STATE__DISCONNECTED);
        }
    }
}
