# Pinboard - User Guide

Pin feedback onto several pieces of code, then tell your agent to work through the lot.

This guide covers installation, connecting an agent, everyday use, and what to do when something
does not work. A Vietnamese version is at [user-guide-vi.md](user-guide-vi.md).

---

## 1. What Pinboard is for

Your JetBrains IDE already ships an MCP server, and it can already hand an agent your current
selection. That channel is synchronous and one shot: you point at something, the agent looks at it,
the moment is gone.

Reviewing code does not work that way. You read a file, spot five things, and want to note all five,
keep reading, and hand the batch over when you are done.

Pinboard adds that missing piece:

- **A queue.** Pin as many notes as you like. Nothing is sent yet.
- **Batching.** The agent picks up a cluster in one call instead of a round trip each.
- **Threads.** The agent replies, asks questions, and records what it did, next to the code you
  pinned.
- **It survives a restart.** Close the IDE, reopen it, the queue and its history are still there.
- **A stale flag.** If the code changed after you pinned it, the agent is told so and is given the
  original snapshot to relocate from.

---

## 2. Requirements

| | |
|---|---|
| IDE | Any JetBrains IDE, build **252 (2025.2)** or newer - IntelliJ IDEA, PyCharm, WebStorm, GoLand, and the rest |
| Editions | Community and Ultimate both work |
| Bundled plugin | **MCP Server** must be enabled. It ships with the IDE; you do not install it separately |
| Agent | Any MCP client that can talk to the IDE's server. Claude Code is configured in one click |
| Git | Optional. If the project is a Git repository, Pinboard records the revision a pin was made at |

### Checking the MCP Server plugin

**Settings | Plugins | Installed**, search for *MCP Server*. It must be present and enabled.

If it is disabled, Pinboard will refuse to install - it declares a hard dependency on it, because
without that server there is nowhere for the tools to appear.

> **Note on IDE versions.** Pinboard sets no upper version bound on purpose, so it keeps working
> when your IDE updates to a new major version. The trade-off is that a future IDE could change
> something underneath it. If something breaks right after an IDE update, that is the first thing
> to suspect.

---

## 3. Installing the plugin

1. **Settings | Plugins | Marketplace**.
2. Search for **Pinboard**.
3. **Install**.
4. **Restart the IDE** when prompted.

The restart is not optional and is not laziness on the plugin's part. Pinboard registers MCP tools,
and a partial hot reload would leave the tool window running while the tools silently disappeared -
the worst possible failure, because everything looks fine. Forcing a restart makes install and
update all or nothing.

### Installing from a file instead

If you have a `pinboard-<version>.zip`:

**Settings | Plugins**, the gear icon, **Install Plugin from Disk...**, pick the zip, restart.

### Confirming it installed

After the restart you should see a **Pinboard** tool window button on the right-hand edge of the
IDE window. Open it. If it is there, the plugin loaded.

---

## 4. Connecting your agent

Pinboard does not run a server of its own. Its tools appear on the MCP server your IDE already runs,
so there is no MCP configuration to write by hand.

1. Open the IDE's MCP settings:
   - **2025.2**: Settings | Tools | **MCP Server**
   - **2026.x**: Settings | Tools | **Client Auto-Configuration**
2. Find your client in the list. For **Claude Code**, one click writes the entry for you.
3. **Restart your agent** so it picks up the server.

For Claude Code specifically, after restarting run `/mcp` and check that the IDE server is listed
and connected.

### What you should see

Once an agent has called any Pinboard tool even once, the chip at the top right of the tool window
changes. See [section 8](#8-is-the-agent-actually-connected).

---

## 5. Teaching your agent when to use it

The tools exist as soon as the plugin is installed, but an agent has no reason to reach for them.
It does not know that a queue exists or when to check it. That is what the skill is for: it tells
the agent to pick up batches, how to read the `stale` flag, and to close every item with a summary
you can audit.

### Option A - one command, any agent (recommended)

```bash
npx skills add trantruong-dev/pinboard
```

That is the [`skills`](https://github.com/vercel-labs/skills) CLI, the installer for the open
[Agent Skills](https://agentskills.io) ecosystem. It knows where each coding agent keeps its skills -
Claude Code, Codex, Cursor, OpenCode, Gemini CLI, GitHub Copilot, Cline, Continue, Zed, Junie,
Windsurf and dozens more - so it writes the skill to the right place without you looking anything up.
It will ask which agents to install for.

Useful variants:

```bash
npx skills add trantruong-dev/pinboard --list          # just show what is in there, install nothing
npx skills add trantruong-dev/pinboard -g              # install globally, for every project
npx skills add trantruong-dev/pinboard -g -a claude-code -y   # one agent, no prompts
```

**Scope matters.** By default it installs into the **current project** (`./.claude/skills/` and the
equivalent for other agents), which means it is committed with your repository and your teammates get
it too. Add `-g` to install into your home directory instead, so it applies everywhere and touches no
repository.

Nothing else to configure. The skill works the moment your agent next starts.

### Option B - Claude Code plugin

If you would rather manage it through Claude Code's own plugin system:

```
/plugin marketplace add trantruong-dev/pinboard
/plugin install pinboard@trantruong-dev
/reload-plugins
```

The first line registers this repository as a plugin marketplace, the second installs the skill from
it, and the third activates it without restarting Claude Code. `/plugin` then offers you updates when
a new version ships.

- The marketplace is registered **per user**, so you do it once, not once per project.
- `/plugin` needs a recent version of Claude Code. If it is not recognised, use option A or C.

### Option C - copy the file by hand

Copy [`skills/pinboard/SKILL.md`](../skills/pinboard/SKILL.md) from this repository to:

| | |
|---|---|
| macOS / Linux | `~/.claude/skills/pinboard/SKILL.md` |
| Windows | `%USERPROFILE%\.claude\skills\pinboard\SKILL.md` |

Create the directories if they do not exist. This works on every version of Claude Code.

---

## 5b. Agents other than Claude Code

**Nothing about Pinboard is Claude-specific.** The plugin adds its tools to the MCP server your IDE
already runs, so any MCP client that connects to that server gets all seven tools, with no
Pinboard-side configuration at all. The skill is only a convenience for teaching an agent *when* to
reach for them.

There are two steps for any client: connect it, then tell it when to look.

### Step 1 - connect the client to the IDE

Open the IDE's MCP settings (**Settings | Tools | MCP Server** in 2025.2,
**Settings | Tools | Client Auto-Configuration** in 2026.x).

**Clients the IDE configures for you.** In 2025.2 the bundled MCP Server plugin ships dedicated
support for:

- Claude Code
- Claude Desktop
- Cursor
- VS Code
- Windsurf

One click and the IDE writes the config file for that client itself. Newer IDE versions may add
more, so trust the list on that settings page over this one.

**Every other client.** The same page has a **Manual Client Configuration** entry with two buttons:

| Button | Use it when |
|---|---|
| **Copy SSE Config** | Your client speaks MCP over SSE. Prefer this if it supports both |
| **Copy Stdio Config** | Your client only speaks MCP over stdio |

Paste the copied block into wherever your client keeps its MCP servers. This is the standard MCP
config shape, so it drops into Cline, Continue, Zed, Codex CLI, Gemini CLI, JetBrains Junie, a
homemade client, or anything else that speaks MCP.

**Restart the client afterwards.** Most clients only read their MCP config at startup.

If the IDE shows a *"MCP clients detected"* notification, it has spotted a client on your machine
and is offering to configure it - that is the same thing, just initiated by the IDE.

### Step 2 - tell the client when to use the tools

**Try the one-liner first.** `npx skills add trantruong-dev/pinboard` from
[section 5](#5-teaching-your-agent-when-to-use-it) is not Claude-only - it supports dozens of agents
and writes the skill into the directory each one reads. If your agent is on its list, you are done
and can skip the rest of this step.

For an agent it does not cover, do it by hand. The skill file is plain Markdown: everything below its
`---` frontmatter is client-agnostic prose, so copy that body into whatever file your client reads as
standing instructions.

| Client | Where its project instructions live |
|---|---|
| Cursor | `.cursor/rules/` |
| Windsurf | `.windsurf/rules/` |
| VS Code + GitHub Copilot | `.github/copilot-instructions.md` |
| Cline | `.clinerules/` |
| JetBrains Junie | `.junie/guidelines.md` |
| Codex CLI and a growing number of others | `AGENTS.md` in the project root |

These paths move between versions - check your client's own documentation if one does not take
effect. The content you paste is the same in every case.

**If your client has no instructions file at all**, nothing is lost. Just say it in chat when you
want the queue picked up:

> *"Check the pinboard and work through anything pending."*

The tools are discoverable on their own; the instructions only save you from repeating yourself.

### Two things that bite non-Claude clients

**Tool names are usually prefixed.** Your client may expose `feedback_list` as
`mcp__idea__feedback_list`, `idea.feedback_list`, or similar, depending on how it namespaces
servers. If an agent reports that `feedback_watch` does not exist, it is almost always looking for
the bare name. Tell it to match on the `feedback_` part.

**`feedback_watch` only sees what you pin after it is called.** It holds the connection open waiting
for the next pin, which is what makes batching work without polling - but anything already sitting
in the queue is invisible to it. An agent that opens with `feedback_watch` will sit there looking
idle while your backlog goes untouched. `feedback_list` is what reads the backlog, which is why the
skill tells the agent to start there and only then settle into `watch`.

**Clients also disagree about how long they will wait.** The IDE will happily block for minutes, but
your MCP client will give up first and the call is lost. `feedback_watch`'s `timeoutSeconds`
parameter defaults to 60 seconds for that reason. If your client times out sooner:

- lower `timeoutSeconds` to fit, or
- skip `feedback_watch` entirely and use `feedback_list`, which returns immediately.

Polling with `feedback_list` costs nothing but a round trip and works on every client.

### Checking it worked

Whatever the client, the test is the same: get it to call any Pinboard tool once, then look at the
chip in the tool window. If it says **Agent active**, the client is through.

---

## 6. Everyday use

### Pinning a selection

Select the code, then any one of:

- Press **`Ctrl+Alt+Shift+F`** (**`Cmd+Alt+Shift+F`** on macOS)
- Click the button that floats above the selection
- Right-click and choose **Pin for Agent**

A balloon opens at the caret. Type your note and press **`Ctrl+Enter`** (**`Cmd+Enter`**) to pin it.

- **Esc** cancels.
- Clicking into the editor to re-read the code does **not** cancel - the balloon stays open while
  you look around.

### Pinning a whole file

Right-click the file in the **Project** view, or right-click its **editor tab**, and choose
**Pin File for Agent**. Use this for notes that are about the file as a whole rather than a
particular line.

### Fixing a note you already pinned

Spotted a typo, or worded it badly? While the item is still **pending**, select it in the tool
window and do any one of:

- Press **`F2`**
- Click **Edit** on the toolbar
- Right-click the row and choose **Edit**

The same box reopens with your note in it, alongside the code you pinned. **`Ctrl+Enter`** saves,
**Esc** leaves it alone.

Two limits, both deliberate:

- **Only the wording is editable.** The pinned lines and the snapshot taken at capture time stay as
  they were. To point a note at different code, delete it and pin again.
- **Acknowledged items are locked.** Once the agent has said it has seen an item, it is working
  from the words it read. Rewriting them underneath it is the reliable way to end up with the two
  of you acting on different instructions. Reply to the agent instead.

There is a narrow gap here worth knowing about: an agent can *read* a pending item before it
acknowledges it. If you edit a note while the agent is mid-run, it may already have the old
wording. If you edit at the moment it acknowledges, the change is refused and Pinboard tells you so
rather than silently dropping it.

### Seeing your pins in the code

A pinned range is:

- tinted in the editor,
- marked in the error stripe on the right,
- given a pin icon in the gutter - clicking it brings the queue forward.

Any file with open feedback gets a faint wash on its editor tab, so you can tell at a glance which
open files still have something outstanding.

**Editing above a pin moves the pin with the code** rather than marking it stale. Only a change to
the pinned code itself makes it stale.

### Working in the tool window

The **Pinboard** tool window on the right shows the queue as cards, grouped by status.

| Action | How |
|---|---|
| Jump back to the pinned code | Double-click a card, or select it and press **Enter** |
| Fold or unfold a status group | Click the status header |
| Edit a pending note | **F2**, the toolbar button, or the card's right-click menu |
| Delete one item | **Del**, the toolbar button, or the card's right-click menu |
| Copy one item as Markdown | **Copy** on the toolbar, the card's right-click menu, right-click inside the detail pane with nothing highlighted, or **Ctrl+C** with the list focused |
| Copy just the text you highlighted | Drag over it, then **Copy Selection** from the right-click menu, or **Ctrl+C** |
| Delete finished work in bulk | **Clear** dropdown: *Clear resolved*, *Clear dismissed* |
| Delete everything | **Delete All** - it asks first, because it cannot be undone |

Across the top, a bar shows how much of the queue is done. The tool window icon carries a dot while
anything is still pending, so you can see there is outstanding work without opening the panel.

Selecting a card shows the detail pane: where it points, whether the code moved, the note itself,
the code as it was when you pinned it, and the whole conversation with the agent. Every part of that
is ordinary selectable text - drag over the header, the note, a thread message, or the code snapshot
and Ctrl+C copies exactly what you highlighted. The code snapshot is a live viewer, not a disabled
control: it shows as a scrollable block, and Ctrl+F finds text inside it the same as in any editor.
That block is the only record of what was actually pinned once the file has moved on, which is
exactly when the stale banner tells you to trust it over the line numbers.

### Copying a whole pin

**Copy** puts the selected item on the clipboard as one block of Markdown - location, status, when
it was pinned (plus a stale or file-missing note if either applies), the note, the code snapshot in
a fenced block, and the full conversation. Paste it straight into a chat with an agent that has no
MCP access to the queue.

Reach it the same four ways Edit and Delete already work: the toolbar button, the card's right-click
menu, right-clicking inside the detail pane, or **Ctrl+C** with the list itself focused. It is
disabled when nothing is selected.

Copy never takes more than you asked for. Highlight part of a note and right-click it and the entry
reads **Copy Selection**, and copies exactly that. Right-click with nothing highlighted and it reads
**Copy**, and copies the whole pin. The same rule applies to Ctrl+C: with the list focused it copies
the item, with the caret inside a note or the code snapshot it copies your selection there, because
the detail pane is not part of the list and its own selection wins.

---

## 7. The lifecycle of an item

Every item is in exactly one of four states.

| Status | Meaning |
|---|---|
| **Pending** | You pinned it. Nobody has looked at it |
| **Acknowledged** | The agent has read it and is working on it. **Not finished** |
| **Resolved** | The agent finished it and left a summary of what it did |
| **Dismissed** | The agent decided not to act, and left a reason |

Resolved and dismissed groups start folded, because finished work is history and would otherwise
push pending items out of view.

**Acknowledged is not done.** If an agent restarts mid-task, acknowledged items are the ones it had
already started; a well-behaved agent picks them back up.

**Pending is also your window to change your mind about the wording.** Acknowledgement is the
deadline: after it, the note is locked. See *Fixing a note you already pinned* above.

### When code changes underneath a pin

- **Stale** - the pinned code itself changed after you pinned it. The line numbers can no longer be
  trusted. The agent is told this, and is given the snapshot taken at pin time plus the enclosing
  symbol name so it can find where that code lives now.
- **File missing** - the file was renamed, moved, or deleted. The agent is told to say so rather
  than invent a location.

In both cases the snapshot stays authoritative. Nothing is lost.

---

## 8. Is the agent actually connected?

The chip at the top right of the tool window answers this, and the line along the bottom says when
the agent last called a tool. Open **Log** next to it for the raw list of calls.

| Chip | What it means | What to do |
|---|---|---|
| **Agent active** | A tool call arrived in the last ten minutes | Nothing |
| **Idle** | An agent has called before, but not lately | Normal between tasks |
| **Waiting for agent** | Everything is wired up; nothing has called yet | Check your agent is running and has the IDE server configured |
| **Tools not registered** | The plugin loaded without its MCP tools | Restart the IDE |

The chip deliberately never says "connected". Pinboard rides the IDE's MCP server rather than
running its own, so it cannot ask whether a client is attached. It reports calls that actually
arrived, and nothing more.

---

## 9. The tools your agent gets

| Tool | What it does |
|---|---|
| `feedback_list` | Current queue. Pending and acknowledged by default |
| `feedback_watch` | Blocks until items are pinned *after* the call, returns them as one batch |
| `feedback_acknowledge` | Marks items as seen. Takes a whole batch at once |
| `feedback_resolve` | Closes an item with a required summary of what was done |
| `feedback_dismiss` | Closes an item with a required reason for not acting |
| `feedback_reply` | Adds a question or note to a thread, status unchanged |
| `feedback_clear_resolved` | Deletes items that are already resolved or dismissed |

Your MCP client may show these under a prefix taken from the server name, for example
`mcp__idea__feedback_list`. That is normal.

Long conversations reach the agent trimmed: the 10 newest messages on an item, each capped at 1MB,
with a `threadOmitted` count telling it how many older ones it is not seeing. The message count is
what does the trimming; the size cap is only there to stop one dumped file being the whole payload,
so an ordinary stack trace or diff arrives whole. Your note is never trimmed, and nothing is hidden
from you - the panel still holds the whole thread. The point is that an agent working through a
batch cannot fill its own context with its own replies.

**The agent cannot create feedback, and cannot delete anything still pending or acknowledged.** That
boundary is deliberate. The queue is your record of what you asked for, and an agent that could
quietly clear work it had not finished would destroy the only copy of it.

---

## 10. Where your data goes

Nowhere. The plugin makes no network calls and collects no telemetry.

The queue is stored as JSON under the IDE's system directory
(`PathManager.getSystemPath()/pinboard/`), one file per project, named from a hash of the project's
base path. It sits **outside your repository**, so it can never land in a commit.

**It contains verbatim source code** - the snapshot of everything you pinned - so treat that
directory with the same care as the repository itself.

The MCP server serving these tools is the IDE's own, bound to localhost.

### One queue per project

Queues never mix. Open ten projects at once and an agent working in one of them sees only that
project's feedback.

---

## 11. Troubleshooting

**The Pinboard tool window is not there.**
The plugin did not load. Check **Settings | Plugins | Installed** that Pinboard is enabled, and that
**MCP Server** is enabled too. Restart the IDE.

**The chip says "Tools not registered".**
The plugin loaded but its MCP tools did not register. Restart the IDE. If it persists after a
restart, the MCP Server plugin is probably disabled.

**The chip stays on "Waiting for agent".**
The IDE side is fine - nothing has called yet. Check that your agent is running, that it has the
IDE's MCP server configured, and that you restarted the agent after configuring it. In Claude Code,
`/mcp` lists the servers it can see.

**The agent says it cannot find `feedback_watch`.**
Clients often expose tools under a prefix, so the name may be `mcp__idea__feedback_watch`. An agent
looking for the bare name and finding nothing should look again for the suffix. The skill in
[section 5](#5-teaching-your-agent-when-to-use-it) tells it to do exactly that.

**The agent reports `HTTP 404: Session not found`.**
A long agent turn outlived the IDE's MCP session. This is the IDE's built-in server, not Pinboard.
Reconnect (`/mcp` in Claude Code) and ask the agent to retry. Nothing is lost - an acknowledged item
is still sitting there waiting to be resolved.

**A pin says "file missing" but the file is right there.**
The file was moved or renamed after the pin was made. Pinboard records the path as it was. The
snapshot is still intact, so the note is still readable and the agent is told to relocate rather
than guess.

**Two IDE windows on the same repository.**
They share one queue, deliberately, and thread messages from both sides are merged rather than
dropped. But everything else on an item - the note, its status - resolves last write wins. Edit the
same item from both windows at once and one edit is lost. If you work this way, edit an item from
one window at a time.

---

## 12. A worked example

1. You are reviewing a pull request in the IDE. You find four things across three files.
2. You select each one and press `Ctrl+Alt+Shift+F`, typing a short note each time. Four cards
   appear under **Pending**. Nothing has been sent anywhere.
3. You tell your agent: *"work through the pinboard"*.
4. The agent calls `feedback_list`, gets all four in one batch, and calls `feedback_acknowledge`
   with all four ids. In the tool window they move to **Acknowledged** and the progress bar moves.
5. For each item it reads your note and the code snapshot, makes the change, and calls
   `feedback_resolve` with a summary of what it actually did.
6. It then calls `feedback_watch` and waits there for whatever you pin next.
7. You read the summaries in the detail pane, next to the code you pinned. One of them is not what
   you meant, so you pin a follow-up - and because the agent is sitting in `feedback_watch`, it
   picks that one up on its own.
8. When you are satisfied, **Clear | Clear resolved** tidies the finished work away.

---

## Support this plugin

Pinboard is free and always will be. If it saves you time, you can
[buy me a coffee](https://buymeacoffee.com/trantruong.dev).

---

## Reference

- Source and issues: <https://github.com/trantruong-dev/pinboard>
- Support the plugin: <https://buymeacoffee.com/trantruong.dev>
- Changelog: [CHANGELOG.md](../CHANGELOG.md)
- Licence: Apache-2.0. Parts of the presentation and interaction design are adapted from
  [Marginalia](https://github.com/borgand/marginalia) (MIT)
