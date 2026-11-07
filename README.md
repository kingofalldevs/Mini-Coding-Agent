# mini-coding-agent

A file-management coding agent built with Spring Boot and Spring AI, backed by DeepSeek. It can
inspect, create, read, move, and delete files — but only inside a single configured workspace
directory.

It ships with two front ends over the same agent:

- a **terminal REPL** (`CommandLineRunner`), and
- a **browser chat UI** served from `/` and backed by a streaming `/api/chat` endpoint.

Both share the same tools, the same in-memory conversation memory, and the same audit log.

## Requirements

- Java 21
- Maven (or the included wrapper, if present)
- A DeepSeek API key

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
```

Two things to set before running:

| Property | Meaning |
| --- | --- |
| `agent.workspace` | The only directory the agent can touch. Every file path is resolved relative to it and rejected if it escapes. |
| `spring.ai.deepseek.api-key` | Supplied via the `DEEPSEEK_API_KEY` environment variable, or an untracked `application-local.properties`. |

Do not commit real API keys. The `local` profile exists so secrets can live in
`application-local.properties` and stay out of version control.

`server.address=127.0.0.1` keeps the HTTP server on loopback. That matters: anyone who can reach the
port can drive the agent, and the agent can write and delete files.

## Run

```bash
export DEEPSEEK_API_KEY=sk-...
mvn spring-boot:run
```

Startup prints the resolved workspace and the activity-log path:

```
>>> Agent workspace: /path/to/workspace
>>> Agent activity log: /path/to/workspace/.agent/activity.log
Mini coding agent ready. Type 'exit' to quit, ':log' to see recent activity.

you>
```

Then open <http://127.0.0.1:8080/> for the browser UI, or stay in the terminal.

### Terminal REPL

Responses stream token by token. Type `exit` (or send EOF) to quit. `:log` prints the last 20
activity records. Conversation history is kept in memory for the life of the process, so follow-up
messages like "now rename that file" work.

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

Turn and tool records are appended to the activity log when the stream completes, errors, or is
cancelled.

## What the agent can do

The model has exactly **five tools**, all defined in `FileTools` and all scoped to the workspace
root. There is no additional capability beyond these: no shell, no code execution, no network
access, no web fetch, and no way to read or write anything outside `agent.workspace`. Those five
tools are the complete tool surface.

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

## Activity log

Every turn and every tool call is appended to a JSONL audit trail:

```
<agent.workspace>/.agent/activity.log
```

- **REPL** — type `:log` for the last 20 records.
- **Shell** — `tail -f <agent.workspace>/.agent/activity.log`, or `jq . <agent.workspace>/.agent/activity.log`.
- **API** — `ActivityLog.tail(int)` is the read path used by the REPL.

The log lives under `.agent/` because `listFiles` hides dot-prefixed path segments, so it never
clutters the agent's view of the workspace. Write failures degrade to a stderr warning rather than
breaking a conversation.

See [ACTIVITY_LOG.md](ACTIVITY_LOG.md) for the record format, field meanings, and design rationale.

## Project layout

```
pom.xml
src/main/java/com.example.agent/
  FileAgentApplication.java    # Spring Boot entry point, beans, system prompt, REPL
  ChatController.java          # /api/chat SSE endpoint for the web UI
  FileTools.java               # the five @Tool methods and path guards
  ActivityLog.java             # append-only JSONL audit trail
  LoggingToolCallback.java     # per-request tool wrapper that writes to the log
src/main/resources/
  application.properties
  application-local.properties # holds the API key; keep it out of version control
  static/index.html            # browser chat UI
  static/app.js                # SSE consumption, streaming render, Markdown
  static/style.css
src/test/java/com.example.agent/
  FileToolsTest.java           # traversal, symlink, limit, and overwrite tests
```

## Tests

```bash
mvn test
```

`FileToolsTest` runs against JUnit's `@TempDir` and covers path traversal (including absolute
paths), symlink escapes, missing files, overwrite semantics, the read-size guard, listing depth and
entry caps, and delete/move behavior. Symlink tests skip themselves on filesystems that do not
support symbolic links.

## Notes

- The app starts an embedded HTTP server for the web UI. `server.address=127.0.0.1` is deliberate —
  binding to `0.0.0.0` would put a file-writing agent on your network.
- The system prompt in `FileAgentApplication` reinforces workspace discipline: list before assuming
  a file is missing, read before rewriting, reuse the same path when modifying, and never create
  probe or placeholder files.
- Conversation memory is in-process and unbounded in lifetime: restarting the app forgets every
  conversation, and the web UI's conversation id becomes meaningless.
