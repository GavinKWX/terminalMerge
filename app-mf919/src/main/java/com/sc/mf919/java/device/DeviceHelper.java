package com.sc.mf919.java.device;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

import com.morefun.yapi.card.cpu.CPUCardHandler;
import com.morefun.yapi.card.emulate.EmulateCardHandler;
import com.morefun.yapi.card.industry.IndustryCardHandler;
import com.morefun.yapi.card.mifare.M1CardHandler;
import com.morefun.yapi.device.beeper.Beeper;
import com.morefun.yapi.device.led.LEDDriver;
import com.morefun.yapi.device.logrecorder.LogRecorder;
import com.morefun.yapi.device.mdb.IMdbService;
import com.morefun.yapi.device.pinpad.PinPad;
import com.morefun.yapi.device.printer.MultipleAppPrinter;
import com.morefun.yapi.device.reader.icc.IccCardReader;
import com.morefun.yapi.device.reader.mag.MagCardReader;
import com.morefun.yapi.device.scanner.InnerScanner;
import com.morefun.yapi.device.serialport.SerialPort;
import com.morefun.yapi.device.serialport.SerialPortDriver;
import com.morefun.yapi.emv.EmvHandler;
import com.morefun.yapi.emv.EmvRupayService;
import com.morefun.yapi.engine.DeviceInfoConstrants;
import com.morefun.yapi.engine.DeviceServiceEngine;
import com.sc.mf919.java.MF919;
import com.sc.mf919.java.activity.Utils;
import utils.HexUtil;
import com.sc.mf919.kotlin.data_enum.AcquirerSettingModel;
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;


public class DeviceHelper {
    private final static String TAG = DeviceHelper.class.getName();
    public static MF919 application;

    // Last result of each ROM capability request, for logCapabilitySummary(). Volatile because
    // they are written on the binder callback thread and read from the same, but may be surfaced
    // elsewhere later.
    private static volatile boolean lastAutoStartOk = false;
    private static volatile boolean lastOomWhitelistOk = false;
    private static volatile boolean lastPowerSaveWhitelistOk = false;

    private static PinPad pinpad;
    private static IccCardReader iccCardReader;
    private static CPUCardHandler cpuCardHandler;
    private static M1CardHandler m1CardHandler;
    private static IndustryCardHandler industryCardHandler;
    private static MagCardReader magCardReader;
    private static LEDDriver ledDriver;
    private static MultipleAppPrinter printer;
    private static SerialPortDriver serialPortDriver;
    private static SerialPort usbSerialPort;
    private static Beeper beeper;
    private static EmvHandler emvHandler;
    private static InnerScanner innerScanner;
    private static LogRecorder logRecorder;
    private static EmvRupayService emvRupayService;
    private static DeviceServiceEngine deviceServiceEngine;
    private static EmulateCardHandler emulateCardHandler;
    private static IMdbService mdbService;

    @SuppressLint("NewApi")
    public static void initDevices(MF919 application) throws RemoteException {
        DeviceHelper.application = application;

        if (application == null) {
            return;
        }

        if (application.getDeviceService() != null) {
            try {
                pinpad = getPinpad();
                magCardReader = getMagCardReader();
                ledDriver = getLedDriver();
                printer = getPrinter();
                beeper = getBeeper();
                emvHandler = getEmvHandler();
                innerScanner = getInnerScanner();
                logRecorder = getLogRecorder();
                emvRupayService = getEmvRupayService();
                emulateCardHandler = getEmulateCardHandler();
                mdbService = getMdbService();
                resetAID();
                resetCAPK();

            } catch (RemoteException e) {
                e.printStackTrace();
                throw e;
            }
        } else {
            application.bindDeviceService();
            reset();
        }
    }

    public static void resetAID() {
        clearAID();
        downloadAID();
        downloadMydebitAID();
    }

    public static void resetCAPK() {
        clearCAPK();
        downloadCAPK();
    }

    /**
     * Ask the ROM to launch this package on every boot.
     *
     * StartMyServiceAtBootReceiver cannot do this any more. It calls startActivity() from a
     * BOOT_COMPLETED broadcast, which is a background activity start -- allowed on Android 7,
     * silently dropped from Android 10 onward with no exception and nothing in logcat. On the
     * fleet that shows up as only the Android 7 terminals coming back after a power cycle.
     *
     * Handing the job to the YSDK device service removes the problem rather than working around
     * it: the ROM starts us, so no background-start restriction applies on any OS version. The
     * receiver stays as-is for the Android 7 units and as a fallback where the ROM does not
     * implement this property.
     *
     * Deliberately re-applied on every process start instead of being latched behind a
     * SharedPreferences flag, so the setting self-heals if it is ever cleared (factory reset,
     * TMS wipe, ROM update). It is one binder call and the ROM treats it as idempotent.
     */
    public static void enableAutoStartOnBoot() {
        try {
            Bundle bundle = new Bundle();
            bundle.putBoolean(DeviceInfoConstrants.AUTO_START_APP_ENABLE, true);
            // getPackageName(), not a literal: debug/stag builds carry .dev / .uat suffixes.
            bundle.putString(DeviceInfoConstrants.AUTO_START_APP_PACKAGE, application.getPackageName());

            int ret = getDeviceService().setProperties(bundle);
            lastAutoStartOk = (ret == 0);
            // 0 == success, per the YSDK sample (SdkCallHelper.setProperties).
            if (ret == 0) {
                Utils.debugLogPrint(TAG, "Auto start on boot :: enabled for " + application.getPackageName());
            } else {
                Utils.debugLogPrint(TAG, "Auto start on boot :: REJECTED by device service, ret=" + ret);
            }
        } catch (Exception e) {
            // Never let this break service connection -- the app must still start when the ROM
            // does not support the property or the binder is unavailable.
            Utils.debugLogPrint(TAG, "Auto start on boot :: failed -> " + e);
        }
    }

    /**
     * Ask the ROM to protect this process from being reclaimed.
     *
     * Two independent lists, applied together because they defend against the same outcome from
     * different directions: the OOM adjuster list keeps the low-memory killer from choosing us,
     * and the power-save list keeps the battery/standby manager from freezing or stopping us.
     *
     * This is the fix for the incident LogSessionMarker was written to diagnose: on terminal
     * 98211000002538 a sale completed, uploaded its receipt, and then the process simply vanished
     * -- the log jumped from 14:32 to 14:34 where it cold-started three times, with no crash and
     * no clean exit, on a device running with MemFree in the tens of MB. Auto-start brings the app
     * back after a reboot; this stops it being taken in the first place.
     *
     * Requires a 6.05.08+ YSDK service. Calling it against an older one is harmless -- verified on
     * device against 6.05.01: no crash, no RemoteException, the call is delivered (setProperties
     * keeps its transaction id across jar versions) and silently ignored.
     *
     * But it returns 0 anyway. An older service accepts the unknown keys and reports success, so
     * ret==0 means "call accepted", NOT "feature supported" -- which is why the service version is
     * logged alongside. Without it every 6.05 terminal would claim protection it does not have,
     * and that log line would be read as evidence during the next low-memory investigation.
     *
     * NOTE this ties the project to the 6.14 jar: the four constants do not exist in 6.05, so
     * reverting the jar will not compile until this method goes or moves behind reflection.
     *
     * Re-applied every process start for the same self-healing reason as
     * {@link #enableAutoStartOnBoot()}.
     *
     * Note the effect is not observable from outside: a bench A/B against /proc/<pid>/oom_score_adj
     * showed no change with the whitelist on or off, so ret==0 confirms only that the ROM accepted
     * the call, not that it protects anything. Pending confirmation from Morefun.
     */
    /**
     * Version of the installed YSDK service, or "unknown".
     *
     * Logged next to the whitelist results because ret==0 does NOT mean the feature is supported.
     * Verified on device: a 6.05.01 service returns 0 for SET_OOM_ADJUSTER_WHITE even though those
     * keys were only added in 6.05.08, so it accepts the call and silently ignores it. Without the
     * version in the log, every 6.05 terminal would report protection it does not have.
     */
    private static String ysdkServiceVersion() {
        try {
            return application.getPackageManager()
                    .getPackageInfo("com.morefun.ysdk", 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * One stable, greppable line summarising what the ROM actually granted this terminal.
     *
     * TerminaLog is already uploaded to TMS, so this makes fleet state queryable without a backend
     * change: grep the uploaded logs for TERMINAL_CAPABILITY and you can see, per terminal, whether
     * auto-start took and which YSDK service is installed. That matters because the same APK
     * behaves differently on a 6.05 service (whitelist keys accepted and ignored) than on 6.14.
     *
     * A structured field on the DeviceInfo API would be better than log-scraping, but that needs a
     * backend contract change -- raised separately rather than blocking this.
     */
    public static void logCapabilitySummary() {
        Utils.debugLogPrint(TAG, "TERMINAL_CAPABILITY :: autostart=" + (lastAutoStartOk ? "OK" : "FAIL")
                + " oom=" + (lastOomWhitelistOk ? "ACCEPTED" : "FAIL")
                + " powersave=" + (lastPowerSaveWhitelistOk ? "ACCEPTED" : "FAIL")
                + " ysdk=" + ysdkServiceVersion()
                + " os=" + android.os.Build.VERSION.RELEASE + "(API" + android.os.Build.VERSION.SDK_INT + ")"
                + " pkg=" + application.getPackageName()
                + " app=" + ServiceHolder.Companion.getAppVersion());
    }

    public static void enableProcessProtection() {
        String pkg = application.getPackageName();
        String svc = ysdkServiceVersion();

        try {
            HashMap<String, Boolean> oomList = new HashMap<>();
            oomList.put(pkg, true);

            Bundle bundle = new Bundle();
            bundle.putBoolean(DeviceInfoConstrants.SET_OOM_ADJUSTER_WHITE, true);
            bundle.putSerializable(DeviceInfoConstrants.OOM_ADJUSTER_WHITE_LIST, oomList);

            int ret = getDeviceService().setProperties(bundle);
            lastOomWhitelistOk = (ret == 0);
            Utils.debugLogPrint(TAG, "OOM whitelist :: " + (ret == 0 ? "accepted for " + pkg : "REJECTED ret=" + ret) + " by ysdk " + svc + " -- support added in 6.05.08; older services accept and ignore");
        } catch (Exception e) {
            Utils.debugLogPrint(TAG, "OOM whitelist :: failed -> " + e);
        }

        // Deliberately a separate try/catch and a separate binder call: on a ROM that supports one
        // list but not the other, we still want the supported one applied, and we want the log to
        // say which is which.
        try {
            HashMap<String, Boolean> powerList = new HashMap<>();
            powerList.put(pkg, true);

            Bundle bundle = new Bundle();
            bundle.putBoolean(DeviceInfoConstrants.SET_APP_POWER_SAVE_WHITE, true);
            bundle.putSerializable(DeviceInfoConstrants.POWER_SAVE_WHITE_PACKAGE_NAME_LIST, powerList);

            int ret = getDeviceService().setProperties(bundle);
            lastPowerSaveWhitelistOk = (ret == 0);
            Utils.debugLogPrint(TAG, "Power-save whitelist :: " + (ret == 0 ? "accepted for " + pkg : "REJECTED ret=" + ret) + " by ysdk " + svc + " -- support added in 6.05.08; older services accept and ignore");
        } catch (Exception e) {
            Utils.debugLogPrint(TAG, "Power-save whitelist :: failed -> " + e);
        }
    }

    public static DeviceServiceEngine getDeviceService() throws RemoteException {
        if (application == null) {
            throw new RemoteException("application is null, please try again later.");
        }

        if (application.getDeviceService() == null) {
            checkState();
        }
        return application.getDeviceService();
    }

    public static void checkState() throws RemoteException {
        if (application == null) {
            Utils.debugLogPrint(TAG, "============checkState application == null");
            Utils.debugLogPrint(TAG, "Init device again");
            application = MF919.getApp();
        }

        // getApp() is null until MF919.onCreate has run, and always null in the :recovery
        // process. Report that as RemoteException rather than letting the call below throw NPE.
        if (application == null) {
            throw new RemoteException("Application not initialised, device service unavailable.");
        }

        if (application.getDeviceService() == null) {
            reset();
            application.bindDeviceService();
            throw new RemoteException("Device service connection failed, please try again later.");
        }
    }


    @SuppressLint("NewApi")
    public static PinPad getPinpad() throws RemoteException {
        if (pinpad == null) {
            checkState();
            try {
                Utils.printLog("Init PinPad");
                return application.getDeviceService().getPinPad();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            Utils.printLog("Already Init PinPad");
            return pinpad;
        }
    }


    @SuppressLint("NewApi")
    public static IccCardReader getIccCardReader(int cardType) throws RemoteException {
        if (iccCardReader == null) {
            checkState();
            try {
                return application.getDeviceService().getIccCardReader(cardType);
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return iccCardReader;
        }
    }


    @SuppressLint("NewApi")
    public static CPUCardHandler getCpuCardHandler(IccCardReader iccCardReader) throws RemoteException {
        if (cpuCardHandler == null) {
            checkState();
            try {
                return application.getDeviceService().getCPUCardHandler(iccCardReader);
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return cpuCardHandler;
        }
    }

    @SuppressLint("NewApi")
    public static M1CardHandler getM1CardHandler(IccCardReader iccCardReader) throws RemoteException {
        if (m1CardHandler == null) {
            checkState();
            try {
                return application.getDeviceService().getM1CardHandler(iccCardReader);
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return m1CardHandler;
        }
    }

    @SuppressLint("NewApi")
    public static IndustryCardHandler getIndustryCardHandler(IccCardReader iccCardReader) throws RemoteException {
        if (industryCardHandler == null) {
            checkState();
            try {
                return application.getDeviceService().getIndustryCardHandler(iccCardReader);
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return industryCardHandler;
        }
    }

    @SuppressLint("NewApi")
    public static MagCardReader getMagCardReader() throws RemoteException {
        if (magCardReader == null) {
            checkState();
            try {
                return application.getDeviceService().getMagCardReader();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return magCardReader;
        }
    }

    @SuppressLint("NewApi")
    public static LEDDriver getLedDriver() throws RemoteException {
        if (ledDriver == null) {
            checkState();
            try {
                return application.getDeviceService().getLEDDriver();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return ledDriver;
        }
    }

    @SuppressLint("NewApi")
    public static MultipleAppPrinter getPrinter() throws RemoteException {
        if (printer == null) {
            checkState();
            try {
                return application.getDeviceService().getMultipleAppPrinter();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return printer;
        }
    }

    @SuppressLint("NewApi")
    public static SerialPortDriver getSerialPortDriver(int port) throws RemoteException {
        if (serialPortDriver == null) {
            checkState();
            try {
                return application.getDeviceService().getSerialPortDriver(port);
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return serialPortDriver;
        }
    }

    @SuppressLint("NewApi")
    public static SerialPort getUsbSerialPort(String path) throws RemoteException {
        if (usbSerialPort == null) {
            checkState();
            try {
                return application.getDeviceService().getSerialPort(path);
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return usbSerialPort;
        }
    }
    @SuppressLint("NewApi")
    public static Beeper getBeeper() throws RemoteException {
        if (beeper == null) {
            checkState();
            try {
                return application.getDeviceService().getBeeper();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return beeper;
        }
    }

    @SuppressLint("NewApi")
    public static EmvHandler getEmvHandler() throws RemoteException {
        if (emvHandler == null) {
            checkState();
            try {
                return application.getDeviceService().getEmvHandler();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return emvHandler;
        }
    }

    @SuppressLint("NewApi")
    public static EmvRupayService getEmvRupayService() throws RemoteException {
        if (emvRupayService == null) {
            checkState();
            try {
                return application.getDeviceService().getEmvRupayService();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return emvRupayService;
        }
    }

    @SuppressLint("NewApi")
    public static InnerScanner getInnerScanner() throws RemoteException {
        if (innerScanner == null) {
            checkState();
            try {
                return application.getDeviceService().getInnerScanner();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return innerScanner;
        }
    }

    @SuppressLint("NewApi")
    public static LogRecorder getLogRecorder() throws RemoteException {
        if (logRecorder == null) {
            checkState();
            try {
                return application.getDeviceService().getLogRecorder();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return logRecorder;
        }
    }

    @SuppressLint("NewApi")
    public static EmulateCardHandler getEmulateCardHandler() throws RemoteException {
        if (emulateCardHandler == null) {
            checkState();
            try {
                return application.getDeviceService().getEmulateCardHandler();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return emulateCardHandler;
        }
    }

    public static IMdbService getMdbService() throws RemoteException {
        if (mdbService == null) {
            checkState();
            try {
                return application.getDeviceService().getMdbService();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return mdbService;
        }
    }

    public static void reset() {
        pinpad = null;
        iccCardReader = null;
        cpuCardHandler = null;
        m1CardHandler = null;
        industryCardHandler = null;
        magCardReader = null;
        ledDriver = null;
        printer = null;
        serialPortDriver = null;
        usbSerialPort = null;
        beeper = null;
        emvHandler = null;
        innerScanner = null;
        logRecorder = null;
        emulateCardHandler = null;
        mdbService = null;
    }

    private static void downloadAID() {
        String aidCapFile = "";

        AcquirerSettingModel acqSetting = ServiceHolder.Companion.getAcquirerSetting();
        String acqName = acqSetting.getAcqName();
        switch (acqName.toUpperCase(Locale.ENGLISH)){
            case "PAYDEE": {
                aidCapFile = "aidcappara/paydee/aidList.txt";
                break;
            }
            case "FINEXUS": {
                aidCapFile = "aidcappara/finexus/aidList.txt";
                break;
            }
            case "BSN_CARDZONE":
            case "BSN": {
                aidCapFile = "aidcappara/bsn/aidList.txt";
                break;
            }
            default: aidCapFile = "aidcappara/gobiz/aidList.txt";
        }
        //aidCapFile = "aidcappara/gobiz/aidList_master.txt"; //For Certification

        try {
            InputStream ins;
            ins = ServiceHolder.Companion.getContext().getAssets().open(aidCapFile);
            BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
            String str;
            while ((str = buf.readLine()) != null) {
                String tip = "Download aid:" + str;
                int ret = DeviceHelper.getEmvHandler().addAidParam(HexUtil.hexStringToByte(str));
                Utils.debugLogPrint("TAG", tip + ":Status:" + ret);
                SystemClock.sleep(50);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadMydebitAID() {
        String aidCapFile = "aidcappara/mydebit/aidList.txt";

        DbModelTerminalConfig terminalConfig = ServiceHolder.Companion.getTerminalConfig();
        boolean optIn = DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "OptIn");
        if(optIn){
            try {
                InputStream ins;
                ins = ServiceHolder.Companion.getContext().getAssets().open(aidCapFile);
                BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
                String str;
                while ((str = buf.readLine()) != null) {
                    String tip = "Download aid:" + str;
                    int ret = DeviceHelper.getEmvHandler().addAidParam(HexUtil.hexStringToByte(str));
                    Utils.debugLogPrint("TAG", tip + ":Status:" + ret);
                    SystemClock.sleep(50);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void clearAID() {
        try {
            DeviceHelper.getEmvHandler().clearAIDParam();
        } catch (RemoteException e) {
            System.out.println("Error in ClearAID");
        }
    }

    private static void downloadCAPK() {
        String aidCapFile = "";

        AcquirerSettingModel acqSetting = ServiceHolder.Companion.getAcquirerSetting();
        String acqName = acqSetting.getAcqName();
        switch (acqName.toUpperCase(Locale.ENGLISH)){
            case "PAYDEE": {
                aidCapFile = "aidcappara/paydee/capkList.txt";
                break;
            }
            case "FINEXUS": {
                aidCapFile = "aidcappara/finexus/capkList.txt";
                break;
            }
            case "BSN_CARDZONE":
            case "BSN": {
                aidCapFile = "aidcappara/bsn/capkList.txt";
                break;
            }
            default: aidCapFile = "aidcappara/gobiz/capkList.txt";
        }
        //aidCapFile = "aidcappara/gobiz/capkList_master.txt"; //For Certification
        //aidCapFile = "aidcappara/gobiz/capkList_visa.txt"; //For Certification

        try {
            InputStream ins;
            ins = ServiceHolder.Companion.getContext().getAssets().open(aidCapFile);
            BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
            String str;
            while ((str = buf.readLine()) != null) {
                String tip = "Download capk:" + str;
                int ret = DeviceHelper.getEmvHandler().addCAPKParam(HexUtil.hexStringToByte(str));
                Utils.debugLogPrint("TAG", tip + ":Status:" + ret);
                SystemClock.sleep(50);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void clearCAPK() {
        try {
            DeviceHelper.getEmvHandler().clearCAPKParam();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sets the terminal clock. Calls checkState() first, like the other accessors here, so that a
     * device service that is not bound yet surfaces as a RemoteException the caller can handle
     * rather than a NullPointerException.
     */
    public static void updateSystemDatetime(String dateTime) throws RemoteException {
        checkState();
        try {
            application.getDeviceService().setSystemClock(dateTime);
        } catch (RemoteException e) {
            throw new RemoteException("Fail to update System Datetime");
        }
    }
}
