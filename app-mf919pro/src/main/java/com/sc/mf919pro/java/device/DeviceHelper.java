package com.sc.mf919pro.java.device;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

import com.morefun.yapi.engine.DeviceInfoConstrants;

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
import com.morefun.yapi.device.printer.Printer;
import com.morefun.yapi.device.reader.icc.IccCardReader;
import com.morefun.yapi.device.reader.mag.MagCardReader;
import com.morefun.yapi.device.scanner.InnerScanner;
import com.morefun.yapi.device.serialport.SerialPort;
import com.morefun.yapi.device.serialport.SerialPortDriver;
import com.morefun.yapi.emv.EmvHandler;
import com.morefun.yapi.emv.EmvRupayService;
import com.morefun.yapi.engine.DeviceServiceEngine;
import com.sc.mf919pro.java.MF919;
import com.sc.mf919pro.java.activity.Utils;
import utils.HexUtil;
import com.sc.mf919pro.kotlin.data_enum.AcquirerSettingModel;
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;


public class DeviceHelper {
    private final static String TAG = DeviceHelper.class.getName();
    public static MF919 application;

    private static PinPad pinpad;
    private static IccCardReader iccCardReader;
    private static CPUCardHandler cpuCardHandler;
    private static M1CardHandler m1CardHandler;
    private static IndustryCardHandler industryCardHandler;
    private static MagCardReader magCardReader;
    private static LEDDriver ledDriver;
    private static MultipleAppPrinter multipleAppPrinter;
    private static Printer printer;
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


    /**
     * Ask the ROM to relaunch this app after a boot, via the YSDK device service.
     *
     * The alternative -- a BOOT_COMPLETED receiver calling startActivity -- is a background
     * activity start, permitted on Android 7 and silently dropped from Android 10 onward. No
     * exception, nothing in logcat, the activity simply never appears. This app has no such
     * receiver at all, so before this it never came back from a power cycle on any version.
     *
     * Re-applied on every service connection rather than once: the property lives in the ROM, and
     * a factory reset, a ROM update or a package reinstall can drop it. Re-asserting it each start
     * costs one binder call and makes the setting self-healing.
     *
     * Never allowed to break service connection -- if the ROM does not support the property or the
     * binder is unavailable, the app must still start.
     */
    public static void enableAutoStartOnBoot() {
        try {
            Bundle bundle = new Bundle();
            bundle.putBoolean(DeviceInfoConstrants.AUTO_START_APP_ENABLE, true);
            // getPackageName(), not a literal: debug/stag builds carry .dev / .uat suffixes.
            bundle.putString(DeviceInfoConstrants.AUTO_START_APP_PACKAGE, application.getPackageName());

            // 0 == success, per the YSDK sample (SdkCallHelper.setProperties).
            int ret = getDeviceService().setProperties(bundle);
            if (ret == 0) {
                Utils.debugLogPrint(TAG, "Auto start on boot :: enabled for " + application.getPackageName());
            } else {
                Utils.debugLogPrint(TAG, "Auto start on boot :: REJECTED by device service, ret=" + ret);
            }
        } catch (Exception e) {
            Utils.debugLogPrint(TAG, "Auto start on boot :: failed -> " + e);
        }
    }

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
                multipleAppPrinter = getMultipleAppPrinter();
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
                Utils.printLog(e.getMessage());
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
                Utils.debugLogPrint("Checking", e.getMessage());
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
    public static MultipleAppPrinter getMultipleAppPrinter() throws RemoteException {
        if (multipleAppPrinter == null) {
            checkState();
            try {
                return application.getDeviceService().getMultipleAppPrinter();
            } catch (RemoteException e) {
                throw new RemoteException("PinPad service acquisition failed, please try again later.");
            }
        } else {
            return multipleAppPrinter;
        }
    }

    @SuppressLint("NewApi")
    public static Printer getPrinter() throws RemoteException {
        if (printer == null) {
            checkState();
            try {
                return application.getDeviceService().getPrinter();
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

    public static void updateSystemDatetime(String dateTime) throws RemoteException {
        try {
            application.getDeviceService().setSystemClock(dateTime);
        } catch (RemoteException e) {
            throw new RemoteException("Fail to update System Datetime");
        }
    }
}
