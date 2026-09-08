# Pinboard Changelog

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## [0.0.4] - 2026-09-08

### Added

- Edit the note on a pin that is still pending, from the tool window: `F2`, the toolbar button, or
  the row's context menu. Locked once the agent acknowledges the item, since it is already working
  from the words it read. Only the wording changes; the pinned code stays as captured.
- Copy a whole pin as paste-ready Markdown: location, status, pinned timestamp, the stale or
  file-missing note when applicable, the note, the code snapshot in a fenced block, and the whole
  conversation. Reachable the same four ways as Edit and Delete - the toolbar button, the row's
  context menu, right-click inside the detail panel, or Ctrl+C with the list focused. Disabled when
  nothing is selected. When text in the detail panel is highlighted the entry reads Copy Selection
  and copies exactly that, so the gesture never quietly takes more than you asked for.

### Changed

- The detail panel's header, note, and conversation messages are focusable and selectable, so any
  visible text can be dragged over and copied. They used to be non-focusable HTML panes that
  neither highlighted nor copied.
- The code snapshot in the detail panel is a live read-only viewer instead of a disabled editor:
  full contrast, selectable, copyable, and Ctrl+F works inside it. It is still read-only.
- The code snapshot shows as a scrollable block rather than a single line. Once the file has moved
  on it is the only record of what was actually pinned, which is when it matters most.

### Fixed

- Folding or unfolding a status group no longer clears the selection and the detail panel. Expanding
  Resolved to glance at something used to deselect whatever you were reading.

## [0.0.3] - 2026-09-05

### Added

- `make release VERSION=x.y.z` does a whole release in one command: bumps the version, rolls the
  changelog, runs the tests and the verifier, commits, tags, publishes to the Marketplace and pushes.

### Changed

- The version lives in `gradle.properties` instead of `build.gradle.kts`, so the release command can
  rewrite it.

### Fixed

- The agent skill opened its loop on `feedback_watch`, which only ever returns items pinned after the
  call. Anything already waiting in the queue was invisible, so an agent could sit blocking on an
  empty queue while the backlog went untouched. It now starts with `feedback_list`, and reads
  `totalPending` to notice a backlog after a timeout. Both user guides carried the same mistake.

## [0.0.2] - 2026-09-05

### Added

- User guide in English and Vietnamese under `docs/`, including how to connect agents other than
  Claude Code and where each client keeps its standing instructions.
- One-command skill install for any agent: `npx skills add trantruong-dev/pinboard`.
- Links to the documentation and to a donation page at the end of the Marketplace description.
- Claude Code plugin manifest, so the skill also installs with `/plugin marketplace add
  trantruong-dev/pinboard` instead of being copied by hand.

### Changed

- The Claude Code skill moved from `claude-skill/SKILL.md` to `skills/pinboard/SKILL.md`, the layout
  Claude Code discovers automatically.

## [0.0.1] - 2026-09-05

### Added

- Pin a selection or a whole file into a per-project feedback queue, from the editor context menu,
  the Project view, or the editor tab.
- Pinboard tool window: queue grouped by status, agent replies, jump back to the pinned code,
  delete one item or all of them.
- Seven MCP tools on the IDE's own server - `feedback_list`, `feedback_watch`,
  `feedback_acknowledge`, `feedback_resolve`, `feedback_dismiss`, `feedback_reply`,
  `feedback_clear_resolved`.
- Stale detection: an item whose code changed after it was pinned is flagged, and the agent is given
  the original snapshot and the enclosing symbol to relocate from.
- Capture in a balloon at the caret instead of a modal dialog, and a floating button over a
  selection.
- Queue rendered as cards that wrap the note to the panel width, grouped under collapsible status
  headers, with a ribbon showing whether the agent is actually reaching the MCP tools.
- Pinned ranges marked in the editor - gutter icon, range highlight, and a tinted file tab.
- Pins follow their code when the file above them changes, so line numbers stay right without
  a re-pin.
- Claude Code skill in `claude-skill/SKILL.md`.

[Unreleased]: https://github.com/trantruong-dev/pinboard/compare/v0.0.4...HEAD
[0.0.4]: https://github.com/trantruong-dev/pinboard/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/trantruong-dev/pinboard/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/trantruong-dev/pinboard/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/trantruong-dev/pinboard/commits/v0.0.1
