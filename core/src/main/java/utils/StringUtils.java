package utils;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;

public class StringUtils {

    /**
     * Pad `value` with `f` to `length`, at the end when `end` is true, otherwise at the front.
     * Returns the input untouched when it is already at least that long -- it never truncates.
     * Moved from each app's Utils so crypto.Encryption could come to :core with it.
     */
    public static String paddingWith(String value, String f, int length, boolean end) {
        if (value.length() < length) {
            StringBuilder b = new StringBuilder(value);
            while (b.length() != length) {
                if (end) {
                    b.append(f);
                } else {
                    b.insert(0, f);
                }
            }
            value = b.toString();
        }
        return value;
    }

    private static String transformAmount(String amount) {
        try {
            long lAmount = Long.parseLong(amount);
            amount = String.valueOf(lAmount * 100);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return amount;
    }

    @SafeVarargs
    public static <T> ArrayList<T> createArrayList(T... elements) {
        return new ArrayList<T>(Arrays.asList(elements));
    }

    public static String getServiceCodeFromTrack2(String track2) {
        String ServiceCode24 = "";
        if (TextUtils.isEmpty(track2)){
            return "";
        }
        for (int i = 0; i < track2.length(); i++) {
            if (track2.charAt(i) == '=' || track2.charAt(i) == 'd' || track2.charAt(i) == 'D') {
                ServiceCode24 = track2.substring(i + 5, i + 5 + 3);
                break;
            }
        }
        return ServiceCode24;
    }

    // -------------------------------------------------------------------------------------
    // Utils slice, tranche 2: text shaping and masking.
    //
    // Moved verbatim from both apps' Utils, which held identical copies. Several of these
    // slice with unguarded substring() calls and will throw on short input -- that is
    // existing behaviour every caller already relies on, so it is pinned in
    // StringUtilsTextTest rather than "fixed" here.
    // -------------------------------------------------------------------------------------

    public static String blankSpace(int i) {
        String temp = "";
        int loop = 1;
        while (loop < i) {
            temp = temp.concat(" ");
            loop++;
        }
        return temp;
    }

    public static String spaceBtwNoChar(String value, int space) {
        String temp = "";
        while (value.length() > space) {
            temp = temp.concat(value.substring(0, 4).concat(" "));
            value = value.substring(4);
        }
        temp = temp.concat(value);
        return (temp);
    }

    public static String symbolString(String Symbol, int Len) {
        StringBuilder returnVal = new StringBuilder();
        for (int j = 0; j < Len; j++) {
            returnVal.append(Symbol);
        }
        return returnVal.toString();
    }

    public static String maskString(String value, int clearTextRemain) {
        String resultString = value;
        int stringLength = value.length();
        if(stringLength > clearTextRemain){
            resultString= symbolString("*", stringLength - clearTextRemain) + value.substring(stringLength - clearTextRemain);
        }
        return resultString;
    }

    public static String mask_pan(String value) {
        value = value.substring(0, 6) + symbolString("0", value.length() - 10) + value.substring(value.length() - 4);
        value = paddingWith(value, "0", 20, true);
        return (value);
    }

    public static String removeCarNumChar(String CardNu) {
        if (CardNu.contains("F")) {
            int index = CardNu.indexOf("F");
            CardNu = CardNu.substring(0, index);

        }
        return CardNu;
    }

    public static String removeWhiteSpace(String InputText) {
        InputText = InputText.trim();
        InputText = InputText.replace("\t", "");
        return InputText.replace(" ", "");
    }

    public static String DateFormat(String date) {
        date = date.substring(6, 8) + "-" + date.substring(4, 6)
                + "-" + date.substring(0, 4);
        return (date);
    }

    public static String TimeFormat(String time) {
        time = time.substring(0, 2) + ":" + time.substring(2, 4)
                + ":" + time.substring(4, 6);
        return (time);
    }

    public static String maskIp(String ip) {
        String result = "-";
        if (!ip.trim().isEmpty()) {
            String[] parts = ip.split("\\.");
            int finalDest = 0;
            if(parts.length > 0) {
                finalDest = parts.length -1;
            }
            result = "xxx.xxx.xxx." + parts[finalDest];
        }

        return result;
    }

    public static String getTxnType(String txnType) {
        timber.log.Timber.tag("StringUtils").d("getTxnType: %s", txnType);
        String returnVale = txnType;
        if(txnType.equalsIgnoreCase("MOTO")){
            returnVale = "Sale";
        }
        return returnVale;
    }
}
