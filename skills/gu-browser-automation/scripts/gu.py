#!/usr/bin/env python3
"""古月浏览器自动化客户端：通过 127.0.0.1:8765 JSON API 控制手机上的 WebView 浏览器。

用法示例:
  gu.py navigate https://example.com
  gu.py search "WebView 自动化" --engine "秘塔 AI"
  gu.py fill --selector "#index-kw" --value "关键词"
  gu.py click --text "百度一下"
  gu.py click --selector ".result a"
  gu.py scroll --dir down --px 500
  gu.py eval "document.title"
  gu.py dom
  gu.py screenshot --out page.png
  gu.py tabs / gu.py tab-new / gu.py tab-switch 0
"""
import argparse
import base64
import json
import re
import subprocess
import sys
import time
import urllib.request

API = "http://127.0.0.1:8765"
PKG = "com.phonewebmcp.gu/.MainActivity"


def get_token():
    try:
        html = urllib.request.urlopen(API + "/", timeout=5).read().decode()
        m = re.search(r"const T='([0-9a-f]{32})'", html)
        return m.group(1) if m else None
    except Exception:
        return None


def wake():
    subprocess.run(["am", "start", "-n", PKG], capture_output=True)
    time.sleep(2)


def call(path, data=None):
    token = None
    for _ in range(3):
        token = get_token()
        if token:
            break
        wake()
    if not token:
        sys.exit("API 不可达：古月 App 未运行（已多次尝试唤醒），请先打开 App 或检查 8765 端口")
    req = urllib.request.Request(API + path, method="POST" if data is not None else "GET")
    req.add_header("X-Api-Token", token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
        body = json.dumps(data).encode()
    else:
        body = None
    with urllib.request.urlopen(req, data=body, timeout=25) as r:
        return json.loads(r.read().decode())


def out(r):
    if not r.get("ok"):
        sys.exit("错误: " + r.get("error", "unknown"))
    return r


def main():
    p = argparse.ArgumentParser(description="古月 WebView 浏览器自动化客户端")
    sub = p.add_subparsers(dest="cmd")

    sub.add_parser("wake", help="唤醒 App")
    sub.add_parser("status", help="当前页面状态")
    sub.add_parser("back")
    sub.add_parser("forward")
    sub.add_parser("reload")
    nav = sub.add_parser("navigate", help="打开 URL")
    nav.add_argument("url")
    s = sub.add_parser("search", help="搜索")
    s.add_argument("query")
    s.add_argument("--engine", default=None, help="搜索引擎名或别名，如 秘塔 AI / metaso / perplexity")
    sc = sub.add_parser("scroll", help="滚动: --dir up/down --px 或 --x/--y 或 --selector")
    sc.add_argument("--dir", default=None)
    sc.add_argument("--px", type=int, default=400)
    sc.add_argument("--x", type=int)
    sc.add_argument("--y", type=int)
    sc.add_argument("--selector")
    cl = sub.add_parser("click", help="点击: --selector 或 --text 或 --x/--y")
    cl.add_argument("--selector")
    cl.add_argument("--text")
    cl.add_argument("--x", type=int)
    cl.add_argument("--y", type=int)
    f = sub.add_parser("fill", help="填表")
    f.add_argument("--selector", required=True)
    f.add_argument("--value", required=True)
    ev = sub.add_parser("eval", help="执行 JS")
    ev.add_argument("js")
    sub.add_parser("dom", help="DOM 摘要")
    sub.add_parser("links", help="链接列表")
    shot = sub.add_parser("screenshot", help="截图保存 PNG")
    shot.add_argument("--out", default="shot.png")
    sub.add_parser("tabs")
    tnew = sub.add_parser("tab-new")
    tnew.add_argument("url", nargs="?")
    tsw = sub.add_parser("tab-switch")
    tsw.add_argument("index", type=int)
    tcl = sub.add_parser("tab-close")
    tcl.add_argument("index", type=int)
    sub.add_parser("bookmarks", help="收藏列表")
    ba = sub.add_parser("bookmark-add")
    ba.add_argument("--title")
    ba.add_argument("--url", required=True)

    args = p.parse_args()
    if not args.cmd:
        p.print_help()
        return

    if args.cmd == "wake":
        wake()
        print("已唤醒")
        return

    wake()

    if args.cmd == "status":
        print(json.dumps(out(call("/api/status"))["result"], ensure_ascii=False, indent=1))
    elif args.cmd == "navigate":
        print("ok" if out(call("/api/navigate", {"url": args.url})).get("ok") else "fail")
    elif args.cmd == "search":
        d = {"query": args.query}
        if args.engine:
            d["engine"] = args.engine
        print("ok" if out(call("/api/search", d)).get("ok") else "fail")
    elif args.cmd in ("back", "forward", "reload"):
        print("ok" if out(call("/api/" + args.cmd)).get("ok") else "fail")
    elif args.cmd == "scroll":
        d = {"px": args.px}
        if args.selector:
            d["selector"] = args.selector
        elif args.x is not None and args.y is not None:
            d = {"x": args.x, "y": args.y}
        else:
            d["dir"] = args.dir or "down"
        print("ok" if out(call("/api/scroll", d)).get("ok") else "fail")
    elif args.cmd == "click":
        d = {}
        if args.selector:
            d["selector"] = args.selector
        elif args.text:
            d["text"] = args.text
        elif args.x is not None and args.y is not None:
            d["x"] = args.x
            d["y"] = args.y
        print(json.dumps(out(call("/api/click", d)), ensure_ascii=False))
    elif args.cmd == "fill":
        print("ok" if out(call("/api/fill", {"selector": args.selector, "value": args.value})).get("ok") else "fail")
    elif args.cmd == "eval":
        print(out(call("/api/evaluate", {"js": args.js})).get("result"))
    elif args.cmd == "dom":
        print(json.dumps(out(call("/api/dom"))["result"], ensure_ascii=False, indent=1)[:3000])
    elif args.cmd == "links":
        for l in out(call("/api/links"))["result"] or []:
            print(f"  {(l.get('text') or '(无文字)')[:40]} -> {l.get('href','')[:60]}")
    elif args.cmd == "screenshot":
        r = out(call("/api/screenshot"))
        open(args.out, "wb").write(base64.b64decode(r["result"].split(",")[1]))
        print("saved", args.out)
    elif args.cmd == "tabs":
        for t in out(call("/api/tabs"))["result"]:
            print(f"  [{t['index']}] {'当前→' if t['current'] else ''}{t['title'][:24]} | {t['url'][:50]}")
    elif args.cmd == "tab-new":
        d = {}
        if args.url:
            d["url"] = args.url
        print("ok" if out(call("/api/tab/new", d)).get("ok") else "fail")
    elif args.cmd == "tab-switch":
        print("ok" if out(call("/api/tab/switch", {"index": args.index})).get("ok") else "fail")
    elif args.cmd == "tab-close":
        print("ok" if out(call("/api/tab/close", {"index": args.index})).get("ok") else "fail")
    elif args.cmd == "bookmarks":
        for i, b in enumerate(out(call("/api/bookmarks"))["result"]):
            print(f"  [{i}] {b.get('title','')} -> {b.get('url','')}")
    elif args.cmd == "bookmark-add":
        d = {"url": args.url}
        if args.title:
            d["title"] = args.title
        print("ok" if out(call("/api/bookmark/add", d)).get("ok") else "fail")


if __name__ == "__main__":
    main()
