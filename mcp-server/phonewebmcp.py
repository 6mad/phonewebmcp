#!/usr/bin/env python3
"""PhoneWebMCP - MCP (Model Context Protocol) 标准适配层。

把「古月」WebView 浏览器 (com.phonewebmcp.gu) 的本地 JSON API (127.0.0.1:8765)
包装成标准 MCP server，通过 stdio JSON-RPC 供 Claude/Cursor/任何 MCP 客户端调用。

零第三方依赖：纯 Python 标准库。运行：
    python3 phonewebmcp.py

MCP 客户端接入示例 (Claude Code):
    claude mcp add phonewebmcp -- python3 /path/to/phonewebmcp.py
"""
import json
import re
import subprocess
import sys
import time
import urllib.request

API = "http://127.0.0.1:8765"
PKG = "com.phonewebmcp.gu/.MainActivity"
PROTOCOL_VERSION = "2025-06-18"


# ---------------- HTTP 桥 ----------------

def get_token():
    try:
        html = urllib.request.urlopen(API + "/", timeout=4).read().decode()
        m = re.search(r"const T='([0-9a-f]{32})'", html)
        return m.group(1) if m else None
    except Exception:
        return None


def wake():
    subprocess.run(["am", "start", "-n", PKG], capture_output=True)
    time.sleep(3)


def call_api(path, data=None):
    # 唤醒 + 重试（应对国产 ROM 后台冻结）
    token = None
    for attempt in range(3):
        token = get_token()
        if token:
            break
        wake()
    if not token:
        raise RuntimeError("古月 App 未运行或 API 不可达 (127.0.0.1:8765)。请先打开 App，"
                           "或确认前台服务已启动。")
    req = urllib.request.Request(API + path, method="POST" if data is not None else "GET")
    req.add_header("X-Api-Token", token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
        body = json.dumps(data).encode()
    else:
        body = None
    with urllib.request.urlopen(req, data=body, timeout=30) as r:
        resp = json.loads(r.read().decode())
    if not resp.get("ok"):
        raise RuntimeError("API 错误: " + resp.get("error", "unknown"))
    return resp.get("result")


# ---------------- MCP 工具 ----------------

def _nav(data):  # navigate / search
    if "query" in data:
        d = {"query": data["query"]}
        if data.get("engine"):
            d["engine"] = data["engine"]
        return call_api("/api/search", d)
    if "url" in data:
        return call_api("/api/navigate", {"url": data["url"]})
    raise ValueError("需要 url 或 query 参数")


def _status(data):
    r = call_api("/api/status")
    return json.dumps(r, ensure_ascii=False, indent=1)


def _dom(data):
    return json.dumps(call_api("/api/dom"), ensure_ascii=False, indent=1)


def _links(data):
    return json.dumps(call_api("/api/links"), ensure_ascii=False, indent=1)


def _scroll(data):
    d = {}
    if data.get("selector"):
        d["selector"] = data["selector"]
        d["position"] = data.get("position", "center")
    elif "x" in data and "y" in data:
        d = {"x": data["x"], "y": data["y"]}
    else:
        d = {"dir": data.get("dir", "down"), "px": data.get("px", 400)}
    return call_api("/api/scroll", d)


def _click(data):
    d = {}
    for k in ("selector", "text", "x", "y"):
        if k in data:
            d[k] = data[k]
    if not d:
        raise ValueError("click 需要 selector / text / x+y 之一")
    return call_api("/api/click", d)


def _fill(data):
    if not data.get("selector") or "value" not in data:
        raise ValueError("fill 需要 selector 和 value")
    return call_api("/api/fill", {"selector": data["selector"], "value": data["value"]})


def _evaluate(data):
    if not data.get("js"):
        raise ValueError("evaluate 需要 js 参数")
    return str(call_api("/api/evaluate", {"js": data["js"]}))


def _screenshot(data):
    r = call_api("/api/screenshot")
    return r  # data:image/png;base64,...


def _wait(data):
    time.sleep(data.get("ms", 1000) / 1000.0)
    return "ok"


def _wait_for(data):
    if not data.get("selector"):
        raise ValueError("waitFor 需要 selector")
    return call_api("/api/waitFor", {
        "selector": data["selector"],
        "timeoutMs": data.get("timeoutMs", 10000),
    })


def _simple(path):
    def fn(data):
        return call_api(path)
    return fn


def _tab_new(data):
    d = {}
    if data.get("url"):
        d["url"] = data["url"]
    return call_api("/api/tab/new", d)


def _tab_index(path):
    def fn(data):
        if "index" not in data:
            raise ValueError("需要 index 参数")
        return call_api(path, {"index": int(data["index"])})
    return fn


def _bookmarks(data):
    return call_api("/api/bookmarks")


def _bookmark_add(data):
    if not data.get("url"):
        raise ValueError("bookmark_add 需要 url")
    d = {"url": data["url"]}
    if data.get("title"):
        d["title"] = data["title"]
    return call_api("/api/bookmark/add", d)


def _bookmark_remove(data):
    if "index" not in data:
        raise ValueError("需要 index 参数")
    return call_api("/api/bookmark/remove", {"index": int(data["index"])})


def _cookies(data):
    d = {}
    if data.get("url"):
        d["url"] = data["url"]
    return call_api("/api/cookies" + ("?url=" + urllib.parse.quote(d["url"]) if d else ""))


def _settings(data):
    if "key" in data:
        if "value" not in data:
            raise ValueError("settings 修改需要 value")
        return call_api("/api/settings", {"key": data["key"], "value": data["value"]})
    r = call_api("/api/status")
    return json.dumps(r.get("settings", {}), ensure_ascii=False, indent=1)


TOOLS = [
    {"name": "navigate", "description": "打开网址或搜索。传入 url 打开网页；传入 query 搜索（可用 engine 指定搜索引擎，如 百度/秘塔 AI/metaso/perplexity）",
     "inputSchema": {"type": "object", "properties": {
         "url": {"type": "string", "description": "要打开的网址，如 https://example.com"},
         "query": {"type": "string", "description": "搜索关键词"},
         "engine": {"type": "string", "description": "搜索引擎名或别名（可选）"}}},
     "fn": _nav},
    {"name": "status", "description": "当前页面状态：URL、标题、加载进度、WebView 设置（UA/JS/缓存模式等）",
     "inputSchema": {"type": "object", "properties": {}}, "fn": _status},
    {"name": "dom", "description": "当前页面 DOM 摘要：正文文本、标题、前 50 个链接、meta 信息",
     "inputSchema": {"type": "object", "properties": {}}, "fn": _dom},
    {"name": "links", "description": "当前页面链接列表",
     "inputSchema": {"type": "object", "properties": {}}, "fn": _links},
    {"name": "scroll", "description": "滚动页面：--dir down/up --px 步长；或 x/y 绝对坐标；或滚动到 selector 元素",
     "inputSchema": {"type": "object", "properties": {
         "dir": {"type": "string", "enum": ["down", "up", "left", "right"]},
         "px": {"type": "integer", "description": "滚动像素（默认400）"},
         "x": {"type": "integer"}, "y": {"type": "integer"},
         "selector": {"type": "string"}, "position": {"type": "string"}}},
     "fn": _scroll},
    {"name": "click", "description": "点击页面元素：按 CSS selector、按可见文字 text、或按坐标 x/y",
     "inputSchema": {"type": "object", "properties": {
         "selector": {"type": "string", "description": "CSS 选择器"},
         "text": {"type": "string", "description": "元素可见文字，如 百度一下"},
         "x": {"type": "integer"}, "y": {"type": "integer"}}},
     "fn": _click},
    {"name": "fill", "description": "填写表单输入框（触发 input/change 事件）",
     "inputSchema": {"type": "object", "properties": {
         "selector": {"type": "string"}, "value": {"type": "string"}},
      "required": ["selector", "value"]}, "fn": _fill},
    {"name": "evaluate", "description": "在当前页面执行任意 JavaScript，返回结果",
     "inputSchema": {"type": "object", "properties": {
         "js": {"type": "string", "description": "JavaScript 代码，如 document.title"}},
      "required": ["js"]}, "fn": _evaluate},
    {"name": "screenshot", "description": "截取当前页面，返回 data:image/png;base64 图片",
     "inputSchema": {"type": "object", "properties": {}}, "fn": _screenshot},
    {"name": "wait", "description": "等待指定毫秒（页面加载/动画）",
     "inputSchema": {"type": "object", "properties": {"ms": {"type": "integer", "default": 1000}}}, "fn": _wait},
    {"name": "waitFor", "description": "等待元素出现（轮询 300ms，默认超时 10s）",
     "inputSchema": {"type": "object", "properties": {
         "selector": {"type": "string"}, "timeoutMs": {"type": "integer"}},
      "required": ["selector"]}, "fn": _wait_for},
    {"name": "back", "description": "浏览器后退", "inputSchema": {"type": "object", "properties": {}}, "fn": _simple("/api/back")},
    {"name": "forward", "description": "浏览器前进", "inputSchema": {"type": "object", "properties": {}}, "fn": _simple("/api/forward")},
    {"name": "reload", "description": "刷新页面", "inputSchema": {"type": "object", "properties": {}}, "fn": _simple("/api/reload")},
    {"name": "tabs", "description": "列出所有标签页", "inputSchema": {"type": "object", "properties": {}}, "fn": lambda d: call_api("/api/tabs")},
    {"name": "tab_new", "description": "新建标签页（可传 url）", "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}}, "fn": _tab_new},
    {"name": "tab_switch", "description": "切换到指定标签", "inputSchema": {"type": "object", "properties": {"index": {"type": "integer"}}, "required": ["index"]}, "fn": _tab_index("/api/tab/switch")},
    {"name": "tab_close", "description": "关闭指定标签", "inputSchema": {"type": "object", "properties": {"index": {"type": "integer"}}, "required": ["index"]}, "fn": _tab_index("/api/tab/close")},
    {"name": "bookmarks", "description": "收藏列表", "inputSchema": {"type": "object", "properties": {}}, "fn": _bookmarks},
    {"name": "bookmark_add", "description": "添加收藏", "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}, "url": {"type": "string"}}, "required": ["url"]}, "fn": _bookmark_add},
    {"name": "bookmark_remove", "description": "删除收藏（按 index）", "inputSchema": {"type": "object", "properties": {"index": {"type": "integer"}}, "required": ["index"]}, "fn": _bookmark_remove},
    {"name": "cookies", "description": "查看 Cookie（可按 url 过滤）", "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}}, "fn": _cookies},
    {"name": "cookies_clear", "description": "清除所有 Cookie", "inputSchema": {"type": "object", "properties": {}}, "fn": _simple("/api/cookies/clear")},
    {"name": "cache_clear", "description": "清除 WebView 缓存", "inputSchema": {"type": "object", "properties": {}}, "fn": _simple("/api/cache/clear")},
    {"name": "settings", "description": "查看/修改 WebView 设置。修改：key=userAgent/javaScript/domStorage/cacheMode/safeBrowsing/mixedContent 等 + value",
     "inputSchema": {"type": "object", "properties": {"key": {"type": "string"}, "value": {"type": "string"}}}, "fn": _settings},
]


def handle_tool_call(params):
    name = params.get("name")
    args = params.get("arguments") or {}
    for t in TOOLS:
        if t["name"] == name:
            try:
                text = t["fn"](args)
                if not isinstance(text, str):
                    text = json.dumps(text, ensure_ascii=False)
                return {"content": [{"type": "text", "text": text}]}
            except Exception as e:
                return {"content": [{"type": "text", "text": "错误: " + str(e)}],
                        "isError": True}
    return {"content": [{"type": "text", "text": "未知工具: " + name}], "isError": True}


# ---------------- stdio JSON-RPC 循环 ----------------

def log(msg):
    sys.stderr.write(msg + "\n")
    sys.stderr.flush()


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main():
    log("PhoneWebMCP MCP server 启动 (API=" + API + ")")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        msg_id = msg.get("id")
        method = msg.get("method")

        if method == "initialize":
            send({"jsonrpc": "2.0", "id": msg_id, "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "phonewebmcp", "version": "1.0.0"}}})
        elif method == "notifications/initialized":
            pass  # 无需响应
        elif method == "ping":
            send({"jsonrpc": "2.0", "id": msg_id, "result": {}})
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": msg_id, "result": {
                "tools": [{k: v for k, v in t.items() if k != "fn"} for t in TOOLS]}})
        elif method == "tools/call":
            send({"jsonrpc": "2.0", "id": msg_id, "result": handle_tool_call(msg.get("params") or {})})
        elif method and method.startswith("notifications/"):
            pass
        else:
            send({"jsonrpc": "2.0", "id": msg_id,
                  "error": {"code": -32601, "message": "unknown method " + str(method)}})


if __name__ == "__main__":
    main()
