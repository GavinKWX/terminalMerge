package com.sc.mf919.java.activity;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.sc.mf919.R;
import com.sc.mf919.kotlin.activity.AppServices;
import com.sc.mf919.kotlin.activity.CardPaymentActivity;
import com.sc.mf919.kotlin.activity.EppAcquirerActivity;
import com.sc.mf919.kotlin.activity.GenerateQrActivity;
import com.sc.mf919.kotlin.activity.KeypadActivitySaleCom;
import com.sc.mf919.kotlin.activity.MainActivity;
import com.sc.mf919.kotlin.activity.MotoSaleActivity;
import com.sc.mf919.kotlin.activity.QrScanActivity;
import com.sc.mf919.kotlin.activity.SettlementActivity;
import com.sc.mf919.kotlin.activity.SettlementQrActivity;
import com.sc.mf919.kotlin.activity.VoidOffSaleActivity;
import com.sc.mf919.kotlin.activity.VoidPreauthActivity;
import com.sc.mf919.kotlin.activity.VoidQrActivity;
import com.sc.mf919.kotlin.activity.VoidSaleActivity;
import data_enum.CardErrorDataEnum;
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum;
import com.sc.mf919.kotlin.data_enum.SaleModelNew;
import com.sc.mf919.kotlin.database.model.DbModelProductList;
import com.sc.mf919.kotlin.database.model.DbModelProductListGet;
import com.sc.mf919.kotlin.database.model.DbModelReceiptUpload;
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919.kotlin.database.model.DbModelTransactionQrGet;
import com.sc.mf919.kotlin.database.repo.ProductListRepo;
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo;
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;
import data_enum.SalesModel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tms.models.EppDetail;

public class TransactionReceiver extends AppCompatActivity {
    HashMap<String, String> txn_map;

    private DbModelProductList getSpecificProduct(String name) {
        List<DbModelProductList> merchantProductList = ServiceHolder.Companion.getMerchantProductList();
        for (Object item : Objects.requireNonNull(merchantProductList)) {
            Gson gson = new Gson();
            String jsonString = gson.toJson(item);
            DbModelProductList convertedObject = gson.fromJson(jsonString, DbModelProductList.class);
            if (convertedObject.getProduct().toString().equals(name)) {
                return convertedObject;
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_txn_receiver);
        txn_map = (HashMap<String, String>) getIntent().getSerializableExtra("txn_map");
        Utils.debugLogPrint("TransactionReceiver", txn_map.toString());

        isMyServiceRunning();
        CountDownLatch migrationLatch = new CountDownLatch(1);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        AlertDialog dialog = null;
        builder.setCancelable(false);
        builder.setView(R.layout.activity_layoutloadingdialog);

        dialog = builder.create();
        dialog.show();
        
        AlertDialog finalDialog = dialog;
        new Thread(() -> {
            try {
                MainActivity.Companion.runMigrationFunction(migrationLatch);
                migrationLatch.await(30, TimeUnit.SECONDS);
                Thread.sleep(500); // simulate slight pause before UI update

                runOnUiThread(finalDialog::dismiss);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                if (txn_map != null) {
                    ServiceHolder.Companion.setAppIntent(true);
                    ServiceHolder.Companion.setPackageName(txn_map.get("Package_Name")); //returnAppPackage
                    ServiceHolder.Companion.setActivityName(txn_map.get("Activity_Name")); //returnActivity
                    ServiceHolder.Companion.setTxnType(Integer.parseInt(Objects.requireNonNull(txn_map.get("TransactionType"))));

                    if(ServiceHolder.Companion.getAutoSettlementIsRunning()) {
                        txn_map.put("ResponseCode", "SHC002");
                        txn_map.put("ResponseDescription", "Auto Settlement is running");
                        onBackToApp();
                        return;
                    }

                    String txnAmount = null;
                    if (txn_map.containsKey("TransactionAmount")) {
                        try {
                            txnAmount = txn_map.get("TransactionAmount");
                            Double.parseDouble(txnAmount);
                            BigDecimal ss = new BigDecimal(txnAmount).setScale(2, RoundingMode.HALF_UP);
                            BigDecimal ss1 = new BigDecimal("999999.99");
                            txnAmount = ss.toString();
                            if(ss.compareTo(ss1) > 0) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Trade amount should be less than 999999.99", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                return;
                            } else if (txnAmount.equals("0.00")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Trade amount should be greater than 0", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                return;
                            }
                        }catch (Exception e){
                            runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show(); }
                            });
                            txn_map.put("ResponseCode", "SHC001");
                            txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                            onBackToApp();
                            return;
                        }
                    }

                    String posReferenceNo = null;
                    if (txn_map.containsKey("PosReference")) {
                        posReferenceNo = txn_map.get("PosReference");
                    }

                    if (txn_map.containsKey("AcknowledgeCountdown")) {
                        try{
                            int countDownSecond = Integer.parseInt(txn_map.get("AcknowledgeCountdown"));
                            ServiceHolder.Companion.setAckCountDownSecond(countDownSecond);
                        }catch (Exception ex){
                            runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (AcknowledgeCountdown)", Toast.LENGTH_SHORT).show(); }
                            });
                            txn_map.put("ResponseCode", "SHC001");
                            txn_map.put("ResponseDescription", "Invalid Parameter - (AcknowledgeCountdown)");
                            onBackToApp();
                            return;
                        }
                    }

                    switch (ServiceHolder.Companion.getTxnType()) {
                        case 1: //sale
                        {
                            DbModelTerminalConfig terminalConfig = ServiceHolder.getTerminalConfig();
                            if(!DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "SALES_CARD")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC010");
                                txn_map.put("ResponseDescription", "Transaction Not Supported");
                                onBackToApp();
                                break;
                            }

                            if(DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "FORCE_SETTLEMENT") ||
                                    DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "FORCE_SETTLEMENT_DAILY")){
                                if (ServiceHolder.Companion.getClearSettlementBatch()) {
                                    runOnUiThread(new Runnable() {
                                        public void run() { Toast.makeText(getApplicationContext(), "Please Run Settlement for Last day Transaction before Proceed", Toast.LENGTH_SHORT).show(); }
                                    });
                                    txn_map.put("ResponseCode", "SHC011");
                                    txn_map.put("ResponseDescription", "Please Run Settlement for Last day Transaction before Proceed");
                                    onBackToApp();
                                    break;
                                }
                            }

                            if(!txn_map.containsKey("TransactionAmount")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (AcknowledgeCountdown)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            try {
                                String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                //DbModelProductList productModel = getSpecificProduct(productCat);
                                DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                if (productModel == null) {
                                    throw new Exception();
                                }
                                SalesModel salesModel = new SalesModel(
                                        ProductCatSelectionDataEnum.CARD_SETTINGS.getData().getSalesType(),
                                        productModel.getProduct(),
                                        productModel.getAcqCode(),
                                        productModel.getAcqMid(),
                                        productModel.getAcqTid(),
                                        productModel.getQrProductCode(),
                                        productModel.getProductName(),
                                        productModel.getEppProductCode(),
                                        productModel.getEppTenure(),
                                        productModel.getEppTenureCode()
                                );
                                ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                Gson gson = new Gson();
                                String jsonProductList = gson.toJson(productModel);
                                SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                saleModelNew.setSalesType(ProductCatSelectionDataEnum.CARD_SETTINGS.getData().getSalesType());
                                ServiceHolder.Companion.setSaleModelCache(saleModelNew);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, CardPaymentActivity.class);
                            intent.putExtra("txnAmt", txnAmount);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 2: //void sale
                        {
                            if(!txn_map.containsKey("TransactionInvoice")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            String txnInvoice = txn_map.get("TransactionInvoice");
                            try {
                                Integer.parseInt(txnInvoice);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            try {
                                String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                //DbModelProductList productModel = getSpecificProduct(productCat);
                                DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                if (productModel == null) {
                                    throw new Exception();
                                }
                                SalesModel salesModel = new SalesModel(
                                        ProductCatSelectionDataEnum.CARD_SETTINGS.getData().getSalesType(),
                                        productModel.getProduct(),
                                        productModel.getAcqCode(),
                                        productModel.getAcqMid(),
                                        productModel.getAcqTid(),
                                        productModel.getQrProductCode(),
                                        productModel.getProductName(),
                                        productModel.getEppProductCode(),
                                        productModel.getEppTenure(),
                                        productModel.getEppTenureCode()
                                );
                                ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                Gson gson = new Gson();
                                String jsonProductList = gson.toJson(productModel);
                                SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                saleModelNew.setSalesType(ProductCatSelectionDataEnum.CARD_SETTINGS.getData().getSalesType());
                                ServiceHolder.Companion.setSaleModelCache(saleModelNew);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, VoidSaleActivity.class);
                            intent.putExtra("Invoice", txnInvoice);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 3: //settlement
                        {
                            try {
                                String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                //DbModelProductList productModel = getSpecificProduct(productCat);
                                DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                if (productModel == null) {
                                    throw new Exception();
                                }
                                SalesModel salesModel = new SalesModel(
                                        ProductCatSelectionDataEnum.CARD_SETTINGS.getData().getSalesType(),
                                        productModel.getProduct(),
                                        productModel.getAcqCode(),
                                        productModel.getAcqMid(),
                                        productModel.getAcqTid(),
                                        productModel.getQrProductCode(),
                                        productModel.getProductName(),
                                        productModel.getEppProductCode(),
                                        productModel.getEppTenure(),
                                        productModel.getEppTenureCode()
                                );
                                ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                Gson gson = new Gson();
                                String jsonProductList = gson.toJson(productModel);
                                SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                saleModelNew.setSalesType(ProductCatSelectionDataEnum.CARD_SETTINGS.getData().getSalesType());
                                ServiceHolder.Companion.setSaleModelCache(saleModelNew);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, SettlementActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 4: //Preauth
                        {
                            DbModelTerminalConfig terminalConfig = ServiceHolder.getTerminalConfig();
                            if(!DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "PreAuth")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC010");
                                txn_map.put("ResponseDescription", "Transaction Not Supported");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionAmount")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            try {
                                String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                //DbModelProductList productModel = getSpecificProduct(productCat);
                                DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                if (productModel == null) {
                                    throw new Exception();
                                }
                                SalesModel salesModel = new SalesModel(
                                        8,
                                        productModel.getProduct(),
                                        productModel.getAcqCode(),
                                        productModel.getAcqMid(),
                                        productModel.getAcqTid(),
                                        productModel.getQrProductCode(),
                                        productModel.getProductName(),
                                        productModel.getEppProductCode(),
                                        productModel.getEppTenure(),
                                        productModel.getEppTenureCode()
                                );
                                ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                Gson gson = new Gson();
                                String jsonProductList = gson.toJson(productModel);
                                SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                saleModelNew.setSalesType(8);
                                ServiceHolder.Companion.setSaleModelCache(saleModelNew);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, CardPaymentActivity.class);
                            intent.putExtra("txnAmt", txnAmount);
                            intent.putExtra("typeofSale", 8);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 5: //SaleComp
                        {
                            DbModelTerminalConfig terminalConfig = ServiceHolder.getTerminalConfig();
                            if(!DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "SaleComOnline")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC010");
                                txn_map.put("ResponseDescription", "Transaction Not Supported");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionAmount")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionApprovalCode")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionApprovalCode)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionRRN")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionRRN)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionRRN)");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionInvoice")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            try {
                                String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                //DbModelProductList productModel = getSpecificProduct(productCat);
                                DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                if (productModel == null) {
                                    throw new Exception();
                                }
                                SalesModel salesModel = new SalesModel(
                                        4,
                                        productModel.getProduct(),
                                        productModel.getAcqCode(),
                                        productModel.getAcqMid(),
                                        productModel.getAcqTid(),
                                        productModel.getQrProductCode(),
                                        productModel.getProductName(),
                                        productModel.getEppProductCode(),
                                        productModel.getEppTenure(),
                                        productModel.getEppTenureCode()
                                );
                                ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                Gson gson = new Gson();
                                String jsonProductList = gson.toJson(productModel);
                                SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                saleModelNew.setSalesType(4);
                                ServiceHolder.Companion.setSaleModelCache(saleModelNew);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, KeypadActivitySaleCom.class);
                            intent.putExtra("txnAmt", txnAmount);
                            intent.putExtra("typeofSale", 4);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.putExtra("apprCode", txn_map.get("TransactionApprovalCode"));
                            intent.putExtra("rrn", txn_map.get("TransactionRRN"));
                            intent.putExtra("invNo", txn_map.get("TransactionInvoice"));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 6: //VoidPreAuth
                        {
                            if(!txn_map.containsKey("TransactionInvoice")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            String txnInvoice = txn_map.get("TransactionInvoice");
                            try {
                                Integer.parseInt(txnInvoice);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, VoidPreauthActivity.class);
                            intent.putExtra("Invoice", txnInvoice);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 7: //saleQR
                        {
                            DbModelTerminalConfig terminalConfig = ServiceHolder.getTerminalConfig();
                            if(!DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "SALES_EWALLET")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC010");
                                txn_map.put("ResponseDescription", "Transaction Not Supported");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionAmount")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                            if(txn_map.containsKey("ProductCode")){
                                try {
                                    String productCode = txn_map.get("ProductCode");
                                    DbModelProductListGet productListGet = ProductListRepo.Companion.getSingle(getApplicationContext(),
                                            new ArrayList<>(List.of("Product", "QrProductCode", "IsActive")),
                                            new String[] { ProductCatSelectionDataEnum.GENERATE_QR.name(), productCode, "true" });
                                    if (productListGet == null) {
                                        throw new Exception();
                                    }
                                    SalesModel salesModel = new SalesModel(
                                            ProductCatSelectionDataEnum.GENERATE_QR.getData().getSalesType(),
                                            productListGet.getProduct(),
                                            productListGet.getAcqCode(),
                                            productListGet.getAcqMid(),
                                            productListGet.getAcqTid(),
                                            productListGet.getQrProductCode(),
                                            productListGet.getProductName(),
                                            productListGet.getEppProductCode(),
                                            productListGet.getEppTenure(),
                                            productListGet.getEppTenureCode()
                                    );
                                    ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                    Gson gson = new Gson();
                                    String jsonProductList = gson.toJson(productListGet);
                                    SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                    saleModelNew.setSalesType(ProductCatSelectionDataEnum.GENERATE_QR.getData().getSalesType());
                                    ServiceHolder.Companion.setSaleModelCache(saleModelNew);

                                    Intent intent = new Intent(this, GenerateQrActivity.class);
                                    intent.putExtra("txnAmt", txnAmount);
                                    intent.putExtra("posReference", posReferenceNo);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    startActivity(intent);
                                    finish();
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                    });
                                    txn_map.put("ResponseCode", "SHC007");
                                    txn_map.put("ResponseDescription", "Terminal System Error");
                                    onBackToApp();
                                    break;
                                }
                            } else {
                                int cameraFacing = 0;
                                if(txn_map.containsKey("CameraFacing")){
                                    int tempCamera = Integer.parseInt(txn_map.get("CameraFacing"));
                                    if(tempCamera == 1 || tempCamera == 0) {
                                        cameraFacing = tempCamera;
                                    }
                                }

                                try {
                                    //DbModelProductList productModel = getSpecificProduct(productCat);
                                    DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                    if (productModel == null) {
                                        throw new Exception();
                                    }
                                    SalesModel salesModel = new SalesModel(
                                            ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.getData().getSalesType(),
                                            productModel.getProduct(),
                                            productModel.getAcqCode(),
                                            productModel.getAcqMid(),
                                            productModel.getAcqTid(),
                                            productModel.getQrProductCode(),
                                            productModel.getProductName(),
                                            productModel.getEppProductCode(),
                                            productModel.getEppTenure(),
                                            productModel.getEppTenureCode()
                                    );
                                    ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                    Gson gson = new Gson();
                                    String jsonProductList = gson.toJson(productModel);
                                    SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                    saleModelNew.setSalesType(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.getData().getSalesType());
                                    ServiceHolder.Companion.setSaleModelCache(saleModelNew);

                                    Intent intent = new Intent(this, QrScanActivity.class);
                                    intent.putExtra("txnAmt", txnAmount);
                                    intent.putExtra("posReference", posReferenceNo);
                                    intent.putExtra("cameraFacing", cameraFacing);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    startActivity(intent);
                                    finish();
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                    });
                                    txn_map.put("ResponseCode", "SHC007");
                                    txn_map.put("ResponseDescription", "Terminal System Error");
                                    onBackToApp();
                                    break;
                                }
                            }
                            break;
                        }
                        case 8: //voidQR
                        {
                            if(!txn_map.containsKey("TransactionRefId")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionRefId)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionRefId)");
                                onBackToApp();
                                break;
                            }

                            String txnInvoice = txn_map.get("TransactionRefId");
                            try {
                                Long.parseLong(txnInvoice);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionRefId)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionRefId)");
                                onBackToApp();
                                break;
                            }

                            try {
                                //DbModelProductList productModel = getSpecificProduct(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name());
                                DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name())));
                                if (productModel == null) {
                                    //productModel = getSpecificProduct(ProductCatSelectionDataEnum.GENERATE_QR.name());
                                    productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(ProductCatSelectionDataEnum.GENERATE_QR.name())));
                                }

                                if(productModel == null) {
                                    throw new Exception();
                                }
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, VoidQrActivity.class);
                            intent.putExtra("Invoice", txnInvoice);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 9: //settlementQr
                        {
                            Intent intent = new Intent(this, SettlementQrActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 10: //VoidSaleCom
                        {
                            if(!txn_map.containsKey("TransactionInvoice")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            String txnInvoice = txn_map.get("TransactionInvoice");
                            try {
                                Integer.parseInt(txnInvoice);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionInvoice)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionInvoice)");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, VoidOffSaleActivity.class);
                            intent.putExtra("Invoice", txnInvoice);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 11: //MOTO sale
                        {
                            DbModelTerminalConfig terminalConfig = ServiceHolder.getTerminalConfig();
                            if(!DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "MOTO")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Transaction Not Supported", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC010");
                                txn_map.put("ResponseDescription", "Transaction Not Supported");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("CardNumber")) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (CardNumber)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (CardNumber)");
                                onBackToApp();
                                break;
                            }

                            String cardNumber = txn_map.get("CardNumber");
                            try {
                                Double.parseDouble(cardNumber);
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (CardNumber)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (CardNumber)");
                                onBackToApp();
                                break;
                            }

                            String expDate = txn_map.get("ExpiryDate");
                            if (expDate == null) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (ExpiryDate)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (ExpiryDate)");
                                onBackToApp();
                                break;
                            }

                            if(!txn_map.containsKey("TransactionAmount")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            try {
                                terminalConfig = ServiceHolder.getTerminalConfig();
                                if(DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "MOTO")){
                                    String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                    DbModelProductListGet productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(productCat)));
                                    if (productModel == null) {
                                        //Handle for MOTO merged with CARD
                                        System.out.println("MOTO empty -> CARD SETTINGS");
                                        productModel = ProductListRepo.Companion.getSinglev2(ServiceHolder.mContext, new ArrayList<>(List.of("Product")), new ArrayList<>(List.of(ProductCatSelectionDataEnum.CARD_SETTINGS.name())));
                                        if (productModel == null) {
                                            throw new Exception();
                                        }
                                    } else {
                                        throw new Exception();
                                    }
                                    SalesModel salesModel = new SalesModel(
                                            ProductCatSelectionDataEnum.MOTO.getData().getSalesType(),
                                            productModel.getProduct(),
                                            productModel.getAcqCode(),
                                            productModel.getAcqMid(),
                                            productModel.getAcqTid(),
                                            productModel.getQrProductCode(),
                                            productModel.getProductName(),
                                            productModel.getEppProductCode(),
                                            productModel.getEppTenure(),
                                            productModel.getEppTenureCode()
                                    );
                                    ServiceHolder.Companion.setSelectedCacheModel(salesModel);
                                    Gson gson = new Gson();
                                    String jsonProductList = gson.toJson(productModel);
                                    SaleModelNew saleModelNew = gson.fromJson(jsonProductList, SaleModelNew.class);
                                    saleModelNew.setSalesType(ProductCatSelectionDataEnum.MOTO.getData().getSalesType());
                                    ServiceHolder.Companion.setSaleModelCache(saleModelNew);

                                } else {
                                    throw new Exception();
                                }
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, MotoSaleActivity.class);
                            intent.putExtra("cardNumber", cardNumber);
                            intent.putExtra("expDate", expDate);
                            intent.putExtra("txnAmt", txnAmount);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.putExtra("typeofSale", ProductCatSelectionDataEnum.MOTO.getData().getSalesType());
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 12: // EPP
                        {
                            if(!txn_map.containsKey("TransactionAmount")){
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid Parameter - (TransactionAmount)", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid Parameter - (TransactionAmount)");
                                onBackToApp();
                                break;
                            }

                            try {
                                String productCat = ProductCatSelectionDataEnum.Companion.getProductCatForHttp(ServiceHolder.Companion.getTxnType());
                                DbModelProductList productModel = getSpecificProduct(productCat);
                                if (productModel == null) {
                                    throw new Exception();
                                }
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "System Error", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC007");
                                txn_map.put("ResponseDescription", "Terminal System Error");
                                onBackToApp();
                                break;
                            }

                            Intent intent = new Intent(this, EppAcquirerActivity.class);
                            intent.putExtra("txnAmt", txnAmount);
                            intent.putExtra("posReference", posReferenceNo);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                            finish();
                            break;
                        }
                        case 13: { //Transaction Inquiry
                            if (posReferenceNo == null) {
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(getApplicationContext(), "Invalid PosReference No", Toast.LENGTH_SHORT).show(); }
                                });
                                txn_map.put("ResponseCode", "SHC001");
                                txn_map.put("ResponseDescription", "Invalid PosReference No");
                            } else {
                                DbModelReceiptUpload dbmodelReceiptUpload = ReceiptUploadRepo.Companion.getSingleDesc(this,
                                        new ArrayList<>(List.of("POS_REF_NO")),
                                        new String[] { posReferenceNo });
                                if(dbmodelReceiptUpload != null ){
                                    String txnQrRefId = dbmodelReceiptUpload.getQrRefId();
                                    if(txnQrRefId == null){
                                        txnQrRefId = "";
                                    }
                                    if(!txnQrRefId.isEmpty()){
                                        DbModelTransactionQrGet transactionQrData = TransactionQrRepo.Companion.getSingleTransactionQr(this,
                                                new ArrayList<>(List.of("refId")),
                                                new ArrayList<>(List.of(txnQrRefId)));
                                        if (transactionQrData != null){
                                            String txnType = transactionQrData.getTxnType();
                                            if(txnType == null){
                                                txnType = "";
                                            }
                                            String txnQrAmt = transactionQrData.getTxnAmount();
                                            if(txnQrAmt == null) {
                                                txnQrAmt = "";
                                            }

                                            String transactionDateTime = "Void".equals(txnType.trim())
                                                    ? transactionQrData.getVoidDateTime()
                                                    : transactionQrData.getTxnDateTime();

                                            txn_map.put("ResponseCode", transactionQrData.getRespCode());
                                            txn_map.put("ResponseDescription", transactionQrData.getRespDesc());
                                            txn_map.put("TransactionLabel", transactionQrData.getTxnType());
                                            txn_map.put("TransactionAmount", Utils.getActualAmount(txnQrAmt));
                                            txn_map.put("TransactionId", transactionQrData.getHostRefNo());
                                            txn_map.put("TransactionRefId", transactionQrData.getRefId());
                                            txn_map.put("TransactionEWallet", transactionQrData.getProductCode());
                                            txn_map.put("TransactionEWalletDescription", transactionQrData.getProductName());
                                            txn_map.put("TransactionDateTime", transactionDateTime);

                                        } else {
                                            txn_map.put("ResponseCode", "SHC008");
                                            txn_map.put("ResponseDescription", "QR Transaction Not Found");
                                        }
                                    } else {
                                        String desc = "Failed";
                                        try {
                                            String formedEnumTag = "TAG_"+dbmodelReceiptUpload.getRESP_CODE();
                                            desc = "(" + dbmodelReceiptUpload.getRESP_CODE() + ")" + CardErrorDataEnum.valueOf(formedEnumTag).getData();
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        String txnAmt = dbmodelReceiptUpload.getTXN_AMT();
                                        if(txnAmt == null){
                                            txnAmt = "";
                                        }

                                        txn_map.put("ResponseCode", dbmodelReceiptUpload.getRESP_CODE());
                                        txn_map.put("ResponseDescription", desc);
                                        txn_map.put("TransactionLabel", dbmodelReceiptUpload.getTXN_TYPE());
                                        txn_map.put("TransactionAmount", Utils.getActualAmount(txnAmt));
                                        txn_map.put("TransactionMID", dbmodelReceiptUpload.getMID());
                                        txn_map.put("TransactionTID", dbmodelReceiptUpload.getTID());
                                        txn_map.put("TransactionSTN", dbmodelReceiptUpload.getSTAN());
                                        txn_map.put("TransactionRRN", dbmodelReceiptUpload.getRRN());
                                        txn_map.put("TransactionBatchNo", dbmodelReceiptUpload.getBATCH_NO());
                                        txn_map.put("TransactionApplicationLabel", dbmodelReceiptUpload.getCARD_LABEL());
                                        txn_map.put("TransactionCardNo", dbmodelReceiptUpload.getCARD_MASKED());
                                        txn_map.put("TransactionEntryType", dbmodelReceiptUpload.getENTRY_TYPE());
                                        txn_map.put("TransactionARQC", dbmodelReceiptUpload.getARQC());
                                        txn_map.put("TransactionTVR", dbmodelReceiptUpload.getTVR());
                                        txn_map.put("TransactionAID", dbmodelReceiptUpload.getAID());
                                        txn_map.put("TransactionCVM", dbmodelReceiptUpload.getCVM());
                                        txn_map.put("TransactionTSI", "-");
                                        txn_map.put("TransactionApprovalCode", dbmodelReceiptUpload.getAPPR_CODE());
                                        txn_map.put("OriTransactionRRN", dbmodelReceiptUpload.getRRN_ORI());
                                        txn_map.put("OriTransactionApprovalCode", dbmodelReceiptUpload.getAPPR_CODE_ORI());
                                        txn_map.put("TransactionInvoice", dbmodelReceiptUpload.getINV_NO());
                                        txn_map.put("TransactionSchemeID", dbmodelReceiptUpload.getSCHEME_ID());
                                        txn_map.put("TransactionDateTime", dbmodelReceiptUpload.getTXN_DT());

                                        Gson gson = new Gson();
                                        EppDetail eppDetail = gson.fromJson(dbmodelReceiptUpload.getEPP_DETAIL(), EppDetail.class);
                                        System.out.println("eppDetail -> " + eppDetail);
                                        if(eppDetail != null && eppDetail.getTenure() != null && !eppDetail.getTenure().equalsIgnoreCase("00")){
                                            txn_map.put("TransactionEPP", dbmodelReceiptUpload.getEPP_DETAIL());
                                        } else {
                                            txn_map.put("TransactionEPP", "-");
                                        }
                                    }
                                } else {
                                    txn_map.put("ResponseCode", "SHC008");
                                    txn_map.put("ResponseDescription", "Transaction Not Found");
                                }
                            }

                            onBackToApp();
                            break;
                        }
                        default: {
                            runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(getApplicationContext(), "Invalid Transaction Type", Toast.LENGTH_SHORT).show(); }
                            });
                            txn_map.put("ResponseCode", "SHC001");
                            txn_map.put("ResponseDescription", "Invalid Transaction Type");
                            onBackToApp();
                            break;
                        }
                    }
                } else {
                    onBackPressed();
                }
            }
        }).start();
    }

    private void isMyServiceRunning() {
        boolean startService = true;
        Intent intent = new Intent(this, AppServices.class);
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);

        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (AppServices.class.getName().equals(service.service.getClassName())) {
                startService = false;
                //stopService(intent);
            }
        }

        if(startService) {
            startService(intent);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent;
        intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ServiceHolder.Companion.setAppIntent(false);
        startActivity(intent);
    }

    public void onBackToApp() {
        Intent intent;
        intent = new Intent(this, TransactionTransmitter.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("txn_map", txn_map);
        startActivity(intent);
        finish();
    }
}
