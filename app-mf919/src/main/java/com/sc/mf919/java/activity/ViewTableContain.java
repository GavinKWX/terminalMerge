package com.sc.mf919.java.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.sc.mf919.R;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ViewTableContain extends Activity implements AdapterView.OnItemSelectedListener
{
    private final static String TAG = "ViewTable";
    private LinearLayout LL;
    private String[] ColumnName;
    private EditText[] ET;
    private DataAdapter DAs;
    private Cursor mCursor;
    private String currentTableName;
    private String DB_PATH;
    private int currentID;

    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewtable);
        String value = getIntent().getStringExtra("dbName");
        if(value==null){value=Global.filesInfo.SystemTrace;}
        DAs = new DataAdapter(ServiceHolder.Companion.getContext(), value);
        DAs.createDatabase();
        DAs.open();
        String[] tableNames = removeKeyTable(DAs.listOfTable());
        LL = findViewById(R.id.LayoutVTC);
        Spinner spinner = findViewById(R.id.tablename);
        spinner.setOnItemSelectedListener(this);
        List<String> categories = new ArrayList<>();
        Collections.addAll(categories, tableNames);
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, R.layout.activity_spinneritem, categories);
        dataAdapter.setDropDownViewResource(R.layout.activity_spinneritem);
        spinner.setAdapter(dataAdapter);
        DAs.close();
    }

    public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
    {
        currentTableName = parent.getItemAtPosition(position).toString();
        getData();
        if(mCursor !=null)
        {
            viewData();
        }
    }

    public void onNothingSelected(AdapterView<?> arg0)
    {
        // TODO Auto-generated method stub
    }

    public void move2Next(View view)
    {
        boolean isSucc = mCursor.moveToNext();
        if(isSucc)
        {
            viewData();
        }
        else
        {
            mCursor.moveToPrevious();
            Toast.makeText(getApplicationContext(),"At Last Record", Toast.LENGTH_SHORT).show();
        }
    }

    public void move2Previous(View view)
    {
        boolean isSucc = mCursor.moveToPrevious();
        if(isSucc)
        {
            viewData();
        }
        else
        {
            mCursor.moveToNext();
            Toast.makeText(getApplicationContext(),"At First Record", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("Range")
    private void viewData()
    {
        ColumnName= mCursor.getColumnNames();
        Utils.debugLogPrint(TAG, "viewData: " + mCursor.getCount());
        LL.removeAllViews();
        Utils.debugLogPrint(TAG, "onItemSelected: " + ColumnName.length);
        TextView[] TV = new TextView[ColumnName.length];
        ET = new EditText[ColumnName.length];
        for(int j=0;j<ColumnName.length;j++)
        {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            TV[j] = new TextView(getApplicationContext());
            TV[j].setText(ColumnName[j]);
            TV[j].setTextColor(getResources().getColor(R.color.colorBlack));
            TV[j].setTextSize(20);
            TV[j].setPadding(10, 0, 0, 0);
            TV[j].setBackgroundColor(getResources().getColor(R.color.colorGrey));
            ET[j] = new EditText(getApplicationContext());
            ET[j].setLayoutParams(lp);
            String temp;
            try
            {
                temp= mCursor.getString(mCursor.getColumnIndex(ColumnName[j]));
            }
            catch(Exception e)
            {
                Utils.printErrorLog(TAG,"viewData",e.getMessage());
                Utils.debugLogPrint(TAG, "onItemSelected: " + e.getMessage());
                temp="";
            }
            if(ColumnName[j].equals("ID"))
            {
                if(!temp.equals(""))
                {
                    currentID= Integer.parseInt(temp);
                }
            }
            ET[j].setText(temp);
            ET[j].setTextColor(getResources().getColor(R.color.BoxColor));
            ET[j].setTextSize(22);
            ET[j].setPadding(10, 0, 10, 0);
            LL.addView(TV[j]);
            LL.addView(ET[j]);
        }
    }

    public void updateData(View view)
    {
        DAs.open();
        for(int j=0;j<ColumnName.length;j++)
        {
            if(!ColumnName[j].equals("ID"))
            {
                DAs.updateFile(ColumnName[j],(ET[j].getText()).toString(),currentTableName,currentID);
            }
        }
        DAs.close();
        getData();
        if(mCursor !=null)
        {
            viewData();
        }
    }

    public void deleteData(View view)
    {
        if(currentID!=0)
        {
            DAs.open();
            DAs.deleteARow(currentTableName,currentID);
            DAs.close();
            getData();
            if(mCursor !=null)
            {
                viewData();
            }
        }
        else
        {
            Toast.makeText(this,"Cannot delete", Toast.LENGTH_SHORT).show();
        }
    }

    private void getData()
    {
        DAs.open();
        try
        {
            mCursor = DAs.getData(currentTableName);
        }
        catch (Exception e)
        {
            Utils.printErrorLog(TAG,"getData",e.getMessage());
            Utils.debugLogPrint(TAG, "getData: " + e.getMessage());
            mCursor =null;
        }
        DAs.close();
    }

    private String[] removeKeyTable(String[] table)
    {
        String[] temp = new String[table.length];
        int count=0;
        for (String aTable : table)
        {
            if (!aTable.equals("Key")) {
                temp[count] = aTable;
                count++;
            }
        }
        String[] ret = new String[count];
        System.arraycopy(temp,0,ret,0,count);
        return(ret);
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
        Intent intent = new Intent(this, VendorOption.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}
