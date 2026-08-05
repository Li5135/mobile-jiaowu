package com.hynu.jiaowu;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    /** 页面实际路径 */
    private static final String BASE_URL = "https://hysfjwyd.hynu.edu.cn/dist/";
    private static final String SITE_HOST = "hysfjwyd.hynu.edu.cn";
    /** 首页连续按两次返回键退出 */
    private static final long BACK_EXIT_INTERVAL = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 站内导航栈（SPA hash 路由不会产生 WebView 历史，需要自己维护） */
    private final List<String> historyStack = new ArrayList<>();
    private long lastBackPressedAt = 0L;

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
        // 轮询页面 URL 维护站内导航栈
        handler.postDelayed(urlWatcher, 1000);
        // 左下角“设置”齿轮按钮
        addSettingsButton();
    }

    @Override
    public void onDestroy() {
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

    /** 左下角圆形“设置”齿轮按钮 */
    private void addSettingsButton() {
        runOnUiThread(() -> {
            try {
                FrameLayout root = (FrameLayout) getWindow().getDecorView();
                Button btn = new Button(this);
                btn.setText("⚙");
                btn.setTextSize(16);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setTextColor(Color.WHITE);
                btn.setBackgroundColor(Color.parseColor("#99000000"));
                int size = (int) (40 * getResources().getDisplayMetrics().density);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                lp.setMargins((int) (16 * getResources().getDisplayMetrics().density), 0, 0,
                        (int) (24 * getResources().getDisplayMetrics().density));
                root.addView(btn, lp);
                btn.setOnClickListener(v -> showSettings());
            } catch (Exception ignored) {
            }
        });
    }

    /** 设置菜单 */
    private void showSettings() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(new String[]{"切换账号"}, (d, w) -> confirmSwitchAccount())
                .setNegativeButton("取消", null)
                .show());
    }

    private void confirmSwitchAccount() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("切换账号")
                .setMessage("将清除当前登录状态并回到登录页，重新登录即可切换到其他账号。")
                .setPositiveButton("切换", (d, w) -> clearSessionAndReload())
                .setNegativeButton("取消", null)
                .show());
    }

    /** 彻底清除登录态（cookie + localStorage/sessionStorage + WebStorage），回到登录页 */
    private void clearSessionAndReload() {
        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                wv.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
            }
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            if (wv != null) {
                wv.loadUrl(BASE_URL);
            }
        } catch (Exception ignored) {
        }
    }
}
