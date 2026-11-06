# Agent activity log

The app now writes an append-only record of what the agent does, to:

```
<agent.workspace>/.agent/activity.log
```

With the current `agent.workspace` that resolves to
`/Users/apple/IdeaProjects/Boot4Boot/.agent/activity.log`.

## Format

One JSON object per line (JSONL), so `jq` and `grep` both work:

```json
{"ts":"2025-01-01T12:00:00.123Z","kind":"turn","conversation":"repl","message":"create whoami.md","elapsedMs":4310,"ok":"true"}
{"ts":"2025-01-01T12:00:01.456Z","kind":"tool","conversation":"repl","tool":"listFiles","args":"{}","summary":"\"README.md\", \"notes/\"","ok":"true"}
{"ts":"2025-01-01T12:00:02.789Z","kind":"tool","conversation":"repl","tool":"writeFile","args":"{\"relativePath\":\"whoami.md\",\"content\":\"...\"}","summary":"Wrote 812 characters to whoami.md","ok":"true"}
```

| Field | Meaning |
| --- | --- |
| `ts` | ISO-8601 instant, UTC |
| `kind` | `turn` (one user request) or `tool` (one tool invocation) |
| `conversation` | Conversation id — `repl` for the terminal, the UUID for the web UI |
| `tool`, `args`, `summary` | Tool name, its arguments, and a 600-char digest of the result |
| `ok` | `false` when the tool returned an `Error:` or the request threw |

`summary` is capped at 600 characters so a `readFile` of a large file does not
bloat the log.

## Reading it

- In the REPL: type `:log` for the last 20 records.
- From a shell: `tail -f <agent.workspace>/.agent/activity.log`
- Pretty print: `jq . <agent.workspace>/.agent/activity.log`

## Design notes

- **Separate from the agent's notes.** If you also want the agent to write prose
  notes, keep them in a different file. This log is code-written and authoritative;
  a confused model must not be able to rewrite its own audit trail.
- **Hidden location.** `listFiles` skips any path segment starting with a dot, so
  `.agent/` never clutters the agent's view of the workspace, and no tool targets
  it inadvertently.
- **Logging never breaks a run.** Write failures are swallowed with a warning on
  stderr. A full disk degrades observability, not the conversation.
- **Turn logging uses `doFinally`**, so a cancelled or failed stream is still
  recorded, including the completion signal.
- **Tools are attached per request.** `LoggingToolCallback` wraps each callback
  with the conversation id in hand, so no logging happens inside `FileTools` and
  every current and future tool is covered automatically.

## Caveat

The log records what the *tools* did, not what the model *said* about it. If the
model claims it wrote a file but no `tool` record exists, the claim is false —
which is exactly the case this log exists to catch.
