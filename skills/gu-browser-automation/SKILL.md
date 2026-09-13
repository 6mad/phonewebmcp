---
name: gu-browser-automation
description: 用手机上安装的「古月」WebView 浏览器（包名 com.phonewebmcp.gu，即 WebView 调试器）做页面自动化：导航、搜索、滚动、点击（选择器/文字/坐标）、填表、等待、截图、多标签、收藏，全部通过本机 JSON API（127.0.0.1:8765）控制，类似 Playwright/Selenium 但跑在真实手机上。用于打开真实网页、自动点击/填表/翻页、网页调试（改 UA、注入 JS、查 Cookie）、浏览器截图配合视觉验证。
---

# 古月浏览器自动化（gu-browser-automation）

用手机上安装的「古月」WebView 浏览器（包名 `com.phonewebmcp.gu`，即 WebView 调试器）做页面自动化：导航、搜索、滚动、点击（选择器/文字/坐标）、填表、等待元素、截图、多标签、收藏。全部通过本机 JSON API（`127.0.0.1:8765`）控制，类似 Playwright/Selenium 的能力但跑在真实手机上。

**遇到以下情况就主动使用本 skill**：
- 用户要求"打开某个网页/网站/搜索 xx"并希望真实浏览器渲染（可用古月浏览器而非 curl）
- 页面自动化：自动点击、填表、翻页、滚动加载、抓取页面正文/链接
- 网页调试：改 UA 看桌面版/微信版效果、注入 JS、检查 Cookie
- 需要手机浏览器截图确认渲染效果（配合 describe_image 视觉验证）
- 用户提到"古月浏览器""WebView 调试器""浏览器自动化"

## 前置条件

1. 古月 App 已安装（包名 com.phonewebmcp.gu，minSdk 24+）
2. App 进程可能在后台被 vivo 冻结 —— 每次操作前用 `am start -n com.phonewebmcp.gu/.MainActivity` 唤醒（客户端 gu.py 自动做）
3. 认证 token：`GET http://127.0.0.1:8765/` 返回的 Web 控制台 HTML 里 `const T='<32位hex>'`；token 持久化，进程重启不变

## 快速开始

```bash
PY=/data/data/com.termux/files/home/.pi/agent/skills/gu-browser-automation/scripts/gu.py
python3 $PY navigate https://www.bilibili.com     # 打开网页
python3 $PY search "人工智能" --engine "秘塔 AI"   # 搜索（引擎可选）
python3 $PY status                                 # 当前页面
python3 $PY dom                                    # 页面正文/链接摘要
python3 $PY screenshot --out ~/tmp/page.png        # 截图 → describe_image 验证
```

## API 总表（curl 直调也行）

鉴权头 `X-Api-Token: <token>`，或 `?token=` 参数。GET 无参、POST 传 JSON body。

### 导航与搜索
| 端点 | body | 说明 |
|---|---|---|
| `POST /api/navigate` | `{"url":"https://..."}` | 打开网址 |
| `POST /api/search` | `{"query":"...","engine":"秘塔 AI"}` | 搜索；engine 支持全部 29 个引擎名/别名 |
| `POST /api/back` `/forward` `/reload` | - | 前进后退刷新 |
| `GET /api/status` | - | URL/标题/进度/全部 WebView 设置 |
| `GET /api/history` | - | 当前标签历史栈 |

### 页面交互（自动化核心）
| 端点 | body | 说明 |
|---|---|---|
| `POST /api/scroll` | `{"dir":"down","px":500}` 或 `{"x":0,"y":0}` 或 `{"selector":".x","position":"center"}` | 滚动 |
| `POST /api/click` | `{"selector":"#kw"}` 或 `{"text":"百度一下"}` 或 `{"x":100,"y":200}` | 点击（坐标点击派发 pointerdown/mousedown/up/click 事件链） |
| `POST /api/fill` | `{"selector":"#kw","value":"词"}` | 填表（触发 input/change 事件） |
| `POST /api/evaluate` | `{"js":"document.title"}` | 执行任意 JS，返回 JSON 编码结果 |
| `POST /api/wait` | `{"ms":2000}` | 等待 |
| `POST /api/waitFor` | `{"selector":".result","timeoutMs":10000}` | 等待元素出现 |
| `GET /api/dom` | - | 正文文本(前3000) + 标题 + 前50链接 + meta |
| `GET /api/links` | - | 链接列表 |
| `GET /api/screenshot` | - | base64 PNG（data:image/png;base64,）。截取**窗口真实渲染帧**（含浏览器 UI，与屏幕一致）；App 需在前台，否则回退旧帧可能不准 |

### 数据与设置
| 端点 | body | 说明 |
|---|---|---|
| `POST /api/cache/clear` `/cookies/clear` `/form/clear` `/clear-all` | - | 清缓存/Cookie/表单/全部 |
| `GET /api/cookies` `POST /api/cookie/set` | `{"url","nameValue"}` | Cookie 查看/设置 |
| `POST /api/settings` | `{"key":"userAgent","value":"..."}` | 改设置：userAgent/javaScript/domStorage/cacheMode/safeBrowsing/mixedContent 等 |
| `GET /api/tabs` `POST /api/tab/new` `/switch` `/close` | `{"index":0}` / `{"url":"..."}` | 多标签（最多8个） |
| `GET /api/bookmarks` `POST /api/bookmark/add` `/remove` | `{"title","url"}` / `{"index":0}` | 收藏 |

### 搜索引擎（engine 参数，29 个，分两组）
- 常用：百度/必应/Google/搜狗/360/夸克/头条搜索/维基百科/知乎/哔哩哔哩/GitHub/YouTube/淘宝/京东/抖音/微博/小红书/高德地图/有道词典
- AI：秘塔 AI/Perplexity/Felo/Devv(编程)/Phind(编程)/You.com/Kagi/豆包/Kimi/Bing Copilot
- 别名：baidu/bing/google/sogou/so/quark/zhihu/github/bilibili/metaso/perplexity 等
- 主页搜索框选择后持久化，地址栏搜索词和 /api/search 默认跟随

## 自动化工作流（推荐）

```bash
PY=.../gu.py
# 1. 唤醒 + 导航
python3 $PY navigate https://www.baidu.com
# 2. 侦察 DOM（选择器不确定时）
python3 $PY eval 'JSON.stringify([...document.querySelectorAll("input")].map(i=>i.id||i.name))'
# 3. 交互
python3 $PY fill --selector "#index-kw" --value "关键词"
python3 $PY click --text "百度一下"
python3 $PY wait --ms 3000   # 或 waitFor --selector ...
# 4. 读取结果
python3 $PY dom
# 5. 截图 + 视觉验证（当前模型无视觉时必须用 describe_image 看图）
python3 $PY screenshot --out ~/tmp/pg.png
```

## 注意事项

- **vivo 冻结**：App 后台会被冻结（HTTP 000），`am start` 唤醒后 2-3 秒恢复；gu.py 每次自动唤醒。进程不会被杀，token 不变
- **渲染帧滞后**：App 后台/刚唤醒时 navigate/tab-new 后 document 已更新但屏幕渲染帧可能还是旧页——API 数据（status/dom）与截图可能不一致。**截图前务必确保 App 在前台**（gu.py 自动 wake），操作后等 2-3 秒再截图
- **WebGL/动画页面截图空白（重要）**：Three.js/WebGL/Canvas 动画依赖 requestAnimationFrame，页面在后台（`visibilityState=hidden`）时浏览器**暂停 rAF** → 模型/动画不渲染，截图只有背景色。这是浏览器标准行为，不是截图 bug。**自动化截图 WebGL 页面前必须保证手机亮屏 + App 真正在前台**（am start 唤醒但屏幕锁定时页面仍 hidden）。截图 API 响应含 `vis` 字段（visible/hidden）可诊断；`/api/screenshot` 检测到 hidden 会自动等 2 秒重试
- **data URL 主页**：`/api/status` 的 title 可能是 `data:text/html;...` 开头=新标签主页，属正常
- **页面未就绪**：导航后先 `wait`/`waitFor` 再交互；SPA 页面优先 `waitFor selector`
- **点击选择器失效**：优先 `--text`（按可见文字）或 `--x --y` 坐标点击；移动端站点选择器与桌面版不同，先 `eval` 侦察
- **evaluate 结果**：返回值是 JSON 编码字符串（如 `"\"标题\""`），两层 JSON 解析
- **多标签**：API 操作作用于当前标签；新建标签用 `/api/tab/new`
- 安装新版 APK 后必须确认：`/api/search engine=metaso` 返回 metaso.cn 即新版（旧版回退百度）
