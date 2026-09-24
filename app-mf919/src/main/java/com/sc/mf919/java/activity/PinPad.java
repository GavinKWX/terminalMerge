package com.sc.mf919.java.activity;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;

import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sc.mf919.R;
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig;
import com.sc.mf919.kotlin.helper_common.MfHelper;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

import java.util.Random;

public class PinPad  extends AppCompatActivity {
    private Context mContext;
    private AlertDialog.Builder alertDialogBuilder;
    private AlertDialog alertDialog;
    private LinearLayout pinString;
    private Keypad keypad;
    private String CardNumber = "0000000000000000";
    private PinPadListener pinPadListener;
    private ISO type = ISO.ISO_0;

    public enum ISO {
        ISO_0,
        ISO_1,
        ISO_2,
        ISO_3;
    }


    PinPad(Context _mContext) {
        this.mContext = _mContext;
    }

    public PinPad(Context _mContext, String _cardNo, ISO _type) {
        this.mContext = _mContext;
        this.CardNumber = _cardNo;
        this.type = _type;
    }

    public void getPin(PinPadListener _pinPadListener) {
        pinPadListener = _pinPadListener;
        pinDialog();
    }



    public void pinDialog() {
        DialogInterface.OnKeyListener keyListener = new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent KEvent) {
                return keyCode == KeyEvent.KEYCODE_BACK;
            }
            /*
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent KEvent) {
                return keyCode == KeyEvent.KEYCODE_HOME;
            }
            * */
        };

        alertDialogBuilder = new AlertDialog.Builder(this.mContext, R.style.myFullscreenAlertDialogStyle);
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        @SuppressLint("InflateParams") View dialogView = inflater.inflate(R.layout.activity_pinpad, null);
        keypad = dialogView.findViewById(R.id.keypad_ll);
        keypad.registerOnOKEventListener(listener);
        pinString = dialogView.findViewById(R.id.pinMsg);
        alertDialogBuilder.setView(dialogView);
        alertDialogBuilder.setOnKeyListener(keyListener);
        alertDialog = alertDialogBuilder.create();
        alertDialog.show();

        //TODO Dynamic Layout For Small Terminal
        if (MfHelper.isSmallTerminal()) {
            TextView titleMsg = dialogView.findViewById(R.id.titleMsg);
            ConstraintLayout.LayoutParams paramsTitleMsg = (ConstraintLayout.LayoutParams) titleMsg.getLayoutParams();
            paramsTitleMsg.topMargin = 15;
            titleMsg.setLayoutParams(paramsTitleMsg);

            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) keypad.getLayoutParams();
            params.topMargin = 0;
            keypad.setLayoutParams(params);

            keypad.post(() -> {
                DisplayMetrics displayMetrics1 = keypad.getResources().getDisplayMetrics();
                int screenHeightPx1 = displayMetrics1.heightPixels;

                int targetHeight = (int) (screenHeightPx1 * 0.70f);
                ViewGroup.LayoutParams paramsKeypad = keypad.getLayoutParams();
                paramsKeypad.height = targetHeight;
                keypad.setLayoutParams(paramsKeypad);
            });
        }
        //TODO Dynamic Layout For Small Terminal
    }

    onKeypadEventListener listener = new onKeypadEventListener() {
        @Override
        public void onOK(boolean isOK, String msg) {
            if (isOK) {
                if (msg != null) {
                    if (msg.isEmpty()) {
                        DbModelTerminalConfig terminalConfig = ServiceHolder.getTerminalConfig();
                        boolean isBypassPIN = DbModelTerminalConfig.Companion.getBooleanValue(terminalConfig, "BYPASS_PIN");
                        if (isBypassPIN) {
                            pinPadListener.onReadPinSuccess("");
                        } else {
                            pinPadListener.onReadPinCancel();
                        }
                    } else {
                        pinPadListener.onReadPinSuccess(PinBlockEncode(msg, CardNumber, type.ordinal()).toUpperCase());
                    }
                } else {
                    pinPadListener.onReadPinCancel();
                }
                if (alertDialog.isShowing()) {
                    alertDialog.dismiss();
                }
            } else {
                pinPadListener.onReadingPin(msg.length(), paddingWith("", "*", msg.length(), false));

                //TODO Dynamic Layout For Small Terminal
                ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) pinString.getLayoutParams();
                if (msg.isEmpty()) {
                    int marginDp = (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            25,
                            mContext.getResources().getDisplayMetrics()
                    );
                    params.topMargin = marginDp;
                } else {
                    params.topMargin = 0;
                }
                pinString.setLayoutParams(params);
                //TODO Dynamic Layout For Small Terminal

                pinString.removeAllViews();
                for (int i = 0; i < msg.length(); i++) {
                    LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    View child = inflater.inflate(R.layout.pinbox, null);
                    pinString.addView(child);
                }
            }
        }
    };

    private String PinBlockEncode(String pin, String pan, int Mode) {
        pin = Mode + Integer.toString(pin.length(), 16) + pin;
        //Utils.debugLogPrint(TAG, "encyPinBlock: " + pin);
        String returnVal = "";
        String substring = pan.substring(pan.length() - 13, pan.length() - 1);
        switch (Mode) {
            case 0:
                pan = substring;
                pin = paddingWith(pin, "F", 16, true);
                //Utils.debugLogPrint(TAG, "encyPinBlock: " + pin);
                pan = paddingWith(pan, "0", 16, false);
                //Utils.debugLogPrint(TAG, "encyPinBlock: " + pan);
                byte[] comb1 = hexStringToByte(pin);
                byte[] comb2 = hexStringToByte(pan);
                int result;
                for (int j = 0; j < comb1.length; j++) {
                    result = Byte2Int(comb1[j]) ^ Byte2Int(comb2[j]);
                    returnVal = returnVal.concat(zeroPadding(Integer.toHexString(result), 2));
                }
                break;
            case 1:
                while (pin.length() == 16) {
                    Random rand = new Random();
                    int n = rand.nextInt(16);
                    pin = pin.concat(Integer.toHexString(n - 1));
                }
                returnVal = pin;
                break;
            case 2:
                pin = paddingWith(pin, "F", 16, true);
                returnVal = pin;
                break;
            case 3:
                while (pin.length() == 16) {
                    Random rand = new Random();
                    int n = rand.nextInt(16);
                    pin = pin.concat(Integer.toHexString(n - 1));
                }
                pan = substring;
                pan = paddingWith(pan, "0", 16, false);
                byte[] compB11 = hexStringToByte(pin);
                byte[] compB12 = hexStringToByte(pan);
                int result1;
                for (int j = 0; j < compB11.length; j++) {
                    result1 = Byte2Int(compB11[j]) ^ Byte2Int(compB12[j]);
                    returnVal = returnVal.concat(zeroPadding(Integer.toHexString(result1), 2));
                }
        }
        return returnVal;
    }

    public String pinBlockDecode(String pin, String pan) {
        int Mode = Integer.parseInt(String.valueOf(pin.charAt(0)));
        int len = Integer.parseInt(String.valueOf(pin.charAt(1)), 16);
        String returnVal = "";
        final String substring = pan.substring(pan.length() - 13, pan.length() - 1);
        switch (Mode) {
            case 0:
                pan = substring;
                pan = paddingWith(pan, "0", 16, false);
                byte[] compB1 = hexStringToByte(pin);
                byte[] compB2 = hexStringToByte(pan);
                int result;
                for (int j = 0; j < compB1.length; j++) {
                    result = Byte2Int(compB1[j]) ^ Byte2Int(compB2[j]);
                    returnVal = returnVal.concat(Integer.toHexString(result));
                }
                returnVal = returnVal.substring(2, len + 2);
                break;
            case 1:
                returnVal = returnVal.substring(2, len + 2);
                break;
            case 2:
                returnVal = removeCarNumChar(pin.substring(2));
                break;
            case 3:
                pan = substring;
                pan = paddingWith(pan, "0", 16, false);
                byte[] compb11 = hexStringToByte(pin);
                byte[] compb12 = hexStringToByte(pan);
                int result1;
                for (int j = 0; j < compb11.length; j++) {
                    result1 = Byte2Int(compb11[j]) ^ Byte2Int(compb12[j]);
                    returnVal = returnVal.concat(zeroPadding(Integer.toHexString(result1), 2));
                }
                returnVal = returnVal.substring(2, len + 2);
        }
        //Log.d(TAG, "pinBlockDecode: " + returnVal);
        return returnVal;
    }

    private String paddingWith(String pin, String f, int length, boolean end) {
        if (pin.length() < length) {
            StringBuilder pinBuilder = new StringBuilder(pin);
            while (pinBuilder.length() != length) {
                if (end) {
                    pinBuilder.append(f);
                } else {
                    pinBuilder.insert(0, f);
                }
            }
            pin = pinBuilder.toString();
        }
        return pin;
    }

    public static String removeCarNumChar(String CardNu) {
        if (CardNu.contains("F")) {
            int index = CardNu.indexOf("F");
            CardNu = CardNu.substring(0, index);
        }
        return CardNu;
    }

    private int Byte2Int(byte b) {
        int a = b;
        if (a < 0) {
            a = 256 + a;
        }
        return (a);
    }

    private String zeroPadding(String s, int LengthOfString) {
        return (paddingWith(s, "0", LengthOfString, false));
    }

    private String zeroPadding(String s, int LengthOfString, boolean end) {
        return (paddingWith(s, "0", LengthOfString, end));
    }

    private byte[] hexStringToByte(String hexString) {
        int len = hexString.length();
        if (len % 2 != 0) {
            hexString = zeroPadding(hexString, len + 1, false);
            len++;
        }

        byte[] output = new byte[len / 2];
        int temps;

        for (int j = 0; j < (len / 2); j++) {
            temps = Integer.parseInt(hexString.substring(0, 2), 16);
            if (hexString.length() > 2) {
                hexString = hexString.substring(2);
            }
            output[j] = (byte) temps;
        }
        return (output);
    }
}

