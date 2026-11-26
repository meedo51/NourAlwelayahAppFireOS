package com.nouralwelayah.clock;

import android.content.Context;
import androidx.core.content.ContextCompat;

/** Context.getSystemService(Class) needs Android 6; this works on Fire OS 5 (Android 5.1) too.
 *  Java, so Kotlin sees the same platform type (T!) as getSystemService(Class) returned. */
public final class Sys {
    private Sys() {}

    public static <T> T svc(Context c, Class<T> k) {
        return ContextCompat.getSystemService(c, k);
    }
}
