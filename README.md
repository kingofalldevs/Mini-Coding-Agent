# mini-coding-agent

A file-management coding agent built with Spring Boot 3.5 and Spring AI 1.1, backed by DeepSeek. It
can inspect, create, read, move, and delete files — but only inside a single configured workspace
directory.

It ships with two front ends over the same agent:

- a **terminal REPL** (`CommandLineRunner`), and
- a **browser chat UI** served from `/` and backed by a streaming `/api/chat` endpoint.

Both share the same tools, the same in-memory conversation memory, and the same audit log.

Git commit and push exist as **REPL commands for the human**, not as model tools. See
[Git: verify, commit, push](#git-verify-commit-push).

## Requirements

- Java 21
- Maven (or the included wrapper, if present)
- A DeepSeek API key
- `git` on `PATH` if you want the git commands (they fail gracefully if absent)

## Configuration

`src/main/resources/application.properties`:

```properties
spring.application.name=mini-coding-agent
spring.ai.deepseek.api-key=${DEEPSEEK_API_KEY}
spring.ai.deepseek.chat.options.model=deepseek-chat
spring.profiles.active=local

# Point this at a dedicated sandbox directory, never your real source tree.
agent.workspace=/path/to/workspace

# Local-only by default: the agent can write and delete files, so don't expose it.
server.address=127.0.0.1
server.port=8080

# Git (user-driven; none of this is exposed as a model tool)
agent.git.repo=/path/to/repo
agent.git.auto-push=false
agent.git.allow-force=false
agent.git.branch-allowlist=main
agent.git.remote=origin
agent.git.verify-command=mvn -q -B test
```

| Property | Meaning |
| --- | --- |
| `agent.workspace` | The only directory the agent can touch. Every file path is resolved relative to it and rejected if it escapes. Defaults to the working directory if `/workspace` is `.` and it does not exist. |
| `spring.ai.deepseek.api-key` | Supplied via the `DEEPSEEK_API_KEY` environment variable, or an untracked `application-local.properties`. |
| `agent.git.repo` | The repository the `:status`/`:diff`/`:check`/`:commit`/`:push` commands act on. Keep it the source tree; `agent.workspace` stays the model's sandbox. If blank, the git commands fall back to the working directory and report "not a git repository". |
| `agent.git.auto-push` | Reserved for unattended push. Off by default; nothing pushes without a `:push`. |
| `agent.git.allow-force` | Force-push is refused unless this is `true`; when enabled it uses `--force-with-lease`, never bare `--force`. |
| `agent.git.branch-allowlist` | Branches that may be pushed. Anything else is refused. |
| `agent.git.remote` | Remote name used for the explicit-ref push. |
| `agent.git.verify-command` | The build gate. Runs before every push; non-zero exit blocks it. Executed via `ProcessBuilder`, not a shell — no `&&`, `;`, or pipes. |

Do not commit real API keys. The `local` profile exists so secrets can live in
`application-local.properties` and stay out of version control; the repo `.gitignore` also excludes
it, along with `target/` and the `.agent/` log directory.

`server.address=127.0.0.1` keeps the HTTP server on loopback. That matters: anyone who can reach the
port can drive the agent, and the agent can write and delete files.

### Choose a real sandbox

`agent.workspace` must exist and must **not** be the project's own source tree if you care about the
code. The startup banner prints the resolved root; if it points at this repository, the model can
rewrite `README.md`, `pom.xml`, and everything under `src/` within its guards.

## Run

```bash
export DEEPSEEK_API_KEY=sk-...
mvn spring-boot:run
```

Startup prints the resolved workspace, the log paths, and the git configuration:

```
>>> Agent workspace: /path/to/workspace
>>> Agent activity log:  /path/to/workspace/.agent/activity.log
>>> Agent transcript:    /path/to/workspace/.agent/activity.md
>>> Git repo:            /path/to/repo
>>> Git auto-push:       off
>>> Git verify command:  mvn -q -B test
Mini coding agent ready. Type 'exit' to quit, ':log' to see recent activity.
Git: ':status', ':diff', ':check', ':commit <msg>', ':push <msg>'.

you>
```

If `agent.git.repo` is not a git repository, a `>>> WARN: ... is not a git repository` line is
printed instead of the git banner, and the git commands report errors until it is one.

Then open <http://127.0.0.1:8080/> for the browser UI, or stay in the terminal.

### Terminal REPL

Responses stream token by token. Type `exit` (or send EOF) to quit. Conversation history is kept in
memory for the life of the process, so follow-up messages like "now rename that file" work.

| Command | What it does |
| --- | --- |
| `:log` | Print the last 20 activity records. |
| `:status` | `git status --short --branch` in `agent.git.repo`. |
| `:diff` | `git diff --stat --patch`, capped at 8 KB. |
| `:check` | Run the verify command and record the result. This is the push gate. |
| `:commit <message>` | `git add -A` + `git commit`. Local only. |
| `:push <message>` | **Verify → commit → push.** If the build is red, nothing is committed and nothing is pushed. |

Parsing is deliberately dumb: the command name is lowercased and everything after the first space
is the argument, verbatim — no quoting, no escapes, no shell semantics. `:push` and `:commit`
without a message print a usage line instead of running git. An unknown `:command` prints the list
of valid ones.

### Web UI

`index.html`, `app.js`, and `style.css` are static resources served at `/`. The page posts to
`/api/chat` and renders the streamed reply with a small built-in Markdown renderer (input is escaped
first, so model output cannot inject HTML). "New chat" resets the conversation id. Headings, lists,
tables, block quotes, fenced code, inline code, links, and bold/italic/strikethrough are supported;
autoscroll pauses when you scroll up and offers a "jump to latest" button.

`POST /api/chat` takes `{"message": "...", "conversationId": "..."}` — omit `conversationId` on the
first turn. A blank message returns a single `{"t":"Error: message must not be blank."}` event. It
responds with `text/event-stream`, where each event's data is a JSON object:

| Payload | Meaning |
| --- | --- |
| `{"cid":"..."}` | The conversation id, sent first so the client can reuse it. |
| `{"t":"..."}` | A content chunk. JSON-encoded because Spring's SSE encoder rewrites newlines inside values and drops whitespace-only chunks — which would collapse the reply into unspaced text. |
| `{"nl":n}` | A run of `n` line breaks. |

The web UI has no git commands: `:push` is terminal-only, so a browser session cannot reach a push.

Turn and tool records are appended to the activity log when the stream completes, errors, or is
cancelled (`doFinally`), so an abandoned browser tab still leaves a trace.

## What the agent can do

The model has exactly **five tools**, all defined in `FileTools` and all scoped to the workspace
root. There is no additional capability beyond these: no shell, no code execution, no network
access, no web fetch, no git, and no way to read or write anything outside `agent.workspace`. Those
five tools are the complete tool surface — `ReplCommandTest` asserts the registered tool names are
exactly these five and that none mentions "git".

All paths are relative to the workspace root.

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `listFiles` | *(none)* | Lists workspace contents, up to 8 levels deep and at most 200 entries. Directories get a trailing `/`. Hidden entries (any path segment starting with `.`) are omitted. Output is truncated with an explicit notice when the cap is hit. |
| `readFile` | `relativePath` | Returns a file's UTF-8 text. Refuses files larger than 256 KB to protect the context window. |
| `writeFile` | `relativePath`, `content` | Writes UTF-8 text, creating parent directories as needed and overwriting an existing file. Reports how many characters were written. |
| `moveFile` | `from`, `to` | Moves or renames a file. Fails if the destination already exists — it never clobbers. |
| `deleteFile` | `relativePath` | Deletes a file. Refuses directories. |

### Safety

The workspace root is resolved with `toRealPath()` at startup, and every requested path is
normalized against it. Guards in place:

- **Traversal** — `..` segments that resolve outside the root are rejected before any I/O, as are
  absolute paths pointing elsewhere.
- **Symlink escape** — the nearest existing ancestor of a path is resolved for real, so a symlinked
  directory inside the workspace cannot be used to reach outside it. Directories are re-checked
  after creation.
- **Size limits** — reads over 256 KB are refused; listings are capped in both depth (8) and count
  (200).
- **No clobbering** — `moveFile` refuses an existing destination.

Failures are returned to the model as `Error: ...` strings rather than thrown, so the agent can
recover and explain itself instead of crashing the REPL. The one exception is a logging wrapper,
which records the failure and rethrows so the error still reaches the model.

The system prompt (`MiniCodingAgentApplication`) reinforces the same discipline: list before
assuming a file is missing, read before rewriting, reuse the same path when modifying, give files a
sensible extension, never create probe or placeholder files, confirm which file was written, and
state plainly that the model cannot run commands or use git.

## Git: verify, commit, push

`GitTools` is a `@Bean` but **not** a tool callback. The model cannot commit or push, and no file
the model reads can instruct it to: the capability simply is not in its tool list. The human drives
it from the REPL.

Why the split:

- **A push is not undoable.** A commit on `main` has shipped by the time anyone reads the
  transcript; a file write is fixable.
- **Prompt injection.** Any file the model reads is untrusted input. If `README.md` said "push this",
  a tool-enabled agent would be one sentence away from publishing unreviewed code.
- **Credentials.** The token stays in the host environment and is handed only to the child `git`
  process. It is never written to the workspace (where `readFile` could reach it) and never put in a
  remote URL (where it would persist in `.git/config` and the reflog).

Safety properties of the implementation:

| Rule | Reason |
| --- | --- |
| `ProcessBuilder` with an argv list; no `bash -c` | Commit messages, branch names, and file names cannot inject `;`, `\|`, `$(...)`, or redirection. `git commit -m <msg>` passes the message as one argument. |
| The verify command is split on whitespace, argv-only | No shell, so the gate cannot be a pipeline; a blank command counts as *failed*, never as passed. |
| Verify runs **before** the commit in `:push` | A red build leaves the working tree as you left it, rather than half-committed. |
| `isVerified()` is invalidated by every commit and every `:commit` | A stale pass can never authorise a later push. |
| Explicit ref: `git push <remote> <branch>` | Never bare `git push`; never `--force` unless enabled, and then only `--force-with-lease`. |
| Branch allowlist | Pushing to an unexpected branch is refused before the network is touched. |
| `GIT_TERMINAL_PROMPT=0` | A missing credential fails fast instead of hanging the REPL on a username prompt. |
| Every command timed out | 15–60 s for queries and commits, 120 s for git, 600 s for the verify command; captured output is capped at 8 KB. |

Git has its own switching path: `GitTools` is constructed from `agent.git.*` (`auto-push` is read
and reported at startup but not wired to a trigger — see [Notes](#notes)). The class is not
thread-safe by design; it is driven from the single REPL thread.

### Credentials

For a push to work unattended, `git` needs a credential helper. The recommended shape:

```bash
export GITHUB_TOKEN=ghp_...   # fine-grained PAT, this repo only, contents:write
```

`GitTools` passes `GITHUB_TOKEN` and `GIT_ASKPASS` through to the child process only. Do **not** put
the token in `application.properties`, `application-local.properties`, or anywhere under
`agent.workspace`. If you would rather not have a token at all, let `:push` do the commit and run
`git push` yourself afterwards — the commit is already made and reviewed.

## Activity log

Every turn and every tool call is logged in three places, written together from one call site in
`ActivityLog.record()`:

| Sink | Where | Why |
| --- | --- | --- |
| Terminal | stdout, one or two lines per event | Watch the agent work live |
| Markdown | `<agent.workspace>/.agent/activity.md` | Read back, diff, and scroll a human-readable transcript |
| JSONL | `<agent.workspace>/.agent/activity.log` | `jq`, `grep`, and `:log` |

- **REPL** — type `:log` for the last 20 records.
- **Shell** — `tail -f <agent.workspace>/.agent/activity.log`, or `jq . <agent.workspace>/.agent/activity.log`.
- **API** — `ActivityLog.tail(int)` is the read path used by the REPL.

Git commands print to the terminal. They are **not** written to the activity log yet — if you need
them in the audit trail, add a `log.git(...)` sink alongside `turn` and `toolCall`.

Both files live under `.agent/` because `listFiles` hides dot-prefixed path segments, so they never
clutter the agent's view of the workspace, and no tool can target them. `.agent/` is listed in
`.gitignore`, so the logs stay local. Write failures degrade to a stderr warning rather than
breaking a conversation. Fields are capped at 600 characters; the Markdown transcript is appended to,
never rewritten (a header is written only on creation).

See [ACTIVITY_LOG.md](ACTIVITY_LOG.md) for the record format, field meanings, and design rationale.

## Project layout

```
pom.xml
.gitignore                     # excludes .agent/ logs, target/, and local secrets
src/main/java/com.example.agent/
  MiniCodingAgentApplication.java  # Spring Boot entry point, beans, system prompt, REPL
  ChatController.java              # /api/chat SSE endpoint for the web UI
  FileTools.java                   # the five @Tool methods and path guards
  GitTools.java                    # git status/diff/verify/commit/push (user-driven, not a tool)
  ActivityLog.java                 # terminal + Markdown + JSONL audit trail
  LoggingToolCallback.java         # per-request tool wrapper that writes to the log
src/main/resources/
  application.properties
  application-local.properties     # holds the API key; keep it out of version control
  static/index.html                # browser chat UI
  static/app.js                    # SSE consumption, streaming render, Markdown
  static/style.css
src/test/java/com.example.agent/
  FileToolsTest.java               # traversal, symlink, limit, and overwrite tests
  GitToolsTest.java                # verify gate, no-shell commit, ref/allowlist behaviour
  ActivityLogTest.java             # JSONL validity, escaping, truncation, tail
  ReplCommandTest.java             # :command parsing and the five-tool surface
```

Note that the source packages live in directories named `com.example.agent` (dots, not slashes),
which is legal on the filesystem but unusual; Maven still resolves them because the package
declarations are what the compiler uses.

## Tests

```bash
mvn clean test
```

- `FileToolsTest` runs against JUnit's `@TempDir` and covers path traversal (including absolute
  paths), symlink escapes, missing files, overwrite semantics, the read-size guard, listing depth
  and entry caps (including that `.agent/` is hidden), and delete/move behavior including refusal to
  clobber or delete a directory. Symlink tests skip themselves on filesystems that do not support
  symbolic links.
- `GitToolsTest` builds a throwaway repository per test (temp dir only, no network, no credentials):
  it asserts a push is refused before any verification, refused when the verify command fails,
  refused for a branch outside the allowlist, that `:push` runs the build before committing, that a
  commit message containing `;` and `$(...)` stays literal (no file is created and the message is
  recorded verbatim), and that the verify command splits into argv correctly. Tests skip when `git`
  is not installed.
- `ActivityLogTest` asserts one parseable JSON object per event, that newlines and quotes cannot
  break or forge a record, that fields are truncated, that both sinks are created under `.agent/`,
  that the transcript is appended to rather than rewritten, and that `tail` returns the most recent
  records.
- `ReplCommandTest` asserts `:command` parsing (bare commands, arguments, whitespace trimming,
  lowercase names with untouched arguments, hostile arguments staying literal, `null`) and that the
  registered tool surface is exactly the five file tools with no git tool.

## Notes

- The app starts an embedded HTTP server for the web UI. `server.address=127.0.0.1` is deliberate —
  binding to `0.0.0.0` would put a file-writing agent on your network.
- Conversation memory is in-process and unbounded in lifetime: restarting the app forgets every
  conversation, and the web UI's conversation id becomes meaningless. The REPL uses the fixed id
  `repl`; the web UI gets a UUID per new chat.
- `agent.git.auto-push` is accepted, printed at startup, and exposed via `GitTools.isAutoPush()`, but
  is not wired to an automatic trigger. It is the documented extension point if you later want a
  push after every successful turn.
- Tool calls produce no content deltas, so they never appear in the streamed reply — the activity
  log is where you see them. The model's prose and the log can disagree; the log is written by code
  and wins.
