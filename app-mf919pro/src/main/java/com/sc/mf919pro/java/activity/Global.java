package com.sc.mf919pro.java.activity;

import com.sc.mf919pro.R;
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum;

public class Global
{
    public class cube
    {
        public static final byte CUBE_ACCTYPE_EMVCT = 0x01;
        public static final byte CUBE_ACCTYPE_EMVCL = 0x02;
        public static final byte CUBE_ACCTYPE_QR_SCAN = 0x03;
        public static final byte CUBE_ACCTYPE_QR_SHOW = 0x04;

        public static final String CUBE_TAG_TXN_TYPE = "1F01";
        public static final String CUBE_TAG_TXN_AMT = "1F02"; /*BCD6*/
        public static final String CUBE_TAG_TXN_DATETIME7 = "1F03"; /*YYYYMMMDDHHMMSS*/
        public static final String CUBE_TAG_DUKPT_KSN = "1F04";
        public static final String CUBE_TAG_DUKPT_PIN_KSN = "1F05";
        public static final String CUBE_TAG_PYMT_PRODUCT_ID = "1F06";
        public static final String CUBE_TAG_POS_REFERENCE = "1F07";

        public static final String CUBE_TAG_CARDPAN_CLEAR = "3F01"; /*ASCII*/
        public static final String CUBE_TAG_CARDPAN_MASK = "3F02"; /*ASCII*/
        public static final String CUBE_TAG_CARDPAN_MASKBCD = "3F03"; /*ASCII*/
        public static final String CUBE_TAG_CARDPAN_HASH = "3F04"; /*ASCII*/
        public static final String CUBE_TAG_CARD_EXP_MMYY = "3F05"; /*ASCII*/
        public static final String CUBE_TAG_CARD_APPLABEL = "3F06";
        public static final String CUBE_TAG_CARD_ARQC = "3F07";
        public static final String CUBE_TAG_CARD_AID = "3F08";
        public static final String CUBE_TAG_CARD_CVM = "3F09";
        public static final String CUBE_TAG_CARD_TVR = "3F10";
        public static final String CUBE_TAG_CARD_SCHEME_ID = "3F11";
        public static final String CUBE_TAG_CARD_ENTRY_MODE = "3F12";
        public static final String CUBE_TAG_CARD_TSI = "3F13";
        public static final String CUBE_TAG_RETAIL_AMOUNT = "3F14";
        public static final String CUBE_TAG_CASH_OUT_AMOUNT = "3F15";

        public static final String CUBE_TAG_MTI = "DF00";
        public static final String CUBE_TAG_NII = "BF24";
        public static final String CUBE_TAG_STAN = "BF11";
        public static final String CUBE_TAG_TID = "BF41";
        public static final String CUBE_TAG_MID = "BF42";
        public static final String CUBE_TAG_RRN = "BF37";
        public static final String CUBE_TAG_APPRCODE = "BF38";
        public static final String CUBE_TAG_RESPCODE = "BF39";
        public static final String CUBE_TAG_CHIPDATA = "BF55";
        public static final String CUBE_TAG_BATCHNO = "BF60";
        public static final String CUBE_TAG_INVNO = "BF62";
        public static final String CUBE_TAG_EPP_DETAILS = "BF63";
    }

    public static class tlv
    {
        public class err
        {
            public static final int tagNotFound = (-1);
            public static final int invalidTag = (-2);
            public static final int invalidLen = (-3);
            public static final int invalidValue = (-4);
        }
    }

    public static class iso
    {
        public class err
        {
            public static final int ok = 0;
            public static final int txnApprovedOffline = 1001;
            public static final int failed = (-1);
            public static final int invalidInputData = (-11);
            public static final int invalidInputLen = (-11);

            public static final int tnxCompleted = 0;
            public static final int txnApproved = 0;

            public static final int connectionFailed = (-10001);
            public static final int hostOffline = (-100002);
            public static final int communicationTimeout = (-20002);
            public static final int txnTimeout = (-20002);
            public static final int txnNotProcessed = (-20003);
            public static final int txnNotCompleted = (-20004);
            public static final int txnDeclined = (-20005);
            public static final int reconcileError = (-20006);
            public static final int txnNotAllowed = (-20007);
            public static final int txnReEnter = (-20008); /*'19'*/
            public static final int txnHostOffline = (-20009);
            public static final int txnDeclined_insertCard = (-20010);
            public static final int txnDeclined_pinError = (-20011);
            public static final int txnDeclined_pinExceeded = (-20012);/*Pin Tries Exceeded*/
            public static final int txnDeclined_pinNeeded = (-20013);/*Pin Tries Exceeded*/
            public static final int txnDeclined_invalidStan = (-20014);
            public static final int txnDeclined_emptySecureKey = (-20015);

            public static final int txnProcessResp = 10;
            public static final int fileNotFound = -8001;
            public static final int fileOpenFailed = -8002;
            public static final int fileWriteFailed = -8003;
            public static final int fileReadFailed = -8004;
            public static final int fileCloseFailed = -8005;
            public static final int fileBoudaryErr = -8006;
            public static final int fileFull = -8007;
            public static final int fileOutOfRange = -8008;
            public static final int DB_Failed = -80009;

            public static final int DB_CONN_FAIL = (-12001);
            public static final int DB_Insert = (-12002);
            public static final int DB_Update = (-12003);

            public static final int selectNext = (-303);

            public static final int segmentationFault = -90099;
        }

        public class tag
        {
            //public const uint AID = 0xDF00;//to be confirmed
            //public const uint PANSTRING = 0xDF01;
            //public const uint TXN_DT7 = 0xDF02;
            public static final byte PIN = (byte)0x99;
            public static final String TPDU = "C1";
            public static final byte TRACK1 = (byte)0xD1;
            public static final byte TRACK2 = (byte)0xD2;
            public static final String TXN_DT7 = "D4";
            public static final String PANSTRING = "D5";
            public static final byte PANFIRST4 = (byte)0xD6;
            public static final byte PANLAST4 = (byte)0xD7;
            public static final String AID = "D8";
            public static final byte TXNTYPE = (byte)0xD9; /*stirng = Sale, Preauth, OffSale, settle*/
            public static final byte SCHEME_ID = (byte)0xDA;
            public static final byte BATCH_STATUS = (byte)0xDB;
            public static final String EXPDATE = "DC";

            public static final String MTI = "DF00";
            public static final String BMP = "DF66";
            public static final String MTI_RESP = "BF00";
            public static final String TPDU_RESP = "BF65";
            public static final String BMP_RESP = "BF66";
            public static final String RECEIPT_DATA = "BF67";
        }

        public interface settings
        {
            String hostIp = "192.168.3.77";
            String hostPort = "3828";
            String posEntryCode = "0071";//global pos entry code --> verify first
            String posCondition = "00";
            byte settleCheck = 0;
            int maxTxnTotal = 0;
            int maxTxnCount = 999;
        }

        public interface isoInfo
        {
            static String getSchemeId(
                    String scheme,
                    int schemeType, /*2=contactless, 1=contact, 3=magstripe*/
                    String acqCode
            )
            {
                Utils.debugLogPrint("TAG:isoInfo", scheme);
                String schemeId = "00";
                switch(scheme)
                {
                    case "VISA":
                        if(schemeType == 2)
                            schemeId = "11";
                        else if(schemeType == 3)
                            schemeId = "12";
                        else
                            schemeId = "91";
                        break;
                    case "MASTER":
                        if(schemeType == 2)
                            schemeId = "21";
                        else if(schemeType == 3)
                            schemeId = "20";
                        else if(schemeType == 4)
                            schemeId = "22";
                        else
                            schemeId = "92";
                        break;
                    case "PBOC":
                        if(schemeType == 2)
                            schemeId = "31";
                        else if(schemeType == 3)
                            schemeId = "30";
                        else
                            schemeId = "93";
                        break;
                    case "MCCS":
                        /*if(schemeType == 2)
                            schemeId = "81";
                            //else if(schemeType == 3)
                            //    schemeId = "80";
                        else
                            schemeId = "98";*/
                        if(acqCode.equalsIgnoreCase(AcquirerLogoDataEnum.BSN.name())) {
                            if(schemeType == 2) schemeId = "81";
                            else schemeId = "98";
                        } else {
                            if(schemeType == 2) schemeId = "82";
                            else schemeId = "99";
                        }
                        break;
                }

                return schemeId;
            }
        }
    }

    public static class paymentInterfaceConfig
    {
        static int getStringID(int id)
        {
            int imageID=0;
            switch (id)
            {
                case 0:
                    imageID= R.string.wave1;
                    break;
                case 1:
                    imageID= R.string.chip1;
                    break;
            }
            return (imageID);
        }

        static int getImageID(int id)
        {
            int imageID=0;
            switch (id)
            {
                case 0:
                    imageID= R.drawable.ic_waveicon;
                    break;
                case 1:
                    imageID= R.drawable.ic_pinicon;
                    break;
            }
            return (imageID);
        }

        public static boolean contactVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Sale Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean contactlessVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Settlement Visible");
            String temp = "1";
            return !temp.equals("0");
        }
    }

    public static class paymentInfo
    {
        static int getPaymentStatusInfoId(int id)
        {
            int infoId = R.string.terminal;
            switch(id)
            {
                case 0:
                    infoId = R.string.pay_status_info_type;
                    break;
                case 1:
                    infoId = R.string.pay_status_info_amount;
                    break;
                case 2:
                    infoId = R.string.pay_status_info_cardno;
                    break;
                case 3:
                    infoId = R.string.pay_status_info_authcode;
                    break;
                case 4:
                    infoId = R.string.pay_status_info_rrn;
                    break;
                case 5:
                    infoId = R.string.pay_status_info_traceno;
                    break;
                case 6:
                    infoId = R.string.pay_status_info_datetime;
                    break;
                case 7:
                    infoId = R.string.pay_status_info_respcode;
                    break;
            }
            return infoId;
        }
    }

    public static class appConfig
    {
        static int getStringID(int id)
        {
            int imageID=0;
            switch (id)
            {
                case 0:
                    imageID= R.string.sale1;
                    break;
                case 1:
                    imageID= R.string.voids;
                    break;
                case 2:
                    imageID= R.string.pre_auth;
                    break;
                case 3:
                    imageID= R.string.refund;
                    break;
                case 4:
                    imageID= R.string.settlement;
                    break;
                case 5:
                    imageID= R.string.setting;
                    break;
            }
            return (imageID);
        }

        static int getImageID(int id)
        {
            int imageID=0;
            switch (id)
            {
                case 0:
                    imageID= R.drawable.ic_saleicon;
                    break;
                case 1:
                    imageID= R.drawable.ic_voidicon;
                    break;
                case 2:
                    imageID= R.drawable.ic_preauthicon;
                    break;
                case 3:
                    //imageID= R.drawable.ic_refundicon_1;
                    break;
                case 4:
                    imageID= R.drawable.ic_settlementicon;
                    break;
                case 5:
                    imageID= R.drawable.ic_settingicon;
                    break;
                case 6:
                    //imageID= R.drawable.ic_bill_icon;
                    break;
            }
            return (imageID);
        }

        public static boolean saleVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Sale Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean settleVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Settlement Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean voidVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Void Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean refundVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Refund Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean preAuthVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Pre-Auth Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean settingVisibleEnable()
        {
            //String temp = getFileValue(TransFieldConstant.files.configFile, "Setting Visible");
            String temp = "1";
            return !temp.equals("0");
        }

        public static boolean getExtraBtnStatus()
        {
            //String value=getFileValue(TransFieldConstant.files.addFile,"ExtBtn");
            String value = null;
            if(value==null){return false;}
            else{return !value.equals("0");}
        }
    }

    public interface filesInfo
    {
        String termInfoFile = "termInfo.txt";
        String merchantInfo = "merchantInfo.txt";
        String configFile = "config.ini";
        String SettleFile = "settle_details.txt";
        String TransFile = "trans_details.txt";
        String IsoFile = "iso_details.txt";
        String ResponseCodeFile = "ResponseCode.txt";
        String LogFile = "Log.txt";
        String PrivateKey = "client_key.der";
        String Certificate_authority ="ca_crt.pem";
        String Client_Certificate ="client_crt.pem";
        String SystemTrace = "SystemTrace.db";
        String SecureGateway = "SecureGateway.db";
        String saleComp = "SaleComp.txt";
        String tmsFile = "tms.ini";
        String appIntentFile="returnFile.txt";
        String addFile="add.ini";
        String selection="Selection.ini";
        String epay="epay.txt";
    }


    public interface paymentMethod
    {
        int ICC=1;
        int RF=2;
        int Meg=3;
        int Non=0;
        int Cancel=4;
        int Timeout=5;
    }

    public interface envSettings
    {
        //prod
        String serverHashKey = "9D3xaJR2UdqZb93e";
        //uat
        //String serverHashKey = "Zb939D3UdqexaJR2";
    }
}
