# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Nothing yet

## [1.1.0] - 2026-09-13

### Added
- Editable home page: long-press / manage mode to edit/delete/add bookmarks
  and quick-nav shortcuts (JS bridge persistence)
- Search button next to the search box (touch friendly, no keyboard needed)
- Dark mode: follows system + manual cycle toggle (system/dark/light), CSS
  variables, deep-gray palette (not pure black)
- Fullscreen toggle (^/down arrow): animated collapse of tab bar + address
  bar, floating translucent button between + and menu
- 4-column bookmark grid, badge truncation (grid minmax(0,1fr))

### Changed
- Frosted-glass translucent top bar with immersive edge-to-edge status bar
  (status bar height from system insets, exact fit)
- Pill-shaped tab buttons, adjacent tabs use different modern colors
- Bookmark/quick-nav cards smaller and aligned; page content shifted down
- Compressed tab bar and address bar rows

### Fixed
- Page JS was broken (Java text block ate `\'` escapes in JS strings) -
  replaced string-built inline handlers with addEventListener
- Dark mode theme attribute now applied on <html> (:root) so CSS variables
  take effect
- Tab strip auto-scrolled to end when switching - now scrolls to selected tab
- Search engine button positioned outside the input box - wrapped in .box
- Old "home" menu item navigated to Baidu instead of the built-in home page

## [1.0.1] - 2026-09-13

### Fixed
- Slow page loading now gives clear feedback: floating progress bar (overlay on
  the web content, only visible while loading), "..." marker on loading tabs,
  reload button turns into x while loading
- x (stop loading) now reliably cancels loading: stopLoading + window.stop()
  + delayed re-check fallback
- Tabs created while the app was frozen/backgrounded never got laid out
  (viewport 0, broken screenshots) - force relayout on tab switch plus a
  measure fallback in the screenshot API

### Changed
- Top bar is now a frosted-glass translucent style with immersive
  edge-to-edge status bar (deep blue bar removed)

## [1.0.0] - 2026-09-13

### Added
- Initial release: PhoneWebMCP on-device WebView browser (GuYue) with
  local JSON API and MCP adapter
- Multi-tab browser (max 8), bookmarks, 29 search engines (incl. CN AI
  search), UA switching (11 presets), cache/Cookie/JS console management
- Local JSON API on 127.0.0.1:8765 with random token auth: navigate /
  search / scroll / click (selector·text·coordinate) / fill / evaluate /
  wait / waitFor / dom / links / screenshot / tabs / bookmarks / settings
- Zero-dependency stdio MCP server adapter (25 tools)
- pi skill (gu-browser-automation) with gu.py CLI client
- GitHub Actions CI: builds APK on push, auto-release on tag
