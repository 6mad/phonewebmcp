# PhoneWebMCP API 文档

Base URL: `http://127.0.0.1:8765`（仅本机回环）
认证: 请求头 `X-Api-Token: <token>` 或 URL 参数 `?token=<token>`

Token 获取方式（三选一）:
1. App 内「信息」面板显示
2. `curl http://127.0.0.1:8765/` → 返回的 Web 控制台 HTML 中 `const T='...'`
3. App 重启 token 不变（持久化）

响应统一格式: `{"ok": true, "result": ...}` 或 `{"ok": false, "error": "..."}`。
POST 请求体为 JSON；GET 请求无 body。

## 导航

| 端点 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `/api/navigate` | POST | `{"url":"https://...","wait":1,"waitTimeoutMs":20000}` | 打开网址。`wait=1` 会阻塞到本次导航真正结束，并返回真实结局。**不加 `wait` 时返回的 `info.title` 往往是上一个页面的标题，不可作为成功依据** |
| `/api/search` | POST | `{"query":"...","engine":"baidu"}` | 搜索，见[搜索引擎](#搜索引擎) |
| `/api/back` | POST | - | 后退 |
| `/api/forward` | POST | - | 前进 |
| `/api/reload` | POST | - | 刷新 |
| `/api/stop` | POST | - | 停止加载 |
| `/api/home` | POST | - | 回到主页 |
| `/api/status` | GET | - | URL/标题/进度/加载状态/全部 WebView 设置。**不保证是本次新加载的页面**，详见[加载状态](#加载状态与导航确认) |
| `/api/history` | GET | - | 当前标签历史栈 |

## 加载状态与导航确认

### 为什么需要它

App 会**持久化并恢复标签会话**（`cfg.tabs`）。冷启动后第一毫秒，`/api/status`
就能返回一个**完全正常的旧页面**（url/title/progress 齐全），而 `/api/navigate`
是"乐观返回"（`loadUrl` 一调用就 `ok:true`）。因此单看返回值**无法判断导航是否生效**，
自动化脚本极易把"恢复的旧页"误认为"刚打开的页"。

为此 API 提供了一组显式的加载状态字段与 `wait` 参数。

### `/api/status` 新增字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `loading` | bool | 是否正在加载 |
| `everLoaded` | bool | 是否至少完成过一次加载（`false` = 从未加载，典型是恢复但未重载的标签） |
| `pageStartedAt` | long | 本次页面开始加载的墙钟毫秒（0 = 未知） |
| `pageAgeMs` | long | **页面已存活毫秒数**。这是识别"旧页冒充新页"的关键信号：URL 相同但数值很大 → 是恢复的旧页 |
| `lastRequestedUrl` | string | 最近一次通过 API 请求导航的地址 |
| `sessionRestored` | bool | 当前页是否来自**会话恢复**（而非本次加载） |
| `sessionRestoredUrl` | string | 被恢复的那个地址 |
| `lastLoadFailed` | bool | 主框架是否加载失败（来自 `onReceivedError`） |
| `lastErrorDesc` | string | 失败描述，如 `net::ERR_NAME_NOT_RESOLVED` |
| `lastErrorCode` | int | 失败错误码，如 `-2`（`ERROR_HOST_LOOKUP`） |

### `/api/navigate?wait=1` 的返回

| 字段 | 含义 |
|---|---|
| `beforeUrl` | 导航前的地址 |
| `requestedUrl` | 本次请求的地址 |
| `finalUrl` / `finalTitle` | 加载结束后的地址/标题 |
| `loadFailed` / `loadError` / `loadErrorCode` | 是否失败及原因 |
| `loadOutcome` | 结局，见下表 |

`loadOutcome` 取值：

| 值 | 含义 |
|---|---|
| `finished` | 已确认加载成功 |
| `error_page` | 加载失败（有真实错误码） |
| `session_restored` | 仍停在会话恢复的旧页 → 导航未生效 |
| `timeout` | 等待超时 |
| `pending` | 尚无法判定（URL 还未切换到目标），应继续轮询 |

### 设计说明（两个坑）

1. **不能用标题判断错误页**。WebView 的 `title` 更新**滞后于** `loading` 状态，
   且错误页标题随系统语言变化。本实现改用 `WebViewClient.onReceivedError`
   （只上报**主框架**错误，子资源失败不误判整页），错误码与语言无关。
2. **必须用独立的导航代次计数器**。新建标签时会先加载 `about:blank` 预热渲染，
   再 `postDelayed(400ms)` 加载真实页/主页。若这段时间内有外部导航，
   必须让延迟回调让位，否则会把导航覆盖掉（表现为"导航了却停在默认标签页"）。
   判断依据**不能是 `WebView.getUrl()`**——新导航在页面 commit 之前它仍返回旧值
   （实测仍返回 `about:blank`），所以"还是空白页"并不代表"没人导航过"。
   本实现用 `navigationGeneration` 计数器，由所有导航入口主动递增。

## 页面交互（自动化核心）

| 端点 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `/api/scroll` | POST | `{"dir":"down","px":500}` / `{"x":0,"y":0}` / `{"selector":"#id","position":"center"}` | 平滑滚动 |
| `/api/click` | POST | `{"selector":"#kw"}` / `{"text":"百度一下"}` / `{"x":100,"y":200}` | 点击。坐标点击通过 elementFromPoint + 完整 pointer/mouse 事件链 |
| `/api/fill` | POST | `{"selector":"#kw","value":"词"}` | 填表（focus + value + input/change 事件） |
| `/api/evaluate` | POST | `{"js":"document.title"}` | 执行任意 JS，返回 JSON 编码结果 |
| `/api/wait` | POST | `{"ms":2000}` | 等待毫秒 |
| `/api/waitFor` | POST | `{"selector":".x","timeoutMs":10000}` | 轮询等待元素出现（300ms 间隔） |

## 页面内容

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/dom` | GET | 标题 + 正文文本(前3000) + 链接(前50) + meta |
| `/api/links` | GET | 链接列表 `[{href,text}]` |
| `/api/screenshot` | GET | `data:image/png;base64,...` |

## 多标签

| 端点 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `/api/tabs` | GET | - | `[{index,title,url,progress,current}]` |
| `/api/tab/new` | POST | `{"url":"可省略"}` | 新建标签（最多 8 个） |
| `/api/tab/switch` | POST | `{"index":0}` | 切换 |
| `/api/tab/close` | POST | `{"index":0}` | 关闭 |

所有针对页面的 API 均作用于**当前标签**。

## 收藏

| 端点 | 方法 | 参数 |
|---|---|---|
| `/api/bookmarks` | GET | - |
| `/api/bookmark/add` | POST | `{"title":"可选","url":"..."}` |
| `/api/bookmark/remove` | POST | `{"index":0}` |

## 数据与设置

| 端点 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `/api/cache/clear` | POST | - | 清空 WebView 缓存 + DOM Storage |
| `/api/cookies/clear` | POST | - | 清除全部 Cookie |
| `/api/form/clear` | POST | - | 清表单数据/密码 |
| `/api/clear-all` | POST | - | 全部清除 |
| `/api/cookies` | GET | `?url=` | 查看 Cookie（默认当前页） |
| `/api/cookie/set` | POST | `{"url":"...","nameValue":"k=v; path=/; domain=..."}` | 写 Cookie |
| `/api/settings` | GET | - | 读全部设置 |
| `/api/settings` | POST | `{"key":"userAgent","value":"..."}` | 改设置 |

设置项 key: `userAgent` `javaScript` `domStorage` `cacheMode`(LOAD_DEFAULT/LOAD_CACHE_ELSE_NETWORK/LOAD_NO_CACHE/LOAD_CACHE_ONLY) `mixedContent`(0/1/2) `safeBrowsing` `allowFileAccess` `javaScriptCanOpenWindowsAutomatically` `builtInZoomControls` `loadWithOverviewMode` `useWideViewPort` `mediaPlaybackRequiresUserGesture` `supportMultipleWindows` `defaultTextEncoding`

## 搜索引擎

`/api/search` 的 `engine` 参数支持显示名或别名（均可），缺省用当前选择的引擎：

- 常用: 百度 必应 Google 搜狗 360 夸克 头条搜索 维基百科 知乎 哔哩哔哩 GitHub YouTube 淘宝 京东 抖音 微博 小红书 高德地图 有道词典
- AI: 秘塔 AI Perplexity Felo Devv(编程) Phind(编程) You.com Kagi 豆包 Kimi Bing Copilot
- 别名: `baidu` `bing` `google` `sogou` `so` `quark` `zhihu` `github` `bilibili` `metaso` `perplexity` ...

## 内置 Web 控制台

手机/本机浏览器访问 `http://127.0.0.1:8765/`：
地址栏导航、搜索（选引擎）、后退/前进/刷新、JS 执行、DOM/链接、截图、
快捷键清缓存、Cookie 查看、UA/缓存模式设置。Token 已内嵌无需手动输入。

## 版本与能力协商

`/api/info` 返回服务端版本与**能力清单**：

```json
{
  "app": "WebView 调试器",
  "apiVersion": "1.2",
  "appVersionName": "1.3.5",
  "appVersionCode": 10,
  "features": ["navigate_meta", "navigate_wait", "load_state",
               "session_restored", "load_error", "version_info"],
  "webViewVersion": "138.0.7204.179"
}
```

| capability | 含义 |
|---|---|
| `navigate_meta` | `/api/navigate` 返回 `beforeUrl` / `requestedUrl` / `loading` |
| `navigate_wait` | 支持 `wait=1` + `waitTimeoutMs`，返回 `loadOutcome` |
| `load_state` | `/api/status` 返回 `loading` / `everLoaded` / `pageAgeMs` |
| `session_restored` | `/api/status` 返回 `sessionRestored` / `sessionRestoredUrl` |
| `load_error` | 基于 `onReceivedError` 的 `lastLoadFailed` / `lastErrorDesc` / `lastErrorCode` |
| `version_info` | `/api/info` 返回 `apiVersion` / `appVersionName` / `appVersionCode` |

**客户端应据 `features` 判断字段可用性，而不是比较版本号字符串。**
早期版本把 `apiVersion` 硬编码为 `"1.0"` 且从不更新，导致客户端只能靠"行为差异"
猜测服务端版本，多次误判"新版已安装"。能力协商消除了这个问题。
