# Consilium

An IntelliJ IDEA plugin that puts one person, Claude Code, and OpenAI Codex in the same chat.

Working with two coding agents usually means two terminals and copying answers between them. Consilium keeps one shared transcript in a tool window: both agents answer the same message independently, and on the next turn each sees what the other said. You just write: ask a question and both discuss; name one of them and that one does the work; let them agree on a split, say "go ahead", and each does its part in parallel in its own copy of the repository, after which Claude merges both.

![Both agents answer the same question in the Consilium tool window](assets/chat.png)

*Both agents answer one question independently; on the next turn each sees what the other said.*

**Authorship:** direction, design decisions, and review: Katherine Mironova. Implementation used Claude Code and OpenAI Codex under her direction.

## How it works

By default (**Auto**) you don't pick anything: Consilium reads what your message asks for.

| You write | What happens |
| --- | --- |
| a question, an opinion, a plan: *"what do you think about caching?"* | both agents answer, read-only |
| to one agent: *"Codex, what do you say?"* | only that agent answers, read-only |
| a task for one agent: *"Claude, fix the parser"* | that agent does it in the project |
| a go-ahead to both after they agreed on a split: *"go ahead"*, *"делайте"* | each does its part in parallel in its own git worktree and branch; Consilium commits each part, Claude merges both branches into the project with proper commit messages, and the copies are removed |
| *"merge"*, *"Codex, merge what's there"* | merges the parts left on their branches |
| something that could go either way: *"ok"* | Consilium asks whether to start, and nothing runs |

Only your own message decides whether code may change. Consilium reads it with a separate Claude call that has no tools and sees nothing but your message, so an agent's answer, or a file an agent read, can't start work on its own. That call takes about two seconds before the agents start. If it fails, the message is treated as a question: both agents answer read-only. Text you paste is part of your message, so a pasted request ("please fix X") can read as a task. A task with no name on it (*"fix the parser"*) goes to Claude alone; both agents work only when the message is plainly for both.

If you prefer to choose yourself, **Mode** still has the explicit modes:

| Mode | Who runs | Access |
| --- | --- | --- |
| Both discuss | Claude and Codex, in parallel | read-only |
| Claude answers / Codex answers | the selected agent | read-only |
| Claude does / Codex does | the selected agent only | can edit the repository and run checks |

The coordination rule: **two agents never write to the same files at once.** In a "does" turn the other agent is not started. When both work in parallel:

- Each has its own git worktree and branch outside the project, starting from the last commit. Uncommitted changes aren't in the copies, and Consilium says so.
- The agents don't commit; Consilium commits each part on its branch when the agent is done. An agent in a copy gets no write access to the repository's `.git`, so a sandboxed Codex can't plant a git hook or config that would run outside its sandbox.
- Only the merging agent touches the project, and only when the project has no uncommitted changes of yours; otherwise Consilium waits for you to commit or stash them and say "merge".
- If a part fails, is stopped, or changes nothing, nothing is merged and the work stays on its branch until you say "merge". **Clear** keeps that work too and says where it is. A new parallel task starts only after earlier parts are merged.
- Parallel work needs a git repository with at least one commit.

How read-only is enforced:

- **Codex** runs with the `read-only` sandbox and `approval_policy="never"`, also when resuming a session. Without the second setting, an auto-approving reviewer in the user's Codex config could approve a write after the sandbox denied it. Every MCP server from your Codex configuration (user or project) is switched off for the turn, because MCP servers run outside the sandbox: Consilium lists them with `codex mcp list --json` first, and if that fails, or a server's name can't be switched off from the command line (only letters, digits, `_` and `-` can), the turn doesn't start and the card says why.
- **Claude Code** gets only the `Read`, `Glob` and `Grep` built-in tools and runs with `--restricted`, `--strict-mcp-config` and `--permission-prompts none`: no MCP servers, no settings files from the repository (so a cloned repository's hooks can't run), and no tool call that would need approval. From your own `~/.claude/settings.json` only the model, effort, `env` and sign-in helpers (`apiKeyHelper`, AWS helpers) are passed back, through a temporary file readable only by you; hooks, permissions and plugins are not.

Other details:

- Answers stream as the CLI produces them. **Stop** ends the turn and every process still attached to it (MCP servers, test runs, background commands); the partial answer stays in the transcript, marked as cut off. A new turn starts only after every process from the previous one has exited.
- In discussion modes each agent resumes its own CLI session and receives only the messages it has not seen yet. A "does" turn always starts a fresh session with the full transcript: a clean task context, with permissions set explicitly rather than inherited. After it, that agent's next discussion turn starts fresh too, so it sees its own report of what it changed.
- Each message reaches the agents as a tagged block, and only your blocks count as instructions. The agents are told to treat each other's text as quoted opinion, so an agent that read a hostile file can't simply pose as you; like any prompt-level defense, this lowers the risk rather than ruling it out.
- An agent that fails shows its error in red; errors are never passed to the other agent as something said.
- Optional editor context attaches the open file's path and the current selection.
- The transcript is stored per project, locally.

Consilium does not talk to any API itself. It runs the official `claude` and `codex` CLIs that are already installed and signed in on your machine, so each agent uses your account and, in "does" turns, its own settings, skills, and MCP servers. The plugin keeps no credentials of its own. For Claude's discussion turns it reads the model, `env` and sign-in keys of your `~/.claude/settings.json` (see above), which may include an API key, and hands them back to the CLI through a temporary file readable only by you, deleted when the turn ends.

The interface follows the IDE language: English by default, Russian when the IDE runs with a Russian locale. Agents are asked to reply in the language you write in.

## Requirements

- IntelliJ IDEA 2026.1 or newer (build 261+)
- `claude` and `codex` CLIs, installed and authenticated. The plugin looks in `~/.local/bin` first, then in your login shell's `PATH`, so CLIs from Homebrew or npm are found even when the IDE was started from the Dock. A CLI found inside the project folder is never run.
- Developed and tested on macOS. Other platforms may work but have not been tried.

## Install

Download the ZIP from [Releases](../../releases), then in IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the ZIP, restart, and open the **Consilium** tool window on the right.

## Usage

1. Open the **Consilium** tool window on the right side of the IDE.
2. Type a message and press **Enter**. **Shift+Enter** starts a new line; **⌘Enter** (Ctrl+Enter) also sends.
3. Each agent's answer streams into its own card. On the next turn, each agent also gets what the other one said, so you can ask them to respond to each other: *"Each of you, reply to the other's argument in two lines."*
4. When it's time to change code, say so in words: *"Claude, do it"* for one agent, or, once they've agreed who does what, *"go ahead"* for both. Consilium's own short notes in the transcript say what it started and where the work is.

Controls:

- **editor context** — when checked, the message carries the path of the file open in the editor and the selected text, if any. Uncheck it to send the message alone.
- **Stop** — cancels the current turn. What the agent managed to write stays in the transcript, marked as cut off.
- **Clear** — empties the transcript and forgets both agents' CLI sessions, so the next message starts from scratch. It can't be undone. Unmerged parallel work isn't touched: the new transcript says which branches still hold it.

The agents work in the project's root folder, so ask about the repository directly: *"Look at the scanner and tell me how it skips video files."*

## Troubleshooting

- **"Couldn't start … CLI".** The executable wasn't found in `~/.local/bin` or in your login shell's `PATH`. Check that `which claude` / `which codex` prints a path in a new terminal window, or link the CLI into `~/.local/bin` (for example `ln -s "$(which codex)" ~/.local/bin/codex`), then send the message again.
- **"… CLI exited with code …" or "… ended the turn with an error".** The message shows what the CLI reported. Most often it isn't signed in (run `claude` and then `/login`, or `codex login`) or a CLI update changed its flags; in that case the error points to the code that builds the command.
- **An agent didn't change anything.** In **Auto**, a question or an unclear message is read as discussion, which is read-only by design: say plainly who should do it (*"Claude, do it"*, or *"go ahead"* for both). In the explicit modes only **… does** can edit the project.
- **"Working in parallel needs a git repository".** Each agent works on its own branch, so the project has to be a git repository with at least one commit. Without git, ask one agent to do the whole task.
- **"Open a project folder to use Consilium".** The tool window was opened without a project folder; agents always work inside one.

## Security and privacy

- Messages, the transcript the agents need, and the editor context (file path and selection) go to Anthropic and OpenAI through their CLIs, exactly as if you typed them there. In **Auto**, each message also goes once more to Claude, alone, to read what it asks for. Uncheck **editor context** to send the message alone.
- The transcript is stored in plain text in the project's `.idea/workspace.xml`, which IDEA's default `.idea/.gitignore` keeps out of git. **Clear** removes it there; the CLIs keep their own session logs in `~/.claude` and `~/.codex`.
- Answers are shown with raw HTML removed: nothing in an answer can load an image or any other URL, and only `http`, `https` and `mailto` links open, on click.
- A **does** turn runs the agent in its own auto-approval mode inside the project: it can edit files and run commands. Use it in repositories you trust.

## Build from source

```bash
./gradlew test buildPlugin
```

The plugin ZIP lands in `build/distributions/`. The build compiles against the IDEA installation at `ideaPath` in `gradle.properties` (pass `-PideaPath=…` if yours is elsewhere); without one, it downloads IntelliJ IDEA `platformVersion`. Gradle needs a JDK 17+ to start and fetches the JDK 25 toolchain itself if it isn't installed; IDEA's bundled runtime works too: `JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"`.

## Status

MVP, version 0.3.2. Open questions and deliberate decisions are tracked in [BACKLOG.md](BACKLOG.md).

The CLI flags the plugin passes are pinned in `CliCommandTest`, with the CLI versions they were last checked against (Claude Code 2.1.322, codex-cli 0.167.0). If a CLI upgrade makes an agent fail at launch, the error message points there. The stored transcript keeps the latest 300 messages.

## License

[MIT](LICENSE)
