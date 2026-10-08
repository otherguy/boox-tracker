package dev.otherguy.booxtracker;

import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.RoboCookieManager;
import android.webkit.ValueCallback;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowCookieManager;

/**
 * Robolectric's cookie store with WebView's thread rule: Chromium's AwCookieManager answers a cookie callback on the
 * calling thread's Looper and throws on a thread without one.
 */
@Implements(CookieManager.class)
public class LooperCheckingCookieManager extends ShadowCookieManager {
    private static CookieManager instance;

    @Implementation
    protected static CookieManager getInstance() {
        if (instance == null) {
            instance = new RoboCookieManager() {
                @Override
                public void removeAllCookies(ValueCallback<Boolean> callback) {
                    if (callback != null && Looper.myLooper() == null) {
                        throw new IllegalStateException("removeAllCookies must be called on a thread with a running Looper.");
                    }
                    super.removeAllCookies(callback);
                }
            };
        }
        return instance;
    }

    @Resetter
    public static void reset() {
        instance = null;
    }
}
