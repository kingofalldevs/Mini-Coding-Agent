# Agent activity log

The app writes what the agent does, in two places at once, from one call site:

```
<agent.workspace>/.agent/activity.log   # JSONL, for jq/grep and ActivityLog.tail()
<agent.workspace>/.agent/activity.md    # Markdown transcript, for humans
```

It also prints every record to **stdout**, one or two lines per event, so you can
watch it in the terminal while the agent runs.

Both files are git-ignored via the `.agent/` entry in `.gitignore`. They are
runtime artifacts, not source.

With the current `agent.workspace` that resolves to
`/Users/apple/IdeaProjects/Boot4Boot/.agent/`.

## Format

### Terminal

```
2025-01-01T12:00:00.123Z  TURN   [repl] 4310 ms  ok=true
    create whoami.md
2025-01-01T12:00:01.456Z  TOOL   listFiles  ok=true
    args: {}
    out:  "README.md", "notes/"
2025-01-01T12:00:02.789Z  TOOL   writeFile  ok=true
    args: {"relativePath":"whoami.md","content":"..."}
    out:  Wrote 812 characters to whoami.md
```

### `activity.log` (JSONL)

One JSON object per line, so `jq` and `grep` both work:

```json
{"ts":"2025-01-01T12:00:00.123Z","kind":"turn","conversation":"repl","message":"create whoami.md","elapsedMs":"4310","ok":"true"}
{"ts":"2025-01-01T12:00:01.456Z","kind":"tool","conversation":"repl","tool":"listFiles","args":"{}","summary":"\"README.md\", \"notes/\"","ok":"true"}
```

| Field | Meaning |
| --- | --- |
| `ts` | ISO-8601 instant, UTC |
| `kind` | `turn` (one user request) or `tool` (one tool invocation) |
| `conversation` | Conversation id — `repl` for the terminal, the UUID for the web UI |
| `tool`, `args`, `summary` | Tool name, its arguments, and a 600-char digest of the result |
| `ok` | `false` when the tool returned an `Error:` or the request threw |

`args` and `summary` are capped at 600 characters so a `readFile` of a large file
does not bloat the log.

### `activity.md` (transcript)

One `##` section per event, key/value bullets, appended in order. Same fields,
rendered for reading rather than parsing.

## Reading it

- In the REPL: type `:log` for the last 20 records.
- From a shell: `tail -f <agent.workspace>/.agent/activity.log`
- Pretty print: `jq . <agent.workspace>/.agent/activity.log`
- Human view: open `<agent.workspace>/.agent/activity.md`

## Design notes

- **One call site, two sinks.** `ActivityLog.record()` writes the terminal line,
  the Markdown section, and the JSON line together, so the three views cannot
  drift apart.
- **Separate from the agent's notes.** If you also want the agent to write prose
  notes, keep them in a different file. This log is code-written and authoritative;
  a confused model must not be able to rewrite its own audit trail — and since no
  tool takes a path under `.agent/`, it cannot even try.
- **Hidden and ignored.** `listFiles` skips any path segment starting with a dot,
  so `.agent/` never clutters the agent's view of the workspace; `.gitignore` keeps
  it out of commits.
- **Logging never breaks a run.** Write failures are swallowed with a warning on
  stderr. A full disk degrades observability, not the conversation.
- **Turn logging uses `doFinally`** in the web controller, so a cancelled or failed
  stream is still recorded, including the completion signal.
- **Tools are attached per request.** `LoggingToolCallback` wraps each callback
  with the conversation id in hand, so no logging happens inside `FileTools` and
  every current and future tool is covered automatically.

## Caveat

The log records what the *tools* did, not what the model *said* about it. If the
model claims it wrote a file but no `tool` record exists, the claim is false —
which is exactly the case this log exists to catch.
