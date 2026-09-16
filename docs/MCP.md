# MCP 接入指南

PhoneWebMCP 提供标准 MCP (`Model Context Protocol`) stdio 适配层，
零第三方依赖，任何支持 MCP 的客户端可直接连接。

## 运行

```bash
python3 mcp-server/phonewebmcp.py
```

无需安装任何 Python 包（仅标准库）。启动后通过 stdin/stdout 与 MCP 客户端通信，
内部自动完成：唤醒古月 App（若被系统冻结）→ 提取 token → 调用 HTTP API。

## Claude Code

```bash
claude mcp add phonewebmcp -- python3 /path/to/phonewebmcp.py
```

## Cursor

`Settings → MCP → Add new MCP server`，类型选择 `stdio`，命令填：
```
python3 /path/to/phonewebmcp.py
```

## 通用客户端（自定义）

任何支持 MCP 的客户端按 `initialize → tools/list → tools/call` 流程即可。

可用工具（25+）：

```
navigate  search  status  dom  links  scroll  click  fill  evaluate
screenshot  wait  waitFor  back  forward  reload  tabs  tab_new
tab_switch  tab_close  bookmarks  bookmark_add  bookmark_remove
cookies  cookies_clear  cache_clear  settings
```

每个工具的输入 schema 见 `tools/list` 返回。

## 示例会话

```
User: 用手机浏览器打开 bilibili 首页，截个图
Agent: tools/call navigate {"url": "https://www.bilibili.com"}
       tools/call wait {"ms": 3000}
       tools/call screenshot {}
```

## 注意事项

- App 必须在同一台设备上运行（API 在 127.0.0.1 回环）
- **导航后务必确认真的生效**：App 会恢复上次会话的标签，`navigate` 的返回是
  乐观的（其 `info.title` 往往是上一个页面的标题）。冷启动后 `status` 可能立刻
  返回一个正常的旧页面，看起来像"已打开"。请用 `navigate?wait=1` 并检查
  `loadOutcome`，或轮询 `status.pageAgeMs` / `sessionRestored` 来判断
- 若长时间未操作被系统冻结，适配层会自动 `am start` 唤醒（需要能在设备上执行
  `am` 命令的环境，如 Termux 或 ADB shell）