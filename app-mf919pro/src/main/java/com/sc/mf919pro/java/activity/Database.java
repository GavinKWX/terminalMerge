package com.sc.mf919pro.java.activity;

import android.content.Context;

/**
 * Legacy DB access layer. The isoengine_gobiz.db table wrappers that used to live here were
 * replaced by the DbHandler + repo layer (kotlin/database). What remains serves only the
 * SystemTrace database used by Utils (invoice/stan counters).
 */
public class Database
{
    public static String TAG = "Database";

    public static class DatabaseAccess {
        private DataAdapter DAdapter;

        public DatabaseAccess(Context mContext, String filename) {
            DAdapter = new DataAdapter(mContext, filename);
            DAdapter.createDatabase();
        }

        void updateSQL(String tags, String value, String tableName, int id) {
            DAdapter.open();
            DAdapter.updateFile(tags, value, tableName, id);
            DAdapter.close();
        }

        String getSQLValue(String tags, String tableName, int id) {
            DAdapter.open();
            String returnVal = DAdapter.getValueStr(tags, tableName, id);
            DAdapter.close();
            return (returnVal);
        }
    }
}
