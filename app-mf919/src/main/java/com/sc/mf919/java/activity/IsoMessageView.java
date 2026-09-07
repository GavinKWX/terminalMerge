package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;import com.sc.mf919.R;

public class IsoMessageView extends AppCompatActivity
{
    private String TAG="IsoMsg";
    private String[] tag1;
    private String[] val;
    private LinearLayout rl;
    private TextView[] tv1;
    private TextView[] tv2;
    private String[] ISOm;

    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_isomessagedisplay);
        //mPrinter=ServiceHolder.MPrinterDev;
        rl = findViewById(R.id.LayoutISO_2);
        rl.setScrollContainer(true);
        /*String[] iso = GeneralMethod.readISOMsg();
        if(iso[0]!=null)
        {
            //GeneralMethod.debugLogPrint("h", "onCreate: " + iso[0]);
            init(iso);
            formLayout();
        }*/
    }

    private void init(String[] iso)
    {
        /*int len;
        int oriLen;
        GeneralMethod.DecomposedIsoMessage DM=new GeneralMethod.DecomposedIsoMessage();
        GeneralMethod.DecomposedIsoMessage DM1=new GeneralMethod.DecomposedIsoMessage();
        DM.setISOMsg(iso[0]);
        String[] field = DM.getAllField();
        len=field.length+4;
        oriLen=len;
        String[] field_r = new String[0];
        if(!iso[1].isEmpty())
        {
            DM1.setISOMsg(iso[1]);
            field_r=DM1.getAllField();
            len=oriLen+field_r.length+4;
        }
        //GeneralMethod.debugLogPrint(TAG, "init: " + len);
        ISOm=new String[len];
        ISOm[0]="SENT ISO MESSAGE:  ";
        ISOm[1]="TPDU: " + DM.getTPDU();
        ISOm[2]="MTI: " + DM.getMTI();
        ISOm[3]="BITMAP: " + DM.getBITMAP();
        System.arraycopy(field,0,ISOm,4,field.length);
        if(!iso[1].isEmpty())
        {
            ISOm[oriLen]="RECEIVED ISO MESSAGE:  ";
            ISOm[1+oriLen]="TPDU: " + DM1.getTPDU();
            ISOm[2+oriLen]="MTI: " + DM1.getMTI();
            ISOm[3+oriLen]="BITMAP: " + DM1.getBITMAP();
            System.arraycopy(field_r,0,ISOm,4+oriLen,field_r.length);
        }
        tag1= GeneralMethod.getTags(ISOm);
        val= GeneralMethod.getValue(ISOm);
        for(int j=0;j<tag1.length;j++)
        {
            if(tag1[j].equals("DE55:"))
            {
                //val[j]= StringArray2String(GeneralMethod.decomposedField55(val[j]));
                ISOm[j]="DE55:\n"+val[j];
            }
        }
        tv1 = new TextView[tag1.length];
        tv2 = new TextView[val.length];*/
    }

    @SuppressLint("NewApi")
    private void formLayout()
    {
        for(int j=0;j<tag1.length;j++) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            tv1[j] = new TextView(getApplicationContext());
            tv1[j].setLayoutParams(lp);// Apply the layout parameters to TextView widget
            tv1[j].setText(tag1[j]); // Set text to display in TextView
            tv1[j].setTextColor(getResources().getColor(R.color.WordColor));
            tv1[j].setTextSize(18);
            tv1[j].setPadding(10, 0, 10, 0);
            if(tag1[j].contains("ISO MESSAGE"))
            {
                tv1[j].setTextSize(22);
                tv1[j].setGravity(Gravity.CENTER_HORIZONTAL);
            }
            //tv1[j].setBackgroundColor(Color.parseColor("#e4e2e2"));
            tv2[j] = new TextView(getApplicationContext());
            tv2[j].setLayoutParams(lp);
            tv2[j].setText(val[j]);
            tv2[j].setTextColor(getResources().getColor(R.color.BoxColor));
            tv2[j].setTextSize(22);
            tv2[j].setPadding(10, 0, 10, 0);
            //tv2[j].setBackgroundColor(Color.parseColor("#ffffff"));
            rl.addView(tv1[j]);
            rl.addView(tv2[j]);
        }
        rl.addView(new TextView(getApplicationContext()));
    }

    boolean isFinish;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if ((keyCode == KeyEvent.KEYCODE_HOME))
        {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void printISOMsg(View view)
    {
        //print();
    }

    @Override
    public void onBackPressed()
    {
        super.onBackPressed();
        Intent intent = new Intent(this, VendorOption.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }
}