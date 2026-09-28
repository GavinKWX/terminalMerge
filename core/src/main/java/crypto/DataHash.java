package crypto;

import com.library.terminal.Utility;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import utils.HexUtil;

/**
 * SHA-1 of an ASCII input, used for the CARD_HASHED field.
 *
 * Its own file rather than utils.ByteOps: that class is byte/buffer/hex primitives, and a digest
 * is neither. Lifted verbatim from each app's Utils, where the two copies were identical --
 * including the System.out.println of the digest, which is left as-is so the move changes nothing.
 * (It prints a hash, not a key, but it is a card identifier on logcat and worth revisiting.)
 */
public class DataHash {

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

    /** Digest of hex-encoded input, as hex. Replaces the apps' hashData stub that returned "123" (item 93). */
    public static String hashHex(String inputHex, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HexUtil.bytesToHexString(md.digest(HexUtil.hexStringToByte(inputHex)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Digest of a file, streamed so a whole APK is never held in memory. */
    public static String hashFile(String path, String algorithm) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(path)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexUtil.bytesToHexString(md.digest());
    }
}
