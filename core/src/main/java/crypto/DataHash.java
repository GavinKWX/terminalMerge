package crypto;

import com.library.terminal.Utility;

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
}
