package com.sc.mf919.java.activity;

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
import com.sc.mf919.R;
import utils.HexUtil;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import org.apache.commons.lang3.math.NumberUtils;
import org.threeten.bp.LocalDateTime;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

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
        // Routed through Timber -> FileLoggingTree (AsyncLogWriter, async file write on a
        // background thread) + DebugTree (logcat, debug builds only). No synchronous disk
        // I/O on the calling thread here.
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

    public static void printLogD(String log) {
        if (fileSizeInKb("DebugLog.txt") > 50000) {
            deleteFiles("DebugLog.txt");
        }
        try {
            writeToFile(log, "DebugLog.txt");
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    public static long fileSizeInKb(String fileName) {
        File file = new File(ServiceHolder.Companion.getContext().getFilesDir(), fileName);
        long fileSize = file.length();
        return fileSize / 1024;
    }

    protected static String getFileValue(String filename, String tags) {
        Map<String, String> value = ServiceHolder.Companion.getFile(filename);
        String output = null;
        if (value != null) {
            output = value.get(tags);
        } else {
            InputStream ins;
            try {
                ins = ServiceHolder.Companion.getContext().openFileInput(filename);
                BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
                String str;
                while (null != (str = buf.readLine())) {
                    if (str.startsWith(tags)) {
                        output = str.split("=")[1];
                    }
                }
            } catch (Exception e) {
                printErrorLog(TAG, "getFileValue", e.getMessage());
                e.printStackTrace();
            }
        }
        return (output);
    }

    protected static void updateFileValue(final String filename, String tags, String Value) {
        final Map<String, String> value = ServiceHolder.Companion.getFile(filename);
        if (value != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                value.replace(tags, Value);
            } else {
                value.put(tags, Value);
            }
            ServiceHolder.Companion.restoreFile(value, filename);
            final Map<String, String> tmpChange = value;
            new Thread() {
                @Override
                public void run() {
                    super.run();
                    String[] values = tmpChange.keySet().toArray(new String[0]);
                    for (int i = 0; i < tmpChange.size(); i++) {
                        values[i] = values[i] + "=" + tmpChange.get(values[i]);
                        //System.out.println(values[i]);
                    }
                    write2File(values, filename);
                }
            }.run();
        } else {
            String[] values = readFromFile(filename);
            boolean found = false;
            for (int j = 0; j < values.length; j++) {
                if (values[j].startsWith(tags)) {
                    found = true;
                    values[j] = values[j].split("=")[0] + "=" + Value;
                    break;
                }
            }
            if (!found) {
                String[] tempValue = new String[values.length];

            } else {
                write2File(values, filename);
            }
        }
    }

    public static boolean createFolder(String path) {
        /*String init = "";
        if (path.charAt(0) == '/') {
            init = "/";
            path = path.substring(1);
        }
        if (path.charAt(path.length() - 1) == '/') {
            path = path.substring(0, path.length() - 1);
        }
        //GeneralMethod.debugLogPrint(TAG, "createFolder: " + path);
        int[] loc = findCharWithLoc(path, '/');
        for (int aLoc : loc) {
            File F = new File(init + path.substring(0, aLoc));
            //GeneralMethod.debugLogPrint(TAG, "createFolder: " + init + path.substring(0, aLoc));
            if (!F.exists()) {
                if (F.mkdir()) {
                    Utils.debugLogPrint(TAG, "This folder is create " + init + path.substring(0, aLoc));
                }
            }
        }
        File F = new File(init + path);
        if (!F.exists()) {
            if (F.mkdir()) {
                Utils.debugLogPrint(TAG, "This folder is create " + init + path);
            }
        }
        return F.exists();*/
        return true;
    }

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
                        int x = new Random().nextInt(900) + 100;
                        paths = ServiceHolder.Companion.getExternalStoragePaths() + "/files/download(" + x + ")_app.apk";
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

    public static void removeInstallApk(String name) {
        String[] value = readFromFile("installApk.txt");
        String[] tmp = new String[value.length];
        int count = 0;
        if (value[0] != null) {
            for (String s : value) {
                if (!name.equals(s)) {
                    tmp[count] = s;
                    count++;
                }
            }

            String[] temp = new String[count];
            System.arraycopy(tmp, 0, temp, 0, count);
            write2File(temp, "installApk.txt");
        }
    }

    public static String getInstallApk() {
        String[] value = readFromFile("installApk.txt");
        return value[0];
    }

    public static String[] readFromFilePath(String path) {
        File file = new File(path);
        if (file.exists()) {
            String[] ret = new String[0];
            int count = 0;
            try {
                String[] temp = new String[50000];
                InputStream inputStream = new FileInputStream(file);
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                String receiveString;
                while ((receiveString = bufferedReader.readLine()) != null) {
                    temp[count] = receiveString;
                    count++;
                }
                bufferedReader.close();
                inputStream.close();
                ret = new String[count];
                System.arraycopy(temp, 0, ret, 0, count);
            } catch (FileNotFoundException e) {
                printErrorLog(TAG, "readFromFile", e.getMessage());
            } catch (IOException e) {
                printErrorLog(TAG, "readFromFile", e.getMessage());
            }
            return ret;
        }
        return new String[]{null};
    }

    public static void writeToFile(String data, String filename) {
        try {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(ServiceHolder.Companion.getContext().openFileOutput(filename, Context.MODE_APPEND));
            BufferedWriter bufferedWriter = new BufferedWriter(outputStreamWriter);
            bufferedWriter.write(data);
            bufferedWriter.newLine();
            bufferedWriter.close();
            outputStreamWriter.close();
            //Utils.debugLogPrint(TAG, "writeToFile: " + data + "-->" + filename);
        } catch (IOException e) {
            printErrorLog(TAG, "writeToFile", e.getMessage());
        }
    }

    public static String[] readFromFile(String filename) {
        if (checkFiles(filename)) {
            String[] ret = new String[0];
            int count = 0;
            try {
                String[] temp = new String[100000];
                InputStream inputStream = ServiceHolder.Companion.getContext().openFileInput(filename);
                if (inputStream != null) {
                    InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                    String receiveString;
                    while ((receiveString = bufferedReader.readLine()) != null) {
                        temp[count] = receiveString;
                        count++;
                    }
                    bufferedReader.close();
                    inputStream.close();
                }
                ret = new String[count];
                System.arraycopy(temp, 0, ret, 0, count);
            } catch (Exception e) {
                printErrorLog(TAG, "readFromFile", e.getMessage());
            }
            return ret;
        }
        return new String[]{null};
    }

    public static boolean checkFiles(String filename) {
        File file = new File(ServiceHolder.Companion.getContext().getFilesDir(), filename);
        return file.exists();
    }

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

    public static String[] String2ArrayString(String value) {
        int[] loc = findCharWithLoc(value, '\n');
        String[] array = new String[loc.length + 1];
        for (int j = 0; j < loc.length + 1; j++) {
            if (j == 0) {
                array[j] = value.substring(0, loc[j]);
            } else if (j == loc.length) {
                array[j] = value.substring(loc[j - 1] + 1);
            } else {
                array[j] = value.substring(loc[j - 1] + 1, loc[j]);
            }
        }
        return (array);
    }

    public static int[] findCharWithLoc(String data, char value) {
        int[] loc = new int[data.length()];
        int numofChar = 0;
        for (int j = 0; j < data.length(); j++) {
            if (data.charAt(j) == value) {
                loc[numofChar] = j;
                numofChar++;
            }
        }
        int[] temp = new int[numofChar];
        System.arraycopy(loc, 0, temp, 0, numofChar);
        return temp;
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

    protected static boolean writeFileInByte(String filename, String value) {
        String path = ServiceHolder.Companion.getContext().getApplicationInfo().dataDir + "/files/";
        File file = new File(path, filename);
        if (file.exists()) {
            Utils.debugLogPrint(TAG, "Delete Files : (" + file.getName() + ") " + file.delete());
        }
        FileOutputStream fos;
        boolean returnValue = true;
        try {
            fos = new FileOutputStream(file);
            //fos.write(HexUtil.hexStringToByte(value));
            fos.close();
        } catch (Exception e) {
            returnValue = false;
            e.printStackTrace();
        }
        return returnValue;
    }

    public static String readDataBaseInByte(String filename) {
        String DB_PATH = ServiceHolder.Companion.getContext().getApplicationInfo().dataDir + "/databases/";
        File file = new File(DB_PATH, filename);
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

    public static boolean writeDataBaseInByte(String value, String filename) {
        boolean returnValue = true;
        String DB_PATH = ServiceHolder.Companion.getContext().getApplicationInfo().dataDir + "/databases/";
        File file = new File(DB_PATH, filename);
        if (new File(DB_PATH, filename + "-journal").exists()) {
            new File(DB_PATH, filename + "-journal").delete();
        }
        if (file.exists()) {
            Utils.debugLogPrint(TAG, "Delete Files : (" + file.getName() + ") " + file.delete());
        }
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(file);
            //fos.write(HexUtil.hexStringToByte(value));
            fos.close();
        } catch (Exception e) {
            returnValue = false;
            e.printStackTrace();
        }
        return returnValue;
    }

    public static String readDataBaseInByteFromPath(String filename, String DB_PATH) {
        File file = new File(DB_PATH, filename);
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

    public static String hashCardNum(String input) {
        return (hashData(input, "MD5"));
    }

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

    public static String hashDataWithClearText(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] messageDigest = md.digest(Utility.ASCIItoByte(input));
            System.out.println(Utility.Bytes2HexString(messageDigest));
            return HexUtil.bytesToHexString(messageDigest);
        }catch (NoSuchAlgorithmException var4) {
            System.out.println("NoSuchAlgorithmException");
            throw new RuntimeException(var4);
        }
    }


    public static void write2File(String[] arr1, String filename) {
        Utils.deleteFiles(filename);
        //Utils.debugLogPrint(TAG, "write2File: " + Arrays.toString(arr1));
        for (String anArr1 : arr1) {
            Utils.writeToFile(anArr1, filename);
        }
        Utils.debugLogPrint(TAG, "Write Files : (" + filename + ")");
    }

    public static void deleteFiles(String filename) {
        if (filename.endsWith(".db")) {
            String DB_PATH = ServiceHolder.Companion.getContext().getApplicationInfo().dataDir + "/databases/";
            File file = new File(DB_PATH, filename);
            if (new File(DB_PATH, filename + "-journal").exists()) {
                new File(DB_PATH, filename + "-journal").delete();
            }
            if (file.exists()) {
                Utils.debugLogPrint(TAG, "Delete Files : (" + file.getName() + ") " + file.delete());
            }
        } else {
            File file = new File(ServiceHolder.Companion.getContext().getFilesDir(), filename);
            if (file.exists()) {
                Utils.debugLogPrint(TAG, "Delete Files : (" + file.getName() + ") " + file.delete());
            }
        }
    }

    /*public static boolean isPrintISOMsgEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Print ISO Message");
        return !temp.equals("0");
    }

    public static void setPrintISOMsgEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Print ISO Message", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Print ISO Message", "0");
        }
    }

    public static boolean isPrintReceiptEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Print Receipt");
        return !temp.equals("0");
    }

    public static void setPrintReceiptEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Print Receipt", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Print Receipt", "0");
        }
    }

    public static boolean saleVisibleEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Sale Visible");
        return !temp.equals("0");
    }

    public static void setSaleVisibleEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Sale Visible", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Sale Visible", "0");
        }
    }

    public static boolean settleVisibleEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Settlement Visible");
        return !temp.equals("0");
    }

    public static void setSettleVisibleEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Settlement Visible", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Settlement Visible", "0");
        }
    }

    public static boolean voidVisibleEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Void Visible");
        return !temp.equals("0");
    }

    public static void setVoidVisibleEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Void Visible", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Void Visible", "0");
        }
    }

    public static boolean refundVisibleEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Refund Visible");
        return !temp.equals("0");
    }

    public static void setRefundVisibleEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Refund Visible", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Refund Visible", "0");
        }
    }

    public static boolean preAuthVisibleEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Pre-Auth Visible");
        return !temp.equals("0");
    }

    public static void setPreAuthVisibleEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Pre-Auth Visible", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Pre-Auth Visible", "0");
        }
    }

    public static boolean manualEntryEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "ManualEntry");
        return !temp.equals("0");
    }

    public static void setManualEntryEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "ManualEntry", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "ManualEntry", "0");
        }
    }

    public static boolean megStripEntryEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "MagStripEntry");
        return !temp.equals("0");
    }

    public static void setMegStripEntryEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "MagStripEntry", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "MagStripEntry", "0");
        }
    }

    public static boolean contactLessEntryEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "ContactlessEntry");
        return !temp.equals("0");
    }

    public static void setContactLessEntryEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "ContactlessEntry", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "ContactlessEntry", "0");
        }
    }

    public static boolean settingVisibleEnable() {
        String temp = getFileValue(TransFieldConstant.files.configFile, "Setting Visible");
        return !temp.equals("0");
    }

    public static void setSettingEnability(boolean a) {
        if (a) {
            updateFileValue(TransFieldConstant.files.configFile, "Setting Visible", "1");
        } else {
            updateFileValue(TransFieldConstant.files.configFile, "Setting Visible", "0");
        }
    }*/

    public static void DelayMili(int duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration);
        } catch (Exception e) {
            printErrorLog(TAG, "DelayMili", e.getMessage());
            e.printStackTrace();
        }
    }

    public static byte bcd2hex(byte val) {
        return (byte) ((val & 0x0f) + (val >> 4) * 10);
    }

    public static byte hex2bcd(byte val) {
        return (byte) (((val / 10) << 4) + val % 10);
    }

    public static byte hex2bcd(int val) {
        return (byte) (((val / 10) << 4) + val % 10);
    }

    public static byte hex2bcd(long val) {
        return (byte) (((val / 10) << 4) + val % 10);
    }

    public static int set_ushort(long value, byte[] destbuf, int destbufoffset) {
        destbuf[destbufoffset++] = (byte) ((value & 0xFF00) >> 8);
        destbuf[destbufoffset++] = (byte) (value & 0x00FF);
        return destbufoffset;
    }

    //
    // C functions wrapping
    //
    public static int strcmp(String string1, String string2) {
        if (string1 == string2)
            return 0;
        else
            return -1;
    }

    public static int arrayCopy(byte[] source, int soureoffset, byte[] dest, int destoffset, int len) {
        System.arraycopy(source, soureoffset, dest, destoffset, len);
        return destoffset + len;
    }

    public static int memcpy(byte[] destBuf, int destBufOffset, byte[] sourceBuf, int sourceBufOffset, int len) {
        return arrayCopy(sourceBuf, sourceBufOffset, destBuf, destBufOffset, len);
    }

    public static int memcpy(byte[] destBuf, byte[] sourceBuf, int len) {
        return memcpy(destBuf, 0, sourceBuf, 0, len);
    }

    public static int memcmp(byte[] data1, int data1Offset, byte[] data2, int data2Offset, int dataLen) {
        for (int i = 0; i < dataLen; i++) {
            if (data1[data1Offset + i] > data2[data2Offset + i])
                return 1;
            else if (data1[data1Offset + i] < data2[data2Offset + i])
                return -1;
        }

        return 0;
    }

    public static int memcmp(byte[] data1, int data1Offset, String data2, int dataLen) {
        byte[] byteData2 = data2.getBytes(StandardCharsets.US_ASCII);
        return memcmp(data1, data1Offset, byteData2, 0, dataLen);
    }

    public static int memcmp(byte[] data1, String data2, int dataLen) {
        byte[] byteData2 = data2.getBytes(StandardCharsets.US_ASCII);
        return memcmp(data1, 0, byteData2, 0, dataLen);
    }

    public static int arrayFill(byte data, byte[] dest, int destoffset, int len) {
        while (len-- > 0) {
            dest[destoffset++] = data;
        }

        return destoffset;
    }

    public static int memset(byte[] dest, int destOffset, byte setValue, int length) {
        arrayFill(setValue, dest, destOffset, length);
        return length;
    }

    public static int memset(byte[] dest, byte setValue, int length) {
        return memset(dest, 0, setValue, length);
    }

    public static int set_short(short value, byte[] destbuf) {
        int iPointer = 0;
        destbuf[iPointer++] = (byte) (0x00FF & (value >> 8));
        destbuf[iPointer++] = (byte) (0x00FF & (value));

        return iPointer;
    }

    public static int set_ushort(short value, byte[] destbuf, int destbufoffset) {
        destbuf[destbufoffset++] = (byte) ((value & 0xFF00) >> 8);
        destbuf[destbufoffset++] = (byte) (value & 0x00FF);
        return destbufoffset;
    }

    public static short get_ushort(byte[] data, int dataoffset) {
        byte[] bUShort = new byte[2];
        short usResp = 0;

        bUShort[0] = data[dataoffset++];
        bUShort[1] += data[dataoffset];

        usResp = (short) HexUtil.bytes2short(bUShort);

        return usResp;
    }

    //return index value
    public static int sscanf(String dataIn, String lookFor, String[] foundValue) {
        int iIndex = dataIn.indexOf(lookFor);
        if (iIndex < 0)
            return iIndex;

        String tempResult = dataIn.substring(iIndex + lookFor.length());
        int seperatorIndex = tempResult.indexOf(";");
        if(seperatorIndex > 0)
            tempResult = tempResult.substring(0, seperatorIndex);

        //foundValue = dataIn.Substring(iIndex);
        foundValue[0] = tempResult;
        return iIndex + lookFor.length();
    }

    //return parse value
    public static int sscanf(byte[] dataIn, int dataInOffset, int dataInLength, String lookFor, String[] foundValue) {
        String strData = byteArrayToAsciiString(dataIn, dataInOffset, dataInLength);

        return sscanf(strData, lookFor, foundValue);
    }

    public static int strlen(String data) {
        if (data == null)
            return 0;

        return data.length();
    }

    public static int atoi(String value) {
        // Enhanced Integer.parseInt
        //return Integer.parseInt(value);
        if (value == null){value = "";}
        return NumberUtils.toInt(value.replaceAll("\\.", "").replaceAll(",", ""), 0);
    }

    public static long convertLong(String value) {
        // Enhanced Integer.parseInt
        //return Integer.parseInt(value);
        return NumberUtils.toLong(value, 0);
    }

    public static String removeWhiteSpace(String InputText) {
        InputText = InputText.trim();
        InputText = InputText.replace("\t", "");
        return InputText.replace(" ", "");
    }

    public static String byteArrayToHexString(byte[] Input, int Offset, int Length, String spacing) {
        String strO = "";
        for (int i = Offset; i < Offset + Length; i++)
            //strO += String.format("{0:x2}", (int) Input[i]) + spacing;
            strO += String.format("%02x", (int) Input[i]) + spacing;
        return strO.toUpperCase().trim();
    }

    public static String byteArrayToHexString(byte[] Input, int Offset, int Length) {
        return byteArrayToHexString(Input, Offset, Length/*Input.Length*/, "");
    }

    public static String byteArrayToHexString(byte[] Input) {
        return byteArrayToHexString(Input, 0, Input.length, "");
    }

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
    public static byte[] hexStringToByteArray(String hex) {
        if (hex == null || "".equals(hex)) {
            return null;
        }
        hex = hex.toUpperCase();
        int len = (hex.length() / 2);
        byte[] result = new byte[len];
        char[] achar = hex.toCharArray();
        for (int i = 0; i < len; i++) {
            int pos = i * 2;
            result[i] = (byte) (toByte(achar[pos]) << 4 | toByte(achar[pos + 1]));
        }
        return result;
    }

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

    public static byte toByte(char c) {
        byte b = (byte) "0123456789ABCDEF".indexOf(c);
        return b;
    }

    public byte hexStringToByte(String HexStringByte) {
        char[] achar = HexStringByte.toUpperCase().toCharArray();
        byte b = (byte) (toByte(achar[0]) << 4 | toByte(achar[1]));
        return b;
    }

    public static void bin2bcd(int dataIn, int dataInLength, byte[] outBuffer, int outBufferOffset) {
        int i = 0;
        byte[] buffer = new byte[100];
        for (i = 0; i < dataInLength; i++) {

            buffer[i] = (byte) (dataIn % 10);
            dataIn /= 10;
            buffer[i] |= (byte) (dataIn % 10 << 4);
            dataIn /= 10;

        }

        for (i = 0; i < dataInLength; i++) {
            outBuffer[outBufferOffset + i] = buffer[dataInLength - i - 1];
        }

    }

    public static int bcd2bin(byte[] inBuf, int inBufOffset, int inBufLen) {
        int iOutput = 0, i;
        byte[] buf = new byte[100];

            /*for (i = inBufOffset; i < inBufLen; i++)
            {
                iOutput *= 100;
                iOutput += (10 * (inBuf[i] >> 4));
                iOutput += inBuf[i] & 0xf;
            }*/

        for (i = 0; i < inBufLen; i++) {
            iOutput *= 100;

            if((i+1) == inBufLen){
                iOutput +=  Integer.parseInt(HexUtil.bytesToHexString(inBuf, i + inBufOffset, 1));
            }else {
                iOutput += (10 * (inBuf[i + inBufOffset] >> 4));
                iOutput += inBuf[i + inBufOffset] & 0xf;
            }
        }

        return iOutput;
    }

    public static int bcd2Int(byte[] bcdData, int bcdDataOffset, int bcdDataLen) {
        int iMultiplier = 1;
        int iTotalValue = 0;

        for (int i = bcdDataLen - 1; i >= 0; i--) {
            byte thisByte = bcdData[bcdDataOffset + i];
            int iLower = thisByte & 0x0F;
            int iUpper = (thisByte & 0xF0) >> 4;

            int iThisValue = (iUpper * 10) + iLower;
            iThisValue = iThisValue * iMultiplier;
            iTotalValue += iThisValue;

            iMultiplier = iMultiplier * 100;
        }

        return iTotalValue;
    }

    public static String byteArrayToAsciiString(byte[] DataIn, int Offset, int Length) {
        if(Length > 0) {
            byte[] data = new byte[Length];
            memcpy(data, 0, DataIn, Offset, Length);

            String ascii = new String(data);

            return ascii;
        } else {
            return "";
        }
    }

    public static String byteArrayToAsciiString(byte[] DataIn) {
        return byteArrayToAsciiString(DataIn, 0, DataIn.length);

    }

    public static byte[] AsciiToByteArray(String DataIn) {
        byte[] byteDataInConverted = DataIn.getBytes();
        //Utils.debugLogPrint(TAG, "AsciiToByteArray: " + (int) byteDataInConverted[0]);

        return byteDataInConverted;
    }

    public static byte[] ASCIItoByte(String info) {
        byte[] temp = new byte[info.length()];
        for (int j = 0; j < info.length(); j++) {
            temp[j] = (byte) info.charAt(j);
        }
        return (temp);
    }

    public static String ASCIItoHexString(String info) {
        byte[] temp = ASCIItoByte(info);
        //GeneralMethod.debugLogPrint(TAG, "ASCIItoHexString: " + HexUtil.bytesToHexString(temp));
        return (HexUtil.bytesToHexString(temp));
    }

    public static String symbolString(String Symbol, int Len) {
        StringBuilder returnVal = new StringBuilder();
        for (int j = 0; j < Len; j++) {
            returnVal.append(Symbol);
        }
        return returnVal.toString();
    }

    public static String bcdToASCString(byte[] bytes) {
        byte[] temp = new byte[bytes.length * 2];

        for(int i = 0; i < bytes.length; ++i) {
            byte val = (byte)((bytes[i] & 240) >> 4 & 15);
            temp[i * 2] = (byte)(val > 9 ? val + 65 - 10 : val + 48);
            val = (byte)(bytes[i] & 15);
            temp[i * 2 + 1] = (byte)(val > 9 ? val + 65 - 10 : val + 48);
        }

        return new String(temp);
    }

    public static String removeCarNumChar(String CardNu) {
        if (CardNu.contains("F")) {
            int index = CardNu.indexOf("F");
            CardNu = CardNu.substring(0, index);

        }
        return CardNu;
    }

    public static String hideCardDetails(String cdNum) {
        return hideCardDetails(cdNum, true);
    }

    public static String hideCardDetails(String CardNu, boolean b) {
        if (CardNu.length() > 11) {
            if (b) {
                CardNu = CardNu.substring(0, 6) + symbolString("*", CardNu.length() - 10) + CardNu.substring(CardNu.length() - 4);
            } else {
                CardNu = symbolString("*", CardNu.length() - 4) + CardNu.substring(CardNu.length() - 4);
            }
        }
        return CardNu;
    }

    public static String DateFormat(String date) {
        date = date.substring(6, 8) + "-" + date.substring(4, 6)
                + "-" + date.substring(0, 4);
        return (date);
    }

    public static String TimeFormat(String time) {
        time = time.substring(0, 2) + ":" + time.substring(2, 4)
                + ":" + time.substring(4, 6);
        return (time);
    }

    public static String DateTimeFormat(String datetime) {
        try {
            datetime = datetime.substring(0, 4) + "-" + datetime.substring(4, 6)
                    + "-" + datetime.substring(6, 8) + " " + datetime.substring(8, 10) + ":" + datetime.substring(10, 12)
                    + ":" + datetime.substring(12, 14);
            Utils.debugLogPrint(TAG, "DateTimeFormat: " + datetime);
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

    public static String mask_pan(String value) {
        value = value.substring(0, 6) + symbolString("0", value.length() - 10) + value.substring(value.length() - 4);
        value = paddingWith(value, "0", 20, true);
        return (value);
    }

    public static String spaceBtwNoChar(String value, int space) {
        String temp = "";
        while (value.length() > space) {
            temp = temp.concat(value.substring(0, 4).concat(" "));
            value = value.substring(4);
        }
        temp = temp.concat(value);
        return (temp);
    }

    public static String blankSpace(int i) {
        String temp = "";
        int loop = 1;
        while (loop < i) {
            temp = temp.concat(" ");
            loop++;
        }
        return temp;
    }

    public static String paddingWith(String pin, String f, int length, boolean end) {
        if (pin.length() < length) {
            StringBuilder pinBuilder = new StringBuilder(pin);
            while (pinBuilder.length() != length) {
                if (end) {
                    pinBuilder.append(f);
                } else {
                    pinBuilder.insert(0, f);
                }
            }
            pin = pinBuilder.toString();
        }
        return pin;
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

    public static void cpAssetFile(String filename) {
        if (!ChangeInFile(filename)) {
            if (checkFiles(filename)) {
                String[] exitValue = readFromFile(filename);
                try {
                    Utils.debugLogPrint(TAG, "Copy Asset File: " + filename);
                    InputStream ins;
                    ins = ServiceHolder.Companion.getContext().getAssets().open(filename);
                    BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
                    String str;
                    while ((str = buf.readLine()) != null) {
                        boolean update = true;
                        for (String s : exitValue) {
                            int loc = str.indexOf("=");
                            if (s.startsWith(str.substring(0, loc))) {
                                update = false;
                                break;
                            }
                        }
                        if (update) {
                            writeToFile(str, filename);
                        }
                    }
                } catch (Exception e) {
                    printErrorLog(TAG, "cpAssetFile", e.getMessage());
                    e.printStackTrace();
                }
            } else {
                try {
                    Utils.debugLogPrint(TAG, "Copy Asset File: " + filename);
                    InputStream ins;
                    ins = ServiceHolder.Companion.getContext().getAssets().open(filename);
                    BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
                    String str;
                    while ((str = buf.readLine()) != null) {
                        writeToFile(str, filename);
                    }
                } catch (Exception e) {
                    printErrorLog(TAG, "cpAssetFile", e.getMessage());
                    e.printStackTrace();
                }
            }
        }

    }

    private static boolean ChangeInFile(String filename) {
        boolean isSame;
        if (checkFiles(filename)) {
            String[] a1 = readFromFile(filename);
            String[] a2 = readFromAssetFile(filename);
            isSame = (a1.length == a2.length);
        } else {
            isSame = false;
        }
        Utils.debugLogPrint(TAG, "ChangeInFile: " + isSame);
        return (isSame);
    }

    public static String[] readFromAssetFile(String filename) {
        if (checkFiles(filename)) {
            String[] ret = new String[0];
            int count = 0;
            try {
                String[] temp = new String[1000];
                InputStream inputStream = ServiceHolder.Companion.getContext().getAssets().open(filename);
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                String receiveString;
                while ((receiveString = bufferedReader.readLine()) != null) {
                    temp[count] = receiveString;
                    count++;
                }
                bufferedReader.close();
                inputStream.close();
                ret = new String[count];
                System.arraycopy(temp, 0, ret, 0, count);
            } catch (Exception e) {
                printErrorLog(TAG, "readFromAssetFile", e.getMessage());
            }
            return ret;
        }
        return new String[]{null};
    }


    public static String getPinBlock(String pin, String pan, int Mode) {
        pin = Mode + Integer.toString(pin.length()) + pin;
        String returnVal = "";
        String substring = pan.substring(pan.length() - 13, pan.length() - 1);
        switch (Mode) {
            case 0:
                pan = substring;
                pin = paddingWith(pin, "F", 16, true);
                pan = paddingWith(pan, "0", 16, false);
                byte[] comb1 = HexUtil.hexStringToByte(pin);
                byte[] comb2 = HexUtil.hexStringToByte(pan);
                int result;
                for (int j = 0; j < comb1.length; j++) {
                    result = Byte2Int(comb1[j]) ^ Byte2Int(comb2[j]);
                    returnVal = returnVal.concat(zeroPadding(Integer.toHexString(result), 2));
                }
                break;
            case 1:
                while (pin.length() == 16) {
                    Random rand = new Random();
                    int n = rand.nextInt(16);
                    pin = pin.concat(Integer.toHexString(n - 1));
                }
                returnVal = pin;
                break;
            case 2:
                pin = paddingWith(pin, "F", 16, true);
                returnVal = pin;
                break;
            case 3:
                while (pin.length() == 16) {
                    Random rand = new Random();
                    int n = rand.nextInt(16);
                    pin = pin.concat(Integer.toHexString(n - 1));
                }
                pan = substring;
                pan = paddingWith(pan, "0", 16, false);
                byte[] compB11 = HexUtil.hexStringToByte(pin);
                byte[] compB12 = HexUtil.hexStringToByte(pan);
                int result1;
                for (int j = 0; j < compB11.length; j++) {
                    result1 = Byte2Int(compB11[j]) ^ Byte2Int(compB12[j]);
                    returnVal = returnVal.concat(zeroPadding(Integer.toHexString(result1), 2));
                }
        }
        return returnVal;
    }

    public static int Byte2Int(byte b) {
        int a = b;
        if (a < 0) {
            a = 256 + a;
        }
        return (a);
    }

    public static String zeroPadding(String s, int LengthOfString) {
        if (s.length() < LengthOfString) {
            while (s.length() != LengthOfString) {
                s = "0".concat(s);
            }
        }
        return s;
    }

    public static String Byte2ASCII(byte[] b) {
        StringBuilder temp = new StringBuilder();
        for (byte aB : b) {
            char c = (char) aB;
            temp.append(c);
        }
        return (temp.toString());
    }

    public static String getICCUMobile(String field55) {
        String[] tags = new String[]{"72", "82", "84", "91", "95", "9A", "9C", "5F2A", "5F30", "5F34", "9F02", "9F03", "9F06", "9F09", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F28", "9F29", "9F33", "9F34", "9F35", "9F36", "9F37", "9F41", "9F53", "DF31"};
        String temp = "";
        TLVData data1 = new TLVData(field55);
        for (String tag : tags) {
            String value = data1.getTagMsg(tag);
            if (!value.isEmpty()) {
                temp = temp.concat(value);
            }
        }
        return temp;
    }

    public static String getActualAmount(String value) {
        if(value.equals("0")) return value;
        if (value.length() < 3) {
            value = Utils.zeroPadding(value, 3);
        }
        long valueInInt = Long.parseLong(value) / 100;
        String mAmount = Long.toString(valueInInt);
        return (mAmount.concat("." + value.substring(value.length() - 2)));
    }

    /**
     * Best-effort local IP, for log headers only.
     *
     * Every step here is nullable in the real world: ServiceHolder's context before the app is
     * initialised, the WifiManager on a ROM that withholds it, and getConnectionInfo() which
     * returns null when Wi-Fi is off and is redacted from Android 10 onward. This used to throw,
     * and because it is called first thing when building a HelperLog -- including inside
     * CrashHandler -- an NPE here killed the crash handler before it could kill the process,
     * leaving the terminal frozen on whatever screen it was showing. A log header is never worth
     * a throw.
     */
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

    public static String getPublicIP() {
        final boolean[] waitTime = {true};
        final String[] ip = {"0.0.0.0"};
        final URL[] whatismyip = {null};
        new Thread() {
            @Override
            public void run() {
                super.run();
                try {
                    //whatismyip = new URL("http://checkip.amazonaws.com");
                    whatismyip[0] = new URL("https://myexternalip.com/raw");
                    BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip[0].openStream()));
                    ip[0] = in.readLine(); //you get the IP as a String
                    //System.out.println(ip[0]);
                    waitTime[0] = false;
                } catch (Exception e) {
                    //e.printStackTrace();\
                    waitTime[0] = false;
                }
            }
        }.start();

        while (waitTime[0]) {
            Utils.DelayMili(100);
        }
        return ip[0];
    }

    public static String CVMAnalysis(String CVM, String entryMode) {
        String returnValue;
        switch (CVM.charAt(1)) {
            case '1':
                //if (entryMode.equals(TransFieldConstant.payMethods.sRF)) {
                //    returnValue = "NO PIN REQUIRED\nNO SIGNATURE REQUIRED";
                //} else {
                returnValue = "PIN VERIFIED\nNO SIGNATURE REQUIRED";
                //}
                break;
            case '2':
                returnValue = "PIN VERIFIED\nNO SIGNATURE REQUIRED";
                break;
            case '3':
            case '5':
                returnValue = "PIN VERIFIED\n\n\n______________________________\nSign";
                break;
            case 'E':
                returnValue = "\n\n\n______________________________\nSign";
                break;
            default:
                returnValue = "NO PIN REQUIRED\nNO SIGNATURE REQUIRED";
                break;
        }
        return returnValue;
    }

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

    public static String getPayMeythod(String POSEntryCode) {
        Utils.debugLogPrint("TAG", "getPayMeythod: " + POSEntryCode);
        String returnVale = "Manual";
        switch (POSEntryCode) {
            case ("0260"):
            case ("0261"):
            case ("0059"):
            case ("0051"):
                returnVale = "Contact";
                break;
            case ("0270"):
            case ("0271"):
            case ("0081"):
            case ("0071"):
                returnVale = "Contactless";
                break;
            case ("0021"):
            case ("0801"):
                returnVale = "MagStripe";
                break;
        }
        return returnVale;
    }

    public static String getTxnType(String txnType) {
        Utils.debugLogPrint("TAG", "getTxnType: " + txnType);
        String returnVale = txnType;
        if(txnType.equalsIgnoreCase("MOTO")){
            returnVale = "Sale";
        }
        return returnVale;
    }

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

    public static String maskString(String value, int clearTextRemain) {
        String resultString = value;
        int stringLength = value.length();
        if(stringLength > clearTextRemain){
            resultString= symbolString("*", stringLength - clearTextRemain) + value.substring(stringLength - clearTextRemain);
        }
        return resultString;
    }

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
                schemeName = "MCCS";
                break;
        }
        return schemeName;
    }

    public static String maskIp(String ip) {
        String result = "-";
        if (!ip.trim().isEmpty()) {
            String[] parts = ip.split("\\.");
            int finalDest = 0;
            if(parts.length > 0) {
                finalDest = parts.length -1;
            }
            result = "xxx.xxx.xxx." + parts[finalDest];
        }

        return result;
    }
}