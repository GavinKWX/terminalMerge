package com.sc.mf919pro.java.activity;

import android.content.Context;

import com.sc.mf919pro.R;
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig;
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo;
import com.sc.mf919pro.kotlin.helper_common.Helper;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;
import com.sc.mf919pro.kotlin.helper_common.TmsHelper;
import env.EnvironmentManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import enums.EnumLogFileName;
import helpers.HelperCommon;
import helpers.HelperLog;
import tms.handlers.DeviceInfoHandler;
import tms.models.DeviceInfoResp;
import tms.models.DeviceTestCaseList;

public class Tms {
    private static final String TAG = "TMS";
    private String mFirmFileName = "";
    private final Context mContext;
    private final HelperLog log;
    private final String className;

    public Tms(Context tContext) {
        mContext = tContext;
        log = new HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(tContext),
            Utils.getIPAddress(),
            "TMS Activity",
            Tms.this.getClass().getSimpleName(),
            Tms.this.getClass().getName()
        );
        className = Tms.this.getClass().getName();
    }

    public int downloadMerchantInfo() {
        boolean result = TmsHelper.getMerchantConfiguration(log, mContext);
        if (!result) {
            return -1;
        }

        return 0;
    }

    private int downloadConfiguration() {
        boolean result = TmsHelper.getTerminalConfiguration(log, mContext);
        if (!result) {
            return -1;
        }

        return 0;
    }

    private int downloadInjectKey() {
        boolean result = TmsHelper.getInjectionKey(log, mContext);
        if (result) {
            return 0;
        } else {
            return -1;
        }
    }

    public void uploadTms() {
        EnvironmentManager environmentManager = new EnvironmentManager(Objects.requireNonNull(Helper.Companion.getInstance().getPrefs()));
        DeviceInfoHandler deviceInfoHandler = new DeviceInfoHandler(environmentManager);
        DbModelTerminalConfig dbModelTerminalConfig = ServiceHolder.getTerminalConfig();
        String firmID = "";
        /*
        if (Utility.exist_file(ServiceHolder.Companion.getInternalFilesPaths() + "firmID.txt")) {
            String[] file = Utility.read_file(ServiceHolder.Companion.getInternalFilesPaths() + "firmID.txt");
            Utils.debugLogPrint(TAG, "uploadTms: " + Arrays.toString(file));
            firmID = file[0];
        }
        * */

        try{
            int batteryCheck = Utils.checkBatteryStatus();
            ArrayList<DeviceTestCaseList> testCase = new ArrayList<>(List.of(new DeviceTestCaseList("BATTERYSTATUS", batteryCheck)));
            DeviceInfoResp apiResp = deviceInfoHandler.invoke(
                    log,
                    ServiceHolder.getSqnNum(),
                    ServiceHolder.getTerminalSerialNumber(),
                    ServiceHolder.getDeviceModel(),
                    mContext.getString(R.string.app_name),
                    DbModelTerminalConfig.Companion.getSafeValue(dbModelTerminalConfig, "DEV_PROJECT"),
                    DbModelTerminalConfig.Companion.getSafeValue(dbModelTerminalConfig, "DEV_LOCATION"),
                    DbModelTerminalConfig.Companion.getSafeValue(dbModelTerminalConfig, "DEV_LANE_ID"),
                    firmID,
                    ServiceHolder.getAppVersion(),
                    ServiceHolder.getMcVersion(),
                    "-",
                    testCase
			);
            log.appendLine(className, "DeviceInfoHandler Response -> ", apiResp.toString());

            try{
                //String appName = apiResp.getFIRM_FILENAME();
                JSONArray jsonArray = new JSONArray(apiResp.getTASK_NAME());
                for (int j = 0; j < jsonArray.length(); j++) {
                    switch (jsonArray.getString(j)) {
                        case "MerchantConfigUpdate": {
                            int iResp = downloadMerchantInfo();
                            if (iResp == 0) {
                                DbModelMerchantConfig dbModelMerchantConfig = ServiceHolder.Companion.getMerchantInfo();
                                String acqMid = DbModelMerchantConfig.Companion.getSafeValue(dbModelMerchantConfig, "AcqMid");
                                String acqTid = DbModelMerchantConfig.Companion.getSafeValue(dbModelMerchantConfig, "AcqTid");
                                String tpdu = DbModelMerchantConfig.Companion.getSafeValue(dbModelMerchantConfig, "TPDU");
                                String nii = DbModelMerchantConfig.Companion.getSafeValue(dbModelMerchantConfig, "NII");
                                //VISAM
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, acqMid, "mid", "visam");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, acqTid, "tid", "visam");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, tpdu, "isoTpduHeader", "visam");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, tpdu, "isoTpduHeaderTle", "visam");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, nii, "nii", "visam");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, nii, "niiTle", "visam");
                                //MCCS
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, acqMid, "mid", "mccs");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, acqTid, "tid", "mccs");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, tpdu, "isoTpduHeader", "mccs");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, tpdu, "isoTpduHeaderTle", "mccs");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, nii, "nii", "mccs");
                                IsoBatchInfoRepo.Companion.updateBatchInfo(mContext, nii, "niiTle", "mccs");
                            }
                            break;
                        }
                        case "TerminalConfigUpdate": {
                            int res = downloadConfiguration();
                            break;
                        }
                        case "InjectKeyUpdate": {
                            int res = downloadInjectKey();
                            break;
                        }
                        //case "FirmwareUpdate": {
                        //    String appName=responseMsg.getString("FIRM_FILENAME");
                        //    Utils.debugLogPrint("TAG", "checkForUpdate: " + appName);
                        //}
                    }
                }

                /*
                if (!appName.equals("null")) {
                    mFirmFileName = responseMsg.getString("FIRM_FILENAME");
                    String mFirmId = responseMsg.getString("FIRM_ID");
                    String url = responseMsg.getString("FIRM_URL");
                    Utils.debugLogPrint(TAG, "uploadTms: " + mFirmFileName);
                    Utils.debugLogPrint(TAG, "uploadTms: " + url);

                    UrlDownload download = new UrlDownload(appName, url, Environment.getExternalStorageDirectory().getPath() + File.separator + "app-debug.apk");
                    download.getFile();
                    if (download.getPercentage() != 100) {
                        return -1;
                    } else {
                        Utils.write2File(new String[]{mFirmId, ServiceHolder.Companion.getAppVersion()}, "updateApk.txt");
                        Utils.write2File(new String[]{mFirmId}, "firmID.txt");
                        handlerUpadte.sendMessage(new Message());
                    }
                } else {
                    return -1;
                }
                * */
            } catch (JSONException jsonEx) {
                log.appendLine(className, "Json Exception -> ", jsonEx.toString());
            }

        }catch (Exception ex){
            log.appendLine(className, "DeviceInfoHandler Response (Exception)", ex.toString());
        }finally {
            log.logToFile(EnumLogFileName.TerminaLog);
        }
    }

    /*
    Handler handlerUpadte = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            runUpdate();
            //updateFile();
        }
    };
    private synchronized void runUpdate() {
        try {
            //firmUpdate_ack();

            Utils.debugLogPrint("TAG", "Start installapp");
            DeviceHelper.getDeviceService().installApp(Environment.getExternalStorageDirectory().getPath() + File.separator + "app-debug.apk", "", "");
            doRestart();
            Utils.debugLogPrint("TAG", "end installapp");
        } catch (RemoteException exception) {
            exception.printStackTrace();
        }
    }
    * */
    /*
    private void doRestart() {
        Intent mStartActivity = new Intent(ServiceHolder.Companion.getContext(), MainActivity.class);
        int mPendingIntentId = 123456;
        PendingIntent mPendingIntent = PendingIntent.getActivity(ServiceHolder.Companion.getContext(), mPendingIntentId, mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager mgr = (AlarmManager) ServiceHolder.Companion.getContext().getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 30000, mPendingIntent);
        //System.exit(0);
    }
    * */
    /*
    public void firmUpdate_ack() {
        if (Utility.exist_file(ServiceHolder.Companion.getInternalFilesPaths() + "updateApk.txt")) {
            String[] file = Utility.read_file(ServiceHolder.Companion.getInternalFilesPaths() + "updateApk.txt");
            DbModelTerminalConfig dbModelTerminalConfig = ServiceHolder.Companion.getTerminalConfig();

            EnvironmentManager environmentManager = new EnvironmentManager(Objects.requireNonNull(Helper.Companion.getInstance().getPrefs()));
            final Date currentTime = new Date();
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
            String timeStamp = sdf.format(currentTime);

            //Header
            Map<String, String> header = new HashMap<String, String>();
            header.put("Content-Type", "application/json");

            //Json Body
            JSONObject jsonObject = new JSONObject();
            try {
                Utils.debugLogPrint("firmudpateack1", "");
                Utils.debugLogPrint("firmudpateack4", ServiceHolder.Companion.getDeviceModel());
                Utils.debugLogPrint("firmudpateack3", ServiceHolder.Companion.getTerminalSerialNumber());
                jsonObject.put("SEQ_NO", ServiceHolder.Companion.getSqnNum());
                jsonObject.put("TXN_DT", timeStamp);
                jsonObject.put("DEV_SN", ServiceHolder.Companion.getTerminalSerialNumber());
                jsonObject.put("DEV_MODEL", ServiceHolder.Companion.getDeviceModel());
                jsonObject.put("DEV_APPNAME", ServiceHolder.Companion.getContext().getString(R.string.app_name));
                jsonObject.put("DEV_PROJECT", DbModelTerminalConfig.Companion.getSafeValue(dbModelTerminalConfig, "DEV_PROJECT"));
                jsonObject.put("DEV_LOCATION", DbModelTerminalConfig.Companion.getSafeValue(dbModelTerminalConfig, "DEV_LOCATION"));
                jsonObject.put("DEV_LANE_ID", DbModelTerminalConfig.Companion.getSafeValue(dbModelTerminalConfig, "DEV_LANE_ID"));
                jsonObject.put("FIRM_ID", file[0]);
                jsonObject.put("ADD_INFO", "-");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            String body = jsonObject.toString();
            Utils.debugLogPrint("TAG", "firmwareupdateack: " + jsonObject.toString());


            //Calculate Hashkey
            String strHashKeyOut = "";
            try {
                Utils.debugLogPrint("TAG", "ENV KEY=" + environmentManager.getByJava("serverHashKey") + "-->" + Utility.ASCIItoHexString(Global.envSettings.serverHashKey));
                strHashKeyOut = Cryptography.HMAC(Utility.ASCIItoHexString(body), Utility.ASCIItoHexString(environmentManager.getByJava("serverHashKey")), Cryptography.hashAlgorithm.SHA_256);
            } catch (Exception e) {
                e.printStackTrace();
            }
            Utils.debugLogPrint("TAG", "CHECKSUM=" + strHashKeyOut);
            header.put("CHECKSUM", strHashKeyOut.toUpperCase());
            header.put("TERMINAL_IP", Utils.getPublicIP());


            HttpConnection connection = new HttpConnection(environmentManager.getByJava("baseUrl") + ServiceHolder.Companion.getStringValue("tmsUrl.ini", "firmwareUpdateAckUrl"), header, body, (30 * 1000));
            connection.POSTRequest();

            if (connection.getResponseCode() != 200) {
                Utils.debugLogPrint("TAG", "BCBase:Error: HTTP Response: " + connection.getResponseCode() + " --> Message: " + connection.getResponseMessage());
            }

            String resp = connection.getResponseBody();
            if (resp == null) {
                Utils.debugLogPrint("TAG", "BCBase:Error: Body Message = NULL");
            }

            try {
                JSONObject responseMsg = new JSONObject(resp);
                if (Integer.parseInt(responseMsg.getString("RESP_CODE")) == 0) {
                    Utility.delete_file(ServiceHolder.Companion.getInternalFilesPaths() + "updateApk.txt");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            Utils.debugLogPrint("TAG", resp);
        }
    }
    * */
}
