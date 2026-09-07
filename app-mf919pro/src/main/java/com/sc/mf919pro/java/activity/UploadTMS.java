package com.sc.mf919pro.java.activity;

import com.sc.mf919pro.kotlin.activity.AppServices;
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;

public class UploadTMS {
    private static UploadTMS uploadTms = null;

    public static UploadTMS getInstance() {
        if (uploadTms == null) {
            uploadTms = new UploadTMS();
        }
        return uploadTms;
    }

    UploadTMS() {
    }

    public void addReceipt(String body) {
        ReceiptUploadRepo.Companion.insertToDb(ServiceHolder.Companion.getContext(), body);
        AppServices.Companion.receiptUploadToTms(ServiceHolder.Companion.getContext());
    }
}
