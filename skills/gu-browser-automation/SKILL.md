---
name: gu-browser-automation
description: 用手机上安装的「古月」WebView 浏览器（包名 com.pi.webviewtool，即 WebView 调试器）做页面自动化：导航、搜索、滚动、点击（选择器/文字/坐标）、填表、等待、截图、多标签、收藏，全部通过本机 JSON API（127.0.0.1:8765）控制，类似 Playwright/Selenium 但跑在真实手机上。用于打开真实网页、自动点击/填表/翻页、网页调试（改 UA、注入 JS、查 Cookie）、浏览器截图配合视觉验证。
---

# 古月浏览器自动化（gu-browser-automation）

用手机上安装的「古月」WebView 浏览器（包名 `com.pi.webviewtool`，即 WebView 调试器）做页面自动化：导航、搜索、滚动、点击（选择器/文字/坐标）、填表、等待元素、截图、多标签、收藏。全部通过本机 JSON API（`127.0.0.1:8765`）控制，类似 Playwright/Selenium 的能力但跑在真实手机上。

**遇到以下情况就主动使用本 skill**：
- 用户要求"打开某个网页/网站/搜索 xx"并希望真实浏览器渲染（可用古月浏览器而非 curl）
- 页面自动化：自动点击、填表、翻页、滚动加载、抓取页面正文/链接
- 网页调试：改 UA 看桌面版/微信版效果、注入 JS、检查 Cookie
- 需要手机浏览器截图确认渲染效果（配合 describe_image 视觉验证）
- 用户提到"古月浏览器""WebView 调试器""浏览器自动化"

## 前置条件

1. 古月 App 已安装（包名 com.pi.webviewtool，minSdk 24+）
2. App 进程可能在后台被 vivo 冻结 —— 每次操作前用 `am start -n com.pi.webviewtool/.MainActivity` 唤醒（客户端 gu.py 自动做）
3. 认证 token：`GET http://127.0.0.1:8765/` 返回的 Web 控制台 HTML 里 `const T='<32位hex>'`；token 持久化，进程重启不变

## ⚠️ 第一原则：不要相信"看起来正常"的返回

**这是最容易踩、后果最严重的坑。** 真实事故：agent 执行 `gu.py status` 拿到
`{"url":"http://localhost:5173/", "title":"喷气发动机 3D 互动教具", "progress":100}`，
便断定"页面已打开"——**但那个端口根本没有服务在监听**。它读到的是 App 从
**上一次会话恢复的旧标签页**。

三个叠加原因：

1. **App 会持久化并恢复标签会话**（`cfg.tabs`）。冷启动后第一毫秒就能返回一个
   **完全正常的旧页面**。
2. **`/api/navigate` 是"乐观返回"**：`loadUrl` 一调用就回 `ok:true`，而 `info.title`
   **往往是上一个页面的标题**。导航失败与成功返回结构一模一样。
3. **无法从这个环境冷启动 App**：Termux 的 `am` **不支持 `force-stop`**；
   实测 `am start -S` 之后端口全程 200（**进程根本没被杀**）。
   Android 10+ 也不允许其他 App 用 `pidof` 观察目标进程。
   → **gu.py 无法知道 App 处于什么状态**，只能尽力唤醒。

**因此：单次返回值永远不能作为成功依据。** 一律使用"就绪门禁"（gu.py 已内建）。

### gu.py 已内建的防护

| 防护 | 行为 |
|---|---|
| 就绪门禁 | `navigate`/`search` 轮询到 `progress==100` 且 URL 匹配且页面确为新加载，否则**报错退出** |
| 新鲜度硬信号 | 读页面自己的 `performance.now()`。URL 对得上但页面已存在超过 8s → 判定为**会话恢复的旧页**（导航到"当前已在的 URL"时的唯一破绽） |
| 旧页对比 | 每次导航打印 `旧页: …` → `新页: …`，直接暴露"没换页" |
| 错误页识别 | 优先用 `onReceivedError` 的真实错误码（exit 3）；标题匹配仅作旧版兜底 |
| 内置主页识别 | URL 以 `data:` 开头 → 判定导航被覆盖（exit 2） |
| 操作前置校验 | `eval`/`dom`/`click`/`fill`/`scroll`/`links` 前先确认当前是**真实已加载页**，否则拒绝执行（exit 5，`--force` 可绕过） |
| 截图告警 | 截图后若页面可疑会额外提示"图可能是旧页" |

### 先确认服务端版本与能力（别再靠猜）

```bash
python3 $PY version
```
输出示例（新版）：
```
  应用      : WebView 调试器
  API 版本  : 1.2
  应用版本  : 1.3.3 (code 8)
  能力(6)   : navigate_meta, navigate_wait, load_state, session_restored, load_error, version_info
```

**为什么需要这个**：旧版 `/api/info` 把 `apiVersion` 硬编码为 `"1.0"` 且从不更新，
客户端无法判断服务端支不支持某个字段，只能靠"行为差异"猜版本——
历史上多次因此**误判"新版已安装"**（浪费数轮装机）。
现在服务端显式声明 `features`，客户端据此决定是否依赖某字段（能力协商 > 版本号比较）。

| capability | 含义 |
|---|---|
| `navigate_meta` | `/api/navigate` 返回 `beforeUrl` / `requestedUrl` / `loading` |
| `navigate_wait` | 支持 `wait=1` + `waitTimeoutMs`，返回 `loadOutcome` |
| `load_state` | `/api/status` 返回 `loading` / `everLoaded` / `pageAgeMs` |
| `session_restored` | `/api/status` 返回 `sessionRestored` / `sessionRestoredUrl` |
| `load_error` | 基于 `onReceivedError` 的 `lastLoadFailed` / `lastErrorDesc` / `lastErrorCode` |
| `version_info` | `/api/info` 返回 `apiVersion` / `appVersionName` / `appVersionCode` |

**没有 `features` 字段 = 旧版 APK**，此时 `gu.py` 自动回退到 `performance.now()`
读取页面存活时间（旧版也能用，只是少了硬信号）。

### 服务端错误信息（新版才有）

导航失败时新版会给出**真实错误码**，不再靠猜标题：

```
❌ 加载失败：errorCode=-2 desc='net::ERR_NAME_NOT_RESOLVED'
```

（`-2` = `ERROR_HOST_LOOKUP`。`onReceivedError` 只上报**主框架**错误，
子资源失败不会误判整页失败。）

### 退出码约定（可据此写脚本判断）

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 内容可疑（页面过旧），或元素未找到 |
| 2 | 停在 App 内置主页（导航被覆盖） |
| 3 | 加载失败（系统错误页） |
| 4 | 就绪超时 |
| 5 | 当前页面不可用，操作被前置校验拒绝 |

### 强制操作顺序

```bash
PY=.../gu.py
python3 $PY probe                        # 1) 先看当前是什么页，不要假设
python3 $PY navigate https://example.com # 2) 导航（自己确认生效，失败非0）
python3 $PY eval "document.title"        # 3) 再交互
```

**禁忌**：`navigate` 后紧接着盲跑 `eval`/`click`——若导航没生效，你会在旧页上操作，
拿到"看起来合理但属于别的页面"的数据。

## 快速开始

```bash
PY=/data/data/com.termux/files/home/.pi/agent/skills/gu-browser-automation/scripts/gu.py
python3 $PY probe                                  # 先看当前页是否可信（务必先做）
python3 $PY navigate https://www.bilibili.com     # 打开网页（带就绪门禁，失败会非0退出）
python3 $PY search "人工智能" --engine "秘塔 AI"   # 搜索（引擎可选）
python3 $PY status                                 # 完整状态 + 可疑性诊断
python3 $PY dom                                    # 页面正文/链接摘要
python3 $PY screenshot --out ~/tmp/page.png        # 截图 → describe_image 验证
```

## API 总表（curl 直调也行）

鉴权头 `X-Api-Token: <token>`，或 `?token=` 参数。GET 无参、POST 传 JSON body。

### 导航与搜索
| 端点 | body | 说明 |
|---|---|---|
| `POST /api/navigate` | `{"url":"https://..."}` | 打开网址。**返回的 `info.title` 常是旧页标题，不可当成功依据** |
| `POST /api/search` | `{"query":"...","engine":"秘塔 AI"}` | 搜索；engine 支持全部 29 个引擎名/别名 |
| `POST /api/back` `/forward` `/reload` | - | 前进后退刷新 |
| `GET /api/status` | - | URL/标题/进度/全部 WebView 设置。**不保证是本次新加载的页** |
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
# 1) 先探测当前状态，确认不是旧页/错误页
python3 $PY probe
# 2) 导航（客户端会确认真的生效，失败非 0 退出）
python3 $PY navigate https://www.baidu.com
# 3) 侦察 DOM（选择器不确定时）
python3 $PY eval 'JSON.stringify([...document.querySelectorAll("input")].map(i=>i.id||i.name))'
# 4) 交互
python3 $PY fill --selector "#index-kw" --value "关键词"
python3 $PY click --text "百度一下"
python3 $PY wait --ms 3000   # 或 waitFor --selector ...
# 5) 读取结果
python3 $PY dom
# 6) 截图 + 视觉验证（当前模型无视觉时必须用 describe_image 看图）
python3 $PY screenshot --out ~/tmp/pg.png
```

## 注意事项

- **不要相信单次返回值（最重要）** —— 详见开头「第一原则」。App 会恢复旧标签会话，
  `navigate` 的 `ok:true` 与 `info.title` 都不能证明导航生效。用 `probe` 与就绪门禁确认。
- **Termux 无法冷启动该 App**：`am force-stop` 不存在；实测 `am start -S` 也不杀进程
  （端口全程 200）。所以无法在 Termux 里验证"冷启动行为"，
  涉及冷启动的结论必须说明是**推断**而非实测。
- **vivo 冻结**：App 后台会被冻结（HTTP 000），`am start` 唤醒后 2-3 秒恢复；gu.py 每次自动唤醒。进程不会被杀，token 不变
- **渲染帧滞后**：App 后台/刚唤醒时 navigate/tab-new 后 document 已更新但屏幕渲染帧可能还是旧页——API 数据（status/dom）与截图可能不一致。**截图前务必确保 App 在前台**（gu.py 自动 wake），操作后等 2-3 秒再截图
- **WebGL/动画页面截图空白（重要）**：Three.js/WebGL/Canvas 动画依赖 requestAnimationFrame，页面在后台（`visibilityState=hidden`）时浏览器**暂停 rAF** → 模型/动画不渲染，截图只有背景色。这是浏览器标准行为，不是截图 bug。**自动化截图 WebGL 页面前必须保证手机亮屏 + App 真正在前台**（am start 唤醒但屏幕锁定时页面仍 hidden）。截图 API 响应含 `vis` 字段（visible/hidden）可诊断；`/api/screenshot` 检测到 hidden 会自动等 2 秒重试
- **data URL 主页**：`/api/status` 的 title 可能是 `data:text/html;...` 开头=新标签主页，属正常
- **页面未就绪**：导航后先 `wait`/`waitFor` 再交互；SPA 页面优先 `waitFor selector`
- **点击选择器失效**：优先 `--text`（按可见文字）或 `--x --y` 坐标点击；移动端站点选择器与桌面版不同，先 `eval` 侦察
- **evaluate 结果**：返回值是 JSON 编码字符串（如 `"\"标题\""`），两层 JSON 解析
- **多标签**：API 操作作用于当前标签；新建标签用 `/api/tab/new`
- 安装新版 APK 后必须确认：`/api/search engine=metaso` 返回 metaso.cn 即新版（旧版回退百度）
