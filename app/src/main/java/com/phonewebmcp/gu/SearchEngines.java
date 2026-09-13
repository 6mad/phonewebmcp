package com.phonewebmcp.gu;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 搜索引擎配置：{显示名, 分组(common/ai), URL模板(q 用 {q} 占位)}。
 * 分组: common=常用, ai=AI 搜索
 */
public class SearchEngines {
    public static final String[][] LIST = {
            // ---- 常用 ----
            {"百度", "common", "https://www.baidu.com/s?wd={q}"},
            {"必应", "common", "https://www.bing.com/search?q={q}"},
            {"Google", "common", "https://www.google.com/search?q={q}"},
            {"搜狗", "common", "https://www.sogou.com/web?query={q}"},
            {"360", "common", "https://www.so.com/s?q={q}"},
            {"夸克", "common", "https://quark.sm.cn/s?q={q}"},
            {"头条搜索", "common", "https://so.toutiao.com/search?keyword={q}"},
            {"维基百科", "common", "https://zh.wikipedia.org/w/index.php?search={q}"},
            {"知乎", "common", "https://www.zhihu.com/search?type=content&q={q}"},
            {"哔哩哔哩", "common", "https://search.bilibili.com/all?keyword={q}"},
            {"GitHub", "common", "https://github.com/search?q={q}"},
            {"YouTube", "common", "https://www.youtube.com/results?search_query={q}"},
            {"淘宝", "common", "https://s.taobao.com/search?q={q}"},
            {"京东", "common", "https://search.jd.com/Search?keyword={q}"},
            {"抖音", "common", "https://www.douyin.com/search/{q}"},
            {"微博", "common", "https://s.weibo.com/weibo?q={q}"},
            {"小红书", "common", "https://www.xiaohongshu.com/search_result?keyword={q}"},
            {"高德地图", "common", "https://ditu.amap.com/search?query={q}"},
            {"有道词典", "common", "https://dict.youdao.com/search?q={q}"},
            // ---- AI 搜索 ----
            {"秘塔 AI", "ai", "https://metaso.cn/?q={q}"},
            {"Perplexity", "ai", "https://www.perplexity.ai/search?q={q}"},
            {"Felo", "ai", "https://felo.ai/search?q={q}"},
            {"Devv (编程)", "ai", "https://devv.ai/?q={q}"},
            {"Phind (编程)", "ai", "https://www.phind.com/search?q={q}"},
            {"You.com", "ai", "https://you.com/search?q={q}"},
            {"Kagi", "ai", "https://kagi.com/search?q={q}"},
            {"豆包", "ai", "https://www.doubao.com/search?q={q}"},
            {"Kimi", "ai", "https://www.kimi.com/search?q={q}"},
            {"Bing Copilot", "ai", "https://www.bing.com/search?q={q}&form=MYHRBA"},
    };

    /** 默认引擎（百度） */
    public static final String DEFAULT_TEMPLATE = "https://www.baidu.com/s?wd={q}";

    /** 按显示名/别名查模板，找不到返回默认 */
    public static String template(String nameOrAlias) {
        if (nameOrAlias != null) {
            String n = nameOrAlias.trim().toLowerCase(Locale.ROOT);
            for (String[] e : LIST) {
                if (e[0].toLowerCase(Locale.ROOT).equals(n)) return e[2];
            }
            // 旧版别名兼容
            switch (n) {
                case "baidu": return "https://www.baidu.com/s?wd={q}";
                case "bing": return "https://www.bing.com/search?q={q}";
                case "google": return "https://www.google.com/search?q={q}";
                case "sogou": return "https://www.sogou.com/web?query={q}";
                case "so": case "360": return "https://www.so.com/s?q={q}";
                case "quark": return "https://quark.sm.cn/s?q={q}";
                case "zhihu": return "https://www.zhihu.com/search?type=content&q={q}";
                case "github": return "https://github.com/search?q={q}";
                case "bilibili": case "bili": return "https://search.bilibili.com/all?keyword={q}";
                case "perplexity": return "https://www.perplexity.ai/search?q={q}";
                case "metaso": case "秘塔": return "https://metaso.cn/?q={q}";
            }
        }
        return DEFAULT_TEMPLATE;
    }

    /** 按模板找显示名 */
    public static String nameOf(String template) {
        if (template == null) return "百度";
        for (String[] e : LIST) {
            if (e[2].equals(template)) return e[0];
        }
        return "百度";
    }

    public static String build(String engine, String query) {
        String tpl = template(engine);
        return tpl.replace("{q}", URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8));
    }
}
