package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.Nullable;

import com.sc.mf919.java.utils.EmvUtil;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.util.Arrays;
import java.util.List;

public class Database
{
    public static String TAG = "Database";
    public static String isoDbName = "isoengine_gobiz.db";

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

        int searchID(String tags, String tableName, String value) {
            DAdapter.open();
            int returnVal = DAdapter.getID(tags, tableName, value);
            DAdapter.close();
            return (returnVal);
        }

        int numberOfColumn(String tableName) {
            DAdapter.open();
            int returnVal = DAdapter.getNumberColumn(tableName);
            DAdapter.close();
            return (returnVal);
        }

        int numberOfData(String tableName) {
            DAdapter.open();
            int returnVal = DAdapter.getNumberRow(tableName);
            DAdapter.close();
            return (returnVal);
        }

        int numberOfData(String[] tag, String[] index, String tableName) {
            DAdapter.open();
            int returnVal = DAdapter.getNumberRow(tag, index, tableName);
            DAdapter.close();
            return (returnVal);
        }

        void deleteAllData(String tableName) {
            DAdapter.open();
            DAdapter.deleteAllRow(tableName);
            DAdapter.close();
        }

        void deleteRow(String tableName, int id) {
            DAdapter.open();
            DAdapter.deleteARow(tableName, id);
            DAdapter.close();
        }

        void insertSQLValue(ContentValues Cv, String tableName) {
            DAdapter.open();
            DAdapter.insert(Cv, tableName);
            DAdapter.close();
        }

        int getID(String tableName) {
            DAdapter.open();
            int returnVal = DAdapter.get1stID(tableName);
            DAdapter.close();
            return (returnVal);
        }

        int[] getAllID(String tableName) {
            DAdapter.open();
            int[] returnVal = DAdapter.getAllID(tableName);
            DAdapter.close();
            return (returnVal);
        }

        void deleteLastData(String tableName) {
            int[] ids = getAllID(tableName);
            if(ids.length>0)
            {
                int last = ids[ids.length - 1];
                deleteRow(tableName, last);
            }
        }

        Cursor getTable(String tableName) {
            DAdapter.open();
            Cursor mCursor = DAdapter.getData(tableName);
            DAdapter.close();
            return mCursor;
        }

        int getLastID(String tableName) {
            int[] ids = getAllID(tableName);
            return (ids[ids.length - 1]);
        }

        int[] getIDwithIndex(String tag, String tableName, String index) {
            DAdapter.open();
            int[] id = DAdapter.getIDs(tag, tableName, index);
            DAdapter.close();
            return id;
        }

        int[] getIDwithNotCond(String tag, String tableName, String index) {
            DAdapter.open();
            int[] id = DAdapter.getIDsNotCond(tag, tableName, index);
            DAdapter.close();
            return id;
        }

        int[] getIDwithIndex(String[] tag, String tableName, String[] index) {
            DAdapter.open();
            int[] id = DAdapter.getIDs(tag, tableName, index);
            DAdapter.close();
            return id;
        }

        /*Cursor searchID_1(String tags, String tableName, String value) {
            DAdapter.open();
            Cursor returnVal = DAdapter.getID_1(tags, tableName, value);
            DAdapter.close();
            return (returnVal);
        }*/

        void readTable(String tableName) {
            String fileName = tableName + ".txt";
            Utils.deleteFiles(fileName);
            if (numberOfData(tableName) > 0) {
                Cursor mCursor = getTable(tableName);
                mCursor.moveToFirst();
                boolean next = true;
                String[] ColumnName = mCursor.getColumnNames();
                while (next) {
                    for (String s : ColumnName) {
                        @SuppressLint("Range") String temp = mCursor.getString(mCursor.getColumnIndex(s));
                        temp = s + "=" + temp;
                        Utils.writeToFile(temp, fileName);
                    }
                    Utils.writeToFile("", fileName);
                    next = mCursor.moveToNext();
                }
            }
        }

        void writeTable(String tableName) {
            int noCol = numberOfColumn(tableName);
            int noRow = numberOfData(tableName);
            String[] value = Utils.readFromFile(tableName + ".txt");
            int Len = value.length / noCol;
            int count = 0;
            for (int j = 0; j < Len; j++) {
                if ((j + 1) <= noRow) {
                    Utils.debugLogPrint(TAG, noRow + " " + noCol + "  " + value[count].substring(value[count].indexOf("=") + 1));
                    int index = Integer.parseInt(value[count].substring(value[count].indexOf("=") + 1));
                    for (int i = 1; i < noCol; i++) {
                        updateSQL(value[i + count].substring(0, value[i + count].indexOf("=")), value[i + count].substring(value[i + count].indexOf("=") + 1), tableName, index);
                    }
                } else {
                    ContentValues Cvs = new ContentValues();
                    int index = Integer.parseInt(value[count].substring((value[count].indexOf("=")) + 1));
                    for (int i = 1; i < noCol; i++) {
                        Cvs.put(value[i + count].substring(0, value[i + count].indexOf("=")), value[1 + count].substring((value[1 + count].indexOf("=")) + 1));
                    }
                    insertSQLValue(Cvs, tableName);
                }
                count = count + noCol + 1;
            }
            if (Len < noRow) {
                int dif = noRow - Len;
                for (int j = 0; j < dif; j++) {
                    deleteLastData(tableName);
                }
            }
            Utils.deleteFiles(tableName + ".txt");
        }

        void deleteAllWithCondition(String tableName, String tag, String value) {
            DAdapter.open();
            DAdapter.deleteAllRowWithCondition(tableName, tag, value);
            DAdapter.close();
        }

        int searchIDLike(String tableName,String tag, String value)
        {
            DAdapter.open();
            int val = DAdapter.getIDLike(tableName,tag,value);
            DAdapter.close();
            return val;
        }

        public String[] tableNames()
        {
            DAdapter.open();
            String[] table=DAdapter.getAllTableNames();
            DAdapter.close();
            return table;
        }

        public void createTable(String tableName, String[] columeNames)
        {
            DAdapter.open();
            DAdapter.createUserTable(tableName,columeNames);
            DAdapter.close();
        }

        String getValue(String returnTag,String tags, String tableName,String index)
        {
            DAdapter.open();
            String value=DAdapter.getValue(returnTag,tags,tableName,index);
            DAdapter.close();
            return value;
        }

        String[] getDistinctValue(String tableName, String tag)
        {
            DAdapter.open();
            String[]  value = DAdapter.getDistinctValue(tableName,tag);
            DAdapter.close();
            return (value);
        }

        void deleteTable(String tableName)
        {
            DAdapter.open();
            DAdapter.deleteTable(tableName);
            DAdapter.close();
        }

        void createColumn(String tableName,String columnName)
        {
            DAdapter.open();
            String[] columns = DAdapter.getColumnName(tableName);
            if(columns.length>0)
            {
                String columnList = Arrays.toString(columns);
                if(!columnList.contains(columnName))
                {
                    Utils.debugLogPrint(TAG,columnName + " Column not found in table " + tableName);
                    DAdapter.addColumn(tableName,columnName);
                }
            }
            DAdapter.close();
        }

        String getValue(String tag, String subtag, String tableName)
        {
            DAdapter.open();
            String value=DAdapter.getValue(tag,subtag,tableName);
            DAdapter.close();
            return value;
        }

        String getIsoBatchData(String tag, String subtag)
        {
            DAdapter.open();
            String value=DAdapter.getIsoBatchData(tag,subtag);
            DAdapter.close();
            return value;
        }

        int setIsoBatchData(String tag, String subtag, String value)
        {
            DAdapter.open();
            int iResp = DAdapter.setIsoBatchData(tag, subtag, value);
            DAdapter.close();
            return iResp;
        }

        String getIsoBatchLongData(String tag, String subtag)
        {
            DAdapter.open();
            String value=DAdapter.getIsoBatchLongData(tag,subtag);
            DAdapter.close();
            return value;
        }

        int setIsoBatchLongData(String tag, String subtag, String value)
        {
            DAdapter.open();
            int iResp = DAdapter.setIsoBatchLongData(tag, subtag, value);
            DAdapter.close();
            return iResp;
        }

        int insertBatchTable(String postingDt, String stan, String txnType, String invNo, String batchData, String schemeTag, String schemeId, int refId, String status, String batchNo, String mid, String tid)
        {
            DAdapter.open();
            int iResp = DAdapter.insertBatchTable(postingDt, stan, txnType, invNo, batchData, schemeTag, schemeId, refId, status, batchNo, mid, tid);
            DAdapter.close();
            return iResp;
        }

        int resetBatchTableFlag(String mid, String tid, String batchNo, String fromOldStatus, String toNewStatus)
        {
            DAdapter.open();
            int iResp = DAdapter.resetBatchTableFlag(mid, tid, batchNo, fromOldStatus, toNewStatus);
            DAdapter.close();
            return iResp;
        }

        int setBatchTableFlag(int id, String newStatus)
        {
            DAdapter.open();
            int iResp = DAdapter.setBatchTableFlag(id, newStatus);
            DAdapter.close();
            return iResp;
        }

        int getSingleBatchTableRecordId(String status)
        {
            DAdapter.open();
            int iResp = DAdapter.getSingleBatchTableRecordId(status);
            DAdapter.close();
            return iResp;
        }

        int getSingleBatchTableRecordId_byInvNo(String invNo, String status)
        {
            DAdapter.open();
            int iResp = DAdapter.getSingleBatchTableRecordId_byInvNo(invNo, status);
            DAdapter.close();
            return iResp;
        }

        String getSingleBatchTableRecord(int id)
        {
            DAdapter.open();
            String strResp = DAdapter.getSingleBatchTableRecord(id);
            DAdapter.close();
            return strResp;
        }

        int deleteBatchTableRecord(String id)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteBatchTableRecord(id);
            DAdapter.close();
            return iResp;
        }

        int deleteBatchTableRecords(String batchNo, String schemeTag)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteBatchTableRecords(batchNo, schemeTag);
            DAdapter.close();
            return iResp;
        }

        int deleteBatchTableRecord_byInvNo(String invNo)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteBatchTableRecord_byInvNo(invNo);
            DAdapter.close();
            return iResp;
        }

        int insertPreauthTable(String postingDt, String cardData, String schemeTag, String apprCode, String rrn, String invNo, String status, String addInfo)
        {
            DAdapter.open();
            int iResp = DAdapter.insertPreauthTable(postingDt, cardData, schemeTag, apprCode, rrn, invNo, status, addInfo);
            DAdapter.close();
            return iResp;
        }

        int getSinglePreauthTableRecordId(String schemeTag, String status)
        {
            DAdapter.open();
            int iResp = DAdapter.getSinglePreauthTableRecordId(schemeTag, status);
            DAdapter.close();
            return iResp;
        }

        int getSinglePreauthTableRecordId_byInvNo(String invNo, String status)
        {
            DAdapter.open();
            int iResp = DAdapter.getSinglePreauthTableRecordId_byInvNo(invNo, status);
            DAdapter.close();
            return iResp;
        }

        String[] getSinglePreauthTableRecord(int id)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getSinglePreauthTableRecord(id);
            DAdapter.close();
            return strResp;
        }

        int deletePreauthTableRecord(String id)
        {
            DAdapter.open();
            int iResp = DAdapter.deletePreauthTableRecord(id);
            DAdapter.close();
            return iResp;
        }

        int deletePreauthTableRecords(String batchNo, String schemeTag)
        {
            DAdapter.open();
            int iResp = DAdapter.deletePreauthTableRecords(batchNo, schemeTag);
            DAdapter.close();
            return iResp;
        }

        int deletePreauthTableRecord_byInvNo(String invNo)
        {
            DAdapter.open();
            int iResp = DAdapter.deletePreauthTableRecord_byInvNo(invNo);
            DAdapter.close();
            return iResp;
        }

        int insertTxnReceiptTable(String postingDt, String[] receiptInfo)
        {
            DAdapter.open();
            int iResp = DAdapter.insertTxnReceiptTable(postingDt, receiptInfo);
            DAdapter.close();
            return iResp;
        }

        int insertPrintReceiptTable(String postingDt, String txnType, String cardMasked, String schemeId, String txnAmt, String invNo, String stan, String txnApprCode, String receiptInfo)
        {
            DAdapter.open();
            int iResp = DAdapter.insertPrintReceiptTable(postingDt, postingDt, txnType, cardMasked, schemeId, txnAmt, invNo, stan, txnApprCode, receiptInfo);
            DAdapter.close();
            return iResp;
        }

        int deletePrintReceiptTableRecords()
        {
            DAdapter.open();
            int iResp = DAdapter.deletePrintReceiptTableRecords();
            DAdapter.close();
            return iResp;
        }

        public String[] getPrintReceiptStanArr()
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptStanArr();
            DAdapter.close();
            return value;
        }

        public String getPrintReceiptInfo_byStan(String stanNo)
        {
            DAdapter.open();
            String value = DAdapter.getPrintReceiptInfo_byStan(stanNo);
            DAdapter.close();
            return value;
        }

        int dasDeleteVoidedPrintReceiptInfo(String txnAmount, String invoiceNo, String stanNo)
        {
            DAdapter.open();
            int iResp = DAdapter.sqlDeleteVoidedPrintReceiptInfo(txnAmount, invoiceNo, stanNo);
            DAdapter.close();
            return iResp;
        }

        public String[] getPrintReceiptInfo_byID(String stanNo)
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptInfo_byID(stanNo);
            DAdapter.close();
            return value;
        }

        String[] getMultiplePrintReceiptInfo(String schemeIds)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getMultiplePrintReceiptInfo(schemeIds);
            DAdapter.close();
            return strResp;
        }

        int insertQrPayTable(String postingDt, String txnType, String payBrand, String txnDt, String seqNo, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String status, String addInfo, String acqCode, String apprCode, String type, String productCode, String productName, String isUPIQRTxn, String upiVoucherCode, String upiDiscountAmt, String upiMarkupFee)
        {
            DAdapter.open();
            int iResp = DAdapter.insertQrPayTable(postingDt, txnType, payBrand, txnDt, seqNo, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, status, addInfo, acqCode, apprCode, type, productCode, productName, isUPIQRTxn, upiVoucherCode, upiDiscountAmt, upiMarkupFee);
            DAdapter.close();
            return iResp;
        }

        String[] getSingleQrPayTableRecord(String refId)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getSingleQrPayTableRecord(refId);
            DAdapter.close();
            return strResp;
        }

        String[] getMultipleQrPayTableRecord(@Nullable List<String> productNames)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getMultipleQrPayTableRecord(productNames);
            DAdapter.close();
            return strResp;
        }

        int deleteQrPayTableRecord(String refId)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteQrPayTableRecord(refId);
            DAdapter.close();
            return iResp;
        }

        int deleteQrPayTableRecord()
        {
            DAdapter.open();
            int iResp = DAdapter.deleteQrPayTableRecord();
            DAdapter.close();
            return iResp;
        }

        int getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            int iResp = DAdapter.getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return iResp;
        }

        String getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            String strResp = DAdapter.getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return strResp;
        }

        int insertPrintReceiptQrTable(String postingDt, String txnType, String payBrand, String txnDt, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String respCode, String status, String addInfo, String printInfo, String acqCode, String apprCode, String type, String productCode, String productName, String isUPIQRTxn, String upiVoucherCode, String upiDiscountAmt, String upiMarkupFee)
        {
            DAdapter.open();
            int iResp = DAdapter.insertPrintReceiptQrTable(postingDt, txnType, payBrand, txnDt, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, respCode, status, addInfo, printInfo, acqCode, apprCode, type, productCode, productName, isUPIQRTxn, upiVoucherCode, upiDiscountAmt, upiMarkupFee);
            DAdapter.close();
            return iResp;
        }

        String[] getSinglePrintReceiptQrTableRecord(String refId)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getSinglePrintReceiptQrTableRecord(refId);
            DAdapter.close();
            return strResp;
        }

        public String[] getPrintReceiptQrRefIdArr()
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptQrRefIdArr();
            DAdapter.close();
            return value;
        }

        public String[] getPrintReceiptQrIdArr()
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptQrIdArr();
            DAdapter.close();
            return value;
        }

        int getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            int iResp = DAdapter.getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return iResp;
        }

        int getPrintReceiptQrTableTotalTxnCount_byTxnType(String txnType)
        {
            DAdapter.open();
            int iResp = DAdapter.getPrintReceiptQrTableTotalTxnCount_byTxnType(txnType);
            DAdapter.close();
            return iResp;
        }

        String getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            String strResp = DAdapter.getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return strResp;
        }

        String getPrintReceiptQrTableTotalTxnAmt_byTxnType(String txnType)
        {
            DAdapter.open();
            String strResp = DAdapter.getPrintReceiptQrTableTotalTxnAmt_byTxnType(txnType);
            DAdapter.close();
            return strResp;
        }

        int deletePrintReceiptQrTableRecord()
        {
            DAdapter.open();
            int iResp = DAdapter.deletePrintReceiptQrTableRecord();
            DAdapter.close();
            return iResp;
        }

        int deletePrintReceiptQrTable_byRefId(String refId)
        {
            DAdapter.open();
            int iResp = DAdapter.deletePrintReceiptQrTable_byRefId(refId);
            DAdapter.close();
            return iResp;
        }

        //
        // Password
        //
        String getKeyData(String tag, String subtag)
        {
            DAdapter.open();
            String value=DAdapter.getKeyData(tag,subtag);
            DAdapter.close();
            return value;
        }

        int setKeyData(String tag, String subtag, String value)
        {
            DAdapter.open();
            int iResp = DAdapter.setKeyData(tag, subtag, value);
            DAdapter.close();
            return iResp;
        }

        String getSecureData(String tag, String subtag)
        {
            DAdapter.open();
            String value=DAdapter.getSecureData(tag,subtag);
            DAdapter.close();
            Utils.debugLogPrint(TAG, "getSecureData: " + value);
            return value;
        }

        int setSecureData(String tag, String subtag, String value)
        {
            DAdapter.open();
            int iResp = DAdapter.setSecureData(tag, subtag, value);
            DAdapter.close();
            return iResp;
        }

        public String getSinglePreauthTableScheme(int id)
        {
            DAdapter.open();
            String value = DAdapter.getSinglePreauthTableScheme(id);
            DAdapter.close();
            return value;
        }

        public String getSinglePreauthTableSchemeId_byInvNo(String invNo)
        {
            DAdapter.open();
            String value = DAdapter.getSinglePreauthTableSchemeId_byInvNo(invNo);
            DAdapter.close();
            return value;
        }

        public String getSingleBatchTableScheme(int id)
        {
            DAdapter.open();
            String value = DAdapter.getSingleBatchTableScheme(id);
            DAdapter.close();
            return value;
        }

        public String getSingleBatchTableSchemeId_byInvNo(String invNo)
        {
            DAdapter.open();
            String value = DAdapter.getSingleBatchTableSchemeId_byInvNo(invNo);
            DAdapter.close();
            return value;
        }

        public String getSingleBatchTableTxnType_byInvNo(String invNo)
        {
            DAdapter.open();
            String value = DAdapter.getSingleBatchTableTxnType_byInvNo(invNo);
            DAdapter.close();
            return value;
        }

        public int updateTerminalConfigurationTable(String Contact, String Contactless, String Magstripe, String QrPay, String ForcePin, String IsoPrint, String ReceiptPrint, String OptIn, String SaleComOnline, String AutoSettle, String Sale, String Void, String PreAuth, String SaleCom, String Refund, String TmsReceipt, String TmsEnable) {
            DAdapter.open();
            int iResp = DAdapter.updateTerminalConfigurationTable(Contact, Contactless, Magstripe, QrPay, ForcePin, IsoPrint, ReceiptPrint, OptIn, SaleComOnline, AutoSettle, Sale, Void, PreAuth, SaleCom, Refund, TmsReceipt, TmsEnable);
            DAdapter.close();
            return iResp;
        }

        int insertBnplPayTable(String postingDt, String txnType, String payBrand, String payBrandDesc, String txnDt, String seqNo, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String status, String addInfo, String acqCode, String apprCode, String packageCode, String paymentType, String tenure, String tenureDesc, String bnplResp)
        {
            DAdapter.open();
            int iResp = DAdapter.insertBnplPayTable(postingDt, txnType, payBrand, payBrandDesc, txnDt, seqNo, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, status, addInfo, acqCode, apprCode, packageCode, paymentType, tenure, tenureDesc, bnplResp);
            DAdapter.close();
            return iResp;
        }

        int updateBnplPayTable(String tag, String value, String refId)
        {
            DAdapter.open();
            int iResp = DAdapter.updateBnplPayTable(tag, value, refId);
            DAdapter.close();
            return iResp;
        }

        String[] getSingleBnplPayTableRecord(String refId)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getSingleBnplPayTableRecord(refId);
            DAdapter.close();
            return strResp;
        }

        int deleteBnplPayTableRecord(String refId)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteBnplPayTableRecord(refId);
            DAdapter.close();
            return iResp;
        }

        int deleteBnplPayTableRecord()
        {
            DAdapter.open();
            int iResp = DAdapter.deleteBnplPayTableRecord();
            DAdapter.close();
            return iResp;
        }

        int getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            int iResp = DAdapter.getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return iResp;
        }

        String getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            String strResp = DAdapter.getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return strResp;
        }

        int insertPrintReceiptBnplTable(String postingDt, String txnType, String payBrand, String payBrandDesc, String txnDt, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String respCode, String status, String addInfo, String printInfo, String acqCode, String apprCode, String tenure, String tenureDesc, String bnplResp)
        {
            DAdapter.open();
            int iResp = DAdapter.insertPrintReceiptBnplTable(postingDt, txnType, payBrand, payBrandDesc, txnDt, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, respCode, status, addInfo, printInfo, acqCode, apprCode, tenure, tenureDesc, bnplResp);
            DAdapter.close();
            return iResp;
        }

        String[] getSinglePrintReceiptBnplTableRecord(String refId)
        {
            DAdapter.open();
            String[] strResp = DAdapter.getSinglePrintReceiptBnplTableRecord(refId);
            DAdapter.close();
            return strResp;
        }

        public String[] getPrintReceiptBnplRefIdArr()
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptBnplRefIdArr();
            DAdapter.close();
            return value;
        }

        public String[] getPrintReceiptBnplIdArr()
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptBnplIdArr();
            DAdapter.close();
            return value;
        }

        int getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            int iResp = DAdapter.getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return iResp;
        }

        int getPrintReceiptBnplTableTotalTxnCount_byTxnType(String txnType)
        {
            DAdapter.open();
            int iResp = DAdapter.getPrintReceiptBnplTableTotalTxnCount_byTxnType(txnType);
            DAdapter.close();
            return iResp;
        }

        String getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            DAdapter.open();
            String strResp = DAdapter.getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            DAdapter.close();
            return strResp;
        }

        String getPrintReceiptBnplTableTotalTxnAmt_byTxnType(String txnType)
        {
            DAdapter.open();
            String strResp = DAdapter.getPrintReceiptBnplTableTotalTxnAmt_byTxnType(txnType);
            DAdapter.close();
            return strResp;
        }

        public String[] getPrintReceiptBnplInfo_byID(String stanNo)
        {
            DAdapter.open();
            String[] value = DAdapter.getPrintReceiptBnplInfo_byID(stanNo);
            DAdapter.close();
            return value;
        }

        int deletePrintReceiptBnplTableRecord()
        {
            DAdapter.open();
            int iResp = DAdapter.deletePrintReceiptBnplTableRecord();
            DAdapter.close();
            return iResp;
        }

        String[] getMultiplePrintReceiptBnplInfo()
        {
            DAdapter.open();
            String[] strResp = DAdapter.getMultiplePrintReceiptBnplInfo();
            DAdapter.close();
            return strResp;
        }

        int insertRevBatchTable(String postingDt, String stan, String txnType, String invNo, String revDes, String revType, String revIsoDb, String revIsoOri, String schemeTag, String schemeId, int refId, String status, String batchNo, String mid, String tid)
        {
            DAdapter.open();
            int iResp = DAdapter.insertRevBatchTable(postingDt, stan, txnType, invNo, revDes, revType, revIsoDb, revIsoOri, schemeTag, schemeId, refId, status, batchNo, mid, tid);
            DAdapter.close();
            return iResp;
        }

        int resetRevBatchTableFlag(String mid, String tid, String batchNo, String fromOldStatus, String toNewStatus)
        {
            DAdapter.open();
            int iResp = DAdapter.resetRevBatchTableFlag(mid, tid, batchNo, fromOldStatus, toNewStatus);
            DAdapter.close();
            return iResp;
        }

        int getSingleRevBatchTableRecordId(String mid, String tid, String status)
        {
            DAdapter.open();
            int iResp = DAdapter.getSingleRevBatchTableRecordId(mid, tid, status);
            DAdapter.close();
            return iResp;
        }

        String getSingleColumnRevBatchTable(String column, int id)
        {
            DAdapter.open();
            String strResp = DAdapter.getSingleColumnRevBatchTable(column, id);
            DAdapter.close();
            return strResp;
        }

        int setRevBatchTableFlag(int id, String newStatus)
        {
            DAdapter.open();
            int iResp = DAdapter.setRevBatchTableFlag(id, newStatus);
            DAdapter.close();
            return iResp;
        }

        int deleteAllRevBatchTableRecords()
        {
            DAdapter.open();
            int iResp = DAdapter.deleteAllRevBatchTableRecords();
            DAdapter.close();
            return iResp;
        }

        int deleteRevBatchTableRecord_byInvNo(String mid, String tid, String batchNo, String invNo)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteRevBatchTableRecord_byInvNo(mid, tid, batchNo, invNo);
            DAdapter.close();
            return iResp;
        }

        int deleteRevBatchTableRecord_byID(int id)
        {
            DAdapter.open();
            int iResp = DAdapter.deleteRevBatchTableRecord_byID(id);
            DAdapter.close();
            return iResp;
        }

    }

    //
    // IsoEngine
    //
    public static class IsoBatchInfoTable
    {
        private DatabaseAccess DAs;

        IsoBatchInfoTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        String getIsoBatchInfo(String tag, String subtag)
        {
            String data = null;
            try
            {
                data = DAs.getIsoBatchData(tag, subtag);
            }
            catch (Exception e)
            {
                //data=null;
            }
            return data;
        }

        int setIsoBatchInfo(String tag, String subtag, String value)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setIsoBatchData(tag, subtag, value);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class SecureDataTable
    {
        private DatabaseAccess DAs;

        SecureDataTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int setSecureData(String tag, String subtag, String value)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setSecureData(tag, subtag, value);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String getSecureData(String tag, String subtag)
        {
            String data = null;
            try
            {
                data = DAs.getSecureData(tag, subtag);
                Utils.debugLogPrint(TAG, "getSecureData: " + data);
            }
            catch(Exception e)
            {
                //data=null;
            }
            return data;
        }
    }
    public static class IsoBatchLongInfoTable
    {
        private DatabaseAccess DAs;

        IsoBatchLongInfoTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        String getIsoBatchLongInfo(String tag, String subtag)
        {
            String data = null;
            try
            {
                data = DAs.getIsoBatchLongData(tag, subtag);
            }
            catch (Exception e)
            {
                //data=null;
            }
            return data;
        }

        int setIsoBatchLongInfo(String tag, String subtag, String value)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setIsoBatchLongData(tag, subtag, value);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class BatchTable
    {
        private DatabaseAccess DAs;

        BatchTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertBatchTable(String stan, String txnType, String invNo, String batchData, String schemeTag, String schemeId, int refId, String status, String batchNo, String mid, String tid)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertBatchTable(postingDt, stan, txnType, invNo, batchData, schemeTag, schemeId, refId, status, batchNo, mid, tid);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int resetBatchTableFlag(String mid, String tid, String batchNo, String fromOldStatus, String toNewStatus)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.resetBatchTableFlag(mid, tid, batchNo, fromOldStatus, toNewStatus);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int setBatchTableFlag(int id, String newStatus)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setBatchTableFlag(id, newStatus);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int getSingleBatchTableRecordId(String status)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.getSingleBatchTableRecordId(status);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int getSingleBatchTableRecordId_byInvNo(String invNo, String status)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.getSingleBatchTableRecordId_byInvNo(invNo, status);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String getSingleBatchTableRecord(int id)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSingleBatchTableRecord(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String getSingleBatchTableScheme(int id)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSingleBatchTableScheme(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String getSingleBatchTableSchemeId_byInvNo(String invNo)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSingleBatchTableSchemeId_byInvNo(invNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String getSingleBatchTableTxnType_byInvNo(String invNo)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSingleBatchTableTxnType_byInvNo(invNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int deleteBatchTableById(String id)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBatchTableRecord(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteBatchTableRecords(String batchNo, String schemeTag)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBatchTableRecords(batchNo, schemeTag);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteBatchTableRecord_byInvNo(String invNo)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBatchTableRecord_byInvNo(invNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class RevBatchTable
    {
        private DatabaseAccess DAs;

        RevBatchTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertRevBatchTable(String stan, String txnType, String invNo, String revDes, String revType, String revIsoDb, String revIsoOri, String schemeTag, String schemeId, int refId, String status, String batchNo, String mid, String tid)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertRevBatchTable(postingDt, stan, txnType, invNo, revDes, revType, revIsoDb, revIsoOri, schemeTag, schemeId, refId, status, batchNo, mid, tid);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int resetRevBatchTableFlag(String mid, String tid, String batchNo, String fromOldStatus, String toNewStatus)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.resetRevBatchTableFlag(mid, tid, batchNo, fromOldStatus, toNewStatus);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int setRevBatchTableFlag(int id, String newStatus)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setRevBatchTableFlag(id, newStatus);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int getSingleRevBatchTableRecordId(String mid, String tid, String status)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.getSingleRevBatchTableRecordId(mid, tid, status);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String getSingleColumnRevBatchTable(String column, int id)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSingleColumnRevBatchTable(column, id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int deleteAllRevBatchTableRecords()
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteAllRevBatchTableRecords();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteRevBatchTableRecord_byInvNo(String mid, String tid, String batchNo, String invNo)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteRevBatchTableRecord_byInvNo(mid, tid, batchNo, invNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteRevBatchTableRecord_byID(int id)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteRevBatchTableRecord_byID(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class PreauthTable
    {
        private DatabaseAccess DAs;

        PreauthTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertPreauthTable(String cardData, String schemeTag, String apprCode, String rrn, String invNo, String status, String addInfo)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertPreauthTable(postingDt, cardData, schemeTag, apprCode, rrn, invNo, status, addInfo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int getSinglePreauthTableRecordId_byInvNo(String invNo, String status)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.getSinglePreauthTableRecordId_byInvNo(invNo, status);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getSinglePreauthTableRecord(int id)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getSinglePreauthTableRecord(id);
                return strResp;
            }
            catch (Exception e)
            {
                //iResp=;
                return null;
            }
            //return strResp;
        }

        String getSinglePreauthTableScheme(int id)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSinglePreauthTableScheme(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String getSinglePreauthTableSchemeId_byInvNo(String invNo)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSinglePreauthTableSchemeId_byInvNo(invNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int deletePreauthTableById(String id)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deletePreauthTableRecord(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deletePreauthTableRecords(String batchNo, String schemeTag)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBatchTableRecords(batchNo, schemeTag);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deletePreauthTableRecord_byInvNo(String invNo)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deletePreauthTableRecord_byInvNo(invNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        /*int resetBatchTableFlag(String schemeTag, String batchNo, String fromOldStatus, String toNewStatus)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.resetBatchTableFlag(schemeTag, batchNo, fromOldStatus, toNewStatus);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int setBatchTableFlag(int id, String newStatus)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setBatchTableFlag(id, newStatus);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String getSingleBatchTableRecord(String schemeTag, String status)
        {
            String strResp = "";
            try
            {
                strResp = DAs.getSingleBatchTableRecord(schemeTag, status);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int deleteBatchTableById(String id)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBatchTableRecord(id);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteBatchTableRecords(String batchNo, String schemeTag)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBatchTableRecords(batchNo, schemeTag);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }*/

    }

    public static class TxnReceiptTable
    {
        private DatabaseAccess DAs;

        TxnReceiptTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertTxnReceiptTable(String[] receiptInfo)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertTxnReceiptTable(postingDt, receiptInfo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class PrintReceiptTable
    {
        private DatabaseAccess DAs;

        PrintReceiptTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertPrintReceiptTable(String txnType, String cardMasked, String schemeId, String txnAmt, String invoiceNo, String stan, String txnApprCode, String receiptInfo)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertPrintReceiptTable(postingDt, txnType, cardMasked, schemeId, txnAmt, invoiceNo, stan, txnApprCode, receiptInfo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deletePrintReceiptTableRecords()
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deletePrintReceiptTableRecords();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getPrintReceiptStanArr()
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptStanArr();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String getPrintReceiptInfo_byStan(String stanNo)
        {
            String strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptInfo_byStan(stanNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int dbDeleteVoidedPrintReceiptInfo(String txnAmount, String invoiceNo, String stanNo)
        {
            int iResp = -1;
            try {
                iResp = DAs.dasDeleteVoidedPrintReceiptInfo(txnAmount, invoiceNo, stanNo);
            }
            catch (Exception e) {
                //iResp=;
            }
            return iResp;
        }

        String[] getPrintReceiptInfo_byID(String stanNo)
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptInfo_byID(stanNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String[] getMultiplePrintReceiptInfo(String schemeIds)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getMultiplePrintReceiptInfo(schemeIds);
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

    }

    public static class TerminalConfigurationTable
    {
        private DatabaseAccess DAs;

        TerminalConfigurationTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        //
        // TerminalConfig
        //
        int updateTerminalConfigurationTable(String Contact, String Contactless, String Magstripe, String QrPay, String ForcePin, String IsoPrint, String ReceiptPrint, String OptIn, String SaleComOnline, String AutoSettle, String Sale, String Void, String PreAuth, String SaleCom, String Refund, String TmsReceipt, String TmsEnable)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.updateTerminalConfigurationTable(Contact, Contactless, Magstripe, QrPay, ForcePin, IsoPrint, ReceiptPrint, OptIn, SaleComOnline, AutoSettle, Sale, Void, PreAuth, SaleCom, Refund, TmsReceipt, TmsEnable);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class QrPayTable
    {
        private DatabaseAccess DAs;

        QrPayTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertQrPayTable(String txnType, String payBrand, String txnDt, String seqNo, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String status, String addInfo, String acqCode, String apprCode, String type, String productCode, String productName, String isUPIQRTxn, String upiVoucherCode, String upiDiscountAmt, String upiMarkupFee)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertQrPayTable(postingDt, txnType, payBrand, txnDt, seqNo, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, status, addInfo, acqCode, apprCode, type, productCode, productName, isUPIQRTxn, upiVoucherCode, upiDiscountAmt, upiMarkupFee);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getSingleQrPayTableRecord(String refId)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getSingleQrPayTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

        String[] getMultipleQrPayTableRecord(@Nullable List<String> productNames)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getMultipleQrPayTableRecord(productNames);
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

        int deleteQrPayTable_byInvNo(String refId)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteQrPayTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteQrPayTable()
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteQrPayTableRecord();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            int iResp;
            try
            {
                iResp = DAs.getQrPayTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                iResp=-1;
            }
            return iResp;
        }

        String getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            String strResp;
            try
            {
                strResp = DAs.getQrPayTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                strResp=null;
            }
            return strResp;
        }
    }

    public static class PrintReceiptQrTable
    {
        private DatabaseAccess DAs;

        PrintReceiptQrTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertPrintReceiptQrTable(String txnType, String payBrand, String txnDt, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String respCode, String status, String addInfo, String printInfo, String acqCode, String apprCode, String type, String productCode, String productName, String isUPIQRTxn, String upiVoucherCode, String upiDiscountAmt, String upiMarkupFee)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertPrintReceiptQrTable(postingDt, txnType, payBrand, txnDt, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, respCode, status, addInfo, printInfo, acqCode, apprCode, type, productCode, productName, isUPIQRTxn, upiVoucherCode, upiDiscountAmt, upiMarkupFee);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getSinglePrintReceiptQrTableRecord(String refId)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getSinglePrintReceiptQrTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

        int deletePrintReceiptQrTable_byRefId(String refId)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deletePrintReceiptQrTable_byRefId(refId);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deletePrintReceiptQrTable()
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deletePrintReceiptQrTableRecord();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getPrintReceiptQrRefIdArr()
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptQrRefIdArr();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String[] getPrintReceiptQrIdArr()
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptQrIdArr();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            int iResp;
            try
            {
                iResp = DAs.getPrintReceiptQrTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                iResp=-1;
            }
            return iResp;
        }

        int getPrintReceiptQrTableTotalTxnCount_byTxnType(String txnType)
        {
            int iResp;
            try
            {
                iResp = DAs.getPrintReceiptQrTableTotalTxnCount_byTxnType(txnType);
            }
            catch (Exception e)
            {
                iResp=-1;
            }
            return iResp;
        }

        String getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            String strResp;
            try
            {
                strResp = DAs.getPrintReceiptQrTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                strResp=null;
            }
            return strResp;
        }

        String getPrintReceiptQrTableTotalTxnAmt_byTxnType(String txnType)
        {
            String strResp;
            try
            {
                strResp = DAs.getPrintReceiptQrTableTotalTxnAmt_byTxnType(txnType);
            }
            catch (Exception e)
            {
                strResp=null;
            }
            return strResp;
        }
    }

    //
    // Password
    //
    public static class KeyInfoTable
    {
        private DatabaseAccess DAs;

        KeyInfoTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), "password.db");
        }

        String getKeyInfo(String tag, String subtag)
        {
            String data = null;
            try
            {
                data = DAs.getKeyData(tag, subtag);
            }
            catch (Exception e)
            {
                //data=null;
            }
            return data;
        }

        int setKeyInfo(String tag, String subtag, String value)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.setKeyData(tag, subtag, value);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

    }

    public static class BnplPayTable
    {
        private DatabaseAccess DAs;

        BnplPayTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertBnplPayTable(String txnType, String payBrand, String payBrandDesc, String txnDt, String seqNo, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String status, String addInfo, String acqCode, String apprCode, String packageCode, String paymentType, String tenure, String tenureDesc, String bnplResp)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertBnplPayTable(postingDt, txnType, payBrand, payBrandDesc, txnDt, seqNo, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, status, addInfo, acqCode, apprCode, packageCode, paymentType, tenure, tenureDesc, bnplResp);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int updateBnplPayTable(String tag, String value, String refId)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.updateBnplPayTable(tag, value, refId);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getSingleBnplPayTableRecord(String refId)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getSingleBnplPayTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

        int deleteBnplPayTable_byInvNo(String refId)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBnplPayTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deleteBnplPayTable()
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBnplPayTableRecord();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }
    }

    public static class PrintReceiptBnplTable
    {
        private DatabaseAccess DAs;

        PrintReceiptBnplTable()
        {
            DAs = new DatabaseAccess(ServiceHolder.Companion.getContext(), isoDbName);
        }

        int insertPrintReceiptBnplTable(String txnType, String payBrand, String payBrandDesc, String txnDt, String mid, String tid, String txnAmt, String refId, String hostRefNo, String txnRefNo, String respCode, String status, String addInfo, String printInfo, String acqCode, String apprCode, String tenure, String tenureDesc, String bnplResp)
        {
            int iResp = -1;
            try
            {
                String postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss");
                iResp = DAs.insertPrintReceiptBnplTable(postingDt, txnType, payBrand, payBrandDesc, txnDt, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, respCode, status, addInfo, printInfo, acqCode, apprCode, tenure, tenureDesc, bnplResp);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getSinglePrintReceiptBnplTableRecord(String refId)
        {
            String[] strResp;
            try
            {
                strResp = DAs.getSinglePrintReceiptBnplTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

        int deletePrintReceiptBnplTable_byRefId(String refId)
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deleteBnplPayTableRecord(refId);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        int deletePrintReceiptBnplTable()
        {
            int iResp = -1;
            try
            {
                iResp = DAs.deletePrintReceiptBnplTableRecord();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return iResp;
        }

        String[] getPrintReceiptBnplRefIdArr()
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptBnplRefIdArr();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        String[] getPrintReceiptBnplIdArr()
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptBnplIdArr();
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            int iResp;
            try
            {
                iResp = DAs.getPrintReceiptBnplTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                iResp=-1;
            }
            return iResp;
        }

        int getPrintReceiptBnplTableTotalTxnCount_byTxnType(String txnType)
        {
            int iResp;
            try
            {
                iResp = DAs.getPrintReceiptBnplTableTotalTxnCount_byTxnType(txnType);
            }
            catch (Exception e)
            {
                iResp=-1;
            }
            return iResp;
        }

        String getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            String strResp;
            try
            {
                strResp = DAs.getPrintReceiptBnplTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                strResp=null;
            }
            return strResp;
        }

        String getPrintReceiptBnplTableTotalTxnAmt_byTxnType(String txnType)
        {
            String strResp;
            try
            {
                strResp = DAs.getPrintReceiptBnplTableTotalTxnAmt_byTxnType(txnType);
            }
            catch (Exception e)
            {
                strResp=null;
            }
            return strResp;
        }

        String[] getPrintReceiptBnplInfo_byID(String stanNo)
        {
            String[] strResp = null;
            try
            {
                strResp = DAs.getPrintReceiptBnplInfo_byID(stanNo);
            }
            catch (Exception e)
            {
                //iResp=;
            }
            return strResp;
        }

        int getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            int iResp;
            try
            {
                iResp = DAs.getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                iResp=-1;
            }
            return iResp;
        }

        String getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand(String payBrand, String txnType)
        {
            String strResp;
            try
            {
                strResp = DAs.getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand(payBrand, txnType);
            }
            catch (Exception e)
            {
                strResp=null;
            }
            return strResp;
        }

        String[] getMultiplePrintReceiptBnplInfo()
        {
            String[] strResp;
            try
            {
                strResp = DAs.getMultiplePrintReceiptBnplInfo();
            }
            catch (Exception e)
            {
                //iResp=;
                strResp=null;
            }
            return strResp;
        }

    }
}
