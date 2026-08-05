package com.hynu.jiaowu;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;

import com.getcapacitor.BridgeActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends BridgeActivity {

    private static final String PREFS_NAME = "jiaowu_session";
    private static final String KEY_COOKIES = "cookies";
    /** 页面实际路径，cookie 通常以 / 或 /dist/ 为 path，从这个 URL 抓取最全 */
    private static final String BASE_URL = "https://hysfjwyd.hynu.edu.cn/dist/";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable cookieSaver = new Runnable() {
        @Override
        public void run() {
            saveCookiesSnapshot();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 启动时注入上次保存的登录 cookie（转为长有效期），实现免重复登录
        injectSavedCookies();
        // 定时把当前登录 cookie 快照保存到本地
        handler.postDelayed(cookieSaver, 3000);
        // 左下角悬浮“退出登录”按钮
        addLogoutButton();
    }

    @Override
    protected void onDestroy() {
        saveCookiesSnapshot();
        handler.removeCallbacks(cookieSaver);
        super.onDestroy();
    }

    /** 启动时把上次保存的登录 cookie 以“长有效期”重新注入到 WebView */
    private void injectSavedCookies() {
        try {
            String saved = prefs().getString(KEY_COOKIES, "");
            if (saved == null || saved.trim().isEmpty()) return;
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String expiry = "Expires=" + longExpiryDate();
            for (String c : saved.split(";")) {
                String trimmed = c.trim();
                if (trimmed.isEmpty()) continue;
                cm.setCookie(BASE_URL, trimmed + "; " + expiry + "; Path=/");
            }
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    /** 定时抓取当前登录 cookie 保存到本地 */
    private void saveCookiesSnapshot() {
        try {
            WebView wv = getBridge().getWebView();
            if (wv == null) return;
            String cookies = CookieManager.getInstance().getCookie(BASE_URL);
            if (cookies != null && !cookies.isEmpty()) {
                prefs().edit().putString(KEY_COOKIES, cookies).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    /** 一年后过期的 GMT 时间字符串，用于把 session cookie 转成长效 cookie */
    private String longExpiryDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, 1);
        return sdf.format(c.getTime());
    }

    /** 左下角半透明悬浮“退出登录”按钮 */
    private void addLogoutButton() {
        runOnUiThread(() -> {
            try {
                FrameLayout root = (FrameLayout) getWindow().getDecorView();
                Button btn = new Button(this);
                btn.setText("退出");
                btn.setTextSize(11);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setTextColor(Color.WHITE);
                btn.setBackgroundColor(Color.parseColor("#99000000"));
                btn.setPadding(18, 8, 18, 8);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                lp.setMargins(16, 0, 0, 24);
                root.addView(btn, lp);
                btn.setOnClickListener(v -> confirmLogout());
            } catch (Exception ignored) {
            }
        });
    }

    private void confirmLogout() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出后需要重新登录才能使用，确定退出？")
                .setPositiveButton("退出", (d, w) -> doLogout())
                .setNegativeButton("取消", null)
                .show());
    }

    /** 清除登录 cookie 与本地快照，回到登录页（重新登录即为切换账号） */
    private void doLogout() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(ok -> runOnUiThread(() -> {
                prefs().edit().remove(KEY_COOKIES).apply();
                WebView wv = getBridge().getWebView();
                if (wv != null) {
                    wv.loadUrl(BASE_URL);
                }
            }));
            cm.flush();
        } catch (Exception ignored) {
        }
    }
}
