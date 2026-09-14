package com.hynu.jiaowu;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebStorage;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 移动教务壳层。
 *
 * <p>页面本身由校内 H5 提供（WebView 直接加载线上地址），这里只补齐原生缺失的能力：
 * 返回键的站内导航栈、登录凭证的加密记忆与自动填充、课表页自定义背景、
 * 以及“我的”页面注入的设置入口。
 *
 * <p>与线上页面之间通过 {@code window.__jyBridge}（{@link JsBridge}）通信：页面侧
 * 主动上报路由变化和用户输入的凭证，原生不再周期性轮询页面状态。
 */
public class MainActivity extends BridgeActivity {

    /** 站点根地址，退出账号后回到这里 */
    private static final String BASE_URL = "https://hysfjwyd.hynu.edu.cn/dist/";
    private static final String SITE_HOST = "hysfjwyd.hynu.edu.cn";
    /** 首页连续按两次返回键退出 */
    private static final long BACK_EXIT_INTERVAL = 2000L;
    /** 导航栈上限，防止长时间使用无限增长 */
    private static final int MAX_HISTORY = 60;

    /** 注入脚本与背景图各自存在独立的 prefs 文件：前者非敏感，后者是大体积 base64 */
    private static final String PREFS_UI = "jiaowu_ui";
    private static final String KEY_BG = "bg";

    private static final String BRIDGE_NAME = "__jyBridge";
    private static final int REQ_PICK_BG = 1001;

    /** 站内 SPA 导航栈（hash 路由不产生 WebView 历史，需要自己维护） */
    private final List<String> historyStack = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private long lastBackPressedAt = 0L;

    /** 注入脚本模板，仅读取一次 */
    private String injectTemplate;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 返回键：站内先回退，首页再按两次退出
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });

        if (getBridge() != null) {
            webView = getBridge().getWebView();
            // 原生桥的注册时机必须早于页面脚本执行，否则页面拿不到 window.__jyBridge
            webView.addJavascriptInterface(new JsBridge(this), BRIDGE_NAME);
            getBridge().addWebViewListener(new PageLoadListener(this));
        }
    }

    /* ------------------------------------------------------------------ 页面事件 */

    /**
     * 整页加载完成：重置站内导航栈、记录新页面，并注入壳层脚本。
     *
     * <p>栈只在整页加载时重置——此时真正的返回行为已经由 WebView 自身的历史承担，
     * 站内栈只需记录「当前这个页面内的 hash 路由」。
     *
     * <p>这里必须自己把 URL 入栈，不能依赖注入脚本回报：脚本是幂等的，
     * 后续整页加载时 {@code __jyReady} 已存在会直接返回，不会再触发上报。
     */
    void onPageLoaded(WebView view, String url) {
        if (url == null || !url.startsWith("https://" + SITE_HOST)) return;
        historyStack.clear();
        recordUrl(url);
        injectShellScript(view);
    }

    /** 页面通过 hashchange 上报路由变化 */
    private void onRouteReported(String url) {
        recordUrl(url);
    }

    /** 入栈，做去重与上限裁剪 */
    private void recordUrl(String url) {
        if (url == null || !url.startsWith("https://" + SITE_HOST)) return;
        if (!historyStack.isEmpty() && historyStack.get(historyStack.size() - 1).equals(url)) return;
        historyStack.add(url);
        while (historyStack.size() > MAX_HISTORY) {
            historyStack.remove(0);
        }
    }

    /**
     * 注入壳层脚本。凭证以「上次记录值」的形式下发：页面据此避免重复上报，
     * 同时原生侧只在用户真的改动了输入框时才写存储。
     */
    private void injectShellScript(WebView view) {
        if (injectTemplate == null) {
            injectTemplate = readAsset("inject.js");
            if (injectTemplate == null) return;
        }
        String[] creds = CredentialVault.load(this);
        String acct = creds != null ? creds[0] : "";
        String pwd = creds != null ? creds[1] : "";

        String script = injectTemplate
            .replace("__ACCT__", JSONObject.quote(acct))
            .replace("__PWD__", JSONObject.quote(pwd))
            .replace("__BG_VALUE__", JSONObject.quote(background()));
        view.evaluateJavascript(script, null);
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ 返回键 */

    /** 返回键逻辑：WebView 整页历史 > 站内 SPA 历史 > 首页双击退出 */
    private void handleBackPressed() {
        if (webView == null) {
            finish();
            return;
        }
        // 1) 整页导航历史（跨页跳转、外链等）
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 2) 站内 SPA 历史（hash 路由）
        if (historyStack.size() >= 2) {
            String current = historyStack.get(historyStack.size() - 1);
            String prev = historyStack.get(historyStack.size() - 2);
            historyStack.remove(historyStack.size() - 1);
            navigateTo(prev, current);
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
        if (webView == null) return;
        if (currentUrl != null && samePath(currentUrl, targetUrl)) {
            int idx = targetUrl.indexOf('#');
            String hash = idx >= 0 ? targetUrl.substring(idx) : "";
            webView.evaluateJavascript("location.hash=" + JSONObject.quote(hash), null);
        } else {
            webView.loadUrl(targetUrl);
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

    /* ------------------------------------------------------------------ 凭证与背景 */

    /** 保存页面上报的凭证；Keystore 不可用时明确告知用户，不静默失败 */
    private void saveCredentials(String acct, String pwd) {
        if (acct == null || acct.isEmpty() || pwd == null || pwd.isEmpty()) return;
        if (!CredentialVault.save(this, acct, pwd)) {
            runOnUiThread(() ->
                Toast.makeText(this, "账号密码无法安全保存，本次不会记住登录信息", Toast.LENGTH_SHORT).show());
        }
    }

    private String background() {
        try {
            return getSharedPreferences(PREFS_UI, MODE_PRIVATE).getString(KEY_BG, "");
        } catch (Exception e) {
            return "";
        }
    }

    private void saveBackground(String dataUri) {
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putString(KEY_BG, dataUri).apply();
        // 重新加载以应用新背景，同时让注入脚本拿到最新的 __BG_VALUE__
        if (webView != null) webView.loadUrl(BASE_URL);
    }

    /** 彻底清除登录态（凭证 + cookie + localStorage/sessionStorage + WebStorage）并回到登录页 */
    private void clearSessionAndReload() {
        CredentialVault.clear(this);
        final WebView wv = webView;
        if (wv == null) return;
        // 先清完页面存储再重新加载，避免和 WebView 的异步清理竞争
        wv.evaluateJavascript(
            "try{localStorage.clear();sessionStorage.clear();}catch(e){}",
            value -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
                wv.loadUrl(BASE_URL);
            });
    }

    /* ------------------------------------------------------------------ 选图 */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BG || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                final String dataUri = loadImageAsBase64(uri);
                runOnUiThread(() -> {
                    saveBackground(dataUri);
                    Toast.makeText(this, "课表主题已设置，进入课表页查看", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "图片处理失败，请换一张", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 读图 → 降采样 → 缩放 → JPEG 压缩 → base64 data URI（避免超大字符串）。
     *
     * <p>需要声明 {@code IOException}：try-with-resources 在关闭输入流时可能抛出该异常，
     * 调用方在后台线程里统一兜住。
     */
    private String loadImageAsBase64(Uri uri) throws java.io.IOException {
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream probe = getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(probe, null, opts);
        } catch (Exception ignored) {
        }
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= 720) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;

        android.graphics.Bitmap bmp;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            bmp = android.graphics.BitmapFactory.decodeStream(is, null, opts);
        }
        if (bmp == null) throw new IllegalStateException("decode failed");
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

    /* ------------------------------------------------------------------ 组件 */

    /**
     * 页面加载回调。用 Capacitor 提供的监听器而不是替换 {@code WebViewClient}，
     * 这样 Capacitor 自身的 URL 拦截、错误页、插件回调都保持原样。
     */
    private static final class PageLoadListener extends WebViewListener {
        private final WeakReference<MainActivity> activityRef;

        PageLoadListener(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @Override
        public void onPageLoaded(WebView webView) {
            MainActivity activity = activityRef.get();
            if (activity != null) activity.onPageLoaded(webView, webView.getUrl());
        }
    }

    /** 原生桥：页面侧的 route / storeCreds / logout / chooseBackground 都落到这里 */
    private static final class JsBridge {
        private final WeakReference<MainActivity> activityRef;

        JsBridge(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        /** 页面路由变化上报。注意：JS 接口在 WebView 的 JS 线程回调，必须切回主线程。
         *  脚本会多传一个 fullLoad 参数，这里按需忽略。 */
        @JavascriptInterface
        public void route(String url) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.handler.post(() -> activity.onRouteReported(url));
        }

        @JavascriptInterface
        public void storeCreds(String acct, String pwd) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.handler.post(() -> activity.saveCredentials(acct, pwd));
        }

        @JavascriptInterface
        public void logout() {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(activity::clearSessionAndReload);
        }

        @JavascriptInterface
        public void chooseBackground() {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    activity.startActivityForResult(i, REQ_PICK_BG);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
