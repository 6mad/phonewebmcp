package com.phonewebmcp.gu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 JSON API 服务（127.0.0.1 监听）。
 * MCP 风格：所有控制操作通过 HTTP + JSON 完成，供本机 Termux/脚本/Web 控制台调用。
 */
public class ApiServer {


    // ================= 版本与能力声明 =================

    /** API 版本：新增/变更端点的语义时递增 */
    public static final String API_VERSION = "1.2";

    /**
     * 能力清单。客户端应据此判断"该字段/参数是否可用"，而不是匹配版本号字符串。
     * 每个条目对应一组相关字段或参数：
     *   navigate_meta     → /api/navigate 返回 beforeUrl / requestedUrl / loading
     *   navigate_wait     → /api/navigate 支持 wait=1 与 waitTimeoutMs，返回 loadOutcome
     *   load_state        → /api/status 返回 loading / everLoaded / pageAgeMs
     *   session_restored  → /api/status 返回 sessionRestored / sessionRestoredUrl
     *   load_error        → onReceivedError 上报 lastLoadFailed / lastErrorDesc / lastErrorCode
     *   version_info      → /api/info 返回 apiVersion / appVersionName / appVersionCode
     */
    public static final String[] FEATURES = {
            "navigate_meta",
            "navigate_wait",
            "load_state",
            "session_restored",
            "load_error",
            "version_info",
    };
    private final WebController controller;
    private final int port;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile String consoleHtml;
    private final OnLogListener logListener;
    private volatile TabOps tabOps;

    /** 多标签/收藏操作接口（由 MainActivity 实现） */
    public interface TabOps {
        JSONObject newTab(String url);
        JSONObject switchTab(int index);
        JSONObject closeTab(int index);
        JSONObject toggleFullscreen();
        JSONArray listTabs();
        JSONObject addBookmark(String title, String url);
        JSONObject removeBookmark(int index);
        JSONArray listBookmarks();
    }

    public void setTabOps(TabOps ops) {
        this.tabOps = ops;
    }

    public interface OnLogListener {
        void onLog(String line);
    }

    public ApiServer(WebController controller, int port, String token, OnLogListener l) {
        this.controller = controller;
        this.port = port;
        this.token = token;
        this.logListener = l;
    }

    public String getToken() { return token; }
    public int getPort() { return port; }

    public boolean start() {
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
            pool = Executors.newFixedThreadPool(4);
            running.set(true);
            Thread t = new Thread(this::acceptLoop, "api-server");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (running.get()) log("服务器接受连接失败: " + e);
            }
        }
    }

    // ---------- HTTP 处理 ----------

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String rawPath = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(idx + 1).trim();
                    headers.put(k, v);
                    if (k.equals("content-length")) contentLength = (int) Long.parseLong(v);
                }
            }

            byte[] body = new byte[0];
            if (contentLength > 0 && contentLength < 10 * 1024 * 1024) {
                body = readExact(in, contentLength);
            }

            String[] pathSplit = rawPath.split("\\?", 2);
            String path = pathSplit[0];
            Map<String, String> query = new HashMap<>();
            if (pathSplit.length > 1) parseQuery(pathSplit[1], query);

            String clientToken = headers.get("x-api-token");
            if (clientToken == null) clientToken = query.get("token");

            // 内置 Web 控制台（无需 token）
            if (method.equals("GET") && path.equals("/")) {
                sendHtml(out, getConsoleHtml());
                return;
            }

            if (!path.startsWith("/api/")) {
                sendJson(out, 404, new JSONObject().put("ok", false).put("error", "not found: " + path));
                return;
            }

            if (clientToken == null || !clientToken.equals(token)) {
                sendJson(out, 401, new JSONObject().put("ok", false).put("error", "unauthorized"));
                return;
            }

            log(method + " " + rawPath);
            JSONObject resp = route(method, path, query, body);
            if (resp == null) resp = new JSONObject().put("ok", false).put("error", "not found");
            sendJson(out, 200, resp);
        } catch (Exception e) {
            log("请求处理异常: " + e);
        }
    }

    private JSONObject route(String method, String path, Map<String, String> query, byte[] body) throws Exception {
        JSONObject req = new JSONObject();
        if (body.length > 0) {
            try { req = new JSONObject(new String(body, StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        }
        String q = (String) getFirst(req, query, "url");

        switch (path) {
            // ---- 状态与信息 ----
            case "/api/status":
                return withInfo(controller.status());
            case "/api/info":
                return info();
            // ---- 导航 ----
            case "/api/navigate": {
                if (q == null) return err("缺少 url 参数");
                // wait=1 时阻塞到本次导航真正结束，并返回 loadOutcome/finalUrl/finalTitle，
                // 使调用方无需依赖 info 里的旧页标题来判断成功与否。
                Object waitObj = getFirst(req, query, "wait");
                boolean wait = waitObj != null && ("1".equals(waitObj.toString())
                        || "true".equalsIgnoreCase(waitObj.toString()));
                Object toObj = getFirst(req, query, "waitTimeoutMs");
                long to = 20000;
                if (toObj != null) {
                    try { to = Long.parseLong(toObj.toString()); } catch (Exception ignored) {}
                }
                return withInfo(controller.navigate(q, wait, to));
            }
            case "/api/search": {
                String query2 = (String) getFirst(req, query, "query");
                String engine = (String) getFirst(req, query, "engine");
                if (query2 == null) return err("缺少 query 参数");
                String url = SearchEngines.build(engine, query2);
                return withInfo(controller.navigate(url));
            }
            case "/api/back": return withInfo(controller.back());
            case "/api/forward": return withInfo(controller.forward());
            case "/api/reload": return withInfo(controller.reload());
            case "/api/stop": return withInfo(controller.stop());
            case "/api/home": return withInfo(controller.navigate("https://www.baidu.com"));
            // ---- JS / DOM ----
            case "/api/evaluate": {
                String js = (String) getFirst(req, query, "js");
                if (js == null) return err("缺少 js 参数");
                return controller.evaluate(js, 8000);
            }
            case "/api/dom": return controller.domInfo();
            case "/api/links": return controller.links();
            case "/api/history": return controller.history();
            case "/api/screenshot": return controller.screenshot();
            // ---- 页面交互（自动化） ----
            case "/api/scroll": {
                String sel = (String) getFirst(req, query, "selector");
                String dir = (String) getFirst(req, query, "dir");
                if (sel != null) {
                    String pos = (String) getFirst(req, query, "position");
                    return controller.scrollPage(new JSONObject().put("selector", sel).put("position", pos == null ? "center" : pos));
                }
                if (dir != null) {
                    Object px = getFirst(req, query, "px");
                    return controller.scrollPage(new JSONObject().put("dir", dir).put("px", px == null ? 400 : Long.parseLong(px.toString())));
                }
                Object x = getFirst(req, query, "x");
                Object y = getFirst(req, query, "y");
                if (x != null && y != null) {
                    return controller.scrollPage(new JSONObject().put("x", Long.parseLong(x.toString())).put("y", Long.parseLong(y.toString())));
                }
                return err("需要 selector 或 dir 或 x/y");
            }
            case "/api/click": {
                JSONObject c = new JSONObject();
                for (String k : new String[]{"selector", "text", "x", "y"}) {
                    if (req.has(k) || query.containsKey(k)) c.put(k, getFirst(req, query, k));
                }
                return controller.clickPage(c);
            }
            case "/api/fill": {
                String sel = (String) getFirst(req, query, "selector");
                String val = (String) getFirst(req, query, "value");
                if (sel == null || val == null) return err("需要 selector 和 value 参数");
                return controller.fillField(new JSONObject().put("selector", sel).put("value", val));
            }
            case "/api/wait": {
                Object ms = getFirst(req, query, "ms");
                long wait = ms == null ? 1000 : Long.parseLong(ms.toString());
                try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
                return okVal(null);
            }
            case "/api/waitFor": {
                String sel = (String) getFirst(req, query, "selector");
                if (sel == null) return err("需要 selector 参数");
                Object to = getFirst(req, query, "timeoutMs");
                return controller.waitFor(new JSONObject().put("selector", sel)
                        .put("timeoutMs", to == null ? 10000 : Long.parseLong(to.toString())));
            }
            // ---- 缓存 ----
            case "/api/cache/clear": return controller.clearCache();
            case "/api/cookies/clear": return controller.clearCookies();
            case "/api/form/clear": return controller.clearFormData();
            case "/api/clear-all": return controller.clearEverything();
            // ---- Cookie ----
            case "/api/cookies":
                if (method.equals("GET")) return controller.getCookies(q);
                return err("GET /api/cookies 查看 Cookie");
            case "/api/cookie/set": {
                String url = (String) getFirst(req, query, "url");
                String nv = (String) getFirst(req, query, "nameValue");
                if (nv == null) return err("缺少 nameValue 参数 (如 name=value; path=/; domain=.example.com)");
                if (url == null) return err("缺少 url 参数");
                return controller.setCookie(url, nv);
            }
            // ---- 多标签 ----
            case "/api/tabs":
                if (tabOps == null) return err("tab ops 不可用");
                return okVal(tabOps.listTabs());
            case "/api/tab/new":
                if (tabOps == null) return err("tab ops 不可用");
                return tabOps.newTab(q);
            case "/api/tab/switch": {
                if (tabOps == null) return err("tab ops 不可用");
                Object idx = getFirst(req, query, "index");
                if (idx == null) return err("缺少 index 参数");
                return tabOps.switchTab(Integer.parseInt(idx.toString()));
            }
            case "/api/tab/close": {
                if (tabOps == null) return err("tab ops 不可用");
                Object idx = getFirst(req, query, "index");
                if (idx == null) return err("缺少 index 参数");
                return tabOps.closeTab(Integer.parseInt(idx.toString()));
            }
            case "/api/fullscreen":
                if (tabOps == null) return err("tab ops 不可用");
                return tabOps.toggleFullscreen();
            // ---- 收藏 ----
            case "/api/bookmarks":
                if (tabOps == null) return err("tab ops 不可用");
                return okVal(tabOps.listBookmarks());
            case "/api/bookmark/add": {
                if (tabOps == null) return err("tab ops 不可用");
                String title = (String) getFirst(req, query, "title");
                String url = (String) getFirst(req, query, "url");
                if (url == null) return err("缺少 url 参数");
                return tabOps.addBookmark(title, url);
            }
            case "/api/bookmark/remove": {
                if (tabOps == null) return err("tab ops 不可用");
                Object idx = getFirst(req, query, "index");
                if (idx == null) return err("缺少 index 参数");
                return tabOps.removeBookmark(Integer.parseInt(idx.toString()));
            }
            // ---- 设置 ----
            case "/api/settings": {
                String key = (String) getFirst(req, query, "key");
                if (key == null) return err("缺少 key 参数 (GET 返回全部设置)");
                String value = (String) getFirst(req, query, "value");
                if (value == null) return err("缺少 value 参数");
                return withInfo(controller.applySetting(key, value));
            }
            default:
                return null;
        }
    }

    private JSONObject withInfo(JSONObject r) {
        try {
            if (r.optBoolean("ok", false)) {
                r.put("info", currentInfo());
            }
            return r;
        } catch (Exception e) {
            return r;
        }
    }

    private JSONObject currentInfo() throws Exception {
        JSONObject s = controller.status().optJSONObject("result");
        JSONObject o = new JSONObject();
        o.put("url", s == null ? null : s.optString("url"));
        o.put("title", s == null ? null : s.optString("title"));
        return o;
    }

    private JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "WebView 调试器");
        // apiVersion 必须随 API 能力变化而更新——旧版本长期硬编码 "1.0"，
        // 导致客户端无法判断服务端到底支持哪些端点，只能靠"行为差异"去猜版本
        // （历史上多次因此误判"新版已安装"）。现在配合 features 一起使用。
        o.put("apiVersion", API_VERSION);
        o.put("appVersionName", BuildConfig.VERSION_NAME);
        o.put("appVersionCode", BuildConfig.VERSION_CODE);
        // 能力协商：客户端据此判断能否使用某个字段/参数，而不是猜版本号
        JSONArray feats = new JSONArray();
        for (String f : FEATURES) feats.put(f);
        o.put("features", feats);
        o.put("webViewVersion", android.webkit.WebView.getCurrentWebViewPackage() == null ? "unknown"
                : android.webkit.WebView.getCurrentWebViewPackage().versionName);
        JSONObject s = controller.status();
        o.put("status", s);
        o.put("token", token);
        o.put("port", port);
        return o.put("ok", true);
    }

    private static Object getFirst(JSONObject req, Map<String, String> query, String key) {
        if (req.has(key)) return req.opt(key);
        return query.get(key);
    }

    private static JSONObject err(String msg) {
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
            o.put("result", result == null ? JSONObject.NULL : result);
        } catch (Exception ignored) {}
        return o;
    }

    // ---------- HTTP 底层 ----------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            if (bos.size() > 8192) break;
        }
        if (bos.size() == 0 && c == -1) return null;
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return buf;
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) out.put(URLDecoder.decode(pair, "UTF-8"), "");
                else out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
    }

    private static void sendJson(OutputStream out, int code, JSONObject obj) throws IOException {
        byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(code == 200 ? "OK" : code == 401 ? "Unauthorized" : "Not Found").append("\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(data.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, X-Api-Token\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static void sendHtml(OutputStream out, String html) throws IOException {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: text/html; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(data.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private String getConsoleHtml() {
        if (consoleHtml == null) {
            consoleHtml = ConsoleHtml.build(token);
        }
        return consoleHtml;
    }

    private void log(String s) {
        if (logListener != null) logListener.onLog(s);
    }

    /** 生成随机 token */
    public static String randomToken() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 请求历史（最近 N 条 API 调用），供 /api/logs */
    public java.util.List<String> recentLogs = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void addLog(String s) { recentLogs.add(s); if (recentLogs.size() > 100) recentLogs.remove(0); }
}