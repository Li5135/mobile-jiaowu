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
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends BridgeActivity {

    private static final String PREFS_NAME = "jiaowu_session";
    private static final String KEY_COOKIES = "cookies";
    /** 页面实际路径，cookie 通常以 / 或 /dist/ 为 path，从这个 URL 抓取最全 */
    private static final String BASE_URL = "https://hysfjwyd.hynu.edu.cn/dist/";
    private static final String SITE_HOST = "hysfjwyd.hynu.edu.cn";
    /** 首页连续按两次返回键退出 */
    private static final long BACK_EXIT_INTERVAL = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 站内导航栈（SPA hash 路由不会产生 WebView 历史，需要自己维护） */
    private final List<String> historyStack = new ArrayList<>();
    private long lastBackPressedAt = 0L;

    private final Runnable cookieSaver = new Runnable() {
        @Override
        public void run() {
            saveCookiesSnapshot();
            handler.postDelayed(this, 5000);
        }
    };

    /** 轮询当前页面 URL（含 hash），维护站内导航栈 */
    private final Runnable urlWatcher = new Runnable() {
        @Override
        public void run() {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                wv.evaluateJavascript("(function(){ return window.location.href; })()", value -> {
                    String url = unquoteJsString(value);
                    if (url != null && url.startsWith("https://" + SITE_HOST)) {
                        if (historyStack.isEmpty() || !historyStack.get(historyStack.size() - 1).equals(url)) {
                            historyStack.add(url);
                            if (historyStack.size() > 60) {
                                historyStack.remove(0);
                            }
                        }
                    }
                });
            }
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 返回键：站内先回退，首页再按一次退出
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
        // 启动时注入上次保存的登录 cookie（转为长有效期），实现免重复登录
        injectSavedCookies();
        // 定时把当前登录 cookie 快照保存到本地
        handler.postDelayed(cookieSaver, 3000);
        // 轮询页面 URL 维护站内导航栈
        handler.postDelayed(urlWatcher, 1000);
        // 左下角悬浮“退出登录”按钮
        addLogoutButton();
    }

    @Override
    protected void onDestroy() {
        saveCookiesSnapshot();
        handler.removeCallbacks(cookieSaver);
        handler.removeCallbacks(urlWatcher);
        super.onDestroy();
    }

    /** 返回键逻辑：WebView 整页历史 > 站内 SPA 历史 > 首页双击退出 */
    private void handleBackPressed() {
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv == null) {
            finish();
            return;
        }
        // 1) 整页导航历史（跨页跳转、外链等）
        if (wv.canGoBack()) {
            wv.goBack();
            return;
        }
        // 2) 站内 SPA 历史（hash 路由）
        if (historyStack.size() >= 2) {
            String currentUrl = historyStack.get(historyStack.size() - 1);
            String prev = historyStack.get(historyStack.size() - 2);
            historyStack.remove(historyStack.size() - 1);
            navigateTo(prev, currentUrl);
            return;
        }
        // 3) 已在首页：2 秒内连按两次退出
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < BACK_EXIT_INTERVAL) {
            finish();
        } else {
            lastBackPressedAt = now;
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 站内回退：同路径只改 hash（不整页刷新，保留页面状态），不同路径整页加载 */
    private void navigateTo(String targetUrl, String currentUrl) {
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv == null) return;
        if (currentUrl != null && samePath(currentUrl, targetUrl)) {
            int idx = targetUrl.indexOf('#');
            String hash = idx >= 0 ? targetUrl.substring(idx) : "";
            wv.evaluateJavascript("location.hash=" + JSONObject.quote(hash), null);
        } else {
            wv.loadUrl(targetUrl);
        }
    }

    private boolean samePath(String a, String b) {
        try {
            java.net.URI ua = new java.net.URI(a);
            java.net.URI ub = new java.net.URI(b);
            return ua.getHost() != null && ua.getHost().equals(ub.getHost())
                    && ua.getPath() != null && ua.getPath().equals(ub.getPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** evaluateJavascript 返回的字符串是带引号的 JSON 字符串，去掉引号 */
    private String unquoteJsString(String value) {
        if (value == null || value.equals("null")) return null;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
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
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
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
                WebView wv = getBridge() != null ? getBridge().getWebView() : null;
                if (wv != null) {
                    wv.loadUrl(BASE_URL);
                }
            }));
            cm.flush();
        } catch (Exception ignored) {
        }
    }
}
