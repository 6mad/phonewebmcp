#!/usr/bin/env python3
"""竞态回归测试：新标签 400ms 延迟加载 vs 外部导航。

背景
----
App 新建标签时会先 `loadUrl("about:blank")` 预热渲染（避免首帧白屏），
再用 `postDelayed(400ms)` 加载真实页/主页。若外部（自动化 API、URL 栏）
在这段时间内发起导航，那个延迟回调会把它覆盖掉，
表现为"导航了却停在默认标签页"——这是本技能最初的一个假阳性来源。

为什么这个测试比"冷启动测试"更好用
------------------------------------
* **可自主复现**：每次 `/api/tab/new` 都会走同一段代码，不依赖用户杀进程。
* **冷启动测试无法稳定构造**：Termux 的 `am` 不支持 `force-stop`，
  `am start -S` 实测也不杀进程（端口全程 200）。
* 冷启动时 UI 线程忙于恢复会话，`postDelayed` 回调会被推迟执行，
  实际覆盖窗口远长于 400ms 且不可预测；本测试直接命中同一代码路径。

判定依据
--------
导航后最终落点应是**请求的目标页**；若变成 `data:`（内置主页）或
会话恢复的那一页，说明被覆盖，修复失效。

用法
----
    python3 race_newtab_test.py [--trials 4]

退出码：0 全部通过 / 1 存在被覆盖
"""
import argparse
import http.client
import json
import re
import subprocess
import sys
import time
import urllib.request

API = "http://127.0.0.1:8765"
HOST, PORT = "127.0.0.1", 8765
PKG = "com.pi.webviewtool/.MainActivity"


def get_token():
    """token 持久化，优先复用缓存；取不到再走 HTTP"""
    try:
        h = urllib.request.urlopen(API + "/", timeout=4).read().decode()
        m = re.search(r"const T='([0-9a-f]{32})'", h)
        return m.group(1) if m else None
    except Exception:
        return None


class Client:
    """复用 HTTP 连接：navigate 越早发出，越能命中 400ms 窗口，测试越有力"""

    def __init__(self):
        self.conn = http.client.HTTPConnection(HOST, PORT, timeout=30)

    def call(self, path, data=None):
        try:
            self.conn.request(
                "POST" if data is not None else "GET",
                path,
                body=json.dumps(data).encode() if data is not None else None,
                headers={"X-Api-Token": TOKEN, "Content-Type": "application/json"},
            )
            return json.loads(self.conn.getresponse().read().decode())
        except Exception:
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
            raise


TOKEN = None


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=4)
    args = ap.parse_args()

    TOKEN = get_token()
    if not TOKEN:
        print("  服务端不可达，尝试唤醒…")
        subprocess.run(["am", "start", "-n", PKG], capture_output=True)
        time.sleep(3)
        TOKEN = get_token()
    if not TOKEN:
        sys.exit("API 不可达：请先打开古月浏览器")

    c = Client()
    c.call("/api/status")  # 预热连接

    info = c.call("/api/info") or {}
    print(f"服务端: {info.get('appVersionName')} (apiVersion {info.get('apiVersion')})")

    # 能力检查：没有 navigate_wait 也照样能跑，只是少一层确认
    feats = set(info.get("features") or [])
    if "navigate_wait" not in feats:
        print("  ⚠ 服务端未声明 navigate_wait（旧版 APK）——仍可测，但结果仅靠落点判断")

    print(f"\n=== 新标签 400ms 竞态测试（{args.trials} 次）===\n")
    passed = 0
    worst_ms = 0.0
    for trial in range(1, args.trials + 1):
        target = f"https://www.iana.org/help/example-domains?rt={trial}"
        c.call("/api/tab/new", {})
        t0 = time.time()
        try:
            r = c.call("/api/navigate", {"url": target}) or {}
        except Exception as e:
            print(f"  #{trial} navigate 异常: {e}")
            continue
        dt = (time.time() - t0) * 1000
        worst_ms = max(worst_ms, dt)

        time.sleep(2.0)  # 等延迟加载窗口彻底过去
        s = (c.call("/api/status") or {}).get("result") or {}
        u = s.get("url") or ""
        ok = "iana.org" in u
        passed += ok

        where = ("✅ 目标页" if ok
                 else "❌ 内置主页（被覆盖）" if u.startswith("data:")
                 else "❌ 其他页面（被覆盖）")
        print(f"  #{trial}  navigate 发出耗时 {dt:.0f}ms  "
              f"{'★ 在窗口内' if dt < 400 else '（较慢）'}")
        print(f"       beforeUrl = {str(r.get('beforeUrl'))[:50]}")
        print(f"       loadOutcome = {r.get('loadOutcome')}")
        print(f"       最终 url  = {u[:62]}")
        print(f"       {where}")

        # 清理：关掉刚建的标签，避免堆积（最多 8 个）
        try:
            tabs = (c.call("/api/tabs") or {}).get("result") or []
            if len(tabs) > 1:
                c.call("/api/tab/close", {"index": tabs[-1]["index"]})
        except Exception:
            pass

    print(f"\n  结果: {passed}/{args.trials} 导航未被覆盖"
          f"（navigate 最慢 {worst_ms:.0f}ms）")
    if passed == args.trials:
        print("  ✅ 竞态防护生效")
        return 0
    print("  ❌ 存在被覆盖：newTab 的延迟加载仍在抢导航")
    print("     检查 controller.navigationGeneration() 是否在所有 loadUrl 路径上递增")
    return 1


if __name__ == "__main__":
    sys.exit(main())
