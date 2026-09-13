# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Nothing yet

## [1.0.1] - 2026-09-13

### Fixed
- Slow page loading now gives clear feedback: floating progress bar (overlay on
  the web content, only visible while loading), "…" marker on loading tabs,
  reload button turns into ✕ while loading
- ✕ (stop loading) now reliably cancels loading: stopLoading + window.stop()
  + delayed re-check fallback
- Tabs created while the app was frozen/backgrounded never got laid out
  (viewport 0, broken screenshots) - force relayout on tab switch plus a
  measure fallback in the screenshot API

### Changed
- Top bar is now a frosted-glass translucent style with immersive
  edge-to-edge status bar (deep blue bar removed)

## [1.0.0] - 2026-09-13

### Added
- Initial release: PhoneWebMCP on-device WebView browser (古月) with
  local JSON API and MCP adapter
- Multi-tab browser (max 8), bookmarks, 29 search engines (incl. CN AI
  search), UA switching (11 presets), cache/Cookie/JS console management
- Local JSON API on 127.0.0.1:8765 with random token auth: navigate /
  search / scroll / click (selector·text·coordinate) / fill / evaluate /
  wait / waitFor / dom / links / screenshot / tabs / bookmarks / settings
- Zero-dependency stdio MCP server adapter (25 tools)
- pi skill (gu-browser-automation) with gu.py CLI client
- GitHub Actions CI: builds APK on push, auto-release on tag
