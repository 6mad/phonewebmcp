package com.phonewebmcp.gu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int API_PORT = 8765;
    private static final int MAX_TABS = 8;

    // ---- 视图 ----
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private EditText urlBar;
    private ProgressBar progress;
    private FrameLayout container;

    // ---- 状态 ----
    private final List<Tab> tabs = new ArrayList<>();
    private int tabSeq = 0;
    private Tab current;
    private WebController controller;
    private ApiServer apiServer;
    private String token;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 浏览器标签 */
    private static class Tab {
        final int id;
        final WebView webView;
        String title = "新标签页";
        String url = "";
        int progress = 0;
        Button tabButton;

        Tab(int id, WebView wv) {
            this.id = id;
            this.webView = wv;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlBar = findViewById(R.id.url_bar);
        progress = findViewById(R.id.progress);
        tabStrip = findViewById(R.id.tab_strip);
        tabScroll = findViewById(R.id.tab_scroll);
        container = findViewById(R.id.webview_container);

        controller = new WebController(this);

        bindButtons();
        setupApiServer();

        // 启动前台常驻服务（防冻结）
        startForegroundServiceCompat();

        // 首个标签
        String openUrl = handleIntent(getIntent());
        newTab(openUrl != null ? openUrl : null);
    }

    // ================= 标签管理 =================

    private void newTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            toast("标签过多（最多 " + MAX_TABS + " 个），请先关闭一些");
            return;
        }
        WebView wv = new WebView(this);
        setupWebView(wv);
        final Tab tab = new Tab(++tabSeq, wv);
        tabs.add(tab);

        // 标签条按钮
        Button b = new Button(this);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(14, 0, 14, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        tab.tabButton = b;
        b.setOnClickListener(v -> switchTab(tab));
        b.setOnLongClickListener(v -> { closeTab(tab); return true; });
        tabStrip.addView(b);

        container.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        switchTab(tab);

        if (url != null) {
            wv.loadUrl(url);
        } else {
            wv.loadUrl(buildHomePage());
            tab.title = "新标签页";
        }
        renderTabStrip();
    }

    private void switchTab(Tab tab) {
        // 隐藏其他
        for (Tab t : tabs) {
            t.webView.setVisibility(t == tab ? View.VISIBLE : View.GONE);
        }
        current = tab;
        controller.attach(tab.webView);
        urlBar.setText(tab.url);
        progress.setProgress(tab.progress);
        // 强制重新布局：后台/冻结期间 addView 的布局请求可能被丢弃，
        // 导致新建标签的 WebView 从未被 layout（视口 0、截图失败）
        tab.webView.requestLayout();
        container.requestLayout();
        renderTabStrip();
    }

    private void closeTab(Tab tab) {
        if (tabs.size() <= 1) {
            toast("至少保留一个标签");
            return;
        }
        int idx = tabs.indexOf(tab);
        tabs.remove(idx);
        tabStrip.removeView(tab.tabButton);
        container.removeView(tab.webView);
        tab.webView.destroy();
        if (current == tab) {
            Tab next = tabs.get(Math.max(0, idx - 1));
            switchTab(next);
        }
        renderTabStrip();
    }

    /** 渲染标签条：标题 + 当前高亮 */
    private void renderTabStrip() {
        for (Tab t : tabs) {
            String label = t.title;
            if (label.length() > 8) label = label.substring(0, 8) + "…";
            t.tabButton.setText(label);
            boolean active = (t == current);
            t.tabButton.setBackgroundColor(active ? Color.rgb(37, 99, 235) : Color.rgb(51, 65, 85));
            t.tabButton.setTextColor(active ? Color.WHITE : Color.rgb(203, 213, 225));
        }
        tabScroll.post(() -> tabScroll.fullScroll(View.FOCUS_RIGHT));
    }

    // ================= WebView =================

    private void setupWebView(final WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        WebView.setWebContentsDebuggingEnabled(true);

        // JS 桥：起始页切换搜索引擎时持久化到 Android
        wv.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void setEngine(String template, String name) {
                getSharedPreferences("cfg", MODE_PRIVATE)
                        .edit().putString("searchEngine", template).apply();
            }
        }, "NativeBridge");

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("app://newtab")) {
                    newTab(null);
                    return true;
                }
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Tab t = findTab(view);
                if (t != null) {
                    t.title = view.getTitle() == null ? t.title : view.getTitle();
                    t.url = view.getUrl() == null ? url : view.getUrl();
                    if (t == current) {
                        urlBar.setText(t.url);
                    }
                    renderTabStrip();
                }
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                Tab t = findTab(view);
                if (t != null) {
                    t.progress = newProgress;
                    if (t == current) {
                        progress.setProgress(newProgress);
                        progress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
                        controller.onProgress(newProgress);
                    }
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                Tab t = findTab(view);
                if (t != null && title != null && !title.isEmpty()) {
                    t.title = title;
                    renderTabStrip();
                }
            }
        });
    }

    private Tab findTab(WebView view) {
        for (Tab t : tabs) {
            if (t.webView == view) return t;
        }
        return null;
    }

    // ================= 起始页 =================

    private String buildHomePage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=utf-8><title>新标签页</title>")
          .append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
          .append("<style>")
          .append("*{margin:0;padding:0;box-sizing:border-box}")
          .append("body{font-family:system-ui,-apple-system,sans-serif;background:linear-gradient(160deg,#eef2ff 0%,#f8fafc 45%,#eff6ff 100%);min-height:100vh;padding:34px 18px}")
          .append(".wrap{max-width:640px;margin:0 auto;text-align:center}")
          .append(".greet{font-size:13px;color:#94a3b8;margin-bottom:4px}")
          .append(".logo{font-size:32px;font-weight:700;color:#1e293b;letter-spacing:2px;margin-bottom:26px}")
          .append(".logo span{color:#2563eb}")
          .append(".search{position:relative;max-width:560px;margin:0 auto}")
          .append(".search input{width:100%;padding:15px 100px 15px 20px;border-radius:28px;border:1px solid #e2e8f0;background:#fff;font-size:16px;outline:none;box-shadow:0 4px 16px rgba(30,41,59,.06)}")
          .append(".search input:focus{border-color:#2563eb;box-shadow:0 4px 20px rgba(37,99,235,.16)}")
          .append(".eng{position:absolute;right:6px;top:50%;transform:translateY(-50%);background:#eff6ff;color:#2563eb;border:1px solid #dbeafe;border-radius:20px;padding:8px 13px;font-size:13px;cursor:pointer;display:flex;align-items:center;gap:4px;max-width:150px}")
          .append(".eng b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
          .append(".panel{display:none;position:fixed;left:0;right:0;top:0;bottom:0;background:rgba(15,23,42,.45);z-index:10;justify-content:center;align-items:flex-start;padding-top:10vh}")
          .append(".panel.show{display:flex}")
          .append(".sheet{background:#fff;border-radius:20px;width:min(560px,92vw);max-height:68vh;overflow:auto;padding:18px 14px;box-shadow:0 20px 60px rgba(0,0,0,.25)}")
          .append(".sec{font-size:12px;color:#94a3b8;margin:14px 6px 8px}")
          .append(".grid2{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}")
          .append(".en{display:flex;align-items:center;gap:8px;padding:10px 12px;border-radius:12px;cursor:pointer;border:1px solid transparent}")
          .append(".en:hover{background:#f1f5f9}")
          .append(".en.cur{background:#eff6ff;border-color:#bfdbfe;color:#2563eb}")
          .append(".en .dot{width:8px;height:8px;border-radius:50%;flex-shrink:0}")
          .append(".dot.c{background:#2563eb}.dot.a{background:#8b5cf6}.dot.s{background:#10b981}")
          .append(".en .nm{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}")
          .append("h3{font-size:14px;color:#64748b;margin:28px 0 4px;text-align:left;padding-left:8px}")
          .append(".mark{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:10px}")
          .append(".site{display:block;background:#fff;border-radius:14px;padding:14px 6px;text-decoration:none;color:#1e293b;box-shadow:0 1px 4px rgba(30,41,59,.05);border:1px solid #eef2f7}")
          .append(".site .ic{width:38px;height:38px;border-radius:10px;margin:0 auto 6px;display:flex;align-items:center;justify-content:center;font-size:16px;color:#fff;font-weight:600}")
          .append(".site .nm{font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}")
          .append(".site.add{border:1px dashed #cbd5e1;background:transparent;color:#64748b}")
          .append(".svcs{display:flex;gap:8px;justify-content:center;flex-wrap:wrap;margin-top:20px}")
          .append(".svc{background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:7px 14px;font-size:12px;color:#475569;text-decoration:none;box-shadow:0 1px 3px rgba(30,41,59,.04)}")
          .append(".foot{color:#cbd5e1;font-size:11px;margin-top:26px}")
          .append("</style></head><body>")
          .append("<div class=wrap>")
          .append("<div class=greet id=greet></div>")
          .append("<div class=logo>古<span>月</span></div>")
          .append("<div class=search>")
          .append("<input id=q placeholder=\"搜索，或输入网址\" onkeydown=\"if(event.key==='Enter')doSearch()\">")
          .append("<button class=eng onclick=toggle()><b id=engName></b> ▾</button>")
          .append("</div>")
          .append("<div id=panel class=panel onclick=\"if(event.target===this)closePanel()\">")
          .append("<div class=sheet>")
          .append("<div class=sec>✨ 常用搜索</div><div class=grid2 id=gCommon></div>")
          .append("<div class=sec>🤖 AI 搜索</div><div class=grid2 id=gAi></div>")
          .append("<div class=sec>🛠 本机服务</div><div class=grid2 id=gSvc></div>")
          .append("</div></div>")
          .append("<h3>我的收藏</h3>")
          .append("<div class=mark id=marks></div>")
          .append("<div class=svcs id=svcs></div>")
          .append("<div class=foot>古月 · WebView 调试浏览器</div>")
          .append("</div>");

        // ---- JS 数据 ----
        StringBuilder c = new StringBuilder();
        StringBuilder a = new StringBuilder();
        for (String[] e : SearchEngines.LIST) {
            String item = "[" + jsStr(e[0]) + "," + jsStr(e[2]) + "]";
            if (e[1].equals("ai")) {
                if (a.length() > 0) a.append(",");
                a.append(item);
            } else {
                if (c.length() > 0) c.append(",");
                c.append(item);
            }
        }
        String services = "[['古月控制台','http://127.0.0.1:8765/','🔧'],['pi-web-ui','http://127.0.0.1:8800/','🖥'],['小说阅读器','http://127.0.0.1:8888/','📖']]";
        String curTpl = getCurrentEngine();
        String curName = SearchEngines.nameOf(curTpl);
        String marksJson = loadBookmarks().toString();

        sb.append("<script>")
          .append("const E=[").append(c).append("],A=[").append(a).append("],S=").append(services)
          .append(",CUR=").append(jsStr(curTpl)).append(",CURNAME=").append(jsStr(curName))
          .append(",MARKS=").append(marksJson).append(";")
          .append("const COLORS=['#2563eb','#059669','#d97706','#dc2626','#7c3aed','#0891b2','#db2777','#4f46e5'];")
          .append("let cur=CUR,curName=CURNAME;")
          .append("function engItem(name,url,cls){const d=document.createElement('div');d.className='en'+(url===cur?' cur':'');")
          .append("d.innerHTML='<span class=\"dot '+cls+'\"></span><span class=\"nm\">'+name+'</span>';d.onclick=()=>pick(url,name);return d;}")
          .append("function renderEngines(){const gc=document.getElementById('gCommon'),ga=document.getElementById('gAi');gc.innerHTML='';ga.innerHTML='';")
          .append("E.forEach(e=>gc.appendChild(engItem(e[0],e[1],'c')));A.forEach(e=>ga.appendChild(engItem(e[0],e[1],'a')));")
          .append("const gs=document.getElementById('gSvc');gs.innerHTML='';S.forEach(e=>{const d=engItem(e[0],e[1],'s');d.onclick=()=>{location.href=e[1]};gs.appendChild(d)});")
          .append("document.getElementById('engName').textContent=curName;}")
          .append("function pick(u,n){cur=u;curName=n;document.getElementById('engName').textContent=n;")
          .append("try{NativeBridge.setEngine(u,n)}catch(e){}closePanel();renderEngines();}")
          .append("function toggle(){document.getElementById('panel').classList.add('show')}")
          .append("function closePanel(){document.getElementById('panel').classList.remove('show')}")
          .append("function doSearch(){const q=document.getElementById('q').value.trim();if(!q)return;location.href=cur.replace('{q}',encodeURIComponent(q))}")
          .append("function greet(){const h=new Date().getHours();const g=h<5?'夜深了':h<9?'早上好':h<12?'上午好':h<14?'中午好':h<18?'下午好':'晚上好';")
          .append("document.getElementById('greet').textContent=g+' · '+['周日','周一','周二','周三','周四','周五','周六'][new Date().getDay()]}")
          .append("function renderMarks(){const m=document.getElementById('marks');m.innerHTML='';")
          .append("MARKS.forEach((b,i)=>{const a=document.createElement('a');a.className='site';a.href=b.url;")
          .append("a.innerHTML='<div class=\"ic\" style=\"background:'+COLORS[i%COLORS.length]+'\">'+b.title.charAt(0)+'</div><div class=\"nm\"></div>';")
          .append("a.querySelector('.nm').textContent=b.title;m.appendChild(a)});")
          .append("const add=document.createElement('a');add.className='site add';add.href='app://newtab';")
          .append("add.innerHTML='<div class=\"ic\" style=\"background:transparent;color:#94a3b8\">＋</div><div class=\"nm\">新标签</div>';m.appendChild(add);}")
          .append("function renderSvcs(){const sv=document.getElementById('svcs');S.forEach(e=>{const a=document.createElement('a');a.className='svc';a.href=e[1];a.textContent=e[2]+' '+e[0];sv.appendChild(a)});}")
          .append("renderEngines();greet();renderMarks();renderSvcs();")
          .append("</script></body></html>");

        // data URL 编码：URLEncoder 把空格编码为 +，但 data URL 解码不还原 +，必须换成 %20
        String encoded = URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return "data:text/html;charset=utf-8," + encoded;
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    private String getCurrentEngine() {
        return getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("searchEngine", SearchEngines.DEFAULT_TEMPLATE);
    }

    // ================= 收藏 =================

    private JSONArray loadBookmarks() {
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            return new JSONArray(sp.getString("bookmarks", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveBookmarks(JSONArray arr) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("bookmarks", arr.toString()).apply();
    }

    private void addBookmark(String title, String url) {
        if (url == null || url.isEmpty()) return;
        JSONArray arr = loadBookmarks();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null && url.equals(m.optString("url"))) {
                toast("已在收藏中");
                return;
            }
        }
        try {
            arr.put(new JSONObject().put("title", title == null ? url : title).put("url", url));
            saveBookmarks(arr);
            toast("已收藏: " + (title == null ? url : title));
        } catch (Exception e) {
            toast("收藏失败");
        }
    }

    private void showBookmarks() {
        JSONArray arr = loadBookmarks();
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 16, 40, 16);
        ScrollView sv = new ScrollView(this);
        sv.addView(ll);

        if (arr.length() == 0) {
            TextView tv = new TextView(this);
            tv.setText(R.string.bookmarks_empty);
            tv.setPadding(8, 20, 8, 20);
            tv.setTextColor(Color.rgb(100, 116, 139));
            ll.addView(tv);
        } else {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                final int idx = i;
                Button b = new Button(this);
                b.setText("🔖 " + m.optString("title", "书签"));
                b.setOnClickListener(v -> {
                    String u = arr.optJSONObject(idx).optString("url");
                    if (current != null) current.webView.loadUrl(u);
                });
                b.setOnLongClickListener(v -> {
                    JSONArray a2 = loadBookmarks();
                    if (idx < a2.length()) a2.remove(idx);
                    saveBookmarks(a2);
                    toast("已删除收藏");
                    showBookmarks();
                    return true;
                });
                ll.addView(b);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ================= 按钮 & 菜单 =================

    private void bindButtons() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (current != null && current.webView.canGoBack()) current.webView.goBack();
        });
        findViewById(R.id.btn_forward).setOnClickListener(v -> {
            if (current != null && current.webView.canGoForward()) current.webView.goForward();
        });
        findViewById(R.id.btn_reload).setOnClickListener(v -> {
            if (current != null) current.webView.reload();
        });
        findViewById(R.id.btn_new_tab).setOnClickListener(v -> newTab(null));
        findViewById(R.id.btn_bookmark).setOnClickListener(v -> {
            if (current != null) addBookmark(current.title, current.url);
        });
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu());
        urlBar.setOnEditorActionListener((tv, actionId, event) -> {
            goUrl();
            return true;
        });
    }

    private void showMenu() {
        String[] items = {
                getString(R.string.menu_new_tab),
                getString(R.string.menu_bookmarks),
                getString(R.string.menu_add_bookmark),
                getString(R.string.menu_home),
                getString(R.string.menu_ua),
                getString(R.string.menu_settings),
                getString(R.string.menu_cache),
                getString(R.string.menu_console),
                getString(R.string.menu_cookies),
                getString(R.string.menu_info),
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: newTab(null); break;
                        case 1: showBookmarks(); break;
                        case 2: if (current != null) addBookmark(current.title, current.url); break;
                        case 3: if (current != null) current.webView.loadUrl(getString(R.string.home_url)); break;
                        case 4: showUaMenu(); break;
                        case 5: showSettingsPanel(); break;
                        case 6: showCachePanel(); break;
                        case 7: showConsolePanel(); break;
                        case 8: showCookiePanel(); break;
                        case 9: showInfoPanel(); break;
                    }
                })
                .show();
    }

    private void showUaMenu() {
        final String[][] uas = {
                {"移动端默认", ""},
                {"桌面 Windows Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"},
                {"桌面 Windows Edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"},
                {"桌面 macOS Safari", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"},
                {"桌面 Linux Firefox", "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"},
                {"iPhone Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"},
                {"iPad Safari", "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"},
                {"微信内置浏览器", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49"},
                {"支付宝内置浏览器", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36 AlipayClient/10.6.30"},
                {"今日头条内置浏览器", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36 NewsArticle/6.5.2"},
                {"自定义…", null},
        };
        String[] labels = new String[uas.length];
        for (int i = 0; i < uas.length; i++) labels[i] = uas[i][0];
        new AlertDialog.Builder(this)
                .setItems(labels, (d, which) -> {
                    String ua = uas[which][1];
                    if (ua == null) {
                        EditText et = new EditText(this);
                        JSONObject st = controller.status().optJSONObject("result");
                        JSONObject settings = st == null ? new JSONObject() : st.optJSONObject("settings");
                        et.setText(settings.optString("userAgent", ""));
                        new AlertDialog.Builder(this)
                                .setTitle("自定义 User-Agent")
                                .setView(et)
                                .setPositiveButton("应用", (d2, w2) -> {
                                    controller.applySetting("userAgent", et.getText().toString().trim());
                                    toast("UA 已应用");
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } else {
                        controller.applySetting("userAgent", ua);
                        toast("UA: " + uas[which][0]);
                    }
                })
                .show();
    }

    private void goUrl() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty() || current == null) return;
        current.webView.loadUrl(normalizeUrl(input));
    }

    private String normalizeUrl(String input) {
        if (input.startsWith("http://") || input.startsWith("https://")) return input;
        if (input.contains(".") && !input.contains(" ") && !input.matches(".*[\\u4e00-\\u9fa5].*")) {
            return "https://" + input;
        }
        // 用当前选中的搜索引擎
        return getCurrentEngine().replace("{q}", URLEncoder.encode(input, StandardCharsets.UTF_8));
    }

    // ================= API 服务 =================

    private void setupApiServer() {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        token = cfg.getString("token", null);
        if (token == null) {
            token = ApiServer.randomToken();
            cfg.edit().putString("token", token).apply();
        }
        apiServer = new ApiServer(controller, API_PORT, token, line ->
                android.util.Log.i("WebViewTool", "[api] " + line));
        apiServer.setTabOps(tabOps);
        boolean started = apiServer.start();
        Toast.makeText(this, started
                ? "API: 127.0.0.1:" + API_PORT
                : "API 服务启动失败", Toast.LENGTH_LONG).show();
    }

    /** API 端多标签/收藏操作（供 ApiServer 路由调用，线程安全） */
    private final ApiServer.TabOps tabOps = new ApiServer.TabOps() {
        @Override
        public JSONObject newTab(String url) {
            return uiOp(() -> MainActivity.this.newTab(url));
        }

        @Override
        public JSONObject switchTab(int index) {
            return uiOp(() -> {
                if (index >= 0 && index < tabs.size()) MainActivity.this.switchTab(tabs.get(index));
            });
        }

        @Override
        public JSONObject closeTab(int index) {
            return uiOp(() -> {
                if (index >= 0 && index < tabs.size()) MainActivity.this.closeTab(tabs.get(index));
            });
        }

        @Override
        public JSONArray listTabs() {
            final JSONArray out = new JSONArray();
            uiOp(() -> {
                for (int i = 0; i < tabs.size(); i++) {
                    Tab t = tabs.get(i);
                    out.put(new JSONObject()
                            .put("index", i)
                            .put("title", t.title)
                            .put("url", t.url)
                            .put("progress", t.progress)
                            .put("current", t == current));
                }
            });
            return out;
        }

        @Override
        public JSONObject addBookmark(String title, String url) {
            uiOp(() -> MainActivity.this.addBookmark(title, url));
            return ok();
        }

        @Override
        public JSONObject removeBookmark(int index) {
            uiOp(() -> {
                JSONArray arr = loadBookmarks();
                if (index >= 0 && index < arr.length()) arr.remove(index);
                saveBookmarks(arr);
            });
            return ok();
        }

        @Override
        public JSONArray listBookmarks() {
            final JSONArray[] out = new JSONArray[1];
            uiOp(() -> out[0] = loadBookmarks());
            return out[0] == null ? new JSONArray() : out[0];
        }
    };

    private interface UiAction {
        void run() throws Exception;
    }

    private JSONObject uiOp(UiAction a) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            handler.post(() -> {
                try {
                    a.run();
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
            latch.await(5000, TimeUnit.MILLISECONDS);
            return ok();
        } catch (Exception e) {
            return fail(e.toString());
        }
    }

    private JSONObject ok() {
        try {
            return new JSONObject().put("ok", true);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject fail(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ================= 调试面板（保留） =================

    private void showSettingsPanel() {
        JSONObject st = controller.status().optJSONObject("result");
        JSONObject settings = st == null ? new JSONObject() : st.optJSONObject("settings");

        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 20);
        sv.addView(ll);

        addSwitch(ll, "JavaScript", settings.optBoolean("javaScriptEnabled", true), v ->
                controller.applySetting("javaScript", v ? "true" : "false"));
        addSwitch(ll, "DOM Storage", settings.optBoolean("domStorageEnabled", true), v ->
                controller.applySetting("domStorage", v ? "true" : "false"));
        addSwitch(ll, "JS 自动弹窗", settings.optBoolean("javaScriptCanOpenWindowsAutomatically", true), v ->
                controller.applySetting("javaScriptCanOpenWindowsAutomatically", v ? "true" : "false"));
        addSwitch(ll, "内置缩放控件", settings.optBoolean("builtInZoomControls", true), v ->
                controller.applySetting("builtInZoomControls", v ? "true" : "false"));
        addSwitch(ll, "Safe Browsing", settings.optBoolean("safeBrowsingEnabled", true), v ->
                controller.applySetting("safeBrowsing", v ? "true" : "false"));
        addSwitch(ll, "视频自动播放(无手势)", !settings.optBoolean("mediaPlaybackRequiresUserGesture", false), v ->
                controller.applySetting("mediaPlaybackRequiresUserGesture", v ? "false" : "true"));

        TextView cacheTv = new TextView(this);
        cacheTv.setText("缓存模式: " + settings.optString("cacheMode", "LOAD_DEFAULT"));
        cacheTv.setPadding(8, 12, 8, 4);
        ll.addView(cacheTv);
        String[] modes = {"LOAD_DEFAULT 默认", "LOAD_CACHE_ELSE_NETWORK 优先缓存", "LOAD_NO_CACHE 禁用缓存", "LOAD_CACHE_ONLY 仅缓存"};
        for (String m : modes) {
            String key = m.split(" ")[0];
            Button b = new Button(this);
            b.setText(m);
            b.setOnClickListener(v -> {
                controller.applySetting("cacheMode", key);
                Toast.makeText(this, "缓存模式: " + key, Toast.LENGTH_SHORT).show();
            });
            ll.addView(b);
        }

        new AlertDialog.Builder(this)
                .setTitle("WebView 设置")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void addSwitch(LinearLayout ll, String label, boolean checked, java.util.function.Consumer<Boolean> cb) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(8, 10, 8, 2);
        ll.addView(tv);
        Button b = new Button(this);
        b.setText(checked ? "开 ✓" : "关 ✗");
        b.setTextColor(checked ? Color.rgb(74, 222, 128) : Color.rgb(248, 113, 113));
        b.setOnClickListener(v -> {
            boolean now = b.getText().toString().startsWith("开");
            boolean next = !now;
            cb.accept(next);
            b.setText(next ? "开 ✓" : "关 ✗");
            b.setTextColor(next ? Color.rgb(74, 222, 128) : Color.rgb(248, 113, 113));
        });
        ll.addView(b);
    }

    private void showCachePanel() {
        long size = controller.cacheDirSize();
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 20);

        TextView tv = new TextView(this);
        tv.setText("应用缓存目录大小: " + (size < 0 ? "未知" : String.format(Locale.CHINA, "%.1f KB", size / 1024.0)));
        tv.setPadding(8, 4, 8, 12);
        ll.addView(tv);

        addCacheButton(ll, "清空 WebView 缓存", () -> { controller.clearCache(); toast("已清空缓存"); });
        addCacheButton(ll, "清除所有 Cookie", () -> { controller.clearCookies(); toast("已清除 Cookie"); });
        addCacheButton(ll, "清除表单数据/密码", () -> { controller.clearFormData(); toast("已清除表单数据"); });
        addCacheButton(ll, "全部清除", () -> { controller.clearEverything(); toast("已全部清除"); });

        new AlertDialog.Builder(this)
                .setTitle("缓存管理")
                .setView(ll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void addCacheButton(LinearLayout ll, String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> r.run());
        ll.addView(b);
    }

    private void showConsolePanel() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 20);

        EditText et = new EditText(this);
        et.setHint("输入 JavaScript，如 document.title");
        et.setSingleLine(false);
        et.setMinLines(2);
        ll.addView(et);

        TextView result = new TextView(this);
        result.setText("结果: ");
        result.setPadding(8, 12, 8, 4);
        ll.addView(result);

        Button run = new Button(this);
        run.setText("执行");
        run.setOnClickListener(v -> {
            String js = et.getText().toString().trim();
            if (js.isEmpty()) return;
            JSONObject r = controller.evaluate(js, 8000);
            if (r.optBoolean("ok", false)) {
                result.setText("结果: " + r.optString("result"));
            } else {
                result.setText("错误: " + r.optString("error"));
            }
        });
        ll.addView(run);

        new AlertDialog.Builder(this)
                .setTitle("JS 控制台")
                .setView(ll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showCookiePanel() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 20);

        String url = current == null || current.webView.getUrl() == null ? "" : current.webView.getUrl();
        JSONObject r = controller.getCookies(url);
        TextView tv = new TextView(this);
        tv.setText(r.optString("cookie", "(无 Cookie)"));
        tv.setTextSize(12);
        tv.setPadding(8, 4, 8, 12);
        ll.addView(tv);

        Button clear = new Button(this);
        clear.setText("清除全部 Cookie");
        clear.setOnClickListener(v -> {
            controller.clearCookies();
            toast("已清除");
            tv.setText("(已清除)");
        });
        ll.addView(clear);

        new AlertDialog.Builder(this)
                .setTitle("Cookie（当前页面）")
                .setView(ll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showInfoPanel() {
        String wvVersion = android.webkit.WebView.getCurrentWebViewPackage() == null
                ? "未知" : android.webkit.WebView.getCurrentWebViewPackage().versionName;
        String ua = controller.status().optJSONObject("result") == null ? ""
                : controller.status().optJSONObject("result").optJSONObject("settings").optString("userAgent", "");
        String info = "App: WebView 调试器 v1.1\n\n"
                + "WebView 内核: " + wvVersion + "\n"
                + "标签数: " + tabs.size() + " / " + MAX_TABS + "\n\n"
                + "=== 本地 JSON API ==="
                + "\n地址: http://127.0.0.1:" + API_PORT + "\n"
                + "Web 控制台: http://127.0.0.1:" + API_PORT + "/\n"
                + "Token: " + token + "\n\n"
                + "常用 API:\n"
                + "GET  /api/status\n"
                + "POST /api/navigate {url}\n"
                + "POST /api/search {query,engine}\n"
                + "POST /api/evaluate {js}\n"
                + "POST /api/cache/clear\n"
                + "GET  /api/screenshot\n"
                + "GET  /api/tabs\n"
                + "POST /api/tab/new {url?}\n"
                + "POST /api/tab/switch {index}\n"
                + "POST /api/tab/close {index}\n"
                + "GET  /api/bookmarks\n"
                + "POST /api/bookmark/add {title,url}\n"
                + "POST /api/bookmark/remove {index}\n"
                + "调用: 请求头 X-Api-Token: " + token;

        TextView tv = new TextView(this);
        tv.setText(info);
        tv.setTextSize(12);
        tv.setPadding(30, 20, 30, 20);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("信息")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ================= 生命周期 =================

    private void startForegroundServiceCompat() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(new Intent(this, KeepAliveService.class));
            } else {
                startService(new Intent(this, KeepAliveService.class));
            }
        } catch (Exception e) {
            android.util.Log.w("WebViewTool", "启动常驻服务失败: " + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 渲染修复：后台冻结/恢复后强制 WebView 恢复渲染
        if (current != null) {
            current.webView.onResume();
            current.webView.postInvalidate();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current != null) {
            current.webView.onPause();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String u = handleIntent(intent);
        if (u != null) {
            newTab(u);
        }
    }

    private String handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return null;
        String u = intent.getData().toString();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        return null;
    }

    @Override
    public void onBackPressed() {
        if (current != null && current.webView.canGoBack()) {
            current.webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (apiServer != null) apiServer.stop();
        for (Tab t : tabs) {
            try {
                t.webView.destroy();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}