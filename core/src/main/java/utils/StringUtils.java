package utils;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;

public class StringUtils {

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
}
