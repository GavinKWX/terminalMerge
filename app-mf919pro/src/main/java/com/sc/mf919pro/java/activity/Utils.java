package com.sc.mf919pro.java.activity;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.text.format.Formatter;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import android.view.Gravity;

import com.library.terminal.TLVData;
import com.library.terminal.Utility;
import com.morefun.yapi.device.printer.FontFamily;
import com.sc.mf919pro.R;
import utils.HexUtil;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;

import org.threeten.bp.LocalDateTime;

import timber.log.Timber;
import org.threeten.bp.format.DateTimeFormatter;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Utils extends AppCompatActivity {
    private final static String TAG = "Utils";
    private final static int ONE_LINE_PRINT_PX = 376;

    public static boolean isDebugMsgEnable() {
        /*String temp = getFileValue(TransFieldConstant.files.configFile, "DebugLog");
        if (temp == null) {temp = "0";}
        return (!temp.equals("0"));*/
        return true;
    }

    public static void debugLogPrint(String TAG1, String msg) {
        // Routed through Timber -> FileLoggingTree (logback, async file write) +
        // DebugTree (logcat, debug builds only). No synchronous disk I/O here.
        Timber.tag(TAG1).d(msg);
    }

    public static void printLog(String log) {
        //if (ServiceHolder.getContext() != null) {
            /*if (fileSizeInKb(TransFieldConstant.files.LogFile) > 1000)
            {
                deleteFiles(TransFieldConstant.files.LogFile);
            }
            log = ASCIItoHexString(log);
            String len = zeroPadding(Integer.toString(log.length()), 4);
            try {
                writeToFile(len + encrypt(log, encryptKey()), TransFieldConstant.files.LogFile);
            } catch (Exception e) {
                e.printStackTrace();
            }*/
        //}
        Utils.debugLogPrint(TAG, log);
    }

    public static void printErrorLog(String TAGs, String functionName, String logs) {
        printLog(TAGs + "--" + functionName + " ---> Error:" + logs);
    }

    /*private static class CounterSign {
        private DatabaseAccess DAs;
        private String tableName = TransFieldConstant.CountSign.TableName;

        CounterSign() {
            DAs = new DatabaseAccess(ServiceHolder.getContext(), TransFieldConstant.files.SystemTrace);
        }

        private void updateSign(String type, String msg) {
            try {
                msg = zeroPadding(Integer.toString(msg.length()), 4) + encrypt(msg, encryptKey());
            } catch (Exception e) {
                e.printStackTrace();
            }
            DAs.updateSQL(type, msg, tableName, 1);
        }

        void updateAdminPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.adminPw, msg);
        }

        void updateSettingPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.settingPw, msg);
        }

        void updateSystemPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.systemPw, msg);
        }

        void updateVendorPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.vendorPw, msg);
        }

        String getKeys() {
            String tempKey = "736172696C6C6565436f686572656E74";
            String key = DAs.getSQLValue(TransFieldConstant.CountSign.keyPw, tableName, 1);
            try {
                key = decrypt(key, tempKey);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return key;
        }

        private String getSign(String type) {
            String data = DAs.getSQLValue(type, tableName, 1);
            try {
                String len = data.substring(0, 4);
                data = data.substring(4);
                data = decrypt(data, encryptKey());
                data = data.substring(0, Integer.parseInt(len));
            } catch (Exception e) {
                e.printStackTrace();
            }
            return data;
        }

        boolean verifyAdminPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.adminPw);
            return (msg.equals(value));
        }

        boolean verifySettingPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.settingPw);
            return (msg.equals(value));
        }

        boolean verifySystemPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.systemPw);
            return (msg.equals(value));
        }

        boolean verifyVendorPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.vendorPw);
            return (msg.equals(value));
        }
    }*/

    public static long fileSizeInKb(String fileName) { return utils.FileOps.fileSizeInKb(fileName); }

    public static void downloadApk() {
        new Thread() {
            @Override
            public void run() {
                super.run();
                String[] value = checkForUpdates();
                System.out.println(Arrays.toString(value));
                if (value[0].equals("1")) {
                    String paths = "";
                    int count;
                    try {
                        URL url = new URL(value[2] + value[1]);
                        Utils.debugLogPrint(TAG, "downloadApk : " + url);
                        URLConnection connection = url.openConnection();
                        connection.connect();
                        int lengthOfFile = connection.getContentLength();
                        InputStream input = new BufferedInputStream(url.openStream(), 8192);
                        // F7 — fixed name, plus a sweep of the legacy random-named downloads.
                        // The old "download(<random>)_app.apk" left an orphan on every failed or
                        // interrupted download, with nothing able to find them afterwards.
                        String downloadDir = ServiceHolder.Companion.getExternalStoragePaths() + "/files";
                        try {
                            File[] stale = new File(downloadDir).listFiles((dir, name) ->
                                    name.startsWith("download") && name.endsWith("_app.apk"));
                            if (stale != null) {
                                for (File f : stale) {
                                    Utils.debugLogPrint(TAG, "Removing stale APK download: " + f.getName());
                                    f.delete();
                                }
                            }
                        } catch (Exception e) {
                            Utils.debugLogPrint(TAG, "Stale APK sweep failed: " + e);
                        }
                        paths = downloadDir + "/download_app.apk";
                        Utils.debugLogPrint(TAG, "run: " + paths);
                        OutputStream output = new FileOutputStream(paths);
                        byte[] data = new byte[1024];
                        long total = 0;
                        while ((count = input.read(data)) != -1) {
                            total += count;
                            output.write(data, 0, count);
                            Utils.debugLogPrint(TAG, "Download Progress % : " + (int) ((total * 100) / lengthOfFile));
                        }
                        Utils.debugLogPrint(TAG, "Downloaded APK");
                        output.flush();
                        output.close();
                        input.close();

                        String checkSum = "";
                        if (new File(paths).exists()) {
                            checkSum = getChecksum(paths);
                            if (checkFiles("ApkCheckSum.txt")) {
                                String[] valueC = readFromFile("ApkCheckSum.txt");
                                if (!checkSum.equals(valueC[0])) {
                                    File fileO = new File(paths);
                                    File fileC = new File(ServiceHolder.Companion.getExternalStoragePaths() + "/files/app.apk");
                                    if (fileC.exists()) {
                                        fileC.delete();
                                    }
                                    if (fileO.renameTo(fileC)) {
                                        debugLogPrint(TAG, "Rename the file : " + true);
                                        write2File(new String[]{checkSum}, "ApkCheckSum.txt");
                                    }
                                    fileO.delete();
                                } else {
                                    File fileO = new File(paths);
                                    File fileC = new File(ServiceHolder.Companion.getExternalStoragePaths() + "/files/app.apk");
                                    if (fileC.exists()) {
                                        fileO.delete();
                                    } else {
                                        if (fileO.renameTo(fileC)) {
                                            debugLogPrint(TAG, "Rename the file : " + true);
                                            write2File(new String[]{checkSum}, "ApkCheckSum.txt");
                                        }
                                    }
                                    fileO.delete();
                                }
                            } else {
                                File fileO = new File(paths);
                                File fileC = new File(ServiceHolder.Companion.getExternalStoragePaths() + "/files/app.apk");
                                if (fileC.exists()) {
                                    fileC.delete();
                                }
                                if (fileO.renameTo(fileC)) {
                                    debugLogPrint(TAG, "Rename the file : " + true);
                                    write2File(new String[]{checkSum}, "ApkCheckSum.txt");
                                }
                                fileO.delete();
                            }
                        }
                    } catch (Exception e) {
                        if (paths.isEmpty()) {
                            Utils.debugLogPrint(TAG, "run1: connection fail");
                        }
                        File fileO = new File(paths);
                        if (fileO.exists()) {
                            fileO.delete();
                        }
                        printErrorLog(TAG, "downloadApk", e.getMessage());
                        Utils.debugLogPrint(TAG, "run: connection fail");

                    }
                }
            }
        }.start();
    }

    public static void removeInstallApk(String name) { utils.FileOps.removeInstallApk(name); }

    public static String getInstallApk() { return utils.FileOps.getInstallApk(); }

    public static String[] readFromFilePath(String path) { return utils.FileOps.readFromFilePath(path); }

    public static void writeToFile(String data, String filename) { utils.FileOps.writeToFile(data, filename); }

    public static String[] readFromFile(String filename) { return utils.FileOps.readFromFile(filename); }

    public static boolean checkFiles(String filename) { return utils.FileOps.checkFiles(filename); }

    static boolean isDataExist() {
        /*String DB_PATH = ServiceHolder.getContext().getApplicationInfo().dataDir + "/databases/";
        boolean dbSys=(new File(DB_PATH, TransFieldConstant.files.SystemTrace)).exists();
        return (dbSys);*/
        return true;
    }

    public static String[] checkForUpdates() {
        String paths = ServiceHolder.Companion.getExternalStoragePaths() + "/files/script.txt";
        String[] lines = readFromFilePath(paths);
        String details = null;
        String[] returnValue = new String[]{"0", "", "", ""};
        String appVersion = "";
        long iAppVesrsion = 0;
        try {
            PackageManager packageManager = ServiceHolder.Companion.getContext().getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(ServiceHolder.Companion.getContext().getApplicationInfo().packageName, 0);
            PackageInfo pInfo = ServiceHolder.Companion.getContext().getPackageManager().getPackageInfo(ServiceHolder.Companion.getContext().getPackageName(), 0);
            appVersion = pInfo.versionName;
            iAppVesrsion = Long.parseLong(appVersion.replaceAll("\\.", ""));
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        if (lines[0] != null) {
            for (String line : lines) {
                //GeneralMethod.debugLogPrint(TAG, "checkForUpdates: " + line);
                if (line.startsWith("Update")) {
                    if (line.contains(ServiceHolder.Companion.getContext().getString(R.string.app_name))) {
                        details = line;
                        break;
                    }
                }
            }
        }
        if (details != null) {
            details = details.replace(",", "\n");
            String[] array = String2ArrayString(details);
            System.out.println(Arrays.toString(array));
            for (String _line : array) {
                if (_line.startsWith("appVersion")) {
                    long newVersion = Long.parseLong(_line.split("=")[1].replaceAll("\\.", ""));
                    Utils.debugLogPrint(TAG, "checkForUpdates: " + iAppVesrsion + "  " + newVersion);
                    if (iAppVesrsion < newVersion) {
                        returnValue[0] = "1";
                    } else {
                        returnValue[0] = "0";
                    }
                }
                if (_line.startsWith("fileName")) {
                    returnValue[1] = _line.split("=")[1];
                }
                if (_line.startsWith("url")) {
                    returnValue[2] = _line.substring(4);
                }
                if (_line.startsWith("checkSum")) {
                    Utils.debugLogPrint(TAG, "checkForUpdates: " + _line);
                    returnValue[3] = _line.split("=")[1];
                }
            }
        }

        if (returnValue[0].equals("1")) {
            if (checkFiles("ApkCheckSum.txt")) {
                String[] valueC = readFromFile("ApkCheckSum.txt");
                Utils.debugLogPrint(TAG, "checkForUpdates: " + valueC[0] + "-->" + returnValue[3]);
                if (returnValue[3].equals(valueC[0])) {
                    returnValue[0] = "2";
                    if (!new File(ServiceHolder.Companion.getExternalStoragePaths() + "/files/app.apk").exists()) {
                        returnValue[0] = "1";
                    }
                } else {
                    //GeneralMethod.debugLogPrint(TAG,"Delete Apk : " + new File(ServiceHolder.getExternalStoragePaths() + "/files/app.apk").delete());
                }
            } else {
                /*File nfile = new File(ServiceHolder.getExternalStoragePaths() + "/files/app.apk");
                if(nfile.exists())
                {
                    GeneralMethod.debugLogPrint(TAG,"Delete Apk : " + nfile.delete());
                }*/
            }
        }
        System.out.println(Arrays.toString(returnValue));
        return returnValue;
    }

    public static String[] String2ArrayString(String value) { return utils.ByteOps.String2ArrayString(value); }

    public static int[] findCharWithLoc(String data, char value) { return utils.ByteOps.findCharWithLoc(data, value); }

    // Still a stub: the real digest below is commented out and every call returns "123".
    // Its only caller is getChecksum, on the dormant downloadApk path. See the audit doc.
    public static String hashData(String input, String hash) {
        try {
            MessageDigest md = MessageDigest.getInstance(hash);
            //byte[] messageDigest = md.digest(HexUtil.hexStringToByte(input));
            //return HexUtil.bytesToHexString(messageDigest);
            return "123";
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getChecksum(String appPaths) {
        String value = readFileInByteFromPath(appPaths);
        return (hashData(value, "MD5"));
    }

    protected static String readFileInByteFromPath(String filename) {
        File file = new File(filename);
        String value = "";
        byte[] bytesArray = new byte[(int) file.length()];
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            int a = fis.read(bytesArray); //read file into bytes[]
            fis.close();
            //value = HexUtil.bytesToHexString(bytesArray);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }

    public static void write2File(String[] arr1, String filename) { utils.FileOps.write2File(arr1, filename); }

    public static void deleteFiles(String filename) { utils.FileOps.deleteFiles(filename); }


    public static void DelayMili(int duration) { utils.Util.DelayMili(duration); }

    public static byte bcd2hex(byte val) { return utils.ByteOps.bcd2hex(val); }

    public static byte hex2bcd(byte val) { return utils.ByteOps.hex2bcd(val); }

    public static byte hex2bcd(int val) { return utils.ByteOps.hex2bcd(val); }

    public static byte hex2bcd(long val) { return utils.ByteOps.hex2bcd(val); }

    // Delegates to :core -- see utils.ByteOps.
    public static int set_ushort(long value, byte[] destbuf, int destbufoffset) { return utils.ByteOps.set_ushort(value, destbuf, destbufoffset); }

    //
    // C functions wrapping
    //
    public static int strcmp(String string1, String string2) { return utils.ByteOps.strcmp(string1, string2); }

    public static int arrayCopy(byte[] source, int soureoffset, byte[] dest, int destoffset, int len) { return utils.ByteOps.arrayCopy(source, soureoffset, dest, destoffset, len); }

    // Delegates to :core -- see utils.ByteOps.
    public static int memcpy(byte[] destBuf, int destBufOffset, byte[] sourceBuf, int sourceBufOffset, int len) { return utils.ByteOps.memcpy(destBuf, destBufOffset, sourceBuf, sourceBufOffset, len); }

    // Delegates to :core -- see utils.ByteOps.
    public static int memcpy(byte[] destBuf, byte[] sourceBuf, int len) { return utils.ByteOps.memcpy(destBuf, sourceBuf, len); }

    public static int memcmp(byte[] data1, int data1Offset, byte[] data2, int data2Offset, int dataLen) { return utils.ByteOps.memcmp(data1, data1Offset, data2, data2Offset, dataLen); }

    public static int memcmp(byte[] data1, int data1Offset, String data2, int dataLen) {
        byte[] byteData2 = data2.getBytes(StandardCharsets.US_ASCII);
        return memcmp(data1, data1Offset, byteData2, 0, dataLen);
    }

    public static int memcmp(byte[] data1, String data2, int dataLen) { return utils.ByteOps.memcmp(data1, data2, dataLen); }

    public static int arrayFill(byte data, byte[] dest, int destoffset, int len) { return utils.ByteOps.arrayFill(data, dest, destoffset, len); }

    // Delegates to :core -- see utils.ByteOps.
    public static int memset(byte[] dest, int destOffset, byte setValue, int length) { return utils.ByteOps.memset(dest, destOffset, setValue, length); }

    // Delegates to :core -- see utils.ByteOps.
    public static int memset(byte[] dest, byte setValue, int length) { return utils.ByteOps.memset(dest, setValue, length); }

    // Delegates to :core -- see utils.ByteOps.
    public static int set_short(short value, byte[] destbuf) { return utils.ByteOps.set_short(value, destbuf); }

    // Delegates to :core -- see utils.ByteOps.
    public static int set_ushort(short value, byte[] destbuf, int destbufoffset) { return utils.ByteOps.set_ushort(value, destbuf, destbufoffset); }

    // Delegates to :core -- see utils.ByteOps.
    public static short get_ushort(byte[] data, int dataoffset) { return utils.ByteOps.get_ushort(data, dataoffset); }

    //return index value
    public static int sscanf(String dataIn, String lookFor, String[] foundValue) { return utils.ByteOps.sscanf(dataIn, lookFor, foundValue); }

    //return parse value
    public static int sscanf(byte[] dataIn, int dataInOffset, int dataInLength, String lookFor, String[] foundValue) {
        String strData = byteArrayToAsciiString(dataIn, dataInOffset, dataInLength);

        return sscanf(strData, lookFor, foundValue);
    }

    // Delegates to :core -- see utils.ByteOps.
    public static int strlen(String data) { return utils.ByteOps.strlen(data); }

    public static int atoi(String value) { return utils.ByteOps.atoi(value); }

    public static long convertLong(String value) { return utils.ByteOps.convertLong(value); }

    public static String removeWhiteSpace(String InputText) { return utils.StringUtils.removeWhiteSpace(InputText); }

    // Delegates to :core -- see utils.ByteOps.
    public static String byteArrayToHexString(byte[] Input, int Offset, int Length, String spacing) { return utils.ByteOps.byteArrayToHexString(Input, Offset, Length, spacing); }

    // Delegates to :core -- see utils.ByteOps.
    public static String byteArrayToHexString(byte[] Input, int Offset, int Length) { return utils.ByteOps.byteArrayToHexString(Input, Offset, Length); }

    // Delegates to :core -- see utils.ByteOps.
    public static String byteArrayToHexString(byte[] Input) { return utils.ByteOps.byteArrayToHexString(Input); }

    /*public static byte[] hexStringToByteArray(String data)
    {
        data = removeWhiteSpace(data);

        byte[] endResult = new byte[data.length() / 2];
        for (int i = 0; i < data.length(); i += 2)
        {
            endResult[Math.Abs(i / 2)] = byte.Parse(data.substring(i, 2), System.Globalization.NumberStyles.HexNumber);
        }
        return endResult;
    }*/
    // Delegates to :core -- see utils.ByteOps.
    public static byte[] hexStringToByteArray(String hex) { return utils.ByteOps.hexStringToByteArray(hex); }

    /*public int hexStringToByteArray(String data, byte[] HexOut, int HexOutOffset)
    {
        int iLen = 0;

        data = removeWhiteSpace(data);

        for (int i = 0; i < data.length(); i += 2)
        {
            HexOut[HexOutOffset + iLen] = byte.Parse(data.substring(i, 2), System.Globalization.NumberStyles.HexNumber);
            iLen += 1;
        }

        return iLen;
    }*/

    public static byte toByte(char c) { return utils.ByteOps.toByte(c); }

    public byte hexStringToByte(String HexStringByte) {
        char[] achar = HexStringByte.toUpperCase().toCharArray();
        byte b = (byte) (toByte(achar[0]) << 4 | toByte(achar[1]));
        return b;
    }

    public static void bin2bcd(int dataIn, int dataInLength, byte[] outBuffer, int outBufferOffset) { utils.ByteOps.bin2bcd(dataIn, dataInLength, outBuffer, outBufferOffset); }

    // Delegates to :core -- see utils.ByteOps.
    public static int bcd2bin(byte[] inBuf, int inBufOffset, int inBufLen) { return utils.ByteOps.bcd2bin(inBuf, inBufOffset, inBufLen); }

    public static int bcd2Int(byte[] bcdData, int bcdDataOffset, int bcdDataLen) { return utils.ByteOps.bcd2Int(bcdData, bcdDataOffset, bcdDataLen); }

    public static String byteArrayToAsciiString(byte[] DataIn, int Offset, int Length) {
        byte[] data = new byte[Length];
        memcpy(data, 0, DataIn, Offset, Length);

        String ascii = new String(data);

        return ascii;
    }

    public static String byteArrayToAsciiString(byte[] DataIn) {
        return byteArrayToAsciiString(DataIn, 0, DataIn.length);

    }

    public static byte[] AsciiToByteArray(String DataIn) { return utils.ByteOps.AsciiToByteArray(DataIn); }

    public static byte[] ASCIItoByte(String info) { return utils.ByteOps.ASCIItoByte(info); }

    public static String ASCIItoHexString(String info) { return utils.ByteOps.ASCIItoHexString(info); }

    public static String symbolString(String Symbol, int Len) { return utils.StringUtils.symbolString(Symbol, Len); }

    public static String removeCarNumChar(String CardNu) { return utils.StringUtils.removeCarNumChar(CardNu); }

    public static String hideCardDetails(String cdNum) { return utils.Util.hideCardDetails(cdNum); }

    public static String hideCardDetails(String CardNu, boolean b) { return utils.Util.hideCardDetails(CardNu, b); }

    public static String DateFormat(String date) { return utils.StringUtils.DateFormat(date); }

    public static String TimeFormat(String time) { return utils.StringUtils.TimeFormat(time); }

    public static String DateTimeFormat(String datetime) {
        try {
            datetime = datetime.substring(0, 4) + "-" + datetime.substring(4, 6)
                    + "-" + datetime.substring(6, 8) + " " + datetime.substring(8, 10) + ":" + datetime.substring(10, 12)
                    + ":" + datetime.substring(12, 14);
        } catch (Exception exc) {
            Utils.debugLogPrint(TAG, "DateTimeFormat Exception: " + datetime);
            Utils.debugLogPrint(TAG, "DateTimeFormat Exception: " + exc.getMessage());
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            datetime = now.format(formatter);

            // Manually convert "yyyyMMddHHmmss" to "yyyy-MM-dd HH:mm:ss"
            datetime = datetime.substring(0, 4) + "-" + datetime.substring(4, 6)
                    + "-" + datetime.substring(6, 8) + " " + datetime.substring(8, 10)
                    + ":" + datetime.substring(10, 12) + ":" + datetime.substring(12, 14);
        }
        return (datetime);
    }

    public static String mask_pan(String value) { return utils.StringUtils.mask_pan(value); }

    public static String spaceBtwNoChar(String value, int space) { return utils.StringUtils.spaceBtwNoChar(value, space); }

    public static String blankSpace(int i) { return utils.StringUtils.blankSpace(i); }

    // Delegates to :core so the padding logic exists once. crypto.Encryption needed it
    // and could not depend on this class.
    public static String paddingWith(String pin, String f, int length, boolean end) {
        return utils.StringUtils.paddingWith(pin, f, length, end);
    }

    /*private static class CounterSign {
        private DatabaseAccess DAs;
        private String tableName = TransFieldConstant.CountSign.TableName;

        CounterSign() {
            DAs = new DatabaseAccess(ServiceHolder.getContext(), TransFieldConstant.files.SystemTrace);
        }

        private void updateSign(String type, String msg) {
            try {
                msg = zeroPadding(Integer.toString(msg.length()), 4) + encrypt(msg, encryptKey());
            } catch (Exception e) {
                e.printStackTrace();
            }
            DAs.updateSQL(type, msg, tableName, 1);
        }

        void updateAdminPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.adminPw, msg);
        }

        void updateSettingPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.settingPw, msg);
        }

        void updateSystemPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.systemPw, msg);
        }

        void updateVendorPW(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            updateSign(TransFieldConstant.CountSign.vendorPw, msg);
        }

        String getKeys() {
            String tempKey = "736172696C6C6565436f686572656E74";
            String key = DAs.getSQLValue(TransFieldConstant.CountSign.keyPw, tableName, 1);
            try {
                key = decrypt(key, tempKey);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return key;
        }

        private String getSign(String type) {
            String data = DAs.getSQLValue(type, tableName, 1);
            try {
                String len = data.substring(0, 4);
                data = data.substring(4);
                data = decrypt(data, encryptKey());
                data = data.substring(0, Integer.parseInt(len));
            } catch (Exception e) {
                e.printStackTrace();
            }
            return data;
        }

        boolean verifyAdminPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.adminPw);
            return (msg.equals(value));
        }

        boolean verifySettingPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.settingPw);
            return (msg.equals(value));
        }

        boolean verifySystemPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.systemPw);
            return (msg.equals(value));
        }

        boolean verifyVendorPw(String msg) {
            msg = hashData(ASCIItoHexString(msg), "SHA-1");
            String value = getSign(TransFieldConstant.CountSign.vendorPw);
            return (msg.equals(value));
        }
    }*/

    public static void cpAssetFile(String filename) { utils.FileOps.cpAssetFile(filename); }

    public static String[] readFromAssetFile(String filename) { return utils.FileOps.readFromAssetFile(filename); }


    // Delegates to :core -- see utils.ByteOps.
    public static int Byte2Int(byte b) { return utils.ByteOps.Byte2Int(b); }

    public static String zeroPadding(String s, int LengthOfString) { return utils.AmountFormat.zeroPadding(s, LengthOfString); }

    public static String Byte2ASCII(byte[] b) { return utils.ByteOps.Byte2ASCII(b); }

    public static String getActualAmount(String value) { return utils.AmountFormat.getActualAmount(value); }

    public static String getIPAddress() {
        try {
            Context context = ServiceHolder.Companion.getContext();
            if (context == null) {
                return UNKNOWN_IP;
            }

            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) {
                return UNKNOWN_IP;
            }

            return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        } catch (Exception e) {
            return UNKNOWN_IP;
        }
    }

    /** Placeholder when the local IP cannot be read; keeps log records shaped the same. */
    public static final String UNKNOWN_IP = "0.0.0.0";

    public static String getPublicIP() { return helpers.PublicIp.get(); }

    public static String CVMAnalysis(String CVM, String entryMode) { return utils.ReceiptText.CVMAnalysis(CVM, entryMode); }

    public static class TextItem {
        private String text;
        private int font = FontFamily.MIDDLE;
        private int paddingAlign = Gravity.LEFT;
        private int fillSpaceNum = 0;
        private float pxSize = 0;

        public TextItem(String text) {
            this.text = text;
        }

        public TextItem setFont(int font) {
            this.font = font;
            return this;
        }

        public TextItem setPaddingAlign(int align) {
            this.paddingAlign = align;
            return this;
        }

        public TextItem setFillSpaceNum(int num) {
            //Utils.debugLogPrint(TAG, "setFillSpaceNum:" + num);
            this.fillSpaceNum = num;
            return this;
        }

        public String getText() {
            if (text == null) {
                return "";
            }

            if (getAlign() == Gravity.RIGHT) {
                text = addPadding(text, true, ' ', fillSpaceNum);
            }

            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getOriginalText() {
            return this.text;
        }

        public int getFont() {
            return font;
        }

        public int getAlign() {
            return paddingAlign;
        }

        public float getPxSize() {
            return pxSize;
        }

        public TextItem setPxSize(float pxSize) {
            this.pxSize = pxSize;
            return this;
        }
    }

    private static String addPadding(String src, boolean isLeft, char padding, int fixLen) {
        StringBuilder b = new StringBuilder();

        for (int i = 0; i < fixLen; ++i) {
            b.append(padding);
        }

        if (isLeft) {
            b.append(src);
        } else {
            b.insert(0, src);
        }

        return b.toString();
    }

    public static String makeLineText(TextItem... textItems) {
        StringBuilder text = new StringBuilder();
        float textPX = 0;

        for (TextItem textItem : textItems) {
            textPX += measureTextPX(textItem.getText(), textItem.font);
            if (textItem.getAlign() == Gravity.RIGHT) {
                int fillSpaceNum = 0;
                if (textItem.getFont() == FontFamily.BIG) {
                    fillSpaceNum = (int) (ONE_LINE_PRINT_PX - textPX) / 8;
                } else if (textItem.getFont() == FontFamily.SMALL) {
                    fillSpaceNum = (int) (ONE_LINE_PRINT_PX - textPX) / 4;
                } else {
                    fillSpaceNum = (int) (ONE_LINE_PRINT_PX - textPX) / 6;
                }

                if(fillSpaceNum <= 0){
                    fillSpaceNum = 5;
                }
                textItem.setFillSpaceNum(fillSpaceNum);
            } else if (textItem.getAlign() == Gravity.FILL_HORIZONTAL) {
                if (textItem.getPxSize() > textPX) {
                    int fillSpaceNum = 0;
                    if (textItem.getFont() == FontFamily.BIG) {
                        fillSpaceNum = (int) (textItem.getPxSize() - textPX) / 8;
                    } else if (textItem.getFont() == FontFamily.SMALL) {
                        fillSpaceNum = (int) (textItem.getPxSize() - textPX) / 4;
                    } else {
                        fillSpaceNum = (int) (textItem.getPxSize() - textPX) / 6;
                    }
                    textItem.setFillSpaceNum(fillSpaceNum);
                }
            }
            text.append(textItem.getText());
        }
        return text.toString();
    }

    private static float measureTextPX(String text, int font) {
        Paint paint = new Paint();
        if (font == FontFamily.BIG) {
            paint.setTextSize(32);
        } else if (font == FontFamily.SMALL) {
            paint.setTextSize(16);
        } else {
            paint.setTextSize(24);
        }
        //Utils.debugLogPrint(TAG, "text:" + text);
        //Utils.debugLogPrint(TAG, "text px:" + paint.measureText(text));
        return paint.measureText(text);
    }

    public static String getPayMeythod(String POSEntryCode) { return utils.ReceiptText.getPayMeythod(POSEntryCode); }

    public static String getTxnType(String txnType) { return utils.StringUtils.getTxnType(txnType); }

    static String readCardNum(String track2) {
        Utils.debugLogPrint(TAG, "readCardNum: " + track2.indexOf("D") + "  " + track2);
        String account = track2.substring(0, track2.indexOf("D"));
        //CardNum=account;
        Utils.debugLogPrint(TAG, "readCardNo: " + hideCardDetails(account));
        //account = track2.substring(track2.indexOf("D")+1, track2.indexOf("D")+5);
        //ExpireDate=account;

        return account;
    }

    public static int checkBatteryStatus() {
        int batLevel = 0;
        BatteryManager bm = (BatteryManager) ServiceHolder.Companion.getContext().getSystemService(BATTERY_SERVICE);
        assert bm != null;
        batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return batLevel;
    }

    public static void playSound(String filename) {
        try {
            AssetFileDescriptor afd = ServiceHolder.Companion.getContext().getAssets().openFd("sound/" + filename);
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            player.prepare();
            player.start();
            //player.stop();
        } catch (IOException e) {
            printErrorLog(TAG, "playSound", e.getMessage());
            e.printStackTrace();
        }
    }

    public static String maskString(String value, int clearTextRemain) { return utils.StringUtils.maskString(value, clearTextRemain); }

    public static String getSchemeName(String schemeId) {
        String schemeName = "";
        switch (schemeId) {
            case ("11"):
            case ("12"):
            case ("91"):
                schemeName = "VISA";
                break;
            case ("20"):
            case ("21"):
            case ("22"):
            case ("92"):
                schemeName = "MASTER";
                break;
            case ("31"):
            case ("93"):
                schemeName = "UnionPay";
                break;
            case ("81"):
            case ("82"):
            case ("98"):
            case ("99"):
                //schemeName = "MCCS";
                schemeName = "MYDEBIT";
                break;
        }
        return schemeName;
    }

    public static String maskIp(String ip) { return utils.StringUtils.maskIp(ip); }
}
