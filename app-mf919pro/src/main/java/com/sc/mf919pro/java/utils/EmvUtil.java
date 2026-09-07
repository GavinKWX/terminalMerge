package com.sc.mf919pro.java.utils;

import utils.HexUtil;
import utils.StringUtils;
import utils.TlvData;

import helpers.LogRedact;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;

import com.morefun.yapi.emv.EmvDataSource;
import com.morefun.yapi.emv.EmvTermCfgConstrants;
import com.morefun.yapi.emv.EmvTransDataConstrants;
import com.sc.mf919pro.java.activity.Utils;
import com.sc.mf919pro.java.device.DeviceHelper;
import com.sc.mf919pro.kotlin.data_enum.variables.TransData;
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;
import com.sc.mf919pro.kotlin.helper_common.iso.CardTagsEnum;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class EmvUtil {

    private final static String TAG = EmvUtil.class.getName();

    public static final String[] arqcTLVTags = new String[]{
            "9F26",
            "9F27",
            "9F10",
            "9F37",
            "9F36",
            "95",
            "9A",
            "9B",
            "9C",
            "9F02",
            "9F03",
            "5F2A",
            "5F34",
            "82",
            "9F1A",
            "9F33",
            "9F34",
            "9F35",
            "9F1E",
            "84",
            "9F09",
            "9F63",
            "56",
            "57",
            "6B",
            "50",
            "8E",
            "9F39",
            "9F6E",
            "4F",
            "9F41",
            "9F7B",
            "8F",
            "8C",
            "9F53",
            "9F32",
            "9F22"
    };

    public static final String[] tags = new String[]{
            "50",
            "5F20",
            "5F30",
            "9F03",
            "9F26",
            "9F27",
            "9F10",
            "9F37",
            "9F36",
            "95",
            "9A",
            "9B",
            "9C",
            "9F02",
            "5F2A",
            "5F34",
            "82",
            "9F1A",
            "9F03",
            "9F33",
            "9F34",
            "9F35",
            "9F1E",
            "84",
            "9F09",
            "9F41",
            "9F63",
            "56",
            "57",
            "8E",
            "9F39",
            "9F12",
            "9F6E",
            "4F",
            "9F41",
            "9F7B",
            "8C",
            "9F53",
            "9F32",
            "9F22"
    };


    public static byte[] getExampleARPCData() {
        //TODO Data returned by background server ,should be contain 91 tag, if you need to test ARPC
        // such as : 91 0A F9 8D 4B 51 B4 76 34 74 30 30 ,   if need to set 71 and 72  ,Please add this String
        return HexUtil.hexStringToByte("8A023030");
    }

    public static Bundle getInitTermConfig() {
        Bundle bundle = new Bundle();
        bundle.putByteArray(EmvTermCfgConstrants.TERMCAP, new byte[]{(byte) 0xE0, (byte) 0xF0, (byte) 0xC8});
        //bundle.putByteArray(EmvTermCfgConstrants.TERMCAP, new byte[]{(byte) 0xE0, (byte) 0xE0, (byte) 0xC8});
        bundle.putByteArray(EmvTermCfgConstrants.ADDTERMCAP, new byte[]{(byte) 0xF2, (byte) 0x00, (byte) 0xF0, (byte) 0xA0, (byte) 0x01});
        //bundle.putByteArray(EmvTermCfgConstrants.ADDTERMCAP, new byte[]{(byte) 0x60, (byte) 0x00, (byte) 0xF8, (byte) 0xA0, (byte) 0x01});
        bundle.putByte(EmvTermCfgConstrants.TERMTYPE, (byte) 0x22);
        bundle.putByteArray(EmvTermCfgConstrants.COUNTRYCODE, new byte[]{(byte) 0x04, (byte) 0x58});
        bundle.putByteArray(EmvTermCfgConstrants.CURRENCYCODE, new byte[]{(byte) 0x04, (byte) 0x58});
        bundle.putByteArray(EmvTermCfgConstrants.TRANS_PROP_9F66, new byte[]{0x36, (byte) 0x00, (byte) 0xC0, (byte) 0x00});
        //bundle.putByteArray(EmvTermCfgConstrants.TRANS_PROP_9F66, new byte[]{0x36, (byte) 0x00, (byte) 0x40, (byte) 0x80}); //VISA CERT
        //bundle.putByteArray(EmvTermCfgConstrants.TRANS_PROP_9F66, new byte[]{0x26, (byte) 0x00, (byte) 0x40, (byte) 0x80});
        bundle.putByteArray(EmvTermCfgConstrants.FORCEONLINE,new byte[]{0x00});
        return bundle;
    }

    /*public static Bundle getTransBundle(String amount, String cashBackAmt, boolean isEnableContact, boolean isEnableContactless) {
        return getInitBundleValue(-1, amount, cashBackAmt, isEnableContact, isEnableContactless);
    }*/

    public static Bundle getTransBundle(String amount, String cashOutAmount, int channel, boolean isEnableContact, boolean isEnableContactless) {
        Bundle bundle = new Bundle();

        DbModelTerminalConfig terminalConfig = ServiceHolder.Companion.getTerminalConfig();
        String date = getCurrentTime("yyMMddHHmmss");

        //TODO revamp
        bundle.putInt(EmvTransDataConstrants.CHANNELTYPE, channel);
        bundle.putBoolean(EmvTransDataConstrants.FORCE_ONLINE_CALL_PIN, false);
        bundle.putBoolean(EmvTransDataConstrants.CONTACT_SERVICE_SWITCH, false);
        bundle.putBoolean(EmvTransDataConstrants.SELECT_APP_RETURN_PRIORITY, false);
        bundle.putInt(EmvTransDataConstrants.CHECK_CARD_TIME_OUT, 30);
        //bundle.putBoolean(EmvTransDataConstrants.EMV_KERNEL_CALL_BACK_SWITCH, false);
        //bundle.putString(EmvTransDataConstrants.CONTACTLESS_PIN_FREE_AMT, "50000");
        //TODO revamp

        bundle.putInt(EmvTransDataConstrants.MKEYIDX, 1);
        bundle.putInt(EmvTransDataConstrants.ISQPBOCFORCEONLINE, 0);

        if(!cashOutAmount.equalsIgnoreCase("0.00")) {
            long salesAmount = Utils.convertLong(amount.replaceAll("\\.", ""));
            long newCashOut = Utils.convertLong(cashOutAmount.replaceAll("\\.", ""));
            long newAmount = salesAmount - newCashOut;
            amount = Utils.getActualAmount(Long.toString(newAmount));
            bundle.putByte(EmvTransDataConstrants.B9C, (byte) 0x09);
        } else {
            bundle.putByte(EmvTransDataConstrants.B9C, (byte) 0x00);
        }
        bundle.putString(EmvTransDataConstrants.TRANSDATE, date.substring(0, 6));
        bundle.putString(EmvTransDataConstrants.TRANSTIME, date.substring(6, 12));
        bundle.putString(EmvTransDataConstrants.SEQNO, "00001");
        bundle.putString(EmvTransDataConstrants.TRANSAMT, amount);
        bundle.putString(EmvTransDataConstrants.CASHBACKAMT, cashOutAmount);

        bundle.putString(EmvTransDataConstrants.MERNAME, "MOREFUN");
        bundle.putString(EmvTransDataConstrants.MERID, "488923");
        bundle.putString(EmvTransDataConstrants.TERMID, "500");
        bundle.putBoolean(EmvTransDataConstrants.CONTACT_SERVICE_SWITCH, true);
        bundle.putBoolean(EmvTransDataConstrants.BLACK_CARD_HASH_SWITCH, false);

        bundle.putBoolean(EmvTransDataConstrants.EMV_TRANS_ENABLE_CONTACT, isEnableContact);
        bundle.putBoolean(EmvTransDataConstrants.EMV_TRANS_ENABLE_CONTACTLESS, isEnableContactless);

        ArrayList<String> tlvList = StringUtils.createArrayList("DF81180160", "DF81190108", "DF811B0130");
        if(DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "OptIn")){
            bundle.putBoolean(EmvTransDataConstrants.SELECT_APP_RETURN_AID, true);
            tlvList.add("DF7F05A000000615");
        }
        bundle.putStringArrayList(EmvTransDataConstrants.TERMINAL_TLVS, tlvList);


        return bundle;
    }

    public static String readPan() {
        String pan = getPbocData("5A", true);
        if (TextUtils.isEmpty(pan)) {
            return getPanFromTrack2();
        }
        if (pan.endsWith("F")) {
            return pan.substring(0, pan.length() - 1);
        }
        return pan;
    }

    public static String getPbocData(String tagName, boolean isHex) {
        try {
            byte[] data = new byte[512];
            Utils.debugLogPrint(TAG, "getPbocData Tag:" + tagName);
            int len = DeviceHelper.getEmvHandler().readEmvData(new String[]{tagName.toUpperCase()}, data, new Bundle());
            if (len > 0) {
                TlvData tlvData = TlvData.fromRawData(HexUtil.subByte(data, 0, len), 0);
                if (isHex) {
                    return tlvData.getValue();
                } else {
                    return tlvData.getGBKValue();
                }
            }
            return null;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }


    public static String readTrack2() {
        String track2 = getPbocData(EmvDataSource.GET_TRACK2_TAG_6B, true);
        if (!TextUtils.isEmpty(track2) && track2.endsWith("F")) {
            track2 = track2.substring(0, track2.length() - 1);
        }
        // D7 — register the in-flight card data with the log sink the moment it is known, so
        // AsyncLogWriter can redact it from EVERY line, including raw TLV/hex dumps that no
        // call-site audit found. Registering here covers all readers: this is the single point
        // the track data is obtained from the kernel.
        if (!TextUtils.isEmpty(track2)) {
            String pan = track2;
            int sep = -1;
            for (int i = 0; i < track2.length(); i++) {
                char c = track2.charAt(i);
                if (c == '=' || c == 'D' || c == 'd') { sep = i; break; }
            }
            if (sep > 0) pan = track2.substring(0, sep);
            LogRedact.registerCardData(pan, track2);
        }
        Utils.debugLogPrint(TAG, "readTrack2: " + LogRedact.track2(track2));
        return track2;
    }


    protected static String getPanFromTrack2() {
        String track2 = readTrack2();
        if (track2 != null) {
            for (int i = 0; i < track2.length(); i++) {
                if (track2.charAt(i) == '=' || track2.charAt(i) == 'D') {
                    int endIndex = Math.min(i, 19);
                    return track2.substring(0, endIndex);
                }
            }
        }
        return null;
    }

    public static String getTLVDatas(String[] tags) {
        for (int i = 0; i < tags.length; i++) {
            tags[i] = tags[i].toUpperCase();
        }
        try {
            byte[] buffer = new byte[3096];
            int byteNum = DeviceHelper.getEmvHandler().readEmvData(tags, buffer, new Bundle());
            if (byteNum > 0) {
                return HexUtil.bytesToHexString(HexUtil.subByte(buffer, 0, byteNum));
            } else {
                return "";
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getTLVDatas_1()
    {
        String[] tags=new String[]{"50","72","82","84","91","95","9A","9B","9C","D4","5F2A","5F30","5F34","9F02","9F03","9F06","9F09","9F10","9F1A","9F1E","9F6E","9F22","9F26","9F27","9F28","9F29","9F32","9F33","9F34","9F35","9F36","9F37","9F41","9F53", "9F66","DF21","DF31"};
        try {
            byte[] buffer = new byte[3096];
            int byteNum = DeviceHelper.getEmvHandler().readEmvData(tags, buffer, new Bundle());
            if (byteNum > 0) {
                return HexUtil.bytesToHexString(HexUtil.subByte(buffer, 0, byteNum));
            } else {
                return "";
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getCurrentTime(String format) {
        SimpleDateFormat df = new SimpleDateFormat(format);
        Date curDate = new Date(System.currentTimeMillis());
        return df.format(curDate);
    }

//    @JvmStatic
//    fun getAcquirerRequiredTlvDataAisino(): String {
//        val emvTagString = StringBuilder()
//        CardTagsEnum.getAcquirerChipTags(TransData.acqCode, TransData.schemeType)?.let {
//            for (lTag in it) {
//                getTlvTagStringAisino(lTag)?.let {
//                    emvTagString.append(it.joinToString(""))
//                }
//            }
//        }
//        return emvTagString.toString()
//    }

    public static String getAcquirerRequiredTlvData() {
        String[] tags = CardTagsEnum.Companion.getAcquirerChipTags(TransData.INSTANCE.getAcqCode(), TransData.INSTANCE.getSchemeType());
        try {
            byte[] buffer = new byte[3096];
            int byteNum = DeviceHelper.getEmvHandler().readEmvData(tags, buffer, new Bundle());
            if (byteNum > 0) {
                return HexUtil.bytesToHexString(HexUtil.subByte(buffer, 0, byteNum));
            } else {
                return "";
            }
        } catch (RemoteException e) {
            e.printStackTrace();
            return "";
        }
    }
}
