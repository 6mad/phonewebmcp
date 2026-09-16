#!/usr/bin/env python3
"""古月浏览器自动化客户端：通过 127.0.0.1:8765 JSON API 控制手机上的 WebView 浏览器。

设计要点（血泪教训，勿删）：
  App 会持久化标签会话，冷启动时恢复上次的页面。因此 status/navigate 在开头几秒
  返回的很可能是"旧页面"，看起来一切正常，实则导航根本没生效。
  加之 API 的 navigate 是"乐观返回"（loadUrl 一调用就报 ok:true，info 里的 title
  还是旧页的），单看返回值无法判断成功与否。

  所以本客户端一律遵循：**不信任单次返回值，必须用就绪门禁轮询确认**；
  确认不了就报失败并打印诊断，绝不静默成功。

用法示例:
  gu.py navigate https://example.com
  gu.py navigate https://example.com --no-wait      # 跳过就绪门禁（不推荐）
  gu.py search "WebView 自动化" --engine "秘塔 AI"
  gu.py status / gu.py probe                        # 看当前页 + 可疑性诊断
  gu.py fill --selector "#index-kw" --value "关键词"
  gu.py click --text "百度一下"
  gu.py eval "document.title"
  gu.py dom / gu.py screenshot --out page.png
"""
import argparse
import base64
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "http://127.0.0.1:8765"
PKG = "com.pi.webviewtool/.MainActivity"

# WebView 加载失败时系统给出的错误页标题特征
ERROR_TITLE_MARKERS = (
    "网页无法打开",
    "无法访问此网站",
    "找不到网页",
    "网页可能暂时无法连接",
    "连接已重置",
    "ERR_",
    "net::",
)

# 就绪门禁参数
POLL_INTERVAL = 0.35
READY_TIMEOUT = 25.0

# 服务端能力缓存（首次访问 /api/info 时填充）
_FEATURES_CACHE = None


def server_info():
    """查询服务端版本与能力。

    之所以要"能力协商"而不是"比较版本号"：历史上 /api/info 的 apiVersion
    长期硬编码为 "1.0"，客户端无法判断服务端是否支持某个字段，
    只能靠"行为差异"去猜，多次导致"以为新版已装、其实还是旧版"的误判。
    现在服务端显式声明 features，客户端据此决定是否依赖某些字段。
    """
    try:
        r = call("/api/info")
    except SystemExit:
        return {}
    return r if isinstance(r, dict) else {}


def features():
    """取服务端能力集合（带缓存）"""
    global _FEATURES_CACHE
    if _FEATURES_CACHE is None:
        info = server_info()
        f = info.get("features")
        _FEATURES_CACHE = set(f) if isinstance(f, list) else set()
    return _FEATURES_CACHE


def has_feature(name):
    return name in features()


def print_version():
    """打印服务端版本与能力（用于确认"新版是否真的装上了"）"""
    info = server_info()
    if not info.get("ok"):
        sys.exit("无法获取 /api/info")
    print(f"  应用      : {info.get('app')}")
    print(f"  API 版本  : {info.get('apiVersion')}")
    print(f"  应用版本  : {info.get('appVersionName')} (code {info.get('appVersionCode')})")
    feats = info.get("features")
    if isinstance(feats, list):
        print(f"  能力({len(feats)}) : {', '.join(feats)}")
    else:
        print("  能力      : (旧版未声明 features —— 只能靠行为推断，升级 APK 后可用)")
    print(f"  WebView   : {info.get('webViewVersion')}")
    return info


# --------------------------------------------------------------------------
# 基础通信
# --------------------------------------------------------------------------

def get_token():
    try:
        html = urllib.request.urlopen(API + "/", timeout=5).read().decode()
        m = re.search(r"const T='([0-9a-f]{32})'", html)
        return m.group(1) if m else None
    except Exception:
        return None


def wake():
    """唤醒 App。

    注意：Termux 的 am **不支持 force-stop**，也无法真正冷启动该 App
    （实测 am start -S 之后端口全程 200，进程并未被杀；Android 10+ 也不允许
    其他 App 用 pidof 观察目标进程）。所以这里只能"尽力唤醒"，
    调用方不能假设 App 处于任何确定状态——这正是需要就绪门禁的根本原因。
    """
    subprocess.run(["am", "start", "-n", PKG], capture_output=True)
    time.sleep(2)


def call(path, data=None, timeout=25):
    token = None
    for _ in range(3):
        token = get_token()
        if token:
            break
        wake()
    if not token:
        sys.exit("API 不可达：古月 App 未运行（已多次尝试唤醒），请先手动打开 App 或检查 8765 端口")
    req = urllib.request.Request(API + path, method="POST" if data is not None else "GET")
    req.add_header("X-Api-Token", token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
        body = json.dumps(data).encode()
    else:
        body = None
    try:
        with urllib.request.urlopen(req, data=body, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.URLError as e:
        sys.exit(f"请求 {path} 失败：{e}（App 可能被系统冻结，稍后重试或手动打开 App）")


def out(r):
    if not r.get("ok"):
        sys.exit("错误: " + r.get("error", "unknown"))
    return r


# --------------------------------------------------------------------------
# 页面状态判断
# --------------------------------------------------------------------------

def fetch_status():
    """取当前页面状态（status 的 result 字段）"""
    return (call("/api/status") or {}).get("result") or {}


def norm_url(u):
    if not u:
        return ""
    u = u.strip().lower()
    u = re.sub(r"^https?://", "", u)
    return u.rstrip("/")


# 页面"新鲜度"上限：导航确认后，页面存活时间必须短于此值。
# 这是区分「真加载出来的新页」与「会话恢复的旧页」的关键硬信号。
FRESH_MAX_MS = 8000

# 读页面存活毫秒数。返回 None 表示读不到（页面不可用或 JS 被禁）。
# 优先用新版 App 暴露的 status.pageAgeMs；旧版 APK 没有该字段时，
# 回退到直接读页面自己的 performance.now()——两条路都无需改动即可工作。
def fetch_page_age_ms(status=None):
    s = status if status is not None else fetch_status()
    age = s.get("pageAgeMs")
    if isinstance(age, (int, float)) and age > 0:
        return int(age)
    js = ("(function(){try{"
          "if(typeof performance==='undefined'||typeof performance.now!=='function')return null;"
          "return Math.round(performance.now());"
          "}catch(e){return null}})()")
    try:
        r = call("/api/evaluate", {"js": js}, timeout=10)
    except SystemExit:
        return None
    raw = (r or {}).get("result")
    if raw is None:
        return None
    try:
        v = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        return None
    if isinstance(v, (int, float)):
        return int(v)
    try:
        return int(str(v).strip('"'))
    except Exception:
        return None


def title_of(s):
    return (s.get("title") or "").strip()


def short_url(u, limit=76):
    """显示用短 URL：data: 主页这类超长 URL 只留特征"""
    if not u:
        return "(空)"
    if u.startswith("data:"):
        m = re.search(r"<title>(.*?)</title>", urllib.parse.unquote(u))
        name = m.group(1) if m else "内置页"
        return f"data:…（{name}）"
    return u if len(u) <= limit else u[: limit - 1] + "…"


def is_error_page(s):
    """判断是否为加载失败页。

    优先采用 App 的真实错误标志（WebViewClient.onReceivedError 上报），
    它不依赖标题更新时机、也不依赖系统语言。
    旧版 APK 没有该字段时，回退到标题特征匹配。
    """
    if s.get("lastLoadFailed") is True:
        return True
    t = title_of(s)
    if not t:
        return True
    return any(m in t for m in ERROR_TITLE_MARKERS)


def error_detail(s):
    """失败原因的可读描述（用于诊断输出）"""
    code = s.get("lastErrorCode")
    desc = s.get("lastErrorDesc")
    if code or desc:
        return f"errorCode={code} desc={desc!r}"
    return f"title={title_of(s)!r}"


def is_internal_page(s):
    """判断是否为 App 内置主页（data: URL）而非真实网页"""
    return (s.get("url") or "").startswith("data:")


def diagnose(s, expect=None, before=None):
    """对一个状态快照给出可疑性诊断列表。

    新版 App 会直接给出 loading / pageAgeMs / sessionRestored 等硬信号；
    旧版 APK 没有这些字段，此时靠启发式规则尽量把"旧页冒充新页"暴露出来。
    """
    w = []
    u = s.get("url") or ""
    t = title_of(s)
    if not u:
        w.append("url 为空：页面可能尚未开始加载")
    elif is_internal_page(s):
        w.append("当前是 App 内置主页（data: URL），不是真实网页")
    if is_error_page(s):
        if s.get("lastLoadFailed") is True:
            w.append(f"主框架加载失败（{error_detail(s)}）")
        else:
            w.append(f"标题「{t or '(空)'}」符合加载失败页特征")
    # 新版 App 的硬信号优先
    if s.get("sessionRestored") is True:
        w.append(
            f"sessionRestored=true：该页是 App 从上次会话**恢复**的（{u[:50]}），"
            "并非本次加载。若你刚导航过，说明导航未生效"
        )
    if s.get("loading") is True:
        w.append("loading=true：页面仍在加载中，此刻的数据可能不完整")
    age = s.get("pageAgeMs")
    if isinstance(age, (int, float)) and age > FRESH_MAX_MS:
        w.append(f"pageAgeMs={int(age)}ms：页面已存在较久，很可能是旧页而非本次新加载")
    if before is not None and expect is not None:
        if norm_url(u) == norm_url(before) and norm_url(before) != norm_url(expect):
            w.append("url 与导航前完全相同：本次导航很可能没有生效（被会话恢复的旧页顶替）")
    if expect is not None and norm_url(u) != norm_url(expect) and norm_url(u) != "":
        w.append(f"url 与请求的 {expect} 不一致（实际 {u}）")
    return w


def wait_ready(expect, before=None, timeout=READY_TIMEOUT, quiet=False):
    """就绪门禁：轮询到页面真正加载完成。

    返回 (status, outcome)：
      outcome = "matched"     URL 与请求一致，且页面确实是新加载的
              = "redirected"  加载完成但落在别的真实网页上（可能重定向，也可能被覆盖）
              = "internal"    落到了 App 内置主页（典型：被 newTab 的延迟加载覆盖）
              = "error"       加载完成但显示系统错误页
              = "timeout"     超时仍未就绪
    """
    deadline = time.time() + timeout
    last = {}
    last_progress = None
    age_checked = False
    while time.time() < deadline:
        s = fetch_status()
        last = s
        url = s.get("url") or ""
        prog = s.get("progress")
        title = title_of(s)

        if not quiet and prog != last_progress:
            last_progress = prog
            print(f"  … progress={prog} url={url[:58]}", file=sys.stderr)

        # 真正就绪的条件：进度 100 + 有标题 + url 非空
        if prog == 100 and title and url:
            if is_error_page(s):
                return s, "error"
            if is_internal_page(s):
                return s, "internal"
            is_target = norm_url(url) == norm_url(expect)
            stuck_on_old = before is not None and norm_url(url) == norm_url(before)

            # 新鲜度校验：即使 URL 对上了，也要确认页面是这次真加载出来的。
            # 若导航到的正是会话恢复的同一个 URL，单看 URL 会误判成功，
            # 只有页面存活时间/ sessionRestored 能戳穿它。
            if is_target and not age_checked:
                if s.get("sessionRestored") is True:
                    print("  ⚠ URL 匹配但 App 标记该页为会话恢复的旧页，继续等待", file=sys.stderr)
                    time.sleep(POLL_INTERVAL)
                    continue
                age = fetch_page_age_ms(s)
                if age is not None and age > FRESH_MAX_MS:
                    print(
                        f"  ⚠ URL 匹配但页面已存在 {age/1000:.1f}s，判定为【会话恢复的旧页】而非本次加载，继续等待",
                        file=sys.stderr,
                    )
                    time.sleep(POLL_INTERVAL)
                    continue
                age_checked = True

            if is_target:
                return s, "matched"
            if stuck_on_old:
                time.sleep(POLL_INTERVAL)
                continue
            return s, "redirected"
        time.sleep(POLL_INTERVAL)
    return last, "timeout"


# --------------------------------------------------------------------------
# 命令实现
# --------------------------------------------------------------------------

def do_navigate(url, wait=True):
    """导航并确认结果。返回 status（失败时退出码非 0）"""
    before_s = fetch_status()
    before = before_s.get("url") or ""
    if before:
        print(f"  旧页: {short_url(before)}")
        if is_internal_page(before_s):
            print("        （App 内置主页：当前不在真实网页上）")

    # 支持 navigate_wait 能力的服务端才带 wait 参数（旧版会忽略，但显式判断更清晰）
    payload = {"url": url}
    if has_feature("navigate_wait"):
        payload["wait"] = 1
        payload["waitTimeoutMs"] = 10000
    r = out(call("/api/navigate", payload, timeout=45))
    info = r.get("info") or {}
    # navigate 的 info.title 往往是【旧页标题】（旧版 App 是乐观返回），仅供参照
    if info.get("title"):
        print(f"  navigate 回执: title={info['title']!r}  ← 注意：这通常是旧页标题，不可作为成功依据")
    # 新版 App 会直接给出真实结局，有则优先采用
    outcome_api = r.get("loadOutcome")
    if outcome_api:
        print(f"  App 回报 loadOutcome={outcome_api}")
        if outcome_api == "error_page":
            code = r.get("loadErrorCode")
            err = r.get("loadError")
            print(f"  ❌ 加载失败：errorCode={code} {('desc=%r' % err) if err else ''}")
            sys.exit(3)
        if outcome_api == "session_restored":
            print("  ❌ 仍停在会话恢复的旧页：导航未生效")
            sys.exit(2)
        if outcome_api == "timeout":
            print("  ⚠ App 等待超时，改用客户端就绪门禁继续确认")

    if not wait:
        print(f"  新页: {url}（未等待就绪，结果未验证）")
        return None

    s, outcome = wait_ready(url, before=before)
    actual = s.get("url") or ""
    title = title_of(s)
    print(f"  新页: {short_url(actual)}")
    print(f"  标题: {title or '(空)'}")

    if outcome == "matched":
        print("  ✅ 导航已确认生效")
        return s
    if outcome == "redirected":
        print(f"  ⚠ 加载完成但落在 {actual[:60]}（与请求的 {url} 不同）")
        print("     可能是服务器重定向；若该地址不是预期页面，也要考虑被会话恢复/内置主页覆盖")
        return s
    if outcome == "internal":
        print("  ❌ 停留在 App 内置主页：导航未生效（很可能被新标签页的延迟加载覆盖）")
        sys.exit(2)
    if outcome == "error":
        print(f"  ❌ 加载失败：{error_detail(s)}")
        print(f"     请求地址 {url}")
        sys.exit(3)
    print(f"  ❌ 超时 {READY_TIMEOUT:.0f}s 仍未就绪")
    print(f"     最后一次状态：progress={s.get('progress')} url={actual[:60]} title={title!r}")
    for w in diagnose(s, expect=url, before=before):
        print(f"     · {w}")
    sys.exit(4)


def print_status():
    s = fetch_status()
    print(json.dumps(s, ensure_ascii=False, indent=1))
    w = diagnose(s)
    if w:
        print("⚠ 可疑之处：")
        for x in w:
            print(f"  · {x}")
    print()
    print("提示：status 无法证明页面是本次新加载的——App 会恢复上次会话的标签，")
    print("      旧页在冷启动后立刻就能返回看起来正常的数据。要确认导航生效，")
    print("      请用 `gu.py navigate <url>`（带就绪门禁）或 `gu.py probe`。")


def do_probe():
    """就绪探测：不导航，只报告当前页面是否可信、是否已加载完成"""
    s = fetch_status()
    url = s.get("url") or ""
    title = title_of(s)
    prog = s.get("progress")
    age = fetch_page_age_ms()
    print(f"  url      : {url or '(空)'}")
    print(f"  title    : {title or '(空)'}")
    print(f"  progress : {prog}")
    if age is not None:
        print(f"  页面存活 : {age/1000:.1f}s", end="")
        if age <= FRESH_MAX_MS:
            print("（刚加载，可信）")
        else:
            print(f"（已存在较久：可能是会话恢复的旧页，而非刚访问的页面）")
    print(f"  canGoBack: {s.get('canGoBack')}")
    if s.get("lastLoadFailed") is True:
        print(f"  加载错误 : {error_detail(s)}")
    w = diagnose(s)
    if age is not None and age > FRESH_MAX_MS and not w:
        w.append(
            f"页面已存在 {age/1000:.0f}s：很可能不是本次新加载的页面。"
            "若你刚执行过导航，说明导航没生效（旧页仍在前台）"
        )
    if not w:
        print("  ✅ 当前页面看起来已正常加载（真实网页、无错误页特征）")
        return 0
    print("  ⚠ 存在问题：")
    for x in w:
        print(f"    · {x}")
    return 1


def require_real_page(action):
    """执行 eval/click/fill 前确认当前是真实已加载页面，避免在旧页/错误页上操作"""
    s = fetch_status()
    url = s.get("url") or ""
    w = diagnose(s)
    if not url or is_error_page(s) or is_internal_page(s):
        print(f"❌ 当前页面不适合执行 {action}：", file=sys.stderr)
        print(f"   url={url or '(空)'} title={title_of(s)!r} progress={s.get('progress')}", file=sys.stderr)
        for x in w:
            print(f"   · {x}", file=sys.stderr)
        print("   请先 `gu.py navigate <url>` 并等待确认生效。", file=sys.stderr)
        sys.exit(5)
    return s


# --------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(description="古月 WebView 浏览器自动化客户端")
    sub = p.add_subparsers(dest="cmd")

    sub.add_parser("wake", help="唤醒 App")
    sub.add_parser("version", help="服务端版本与能力（确认新版是否真的装上）")
    sub.add_parser("status", help="当前页面状态（含可疑性诊断）")
    sub.add_parser("probe", help="就绪探测：当前页面是否可信")
    sub.add_parser("back")
    sub.add_parser("forward")
    sub.add_parser("reload")
    nav = sub.add_parser("navigate", help="打开 URL（带就绪门禁，确认真的生效）")
    nav.add_argument("url")
    nav.add_argument("--no-wait", action="store_true", help="跳过就绪门禁（不推荐）")
    s = sub.add_parser("search", help="搜索")
    s.add_argument("query")
    s.add_argument("--engine", default=None, help="搜索引擎名或别名，如 秘塔 AI / metaso / perplexity")
    s.add_argument("--no-wait", action="store_true", help="跳过就绪门禁（不推荐）")
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
    ev.add_argument("--force", action="store_true", help="跳过页面校验（在旧页/错误页上也执行）")
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
        print("已唤醒（注意：Termux 无法真正冷启动或杀掉该 App，唤醒成功不代表 App 状态确定）")
        return

    # 注意：不在此处无条件 am start 唤醒——
    # 频繁 am start 会触发国产 ROM 重建 Activity（标签全部丢失）。
    # call() 内部仅在 API 不可达时才自动唤醒重试。

    if args.cmd == "version":
        print_version()
    elif args.cmd == "status":
        print_status()
    elif args.cmd == "probe":
        sys.exit(do_probe())
    elif args.cmd == "navigate":
        do_navigate(args.url, wait=not args.no_wait)
    elif args.cmd == "search":
        from urllib.parse import quote_plus  # noqa: F401  (搜索引擎映射在 App 侧)
        d = {"query": args.query}
        if args.engine:
            d["engine"] = args.engine
        before_s = fetch_status()
        before = before_s.get("url") or ""
        if before:
            print(f"  旧页: {short_url(before)}")
        r = out(call("/api/search", d, timeout=20))
        info = r.get("info") or {}
        if info.get("title"):
            print(f"  navigate 回执: title={info['title']!r}  ← 通常是旧页标题，不可作为成功依据")
        if args.no_wait:
            print("  ⚠ 未等待就绪，结果未验证")
            return
        s, outcome = wait_ready_first_real(before, timeout=READY_TIMEOUT)
        actual = s.get("url") or ""
        print(f"  结果页: {actual[:70]}")
        print(f"  标题: {title_of(s) or '(空)'}")
        if outcome == "ok":
            print("  ✅ 搜索结果页已加载")
        else:
            print(f"  ❌ 搜索未确认成功（{outcome}）")
            for x in diagnose(s, before=before):
                print(f"     · {x}")
            sys.exit(4)
    elif args.cmd in ("back", "forward", "reload"):
        print("ok" if out(call("/api/" + args.cmd)).get("ok") else "fail")
    elif args.cmd == "scroll":
        require_real_page("scroll")
        d = {"px": args.px}
        if args.selector:
            d["selector"] = args.selector
        elif args.x is not None and args.y is not None:
            d = {"x": args.x, "y": args.y}
        else:
            d["dir"] = args.dir or "down"
        print("ok" if out(call("/api/scroll", d)).get("ok") else "fail")
    elif args.cmd == "click":
        require_real_page("click")
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
        require_real_page("fill")
        print("ok" if out(call("/api/fill", {"selector": args.selector, "value": args.value})).get("ok") else "fail")
    elif args.cmd == "eval":
        if not args.force:
            require_real_page("eval")
        print(out(call("/api/evaluate", {"js": args.js})).get("result"))
    elif args.cmd == "dom":
        require_real_page("dom")
        print(json.dumps(out(call("/api/dom"))["result"], ensure_ascii=False, indent=1)[:3000])
    elif args.cmd == "links":
        require_real_page("links")
        for l in out(call("/api/links"))["result"] or []:
            print(f"  {(l.get('text') or '(无文字)')[:40]} -> {l.get('href','')[:60]}")
    elif args.cmd == "screenshot":
        r = out(call("/api/screenshot"))
        open(args.out, "wb").write(base64.b64decode(r["result"].split(",")[1]))
        print("saved", args.out)
        # 截图前先提示当前页是否可疑，避免"截到了旧页却以为成功"
        w = diagnose(fetch_status())
        if w:
            print("⚠ 注意：截图时页面存在问题，图可能是旧页/错误页：")
            for x in w:
                print(f"  · {x}")
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


def wait_ready_first_real(before, timeout=READY_TIMEOUT):
    """搜索场景专用：不预设目标 URL，只要落在"真实网页且非旧页"即可"""
    deadline = time.time() + timeout
    last = {}
    while time.time() < deadline:
        s = fetch_status()
        last = s
        url = s.get("url") or ""
        prog = s.get("progress")
        title = title_of(s)
        if prog == 100 and title and not is_error_page(s) and not is_internal_page(s):
            if before is None or norm_url(url) != norm_url(before):
                return s, "ok"
        time.sleep(POLL_INTERVAL)
    return last, "timeout"


if __name__ == "__main__":
    main()
