package com.sc.mf919.java.activity;

public interface PinPadListener {
    void onReadPinSuccess(String pinBlock);

    void onReadPinCancel();

    void onReadingPin(int len, String pin);
}
