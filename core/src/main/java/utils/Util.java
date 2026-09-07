package utils;

import com.centerm.iso8583.IsoMessage;
import com.library.terminal.Utility;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Util {
    private static String TAG = "Util";
    private static Map<String, String> config;
    static final int[] IsoMsg = new int[]{0, 0, 6, 12, 12, 12, 10, 8, 8, 8, 6, 6, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 2, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 24, 12, 4, 6, 16, 30, 80, 0, 0, 0, 0, 0, 0, 0, 0, 16, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16};

    public Util() {
    }

    public static String getBitMap(int[] array, int len) {
        byte[] temp = new byte[8];
        int Condition = 1;

        for(int j = 0; j < 8; ++j) {
            int value = 0;

            for(int i = 0; i < len; ++i) {
                if (array[i] < Condition + 8 && array[i] >= Condition) {
                    int arrvalue = 7 - (array[i] - Condition);
                    value += Utility.power(2, arrvalue);
                }
            }

            temp[j] = (byte)value;
            Condition += 8;
        }

        return Utility.Bytes2HexString(temp);
    }

    public static int[] DecomposeBitMap(String bitmap) {
        int[] decomposedValue = new int[64];
        int lenOfArr = 0;
        int Condition = 1;
        byte[] b = Utility.HexString2Bytes(bitmap);

        for(int j = 0; j < b.length; ++j) {
            String temp2 = Utility.Byte2String(b[j]);
            debugLogPrint(TAG, j + ":" + temp2);

            for(int i = 0; i < temp2.length(); ++i) {
                if (temp2.charAt(i) == '1') {
                    decomposedValue[lenOfArr] = 8 - temp2.length() + i + Condition;
                    ++lenOfArr;
                }
            }

            Condition += 8;
        }

        int[] temp = new int[lenOfArr];
        System.arraycopy(decomposedValue, 0, temp, 0, lenOfArr);
        return temp;
    }

    public static String track2padding(String data) {
        if (data.length() % 2 != 0) {
            data = data + "0";
        }

        return data;
    }

    private static void debugLogPrint(String tAG2, String string) {
        System.out.println(tAG2 + "::" + string);
    }

    private static void printLog(String string) {
        System.out.println(string);
    }

    public static String hideCardDetails(String cdNum) {
        return hideCardDetails(cdNum, true);
    }

    public static String hideCardDetails(String CardNu, boolean b) {
        if (CardNu.length() > 11) {
            if (b) {
                CardNu = CardNu.substring(0, 6) + Utility.symbolString("*", CardNu.length() - 10) + CardNu.substring(CardNu.length() - 4);
            } else {
                CardNu = Utility.symbolString("*", CardNu.length() - 4) + CardNu.substring(CardNu.length() - 4);
            }
        }

        return CardNu;
    }

    public static boolean valueExist(int[] array, int loc) {
        boolean isValueExist = false;
        int[] var6 = array;
        int var5 = array.length;

        for(int var4 = 0; var4 < var5; ++var4) {
            int anArray = var6[var4];
            if (anArray == loc) {
                isValueExist = true;
                break;
            }
        }

        return isValueExist;
    }

    public static String[] readConfig() {
        config = new HashMap();
        String[] file = Utility.read_file((new File("")).getAbsolutePath() + "\\config.txt");
        String[] var4 = file;
        int var3 = file.length;

        for(int var2 = 0; var2 < var3; ++var2) {
            String line = var4[var2];
            if (line.contains("=")) {
                config.put(line.split("=")[0], line.split("=")[1]);
            }
        }

        return file;
    }

    public static void writeConfig(String[] tmp) {
        Utility.write_file(tmp, (new File("")).getAbsolutePath() + "\\config.txt");
    }

    public static void Delay(int duration) {
        try {
            TimeUnit.SECONDS.sleep((long)duration);
        } catch (Exception var2) {
            var2.printStackTrace();
        }

    }

    public static void DelayMili(int duration) {
        try {
            TimeUnit.MILLISECONDS.sleep((long)duration);
        } catch (Exception var2) {
            var2.printStackTrace();
        }

    }

    public static void writeKeyValue(String[] value) {
        Utility.write_file(value, (new File("")).getAbsolutePath() + "\\key.txt", true);
    }

    public static String getFileValue(String tag2) {
        return (String)config.get(tag2);
    }

    public static File getCertificate() {
        return new File((new File("")).getAbsolutePath() + "\\ssl.crt");
    }

    public static class DecomposedIsoMessage {
        private String Len = null;
        private String TPDU = null;
        private String MTI = null;
        private String BitMAP = null;
        private String isoMsg;
        private String allISOMsg = "";
        private String[] field;
        private int[] bitmap;
        private int field35len;
        private Map<Integer, byte[]> fieldMap = new HashMap();
        private int field2len;

        public DecomposedIsoMessage() {
        }

        public int[] setISOMsg(String msg) {
            this.isoMsg = msg;
            if (msg == null) {
                return null;
            } else if (msg.isEmpty()) {
                return null;
            } else {
                this.Len = this.isoMsg.substring(0, 4);
                Util.debugLogPrint(Util.TAG, "Len: " + this.Len);
                Util.printLog("Len: " + this.Len);
                this.TPDU = this.isoMsg.substring(4, 14);
                Util.debugLogPrint(Util.TAG, "TPDU: " + this.TPDU);
                Util.printLog("TPDU: " + this.TPDU);
                this.MTI = this.isoMsg.substring(14, 18);
                Util.debugLogPrint(Util.TAG, "MTI: " + this.MTI);
                Util.printLog("MTI: " + this.MTI);
                this.BitMAP = this.isoMsg.substring(18, 34);
                Util.debugLogPrint(Util.TAG, "BitMAP: " + this.BitMAP);
                Util.printLog("BitMAP: " + this.BitMAP);
                this.bitmap = Util.DecomposeBitMap(this.BitMAP);
                this.decomposedMsg(this.bitmap);
                return this.bitmap;
            }
        }

        public String getTPDU() {
            return this.TPDU;
        }

        public String getMTI() {
            return this.MTI;
        }

        public String getBITMAP() {
            return this.BitMAP;
        }

        public String getValue(int index) {
            if (this.validateIndex(index)) {
                if (index != 37 && index != 38 && index != 39 && index != 41 && index != 42 && index != 45 && index != 48 && index != 54 && index != 57 && index != 60 && index != 62 && index != 63) {
                    if (index == 35) {
                        return Utility.Bytes2HexString((byte[])this.fieldMap.get(index)).substring(0, this.field35len);
                    } else if (index == 24) {
                        return Utility.Bytes2HexString((byte[])this.fieldMap.get(index)).substring(1);
                    } else {
                        return index == 2 ? Utility.Bytes2HexString((byte[])this.fieldMap.get(index)).substring(0, this.field2len) : Utility.Bytes2HexString((byte[])this.fieldMap.get(index));
                    }
                } else {
                    return Utility.Byte2ASCII((byte[])this.fieldMap.get(index));
                }
            } else {
                return "";
            }
        }

        private boolean validateIndex(int index) {
            boolean val = false;
            int[] var6;
            int var5 = (var6 = this.bitmap).length;

            for(int var4 = 0; var4 < var5; ++var4) {
                int aBitmap = var6[var4];
                if (aBitmap == index) {
                    val = true;
                    break;
                }
            }

            return val;
        }

        private void decomposedMsg(int[] Arr) {
            this.field = new String[Arr.length];
            String temp = this.isoMsg.substring(34);
            Util.debugLogPrint(Util.TAG, "decomposedMsg: " + Arr.length);

            for(int j = 0; j < Arr.length; ++j) {
                int len = Util.IsoMsg[Arr[j] - 1];
                byte index;
                if (len != 0) {
                    index = 0;
                    if (len % 2 != 0) {
                        ++len;
                    }
                } else if (Arr[j] != 35 && Arr[j] != 2) {
                    if (Arr[j] != 45 && Arr[j] != 54) {
                        index = 4;
                        len = Integer.parseInt(temp.substring(0, 4)) * 2;
                        if (len % 2 != 0) {
                            ++len;
                        }
                    } else {
                        index = 2;
                        len = Integer.parseInt(temp.substring(0, 2)) * 2;
                        if (len % 2 != 0) {
                            ++len;
                        }
                    }
                } else {
                    index = 2;
                    if (Arr[j] == 2) {
                        this.field2len = len = Integer.parseInt(temp.substring(0, 2));
                    } else {
                        this.field35len = len = Integer.parseInt(temp.substring(0, 2));
                    }

                    if (len % 2 != 0) {
                        ++len;
                    }
                }

                String temp2 = temp.substring(index, len + index);
                this.fieldMap.put(Arr[j], Utility.HexString2Bytes(temp2));
                if (Arr[j] != 2 && Arr[j] != 35 && Arr[j] != 45) {
                    Util.debugLogPrint(Util.TAG, "DE_1 " + Arr[j] + ":" + temp2);
                    Util.printLog("DE_1 " + Arr[j] + ":" + temp2);
                } else {
                    Util.debugLogPrint(Util.TAG, "DE_1 " + Arr[j] + ":" + Util.hideCardDetails(temp2));
                    Util.printLog("DE_1 " + Arr[j] + ":" + temp2);
                }

                this.field[j] = "DE" + Arr[j] + ": " + temp2;
                this.allISOMsg = this.allISOMsg.concat(temp.substring(0, len + index));
                temp = temp.substring(len + index);
            }

        }

        public String[] getAllField() {
            return this.field;
        }

        public String getIsoMessage() {
            return this.isoMsg;
        }

        public int[] getFields() {
            return this.bitmap;
        }
    }

    public static class IsoGenerate {
        private Map<Integer, byte[]> fieldMap = new HashMap();
        private int[] BitMap = new int[64];
        private int LenBitMap = 0;
        private String msgTP;
        private String header;
        private String bitMap;

        public IsoGenerate() {
        }

        public void addField(int Loc, String info) {
            if (Loc == 37 || Loc == 38 || Loc == 39 || Loc == 41 || Loc == 42 || Loc == 45 || Loc == 48 || Loc == 54 || Loc == 57 || Loc == 60 || Loc == 62 || Loc == 63) {
                info = Utility.ASCIItoHexString(info);
            }

            if (Loc == 2 || Loc == 35 || Loc == 45 || Loc == 48 || Loc == 54 || Loc == 55 || Loc == 57 || Loc == 60 || Loc == 62 || Loc == 63) {
                String sLen;
                if (Loc == 2) {
                    sLen = Integer.toString(info.length());
                    sLen = Utility.zeroPadding(sLen, 2);
                    if (info.length() % 2 != 0) {
                        info = Util.track2padding(info);
                    }

                    info = sLen + info;
                } else if (Loc == 35) {
                    int len = info.length();
                    if (len % 2 != 0) {
                        info = Util.track2padding(info);
                    }

                    //String sLen = Integer.toString(len);
                    sLen = Integer.toString(len);
                    sLen = Utility.zeroPadding(sLen, 2);
                    info = sLen + info;
                } else if (Loc == 55) {
                    sLen = Integer.toString(info.length() / 2);
                    sLen = Utility.zeroPadding(sLen, 3);
                    info = sLen + info;
                } else if (Loc != 48 && Loc != 60 && Loc != 62 && Loc != 63 && Loc != 57) {
                    sLen = Integer.toString(info.length() / 2);
                    sLen = Utility.zeroPadding(sLen, 2);
                    info = sLen + info;
                } else {
                    sLen = Integer.toString(info.length() / 2);
                    sLen = Utility.zeroPadding(sLen, 3);
                    info = sLen + info;
                }
            }

            if (Loc == 23 && info.length() < 3) {
                info = Utility.zeroPadding(info, 3);
            }

            if (info.length() % 2 != 0) {
                info = Utility.zeroPadding(info, 1 + info.length());
            }

            this.fieldMap.put(Loc, Utility.HexString2Bytes(info));
            if (Loc != 2 && Loc != 35 && Loc != 45) {
                Util.debugLogPrint(Util.TAG, "DE:" + Loc + "-->" + info);
                Util.printLog("DE:" + Loc + "-->" + info);
            } else {
                Util.debugLogPrint(Util.TAG, "DE:" + Loc + "-->" + Util.hideCardDetails(info));
                Util.printLog("DE:" + Loc + "-->" + info);
            }

            int[] tempArr = new int[this.LenBitMap];
            System.arraycopy(this.BitMap, 0, tempArr, 0, this.LenBitMap);
            if (this.LenBitMap != 0) {
                if (!Util.valueExist(tempArr, Loc)) {
                    this.BitMap[this.LenBitMap] = Loc;
                    ++this.LenBitMap;
                }
            } else {
                this.BitMap[this.LenBitMap] = Loc;
                ++this.LenBitMap;
            }

        }

        public void setMessageType(String messageType) {
            this.msgTP = messageType;
            Util.debugLogPrint(Util.TAG, "MessageType: " + this.msgTP);
            Util.printLog("MessageType: " + this.msgTP);
        }

        public void setTPDU(String Header) {
            this.header = "600" + Header + "0000";
            Util.debugLogPrint(Util.TAG, "TPDU: " + this.header);
            Util.printLog("TPDU: " + this.header);
        }

        public String getIsoMessage() {
            this.bitMap = Util.getBitMap(this.BitMap, this.LenBitMap);
            IsoMessage mf = new IsoMessage(Utility.HexString2Bytes(this.header), Utility.HexString2Bytes(this.msgTP), Utility.HexString2Bytes(this.bitMap), this.fieldMap);
            int len = Utility.Bytes2HexString(mf.getAllMessageByteData()).length() / 2;
            String msg;
            if (len < 255) {
                msg = "00" + Integer.toHexString(len) + Utility.Bytes2HexString(mf.getAllMessageByteData());
            } else {
                msg = Integer.toHexString(len) + Utility.Bytes2HexString(mf.getAllMessageByteData());
            }

            if (msg.length() % 2 != 0) {
                msg = Utility.zeroPadding(msg, 1 + msg.length());
            }

            return msg;
        }

        public byte[] getIsoMessageByte() {
            String msg = this.getIsoMessage();
            Util.printLog("BitMap: " + this.bitMap);
            return Utility.HexString2Bytes(msg);
        }

        public void remove(int Loc) {
            byte[] value = (byte[])this.fieldMap.remove(Loc);

            for(int j = 0; j < 64; ++j) {
                if (this.BitMap[j] == Loc) {
                    System.arraycopy(this.BitMap, j + 1, this.BitMap, j, 63 - j);
                    --this.LenBitMap;
                    break;
                }
            }

            if (value != null) {
                String info = Utility.Bytes2HexString(value);
                if (Loc != 2 && Loc != 35 && Loc != 45) {
                    Util.debugLogPrint(Util.TAG, "DE:" + Loc + "<--" + info);
                    Util.printLog("DE:" + Loc + "<--" + info);
                } else {
                    Util.debugLogPrint(Util.TAG, "DE:" + Loc + "<--" + Util.hideCardDetails(info));
                    Util.printLog("DE:" + Loc + "<--" + info);
                }
            }
        }
    }
}
