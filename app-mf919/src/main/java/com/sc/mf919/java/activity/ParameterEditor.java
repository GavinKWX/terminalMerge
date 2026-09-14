package com.sc.mf919.java.activity;

import constants.TerminalConstants;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.sc.mf919.R;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ParameterEditor extends AppCompatActivity implements AdapterView.OnItemSelectedListener {
    private static final String TAG = "ParameterEditor";
    private String[] tag1;
    private String[] val;
    private LinearLayout rl;
    private LinearLayout[] hz;
    private TextView[] tv;
    private EditText[] et;
    String currentTableName;
    Map<String, String> mapTem;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parameterview_screen);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int width = displayMetrics.widthPixels;
        Utils.debugLogPrint(TAG, "onCreate: " + width);
        String[] tableNames = {TerminalConstants.filesInfo.termInfoFile, TerminalConstants.filesInfo.tmsFile, TerminalConstants.filesInfo.configFile, /*TerminalConstants.filesInfo.kiosk, TerminalConstants.filesInfo.kioskInfo,*/ TerminalConstants.filesInfo.addFile, TerminalConstants.filesInfo.selection};
        Spinner spinner = findViewById(R.id.tablename);
        spinner.setOnItemSelectedListener(this);
        List<String> categories = new ArrayList<>();
        Collections.addAll(categories, tableNames);
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, R.layout.activity_spinneritem, categories);
        dataAdapter.setDropDownViewResource(R.layout.activity_spinneritem);
        spinner.setAdapter(dataAdapter);

        rl = findViewById(R.id.layout_1PE);
        rl.setScrollContainer(true);

        Button update = findViewById(R.id.updatebutton);
        update.setOnClickListener(new View.OnClickListener() {
            public void onClick(View V) {
                open();
            }
        });
    }

    private String[] getTags(String[] value) {
        String[] tags = new String[value.length];
        for (int j = 0; j < value.length; j++) {
            int index = value[j].indexOf("=");
            tags[j] = value[j].substring(0, index);
            Utils.debugLogPrint(TAG, "Tag = " + tags[j]);
        }
        return (tags);
    }

    private String[] getValue(String[] value) {
        String[] val = new String[value.length];
        for (int j = 0; j < value.length; j++) {
            int index = value[j].indexOf("=");
            val[j] = value[j].substring(index + 1);
            Utils.debugLogPrint(TAG, "Tag = " + val[j]);
        }
        return (val);
    }

    private String[] merge2Arrays(String[] arr1, String[] arr2) {
        String[] newArry = new String[arr1.length];
        for (int j = 0; j < arr1.length; j++) {
            newArry[j] = arr1[j] + "=" + arr2[j];
        }
        return (newArry);
    }

    private void write2File(String[] arr1) {
        Utils.deleteFiles(currentTableName);
        for (String anArr1 : arr1) {
            Utils.writeToFile(anArr1, currentTableName);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    private void formLayout() {
        rl.removeAllViews();
        for (int j = 0; j < tag1.length; j++) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            float pixels = this.getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lptv = new LinearLayout.LayoutParams((int) pixels * 130, LinearLayout.LayoutParams.WRAP_CONTENT);
            LinearLayout.LayoutParams lpev = new LinearLayout.LayoutParams((int) pixels * 220, LinearLayout.LayoutParams.WRAP_CONTENT);
            hz[j] = new LinearLayout(getApplicationContext());
            hz[j].setLayoutParams(lp);
            hz[j].setOrientation(LinearLayout.HORIZONTAL);
            hz[j].setGravity(Gravity.CENTER_VERTICAL);
            tv[j] = new TextView(getApplicationContext());
            tv[j].setLayoutParams(lptv);
            tv[j].setText(tag1[j]);
            tv[j].setTextColor(getResources().getColor(R.color.WordColor));
            tv[j].setTextSize(20);
            tv[j].setPadding(10, 0, 0, 0);
            et[j] = new EditText(getApplicationContext());
            et[j].setLayoutParams(lpev);
            et[j].setText(val[j]);
            et[j].setTextColor(getResources().getColor(R.color.BoxColor));
            et[j].setTextSize(20);
            hz[j].setPadding(0, 10, 0, 0);
            hz[j].setBackground(getDrawable(R.drawable.white_box_border));
            hz[j].addView(tv[j]);
            hz[j].addView(et[j]);
            rl.addView(hz[j]);
        }
    }

    private void open() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.DialogThemeColor);
        alertDialogBuilder.setMessage("Do you want save the changes");
        alertDialogBuilder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
            @SuppressLint("ShowToast")
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                for (int j = 0; j < et.length; j++) {
                    val[j] = String.valueOf(et[j].getText());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        mapTem.replace(tag1[j], val[j]);
                    } else {
                        mapTem.put(tag1[j], val[j]);
                    }
                }
                ServiceHolder.Companion.restoreFile(mapTem, currentTableName);
                new Thread() {
                    @Override
                    public void run() {
                        super.run();
                        write2File(merge2Arrays(tag1, val));
                    }
                }.run();
                Toast.makeText(getApplicationContext(), "Update is Completed", Toast.LENGTH_SHORT);
            }
        });

        alertDialogBuilder.setNegativeButton("NO", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                for (int j = 0; j < et.length; j++) {
                    et[j].setText(val[j]);
                }
            }
        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_HOME)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        currentTableName = adapterView.getItemAtPosition(i).toString();
        mapTem = ServiceHolder.Companion.getFile(currentTableName);
        if (mapTem != null) {
            tag1 = new String[mapTem.size()];
            val = new String[mapTem.size()];
            tv = new TextView[mapTem.size()];
            et = new EditText[mapTem.size()];
            hz = new LinearLayout[mapTem.size()];
            int count = 0;
            for (Map.Entry<String, String> entry : mapTem.entrySet()) {
                tag1[count] = entry.getKey();
                val[count] = entry.getValue();
                count++;
            }
            formLayout();
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent = new Intent(this, VendorOption.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}
