# file-agent

A terminal REPL agent built with Spring Boot and Spring AI, backed by DeepSeek. It can inspect,
create, read, move, and delete files — but only inside a single configured workspace directory.

## Requirements

- Java 21
- Maven (or the included wrapper, if present)
- A DeepSeek API key

## Configuration

`src/main/resources/application.properties`:

```properties
spring.application.name=file-agent
spring.ai.deepseek.api-key=${DEEPSEEK_API_KEY}
spring.ai.deepseek.chat.options.model=deepseek-chat
spring.profiles.active=local

agent.workspace=/path/to/workspace
spring.main.web-application-type=none
```

Two things to set before running:

| Property | Meaning |
| --- | --- |
| `agent.workspace` | The only directory the agent can touch. Every file path is resolved relative to it and rejected if it escapes. |
| `spring.ai.deepseek.api-key` | Supplied via the `DEEPSEEK_API_KEY` environment variable, or an untracked `application-local.properties`. |

Do not commit real API keys. The `local` profile exists so secrets can live in
`application-local.properties` and stay out of version control.

## Run

```bash
export DEEPSEEK_API_KEY=sk-...
mvn spring-boot:run
```

Startup prints the resolved workspace:

```
>>> Agent workspace: /path/to/workspace
File agent ready. Type 'exit' to quit.

you> 
```

Responses stream token by token. Type `exit` (or send EOF) to quit. Conversation history is kept
in memory for the life of the process, so follow-up messages like "now rename that file" work.

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

## Project layout

```
pom.xml
src/main/java/com.example.agent/
  FileAgentApplication.java    # Spring Boot entry point, ChatClient + REPL
  FileTools.java               # the five @Tool methods and path guards
src/main/resources/
  application.properties
  application-local.properties # holds the API key; keep it out of version control
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

- `spring.main.web-application-type=none` keeps this a plain console app — no HTTP server starts.
- The system prompt in `FileAgentApplication` reinforces workspace discipline: list before assuming
  a file is missing, read before rewriting, and never create probe or placeholder files.
