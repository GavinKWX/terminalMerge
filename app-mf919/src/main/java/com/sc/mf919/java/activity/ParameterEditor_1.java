package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.sc.mf919.R;
import com.sc.mf919.java.activity.ParameterValueDisplay;
import com.sc.mf919.java.activity.Utils;
import com.sc.mf919.kotlin.activity.AdminActivity;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ParameterEditor_1 extends AppCompatActivity
{
    private static final String TAG = "ParameterEditor";
    private String[] tag1;
    private String[] val;
    private LinearLayout rl;
    private EditText edt;
    Map<String , String> merchantInfo;
    Map<String , String> tms;
    private int lenMerchantInfo=0;
    private AlertDialog alertDialog;


    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parameterview_screen_1);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int width = displayMetrics.widthPixels;
        rl = findViewById(R.id.layout_1PE);
        rl.setScrollContainer(true);

        merchantInfo = ServiceHolder.Companion.getMap("merchantInfo.txt");
        tms = ServiceHolder.Companion.getMap("tms.txt");
        if(merchantInfo!=null)
        {
            int count=0;
            int len = merchantInfo.size();
            tag1=new String[len];
            val=new String[len];
            if(tms!=null)
            {
                lenMerchantInfo= tms.size();
                len=len + tms.size();
                tag1=new String[len];
                val=new String[len];
                for (Map.Entry<String,String> entry : tms.entrySet())
                {
                    tag1[count]=entry.getKey();
                    val[count]=entry.getValue();
                    count++;
                }
            }
            for (Map.Entry<String,String> entry : merchantInfo.entrySet())
            {
                tag1[count]=entry.getKey();
                val[count]=entry.getValue();
                count++;
            }
            formLayout();
        }
        else
        {
            lenMerchantInfo=0;
            if(tms!=null)
            {
                int count=0;
                int len= tms.size();
                tag1=new String[len];
                val=new String[len];
                for (Map.Entry<String,String> entry : tms.entrySet())
                {
                    tag1[count]=entry.getKey();
                    val[count]=entry.getValue();
                    count++;
                }
                formLayout();
            }
        }

    }

    private String [] getTags(String[] value)
    {
        String[] tags = new String[value.length];
        for(int j=0;j<value.length;j++)
        {
            int index =value[j].indexOf("=");
            tags[j]=value[j].substring(0,index);
            //GeneralMethod.debugLogPrint(TAG, "Tag = " + tags[j]);
        }
        return(tags);
    }

    private String [] getValue(String[] value)
    {
        String[] val = new String[value.length];
        for(int j=0;j<value.length;j++)
        {
            int index =value[j].indexOf("=");
            val[j]=value[j].substring(index+1);
            //GeneralMethod.debugLogPrint(TAG, "Tag = " + val[j]);
        }
        return(val);
    }

    private String[] merge2Arrays(String[] arr1, String[] arr2)
    {
        String[] newArry = new String[arr1.length];
        for(int j=0;j<arr1.length;j++)
        {
            newArry[j]=arr1[j] + "=" +arr2[j];
        }
        return (newArry);
    }

    private void write2File(String[] arr1)
    {
        new Thread()
        {
            @Override
            public void run()
            {
                if(lenMerchantInfo>0)
                {
                    String[] a1 = new String[merchantInfo.size()];
                    String[] a2 = new String[tms.size()];
                    System.arraycopy(arr1,0,a1,0,a1.length);
                    System.arraycopy(arr1,a1.length,a2,0,a2.length);
                    if(a1.length>0)
                    {
                        Utils.write2File(a1, "tms.txt");
                    }
                    if(a2.length>0)
                    {
                        Utils.write2File(arr1, "tms.txt");
                    }
                }
                else
                {
                    if(arr1.length>0)
                    {
                        Utils.write2File(arr1, "tms.txt");
                    }
                }
            }
        }.start();
    }

    private void formLayout()
    {
        rl.removeAllViews();
        for(int j=0;j<tag1.length;j++)
        {
            ParameterValueDisplay pd = new ParameterValueDisplay(this,j+1,tag1[j],val[j]);
            View _view = pd.getView();
            //_view.setOnClickListener(onClickFunction);
            rl.addView(pd.getView());
        }
    }

     private View.OnClickListener onClickFunction = new View.OnClickListener()
    {

        @Override
        public void onClick(View view)
        {
            int id = view.getId();
            editDialog(id);
        }
    };

    private void save()
    {
        for(int j=0;j<val.length;j++)
        {
            if(j<lenMerchantInfo)
            {
                tms.replace(tag1[j], val[j]);
            }
            else
            {
                merchantInfo.replace(tag1[j], val[j]);
            }
        }
        ServiceHolder.Companion.restoreFile(merchantInfo,"merchantInfo.txt");
        ServiceHolder.Companion.restoreFile(tms,"tms.txt");
        new Thread()
        {
            @Override
            public void run()
            {
                super.run();
                write2File(merge2Arrays(tag1,val));
            }
        }.start();

        formLayout();
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

    @Override
    public void onBackPressed()
    {
        super.onBackPressed();
        Intent intent = new Intent(this, AdminActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void editDialog(final int id)
    {
        DialogInterface.OnKeyListener keyListener = new DialogInterface.OnKeyListener()
        {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent KEvent)
            {
                return keyCode == KeyEvent.KEYCODE_HOME;
            }
        };
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this,R.style.DialogThemeColor);
        alertDialogBuilder.setOnKeyListener(keyListener);
        LayoutInflater inflater = this.getLayoutInflater();
        @SuppressLint("InflateParams") View dialogView = inflater.inflate(R.layout.activity_value_edit, null);
        ((TextView) dialogView.findViewById(R.id.textView1)).setText("Enter your " + tag1[id-1]);
        edt=dialogView.findViewById(R.id.editTextDialogUserInput);
        edt.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edt.setText(val[id-1]);
        edt.setOnEditorActionListener(new TextView.OnEditorActionListener()
        {
            @Override
            public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent)
            {
                //Utils.debugLogPrint(TAG, "onEditorAction: " + i +"  " + keyEvent.getAction());
                boolean handled = false;
                if (i == EditorInfo.IME_ACTION_DONE)
                {
                   val[id-1]=edt.getText().toString();
                   save();
                   alertDialog.dismiss();
                   handled = true;
                }
                return handled;
            }
        });
        alertDialogBuilder.setView(dialogView);
        alertDialogBuilder.setCancelable(true);
        alertDialog = alertDialogBuilder.create();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(Objects.requireNonNull(alertDialog.getWindow()).getAttributes());
        lp.width = 600;
        alertDialog.getWindow().setAttributes(lp);
        alertDialog.show();
    }
}
