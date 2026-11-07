# mini-coding-agent

A file-management coding agent built with Spring Boot and Spring AI, backed by DeepSeek. It can
inspect, create, read, move, and delete files — but only inside a single configured workspace
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
| `agent.workspace` | The only directory the agent can touch. Every file path is resolved relative to it and rejected if it escapes. |
| `spring.ai.deepseek.api-key` | Supplied via the `DEEPSEEK_API_KEY` environment variable, or an untracked `application-local.properties`. |
| `agent.git.repo` | The repository the `:status`/`:diff`/`:check`/`:commit`/`:push` commands act on. Keep it the source tree; `agent.workspace` stays the model's sandbox. |
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

### Web UI

`index.html`, `app.js`, and `style.css` are static resources served at `/`. The page posts to
`/api/chat` and renders the streamed reply with a small built-in Markdown renderer (input is escaped
first, so model output cannot inject HTML). "New chat" resets the conversation id.

`POST /api/chat` takes `{"message": "...", "conversationId": "..."}` — omit `conversationId` on the
first turn. It responds with `text/event-stream`, where each event's data is a JSON object:

| Payload | Meaning |
| --- | --- |
| `{"cid":"..."}` | The conversation id, sent first so the client can reuse it. |
| `{"t":"..."}` | A content chunk. JSON-encoded because Spring's SSE encoder rewrites newlines inside values and drops whitespace-only chunks — which would collapse the reply into unspaced text. |
| `{"nl":n}` | A run of `n` line breaks. |

The web UI has no git commands: `:push` is terminal-only, so a browser session cannot reach a push.

Turn and tool records are appended to the activity log when the stream completes, errors, or is
cancelled.

## What the agent can do

The model has exactly **five tools**, all defined in `FileTools` and all scoped to the workspace
root. There is no additional capability beyond these: no shell, no code execution, no network
access, no web fetch, no git, and no way to read or write anything outside `agent.workspace`. Those
five tools are the complete tool surface.

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

- **Traversal** — `..` segments that resolve outside the root are rejected before any I/O.
- **Symlink escape** — the nearest existing ancestor of a path is resolved for real, so a symlinked
  directory inside the workspace cannot be used to reach outside it. Directories are re-checked
  after creation.
- **Size limits** — reads over 256 KB are refused; listings are capped in both depth and count.
- **No clobbering** — `moveFile` refuses an existing destination.

Failures are returned to the model as `Error: ...` strings rather than thrown, so the agent can
recover and explain itself instead of crashing the REPL.

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
| Verify runs **before** the commit in `:push` | A red build leaves the working tree as you left it, rather than half-committed. |
| `isVerified()` is invalidated by every commit and every `:commit` | A stale pass can never authorise a later push. |
| Explicit ref: `git push <remote> <branch>` | Never bare `git push`; never `--force` unless enabled, and then only `--force-with-lease`. |
| Branch allowlist | Pushing to an unexpected branch is refused before the network is touched. |
| `GIT_TERMINAL_PROMPT=0` | A missing credential fails fast instead of hanging the REPL on a username prompt. |
| Every command timed out | 120 s for git, 600 s for the verify command; captured output is capped at 8 KB. |

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
breaking a conversation.

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
```

## Tests

```bash
mvn clean test
```

- `FileToolsTest` runs against JUnit's `@TempDir` and covers path traversal (including absolute
  paths), symlink escapes, missing files, overwrite semantics, the read-size guard, listing depth
  and entry caps, and delete/move behavior. Symlink tests skip themselves on filesystems that do not
  support symbolic links.
- `GitToolsTest` builds a throwaway repository per test: it asserts a push is refused before any
  verification, refused when the verify command fails, refused for a branch outside the allowlist,
  and that a commit message containing `;` and `$(...)` stays literal (no file is created and the
  message is recorded verbatim). Tests skip when `git` is not installed.
- `ActivityLogTest` asserts one parseable JSON object per event, that newlines and quotes cannot break
  or forge a record, that fields are truncated, and that `tail` returns the most recent records.

## Notes

- The app starts an embedded HTTP server for the web UI. `server.address=127.0.0.1` is deliberate —
  binding to `0.0.0.0` would put a file-writing agent on your network.
- The system prompt in `MiniCodingAgentApplication` reinforces workspace discipline: list before
  assuming a file is missing, read before rewriting, reuse the same path when modifying, never
  create probe or placeholder files, and states that the model cannot run commands or use git.
- Conversation memory is in-process and unbounded in lifetime: restarting the app forgets every
  conversation, and the web UI's conversation id becomes meaningless.
- `agent.git.auto-push` is accepted but not wired to an automatic trigger; it is the documented
  extension point if you later want a push after every successful turn.
