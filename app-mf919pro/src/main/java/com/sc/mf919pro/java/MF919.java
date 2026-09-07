package com.sc.mf919pro.java;
import com.sc.mf919pro.kotlin.helper_common.ProMdbHost;
import mdb.MdbController;
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables;
import database.DbSchema;
import helpers.TerminalInfo;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.jakewharton.threetenabp.AndroidThreeTen;
import com.morefun.yapi.engine.DeviceServiceEngine;
import com.sc.mf919pro.BuildConfig;
import com.sc.mf919pro.R;
import com.sc.mf919pro.java.activity.Utils;
import com.sc.mf919pro.java.device.DeviceHelper;
import com.sc.mf919pro.kotlin.activity.CrashHandler;
import com.sc.mf919pro.kotlin.helper_common.Helper;
import com.sc.mf919pro.kotlin.helper_common.MfHelper;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;

import helpers.HelperCommon;

import java.io.File;

import helpers.AsyncLogWriter;
import helpers.CrashState;
import helpers.LogSessionMarker;
import helpers.FileLoggingTree;
import timber.log.Timber;


public class MF919 extends Application {

    private final String TAG = MF919.class.getName();
    private final String SERVICE_ACTION = "com.morefun.ysdk.service";
    private final String SERVICE_PACKAGE = "com.morefun.ysdk";
    private static MF919 instance;
    private DeviceServiceEngine deviceServiceEngine = null;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (isRecoveryProcess(base)) {
            return;
        }
        // Initialize the async file logger before anything logs (ContentProviders run after
        // this but before onCreate). Writes to filesDir/Logs -- the dir the TMS upload walks.
        AsyncLogWriter.init(new File(base.getFilesDir(), "Logs"));
        // Crash bookkeeping lives in filesDir root, not Logs: it is state the next start reads,
        // not a log the TMS job should ship. Initialised here so it is ready before the crash
        // handler can possibly fire.
        CrashState.init(base.getFilesDir());
    }

    /**
     * True in the lightweight :recovery process that CrashRecoveryActivity runs in.
     *
     * Application.onCreate runs in EVERY process of the app, so without this guard the recovery
     * process would bind the device service, start the HTTP server, schedule WorkManager jobs and
     * -- worst of all -- open the same log files the main process owns. Two processes appending to
     * one file interleaves their lines. The recovery process needs none of it: it shows a screen
     * and starts an activity.
     */
    private static boolean isRecoveryProcess(Context base) {
        String name = currentProcessName(base);
        return name != null && name.endsWith(":recovery");
    }

    private static String currentProcessName(Context base) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        // Works on every API level and needs no permission. cmdline is NUL-padded.
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/cmdline"))) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            int nul = line.indexOf('\0');
            return (nul >= 0 ? line.substring(0, nul) : line).trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (isRecoveryProcess(this)) {
            // See isRecoveryProcess: that process gets no app initialisation at all. It must not
            // even set `instance`, so nothing can mistake it for the live application.
            Log.d("MyApplication", "MF919:OnCreate skipped for :recovery process");
            return;
        }
        instance = this;
        // Single authoritative context init. Application.onCreate runs before any
        // activity, service, receiver or WorkManager worker, so ServiceHolder's
        // context can never be missing regardless of how the process starts.
        ServiceHolder.Companion.setContext(this);

        // :core cannot reference ServiceHolder or BuildConfig, so hand it the four values the
        // shared TMS handlers need (DEV-SN, APP-VER, the sequence number, and which environment
        // this build defaults to). Registered here because Application.onCreate runs before any
        // activity, service, receiver or worker, so no handler can observe an unregistered
        // provider. See helpers.TerminalInfo.
        TerminalInfo.register(new TerminalInfo.Provider() {
            @Override public String serialNumber() { return ServiceHolder.Companion.getTerminalSerialNumber(); }
            @Override public String appVersion()   { return ServiceHolder.Companion.getAppVersion(); }
            @Override public String sqnNum()       { return ServiceHolder.Companion.getSqnNum(); }
            @Override public String deviceModel()  { return ServiceHolder.Companion.getDeviceModel(); }
            @Override public String defaultEnvId() { return BuildConfig.DEFAULT_ENV; }
        });

        // :core's DbHandler self-heals missing tables but cannot see this app's schema -- the two
        // fleets' schemas have diverged (21 tables here vs 19 on the other, separate migration
        // histories), so each app registers its own table list rather than sharing one.
        DbSchema.register(java.util.Arrays.asList(DatabaseTables.values()));
        // MDB lives in :core and is shared with MF919; this hands it Pro's nav-graph navigation
        // and destination-based screen checks.
        MdbController.register(ProMdbHost.INSTANCE);

        DbSchema.onMigrationVersionReset(() -> {
            ServiceHolder.Companion.invalidateMigrationVersionCache();
            return kotlin.Unit.INSTANCE;
        });
        HelperCommon.Companion.setContext(getApplicationContext());
        Helper.Companion.getInstance().Initialize(getApplicationContext());
        // Plant logging trees before any log call: file persistence via logback (all
        // builds) + logcat in debug. logback reads assets/logback.xml on first use.
        Timber.plant(new FileLoggingTree());
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
        // Report how the PREVIOUS run ended, before anything else can muddy the log. A process
        // that was SIGKILLed cannot have written a close marker, so the absence of one is the
        // signal -- see LogSessionMarker.
        LogSessionMarker.openSession(new File(getFilesDir(), "Logs"), getApplicationContext());
        Log.d("MyApplication", "MF919:OnCreate");
        Utils.printLog("MF919:OnCreate");
        Utils.printLog("Application instance initialized");
        Log.d("MyApplication", "Application instance initialized");
        bindDeviceService();
        AndroidThreeTen.init(this);
        // Read the Context-dependent values the crash path needs BEFORE arming the handler.
        // getIPAddress() and checkIsConnectedWifi() are exactly the calls that used to throw
        // inside the handler on Android 10+; doing them here makes a throw survivable.
        CrashHandler.cacheContext(getApplicationContext());
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
    }

    public static MF919 getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Application has not been created yet!");
        }

        return instance;
    }

    public static Context getAppContext() {
        return getInstance().getApplicationContext();
    }

    public static MF919 getApp() {
        /*if (instance == null) {
            Utils.debugLogPrint("MF919", "getApp: " + instance);
        }*/
        return instance;
    }

    public DeviceServiceEngine getDeviceService() {
        return deviceServiceEngine;
    }

    public void bindDeviceService() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Utils.debugLogPrint(TAG, "========MF919:bindDeviceService=========");
                    if (deviceServiceEngine != null) {
                        return;
                    }

                    Intent intent = new Intent();
                    intent.setAction(SERVICE_ACTION);
                    intent.setPackage(SERVICE_PACKAGE);
                    bindService(intent, connection, Context.BIND_AUTO_CREATE);

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            deviceServiceEngine = null;
            Utils.debugLogPrint(TAG, "======onServiceDisconnected======");
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            deviceServiceEngine = DeviceServiceEngine.Stub.asInterface(service);
            Utils.debugLogPrint(TAG, "======onServiceConnected======");
            // Every call below is a synchronous transact over the YSDK binder, and
            // onServiceConnected runs on the main thread -- so a slow or hanging one is an ANR,
            // not a slow start. Measured on the SR800: setProperties never returns (both the
            // auto-start property and the aux-LCD backlight go through it, and that unit has no
            // auxiliary display), leaving the main thread parked in
            // IPCThreadState::waitForResponse until the app was killed with
            //   "MainActivity is not responding. Waited 5000ms for FocusEvent(hasFocus=true)"
            //
            // Moving one call at a time only exposes the next one, so the whole sequence runs on
            // a background thread. Nothing here touches the UI and nothing waits on the results.
            // One thread, not several, because the order matters: reset -> initDevices -> rest.
            new Thread(() -> {
                try {
                    Utils.debugLogPrint(TAG, "======Start======");
                    DeviceHelper.reset();
                    DeviceHelper.initDevices(MF919.this);
                    // Earliest point the device service binder is guaranteed live, so this is the
                    // earliest point the ROM auto-start property can be set. See
                    // DeviceHelper.enableAutoStartOnBoot for why a BOOT_COMPLETED receiver would not
                    // work on Android 10+ even if this app had one.
                    DeviceHelper.enableAutoStartOnBoot();
                    Bitmap bitmap = BitmapFactory.decodeResource(ServiceHolder.Companion.getMContext().getResources(), R.mipmap.sharecomm_logo);
                    MfHelper.INSTANCE.showAuxLcdImg(bitmap);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }, "device-service-init").start();

            linkToDeath(service);
        }

        private void linkToDeath(IBinder service) {
            try {
                service.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Utils.debugLogPrint(TAG, "======binderDied======");
                        deviceServiceEngine = null;
                        bindDeviceService();
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    };
}
