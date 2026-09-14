package com.sc.mf919pro.java.activity;

import com.sc.mf919pro.kotlin.activity.AppServices;
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;

/**
 * Hands a receipt to the upload pipeline. Nothing more: the row goes into ReceiptUploadRepo and
 * AppServices drives the retry/upload, so this class holds no state and does no I/O.
 *
 * It used to own a second pipeline -- a receipt.txt file and its own HTTP POST loop. That became
 * unreachable when addReceipt moved to the repo, and was removed on 2026-09-14. See
 * docs/merge-audit-mf919.md item 58.
 */
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
