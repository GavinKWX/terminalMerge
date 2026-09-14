package com.sc.mf919.java.activity;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.sc.mf919.R;
import com.sc.mf919.kotlin.activity.MainActivity;
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919.kotlin.helper_common.HTTPServer;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.util.ArrayList;
import java.util.HashMap;

import helpers.HelperCommon;
import com.sc.mf919.kotlin.helper_common.MfHelper;

public class TransactionTransmitter extends AppCompatActivity {
    HashMap<String, String> txn_map;
    ArrayList<HashMap<String, String>> settlement_map;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_txn_receiver);
        HTTPServer.getInstance().setAttendActivityContext(this);
        ServiceHolder.Companion.setAckCountDownSecond(ServiceHolder.Companion.getDefaultAckCountdownSecond());
        txn_map = (HashMap<String, String>) getIntent().getSerializableExtra("txn_map");
        settlement_map = (ArrayList<HashMap<String, String>>) getIntent().getSerializableExtra("settlement_map");

        DbModelTerminalConfig terminalConfig = ServiceHolder.Companion.getTerminalConfig();
        if (DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "FORCE_LOCK_HOME")) {
            MfHelper.lockStatusBarAndNavigation(true);
        } else {
            MfHelper.lockStatusBarAndNavigation(false);
        }

        if (settlement_map != null) {
            Utils.debugLogPrint("TransactionTransmitter", settlement_map.toString());
            transmitSettlement(settlement_map);
        } else {
            Utils.debugLogPrint("TransactionTransmitter", txn_map.toString());
            transmit(txn_map);
        }
    }


    private void transmit(HashMap<String, String> map) {
        Intent intent_transmit = getPackageManager().getLaunchIntentForPackage(ServiceHolder.Companion.getPackageName());
        intent_transmit.setAction(Intent.ACTION_SEND);
        intent_transmit.setClassName(ServiceHolder.Companion.getPackageName(), ServiceHolder.Companion.getActivityName());
        intent_transmit.putExtra("txn_map", map);
        ServiceHolder.Companion.setAppIntent(false);
        startActivity(intent_transmit);
        finish();

//        //Add to prevent background stuck at wrong page
//        android.os.Process.killProcess(android.os.Process.myPid());
//        System.exit(2);
    }

    private void transmitSettlement(ArrayList<HashMap<String, String>> map) {
        Intent intent_transmit = getPackageManager().getLaunchIntentForPackage(ServiceHolder.Companion.getPackageName());
        intent_transmit.setAction(Intent.ACTION_SEND);
        intent_transmit.setClassName(ServiceHolder.Companion.getPackageName(), ServiceHolder.Companion.getActivityName());
        intent_transmit.putExtra("settlement_map", map);
        ServiceHolder.Companion.setAppIntent(false);
        startActivity(intent_transmit);
        finish();

//        //Add to prevent background stuck at wrong page
//        android.os.Process.killProcess(android.os.Process.myPid());
//        System.exit(2);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ServiceHolder.Companion.setAppIntent(false);
        startActivity(intent);
    }
}
