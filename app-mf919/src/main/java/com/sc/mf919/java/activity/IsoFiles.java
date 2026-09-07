package com.sc.mf919.java.activity;

public class IsoFiles
{
    private static String isoConfigFile = "isoengine.ini";
    private static String merchantConfigFile = "merchantInfo.txt";
    private static int connectTimeoutMs = 3500;
    private static int timeoutMs = 30000;

    public interface files
    {
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

    public static String getHostIp(boolean Secondary)
    {
        String temp;

        if (Secondary)
            temp = Utils.getFileValue(merchantConfigFile/*isoConfigFile*/, "ip2");
        else
            temp = Utils.getFileValue(merchantConfigFile/*isoConfigFile*/, "ip1");

        return (temp);
    }

    public static String getHostPort(boolean Secondary)
    {
        String temp;

        if (Secondary)
            temp = Utils.getFileValue(merchantConfigFile/*isoConfigFile*/, "port2");
        else
            temp = Utils.getFileValue(merchantConfigFile/*isoConfigFile*/, "port1");

        return (temp);
    }

    public static int getHostConnectTimeout()
    {
        int result = connectTimeoutMs;
        try{
            int iConnTimeoutMs = Integer.parseInt(Utils.getFileValue(isoConfigFile, "connecttimeoutms"));
            if(iConnTimeoutMs <= 0){
                result = connectTimeoutMs;
            }else {
                result = iConnTimeoutMs;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return result;
    }

    public static int getHostTimeout()
    {
        int iTimeoutMs = Integer.parseInt(Utils.getFileValue(merchantConfigFile/*isoConfigFile*/, "timeoutms"));
        if(iTimeoutMs<=0)
            iTimeoutMs = timeoutMs;

        return iTimeoutMs;
    }
}
