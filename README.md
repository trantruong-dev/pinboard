# Pinboard

Pin feedback onto several pieces of code, then tell your agent to work through the lot.

Adds an asynchronous feedback queue on top of the MCP server your JetBrains IDE already ships.

## Why this exists

Your IDE's built-in MCP server can already hand an agent your current selection. That is a
synchronous, one-shot channel: you point at something, the agent looks at it, the moment is gone.

Reviewing code is not like that. You read through a file and spot five things. You want to note
all five, keep reading, and hand the batch over when you are done - and you want a record of what
you asked for and what the agent did about it.

That is what this plugin adds:

- **A queue.** Pin as many notes as you like, whenever you like. Nothing is sent yet.
- **Batching.** The agent picks up a cluster of feedback in one call instead of one round trip
  each.
- **Threads.** The agent replies, asks questions, and records what it did. You read that back
  next to the code you pinned.
- **It survives a restart.** Close the IDE, reopen it, the queue is still there with its history.
- **A stale flag.** If the code moved or changed after you pinned it, the agent is told so, and
  is given the original snapshot and the enclosing symbol to relocate from.

## Requirements

- Any JetBrains IDE, build 252 (2025.2) or newer
- The bundled **MCP Server** plugin enabled - it ships with the IDE

## Install

1. **Settings | Plugins | Marketplace**, search for *Pinboard*, Install, restart the
   IDE.
2. Connect your agent to the IDE's MCP server. In 2025.2 this lives under
   **Settings | Tools | MCP Server**; in 2026.x it moved to
   **Settings | Tools | Client Auto-Configuration**. Claude Code is configured with one click -
   the IDE writes the entry for you.
3. Restart your agent so it picks up the new server.

There is no MCP configuration to write by hand. The tools appear on the IDE's existing server.

The plugin requires an IDE restart when installed or updated. That is deliberate: it registers
MCP tools, and a partial hot-reload would leave the tool window running while the tools silently
disappeared.

## Use

**Pin a selection.** Select code, then press `Ctrl+Alt+Shift+F` (`Cmd+Alt+Shift+F` on macOS), click
the button that floats over the selection, or right-click and choose **Pin for Agent**. A balloon
opens at the caret: type your note and press `Ctrl+Enter` (`Cmd+Enter`). Esc cancels; clicking into
the editor to re-read the code does not.

**Pin a whole file.** Right-click the file in the Project view or its editor tab, **Pin File for
Agent**.

**See your pins in the code.** A pinned range is tinted, marked in the error stripe, and carries a
pin in the gutter that brings the queue forward. Any file with open feedback gets a faint wash on
its tab. Editing above a pin moves it with the code rather than reporting it stale.

**Review the queue.** The **Pinboard** tool window on the right shows everything as cards grouped by
status, with the agent's replies. A bar across the top shows how much of the queue is done, and the
tool window icon carries a dot while anything is pending. Click a status header to fold the group.
Double-click an item or press Enter to jump back to the code. Delete a single item with the Del key,
the toolbar button, or the row's context menu; **Clear** removes finished work in bulk.

## Teaching your agent to use it

The tools are available as soon as the plugin is installed, but an agent will not know when to
reach for them. For Claude Code, copy [`claude-skill/SKILL.md`](claude-skill/SKILL.md) into
`~/.claude/skills/pinboard/SKILL.md`. It tells the agent to pick up batches, how to
read the `stale` flag, and to close each item with a summary you can audit.

## The tools

| Tool | What it does |
|---|---|
| `feedback_list` | Current queue. PENDING and ACKNOWLEDGED by default. |
| `feedback_watch` | Blocks until new items arrive, returns them as one batch. |
| `feedback_acknowledge` | Marks items as seen. Takes a whole batch at once. |
| `feedback_resolve` | Closes an item with a required summary of what was done. |
| `feedback_dismiss` | Closes an item with a required reason for not acting. |
| `feedback_reply` | Adds a question or note to an item's thread, status unchanged. |
| `feedback_clear_resolved` | Deletes items that are already resolved or dismissed. |

**The agent cannot create feedback, and cannot delete anything still pending.** The queue is your
record of what you asked for. An agent that could quietly clear work it had not finished would
destroy the only copy of it.

## One queue per project

Queues never mix. Each project gets its own file, named from a hash of the project's base path,
and the tool window and MCP tools both read the queue of the project they were opened in. Open ten
projects at once and an agent working in one of them sees only that one's feedback.

The path is normalised first, so the same project keeps its queue whether the IDE reports it with
forward or backslashes, with or without a trailing slash. Case is left alone on purpose - paths are
case-sensitive on Linux, and folding it would merge two genuinely different projects.

## Known limitations

**Two IDE windows on the same repository share one queue.** That is deliberate, and thread messages
from both sides are merged rather than dropped. But everything else on an item - the note itself,
its status - still resolves last-write-wins: edit the same item from both windows at once and one
edit is lost. Resolving that properly needs per-field timestamps or a CRDT, which is a steep price
for a notes queue. If you work this way, edit an item from one window at a time.

**A long agent turn can outlive the IDE's MCP session.** If your agent reports
`HTTP 404: Session not found`, the SSE session dropped - the session belongs to the IDE's built-in
MCP server, not to this plugin. Reconnect (`/mcp` in Claude Code) and ask the agent to retry. The
queue is untouched: nothing is lost, and an acknowledged item is still sitting there waiting to be
resolved.

## Is the agent actually connected?

The chip at the top right of the tool window answers that, and the line along the bottom says when
the agent last called a tool. Open **Log** next to it for the raw list of calls.

| Chip | What it means |
|---|---|
| **Agent active** | A tool call arrived in the last ten minutes |
| **Idle** | An agent has called before, but not lately. Normal between tasks |
| **Waiting for agent** | Everything is wired up; nothing has called yet |
| **Tools not registered** | The plugin loaded without its MCP tools. Restart the IDE |

The chip never says "connected". This plugin rides the IDE's MCP server rather than running its own,
so it cannot ask whether a client is attached - it can only report calls that actually arrived, and
it says exactly that much and no more.

## Where your code goes

Nowhere. The plugin makes no network calls and collects no telemetry.

The queue is stored as JSON under the IDE's system directory
(`PathManager.getSystemPath()/pinboard/`), one file per project, outside your repository so
it never lands in a commit. **It contains verbatim source code** - the snapshot of everything you
pin - so treat it with the same care as the repository itself.

The MCP server that serves these tools is the IDE's own, bound to localhost.

## Building from source

```
make build      # compile and test
make run        # launch a sandbox IDE with the plugin
make verify     # JetBrains plugin verifier
make dist       # produces build/distributions/*.zip
make ci         # everything CI runs, before you push
```

`make` on its own lists every target. It wraps Gradle, so `./gradlew build` and friends work
exactly as before if you prefer them.

Two targets take an argument:

```
make test TEST='*.AnchorRegistryTest'   # one class, or one method
make verify IDE=IC-2025.3               # one IDE, as the CI matrix does
```

Requires JDK 21.

## License

[Apache-2.0](LICENSE)

Parts of the presentation and interaction design - the card queue, the inline capture balloon, and
the editor decorations - are adapted from [Marginalia](https://github.com/borgand/marginalia) (MIT).
See [NOTICE](NOTICE).
