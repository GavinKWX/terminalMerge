package com.sc.mf919pro.java.activity;

public interface PinPadListener {
    void onReadPinSuccess(String pinBlock);

    void onReadPinCancel();

    /*
     * OK pressed with an empty PIN buffer while BYPASS_PIN is off. Terminates the transaction
     * like onReadPinCancel. Both set resp code ZQ "PIN Not Entered", matching MF919.
     * */
    void onReadPinNotEntered();

    void onReadingPin(int len, String pin);
}
