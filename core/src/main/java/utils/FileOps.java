package utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;

import timber.log.Timber;

/**
 * The terminal's config-file helpers, moved out of both apps' Utils in tranche 3 of the Utils
 * slice. Everything the app used to reach through its own ServiceHolder now comes from
 * {@link CurrentFiles}.
 *
 * Moved verbatim, including two shapes worth knowing before reusing any of this:
 * <ul>
 *   <li>a read that finds nothing returns a one-element array holding null, not an empty array.
 *       Callers index [0] and compare against null.</li>
 *   <li>{@link #readFromAssetFile} gates on {@link #checkFiles}, which looks in the internal
 *       files dir, not in assets. Its only caller checks the same thing first, so it works, but
 *       it is not the "read an asset" helper its name suggests.</li>
 * </ul>
 */
public class FileOps {

    private static final String TAG = "FileOps";

    /**
     * Byte-for-byte copy of {@code file} to {@code tempName} inside {@code tempDir}.
     *
     * Used to stage a log file before upload so the live file can keep being written to. Both
     * channels are closed in a finally block; the input is also closed on the success path,
     * which is a harmless double close and is kept as it was.
     */
    public static File createTemporaryFile(File file, File tempDir, String tempName)
            throws IOException {
        File newFile = new File(tempDir, tempName);
        FileChannel outputChannel = null;
        FileChannel inputChannel = null;
        try {
            outputChannel = new FileOutputStream(newFile).getChannel();
            inputChannel = new FileInputStream(file).getChannel();
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
            inputChannel.close();
        } finally {
            if (inputChannel != null) {
                inputChannel.close();
            }
            if (outputChannel != null) {
                outputChannel.close();
            }
        }
        return newFile;
    }

    /** Does this filename exist in the app's internal files dir? */
    public static boolean checkFiles(String filename) {
        File file = new File(CurrentFiles.INSTANCE.filesDir(), filename);
        return file.exists();
    }

    /** Size in whole KB, so anything under 1 KB reads as 0. */
    public static long fileSizeInKb(String fileName) {
        File file = new File(CurrentFiles.INSTANCE.filesDir(), fileName);
        long fileSize = file.length();
        return fileSize / 1024;
    }

    /**
     * Reads every line of an internal file. Returns a single null element when the file is
     * absent -- see the class note.
     */
    public static String[] readFromFile(String filename) {
        if (checkFiles(filename)) {
            String[] ret = new String[0];
            int count = 0;
            try {
                String[] temp = new String[100000];
                InputStream inputStream = CurrentFiles.INSTANCE.openInput(filename);
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
                printErrorLog("readFromFile", e.getMessage());
            }
            return ret;
        }
        return new String[]{null};
    }

    /** Same as {@link #readFromFile} but for an absolute path outside the files dir. */
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
                printErrorLog("readFromFile", e.getMessage());
            } catch (IOException e) {
                printErrorLog("readFromFile", e.getMessage());
            }
            return ret;
        }
        return new String[]{null};
    }

    /** Appends one line. The newline is added here, so callers pass the bare value. */
    public static void writeToFile(String data, String filename) {
        try {
            OutputStreamWriter outputStreamWriter =
                    new OutputStreamWriter(CurrentFiles.INSTANCE.openOutputAppend(filename));
            BufferedWriter bufferedWriter = new BufferedWriter(outputStreamWriter);
            bufferedWriter.write(data);
            bufferedWriter.newLine();
            bufferedWriter.close();
            outputStreamWriter.close();
        } catch (IOException e) {
            printErrorLog("writeToFile", e.getMessage());
        }
    }

    /** Replaces a file with these lines: delete, then append each one. */
    public static void write2File(String[] arr1, String filename) {
        deleteFiles(filename);
        for (String anArr1 : arr1) {
            writeToFile(anArr1, filename);
        }
        Timber.tag(TAG).d("Write Files : (%s)", filename);
    }

    /** Deletes an internal file, and a .db name's journal alongside it. */
    public static void deleteFiles(String filename) {
        if (filename.endsWith(".db")) {
            String dbPath = CurrentFiles.INSTANCE.databasesDir();
            File file = new File(dbPath, filename);
            if (new File(dbPath, filename + "-journal").exists()) {
                new File(dbPath, filename + "-journal").delete();
            }
            if (file.exists()) {
                Timber.tag(TAG).d("Delete Files : (%s) %s", file.getName(), file.delete());
            }
        } else {
            File file = new File(CurrentFiles.INSTANCE.filesDir(), filename);
            if (file.exists()) {
                Timber.tag(TAG).d("Delete Files : (%s) %s", file.getName(), file.delete());
            }
        }
    }

    /**
     * First filename queued in installApk.txt, or null when the queue is empty.
     *
     * Relies on the single-null shape from {@link #readFromFile}: an absent file yields one null
     * element, so callers null-check the result rather than checking the length.
     */
    public static String getInstallApk() {
        String[] value = readFromFile("installApk.txt");
        return value[0];
    }

    /** Drops one filename from the installApk.txt queue and rewrites the file. */
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

    /** Reads a bundled asset line by line -- but only if the same name exists internally. */
    public static String[] readFromAssetFile(String filename) {
        if (checkFiles(filename)) {
            String[] ret = new String[0];
            int count = 0;
            try {
                String[] temp = new String[1000];
                InputStream inputStream = CurrentFiles.INSTANCE.openAsset(filename);
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
                printErrorLog("readFromAssetFile", e.getMessage());
            }
            return ret;
        }
        return new String[]{null};
    }

    /**
     * Seeds an internal config file from the bundled asset of the same name.
     *
     * When the file already exists it appends only the keys it does not already have, matching on
     * the text before the first equals sign. That is how a terminal keeps its provisioned values
     * across an app update while still picking up newly added keys.
     */
    public static void cpAssetFile(String filename) {
        if (!changeInFile(filename)) {
            if (checkFiles(filename)) {
                String[] exitValue = readFromFile(filename);
                try {
                    Timber.tag(TAG).d("Copy Asset File: %s", filename);
                    InputStream ins = CurrentFiles.INSTANCE.openAsset(filename);
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
                    printErrorLog("cpAssetFile", e.getMessage());
                    e.printStackTrace();
                }
            } else {
                try {
                    Timber.tag(TAG).d("Copy Asset File: %s", filename);
                    InputStream ins = CurrentFiles.INSTANCE.openAsset(filename);
                    BufferedReader buf = new BufferedReader(new InputStreamReader(ins));
                    String str;
                    while ((str = buf.readLine()) != null) {
                        writeToFile(str, filename);
                    }
                } catch (Exception e) {
                    printErrorLog("cpAssetFile", e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * "Is the internal file already in step with the asset?" -- decided purely on line count,
     * which is why a changed value in an asset never re-seeds. Kept as it was.
     */
    private static boolean changeInFile(String filename) {
        boolean isSame;
        if (checkFiles(filename)) {
            String[] a1 = readFromFile(filename);
            String[] a2 = readFromAssetFile(filename);
            isSame = (a1.length == a2.length);
        } else {
            isSame = false;
        }
        Timber.tag(TAG).d("ChangeInFile: %s", isSame);
        return (isSame);
    }

    private static void printErrorLog(String functionName, String logs) {
        Timber.tag(TAG).d("%s--%s ---> Error:%s", TAG, functionName, logs);
    }
}
