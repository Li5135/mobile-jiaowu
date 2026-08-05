package com.hynu.jiaowu;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
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

    private static final String PREFS_NAME = "jiaowu_creds";
    private static final String KEY_ACCT = "acct";
    private static final String KEY_PWD = "pwd";

    /** 学习脚本：按真实登录框结构（placeholder 特征）抓取账号密码 */
    private static final String LEARN_JS =
            "(function(){" +
            "var acct=document.querySelector('input[placeholder*=\"学号\"],input[placeholder*=\"工号\"],input[autocomplete=\"user\"]');" +
            "if(!acct)return null;" +
            "var pwd=document.querySelector('input[placeholder*=\"密码\"],input[type=\"password\"]');" +
            "if(!pwd)return null;" +
            "if(!acct.value||!pwd.value)return null;" +
            "return JSON.stringify({acct:acct.value,pwd:pwd.value});" +
            "})()";

    /** 填充脚本：登录框存在且密码框为空时填入保存的账号密码 */
    private static final String FILL_JS =
            "(function(){" +
            "var acct=document.querySelector('input[placeholder*=\"学号\"],input[placeholder*=\"工号\"],input[autocomplete=\"user\"]');" +
            "if(!acct)return;" +
            "var pwd=document.querySelector('input[placeholder*=\"密码\"],input[type=\"password\"]');" +
            "if(!pwd||pwd.value)return;" +
            "var set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
            "set.call(acct,__ACCT__);acct.dispatchEvent(new Event('input',{bubbles:true}));" +
            "set.call(pwd,__PWD__);pwd.dispatchEvent(new Event('input',{bubbles:true}));" +
            "})()";

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 站内导航栈（SPA hash 路由不会产生 WebView 历史，需要自己维护） */
    private final List<String> historyStack = new ArrayList<>();
    private long lastBackPressedAt = 0L;

    /** 轮询：记录导航栈 + 学习/填充账号密码 */
    private final Runnable urlWatcher = new Runnable() {
        @Override
        public void run() {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                // 1) 记录 URL 到站内导航栈
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
                // 2) 学习：登录页输入的账号密码自动保存
                wv.evaluateJavascript(LEARN_JS, value -> {
                    if (value == null || value.equals("null")) return;
                    try {
                        // evaluateJavascript 返回值是 JSON 编码字符串，先解一层外层引号
                        String inner = new JSONObject("{\"v\":" + value + "}").getString("v");
                        JSONObject o = new JSONObject(inner);
                        String acct = o.optString("acct", "");
                        String pwd = o.optString("pwd", "");
                        if (!acct.isEmpty() && !pwd.isEmpty()) {
                            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            if (!acct.equals(sp.getString(KEY_ACCT, ""))
                                    || !pwd.equals(sp.getString(KEY_PWD, ""))) {
                                sp.edit().putString(KEY_ACCT, acct).putString(KEY_PWD, pwd).apply();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
                // 3) 填充：已保存且密码框为空时自动填入（用户直接点登录即可）
                SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String acct = sp.getString(KEY_ACCT, "");
                String pwd = sp.getString(KEY_PWD, "");
                if (!acct.isEmpty() && !pwd.isEmpty()) {
                    String js = FILL_JS
                            .replace("__ACCT__", JSONObject.quote(acct))
                            .replace("__PWD__", JSONObject.quote(pwd));
                    wv.evaluateJavascript(js, null);
                }
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
        // 轮询页面 URL / 账号密码学习填充
        handler.postDelayed(urlWatcher, 1000);
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
}

