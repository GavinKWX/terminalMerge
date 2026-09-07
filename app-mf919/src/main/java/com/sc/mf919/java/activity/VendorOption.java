package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.widget.SwitchCompat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;


import com.sc.mf919.R;
import com.sc.mf919.kotlin.activity.AdminActivity;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.io.File;
import java.io.FilenameFilter;

public class VendorOption extends AppCompatActivity
{
    private SwitchCompat debugMsg_check;
    private SwitchCompat userInput_check;
    private SwitchCompat hostURL_check;
    private SwitchCompat gkash_check;
    private SwitchCompat ipay_check;
    private SwitchCompat waz2pay_check;
    private String DB_PATH;

    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vendor);
        debugMsg_check=findViewById(R.id.debugMsg);
        //debugMsg_check.setChecked(GeneralMethod.isDebugMsgEnable());
        userInput_check=findViewById(R.id.userInput);
        //userInput_check.setChecked(GeneralMethod.isUserInputEnable());
        hostURL_check=findViewById(R.id.hostURL);
        //hostURL_check.setChecked(GeneralMethod.isHostURL());
        //if(GeneralMethod.whichEWalletIsActive()>1)
        //{
        //    GeneralMethod.ipayUpdate(false);
        //    GeneralMethod.gkashUpdate(false);
        //    GeneralMethod.waz2payUpdate(false);
        //}
        ipay_check=findViewById(R.id.ipay);
        //ipay_check.setChecked(GeneralMethod.ipayActive());
        gkash_check=findViewById(R.id.gkash);
        //gkash_check.setChecked(GeneralMethod.gkashActive());
        waz2pay_check=findViewById(R.id.waz2pay);
        //waz2pay_check.setChecked(GeneralMethod.waz2payActive());
    }

    public void onDebugMsg(View view)
    {
        //GeneralMethod.setDebugMsgEnability(debugMsg_check.isChecked());
    }

    public void onUserInput(View view)
    {
        //GeneralMethod.setUserInputEnability(userInput_check.isChecked());
    }

    public void onHostURL(View view)
    {
        //GeneralMethod.setHostURL(hostURL_check.isChecked());
    }

    public void startEditor(View view)
    {
        Intent intent = new Intent(VendorOption.this, ParameterEditor.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    public void startIso(View view)
    {
        Intent intent = new Intent(VendorOption.this, IsoMessageView.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    public void start2EditTable(View view)
    {
        DB_PATH = ServiceHolder.Companion.getContext().getApplicationInfo().dataDir + "/databases/";

        File dir = new File(DB_PATH);
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String s)
            {
                return s.toLowerCase().endsWith(".db");
            }
        });
        String[] dbName=new String[files.length];
        for(int j=0;j<files.length;j++)
        {
            dbName[j]=files[j].getName();
        }

        dbSelectorDialog(dbName);
    }

    public void startClear(View view)
    {
        /*GeneralMethod.BatchDatabase BD = new GeneralMethod.BatchDatabase();
        GeneralMethod.SettlementDatabase SD = new GeneralMethod.SettlementDatabase();
        GeneralMethod.SettlementReportDatabase SR = new GeneralMethod.SettlementReportDatabase();
        GeneralMethod.ReversalDatabase RD = new GeneralMethod.ReversalDatabase();
        GeneralMethod.OfflineDatabase OD = new GeneralMethod.OfflineDatabase();
        GeneralMethod.TransReport TR = new GeneralMethod.TransReport();
        GeneralMethod.TMSDatabase TD = new GeneralMethod.TMSDatabase();
        GeneralMethod.RunningNumber RN = new GeneralMethod.RunningNumber();
        BD.deleteALL();
        SD.reset();
        SR.reset();
        TR.deleteALL();
        OD.deleteALL();
        RD.deleteALL();
        TD.deleteAll();
        RN.reset();*/
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if ((keyCode == KeyEvent.KEYCODE_HOME) || keyCode == KeyEvent.KEYCODE_MENU)
        {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void setKey(View view)
    {
        /*Intent intent = new Intent(this, ChangePassword.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();*/
    }

    @Override
    public void onBackPressed()
    {
        super.onBackPressed();
        Intent intent = new Intent(VendorOption.this, AdminActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    public void startFileTransfer(View view)
    {
        /*Intent intent = new Intent(VendorOption.this, FileTransfer.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();*/
    }

    public void onGkash(View view)
    {
        /*if(gkash_check.isChecked())
        {
            GeneralMethod.ipayUpdate(false);
            GeneralMethod.waz2payUpdate(false);
            ipay_check.setChecked(false);
            waz2pay_check.setChecked(false);
        }
        GeneralMethod.gkashUpdate(gkash_check.isChecked());*/
    }

    public void onIpay88(View view)
    {
        /*if(ipay_check.isChecked())
        {
            GeneralMethod.gkashUpdate(false);
            GeneralMethod.waz2payUpdate(false);
            gkash_check.setChecked(false);
            waz2pay_check.setChecked(false);
        }
        GeneralMethod.ipayUpdate(ipay_check.isChecked());*/
    }

    public void onWaz2Pay(View view)
    {
        /*if(waz2pay_check.isChecked())
        {
            GeneralMethod.gkashUpdate(false);
            GeneralMethod.ipayUpdate(false);
            gkash_check.setChecked(false);
            ipay_check.setChecked(false);
        }
        GeneralMethod.waz2payUpdate(waz2pay_check.isChecked());*/
    }

    public void dbSelectorDialog(final String[] list)
    {
        final RadioButton[] mRB = new RadioButton[list.length];
        LayoutInflater li = LayoutInflater.from(this);
        @SuppressLint("InflateParams") final View promptsView = li.inflate(R.layout.activity_dbselector, null);
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.DialogThemeColor);
        alertDialogBuilder.setView(promptsView);
        LinearLayout.LayoutParams Lay = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final RadioGroup mRG = promptsView.findViewById(R.id.selectorLayout);
        for (int i = 0; i < list.length; i++)
        {
            mRB[i] = new RadioButton(this);
            mRB[i].setId(i + 1);
            mRB[i].setLayoutParams(Lay);
            mRB[i].setText(list[i]);
            mRB[i].setTextColor(getResources().getColor(R.color.dialogMsg));
            mRG.addView(mRB[i]);
            mRG.check(mRB[i].getId());
        }

        // set dialog message
        alertDialogBuilder.setCancelable(false)
                .setNegativeButton("OK",new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int id)
                    {
                        int selectedId = mRG.getCheckedRadioButtonId();
                        RadioButton radioButton = promptsView.findViewById(selectedId);
                        String value = radioButton.getText().toString();

                        Intent intent = new Intent(VendorOption.this, ViewTableContain.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        intent.putExtra("dbName",value);
                        startActivity(intent);
                        finish();
                    }
                })
                .setPositiveButton("Cancel",new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int id)
                    {
                        dialog.dismiss();
                    }

                }
                );

        AlertDialog alert11 = alertDialogBuilder.create();
        alert11.show();
    }

}
