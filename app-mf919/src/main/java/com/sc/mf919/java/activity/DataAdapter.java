package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataAdapter
{
    protected static final String TAG = "DataAdapter";

    private SQLiteDatabase mDb;
    private DatabaseOpenHelper mDbHelper;
    private String filename;

    DataAdapter(Context context, String _filename)
    {
        mDbHelper = new DatabaseOpenHelper(context,_filename);
        filename=_filename;
    }

    void createDatabase() throws SQLException
    {
        try
        {
            mDbHelper.createDataBase();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"createDatabase",e.getMessage());
            Log.e(TAG, e.toString() + "  UnableToCreateDatabase");
            throw new Error("UnableToCreateDatabase");
        }
    }

    void createDatabase(byte[] database) throws SQLException
    {
        try
        {
            mDbHelper.createDataBase(database);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"createDatabase",e.getMessage());
            Log.e(TAG, e.toString() + "  UnableToCreateDatabase");
            throw new Error("UnableToCreateDatabase");
        }
    }

    void open() throws SQLException
    {
        try
        {
            mDbHelper.openDataBase();
            mDbHelper.close();
            mDb = mDbHelper.getReadableDatabase();
        }
        catch (SQLException mSQLException)
        {
            Utils.printErrorLog(TAG,"open",mSQLException.getMessage());
            Log.e(TAG, "open >>"+ mSQLException.toString());
            throw mSQLException;
        }
    }

    void close()
    {
        mDbHelper.close();
    }

    Cursor getData(String tableName)
    {
        try
        {
            String sql ="SELECT * FROM " + tableName;
            Cursor mCur = mDb.rawQuery(sql, null);
            if (mCur!=null)
            {
                mCur.moveToNext();
            }
            return mCur;
        }
        catch (SQLException e)
        {
            Utils.printErrorLog(TAG,"getData",e.getMessage());
            Log.e(TAG, "getData >>"+ e.toString());
            throw e;
        }
    }

    Cursor getData(String tableName, String tag, String value)
    {
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tag + "= ?";
            Cursor mCur = mDb.rawQuery(sql,new String[]{value});
            if (mCur!=null)
            {
                mCur.moveToNext();
            }
            return mCur;
        }
        catch (SQLException e)
        {
            Utils.printErrorLog(TAG,"getData",e.getMessage());
            Log.e(TAG, "getData >>"+ e.toString());
            return null;
        }
    }

    String getValueStr(String tags, String tableName, int index)
    {
        String value=null;
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE ID = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[] {Integer.toString(index)});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex(tags));
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    void updateFile(String tags, String value, String tableName, int id)
    {
        try
        {
            ContentValues Cv=new ContentValues();
            Cv.put(tags,value);
            mDb.update(tableName,Cv, "ID = ?",new String[] {Integer.toString(id)});
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"updateFile",e.getMessage());
        }

    }

    int insert(ContentValues Cv, String tableName)
    {
        try
        {
            Utils.printLog("ccc");
            mDb.insert(tableName,null,Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insert",e.getMessage());
            return -1;
        }
        return 1;
    }

    int getNumberRow(String tableName)
    {
        String sql ="SELECT * FROM " + tableName;
        @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql, null);
        int count=0;
        mCur.moveToFirst();
        try
        {
            while(mCur.getString(1)!=null)
            {
                mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getNumberRow",e.getMessage());
        }
        mCur.close();
        return count;
    }

    int getNumberRow(String[] tags, String[] msg, String tableName)
    {
        int count=0;
        StringBuilder tag= new StringBuilder();
        for(int j=0;j<tags.length;j++)
        {
            if(j==tags.length-1)
            {
                tag.append(tags[j]);
            }
            else
            {
                tag.append(tags[j]).append(" = ? AND ");
            }
        }
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tag.toString() + " = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,msg);
            mCur.moveToFirst();
            boolean next=true;
            while(mCur.getString(1)!=null)
            {
                next=mCur.moveToNext();
                count++;
            }
            mCur.close();
        }
        catch (Exception e)
        {
           count=0;
        }
        return count;
    }

    int [] getAllID(String tableName)
    {
        String sql ="SELECT * FROM " + tableName;
        @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql, null);
        int count=0;
        int[] value = new int[mCur.getCount()];
        mCur.moveToFirst();
        boolean next=true;
        try
        {
            while(next)
            {
                value[count]=mCur.getInt(mCur.getColumnIndex("ID"));
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getAllID",e.getMessage());
            Utils.debugLogPrint(TAG,e.getMessage());
        }
        mCur.close();
        int[] returnVal = new int[count];
        System.arraycopy(value,0,returnVal,0,count);
        return(returnVal);
    }

    int get1stID(String tableName)
    {
        int returnVal;
        String sql ="SELECT * FROM " + tableName;
        @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql, null);
        mCur.moveToFirst();
        returnVal=mCur.getInt(mCur.getColumnIndex("ID"));
        mCur.close();
        return(returnVal);
    }

    void deleteARow(String tableName, int id)
    {
        mDb.delete(tableName,"id=?",new String[] {Integer.toString(id)});
    }

    void deleteAllRow(String tableName)
    {
        mDb.delete(tableName,null,null);
    }

    String[] listOfTable()
    {
        String[] temp;
        int count=0;
        @SuppressLint("Recycle") Cursor c = mDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
        temp = new String[c.getCount()];
        if (c.moveToFirst())
        {
            while ( !c.isAfterLast() )
            {
                temp[count]=c.getString(0);
                c.moveToNext();
                count++;
            }
        }
        String[] returnVal = new String[count];
        System.arraycopy(temp,0,returnVal,0,count);
        c.close();
        return returnVal;
    }

    int getID(String tags, String tableName, String msg)
    {
        int value=0;
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tags + "= ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[] {msg});
            mCur.moveToFirst();
            value=mCur.getInt(mCur.getColumnIndex("ID"));
            mCur.getColumnNames();
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    int[] getIDs(String tags, String tableName, String msg)
    {
        int count=0;
        int[] value = new int[0];
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tags + "= ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[] {msg});
            value = new int[mCur.getCount()];
            mCur.moveToFirst();
            boolean next=true;
            while(next)
            {
                value[count]=mCur.getInt(mCur.getColumnIndex("ID"));
                next=mCur.moveToNext();
                count++;
            }
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    int[] getIDsNotCond(String tags, String tableName, String msg)
    {
        int count=0;
        int[] value = new int[0];
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tags + "!= ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[] {msg});
            value = new int[mCur.getCount()];
            mCur.moveToFirst();
            boolean next=true;
            while(next)
            {
                value[count]=mCur.getInt(mCur.getColumnIndex("ID"));
                next=mCur.moveToNext();
                count++;
            }
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    int[] getIDs(String[] tags, String tableName, String[] msg)
    {
        int count=0;
        int[] value = new int[0];
        StringBuilder tag= new StringBuilder();
        for(int j=0;j<tags.length;j++)
        {
            if(j==tags.length-1)
            {
                tag.append(tags[j]);
            }
            else
            {
                tag.append(tags[j]).append(" = ? AND ");
            }
        }
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tag.toString() + " = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,msg);
            value = new int[mCur.getCount()];
            mCur.moveToFirst();
            boolean next=true;
            while(next)
            {
                value[count]=mCur.getInt(mCur.getColumnIndex("ID"));
                next=mCur.moveToNext();
                count++;
            }
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    @SuppressLint("Recycle")
    String getValue(String tag, String subTag, String tableName)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT value FROM " + tableName + " WHERE tag = ? AND subtag = ?";
            mCur = mDb.rawQuery(sql,new String[]{tag,subTag});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("value"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    public int updateTerminalConfigurationTable(String Contact, String Contactless, String Magstripe, String QrPay, String ForcePin, String IsoPrint, String ReceiptPrint, String OptIn, String SaleComOnline, String AutoSettle, String Sale, String Void, String PreAuth, String SaleCom, String Refund, String TmsReceipt, String TmsEnable) {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("Contact", Contact);
            Cv.put("Contactless", Contactless);
            Cv.put("Magstripe", Magstripe);
            Cv.put("QrPay", QrPay);
            Cv.put("ForcePin", ForcePin);
            Cv.put("IsoPrint", IsoPrint);
            Cv.put("ReceiptPrint", ReceiptPrint);
            Cv.put("OptIn", OptIn);
            Cv.put("SaleComOnline", SaleComOnline);
            Cv.put("AutoSettle", AutoSettle);
            Cv.put("Sale", Sale);
            Cv.put("Void", Void);
            Cv.put("PreAuth", PreAuth);
            Cv.put("SaleCom", SaleCom);
            Cv.put("Refund", Refund);
            Cv.put("TmsReceipt", TmsReceipt);
            Cv.put("TmsEnable", TmsEnable);

            iResp = mDb.update("TerminalConfiguration", Cv, "DEV_LANE_ID=\'1\'", null);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"updateTerminalConfigurationTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    //sf
    @SuppressLint("Recycle")
    String getIsoBatchData(String tag, String subtag)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql = "";
            if((subtag != null) && (subtag != ""))
                sql ="SELECT value FROM " + "isoBatchInfo" + " WHERE tag = ? AND subtag = ?";
            else
                sql ="SELECT value FROM " + "isoBatchInfo" + " WHERE tag = ?";

            Utils.debugLogPrint("ISOBATCHINFOGET", sql);

            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("value"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getIsoBatchData",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getIsoBatchData>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int getIsoBatchDataId(String tag, String subtag)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "isoBatchInfo" + " WHERE tag = ? AND subtag = ?";
            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getIsoBatchDataId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getIsoBatchDataId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    int setIsoBatchData(String tag, String subtag, String value)
    {
        int iResp = -1;
        try
        {
            //
            int iId = getIsoBatchDataId(tag, subtag);
            if(iId<=0)
            {
                //insert
                ContentValues Cvs = new ContentValues();
                Cvs.put("tag", tag);
                Cvs.put("subtag", subtag);
                Cvs.put("value", value);
                insert(Cvs, "isoBatchInfo");
            }
            else
            {
                //update
                ContentValues Cv = new ContentValues();
                Cv.put("value", value);
                iResp = mDb.update("isoBatchInfo", Cv, "id = ?", new String[]{Integer.toString(iId)});
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"setIsoBatchInfo",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;

    }

    @SuppressLint("Recycle")
    String getIsoBatchLongData(String tag, String subtag)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql = "";
            if((subtag != null) && (subtag != ""))
                sql ="SELECT value FROM " + "isoBatchLongInfo" + " WHERE tag = ? AND subtag = ?";
            else
                sql ="SELECT value FROM " + "isoBatchLongInfo" + " WHERE tag = ?";

            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("value"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getIsoBatchLongData",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getIsoBatchLongData>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int getIsoBatchLongDataId(String tag, String subtag)
    {
        Utils.printLog("tag=" + tag + ", subtag=" + subtag);
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "isoBatchLongInfo" + " WHERE tag = ? AND subtag = ?";
            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getIsoBatchLongDataId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getIsoBatchLongDataId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    int setIsoBatchLongData(String tag, String subtag, String value)
    {
        int iResp = -1;
        try
        {
            //
            int iId = getIsoBatchLongDataId(tag, subtag);
            Utils.printLog("getBatchLongDataId=" + String.valueOf(iId));
            if(iId<=0)
            {
                Utils.printLog("aaa");
                //insert
                ContentValues Cvs = new ContentValues();
                Cvs.put("tag", tag);
                Cvs.put("subtag", subtag);
                Cvs.put("value", value);
                insert(Cvs, "isoBatchLongInfo");
                Utils.printLog("bbb");
            }
            else
            {
                //update
                ContentValues Cv = new ContentValues();
                Cv.put("value", value);
                iResp = mDb.update("isoBatchLongInfo", Cv, "id = ?", new String[]{Integer.toString(iId)});
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"setIsoBatchLongInfo",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;

    }

    int getSecureDataId(String tag, String subtag)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "secureData" + " WHERE tag = ? AND subtag = ?";
            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSecureDataId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSecureDataId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    int getSecureDataId(String tag)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "secureData" + " WHERE tag = ?";
            mCur = mDb.rawQuery(sql,new String[]{tag});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSecureDataId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSecureDataId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    @SuppressLint("Recycle")
    int setSecureData(String tag, String subtag, String value)
    {
        int iResp = -1;
        try
        {
            int iId = getSecureDataId(tag, subtag);
            //int iId = getSecureDataId(tag);
            if(iId<=0)
            {
                //insert
                ContentValues Cvs = new ContentValues();
                Cvs.put("tag", tag);
                Cvs.put("subtag", subtag);
                Cvs.put("value", value);
                iResp = insert(Cvs, "secureData");
            }
            else
            {
                //update
                ContentValues Cv = new ContentValues();
                Cv.put("value", value);
                //Gavin add
                Cv.put("subtag", subtag);
                //Gavin add
                iResp = mDb.update("secureData", Cv, "id = ?", new String[]{Integer.toString(iId)});
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"setSecureData",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;

    }

    @SuppressLint("Recycle")
    String getSecureData(String tag, String subtag)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql = "";
            if((subtag != null) && (subtag != ""))
                sql ="SELECT value FROM " + "secureData" + " WHERE tag ='" + tag + "' AND subtag ='" + subtag + "'";
            else
                sql ="SELECT value FROM " + "secureData" + " WHERE tag ='" + tag +"'";

            Utils.debugLogPrint("DA:securedata", sql);

            mCur = mDb.rawQuery(sql,null/*new String[]{tag,subtag}*/);
            boolean next = mCur.moveToFirst();
            Utils.debugLogPrint(TAG, "getSecureData: " + next);
            value=mCur.getString(mCur.getColumnIndex("value"));
            /*Utils.debugLogPrint(TAG, "getSecureData: " + value);
            for(int j=0;j<mCur.getColumnCount();j++)
            {
                Utils.debugLogPrint(TAG, "getSecureData: " + mCur.getString(j) + "-->" + mCur.getColumnName(j));
            }*/
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSecureData",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSecureData>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int insertBatchTable(String postingDt, String stan, String txnType, String invNo, String batchData, String schemeTag, String schemeId, int refId, String status, String batchNo, String mid, String tid)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("stan", stan);
            Cv.put("txnType", txnType);
            Cv.put("invNo", invNo);
            Cv.put("batchData", batchData);
            Cv.put("schemeTag", schemeTag);
            Cv.put("schemeId", schemeId);
            Cv.put("refId", String.valueOf(refId));
            Cv.put("status", status);
            Cv.put("batchNo", batchNo);
            Cv.put("mid", mid);
            Cv.put("tid", tid);

            iResp = (int)mDb.insert("batchTable", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertBatchTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    int resetBatchTableFlag(String mid, String tid, String batchNo, String fromOldStatus, String toNewStatus)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("status", toNewStatus);

            if((fromOldStatus != null) && (fromOldStatus != ""))
                iResp = mDb.update("batchTable", Cv, "mid= ? AND tid = ? AND batchNo = ? AND fromOldStatus = ?", new String[]{mid, tid, batchNo, fromOldStatus});
            else
                iResp = mDb.update("batchTable", Cv, "mid= ? AND tid = ? AND batchNo = ?", new String[]{mid, tid, batchNo});
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"resetBatchTableFlag",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;
    }

    int setBatchTableFlag(int id, String newStatus)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("status", newStatus);
            iResp = mDb.update("batchTable", Cv, "id = ?", new String[]{String.valueOf(id)});
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"setBatchTableFlag",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;
    }

    int getSingleBatchTableRecordId(String status)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "batchTable" + " WHERE status =?";

            mCur = mDb.rawQuery(sql,new String[]{status});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableRecordId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableRecordId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    int getSingleBatchTableRecordId_byInvNo(String invNo, String status)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "batchTable" + " WHERE invNo = ? AND status =?";

            mCur = mDb.rawQuery(sql,new String[]{invNo, status});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableRecordId_byInvNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableRecordId_byInvNo>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    String getSingleBatchTableRecord(int id)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT batchData FROM " + "batchTable" + " WHERE id= ? ";

            mCur = mDb.rawQuery(sql,new String[]{Integer.toString(id)});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("batchData"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableRecord",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableRecord>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getSingleBatchTableScheme(int id)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT * FROM batchTable WHERE id= ? ";

            mCur = mDb.rawQuery(sql,new String[]{Integer.toString(id)});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("schemeTag"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableSchemeTag",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableSchemeTag>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getSingleBatchTableSchemeId_byInvNo(String invNo)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT * FROM batchTable WHERE invNo= ? ";

            mCur = mDb.rawQuery(sql,new String[]{invNo});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("schemeId"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableSchemeId_byInvNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableSchemeId_byInvNo>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getSingleBatchTableTxnType_byInvNo(String invNo)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT * FROM batchTable WHERE invNo= ? ";

            mCur = mDb.rawQuery(sql,new String[]{invNo});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("txnType"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableTxnType_byInvNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableTxnType_byInvNo>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int deleteBatchTableRecord(String id)
    {
        return mDb.delete("batchTable","id = ?",new String[] {id});
    }

    int deleteBatchTableRecords(String batchNo, String schemeTag)
    {
        return mDb.delete("batchTable","batchNo = ? AND schemeTag = ?", new String[] {batchNo, schemeTag});
    }

    int deleteBatchTableRecord_byInvNo(String invNo)
    {
        return mDb.delete("batchTable","invNo = ?", new String[] {invNo});
    }

    int insertPreauthTable(String postingDt, String cardData, String schemeTag, String apprCode, String rrn, String invNo, String status, String addInfo)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("cardData", cardData);
            Cv.put("schemeTag", schemeTag);
            Cv.put("apprCode", apprCode);
            Cv.put("rrn", rrn);
            Cv.put("invNo", invNo);
            Cv.put("status", status);
            Cv.put("addInfo", addInfo);

            iResp = (int)mDb.insert("preauthTable", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertPreauthTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    int getSinglePreauthTableRecordId(String schemeTag, String status)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "preauthTable" + " WHERE schemeTag = ? AND status =?";

            mCur = mDb.rawQuery(sql,new String[]{schemeTag, status});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSinglePreautTableRecordId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePreauthTableRecordId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    int getSinglePreauthTableRecordId_byInvNo(String invNo, String status)
    {
        int iId;
        try
        {
            String sql ="SELECT * FROM preauthTable WHERE invNo = ? AND status = ?";
            Utils.debugLogPrint(TAG, "getSinglePreauthTableRecordId_byInvNo: " + sql + "  " + invNo  + "  " + status) ;
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{invNo, status});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getSinglePreauthTableRecordId_byInvNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePreauthTableRecordId_byInvNo>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    String[] getSinglePreauthTableRecord(int id)
    {
        Cursor mCur=null;
        String[] value = new String[5];
        try
        {
            String sql ="SELECT * FROM " + "preauthTable" + " WHERE id= ? ";

            mCur = mDb.rawQuery(sql,new String[]{Integer.toString(id)});
            mCur.moveToFirst();
            value[0]=mCur.getString(mCur.getColumnIndex("cardData"));
            value[1]=mCur.getString(mCur.getColumnIndex("apprCode"));
            value[2]=mCur.getString(mCur.getColumnIndex("rrn"));
            value[3]=mCur.getString(mCur.getColumnIndex("invNo"));
            value[4]=mCur.getString(mCur.getColumnIndex("addInfo"));
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSinglePreauthTableRecord",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePreauthTableRecord>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int deletePreauthTableRecord(String id)
    {
        return mDb.delete("preauthTable","id = ?",new String[] {id});
    }

    int deletePreauthTableRecords(String batchNo, String schemeTag)
    {
        return mDb.delete("preauthTable","batchNo = ? AND schemeTag = ?", new String[] {batchNo, schemeTag});
    }

    int deletePreauthTableRecord_byInvNo(String invNo)
    {
        return mDb.delete("preauthTable","invNo = ?", new String[] {invNo});
    }

    String getSinglePreauthTableScheme(int id)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT * FROM preauthTable WHERE id= ? ";

            mCur = mDb.rawQuery(sql,new String[]{Integer.toString(id)});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("schemeTag"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSinglePreauthTableSchemeTag",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePreauthTableSchemeTag>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getSinglePreauthTableSchemeId_byInvNo(String invNo)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT * FROM preauthTable WHERE invNo= ? ";

            mCur = mDb.rawQuery(sql,new String[]{invNo});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("schemeId"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSinglePreauthTableSchemeId_byInvNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePreauthTableSchemeId_byInvNo>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int insertTxnReceiptTable(String postingDt, String[] receiptInfo)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("txnDt", receiptInfo[0]);
            Cv.put("txnType", receiptInfo[1]);
            Cv.put("mti", receiptInfo[2]);
            Cv.put("nii", receiptInfo[3]);
            Cv.put("mid", receiptInfo[4]);
            Cv.put("tid", receiptInfo[5]);
            Cv.put("cardFirst4", receiptInfo[6]);
            Cv.put("cardLast4", receiptInfo[7]);
            Cv.put("cardHash", receiptInfo[8]);
            Cv.put("schemeId", receiptInfo[9]);
            Cv.put("aid", receiptInfo[10]);
            Cv.put("txnAmt", receiptInfo[11]);
            Cv.put("invoiceNo", receiptInfo[12]);
            Cv.put("stan", receiptInfo[13]);
            Cv.put("batchNo", receiptInfo[14]);
            Cv.put("batchStatus", receiptInfo[15]);
            Cv.put("txnRespCode", receiptInfo[16]);
            Cv.put("txnApprCode", receiptInfo[17]);
            Cv.put("rrn", receiptInfo[18]);
            Cv.put("plazaId", receiptInfo[19]);
            Cv.put("laneId", receiptInfo[20]);
            Cv.put("jobId", receiptInfo[21]);
            Cv.put("txnId", receiptInfo[22]);
            Cv.put("blStatus", receiptInfo[23]);
            Cv.put("status", receiptInfo[24]);

            iResp = (int)mDb.insert("txnReceipt", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertTxnReceiptTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    int insertPrintReceiptTable(String postingDt, String txnDt, String txnType, String cardMasked, String schemeId, String txnAmt, String invNo, String stan, String txnApprCode, String receiptInfo)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("txnDt", txnDt);
            Cv.put("txnType", txnType);
            Cv.put("cardMasked", cardMasked);
            Cv.put("schemeId", schemeId);
            Cv.put("txnAmt", txnAmt);
            Cv.put("invoiceNo", invNo);
            Cv.put("stan", stan);
            Cv.put("txnApprCode", txnApprCode);
            Cv.put("receiptInfo", receiptInfo);

            iResp = (int)mDb.insert("printReceipt", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertPrintReceiptTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    int deletePrintReceiptTableRecords()
    {
        return mDb.delete("printReceipt",null, null);
    }

    String[] getPrintReceiptStanArr()
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT stan FROM printReceipt";

            mCur = mDb.rawQuery(sql,null);
            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            int count=0;
            while(next)
            {
                value[count]= mCur.getString(mCur.getColumnIndex("stan"));
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptStanArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptStanArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getPrintReceiptInfo_byStan(String stan)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT receiptInfo FROM printReceipt WHERE stan= ? ";

            mCur = mDb.rawQuery(sql,new String[]{stan});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("receiptInfo"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptInfo_byStanNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptInfo_byStanNo>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int sqlDeleteVoidedPrintReceiptInfo(String txnAmount, String invoiceNo, String stanNo) {
        return mDb.delete("printReceipt","txnAmt = ? AND invoiceNo = ? AND stan = ?", new String[] {txnAmount, invoiceNo, stanNo});
    }

    String[] getPrintReceiptInfo_byID(String stan)
    {
        Cursor mCur = null;
        String[] value = new String[15]; // Increment when add field
        try
        {
            String sql ="SELECT * FROM printReceiptQr WHERE id= ? ";
            mCur = mDb.rawQuery(sql,new String[]{stan});
            mCur.moveToFirst();
            //Log.d(TAG, "getPrintReceiptInfo_byID: " + Arrays.toString(mCur.getColumnNames()));
            value[0]=mCur.getString(mCur.getColumnIndex("txnDt"));
//            value[1]=mCur.getString(mCur.getColumnIndex("payBrand"));
            value[1]=mCur.getString(mCur.getColumnIndex("productCode"));
            value[2]=mCur.getString(mCur.getColumnIndex("txnType"));
            //value[3]=Utils.maskString(mCur.getString(mCur.getColumnIndex("mid")), 4);
            value[3]=mCur.getString(mCur.getColumnIndex("mid"));
            //value[4]=Utils.maskString(mCur.getString(mCur.getColumnIndex("tid")), 4);
            value[4]=mCur.getString(mCur.getColumnIndex("tid"));
            value[5]=mCur.getString(mCur.getColumnIndex("txnApprCode"));
            value[6]=mCur.getString(mCur.getColumnIndex("hostRefNo"));
            value[7]="RM" + Utils.getActualAmount(mCur.getString(mCur.getColumnIndex("txnAmt")));
            value[8]=mCur.getString(mCur.getColumnIndex("refId"));
            value[9]=mCur.getString(mCur.getColumnIndex("acqCode"));

            value[10]=mCur.getString(mCur.getColumnIndex("isUnionPayTxn"));
            value[11]=mCur.getString(mCur.getColumnIndex("upiVoucherCode"));
            value[12]="RM" + Utils.getActualAmount(mCur.getString(mCur.getColumnIndex("upiDiscountAmt")));
            value[13]=mCur.getString(mCur.getColumnIndex("upiMarkupFee"));
            if (value[10] != "") {
                try {
                    String txnAmt = mCur.getString(mCur.getColumnIndex("txnAmt"));
                    String discAmt = mCur.getString(mCur.getColumnIndex("upiDiscountAmt"));

                    long txnAmtLong = Long.parseLong(txnAmt);
                    long discAmtLong = Long.parseLong(discAmt);

                    long finalAmt = txnAmtLong - discAmtLong;
                    value[14]= "RM" + Utils.getActualAmount(String.format("%012d", finalAmt));
                } catch (Exception e) {}
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptInfo_byStanNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptInfo_byStanNo>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String[] getMultiplePrintReceiptInfo(String schemeIds)
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql = "";
            if (schemeIds.isEmpty()) {
                sql ="SELECT pr.txnDt, pr.txnType, pr.cardMasked, pr.schemeId, pr.txnAmt, pr.stan, tr.invoiceNo, tr.rrn FROM printReceipt pr LEFT JOIN txnReceipt tr ON (tr.stan = pr.stan) ORDER BY pr.invoiceNo, pr.txnDt";
            } else {
                String ids = schemeIds.replace('"', ' ');
                sql ="SELECT pr.txnDt, pr.txnType, pr.cardMasked, pr.schemeId, pr.txnAmt, pr.stan, tr.invoiceNo, tr.rrn FROM printReceipt pr LEFT JOIN txnReceipt tr ON (tr.stan = pr.stan) WHERE pr.schemeId IN (" + ids + ") ORDER BY pr.invoiceNo, pr.txnDt";
            }

            mCur = mDb.rawQuery(sql,null);

            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            Map<String, String> value2 = new HashMap<>();

            int count=0;
            while(next)
            {
                for(int j=0;j<8;j++)
                {
                    value2.put(mCur.getColumnName(j), mCur.getString(j));
                }

                value2.put("Scheme", Utils.getSchemeName(mCur.getString(3)));

                value[count] = new Gson().toJson(value2);
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptInfoRefIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptInfoRefIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int insertQrPayTable(String postingDt, String txnType, String payBrand, String txnDt, String seqNo, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String status, String addInfo, String acqCode, String apprCode, String type, String productCode, String productName, String isUPIQRTxn, String upiVoucherCode, String upiDiscountAmt, String upiMarkupFee)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("txnType", txnType);
            Cv.put("payBrand", payBrand);
            Cv.put("txnDt", txnDt);
            Cv.put("seqNo", seqNo);
            Cv.put("mid", mid);
            Cv.put("tid", tid);
            Cv.put("txnAmt", txnAmt);
            Cv.put("refId", refId);
            Cv.put("hostRefNo", hostRefNo);
            Cv.put("txnRefNo", txnRefNo);
            Cv.put("status", status);
            Cv.put("addInfo", addInfo);
            Cv.put("acqCode", acqCode);
            Cv.put("txnApprCode", apprCode);
            Cv.put("type", type);
            Cv.put("productCode", productCode);
            Cv.put("productName", productName);

            Cv.put("isUnionPayTxn", isUPIQRTxn);
            Cv.put("upiVoucherCode", upiVoucherCode);
            Cv.put("upiDiscountAmt", upiDiscountAmt);
            Cv.put("upiMarkupFee", upiMarkupFee);

            iResp = (int)mDb.insert("qrPayTable", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertQrPaytTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    String[] getSingleQrPayTableRecord(String refId)
    {
        Cursor mCur=null;
        String[] value=new String[getNumberColumn("qrPayTable")];
        try
        {
            String sql ="SELECT * FROM " + "qrPayTable" + " WHERE refId= ? ";

            mCur = mDb.rawQuery(sql,new String[]{refId});
            mCur.moveToFirst();
            for(int j=0;j<value.length;j++)
            {
                value[j]=mCur.getString(j);
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableRecord",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableRecord>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String[] getMultipleQrPayTableRecord(@Nullable List<String> productNames)
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT * FROM qrPayTable ORDER BY refId, txnDt";

            if (productNames != null && productNames.size() > 0) {
                String products = String.join("\", \"", productNames);
                sql = "SELECT * FROM qrPayTable WHERE payBrand COLLATE NOCASE IN (\"" + products + "\") ORDER BY refId, txnDt";
            }

            mCur = mDb.rawQuery(sql,null);

            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            String[] qrData = new String[getNumberColumn("qrPayTable")];
            Map<String, String> value2 = new HashMap<>();
            int count=0;
            while(next)
            {
                for(int j=0;j<qrData.length;j++)
                {
                    value2.put(mCur.getColumnName(j), mCur.getString(j));
                }

                value[count] = new Gson().toJson(value2).toString();
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptQrRefIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrRefIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int deleteQrPayTableRecord(String refId)
    {
        return mDb.delete("qrPayTable","refId = ?",new String[] {refId});
    }

    int deleteQrPayTableRecord()
    {
        return mDb.delete("qrPayTable",null,null);
    }

    int getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        int iTotalCount;
        try
        {
            String sql ="SELECT count(*) FROM qrPayTable WHERE payBrand = ? AND txnType = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            iTotalCount=mCur.getInt(mCur.getColumnIndex("count(*)"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand>: " + e.getMessage());
            iTotalCount=0;
        }
        return iTotalCount;
    }

    String getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT SUM(txnAmt) FROM qrPayTable WHERE payBrand= ?  AND txnType= ?";
            mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("SUM(txnAmt)"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int insertPrintReceiptQrTable(String postingDt, String txnType, String payBrand, String txnDt, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String respCode, String status, String addInfo, String printInfo, String acqCode, String apprCode, String type, String productCode, String productName, String isUPIQRTxn, String upiVoucherCode, String upiDiscountAmt, String upiMarkupFee)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("txnType", txnType);
            Cv.put("payBrand", payBrand);
            Cv.put("txnDt", txnDt);
            Cv.put("mid", mid);
            Cv.put("tid", tid);
            Cv.put("txnAmt", txnAmt);
            Cv.put("refId", refId);
            Cv.put("hostRefNo", hostRefNo);
            Cv.put("txnRefNo", txnRefNo);
            Cv.put("respCode", respCode);
            Cv.put("status", status);
            Cv.put("addInfo", addInfo);
            Cv.put("printInfo", printInfo);
            Cv.put("acqCode", acqCode);
            Cv.put("txnApprCode", apprCode);
            Cv.put("type", type);
            Cv.put("productCode", productCode);
            Cv.put("productName", productName);

            Cv.put("isUnionPayTxn", isUPIQRTxn);
            Cv.put("upiVoucherCode", upiVoucherCode);
            Cv.put("upiDiscountAmt", upiDiscountAmt);
            Cv.put("upiMarkupFee", upiMarkupFee);

            iResp = (int)mDb.insert("printReceiptQr", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertPrintReceiptQr",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    String[] getSinglePrintReceiptQrTableRecord(String refId)
    {
        Cursor mCur=null;
        String[] value=new String[getNumberColumn("printReceiptQr")];
        try
        {
            String sql ="SELECT * FROM " + "printReceiptQr" + " WHERE refId= ? ";

            mCur = mDb.rawQuery(sql,new String[]{refId});
            mCur.moveToFirst();
//            for(int j=0;j<value.length;j++)
//            {
//                value[j]=mCur.getString(j);
//            }
            value[0]=mCur.getString(mCur.getColumnIndex("txnDt"));
            value[1]=mCur.getString(mCur.getColumnIndex("productCode"));
            value[2]=mCur.getString(mCur.getColumnIndex("txnType"));
            value[3]=mCur.getString(mCur.getColumnIndex("mid"));
            value[4]=mCur.getString(mCur.getColumnIndex("tid"));
            value[5]=mCur.getString(mCur.getColumnIndex("txnApprCode"));
            value[6]=mCur.getString(mCur.getColumnIndex("hostRefNo"));
            value[7]="RM" + Utils.getActualAmount(mCur.getString(mCur.getColumnIndex("txnAmt")));
            value[8]=mCur.getString(mCur.getColumnIndex("refId"));
            value[9]=mCur.getString(mCur.getColumnIndex("acqCode"));

            /* handle UPI txn */
            value[10]=mCur.getString(mCur.getColumnIndex("isUnionPayTxn"));
            value[11]=mCur.getString(mCur.getColumnIndex("upiVoucherCode"));
            value[12]=mCur.getString(mCur.getColumnIndex("upiDiscountAmt"));
            value[13]=mCur.getString(mCur.getColumnIndex("upiMarkupFee"));
            if (value[10] != "") {
                try {
                    String txnAmt = mCur.getString(mCur.getColumnIndex("txnAmt"));
                    String discAmt = mCur.getString(mCur.getColumnIndex("upiDiscountAmt"));

                    long txnAmtLong = Long.parseLong(txnAmt);
                    long discAmtLong = Long.parseLong(discAmt);

                    long finalAmt = txnAmtLong - discAmtLong;
                    value[14]= "RM" + Utils.getActualAmount(String.format("%012d", finalAmt));
                } catch (Exception e) {}
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSinglePrintReceiptQrTableRecord",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePrintReceiptQrTableRecord>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String[] getPrintReceiptQrRefIdArr()
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT refId FROM printReceiptQr";

            mCur = mDb.rawQuery(sql,null);
            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            int count=0;
            while(next)
            {
                value[count]= mCur.getString(mCur.getColumnIndex("refId"));
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptQrRefIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrRefIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String[] getPrintReceiptQrIdArr()
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT id FROM printReceiptQr";

            mCur = mDb.rawQuery(sql,null);
            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            int count=0;
            while(next)
            {
                value[count]= mCur.getString(mCur.getColumnIndex("id"));
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptQrIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        int iTotalCount;
        try
        {
            String sql ="SELECT count(*) FROM printReceiptQr WHERE payBrand = ? AND txnType = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            iTotalCount=mCur.getInt(mCur.getColumnIndex("count(*)"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand>: " + e.getMessage());
            iTotalCount=0;
        }
        return iTotalCount;
    }

    int getPrintReceiptQrTableTotalTxnCount_byTxnType(String txnType)
    {
        int iTotalCount;
        try
        {
            String sql ="SELECT count(*) FROM printReceiptQr WHERE txnType = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{txnType});
            mCur.moveToFirst();
            iTotalCount=mCur.getInt(mCur.getColumnIndex("count(*)"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getPrintReceiptQrTableTotalTxnCount_byTxnType",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrTableTotalTxnCount_byTxnType>: " + e.getMessage());
            iTotalCount=0;
        }
        return iTotalCount;
    }

    String getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT SUM(txnAmt) FROM printReceiptQr WHERE payBrand= ?  AND txnType= ?";
            mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("SUM(txnAmt)"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getPrintReceiptQrTableTotalTxnAmt_byTxnType(String txnType)
    {
        Cursor mCur=null;
        String value;
        try
        {
            //String sql ="SELECT SUM(txnAmt) FROM printReceiptQr WHERE payBrand= ?  AND txnType= ?";
            String sql ="SELECT SUM(txnAmt) FROM printReceiptQr WHERE txnType= ?";
            mCur = mDb.rawQuery(sql, new String[]{txnType});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("SUM(txnAmt)"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptQrTableTotalTxnAmt_byTxnType",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptQrTableTotalTxnAmt_byTxnType>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int deletePrintReceiptQrTableRecord()
    {
        return mDb.delete("printReceiptQr",null,null);
    }

    int deletePrintReceiptQrTable_byRefId(String refId)
    {
        return mDb.delete("printReceiptQr","refId = ?",new String[] {refId});
    }

    //
    // Password
    //
    @SuppressLint("Recycle")
    String getKeyData(String tag, String subtag)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql = "";
            if((subtag != null) && (subtag != ""))
                sql ="SELECT value FROM " + "KeyInfo" + " WHERE tag = ? AND subtag = ?";
            else
                sql ="SELECT value FROM " + "KeyInfo" + " WHERE tag = ?";

            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("value"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getKeyData",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getKeyData>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int getKeyDataId(String tag, String subtag)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "KeyInfo" + " WHERE tag = ? AND subtag = ?";
            mCur = mDb.rawQuery(sql,new String[]{tag,subtag});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getKeyDataId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getKeyDataId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    int setKeyData(String tag, String subtag, String value)
    {
        int iResp = -1;
        try
        {
            //
            int iId = getKeyDataId(tag, subtag);
            if(iId<=0)
            {
                //insert
                ContentValues Cvs = new ContentValues();
                Cvs.put("tag", tag);
                Cvs.put("subtag", subtag);
                Cvs.put("value", value);
                insert(Cvs, "KeyInfo");
            }
            else
            {
                //update
                ContentValues Cv = new ContentValues();
                Cv.put("value", value);
                iResp = mDb.update("KeyInfo", Cv, "id = ?", new String[]{Integer.toString(iId)});
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"setKeyInfo",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;

    }

    int getNumberColumn(String tableName)
    {
        String sql ="SELECT * FROM " + tableName;
        @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql, null);
        String[] columnNames = mCur.getColumnNames();
        mCur.close();
        return columnNames.length;
    }

    String[] getColumnName(String tableName)
    {
        String sql ="SELECT * FROM " + tableName;
        @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql, null);
        String[] columnNames = mCur.getColumnNames();
        mCur.close();
        return columnNames;
    }

    void deleteAllRowWithCondition(String tableName, String tag, String value)
    {
        String sql="DELETE FROM " + tableName + " WHERE " + tag + " = ?";
        mDb.execSQL(sql,new String[]{value});
    }

    int getIDLike(String tableName, String tag, String value)
    {
        Cursor mCur = mDb.query(true,tableName, null, tag + " LIKE ?",new String[] { value+"%" }, null, null, null,null);
        boolean st = mCur.moveToFirst();
        int val=0;
        if(st)
        {
            val = mCur.getInt(mCur.getColumnIndex("ID"));
        }
        mCur.close();
        return val;
    }

    String[] getAllTableNames()
    {
        String[] tempTa= new String[100];
        int count=0;
        Cursor c = mDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
        if (c.moveToFirst())
        {
            while ( !c.isAfterLast() )
            {
                tempTa[count]=c.getString(0);
                count++;
                c.moveToNext();
            }
        }
        c.close();
        String[] tables= new String[count];
        System.arraycopy(tempTa,0,tables,0,count);
        return tables;
    }

    void createUserTable(String tableName, String[] columnName)
    {
        StringBuilder table_info = new StringBuilder("id INTEGER PRIMARY KEY, ");
        for(int j=0;j<columnName.length;j++)
        {
            if(j==(columnName.length-1))
            {
                table_info.append(columnName[j]).append(" TEXT");
            }
            else
            {
                table_info.append(columnName[j]).append(" TEXT, ");
            }
        }
        String CREATE_TABLE_NEW_USER = "CREATE TABLE " + tableName + " (" + table_info +")";
        mDb.execSQL(CREATE_TABLE_NEW_USER);
    }

    String getValue(String returnTag, String tags, String tableName, String index)
    {
        String value=null;
        try
        {
            String sql ="SELECT * FROM " + tableName + " WHERE " + tags + "= ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[] {index});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex(returnTag));
            mCur.close();
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    String[] getDistinctValue(String tableName, String tag)
    {
        String[] value=null;
        try
        {
            Cursor cursor = mDb.query(true,tableName,new String[] {tag}, null, null, tag, null,null,null);
            value = new String[cursor.getCount()];
            int count=0;
            if(cursor.moveToFirst())
            {
                while (!cursor.isAfterLast())
                {
                    value[count]=cursor.getString(0);
                    count++;
                    cursor.moveToNext();
                }
            }
            cursor.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Utils.printErrorLog(TAG,"getValueStr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getValueStr>: " + e.getMessage());
        }
        return value;
    }

    public void deleteTable(String tableName)
    {
        mDb.execSQL("DROP TABLE IF EXISTS " + tableName);
    }

    public void addColumn(String tableName, String columnName)
    {
        mDbHelper.setSQLQueryAddColumn(tableName,columnName);
        mDbHelper.onUpgrade(mDb,1,2);
    }

    int insertBnplPayTable(String postingDt, String txnType, String payBrand, String payBrandDesc, String txnDt, String seqNo, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String status, String addInfo, String acqCode, String apprCode, String packageCode, String paymentType, String tenure, String tenureDesc, String bnplResp)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("txnType", txnType);
            Cv.put("payBrand", payBrand);
            Cv.put("payBrandDesc", payBrandDesc);
            Cv.put("txnDt", txnDt);
            Cv.put("seqNo", seqNo);
            Cv.put("mid", mid);
            Cv.put("tid", tid);
            Cv.put("txnAmt", txnAmt);
            Cv.put("refId", refId);
            Cv.put("hostRefNo", hostRefNo);
            Cv.put("txnRefNo", txnRefNo);
            Cv.put("status", status);
            Cv.put("addInfo", addInfo);
            Cv.put("acqCode", acqCode);
            Cv.put("txnApprCode", apprCode);
            Cv.put("packageCode", packageCode);
            Cv.put("paymentType", paymentType);
            Cv.put("tenure", tenure);
            Cv.put("tenureDesc", tenureDesc);
            Cv.put("bnplResp", bnplResp);

            iResp = (int)mDb.insert("bnplPayTable", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertBnplPayTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    int updateBnplPayTable(String tag, String value, String refId)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put(tag, value);
            iResp = (int)mDb.update("bnplPayTable",Cv, "refId = ?",new String[] {refId});
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertBnplPayTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    String[] getSingleBnplPayTableRecord(String refId)
    {
        Cursor mCur=null;
        String[] value=new String[getNumberColumn("bnplPayTable")];
        try
        {
            String sql ="SELECT * FROM " + "BnplPayTable" + " WHERE refId= ? ";

            mCur = mDb.rawQuery(sql,new String[]{refId});
            mCur.moveToFirst();

            for(int j=0;j<value.length;j++)
            {
                value[j]=mCur.getString(j);
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleBatchTableRecord",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleBatchTableRecord>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int deleteBnplPayTableRecord(String refId)
    {
        return mDb.delete("bnplPayTable","refId = ?",new String[] {refId});
    }

    int deleteBnplPayTableRecord()
    {
        return mDb.delete("bnplPayTable",null,null);
    }

    int getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        int iTotalCount;
        try
        {
            String sql ="SELECT count(*) FROM printReceiptBnpl WHERE payBrandDesc = ? AND txnType = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            iTotalCount=mCur.getInt(mCur.getColumnIndex("count(*)"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand>: " + e.getMessage());
            iTotalCount=0;
        }
        return iTotalCount;
    }

    String getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT SUM(txnAmt) FROM printReceiptBnpl WHERE payBrandDesc= ?  AND txnType= ?";
            mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("SUM(txnAmt)"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int insertPrintReceiptBnplTable(String postingDt, String txnType, String payBrand, String payBrandDesc, String txnDt, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String respCode, String status, String addInfo, String printInfo, String acqCode, String apprCode, String tenure, String tenureDesc, String bnplResp)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("txnType", txnType);
            Cv.put("payBrand", payBrand);
            Cv.put("payBrandDesc", payBrandDesc);
            Cv.put("txnDt", txnDt);
            Cv.put("mid", mid);
            Cv.put("tid", tid);
            Cv.put("txnAmt", txnAmt);
            Cv.put("refId", refId);
            Cv.put("hostRefNo", hostRefNo);
            Cv.put("txnRefNo", txnRefNo);
            Cv.put("respCode", respCode);
            Cv.put("status", status);
            Cv.put("addInfo", addInfo);
            Cv.put("printInfo", printInfo);
            Cv.put("acqCode", acqCode);
            Cv.put("txnApprCode", apprCode);
            Cv.put("tenure", tenure);
            Cv.put("tenureDesc", tenureDesc);
            Cv.put("bnplResp", bnplResp);

            iResp = (int)mDb.insert("printReceiptBnpl", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertPrintReceiptBnpl",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    String[] getSinglePrintReceiptBnplTableRecord(String refId)
    {
        Cursor mCur=null;
        String[] value=new String[getNumberColumn("printReceiptBnpl")];
        try
        {
            String sql ="SELECT * FROM " + "printReceiptBnpl" + " WHERE refId= ? ";

            mCur = mDb.rawQuery(sql,new String[]{refId});
            mCur.moveToFirst();
            for(int j=0;j<value.length;j++)
            {
                value[j]=mCur.getString(j);
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSinglePrintReceiptBnplTableRecord",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSinglePrintReceiptBnplTableRecord>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String[] getPrintReceiptBnplRefIdArr()
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT refId FROM printReceiptBnpl";

            mCur = mDb.rawQuery(sql,null);
            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            int count=0;
            while(next)
            {
                value[count]= mCur.getString(mCur.getColumnIndex("refId"));
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptBnplRefIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplRefIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String[] getPrintReceiptBnplIdArr()
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT id FROM printReceiptBnpl";

            mCur = mDb.rawQuery(sql,null);
            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            int count=0;
            while(next)
            {
                value[count]= mCur.getString(mCur.getColumnIndex("id"));
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptBnplIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        int iTotalCount;
        try
        {
            String sql ="SELECT count(*) FROM printReceiptBnpl WHERE payBrand = ? AND txnType = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            iTotalCount=mCur.getInt(mCur.getColumnIndex("count(*)"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand>: " + e.getMessage());
            iTotalCount=0;
        }
        return iTotalCount;
    }

    int getPrintReceiptBnplTableTotalTxnCount_byTxnType(String txnType)
    {
        int iTotalCount;
        try
        {
            String sql ="SELECT count(*) FROM printReceiptBnpl WHERE txnType = ?";
            @SuppressLint("Recycle") Cursor mCur = mDb.rawQuery(sql,new String[]{txnType});
            mCur.moveToFirst();
            iTotalCount=mCur.getInt(mCur.getColumnIndex("count(*)"));
            mCur.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();

            Utils.printErrorLog(TAG,"getPrintReceiptBnplTableTotalTxnCount_byTxnType",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplTableTotalTxnCount_byTxnType>: " + e.getMessage());
            iTotalCount=0;
        }
        return iTotalCount;
    }

    String getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT SUM(txnAmt) FROM printReceiptBnpl WHERE payBrand= ?  AND txnType= ?";
            mCur = mDb.rawQuery(sql,new String[]{payBrand, txnType});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("SUM(txnAmt)"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    String getPrintReceiptBnplTableTotalTxnAmt_byTxnType(String txnType)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT SUM(txnAmt) FROM printReceiptBnpl WHERE txnType= ?";
            mCur = mDb.rawQuery(sql,new String[]{txnType});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex("SUM(txnAmt)"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptBnplTableTotalTxnAmt_byTxnType",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplTableTotalTxnAmt_byTxnType>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    @SuppressLint("Range")
    String[] getPrintReceiptBnplInfo_byID(String stan)
    {
        Cursor mCur = null;
        String[] value = new String[getNumberColumn("printReceiptBnpl")]; // Increment when add field
        try
        {

            String sql ="SELECT * FROM printReceiptBnpl WHERE id= ? ";
            mCur = mDb.rawQuery(sql,new String[]{stan});
            mCur.moveToFirst();
            for(int j=0;j<value.length;j++)
            {
                value[j]=mCur.getString(j);
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptInfo_byStanNo",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptInfo_byStanNo>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int deletePrintReceiptBnplTableRecord()
    {
        return mDb.delete("printReceiptBnpl",null,null);
    }

    String[] getMultiplePrintReceiptBnplInfo()
    {
        Cursor mCur=null;
        String[] value;
        try
        {
            String sql ="SELECT * FROM printReceiptBnpl ORDER BY refId, txnDt";

            mCur = mDb.rawQuery(sql,null);

            boolean next = mCur.moveToFirst();
            value = new String[mCur.getCount()];
            String[] qrData = new String[getNumberColumn("printReceiptBnpl")];
            Map<String, String> value2 = new HashMap<>();
            int count=0;
            while(next)
            {
                for(int j=0;j<qrData.length;j++)
                {
                    value2.put(mCur.getColumnName(j), mCur.getString(j));
                }

                value[count] = new Gson().toJson(value2).toString();
                next=mCur.moveToNext();
                count++;
            }
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getPrintReceiptBnplInfoRefIdArr",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getPrintReceiptBnplInfoRefIdArr>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int insertRevBatchTable(String postingDt, String stan, String txnType, String invNo, String revDes, String revType, String revIsoDb, String revIsoOri, String schemeTag, String schemeId, int refId, String status, String batchNo, String mid, String tid)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("postingDt", postingDt);
            Cv.put("stan", stan);
            Cv.put("txnType", txnType);
            Cv.put("invNo", invNo);
            Cv.put("revDes", revDes);
            Cv.put("revType", revType);
            Cv.put("revIsoDb", revIsoDb);
            Cv.put("revIsoOri", revIsoOri);
            Cv.put("schemeTag", schemeTag);
            Cv.put("schemeId", schemeId);
            Cv.put("refId", String.valueOf(refId));
            Cv.put("status", status);
            Cv.put("batchNo", batchNo);
            Cv.put("mid", mid);
            Cv.put("tid", tid);

            iResp = (int)mDb.insert("revBatchTable", null, Cv);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"insertRevBatchTable",e.getMessage());
            iResp = Global.iso.err.DB_Insert;
        }

        return iResp;
    }

    int resetRevBatchTableFlag(String mid, String tid, String batchNo, String fromOldStatus, String toNewStatus)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("status", toNewStatus);

            if((fromOldStatus != null) && (fromOldStatus != ""))
                iResp = mDb.update("revBatchTable", Cv, "mid= ? AND tid = ? AND batchNo = ? AND fromOldStatus = ?", new String[]{mid, tid, batchNo, fromOldStatus});
            else
                iResp = mDb.update("revBatchTable", Cv, "mid= ? AND tid = ? AND batchNo = ?", new String[]{mid, tid, batchNo});
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"resetRevBatchTableFlag",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;
    }

    int getSingleRevBatchTableRecordId(String mid, String tid, String status)
    {
        Cursor mCur=null;
        int iId;
        try
        {
            String sql ="SELECT id FROM " + "revBatchTable" + " WHERE mid = ? AND tid = ? AND status =?";

            mCur = mDb.rawQuery(sql,new String[]{mid, tid, status});
            mCur.moveToFirst();
            iId=mCur.getInt(mCur.getColumnIndex("id"));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleRevBatchTableRecordId",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleRevBatchTableRecordId>: " + e.getMessage());
            iId=0;
        }
        return iId;
    }

    String getSingleColumnRevBatchTable(String column, int id)
    {
        Cursor mCur=null;
        String value;
        try
        {
            String sql ="SELECT "+ column +" FROM " + "revBatchTable" + " WHERE id= ? ";

            mCur = mDb.rawQuery(sql,new String[]{Integer.toString(id)});
            mCur.moveToFirst();
            value=mCur.getString(mCur.getColumnIndex(column));
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getSingleColumnRevBatchTable",e.getMessage());
            Utils.debugLogPrint(TAG, "Error <getSingleColumnRevBatchTable>: " + e.getMessage());
            value=null;
        }
        return value;
    }

    int setRevBatchTableFlag(int id, String newStatus)
    {
        int iResp = -1;
        try
        {
            ContentValues Cv = new ContentValues();
            Cv.put("status", newStatus);
            iResp = mDb.update("revBatchTable", Cv, "id = ?", new String[]{String.valueOf(id)});
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"setRevBatchTableFlag",e.getMessage());
            iResp = Global.iso.err.DB_Update;
        }

        return iResp;
    }

    int deleteAllRevBatchTableRecords()
    {
        return mDb.delete("revBatchTable",null, null);
    }

    int deleteRevBatchTableRecord_byInvNo(String mid, String tid, String batchNo, String invNo)
    {
        return mDb.delete("revBatchTable","mid = ? AND tid = ? AND batchNo = ? AND invNo = ?", new String[] {mid, tid ,batchNo, invNo});
    }

    int deleteRevBatchTableRecord_byID(int id)
    {
        return mDb.delete("revBatchTable","id = ?", new String[]{String.valueOf(id)});
    }
}
