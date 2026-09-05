# Pinboard Changelog

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

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

[Unreleased]: https://github.com/trantruong-dev/pinboard/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/trantruong-dev/pinboard/commits/v0.0.1
