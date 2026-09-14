package database;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DatabaseOpenHelper extends SQLiteOpenHelper
{
    private final String TAG = "DataBaseHelper";
    private static String DB_PATH = "";
    private static String DB_NAME ="";// Database name
    private SQLiteDatabase mDataBase;
    private final Context mContext;
    private String sqlQuery=null;

    @SuppressLint("SdCardPath")
    public DatabaseOpenHelper(Context context, String filename)
    {
        super(context, filename, null, 1);// 1? Its database Version
        DB_PATH = context.getApplicationInfo().dataDir + "/databases/";
        DB_NAME=filename;
        this.mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db)
    {
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
    {
        if(oldVersion<newVersion)
        {
            if(sqlQuery!=null)
            {
                timber.log.Timber.tag(TAG).d("Create Column");
                try
                {
                    db.execSQL(sqlQuery);
                }
                catch (Exception e)
                {
                    timber.log.Timber.tag(TAG).d("Error : " + e.getMessage());
                    timber.log.Timber.tag(TAG).e("onUpgrade" + " ---> Error:" + e.getMessage());
                }
            }
        }
    }

    //Check that the database exists here: /data/data/your package/databases/Da Name
    private boolean checkDataBase()
    {
        File dbFile = new File(DB_PATH + DB_NAME);
        return dbFile.exists();
    }

    //Copy the database from assets
    private void copyDataBase() throws IOException
    {
        InputStream mInput = mContext.getAssets().open(DB_NAME);
        String outFileName = DB_PATH + DB_NAME;
        OutputStream mOutput = new FileOutputStream(outFileName);
        byte[] mBuffer = new byte[1024];
        int mLength;
        while ((mLength = mInput.read(mBuffer))>0)
        {
            mOutput.write(mBuffer, 0, mLength);
        }
        mOutput.flush();
        mOutput.close();
        mInput.close();
    }

    //Open the database, so we can query it
    public void openDataBase()
    {
        String mPath = DB_PATH + DB_NAME;
        mDataBase = SQLiteDatabase.openDatabase(mPath, null, SQLiteDatabase.CREATE_IF_NECESSARY);
    }

    @Override
    public synchronized void close()
    {
        if(mDataBase != null)
            mDataBase.close();
        super.close();
    }

    public void createDataBase()
    {
        boolean mDataBaseExist = checkDataBase();
        if(!mDataBaseExist)
        {
            this.getReadableDatabase();
            this.close();
            try
            {
                File file = new File(DB_PATH);
                file.mkdirs();
                copyDataBase();
                String TAG = "DataBaseHelper";
            }
            catch (IOException mIOException)
            {
                timber.log.Timber.tag(TAG).e("createDataBase" + " ---> Error:" + mIOException.getMessage());
            }
        }
    }

    public void createDataBase(byte[] database)
    {
        if(deleteDatabase())
        {
            this.getReadableDatabase();
            this.close();
            try
            {
                String outFileName = DB_PATH + DB_NAME;
                InputStream mInput = new ByteArrayInputStream(database);
                OutputStream mOutput = null;
                mOutput = new FileOutputStream(outFileName);
                byte[] mBuffer = new byte[1024];
                int mLength;
                while ((mLength = mInput.read(mBuffer))>0)
                {
                    mOutput.write(mBuffer, 0, mLength);
                }
                mOutput.flush();
                mOutput.close();
                mInput.close();
                FileOutputStream fos;
                String TAG = "DataBaseHelper";
                timber.log.Timber.tag(TAG).d("createDatabase database created");
            }
            catch (IOException mIOException)
            {
                timber.log.Timber.tag(TAG).e("createDataBase" + " ---> Error:" + mIOException.getMessage());
            }
        }
    }

    private boolean deleteDatabase()
    {
        File dbFile = new File(DB_PATH + DB_NAME);
        return dbFile.delete();
    }

    SQLiteDatabase read()
    {
        String mPath = DB_PATH + DB_NAME;
        return SQLiteDatabase.openOrCreateDatabase(mPath,null);
    }

    public void setSQLQueryAddColumn(String tableName, String ColumnName)
    {
        sqlQuery = "ALTER TABLE " + tableName + " ADD COLUMN " + ColumnName + " Text";
    }
}