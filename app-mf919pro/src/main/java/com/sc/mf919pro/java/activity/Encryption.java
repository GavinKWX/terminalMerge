package com.sc.mf919pro.java.activity;

import utils.HexUtil;

import java.nio.charset.StandardCharsets;
import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Encryption
{
    public static String encrypt(String message, String keys) throws Exception
    {
        return encrypt(message, keys, "DESede");
    }

    public static String decrypt(String message, String keys) throws Exception
    {
        return decrypt(message, keys, "DESede");
    }

    public static String encrypt(String message, String keys, String Algorithm) throws Exception
    {
        return (encrypt(message, keys, Algorithm, "CBC"));
    }

    public static String decrypt(String message, String keys, String Algorithm) throws Exception
    {
        return (decrypt(message, keys, Algorithm, "CBC"));
    }

    public static String encrypt(String message, String keys, String Algorithm, String Mode) throws Exception
    {
        if (message.length() % 16 != 0) {
            int len = message.length() + (16 - (message.length() % 16));
            message = Utils.paddingWith(message, "0", len, true);
        }
        String instance = Algorithm.concat("/" + Mode + "/NoPadding");
        byte[] keyBytes = HexUtil.hexStringToByte(keys);
        SecretKey key = new SecretKeySpec(keyBytes, Algorithm);
        IvParameterSpec iv = new IvParameterSpec(new byte[8]);
        Cipher cipher = Cipher.getInstance(instance);
        if (Mode.equals("ECB")) {
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key, iv);
        }
        byte[] cipherText = cipher.doFinal(HexUtil.hexStringToByte(message));
        return HexUtil.bytesToHexString(cipherText);
    }

    public static String decrypt(String message, String keys, String Algorithm, String Mode) throws Exception
    {
        String instance = Algorithm.concat("/" + Mode + "/NoPadding");
        byte[] keyBytes = HexUtil.hexStringToByte(keys);
        final SecretKey key = new SecretKeySpec(keyBytes, Algorithm);
        final IvParameterSpec iv = new IvParameterSpec(new byte[8]);
        final Cipher decipher = Cipher.getInstance(instance);
        if (Mode.equals("ECB")) {
            decipher.init(Cipher.DECRYPT_MODE, key);
        } else {
            decipher.init(Cipher.DECRYPT_MODE, key, iv);
        }
        final byte[] plainText = decipher.doFinal(HexUtil.hexStringToByte(message));
        return (HexUtil.bytesToHexString(plainText));
    }

    public static String AESencrypt(final String strToEncrypt, final String secret) throws Exception
    {
        byte[] data = strToEncrypt.getBytes("UTF-8");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes("UTF-8"), "AES"));

        String base64output = Base64.encodeToString(cipher.doFinal(data), Base64.DEFAULT | Base64.NO_WRAP);

        return base64output;
    }

    public static String AESdecrypt(final String strToDecrypt, final String secret) throws Exception
    {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret.getBytes("UTF-8"), "AES"));

        byte[] base64output = Base64.decode(strToDecrypt.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT | Base64.NO_WRAP);

        byte[] decryptedVal = cipher.doFinal(base64output);

        return new String(decryptedVal);
    }
}
