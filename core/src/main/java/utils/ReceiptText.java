package utils;

import timber.log.Timber;

/**
 * How a transaction gets described on a printed receipt: the cardholder verification result and
 * the entry method. Moved out of both apps' Utils in tranche 4 of the Utils slice.
 *
 * These decide what the customer's copy says about whether a PIN was checked and whether a
 * signature is needed, so the strings are the contract -- including the embedded newlines and the
 * signature rule, which the printer lays out verbatim.
 */
public class ReceiptText {

    private static final String TAG = "ReceiptText";

    /**
     * Maps a CVM Results value (tag 9F34) to the verification lines on the receipt.
     *
     * Reads the SECOND character of the hex string, which is the low nibble of the first CVM
     * Results byte -- the performed CVM method. {@code entryMode} is accepted and ignored: the
     * branch that used it is commented out in both apps and every caller passes an empty string.
     */
    public static String CVMAnalysis(String CVM, String entryMode) {
        String returnValue;
        switch (CVM.charAt(1)) {
            case '1':
                //if (entryMode.equals(TransFieldConstant.payMethods.sRF)) {
                //    returnValue = "NO PIN REQUIRED\nNO SIGNATURE REQUIRED";
                //} else {
                returnValue = "PIN VERIFIED\nNO SIGNATURE REQUIRED";
                //}
                break;
            case '2':
                returnValue = "PIN VERIFIED\nNO SIGNATURE REQUIRED";
                break;
            case '3':
            case '5':
                returnValue = "PIN VERIFIED\n\n\n______________________________\nSign";
                break;
            case 'E':
                returnValue = "\n\n\n______________________________\nSign";
                break;
            default:
                returnValue = "NO PIN REQUIRED\nNO SIGNATURE REQUIRED";
                break;
        }
        return returnValue;
    }

    /**
     * Maps a POS entry mode (field 22) to the entry method printed on the receipt.
     *
     * Anything unrecognised prints as "Manual", which is why a new entry code shows up as a
     * manual entry rather than as an error.
     */
    public static String getPayMeythod(String POSEntryCode) {
        Timber.tag(TAG).d("getPayMeythod: %s", POSEntryCode);
        String returnVale = "Manual";
        switch (POSEntryCode) {
            case ("0260"):
            case ("0261"):
            case ("0059"):
            case ("0051"):
                returnVale = "Contact";
                break;
            case ("0270"):
            case ("0271"):
            case ("0081"):
            case ("0071"):
                returnVale = "Contactless";
                break;
            case ("0021"):
            case ("0801"):
                returnVale = "MagStripe";
                break;
        }
        return returnVale;
    }
}
