# Pinboard Changelog

## [Unreleased]

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
- Claude Code skill in `claude-skill/SKILL.md`.
