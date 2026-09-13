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
    private Button reloadButton;
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
        boolean loading = false;
        int colorIdx = 0;
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
        reloadButton = findViewById(R.id.btn_reload);
        tabStrip = findViewById(R.id.tab_strip);
        tabScroll = findViewById(R.id.tab_scroll);
        container = findViewById(R.id.webview_container);

        controller = new WebController(this);

        setupEdgeToEdge();

        bindButtons();
        setupApiServer();

        // 启动前台常驻服务（防冻结）
        startForegroundServiceCompat();

        // 首个标签：恢复上次会话或新建主页
        String openUrl = handleIntent(getIntent());
        JSONArray saved = loadSavedTabs();
        if (saved != null && saved.length() > 0) {
            // 恢复会话标签（Activity 重建/进程重启后不丢失）
            for (int i = 0; i < saved.length(); i++) {
                JSONObject t = saved.optJSONObject(i);
                if (t == null) continue;
                newTab(t.optString("url", null), i == saved.length() - 1);
            }
            if (openUrl != null) newTab(openUrl, true);
        } else {
            newTab(openUrl != null ? openUrl : null, true);
        }
    }

    // ================= 标签管理 =================

    private void newTab(String url) {
        newTab(url, true);
    }

    private void newTab(String url, boolean makeCurrent) {
        if (tabs.size() >= MAX_TABS) {
            toast("标签过多（最多 " + MAX_TABS + " 个），请先关闭一些");
            return;
        }
        WebView wv = new WebView(this);
        setupWebView(wv);
        final Tab tab = new Tab(++tabSeq, wv);
        tab.colorIdx = tabSeq - 1;
        tabs.add(tab);
        tab.url = url == null ? "" : url;

        // 标签条按钮
        Button b = new Button(this);
        b.setTextSize(11);
        b.setTextColor(Color.rgb(100, 116, 139));
        b.setGravity(Gravity.CENTER);
        b.setPadding(14, 0, 14, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setAllCaps(false);
        tab.tabButton = b;
        b.setOnClickListener(v -> switchTab(tab));
        b.setOnLongClickListener(v -> { closeTab(tab); return true; });
        tabStrip.addView(b);

        container.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (makeCurrent) {
            switchTab(tab);
        } else {
            tab.webView.setVisibility(View.GONE);
        }

        // 关键修复：新 WebView 首次 loadUrl 会丢失渲染首帧（白屏）。
        // 先加载 about:blank 预热渲染 surface（触发首帧），再加载真实页面。
        wv.loadUrl("about:blank");
        wv.postDelayed(() -> {
            try {
                if (url != null) {
                    wv.loadUrl(url);
                } else {
                    wv.loadUrl(buildHomePage());
                    tab.title = "新标签页";
                }
            } catch (Exception ignored) {}
        }, 400);
        renderTabStrip();
    }

    private void switchTab(Tab tab) {
        // 隐藏其他
        for (Tab t : tabs) {
            t.webView.setVisibility(t == tab ? View.VISIBLE : View.GONE);
        }
        current = tab;
        controller.attach(tab.webView);
        // 切换到该标签时强制恢复渲染（后台/冻结期间 WebView 渲染可能暂停）
        try {
            tab.webView.onResume();
        } catch (Exception ignored) {}
        tab.webView.invalidate();
        urlBar.setText(tab.url);
        progress.setProgress(tab.progress);
        progress.bringToFront();
        progress.setVisibility(tab.loading ? View.VISIBLE : View.GONE);
        // 强制重新布局：后台/冻结期间 addView 的布局请求可能被丢弃，
        // 导致新建标签的 WebView 从未被 layout（视口 0、截图失败）
        tab.webView.requestLayout();
        container.requestLayout();
        renderTabStrip();
        scrollTabIntoView(tab);
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

    /** 标签胶囊配色：现代柔和色板，按创建顺序分配保证相邻不同色 */
    private static final int[] TAB_COLORS = {
            0xFF3B82F6, 0xFF8B5CF6, 0xFF06B6D4, 0xFF10B981,
            0xFFF59E0B, 0xFFEC4899, 0xFF6366F1, 0xFFEF4444
    };

    /** 渲染标签条：胶囊样式 + 相邻不同色 + 当前高亮 + 加载中显示「…」 */
    private void renderTabStrip() {
        float d = getResources().getDisplayMetrics().density;
        for (Tab t : tabs) {
            String label = t.loading ? t.title + "…" : t.title;
            if (label.length() > 9) label = label.substring(0, 9) + "…";
            t.tabButton.setText(label);
            int c = TAB_COLORS[t.colorIdx % TAB_COLORS.length];
            boolean active = (t == current);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(12 * d);
            if (active) {
                bg.setColor(c);
            } else {
                bg.setColor(Color.argb(0x24, Color.red(c), Color.green(c), Color.blue(c)));
                bg.setStroke((int) (1 * d), Color.argb(0x50, Color.red(c), Color.green(c), Color.blue(c)));
            }
            t.tabButton.setBackground(bg);
            t.tabButton.setTextColor(active ? Color.WHITE : c);
        }
        saveTabs();
    }

    /** 持久化标签会话（Activity 重建/进程重启后恢复） */
    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) {
                String u = t.url;
                if (u == null || u.isEmpty()) {
                    try { u = t.webView.getUrl() == null ? "" : t.webView.getUrl(); } catch (Exception ignored) {}
                }
                arr.put(new JSONObject().put("url", u == null ? "" : u).put("title", t.title));
            }
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("tabs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONArray loadSavedTabs() {
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            return new JSONArray(sp.getString("tabs", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 滚动标签条，让目标标签居中可见（点击哪个就定位到哪个，而不是滚到末尾） */
    private void scrollTabIntoView(Tab t) {
        if (t == null || t.tabButton == null) return;
        tabScroll.post(() -> {
            View v = t.tabButton;
            int x = v.getLeft();
            int target = Math.max(0, x - tabScroll.getWidth() / 2 + v.getWidth() / 2);
            tabScroll.smoothScrollTo(target, 0);
        });
    }

    // ---------- 慢加载提示 ----------

    private final java.util.Map<Tab, Runnable> slowTimers = new java.util.HashMap<>();

    private void startSlowTimer(final Tab t) {
        Runnable old = slowTimers.remove(t);
        if (old != null) handler.removeCallbacks(old);
        Runnable r = () -> {
            if (t.loading && t == current && findTab(t.webView) != null) {
                toast("页面加载较慢（已超过 10 秒），可点击 ✕ 停止");
            }
            slowTimers.remove(t);
        };
        slowTimers.put(t, r);
        handler.postDelayed(r, 10000);
    }

    private void cancelSlowTimer(Tab t) {
        Runnable r = slowTimers.remove(t);
        if (r != null) handler.removeCallbacks(r);
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

            @android.webkit.JavascriptInterface
            public void saveBookmarks(String json) {
                getSharedPreferences("cfg", MODE_PRIVATE)
                        .edit().putString("bookmarks", json).apply();
            }

            @android.webkit.JavascriptInterface
            public void saveServices(String json) {
                getSharedPreferences("cfg", MODE_PRIVATE)
                        .edit().putString("services", json).apply();
            }

            @android.webkit.JavascriptInterface
            public void saveTheme(String t) {
                getSharedPreferences("cfg", MODE_PRIVATE)
                        .edit().putString("theme", t).apply();
            }

            @android.webkit.JavascriptInterface
            public void reloadHome() {
                handler.post(() -> {
                    if (current != null) current.webView.loadUrl(buildHomePage());
                });
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Tab t = findTab(view);
                if (t != null) {
                    t.loading = true;
                    renderTabStrip();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Tab t = findTab(view);
                if (t != null) {
                    t.loading = false;
                    t.title = view.getTitle() == null ? t.title : view.getTitle();
                    t.url = view.getUrl() == null ? url : view.getUrl();
                    cancelSlowTimer(t);
                    if (t == current) {
                        urlBar.setText(t.url);
                        reloadButton.setText("↻");
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
                    t.loading = newProgress < 100;
                    if (t == current) {
                        boolean loading = t.loading;
                        reloadButton.setText(loading ? "✕" : "↻");
                        progress.setProgress(newProgress);
                        // 进度条悬浮于网页之上：WebView 是后 addView 的子层，必须提到顶层
                        progress.bringToFront();
                        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
                        controller.onProgress(newProgress);
                    }
                    renderTabStrip();
                    if (t.loading) {
                        startSlowTimer(t);
                    } else {
                        cancelSlowTimer(t);
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
        return HomePageBuilder.build(
                loadBookmarks().toString(),
                loadServices().toString(),
                getCurrentEngine(),
                SearchEngines.nameOf(getCurrentEngine()),
                getThemeMode());
    }

    /** 快捷导航数据（无数据时写入默认服务） */
    private JSONArray loadServices() {
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            String stored = sp.getString("services", "");
            if (!stored.isEmpty()) return new JSONArray(stored);
        } catch (Exception ignored) {}
        JSONArray def = new JSONArray();
        try {
            def.put(new JSONObject().put("name", "古月控制台").put("url", "http://127.0.0.1:8765/").put("icon", "\uD83D\uDD27"));
            def.put(new JSONObject().put("name", "pi-web-ui").put("url", "http://127.0.0.1:8800/").put("icon", "\uD83D\uDDA5"));
            def.put(new JSONObject().put("name", "小说阅读器").put("url", "http://127.0.0.1:8888/").put("icon", "\uD83D\uDCD6"));
            saveServices(def);
        } catch (Exception ignored) {}
        return def;
    }

    private void saveServices(JSONArray arr) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("services", arr.toString()).apply();
    }

    private String getThemeMode() {
        return getSharedPreferences("cfg", MODE_PRIVATE).getString("theme", "");
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

    /** 全屏切换：收起/展开标签栏和地址栏（带动画），网页占满屏幕 */
    private boolean fullscreen = false;

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        final View tagBar = findViewById(R.id.tag_bar);
        final View addrBar = findViewById(R.id.addr_bar);
        final Button fs = findViewById(R.id.btn_fullscreen);
        int dur = 280;
        if (fullscreen) {
            fs.setText("↓");
            // 隐藏状态栏图标（网页内容已延伸到状态栏区域，区域保持网页顶部颜色）
            setStatusBarHidden(true);
            tagBar.animate().translationY(-tagBar.getHeight()).alpha(0f).setDuration(dur)
                    .withEndAction(() -> tagBar.setVisibility(View.GONE));
            addrBar.animate().translationY(-tagBar.getHeight() - addrBar.getHeight()).alpha(0f).setDuration(dur)
                    .withEndAction(() -> addrBar.setVisibility(View.GONE));
        } else {
            fs.setText("^");
            setStatusBarHidden(false);
            setStatusBarIconsLight(true);
            tagBar.setVisibility(View.VISIBLE);
            addrBar.setVisibility(View.VISIBLE);
            tagBar.setTranslationY(-tagBar.getHeight());
            tagBar.setAlpha(0f);
            addrBar.setTranslationY(-tagBar.getHeight() - addrBar.getHeight());
            addrBar.setAlpha(0f);
            tagBar.animate().translationY(0).alpha(1f).setDuration(dur);
            addrBar.animate().translationY(0).alpha(1f).setDuration(dur);
        }
    }

    /** 隐藏/显示状态栏（仅隐藏图标；内容延伸使状态栏区域保持网页顶部颜色） */
    private void setStatusBarHidden(boolean hidden) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    if (hidden) {
                        c.hide(android.view.WindowInsets.Type.statusBars());
                    } else {
                        c.show(android.view.WindowInsets.Type.statusBars());
                    }
                }
            } else {
                View decor = getWindow().getDecorView();
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                if (hidden) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                decor.setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) {}
    }

    /** 状态栏图标颜色：浅色网页用深色图标，深色网页用浅色图标（跟随网页背景亮度） */
    private void adaptStatusBarIcons() {
        if (android.os.Build.VERSION.SDK_INT < 23 || current == null) return;
        try {
            current.webView.evaluateJavascript(
                    "(function(){var c=getComputedStyle(document.body).backgroundColor;"
                            + "var m=c.match(/\\d+/g);if(!m||m.length<3)return 'light';"
                            + "var l=(+m[0]*299 + +m[1]*587 + +m[2]*114)/1000;return l>150?'light':'dark';})()",
                    value -> setStatusBarIconsLight(!"\"dark\"".equals(value)));
        } catch (Exception ignored) {}
    }

    private void setStatusBarIconsLight(boolean light) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().getInsetsController().setSystemBarsAppearance(
                        light ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        (light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0)
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } catch (Exception ignored) {}
    }

    /** 沉浸式状态栏 + 毛玻璃（API 31+ 真实模糊，旧版半透明透出） */
    private void setupEdgeToEdge() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                getWindow().getInsetsController().setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                getWindow().setBackgroundBlurRadius(18);
            }
            // 沉浸式后内容延伸到状态栏下：标签栏用系统 insets 精确贴合状态栏高度
            // （比 status_bar_height 资源更准，兼容挖孔/手势条等差异）
            View tagBar = findViewById(R.id.tag_bar);
            tagBar.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                } else {
                    top = insets.getSystemWindowInsetTop();
                }
                v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
                // 全屏按钮与标签栏同行对齐
                View fs = findViewById(R.id.btn_fullscreen);
                if (fs != null) {
                    android.view.ViewGroup.MarginLayoutParams lp =
                            (android.view.ViewGroup.MarginLayoutParams) fs.getLayoutParams();
                    lp.topMargin = top + 8;
                    fs.setLayoutParams(lp);
                }
                return insets;
            });
        } catch (Exception e) {
            android.util.Log.w("WebViewTool", "edge-to-edge 失败: " + e);
        }
    }

    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
        if (h == 0) h = Math.round(24 * getResources().getDisplayMetrics().density);
        return h;
    }

    private void bindButtons() {
        findViewById(R.id.btn_fullscreen).setOnClickListener(v -> toggleFullscreen());
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (current != null && current.webView.canGoBack()) current.webView.goBack();
        });
        findViewById(R.id.btn_forward).setOnClickListener(v -> {
            if (current != null && current.webView.canGoForward()) current.webView.goForward();
        });
        findViewById(R.id.btn_reload).setOnClickListener(v -> {
            if (current == null) return;
            if (current.loading) {
                stopCurrentLoading();
            } else {
                current.webView.reload();
            }
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
                        case 3: if (current != null) current.webView.loadUrl(buildHomePage()); break;
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

    /** 彻底停止当前页面加载：stopLoading + window.stop() + 延迟复查 */
    private void stopCurrentLoading() {
        final WebView wv = current.webView;
        try {
            wv.stopLoading();
            if (android.os.Build.VERSION.SDK_INT >= 19) {
                wv.evaluateJavascript("try{window.stop();}catch(e){}", null);
            }
        } catch (Exception ignored) {}
        // 兜底复查：部分网络栈 stopLoading 后仍继续，稍后再补一刀
        handler.postDelayed(() -> {
            Tab t = current;
            if (t != null && t.webView == wv && t.webView.getProgress() < 100) {
                try {
                    wv.stopLoading();
                } catch (Exception ignored) {}
            }
        }, 500);
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
        public JSONObject toggleFullscreen() {
            return uiOp(() -> MainActivity.this.toggleFullscreen());
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
                            .put("current", t == current)
                            .put("shown", t.webView.isShown())
                            .put("visibility", t.webView.getVisibility()));
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
        handler.removeCallbacksAndMessages(null);
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