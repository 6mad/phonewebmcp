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

    // ---------- 页面加载状态（供 API 判断导航是否真的生效）---------
    /** 当前是否正在加载中 */
    private volatile boolean loading = false;
    /** 本次页面开始加载的时间戳（SystemClock 墙钟毫秒），0 表示未知 */
    private volatile long pageStartedAt = 0;
    /** 上一次通过 API 请求导航的目标地址 */
    private volatile String lastRequestedUrl = null;
    /**
     * 会话恢复的标签地址。
     * App 冷启动会从 SharedPreferences 恢复上次的标签，这些页面在第一毫秒就能返回
     * 看似正常的 url/title，使调用方（自动化脚本）误以为导航成功。
     * 标记出来供 API 层提示"这是恢复的旧页"。
     */
    private volatile String sessionRestoredUrl = null;
    /** 是否发生过至少一次页面加载完成（用于判断"从未加载"的初始状态） */
    private volatile boolean everLoaded = false;
    /**
     * 主框架加载是否失败。
     * 由 WebViewClient.onReceivedError 直接给出（含错误码与描述），
     * 比"读标题里有没有『网页无法打开』"可靠得多——
     * 后者既依赖 WebView 的标题更新时机（实测会滞后于 loading 状态），
     * 又依赖系统语言（非中文环境下标题不同，判断会完全失效）。
     */
    private volatile boolean lastLoadFailed = false;
    private volatile String lastErrorDesc = null;
    private volatile int lastErrorCode = 0;

    /**
     * 导航代次计数器：每次"显式发起导航"就 +1。
     *
     * 用途：新建标签时会先加载 about:blank 预热渲染，再用 postDelayed(400ms) 加载
     * 真实页或主页。若在这 400ms 内外部（自动化 API / URL 栏）发起了导航，
     * 那个延迟回调必须让位，否则会把新导航覆盖掉。
     *
     * 为什么不能用 WebView.getUrl() 判断：getUrl() 返回的是**当前已提交页面**的地址，
     * 新导航在页面 commit 之前它仍然返回旧值（实测在这个窗口内仍返回 about:blank），
     * 所以"还是空白页"并不能说明"没人导航过"。必须用一个独立的、由发起方主动递增的计数器。
     * （这是真机冷启动实测复现出来的问题，见 v1.3.4）
     */
    private volatile int navigationGeneration = 0;

    public int navigationGeneration() {
        return navigationGeneration;
    }

    /** 由 Activity 的 URL 栏/主页/前进后退等直接 loadUrl 路径调用 */
    public void bumpNavigationGeneration() {
        navigationGeneration++;
    }

    public WebController(Activity activity) {
        this.activity = activity;
    }

    public void attach(WebView wv) {
        this.webView = wv;
    }

    // ---------- 由 MainActivity 的 WebViewClient 回调驱动 ----------

    /** 页面开始加载（可能是新导航，也可能是初始空白页） */
    public void onPageStarted(String url) {
        this.loading = true;
        this.pageStartedAt = System.currentTimeMillis();
        this.lastLoadFailed = false;
        this.lastErrorDesc = null;
        this.lastErrorCode = 0;
    }

    /** 主框架加载出错（由 WebViewClient.onReceivedError 调用） */
    public void onLoadError(int errorCode, String description) {
        this.lastLoadFailed = true;
        this.lastErrorCode = errorCode;
        this.lastErrorDesc = description;
    }

    /** 页面加载完成 */
    public void onPageFinished(String url) {
        this.loading = false;
        this.everLoaded = true;
        // 若当前页面正是"会话恢复"的那一个，清除标记（说明它已被重新加载过）
        if (url != null && url.equals(sessionRestoredUrl)) {
            sessionRestoredUrl = null;
        }
    }

    /** 标记某个地址是"会话恢复"得到的，尚未真正重新加载 */
    public void markSessionRestored(String url) {
        this.sessionRestoredUrl = url;
        this.pageStartedAt = 0;
        this.loading = false;
        this.everLoaded = false;
    }

    public boolean isLoading() {
        return loading;
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
        return navigate(url, false, 0);
    }

    /**
     * 导航。waitForLoad=true 时阻塞等待该次导航真正结束（加载完成 / 超时），
     * 并把真实结局（成功/失败页/超时）返回给调用方。
     *
     * 默认（waitForLoad=false）仍是立即返回，但会额外给出 requestedUrl / beforeUrl / loading，
     * 让调用方可以判断"导航是否已生效"，而不必依赖 info 里的旧页标题。
     */
    public JSONObject navigate(final String url, boolean waitForLoad, long waitTimeoutMs) {
        // 注意：WebView.getUrl() 必须在 UI 线程调用，否则在部分机型上返回 null 或抛异常。
        // 因此 beforeUrl 要在 call() 的 UI 线程 lambda 内部取，不能在 HTTP 线程里取。
        final String[] beforeHolder = new String[1];
        JSONObject r = call(wv -> {
            beforeHolder[0] = wv.getUrl();
            wv.loadUrl(url);
        }, 5000);
        final String before = beforeHolder[0];
        navigationGeneration++;
        lastRequestedUrl = url;
        loading = true;
        pageStartedAt = System.currentTimeMillis();
        lastLoadFailed = false;
        lastErrorDesc = null;
        lastErrorCode = 0;
        // 发起导航即取消"会话恢复"标记：此时已在主动加载新页面
        sessionRestoredUrl = null;
        try {
            r.put("beforeUrl", before == null ? JSONObject.NULL : before);
            r.put("requestedUrl", url);
            r.put("loading", true);
        } catch (Exception ignored) {}
        if (!waitForLoad) {
            return r;
        }
        // 阻塞等待加载结束
        long deadline = System.currentTimeMillis() + Math.max(waitTimeoutMs, 1000);
        while (System.currentTimeMillis() < deadline) {
            if (!loading) {
                break;
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return withLoadOutcome(r, deadline);
    }

    /** 给导航结果补上最终结局（供 wait=1 使用） */
    private JSONObject withLoadOutcome(JSONObject r, long deadline) {
        try {
            JSONObject s = status().optJSONObject("result");
            String finalUrl = s == null ? null : s.optString("url");
            String title = s == null ? null : s.optString("title");
            r.put("finalUrl", finalUrl == null ? JSONObject.NULL : finalUrl);
            r.put("finalTitle", title == null ? JSONObject.NULL : title);
            r.put("loading", loading);
            r.put("loadFailed", lastLoadFailed);
            if (lastErrorDesc != null) r.put("loadError", lastErrorDesc);
            if (lastErrorCode != 0) r.put("loadErrorCode", lastErrorCode);

            /**
             * 判断取向：只有当"当前 URL 确实是本次请求的那个"时，才允许用标题/错误标志下结论。
             *
             * 原因（实测复现）：WebView 的 title 更新滞后于 loading 状态。
             * 若上一个页面是失败页（title='网页无法打开'），紧接着导航到正常页，
             * 会出现 loadFailed=false 但 title 仍是旧失败页标题的窗口期。
             * 此时若用标题判断，就会把正常页误报为失败页（假阳性）。
             * 早期版本正是因此出现"偶发误判"，所以这里必须先用 URL 确认"页面已换"。
             */
            boolean urlIsRequested = finalUrl != null && lastRequestedUrl != null
                    && finalUrl.contains(lastRequestedUrl.replaceFirst("/+$", ""));

            if (loading) {
                r.put("loadOutcome", "timeout");
            } else if (sessionRestoredUrl != null && finalUrl != null
                    && finalUrl.equals(sessionRestoredUrl)) {
                r.put("loadOutcome", "session_restored");
            } else if (urlIsRequested) {
                // URL 已确认是本页，此时 title/错误标志才可信
                r.put("loadOutcome", lastLoadFailed ? "error_page" : "finished");
            } else {
                // URL 尚不匹配：不妄下结论，交由客户端就绪门禁继续确认
                r.put("loadOutcome", lastLoadFailed ? "error_page" : "pending");
            }
        } catch (Exception ignored) {}
        return r;
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
        return call(wv -> {
            wv.stopLoading();
            if (Build.VERSION.SDK_INT >= 19) {
                wv.evaluateJavascript("try{window.stop();}catch(e){}", null);
            }
        }, 5000);
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
            // ---- 加载状态：让调用方能识别"旧页冒充新页" ----
            o.put("loading", loading);
            o.put("everLoaded", everLoaded);
            o.put("pageStartedAt", pageStartedAt);
            // 页面已存活毫秒数（0 表示未知）：URL 相同但已存在很久 => 很可能是恢复的旧页
            o.put("pageAgeMs", pageStartedAt > 0 ? System.currentTimeMillis() - pageStartedAt : 0);
            o.put("lastRequestedUrl", lastRequestedUrl == null ? JSONObject.NULL : lastRequestedUrl);
            o.put("sessionRestored", sessionRestoredUrl != null);
            o.put("sessionRestoredUrl", sessionRestoredUrl == null ? JSONObject.NULL : sessionRestoredUrl);
            o.put("lastLoadFailed", lastLoadFailed);
            o.put("lastErrorDesc", lastErrorDesc == null ? JSONObject.NULL : lastErrorDesc);
            o.put("lastErrorCode", lastErrorCode);
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
        // WebGL/动画页面依赖 rAF。visibilityState=hidden 可能是脏状态
        // （vivo 冻结恢复后 WebView 未收到可见性回调，实际屏幕显示正常），
        // 因此不依赖它做失败判断，而是强制恢复渲染后再截。
        String vis = checkVisibility();
        if ("hidden".equals(vis)) {
            forceRenderWakeup();
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            vis = checkVisibility();
        }
        // 检测 rAF 节流：低活跃窗口（无人触摸/锁屏）会被系统压到极低帧率，
        // WebGL 模型渲染不出来 → 截图空白。诊断字段 rafFps 供上层识别。
        double rafFps = checkRafFps();
        if (rafFps < 15) {
            forceRenderWakeup();
            for (int i = 0; i < 10; i++) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                rafFps = checkRafFps();
                if (rafFps >= 15) break;
            }
        }
        // 优先 PixelCopy：截取真实渲染帧（与屏幕所见一致，避免 view.draw 拿到旧帧）
        JSONObject pc = tryPixelCopy();
        if (pc != null) {
            try {
                if (vis != null) pc.put("vis", vis);
                pc.put("rafFps", Math.round(rafFps * 10) / 10.0);
            } catch (Exception ignored) {}
            return pc;
        }
        // 回退：view.draw + measure 兑底
        JSONObject fb = ui(wv -> {
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
            return toDataUrl(bmp);
        }, 8000);
        try {
            if (vis != null) fb.put("vis", vis);
            fb.put("rafFps", Math.round(rafFps * 10) / 10.0);
        } catch (Exception ignored) {}
        return fb;
    }

    /** 检测 rAF 实际帧率（低活跃窗口会被系统节流到个位数 fps） */
    private double checkRafFps() {
        try {
            JSONObject e1 = evaluate("window.__rafN=(window.__rafN||0)+1;"
                    + "if(!window.__rafRun){window.__rafRun=true;"
                    + "(function l(){window.__rafN++;requestAnimationFrame(l)})();}"
                    + "window.__rafN", 3000);
            if (!e1.optBoolean("ok", false)) return -1;
            int start = Integer.parseInt(jsonUnescape(e1.optString("result")));
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            JSONObject e2 = evaluate("window.__rafN", 3000);
            if (!e2.optBoolean("ok", false)) return -1;
            int end = Integer.parseInt(jsonUnescape(e2.optString("result")));
            return (end - start) / 0.6;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 强制恢复 WebView 页面可见性与渲染（rAF/WebGL） */
    private void forceRenderWakeup() {
        try {
            activity.runOnUiThread(() -> {
                try {
                    WebView wv = webView;
                    if (wv == null) return;
                    wv.onResume();
                    wv.dispatchWindowVisibilityChanged(android.view.View.VISIBLE);
                    wv.setVisibility(android.view.View.VISIBLE);
                    wv.invalidate();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** 检查页面 visibilityState（hidden 时 WebGL/动画 rAF 暂停） */
    private String checkVisibility() {
        try {
            JSONObject e = evaluate("document.visibilityState", 3000);
            if (e.optBoolean("ok", false)) {
                String r = e.optString("result", "");
                if (r.startsWith("\"")) {
                    try { r = new JSONObject("{\"v\":" + r + "}").getString("v"); } catch (Exception ignored) {}
                }
                return r;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** PixelCopy 截取窗口真实渲染帧（含浏览器 UI，与用户屏幕一致） */
    private JSONObject tryPixelCopy() {
        if (Build.VERSION.SDK_INT < 26) return null;
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] b = new Bitmap[1];
            final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
            activity.runOnUiThread(() -> {
                try {
                    final WebView wv = webView;
                    final android.view.View decor = activity.getWindow().getDecorView();
                    final Runnable grab = () -> {
                        try {
                            int dw = decor.getWidth();
                            int dh = decor.getHeight();
                            if (dw <= 0 || dh <= 0) { latch.countDown(); return; }
                            final Bitmap fb = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
                            try {
                                android.view.PixelCopy.request(activity.getWindow(), fb, r -> {
                                    if (r == android.view.PixelCopy.SUCCESS) b[0] = fb;
                                    latch.countDown();
                                }, ui);
                            } catch (Exception e) {
                                latch.countDown();
                            }
                        } catch (Throwable t) {
                            latch.countDown();
                        }
                    };
                    if (wv != null) {
                        try { wv.onResume(); } catch (Exception ignored) {}
                        // 强制重建渲染 surface + 恢复页面可见性（WebGL/rAF）
                        try {
                            wv.dispatchWindowVisibilityChanged(android.view.View.VISIBLE);
                        } catch (Exception ignored) {}
                        try {
                            wv.setVisibility(android.view.View.GONE);
                            wv.setVisibility(android.view.View.VISIBLE);
                        } catch (Exception ignored) {}
                        wv.invalidate();
                        // 等 WebView 渲染完成再截（避免拿到旧帧/白帧）
                        try {
                            wv.postVisualStateCallback(2000, new WebView.VisualStateCallback() {
                                @Override
                                public void onComplete(long requestId) {
                                    ui.post(grab);
                                }
                            });
                            return;
                        } catch (Exception ignored) {}
                    }
                    grab.run();
                } catch (Throwable t) {
                    latch.countDown();
                }
            });
            if (!latch.await(4000, TimeUnit.MILLISECONDS)) return null;
            if (b[0] == null) return null;
            return okVal(toDataUrl(b[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private String toDataUrl(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
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