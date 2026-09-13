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
| `/api/navigate` | POST | `{"url":"https://..."}` | 打开网址 |
| `/api/search` | POST | `{"query":"...","engine":"baidu"}` | 搜索，见[搜索引擎](#搜索引擎) |
| `/api/back` | POST | - | 后退 |
| `/api/forward` | POST | - | 前进 |
| `/api/reload` | POST | - | 刷新 |
| `/api/stop` | POST | - | 停止加载 |
| `/api/home` | POST | - | 回到主页 |
| `/api/status` | GET | - | URL/标题/进度/全部 WebView 设置 |
| `/api/history` | GET | - | 当前标签历史栈 |

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