package com.phonewebmcp.gu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebView 控制中枢：所有对 WebView 的操作都经由此类，且线程安全（内部投递到 UI 线程）。
 */
public class WebController {
    private final Activity activity;
    private volatile WebView webView;

    public WebController(Activity activity) {
        this.activity = activity;
    }

    public void attach(WebView wv) {
        this.webView = wv;
    }

    // ---------- 内部工具 ----------

    private interface UiCall {
        void run(WebView wv) throws Exception;
    }

    private JSONObject call(UiCall c, long timeoutMs) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] ex = new Throwable[1];
            activity.runOnUiThread(() -> {
                try {
                    c.run(webView);
                } catch (Throwable t) {
                    ex[0] = t;
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return fail("timeout");
            }
            if (ex[0] != null) {
                return fail("exception: " + ex[0].getMessage());
            }
            return okVal(null);
        } catch (Exception e) {
            return fail("exception: " + e);
        }
    }

    /** 在 UI 线程执行并返回结果对象 */
    private JSONObject ui(JsonCall c, long timeoutMs) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] res = new Object[1];
            final Throwable[] ex = new Throwable[1];
            activity.runOnUiThread(() -> {
                try {
                    res[0] = c.run(webView);
                } catch (Throwable t) {
                    ex[0] = t;
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return fail("timeout");
            }
            if (ex[0] != null) {
                return fail("exception: " + ex[0].getMessage());
            }
            return okVal(res[0]);
        } catch (Exception e) {
            return fail("exception: " + e);
        }
    }

    private static JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject okVal(Object result) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            if (result != null) o.put("result", result);
        } catch (Exception ignored) {}
        return o;
    }

    private interface JsonCall {
        Object run(WebView wv) throws Exception;
    }

    // ---------- 导航 ----------

    public JSONObject navigate(final String url) {
        return call(wv -> wv.loadUrl(url), 5000);
    }

    public JSONObject back() {
        return call(wv -> { if (wv.canGoBack()) wv.goBack(); }, 5000);
    }

    public JSONObject forward() {
        return call(wv -> { if (wv.canGoForward()) wv.goForward(); }, 5000);
    }

    public JSONObject reload() {
        return call(WebView::reload, 5000);
    }

    public JSONObject stop() {
        return call(WebView::stopLoading, 5000);
    }

    // ---------- JS 执行（阻塞等待结果） ----------

    public JSONObject evaluate(final String js, long timeoutMs) {
        JSONObject out = new JSONObject();
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] result = new String[1];
            activity.runOnUiThread(() -> {
                WebView wv = webView;
                if (Build.VERSION.SDK_INT >= 19) {
                    wv.evaluateJavascript(js, value -> {
                        result[0] = value;
                        latch.countDown();
                    });
                } else {
                    wv.loadUrl("javascript:" + js);
                    result[0] = "ok";
                    latch.countDown();
                }
            });
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                out.put("ok", false).put("error", "timeout");
                return out;
            }
            out.put("ok", true).put("result", result[0] == null ? "null" : result[0]);
            return out;
        } catch (Exception e) {
            return fail("exception: " + e);
        }
    }

    // ---------- 状态 ----------

    public JSONObject status() {
        return ui(wv -> {
            JSONObject o = new JSONObject();
            o.put("url", wv.getUrl());
            o.put("title", wv.getTitle());
            o.put("canGoBack", wv.canGoBack());
            o.put("canGoForward", wv.canGoForward());
            o.put("progress", progress);
            WebSettings s = wv.getSettings();
            JSONObject st = new JSONObject();
            st.put("userAgent", s.getUserAgentString());
            st.put("javaScriptEnabled", s.getJavaScriptEnabled());
            st.put("domStorageEnabled", s.getDomStorageEnabled());
            st.put("cacheMode", cacheModeName(s.getCacheMode()));
            st.put("mixedContentMode", s.getMixedContentMode());
            st.put("allowFileAccess", s.getAllowFileAccess());
            st.put("allowContentAccess", s.getAllowContentAccess());
            st.put("javaScriptCanOpenWindowsAutomatically", s.getJavaScriptCanOpenWindowsAutomatically());
            st.put("builtInZoomControls", s.getBuiltInZoomControls());
            st.put("displayZoomControls", s.getDisplayZoomControls());
            st.put("loadWithOverviewMode", s.getLoadWithOverviewMode());
            st.put("useWideViewPort", s.getUseWideViewPort());
            st.put("defaultTextEncoding", s.getDefaultTextEncodingName());
            st.put("mediaPlaybackRequiresUserGesture", s.getMediaPlaybackRequiresUserGesture());
            st.put("supportMultipleWindows", s.supportMultipleWindows());
            st.put("safeBrowsingEnabled", s.getSafeBrowsingEnabled());
            o.put("settings", st);
            o.put("cookieManager.acceptCookie", CookieManager.getInstance().acceptCookie());
            return o;
        }, 5000);
    }

    private static String cacheModeName(int m) {
        switch (m) {
            case WebSettings.LOAD_CACHE_ELSE_NETWORK: return "LOAD_CACHE_ELSE_NETWORK";
            case WebSettings.LOAD_NO_CACHE: return "LOAD_NO_CACHE";
            case WebSettings.LOAD_CACHE_ONLY: return "LOAD_CACHE_ONLY";
            default: return "LOAD_DEFAULT";
        }
    }

    // ---------- 设置 ----------

    public JSONObject applySetting(final String key, final String value) {
        return call(wv -> {
            WebSettings s = wv.getSettings();
            switch (key) {
                case "userAgent": case "ua":
                    s.setUserAgentString(value);
                    break;
                case "javaScript":
                    s.setJavaScriptEnabled(Boolean.parseBoolean(value));
                    break;
                case "domStorage":
                    s.setDomStorageEnabled(Boolean.parseBoolean(value));
                    break;
                case "cacheMode":
                    s.setCacheMode(parseCacheMode(value, s.getCacheMode()));
                    break;
                case "mixedContent":
                    s.setMixedContentMode(Integer.parseInt(value));
                    break;
                case "safeBrowsing":
                    s.setSafeBrowsingEnabled(Boolean.parseBoolean(value));
                    break;
                case "allowFileAccess":
                    s.setAllowFileAccess(Boolean.parseBoolean(value));
                    break;
                case "javaScriptCanOpenWindowsAutomatically":
                    s.setJavaScriptCanOpenWindowsAutomatically(Boolean.parseBoolean(value));
                    break;
                case "builtInZoomControls":
                    s.setBuiltInZoomControls(Boolean.parseBoolean(value));
                    break;
                case "loadWithOverviewMode":
                    s.setLoadWithOverviewMode(Boolean.parseBoolean(value));
                    break;
                case "useWideViewPort":
                    s.setUseWideViewPort(Boolean.parseBoolean(value));
                    break;
                case "mediaPlaybackRequiresUserGesture":
                    s.setMediaPlaybackRequiresUserGesture(Boolean.parseBoolean(value));
                    break;
                case "supportMultipleWindows":
                    s.setSupportMultipleWindows(Boolean.parseBoolean(value));
                    break;
                case "defaultTextEncoding":
                    s.setDefaultTextEncodingName(value);
                    break;
                default:
                    throw new IllegalArgumentException("未知设置项: " + key);
            }
        }, 5000);
    }

    private static int parseCacheMode(String v, int def) {
        switch (v) {
            case "LOAD_CACHE_ELSE_NETWORK": return WebSettings.LOAD_CACHE_ELSE_NETWORK;
            case "LOAD_NO_CACHE": return WebSettings.LOAD_NO_CACHE;
            case "LOAD_CACHE_ONLY": return WebSettings.LOAD_CACHE_ONLY;
            case "LOAD_DEFAULT": return WebSettings.LOAD_DEFAULT;
            default: return def;
        }
    }

    // ---------- 缓存 ----------

    public JSONObject clearCache() {
        return call(wv -> {
            wv.clearCache(true);
            WebStorage.getInstance().deleteAllData();
        }, 8000);
    }

    public JSONObject clearCookies() {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(a -> latch.countDown());
                cm.flush();
            });
            latch.await(8000, TimeUnit.MILLISECONDS);
            return okVal(null);
        } catch (Exception e) {
            return fail("exception: " + e);
        }
    }

    public JSONObject clearFormData() {
        return call(wv -> {
            WebViewDatabase db = WebViewDatabase.getInstance(activity);
            db.clearFormData();
            db.clearHttpAuthUsernamePassword();
        }, 5000);
    }

    public JSONObject clearEverything() {
        JSONObject a = clearCache();
        JSONObject b = clearCookies();
        JSONObject c = clearFormData();
        return okVal(a.optBoolean("ok") && b.optBoolean("ok") && c.optBoolean("ok"));
    }

    // ---------- 页面交互（滚动/点击/填表/等待） ----------

    /** 执行一段返回 JSON 字符串的 JS，并解析为 JSONObject 返回 */
    private JSONObject runJs(String js, long timeoutMs) {
        JSONObject e = evaluate(js, timeoutMs);
        if (!e.optBoolean("ok", false)) return e;
        try {
            String real = jsonUnescape(e.optString("result"));
            return new JSONObject(real);
        } catch (Exception ex) {
            return fail("parse: " + ex);
        }
    }

    /** 滚动：{selector,position} 或 {x,y} 或 {dir,px} */
    public JSONObject scrollPage(JSONObject p) {
        StringBuilder js = new StringBuilder("(function(){");
        try {
            if (p.has("selector")) {
                js.append("var el=document.querySelector(").append(JSONObject.quote(p.optString("selector"))).append(");");
                js.append("if(!el)return JSON.stringify({ok:false,error:'element not found'});");
                js.append("el.scrollIntoView({block:'").append(p.optString("position", "center")).append("',behavior:'smooth'});");
            } else if (p.has("x") || p.has("y")) {
                js.append("window.scrollTo(").append(p.optLong("x", 0)).append(",").append(p.optLong("y", 0)).append(");");
            } else {
                long px = p.optLong("px", 400);
                String sign = "down".equals(p.optString("dir", "down")) ? "" : "-";
                if ("left".equals(p.optString("dir")) || "right".equals(p.optString("dir"))) {
                    js.append("window.scrollBy({left:").append("right".equals(p.optString("dir")) ? px : -px).append(",behavior:'smooth'});");
                } else {
                    js.append("window.scrollBy({top:").append(sign).append(px).append(",behavior:'smooth'});");
                }
            }
        } catch (Exception e) {
            return fail("params: " + e);
        }
        js.append("return JSON.stringify({ok:true});})()");
        return runJs(js.toString(), 8000);
    }

    /** 点击：{selector} 或 {text} 或 {x,y} */
    public JSONObject clickPage(JSONObject p) {
        try {
            if (p.has("selector")) {
                String js = "(function(){var el=document.querySelector(" + JSONObject.quote(p.optString("selector"))
                        + ");if(!el)return JSON.stringify({ok:false,error:'element not found'});el.click();"
                        + "return JSON.stringify({ok:true,tag:el.tagName,text:(el.innerText||el.value||'').substring(0,50)});})()";
                return runJs(js, 8000);
            }
            if (p.has("text")) {
                String txt = p.optString("text");
                String js = "(function(){var t=" + JSONObject.quote(txt)
                        + ";var els=document.querySelectorAll('a,button,[role=button],input[type=submit],[onclick],.btn,.button');"
                        + "for(var i=0;i<els.length;i++){var el=els[i];var tx=(el.innerText||el.value||'').trim();"
                        + "if(el.offsetParent!==null&&(tx===t||tx.indexOf(t)>=0)){el.click();return JSON.stringify({ok:true,tag:el.tagName,text:tx.substring(0,50)});}}"
                        + "return JSON.stringify({ok:false,error:'not found: '+t});})()";
                return runJs(js, 8000);
            }
            if (p.has("x") && p.has("y")) {
                long x = p.optLong("x"), y = p.optLong("y");
                String js = "(function(){var el=document.elementFromPoint(" + x + "," + y + ");"
                        + "if(!el)return JSON.stringify({ok:false,error:'no element at point'});"
                        + "var r=el.getBoundingClientRect();var cx=Math.min(r.left+r.width/2,window.innerWidth-1),cy=Math.min(r.top+r.height/2,window.innerHeight-1);"
                        + "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy}))});"
                        + "return JSON.stringify({ok:true,tag:el.tagName,text:(el.innerText||'').substring(0,50)});})()";
                return runJs(js, 8000);
            }
            return fail("需要 selector 或 text 或 x/y");
        } catch (Exception e) {
            return fail("params: " + e);
        }
    }

    /** 填表：{selector, value} */
    public JSONObject fillField(JSONObject p) {
        try {
            String sel = p.optString("selector");
            String val = p.optString("value");
            String js = "(function(){var el=document.querySelector(" + JSONObject.quote(sel) + ");"
                    + "if(!el)return JSON.stringify({ok:false,error:'element not found'});"
                    + "el.focus();el.value=" + JSONObject.quote(val) + ";"
                    + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return JSON.stringify({ok:true,tag:el.tagName});})()";
            return runJs(js, 8000);
        } catch (Exception e) {
            return fail("params: " + e);
        }
    }

    /** 等待元素出现：{selector, timeoutMs} */
    public JSONObject waitFor(JSONObject p) {
        String sel = p.optString("selector");
        long timeout = p.optLong("timeoutMs", 10000);
        long start = System.currentTimeMillis();
        try {
            while (System.currentTimeMillis() - start < timeout) {
                JSONObject c = runJs("(function(){return JSON.stringify({found:!!document.querySelector(" + JSONObject.quote(sel) + ")})})()", 4000);
                if (c.optBoolean("ok", false) && c.optBoolean("found", false)) {
                    return okVal(null);
                }
                Thread.sleep(300);
            }
            return fail("timeout waiting for: " + sel);
        } catch (Exception e) {
            return fail("waitFor: " + e);
        }
    }

    // ---------- Cookie ----------

    public JSONObject getCookies(final String url) {
        JSONObject out = new JSONObject();
        try {
            String cookie = CookieManager.getInstance().getCookie(url == null ? getCurrentUrl() : url);
            out.put("ok", true);
            out.put("cookie", cookie == null ? "" : cookie);
            if (cookie != null && !cookie.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String pair : cookie.split(";")) {
                    String t = pair.trim();
                    if (t.isEmpty()) continue;
                    int eq = t.indexOf('=');
                    JSONObject c = new JSONObject();
                    if (eq < 0) { c.put("name", t); c.put("value", ""); }
                    else { c.put("name", t.substring(0, eq)); c.put("value", t.substring(eq + 1)); }
                    arr.put(c);
                }
                out.put("cookies", arr);
            }
            return out;
        } catch (Exception e) {
            return fail("exception: " + e);
        }
    }

    public JSONObject setCookie(final String url, final String nameValue) {
        return call(wv -> {
            CookieManager cm = CookieManager.getInstance();
            cm.setCookie(url, nameValue);
            cm.flush();
        }, 5000);
    }

    // ---------- DOM / 页面信息 ----------

    public JSONObject domInfo() {
        String js = "(function(){"
                + "var r={url:location.href,title:document.title};"
                + "r.text=(document.body?document.body.innerText:'').replace(/\\s+/g,' ').substring(0,3000);"
                + "var links=[],a=document.querySelectorAll('a');"
                + "for(var i=0;i<Math.min(a.length,50);i++){var h=a[i].getAttribute('href');if(h)links.push({href:h,text:a[i].innerText.trim().substring(0,80)});}"
                + "r.links=links;"
                + "var meta={};"
                + "document.querySelectorAll('meta[name],meta[property]').forEach(function(m){var k=m.getAttribute('name')||m.getAttribute('property');if(k&&!meta[k])meta[k]=m.getAttribute('content').substring(0,200);});"
                + "r.meta=meta;"
                + "return JSON.stringify(r);})()";
        JSONObject e = evaluate(js, 5000);
        if (!e.optBoolean("ok", false)) return e;
        try {
            // evaluateJavascript 返回的是 JSON 字符串的字面值，需解析两层
            String encoded = e.optString("result");
            String realJson = jsonUnescape(encoded);
            return okVal(new JSONObject(realJson));
        } catch (Exception ex) {
            return fail("parse result: " + ex);
        }
    }

    /** evaluateJavascript 返回的字符串是 JSON 编码的，需要把 \" 还原 */
    private static String jsonUnescape(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            try {
                return new JSONObject("{\"v\":" + s + "}").getString("v");
            } catch (Exception ignored) {
                return s;
            }
        }
        return s;
    }

    public JSONObject links() {
        JSONObject d = domInfo();
        if (!d.optBoolean("ok", false)) return d;
        return okVal(d.optJSONObject("result").optJSONArray("links"));
    }

    public JSONObject history() {
        return ui(wv -> {
            WebBackForwardList l = wv.copyBackForwardList();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < l.getSize(); i++) {
                WebHistoryItem it = l.getItemAtIndex(i);
                JSONObject o = new JSONObject();
                o.put("url", it.getUrl());
                o.put("title", it.getTitle());
                arr.put(o);
            }
            return arr;
        }, 5000);
    }

    // ---------- 截图 ----------

    public JSONObject screenshot() {
        return ui(wv -> {
            // 兑底：若从未被布局（后台创建/冻结后），手动 measure + layout
            if (wv.getWidth() <= 0 || wv.getHeight() <= 0) {
                android.view.View parent = (android.view.View) wv.getParent();
                int w = parent != null && parent.getWidth() > 0 ? parent.getWidth() : 1080;
                int h = parent != null && parent.getHeight() > 0 ? parent.getHeight() : 1920;
                wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
                wv.layout(0, 0, w, h);
            }
            if (wv.getWidth() <= 0 || wv.getHeight() <= 0) throw new IllegalStateException("webview 尚未布局");
            int w = wv.getWidth();
            int h = wv.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            wv.draw(new android.graphics.Canvas(bmp));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            bmp.recycle();
            return "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        }, 8000);
    }

    private volatile int progress = 0;
    public void onProgress(int p) { this.progress = p; }

    private String getCurrentUrl() {
        try {
            WebView wv = webView;
            String u = wv == null ? null : wv.getUrl();
            return u == null ? (intentUrl == null ? "about:blank" : intentUrl) : u;
        } catch (Exception e) {
            return "about:blank";
        }
    }

    private volatile String intentUrl = null;
    public void setIntentUrl(String u) { this.intentUrl = u; }

    public String getBuiltInUa() {
        try {
            return WebSettings.getDefaultUserAgent(activity);
        } catch (Exception e) {
            return "Mozilla/5.0 (Linux; Android)";
        }
    }

    /** 清理浏览器类临时文件（供 UI 展示使用量） */
    public long cacheDirSize() {
        try {
            File[] dirs = activity.getCacheDir().listFiles();
            if (dirs == null) return 0;
            long total = 0;
            for (File f : dirs) total += dirSize(f);
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += dirSize(k);
        return s;
    }
}