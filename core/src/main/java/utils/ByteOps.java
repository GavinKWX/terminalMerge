package utils;

import java.nio.charset.StandardCharsets;

/**
 * Byte, buffer and hex primitives shared by the TLV/EMV code in :core.
 *
 * Lifted verbatim from each app's Utils, where they were byte-identical. Only what emv.Tlv and
 * emv.EmvTag actually need is here -- the rest of Utils is still per-app and still drifted (~291
 * lines apart), so this is a deliberate slice, not the start of a copy.
 *
 * Each app's Utils now delegates these to this class, so the logic exists once.
 */
public class ByteOps {

    public static int memcpy(byte[] destBuf, int destBufOffset, byte[] sourceBuf, int sourceBufOffset, int len) {
        return arrayCopy(sourceBuf, sourceBufOffset, destBuf, destBufOffset, len);
    }
    public static int memcpy(byte[] destBuf, byte[] sourceBuf, int len) {
        return memcpy(destBuf, 0, sourceBuf, 0, len);
    }
    public static int memset(byte[] dest, int destOffset, byte setValue, int length) {
        arrayFill(setValue, dest, destOffset, length);
        return length;
    }
    public static int memset(byte[] dest, byte setValue, int length) {
        return memset(dest, 0, setValue, length);
    }
    public static int strlen(String data) {
        if (data == null)
            return 0;

        return data.length();
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
    public static short get_ushort(byte[] data, int dataoffset) {
        byte[] bUShort = new byte[2];
        short usResp = 0;

        bUShort[0] = data[dataoffset++];
        bUShort[1] += data[dataoffset];

        usResp = (short) HexUtil.bytes2short(bUShort);

        return usResp;
    }
    public static int set_ushort(long value, byte[] destbuf, int destbufoffset) {
        destbuf[destbufoffset++] = (byte) ((value & 0xFF00) >> 8);
        destbuf[destbufoffset++] = (byte) (value & 0x00FF);
        return destbufoffset;
    }
    public static int set_ushort(short value, byte[] destbuf, int destbufoffset) {
        destbuf[destbufoffset++] = (byte) ((value & 0xFF00) >> 8);
        destbuf[destbufoffset++] = (byte) (value & 0x00FF);
        return destbufoffset;
    }
    public static int set_short(short value, byte[] destbuf) {
        int iPointer = 0;
        destbuf[iPointer++] = (byte) (0x00FF & (value >> 8));
        destbuf[iPointer++] = (byte) (0x00FF & (value));

        return iPointer;
    }

    public static int arrayCopy(byte[] source, int soureoffset, byte[] dest, int destoffset, int len) {
        System.arraycopy(source, soureoffset, dest, destoffset, len);
        return destoffset + len;
    }
    public static int arrayFill(byte data, byte[] dest, int destoffset, int len) {
        while (len-- > 0) {
            dest[destoffset++] = data;
        }

        return destoffset;
    }
    public static byte toByte(char c) {
        byte b = (byte) "0123456789ABCDEF".indexOf(c);
        return b;
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
    public static int Byte2Int(byte b) {
        int a = b;
        if (a < 0) {
            a = 256 + a;
        }
        return (a);
    }

    public static String ASCIItoHexString(String info) {
        byte[] temp = ASCIItoByte(info);
        //GeneralMethod.debugLogPrint(TAG, "ASCIItoHexString: " + HexUtil.bytesToHexString(temp));
        return (HexUtil.bytesToHexString(temp));
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
    public static byte hex2bcd(byte val) {
        return (byte) (((val / 10) << 4) + val % 10);
    }
    public static byte hex2bcd(int val) {
        return (byte) (((val / 10) << 4) + val % 10);
    }
    public static byte hex2bcd(long val) {
        return (byte) (((val / 10) << 4) + val % 10);
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

    public static byte[] ASCIItoByte(String info) {
        byte[] temp = new byte[info.length()];
        for (int j = 0; j < info.length(); j++) {
            temp[j] = (byte) info.charAt(j);
        }
        return (temp);
    }

    /**
     * The apps' Utils.atoi, moved here with IsoComm.
     *
     * Deliberately not Integer.parseInt: the original strips thousands separators and decimal
     * points first (an amount string like "1,234.56" becomes 123456) and answers 0 for anything
     * unparseable rather than throwing. Callers on the host path rely on the 0, so keep both.
     * Implemented without Apache NumberUtils, which :core does not depend on.
     */
    public static int atoi(String value) {
        if (value == null) {
            value = "";
        }
        try {
            return Integer.parseInt(value.replaceAll("[.,]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The apps' Utils.convertLong, moved here with EmvUtil.
     *
     * Like {@link #atoi(String)} it answers 0 rather than throwing, which callers on the amount
     * path rely on. Unlike atoi it does NOT strip separators -- the original passed the string
     * straight to Apache NumberUtils.toLong, and callers strip their own before calling.
     */
    public static long convertLong(String value) {
        if (value == null) {
            value = "";
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Utils slice, tranche 1 (2026-09-10): byte / BCD / C-string primitives.
    //
    // Moved verbatim from both apps' Utils, which carried identical copies. The C-library
    // shapes (memcmp/sscanf/strcmp returning ints, out-parameters) are kept exactly as they
    // were -- every ISO message depends on them, so this was a move, not a rewrite.
    // ---------------------------------------------------------------------------------------

    public static byte[] AsciiToByteArray(String DataIn) {
        byte[] byteDataInConverted = DataIn.getBytes();
        //debugLogPrint(TAG, "AsciiToByteArray: " + (int) byteDataInConverted[0]);

        return byteDataInConverted;
    }

    public static String Byte2ASCII(byte[] b) {
        StringBuilder temp = new StringBuilder();
        for (byte aB : b) {
            char c = (char) aB;
            temp.append(c);
        }
        return (temp.toString());
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

    public static byte bcd2hex(byte val) {
        return (byte) ((val & 0x0f) + (val >> 4) * 10);
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

    public static int sscanf(byte[] dataIn, int dataInOffset, int dataInLength, String lookFor, String[] foundValue) {
        String strData = byteArrayToAsciiString(dataIn, dataInOffset, dataInLength);

        return sscanf(strData, lookFor, foundValue);
    }

    public static int strcmp(String string1, String string2) {
        if (string1 == string2)
            return 0;
        else
            return -1;
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


    /** Local stand-in for the apps' Utils.debugLogPrint, which is only a Timber call. */
    private static void debugLogPrint(String tag, String msg) {
        timber.log.Timber.tag(tag).d(msg);
    }
}
