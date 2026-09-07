package com.sc.mf919.java.activity;

import android.util.Log;

import com.sc.mf919.kotlin.helper_common.ServiceHolder;

public class PasswordDb
{
    private static final String TAG = "PASSWORDDB";

    private static void sysPrint(String message)
    {
        Utils.debugLogPrint(TAG,message);
    }

    private static void sysPrint(String message, byte[] data, int dataOffset, int dataLen)
    {
        Utils.debugLogPrint(TAG, Utils.byteArrayToHexString(data, dataOffset, dataLen));
    }

    public interface CountSign
    {
        String TableName = "CounterSign";
        String settingPw = "Setting";
        String adminPw = "Admin";
        String vendorPw = "Vendor";
        String systemPw = "System";
        String keyPw = "Keys";
    }

    static class PasswordManagementDb
    {
        private Database.DatabaseAccess DAs;
        private String tableName = "password.db";

        PasswordManagementDb()
        {
            DAs = new Database.DatabaseAccess(ServiceHolder.Companion.getContext(), "password.db");
        }

    }

    public String getSecureData(String tag, String subtag)
    {
        this.sysPrint("TODO: getSecureData(...)");
        return null;
    }

    public String getKeyDataData(String tag, String subtag)
    {
        Database.KeyInfoTable KeyInfoTable = new Database.KeyInfoTable();

        //Utils.debugLogPrint(TAG, "getIsoBatchData: " + batchInfoTable.getIsoBatchInfo(tag, subtag));

        String strResp = KeyInfoTable.getKeyInfo(tag, subtag);
        if(strResp == null)
        {
            this.sysPrint("ERR: Unable to locate the column for " + tag);
            return null;
        }

        return strResp;
    }

    public int setKeyData(String tag, String subtag, String value)
    {
        Database.KeyInfoTable KeyInfoTable = new Database.KeyInfoTable();

        int iResp = KeyInfoTable.setKeyInfo(tag, subtag, value);
        if(iResp<=0)
        {
            this.sysPrint("ERR:Failed to update DB. Affected Count=" + String.valueOf(iResp));
            return Global.iso.err.DB_Update;
        }

        return iResp;
    }
}
