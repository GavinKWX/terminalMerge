package com.sc.mf919pro.java.activity;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * Legacy SQLite adapter. Retained only for the SystemTrace database accessed through
 * Database.DatabaseAccess (see Utils). isoengine_gobiz.db access lives in DbHandler + repos.
 */
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

    @SuppressLint("Range")
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
}
