package com.sc.mf919.java.activity;

import android.util.Log;

import com.library.terminal.Cryptography;
import com.library.terminal.HttpConnection;
import com.library.terminal.Utility;
import com.sc.mf919.kotlin.activity.AppServices;
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo;
import com.sc.mf919.kotlin.helper_common.Helper;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;
import env.EnvironmentManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class UploadTMS {
    private final String TAG = UploadTMS.class.getSimpleName();
    private static UploadTMS uploadTms = null;
    private List<String> receipt = null;
    private final String filename = "receipt.txt";

    public static UploadTMS getInstance() {
        if (uploadTms == null) {
            uploadTms = new UploadTMS();
        }
        return uploadTms;
    }

    UploadTMS() {
        initConfig();
    }

    private void initConfig() {
        receipt = new ArrayList<>();
        String[] tmp = Utility.read_file(ServiceHolder.Companion.getInternalFilesPaths() + filename);
        if (tmp != null) {
            if (tmp[0] != null) {
                receipt = new LinkedList<>(Arrays.asList(tmp));
            }
        }

    }

    private void saveReceipt() {
        if (receipt.size() > 0) {
            String[] tmp = new String[receipt.size()];
            receipt.toArray(tmp);
            Utility.write_file(tmp, ServiceHolder.Companion.getInternalFilesPaths() + filename);
        } else {
            Utility.delete_file(ServiceHolder.Companion.getInternalFilesPaths() + filename);
        }

    }

    public void uploadReceipt() {
        //int iReceiptCount = receipt.size();
        //if(iReceiptCount>0)
        //{
        Log.d(TAG, "uploadReceipt: " + receipt.size());
        for (int loop = receipt.size(); loop > 0; loop--) {
            int index = loop - 1;
            if (index >= 0 && index < receipt.size()) {
                int iResp = uploadReceipt_ext(receipt.get(index));
                if (iResp == 0) {
                    receipt.remove(index);
                }
            }
        }
        saveReceipt();
        //}
    }

    private int uploadReceipt_ext(String body) {
        EnvironmentManager environmentManager = new EnvironmentManager(Objects.requireNonNull(Helper.Companion.getInstance().getPrefs()));
        Utils.debugLogPrint(TAG, "SEND MSG=" + body);

        //Header
        Map<String, String> header = new HashMap<String, String>();
        header.put("Content-Type", "application/json");

        //String body=jsonObject.toString();


        //Calculate Hashkey
        String strHashKeyOut = null;
        try {
            //strHashKeyOut = Cryptography.HMAC(Utility.ASCIItoHexString(body), Utility.ASCIItoHexString(Global.envSettings.serverHashKey), Cryptography.hashAlgorithm.SHA_256);
            strHashKeyOut = Cryptography.HMAC(Utility.ASCIItoHexString(body), Utility.ASCIItoHexString(environmentManager.getByJava("serverHashKey")), Cryptography.hashAlgorithm.SHA_256);
        } catch (Exception e) {
            e.printStackTrace();
            strHashKeyOut = "";
        }
        Utils.debugLogPrint(TAG, "CHECKSUM=" + strHashKeyOut);
        header.put("CHECKSUM", strHashKeyOut.toUpperCase());
        String ip = Utils.getPublicIP();
        Utils.debugLogPrint(TAG, "sendTmsVoidReceipt: " + ip);
        header.put("TERMINAL_IP", ip);

        Utils.debugLogPrint(TAG, "sendTmsReceipt: " + environmentManager.getByJava("baseUrl") + ServiceHolder.Companion.getStringValue("tmsUrl.ini", "receiptUrl"));
        HttpConnection connection = new HttpConnection(environmentManager.getByJava("baseUrl") + ServiceHolder.Companion.getStringValue("tmsUrl.ini", "receiptUrl"), header, body, (60 * 1000));

        new Thread() {
            public void run() {
                connection.POSTRequest();
            }
        }.start();

        Utils.debugLogPrint(TAG, "sendTmsReceipt: " + connection.getMsgStatus());
        while (connection.isActive()) {
            //Utils.debugLogPrint(TAG, "uploadPayload: " + connection.getMsgStatus());
            Utils.DelayMili(100);
        }
        Utils.debugLogPrint(TAG, "sendTmsReceipt: " + connection.getMsgStatus());

        String resp = connection.getResponseBody();
        if (resp == null) {
            Utils.debugLogPrint(TAG, "BCBase:Error: Body Message = NULL");
            return -100;
        }

        Utils.debugLogPrint(TAG, "RECEIVED MSG=" + resp);

        try {
            JSONObject responseMsg = new JSONObject(resp);
            String respCode = responseMsg.getString("RESP_CODE");
            if (!respCode.equals("0000")) {
                Utils.debugLogPrint(TAG, "ERR:Invalid RESP_CODE");
                return -1;
            }
            Utils.debugLogPrint(TAG, "RESP_CODE success");

            String refId = responseMsg.getString("REF_ID");
            if (!refId.equals(refId)) {
                Utils.debugLogPrint(TAG, "Invalid QR_REFID");
                return -1;
            }
            Utils.debugLogPrint(TAG, "QR_REFID Matched");

            String respDesc = responseMsg.getString("RESP_DESC");
            Utils.debugLogPrint(TAG, "RESP_DESC = " + respDesc);
        } catch (JSONException e) {
            e.printStackTrace();
            return -1;
        }

        Utils.debugLogPrint(TAG, resp);

        return 0;
    }

    public void addReceipt(String body) {
        //receipt.add(body);
        //saveReceipt();
        ReceiptUploadRepo.Companion.insertToDb(ServiceHolder.Companion.getContext(), body);
        AppServices.Companion.receiptUploadToTms(ServiceHolder.Companion.getContext());
    }
}
