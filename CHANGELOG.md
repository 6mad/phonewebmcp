# Changelog

All notable changes to this project will be documented in this file.

## [1.3.5] - 2026-09-16

### Added
- **Load-state reporting** in `/api/status`: `loading`, `everLoaded`,
  `pageStartedAt`, `pageAgeMs`, `lastRequestedUrl`, `sessionRestored`,
  `sessionRestoredUrl` - lets clients tell a freshly loaded page apart from
  a session-restored one
- **`/api/navigate?wait=1`**: blocks until the navigation actually finishes and
  returns the real outcome (`beforeUrl`, `requestedUrl`, `finalUrl`,
  `finalTitle`, `loadFailed`, `loadError`, `loadErrorCode`, `loadOutcome`)
- **Real error reporting** via `WebViewClient.onReceivedError` (main frame only):
  `lastLoadFailed` / `lastErrorDesc` / `lastErrorCode`, e.g. `-2`
  (`net::ERR_NAME_NOT_RESOLVED`). Language-independent, unlike guessing from
  the error page title
- **Version & capability reporting** in `/api/info`: `apiVersion`,
  `appVersionName`, `appVersionCode`, and a `features` capability list
- `race_newtab_test.py`: regression test for the new-tab navigation race

### Fixed
- **Navigation silently lost on cold start** - a new tab pre-loads
  `about:blank` to warm up the render surface, then loads the real page (or home
  page) via `postDelayed(400ms)`. An external navigation issued within that
  window was overwritten, so the browser appeared stuck on the default tab
  while the API reported success. The delayed callback now yields when a
  navigation has happened in the meantime
- **`WebView.getUrl()` cannot be used to detect an in-flight navigation** - before
  the new page commits, it still returns the *previous* URL (`about:blank` in
  practice), so "still blank" does not mean "nobody navigated". Replaced with an
  explicit `navigationGeneration` counter bumped by every navigation entry point
- **False "load failed" verdict** - page title updates lag behind the `loading`
  flag, so a stale error-page title from the previous page could make a
  successful navigation look like a failure. A verdict is now only drawn once
  the current URL is confirmed to be the requested one; otherwise `pending`

### Changed
- `/api/info` no longer hardcodes `apiVersion` to `"1.0"`; client-side version
  guessing is replaced by capability negotiation

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
