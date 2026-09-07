package com.sc.mf919pro.java.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sc.mf919pro.R;

public class Keypad extends LinearLayout implements View.OnClickListener {
    private onKeypadEventListener mListener;
    private Button button_0;
    private Button button_1;
    private Button button_2;
    private Button button_3;
    private Button button_4;
    private Button button_5;
    private Button button_6;
    private Button button_7;
    private Button button_8;
    private Button button_9;
    private ImageButton button_del;
    private Button button_dot;
    private LinearLayout button_OK;
    private LinearLayout button_back;

    // Passin from Activity use keypad
    private String msg = "";
    private boolean isAmount = false;
    private int maxLen = 12;
    private TextView textView;

    public Keypad(Context context) {
        super(context);
        init(context);
    }

    public Keypad(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        init(context);
    }

    public Keypad(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.keypad, this, true);
        button_0 = findViewById(R.id.button_0);
        button_1 = findViewById(R.id.button_1);
        button_2 = findViewById(R.id.button_2);
        button_3 = findViewById(R.id.button_3);
        button_4 = findViewById(R.id.button_4);
        button_5 = findViewById(R.id.button_5);
        button_6 = findViewById(R.id.button_6);
        button_7 = findViewById(R.id.button_7);
        button_8 = findViewById(R.id.button_8);
        button_9 = findViewById(R.id.button_9);
        button_del = findViewById(R.id.button_delete_icon);
        button_dot = findViewById(R.id.button_dot);
        button_OK = findViewById(R.id.button_OK);
        button_back = findViewById(R.id.button_back_icon);

        button_0.setOnClickListener(this);
        button_1.setOnClickListener(this);
        button_2.setOnClickListener(this);
        button_3.setOnClickListener(this);
        button_4.setOnClickListener(this);
        button_5.setOnClickListener(this);
        button_6.setOnClickListener(this);
        button_7.setOnClickListener(this);
        button_8.setOnClickListener(this);
        button_9.setOnClickListener(this);
        button_del.setOnClickListener(this);
        button_dot.setOnClickListener(this);
        button_OK.setOnClickListener(this);
        button_back.setOnClickListener(this);

        msg = "";
        isAmount = false;
        maxLen = 12;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View view) {
        int id = view.getId();
        if(id == R.id.button_0){
            insertValue("0");
        } else if(id == R.id.button_1){
            insertValue("1");
        } else if(id == R.id.button_2){
            insertValue("2");
        } else if(id == R.id.button_3){
            insertValue("3");
        } else if(id == R.id.button_4){
            insertValue("4");
        } else if(id == R.id.button_5){
            insertValue("5");
        } else if(id == R.id.button_6){
            insertValue("6");
        } else if(id == R.id.button_7){
            insertValue("7");
        } else if(id == R.id.button_8){
            insertValue("8");
        } else if(id == R.id.button_9){
            insertValue("9");
        } else if(id == R.id.button_delete_icon){
            deleteValue();
        } else if(id == R.id.button_dot){
            if (!isAmount) {
                insertValue(".");
            }
        } else if(id == R.id.button_OK){
            mListener.onOK(true, msg);
        } else if(id == R.id.button_back_icon){
            mListener.onOK(true, null);
        } else {
            visibleKeypad(true);
        }
    }

    private void insertValue(String value) {
        String previousText = msg;
        if (previousText.length() == maxLen) {
            return;
        }

        if (isAmount) {
            previousText = previousText.replace(".", "");
            if (previousText.charAt(0) == '0') {
                previousText = previousText.substring(1);
            }
            String newText = previousText + value;
            StringBuilder newText1 = new StringBuilder(newText);
            newText1.insert(newText1.length() - 2, ".");
            newText = newText1.toString();
            msg = newText;
        } else {
            msg = previousText.concat(value);
        }

        if (textView == null) {
            mListener.onOK(false, msg);
        } else {
            textView.setText(msg);
        }
    }

    private void deleteValue() {
        String previousText = msg.replace(" ", "");
        if (isAmount) {
            previousText = previousText.replace(".", "");
            if (previousText.length() <= 3) {
                previousText = previousText.substring(0, previousText.length() - 1);
                previousText = "0" + previousText;
            } else {
                previousText = previousText.substring(0, previousText.length() - 1);
            }
            StringBuilder newText1 = new StringBuilder(previousText);
            newText1.insert(newText1.length() - 2, ".");
            msg = newText1.toString();
        } else {
            int len = previousText.length();
            if (len != 0) {
                previousText = previousText.substring(0, len - 1);
            }
            msg = previousText;
        }
        if (textView == null) {
            mListener.onOK(false, msg);
        } else {
            textView.setText(msg);
        }
    }

    private void visibleKeypad(Boolean visibility) {
        if (!visibility) {
            button_0.setVisibility(View.INVISIBLE);
            button_1.setVisibility(View.INVISIBLE);
            button_2.setVisibility(View.INVISIBLE);
            button_3.setVisibility(View.INVISIBLE);
            button_4.setVisibility(View.INVISIBLE);
            button_5.setVisibility(View.INVISIBLE);
            button_6.setVisibility(View.INVISIBLE);
            button_7.setVisibility(View.INVISIBLE);
            button_8.setVisibility(View.INVISIBLE);
            button_9.setVisibility(View.INVISIBLE);
            button_del.setVisibility(View.INVISIBLE);
            button_dot.setVisibility(View.INVISIBLE);
            button_OK.setVisibility(View.INVISIBLE);
            button_back.setVisibility(View.INVISIBLE);
        } else {
            button_0.setVisibility(View.VISIBLE);
            button_1.setVisibility(View.VISIBLE);
            button_2.setVisibility(View.VISIBLE);
            button_3.setVisibility(View.VISIBLE);
            button_4.setVisibility(View.VISIBLE);
            button_5.setVisibility(View.VISIBLE);
            button_6.setVisibility(View.VISIBLE);
            button_7.setVisibility(View.VISIBLE);
            button_8.setVisibility(View.VISIBLE);
            button_9.setVisibility(View.VISIBLE);
            button_del.setVisibility(View.VISIBLE);
            button_dot.setVisibility(View.VISIBLE);
            button_OK.setVisibility(View.VISIBLE);
            button_back.setVisibility(View.VISIBLE);

        }
    }

    public void registerOnOKEventListener(onKeypadEventListener mListener) {
        this.mListener = mListener;
    }

    public void hideDotForPinPad() {
        button_dot.setVisibility(View.INVISIBLE);
    }

    public void setFilter(TextView tv, boolean _isAmount, int _maxLen) {
        isAmount = _isAmount;
        maxLen = _maxLen;
        if (isAmount) {
            maxLen++;
            msg = "0.00";
        }
        textView = tv;
        msg = textView.getText().toString();
        textView.setOnClickListener(this);
    }

    public void setFilter(boolean _isAmount, int _maxLen) {
        isAmount = _isAmount;
        maxLen = _maxLen;
        if (isAmount) {
            maxLen++;
            msg = "0.00";
        }
    }

    public int getTextViewID() {
        if (textView != null) {
            return textView.getId();
        } else {
            return 0;
        }
    }
}
