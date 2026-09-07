package com.sc.mf919.java.activity;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.library.terminal.Utility;
import utils.HexUtil;
import com.sc.mf919.kotlin.data_enum.AcquirerSettingModel;
import com.sc.mf919.kotlin.database.model.DbModelProductList;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;
import com.sc.mf919.kotlin.helper_common.TmsHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import helpers.HelperCommon;
import helpers.HelperLog;

public class CubeActivity implements Serializable {
    EmvTag etag;
    byte[] cubess_tlv_db;

    //private IsoEngine isoengine=null;
    //IsoSteps isos;

    private byte cubess_activatedAccType = 0;
    private String amount = "";
    private String cacheVl3Data = "";
    private String cacheDe55 = "";

    private String[] receiptInfo = new String[19];
    private String[] txnStatusInfo = new String[8];

    public CubeActivity() {
        etag = new EmvTag();
        cubess_tlv_db = new byte[4096];
    }

    private static void sysPrint(String message) {
        Utils.debugLogPrint("CUBE", message);
    }

    private static void sysPrint(String message, byte[] data, int dataOffset, int dataLen) {
        Utils.debugLogPrint("CUBE", HexUtil.bcd2str(data, dataOffset, dataLen));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int tlv_add_by_tv(String tag, byte[] data, int datalen) {
        return etag.addTlvByTv(tag, data, 0, datalen, cubess_tlv_db);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int tlv_add_by_tv_in_string(String tag, String strData) {
        byte[] data;
        data = HexUtil.hexStringToByte(strData);
        //Utils.debugLogPrint("TAG",""+cubess_tlv_db.length);
        //Utils.debugLogPrint("TAG", "tlv_add_by_tv_in_string_1: " + HexUtil.bytesToHexString(cubess_tlv_db));
        //int tm=etag.addTlvByTv(tag, data, 0, data.length, cubess_tlv_db);
        //Utils.debugLogPrint("TAG", "tlv_add_by_tv_in_string_2: " + HexUtil.bytesToHexString(cubess_tlv_db));
        //return tm;
        return etag.addTlvByTv(tag, data, 0, data.length, cubess_tlv_db);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int tlv_remove_tag(String tag) {
        return etag.removeTlvByT(cubess_tlv_db, tag);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public int tlv_get_value(String tag, byte[] data) {
        return etag.getValueFrom(cubess_tlv_db, tag, data);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public String tlv_get_value_in_string(String tag) {
        byte[] data = new byte[50];
        int iDataLen = etag.getValueFrom(cubess_tlv_db, tag, data);
        if (iDataLen <= 0){
            //Gavin Modify
            return "";
            //return null;
        }

        return HexUtil.bytesToHexString(data, 0, iDataLen);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public String tlv_get_value_in_asciistring(String tag) {
        //byte[] data = new byte[50];
        byte[] data = new byte[200];
        int iDataLen = etag.getValueFrom(cubess_tlv_db, tag, data);
        if (iDataLen <= 0)
            return null;

        return Utils.byteArrayToAsciiString(data, 0, iDataLen);
    }

    //return len of tlvs
    int tlv_get_all_tlvs(byte[] dest) {
        int iLen = Utils.get_ushort(cubess_tlv_db, 0);
        if (iLen > 0) {
            Utils.memcpy(dest, 0, cubess_tlv_db, 2, iLen);
        }

        return iLen;
    }

    void tlv_debugPrint() {
        int iLen = Utils.get_ushort(cubess_tlv_db, 0);
        if (iLen < 0)
            iLen = 0;

        sysPrint("TLVDB[" + String.valueOf(iLen) + "]:", cubess_tlv_db, 2, iLen);
    }

    public int _cube_setActivatedAcceptance(byte accType) {
        cubess_activatedAccType |= (1 << accType);

        return 0;
    }

    public byte cube_activeAcceptance(byte accType) {
        if (accType == Global.cube.CUBE_ACCTYPE_EMVCT) {
            Utils.printLog("EMVCT Activated");
            _cube_setActivatedAcceptance(accType);
        } else if (accType == Global.cube.CUBE_ACCTYPE_EMVCL) {
            Utils.printLog("EMVCL Activated");
            _cube_setActivatedAcceptance(accType);
        } else if (accType == Global.cube.CUBE_ACCTYPE_QR_SCAN) {
            Utils.printLog("QR Scan Activated");
            _cube_setActivatedAcceptance(accType);
        }

        //Utils.printLog("CUBE:Activated Acceptance=%04X\n", cubess_activatedAccType);

        return cubess_activatedAccType;
    }

    public int _cube_clearActivatedAcceptance(byte accType) {
        cubess_activatedAccType &= (~(long) (1 << accType));

        return 0;
    }

    public int _cube_isAcceptanceActivated(byte accType) {
        if ((cubess_activatedAccType & (1 << accType)) > 0)
            return 1;
        else
            return 0;
    }

    /*public void searchCard(String amount,int timeout)
    {
        initialTransParam(amount,(byte) 1,"0001");
        TP=null;
        payMethod = 0x00;
        cardReaderHelper = GoBizTerminal.getApp().getDal().getCardReaderHelper();
        try
        {
            Utils.debugLogPrint("TAG", "searchCard: Start Card Search");
            PollingResult result = cardReaderHelper.polling(EReaderType.ICC_PICC,timeout);
            Utils.debugLogPrint("TAG", "searchCard: " + result.getTrack2());
            if (result.getOperationType() == PollingResult.EOperationType.OK)
            {
                switch (result.getReaderType())
                {
                    case ICC:
                    {
                        payMethod=0x01;
                        break;
                    }
                    case PICC:
                    {
                        payMethod=0x02;
                        break;
                    }
                    case MAG:
                    {
                        payMethod=0x03;
                        break;
                    }
                    default:
                    {
                        payMethod=0x04;
                        break;
                    }
                }
            }
            else if (result.getOperationType() == PollingResult.EOperationType.TIMEOUT)
            {
                payMethod=0x06;
            }
            else if (result.getOperationType() == PollingResult.EOperationType.CANCEL)
            {
                payMethod=0x05;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            payMethod=0x05;
        }

        if(cardReaderHelper!=null)
        {
            cardReaderHelper.stopPolling();
        }
    }*/

    public String getMid(String schemeTag) {
        //String value = IsoDb.getIsoBatchInfo("mid", schemeTag);
        String value = "3300000033";

        return value;
    }

    public String getTid(String schemeTag) {
        //String value = IsoDb.getIsoBatchInfo("tid", schemeTag);
        String value = "10000001";

        return value;
    }

    public static boolean isDebugMsgEnable() {
        /*String temp = getFileValue(TransFieldConstant.files.configFile, "DebugLog");
        if (temp == null) {temp = "0";}
        return (!temp.equals("0"));*/
        return true;
    }

    public static void debugLogPrint(String TAG1, String msg) {
        if (isDebugMsgEnable()) {
            Utils.debugLogPrint(TAG1, msg);
        }

        if (ServiceHolder.Companion.getContext() != null) {
            syslogD(TAG1 + ":--> " + msg);
        }
    }

    public static void syslog(String log) {
        Utils.debugLogPrint("CUBE", log);
    }

    public static void syslogD(String log) {
        /*if (fileSizeInKb("DebugLog.txt") > 5000)
        {
            deleteFiles("DebugLog.txt");
        }
        log = ASCIItoHexString(log);
        String len = zeroPadding(Integer.toString(log.length()), 4);
        try
        {
            writeToFile(len + encrypt(log, encryptKey()), "DebugLog.txt");
        } catch (Exception e)
        {
            e.printStackTrace();
        }*/
    }

    public static void syslogE(String TAGs, String functionName, String logs) {
        syslog(TAGs + "--" + functionName + " ---> Error:" + logs);
    }

    public String getCubess_tlv_db() {
        //String value="";
        //byte[] tmp = new byte[25];
        //System.arraycopy(cubess_tlv_db,0,tmp,0,25);
        Utils.debugLogPrint("TAG", "getCubess_tlv_db: " + HexUtil.bytesToHexString(cubess_tlv_db));
        return HexUtil.bytesToHexString(cubess_tlv_db);
    }

    public void setCubess_tlv_db(String value) {
        if (value != null) {
            byte[] tmp = HexUtil.hexStringToByte(value);
            System.arraycopy(tmp, 0, cubess_tlv_db, 0, tmp.length);
        }
    }

    public void setCacheDe55(String value) {
        cacheDe55 = value;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public String[] generateTxnStatusInfo() {
        //0. Type
        //1. Amount
        //2. Card No
        //3. Approval code
        //4. Rrn
        //5. Tracfe no
        //6. txn dt
        //7. RespCode

        String tmp;
        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_TXN_TYPE);
        if (tmp == null) {
            tmp = "";
        }
        txnStatusInfo[0] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_TXN_AMT);
        if (tmp == null) {
            tmp = "0.00";
        } else {
            tmp = Utils.getActualAmount(tmp);
        }
        txnStatusInfo[1] = "RM " + tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARDPAN_MASKBCD);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        txnStatusInfo[2] = tmp;

        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_APPRCODE);
        if (tmp == null) {
            tmp = "";
        }
        txnStatusInfo[3] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_RRN);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        txnStatusInfo[4] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_INVNO);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        txnStatusInfo[5] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_TXN_DATETIME7);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        txnStatusInfo[6] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_RESPCODE);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        txnStatusInfo[7] = tmp;

        return txnStatusInfo;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public String[] generateReceiptInfo() {
        //0. MID
        //1. TID
        //2. Batch No
        //3. Type of transaction
        //4. application label
        //5. card no
        //6. txn dt
        //7. invoice no
        //8. stan no
        //9. entry type
        //10. rrn
        //11. appr code
        //12. cashout amt
        //13. amt
        //14. Epp details
        //15. arqc 9f26
        //16. aid
        //17. tvr (95)
        //18. cvm (check pin)

        AcquirerSettingModel acqSetting = ServiceHolder.Companion.getAcquirerSetting();
        String acqName = acqSetting.getAcqName();

        String tmp;
        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_MID);
        if (tmp == null) {
            tmp = "";
        }
        if (acqName.toUpperCase(Locale.ENGLISH).equals("GOBIZ")) {
            receiptInfo[0] = Utils.maskString(tmp, 4);
        } else receiptInfo[0] = tmp;

        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_TID);
        if (tmp == null) {
            tmp = "";
        }
        if (acqName.toUpperCase(Locale.ENGLISH).equals("GOBIZ")) {
            receiptInfo[1] = Utils.maskString(tmp, 4);
        } else receiptInfo[1] = tmp;

        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_BATCHNO);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[2] = tmp;

        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_TXN_TYPE);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[3] = Utils.getTxnType(tmp);

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_APPLABEL);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        receiptInfo[4] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARDPAN_MASKBCD);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        receiptInfo[5] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_TXN_DATETIME7);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        receiptInfo[6] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_INVNO);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        receiptInfo[7] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_STAN);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[8] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_ENTRY_MODE);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        receiptInfo[9] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_RRN);
        if (tmp == null) {
            tmp = "";
        } else {
            tmp = Utility.HexString2ASCII(tmp);
        }
        receiptInfo[10] = tmp;

        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_APPRCODE);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[11] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CASH_OUT_AMOUNT);
        if (tmp == null) {
            tmp = "0.00";
        } else {
            tmp = Utils.getActualAmount(tmp);
        }
        receiptInfo[12] = tmp;


        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_TXN_AMT);
        if (tmp == null) {
            tmp = "0.00";
        } else {
            tmp = Utils.getActualAmount(tmp);
        }
        receiptInfo[13] = tmp;

        tmp = tlv_get_value_in_asciistring(Global.cube.CUBE_TAG_EPP_DETAILS);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[14] = tmp.trim();

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_ARQC);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[15] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_AID);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[16] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_TVR);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[17] = tmp;

        tmp = tlv_get_value_in_string(Global.cube.CUBE_TAG_CARD_CVM);
        if (tmp == null) {
            tmp = "";
        }
        receiptInfo[18] = tmp;

        String a = Arrays.toString(receiptInfo);

        return receiptInfo;
    }
}
