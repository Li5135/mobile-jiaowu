package com.hynu.jiaowu;

import android.content.Intent;
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
    private static final String KEY_BG = "bg";
    private static final int REQ_PICK_BG = 1001;

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

    /** 注入脚本：在“我的”页面(#/new/person)右上角加设置按钮，点开有“退出账号” */
    private static final String SETTINGS_JS =
            "(function(){" +
            "var h=location.hash||'';" +
            "var isMine=h.indexOf('person')>=0||h.indexOf('my')>=0||h.indexOf('mine')>=0||h.indexOf('user')>=0;" +
            "var btn=document.getElementById('app-settings-btn');" +
            "var menu=document.getElementById('app-settings-menu');" +
            "if(!isMine){if(btn){btn.parentNode.removeChild(btn);}if(menu){menu.parentNode.removeChild(menu);}return;}" +
            "if(btn)return;" +
            "btn=document.createElement('div');" +
            "btn.id='app-settings-btn';" +
            "btn.textContent='\u2699';" +
            "btn.style.cssText='position:fixed;top:12px;right:12px;z-index:99999;width:36px;height:36px;line-height:36px;text-align:center;background:rgba(0,0,0,0.35);color:#fff;font-size:20px;border-radius:50%;';" +
            "btn.onclick=function(){" +
            "var m=document.getElementById('app-settings-menu');" +
            "if(m){m.style.display=(m.style.display==='none')?'block':'none';return;}" +
            "var menu=document.createElement('div');" +
            "menu.id='app-settings-menu';" +
            "menu.style.cssText='position:fixed;top:54px;right:12px;z-index:99999;background:#fff;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,0.2);padding:6px 0;min-width:130px;';" +
            "var it2=document.createElement('div');" +
            "it2.textContent='\u66f4\u6362\u8bfe\u8868\u4e3b\u9898';" +
            "it2.style.cssText='padding:12px 16px;font-size:14px;color:#333;cursor:pointer;text-align:center;';" +
            "it2.onclick=function(){if(window.AndroidBridge){window.AndroidBridge.chooseBackground();}};" +
            "menu.appendChild(it2);" +
            "var it=document.createElement('div');" +
            "it.textContent='\u9000\u51fa\u8d26\u53f7';" +
            "it.style.cssText='padding:12px 16px;font-size:14px;color:#d33;cursor:pointer;text-align:center;';" +
            "it.onclick=function(){if(window.AndroidBridge){window.AndroidBridge.logout();}};" +
            "menu.appendChild(it);" +
            "document.body.appendChild(menu);" +
            "};" +
            "document.body.appendChild(btn);" +
            "})()";

    /** 背景注入脚本：课表页面应用自定义背景图片（__BG__ 占位，data URI） */
    private static final String BG_JS =
            "(function(){" +
            "var h=location.hash||'';" +
            "var isTable=h.indexOf('schedule')>=0||h.indexOf('kebiao')>=0||h.indexOf('timetable')>=0||h.indexOf('course')>=0;" +
            "var s=document.getElementById('app-bg-style');" +
            "if(!isTable){if(s){s.parentNode.removeChild(s);}return;}" +
            "if(s)return;" +
            "s=document.createElement('style');" +
            "s.id='app-bg-style';" +
            "s.textContent='html,body{background-image:url(__BG__)!important;background-size:cover!important;background-position:center!important;background-attachment:fixed!important;}';" +
            "document.head.appendChild(s);" +
            "})()";

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 站内导航栈（SPA hash 路由不会产生 WebView 历史，需要自己维护） */
    private final List<String> historyStack = new ArrayList<>();
    private long lastBackPressedAt = 0L;
    private boolean jsBridgeAttached = false;

    /** 轮询：记录导航栈 + 学习/填充账号密码 */
    private final Runnable urlWatcher = new Runnable() {
        @Override
        public void run() {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                // 0) 首次附加原生桥（供网页内“退出账号”调用）
                if (!jsBridgeAttached) {
                    wv.addJavascriptInterface(new JsBridge(), "AndroidBridge");
                    jsBridgeAttached = true;
                }
                // 0.5) 在“我的”页面注入设置按钮/退出账号
                wv.evaluateJavascript(SETTINGS_JS, null);
                // 0.6) 课表页应用自定义背景
                String bg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_BG, "");
                if (!bg.isEmpty()) {
                    wv.evaluateJavascript(BG_JS.replace("__BG__", bg), null);
                }
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

    /** 原生桥：网页内“退出账号”/“更换课表主题”菜单项点击后调用 */
    private class JsBridge {
        @android.webkit.JavascriptInterface
        public void logout() {
            runOnUiThread(MainActivity.this::clearSessionAndReload);
        }

        @android.webkit.JavascriptInterface
        public void chooseBackground() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    startActivityForResult(i, REQ_PICK_BG);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_BG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            new Thread(() -> {
                try {
                    final String b64 = loadImageAsBase64(uri);
                    runOnUiThread(() -> {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().putString(KEY_BG, b64).apply();
                        Toast.makeText(this, "课表主题已设置，进入课表页查看", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "图片处理失败，请换一张", Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    }

    /** 读图 → 缩放 → JPEG 压缩 → base64 data URI（避免超大字符串） */
    private String loadImageAsBase64(android.net.Uri uri) {
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        java.io.InputStream is0 = getContentResolver().openInputStream(uri);
        android.graphics.BitmapFactory.decodeStream(is0, null, opts);
        if (is0 != null) { try { is0.close(); } catch (Exception ignored) {} }
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= 720) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is, null, opts);
        if (is != null) { try { is.close(); } catch (Exception ignored) {} }
        if (bmp == null) throw new RuntimeException("decode fail");
        int w = bmp.getWidth();
        if (w > 720) {
            int h = (int) (bmp.getHeight() * 720.0 / w);
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, 720, h, true);
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                bos.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** 彻底清除登录态（cookie + localStorage/sessionStorage + WebStorage），回到登录页 */
    private void clearSessionAndReload() {
        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                wv.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
            }
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
            if (wv != null) {
                wv.loadUrl(BASE_URL);
            }
        } catch (Exception ignored) {
        }
    }
}

