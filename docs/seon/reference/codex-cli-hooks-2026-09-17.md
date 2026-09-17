# Codex CLI hooks — verified reference (2026-09-17)

Scope: what `bin/seon-hook` can rely on when the caller is an OpenAI Codex
CLI lane (`bin/codex-agent` → `codex exec`), versus when the caller is Claude
Code. Every row carries its citation and whether it was **verified live** on
this machine today. The companion document for the other caller is
[claude-code-hooks-2026-09-17.md](claude-code-hooks-2026-09-17.md).

Live probes ran in a scratchpad checkout with their own `.codex/hooks.json`
and a hook script that appended its raw stdin to a file. Raw payloads quoted
below are verbatim from those runs (session `01a0ad32-ef5a-70a0-b15c-b8bf14e09b00`
and later).

## 0. THE BLOCKER, FIRST: our lanes are running with hooks OFF

`codex exec` refuses to run a project hook whose persisted trust entry does
not match, and it does so **silently** — no warning on stdout, no line in
`logs/hook-debug.log`, nothing the lane or the orchestrator can see.

- `codex exec --help`: "`--dangerously-bypass-hook-trust`  Run enabled hooks
  without requiring persisted hook trust for this invocation. DANGEROUS.
  Intended only for automation that already vets hook sources"
- `~/.codex/config.toml` records trust per hook entry:
  ```toml
  [hooks.state."/Users/sean/src/seon/.codex/hooks.json:pre_tool_use:0:0"]
  trusted_hash = "sha256:903f77120b8b8ad4154244bb0e37b52d5181ea12ab8f518fa4d50fa28ed1b036"
  ```
- Public docs: "Codex records trust by hook hash; modified or new hooks need
  re-trust." (<https://learn.chatgpt.com/docs/hooks>, §Trust Model)

**Verified live, A/B, same cwd, same env, same prompt shape, 2026-09-17:**

| run | flags | hook fired? |
|---|---|---|
| A | `--dangerously-bypass-approvals-and-sandbox` | NO line in `logs/hook-debug.log`; the broken file landed unchecked |
| B | A's flags **plus** `--dangerously-bypass-hook-trust` | `02:31:40.471229Z \| PreToolUse \| tool=apply_patch \| session=01a0ad34-7371-7d92-8b75-154508590405 \| files=/Users/sean/src/seon/tmp/hook-probe-docs/b.clj`, and the patch was blocked |

That the repo's hooks *used* to fire from `codex exec` is on record —
session `01a0acbc-ebbb-7391-950a-f8559fc01d42`, whose rollout header reads
`"originator": "codex_exec", "source": "exec"`, produced
`2026-09-17T02:16:47.023136Z | PreToolUse | tool=apply_patch | session=01a0acbc-…`.
The last such line is `02:16:47`; `.codex/hooks.json` was rewritten at
19:34 local (`-rw-r--r--@ 1 sean staff 407 Sep 16 19:34 .codex/hooks.json`)
and no codex session has fired a hook since. Six lanes were running at
20:40 local with no hook coverage at all.

Two fixes, either sufficient: add `--dangerously-bypass-hook-trust` to the
`codex exec` and `codex exec resume` invocations in `bin/codex-agent`
(`bin/codex-agent:395`, `:407`), or re-trust the hook interactively after
every `.codex/hooks.json` edit. The flag is the only one that survives an
edit; anything else reintroduces "the check is silent when its subject is
absent", which is this project's named recurring failure class.

## 1. Installed versions

| thing | value | source |
|---|---|---|
| CLI our lanes run | `codex-cli 0.153.4` | `codex --version`; `which -a codex` → `/opt/homebrew/bin/codex` → `@openai/codex/bin/codex.js` |
| CLI in the ChatGPT app bundle | `codex-cli 0.154.0-alpha.6.2` | `/Applications/ChatGPT.app/Contents/Resources/codex --version` |
| lane invocation | `codex exec -m "$model" -c model_reasoning_effort="$effort" --dangerously-bypass-approvals-and-sandbox -o "$state/summary.out" -` | `bin/codex-agent:407` |
| resume invocation | `codex exec resume -m "$model" -c model_reasoning_effort="$effort" -o … "$sid" --dangerously-bypass-approvals-and-sandbox -` | `bin/codex-agent:395` |

`bin/codex-agent` passes **no** `-c features.…` flag: `grep -n "code_mode\|features\." bin/codex-agent` returns nothing. The
`Script completed / Wall time / Output: {json}` shape lanes see around shell
results is the code-mode host's presentation, not a different tool, and it
does not change the hook's `tool_name` (§3).

## 2. Hook events

Codex fires: `PreToolUse`, `PermissionRequest`, `PostToolUse`, `PreCompact`,
`PostCompact`, `SessionStart`, `SessionEnd`, `UserPromptSubmit`,
`SubagentStart`, `SubagentStop`, `Stop`, `Interrupt`.

- Docs: "SessionStart … SessionEnd … PreToolUse … PostToolUse …
  PermissionRequest … PreCompact … PostCompact … UserPromptSubmit …
  SubagentStart … SubagentStop … Stop … Interrupt"
  (<https://learn.chatgpt.com/docs/hooks>, §Hook Event Names)
- The same enum is in the shipped binary:
  `strings /Applications/ChatGPT.app/Contents/Resources/codex | grep PreToolUse`
  yields `…HookEventsTomlPreToolUsePermissionRequestPostToolUsePreCompactPostCompactSessionStartSessionEndUserPromptSubmitSubagentStartSubagentStopStopInterrupt…`

**Verified live:** `SessionStart`, `UserPromptSubmit`, `PreToolUse`,
`PostToolUse`, `Stop`, `SessionEnd` all delivered payloads in one scratchpad
run. `PermissionRequest`, `PreCompact`, `PostCompact`, `SubagentStart`,
`SubagentStop`, `Interrupt` were not exercised.

## 3. Exact stdin payloads (verbatim, live)

Every event carries `session_id`, `hook_event_name`, `cwd`,
`transcript_path`; turn-scoped events add `turn_id`; most add `model` and
`permission_mode`.

`session_id` is a **UUIDv7-shaped string**, e.g.
`"01a0ad32-ef5a-70a0-b15c-b8bf14e09b00"` — which is why our log shows
`session=01a0ac64-…`. It is NOT the Claude Code session UUID shape
(`058357e2-f84e-4654-a8f7-93e0e105119e` in the same log is this Claude
session).

SessionStart:
```json
{"session_id":"01a0ad32-ef5a-70a0-b15c-b8bf14e09b00",
 "transcript_path":"/Users/sean/.codex/sessions/2026/09/16/rollout-2026-09-16T20-29-51-01a0ad32-ef5a-70a0-b15c-b8bf14e09b00.jsonl",
 "cwd":"…/codexhook","hook_event_name":"SessionStart",
 "model":"gpt-6-astra","permission_mode":"bypassPermissions","source":"startup"}
```

UserPromptSubmit adds `turn_id` and `prompt` (the raw prompt text).

PreToolUse, shell:
```json
{"session_id":"…","turn_id":"01a0ad32-f08f-79b3-b9e6-0d05e088daca","transcript_path":"…","cwd":"…",
 "hook_event_name":"PreToolUse","model":"gpt-6-astra","permission_mode":"bypassPermissions",
 "tool_name":"Bash","tool_input":{"command":"echo hi > out.txt"},
 "tool_use_id":"exec-7df9d317-f45e-4bbe-907d-6bccee51f38a"}
```

PostToolUse, shell — same plus `"tool_response":""` (the command's stdout as
a **plain string**, not a structured object).

PreToolUse, patch:
```json
{"hook_event_name":"PreToolUse","tool_name":"apply_patch",
 "tool_input":{"command":"*** Begin Patch\n*** Add File: note.md\n+hello\n*** End Patch"},
 "tool_use_id":"exec-0c3cb1a1-6d67-492b-8989-a6c0cd41f392"}
```

PostToolUse, patch — `"tool_response":"Exit code: 0\nWall time: 0 seconds\nOutput:\nSuccess. Updated the following files:\nA note.md\n"`.

Stop: `{"hook_event_name":"Stop","stop_hook_active":false,"last_assistant_message":"Done. Created `out.txt` …"}`.
SessionEnd: `{"hook_event_name":"SessionEnd","reason":"other"}` — no `model`, no `permission_mode`.

## 4. Tool names

| what the model did | `tool_name` | verified live |
|---|---|---|
| shell command | **`Bash`** | yes — `tool_name":"Bash","tool_input":{"command":"echo hi > out.txt"}` |
| patch | **`apply_patch`**, whole patch envelope in `tool_input.command` | yes |
| MCP call | **`mcp__<server>__<tool>`** | yes — a scratchpad stdio MCP server named `probe` with tool `ping` produced `B ev=PostToolUse tool=mcp__probe__ping resp={"content": [{"type": "text", "text": "pong"}]}` |

Neither `exec_command`, `shell`, nor `local_shell` ever appears. The nine
`tool=exec` lines in `logs/hook-debug.log` are our own fixture events
(`tmp/derived-probe/…`, `tmp/edit-feedback-…`), not codex — `grep -n "tool=exec" logs/hook-debug.log`.
Docs agree: "The canonical ToolName values include \"Bash\", \"apply_patch\",
and MCP tool names like \"mcp__server__tool\"."

An MCP `tool_response` is the **structured result object**; a `Bash` or
`apply_patch` `tool_response` is a string.

`tool_input` never carries `file_path` or `filePath`. For `apply_patch` the
only way to the paths is parsing the patch envelope — which is what
`bin/seon-hook`'s `event-file-paths` / `patch-file-paths` already does.

## 5. Output contract

Documented (<https://learn.chatgpt.com/docs/hooks>, §Output JSON Contract):
`continue`, `stopReason`, `systemMessage`, `suppressOutput` ("Parsed only —
Not yet implemented"), `hookSpecificOutput.additionalContext`, `decision`
(`"block"`, for PreToolUse/PostToolUse/UserPromptSubmit), `reason`,
`permissionDecision` (`allow`/`deny`/`ask`), `updatedInput` (PreToolUse).

Exit codes, documented: "**0** — Success; parse stdout as JSON. **2** —
Blocking decision; read reason from stderr. **Other** — Failure; report
error and continue."

Verified live, one run per row:

| claim | verified live | evidence |
|---|---|---|
| PreToolUse `{"decision":"block","reason":…}` stops the tool and the model sees the reason immediately | **YES** | the lane's own transcript: ``Script error:\nCommand blocked by PreToolUse hook: BLOCKED: clj-kondo found error-level issues in the prospective Clojure edit:\ntmp/hook-probe-docs/b.clj:1:24 [error/syntax] Nested #()s are not allowed.`` — the reason is reproduced whole |
| PostToolUse `{"decision":"block","reason":…}` replaces the tool result the model sees | **YES** | three consecutive `echo` calls all returned ``Script failed … Script error:\nPOSTBLOCK-MARKER-QQQ: the hook refuses this result``; the CLI logged `ERROR codex_core::tools::router: error=POSTBLOCK-MARKER-QQQ…` and `hook: PostToolUse Blocked`. The side effect still happened — the shell had already run |
| exit 2 blocks, stderr becomes the reason | **YES** | `Command blocked by PreToolUse hook: EXIT2-STDERR-MARKER. Command: echo one` |
| any other non-zero exit is ignored | **YES** | exit 1 → `hook: PreToolUse Failed`, and `echo two` ran normally |
| non-JSON stdout with exit 0 is ignored | **YES** | `hook: PreToolUse Completed`, `echo three` ran normally |
| `hookSpecificOutput.additionalContext` reaches the model | **YES** | the rollout carries it as its own turn item: `"role": "developer", "content":[{"type":"input_text","text":"ADVISORY-MARKER-ZZZ from the hook"}], … "content_item_kinds": ["hooks.additional_context"]` — one per tool call |
| `systemMessage` reaches the model | **NO — it does not** | `SYSMSG-MARKER-WWW` was emitted on every PostToolUse and appears **nowhere** in the session rollout, while `ADVISORY-MARKER-ZZZ` from the same JSON object appears three times |
| `permissionDecision:"deny"` stops a tool | **NO, not under our lane flags** | with `--dangerously-bypass-approvals-and-sandbox`, a PreToolUse returning `{"permissionDecision":"deny","permissionDecisionReason":"DENY-MARKER-PPP …"}` did not stop `echo alpha`; it ran and returned `alpha` |
| `updatedInput` rewrites the tool call | **NO, not under our lane flags** | both `{"hookSpecificOutput":{"hookEventName":"PreToolUse","updatedInput":{…}}}` and a top-level `{"updatedInput":{…}}` left `echo beta` / `echo gamma` running unchanged |

Operational consequence: under the lane's bypass flags, **`decision:"block"`
(or exit 2) is the only construct that actually stops or replaces anything.**
`bin/seon-hook` already uses exactly that (the `output!` calls in `bin/seon-hook`'s `-main`) plus `additionalContext` for advisories
(the `feedback-response` path in `bin/seon-hook`), so its contract is the right one — it is just not
being invoked (§0).

## 6. Does a running session re-read the config?

**Docs say yes. Live says no.** The public reference states: "Hooks are
evaluated at event time, not cached at session start. Configuration changes
take effect immediately for new hook invocations within an ongoing session."
(<https://learn.chatgpt.com/docs/hooks>, §Session Reload Behavior)

Probe: a hook that, on its first `PostToolUse`, rewrote `.codex/hooks.json`
in place to point every entry at a *different* script, then three tool calls.
The file on disk was rewritten (confirmed after the run), yet the trace shows
six invocations of the ORIGINAL script and zero of the replacement:

```
H1 PreToolUse
H1 PostToolUse
H1 PreToolUse
H1 PostToolUse
H1 PreToolUse
H1 PostToolUse
```

So on `codex-cli 0.153.4` the hook set is **snapshotted when the session
starts**. A long-lived lane runs the hook configuration that existed at its
launch — including, as §0 shows, "no hooks at all". Changing
`.codex/hooks.json` never reaches a lane that is already running; the lane
must be stopped and relaunched. Editing `bin/seon-hook` itself DOES take
effect immediately, because the snapshot holds the command path, not the
script's bytes.

## 7. Configuration precedence and gating

Documented precedence, lowest to highest, all layers **merged** rather than
overridden: system hooks → `~/.codex/hooks.json` or `~/.codex/config.toml
[hooks]` → `<repo>/.codex/hooks.json` or `<repo>/.codex/config.toml [hooks]`
→ plugin-bundled hooks → `requirements.toml [hooks]` (managed, highest).
"Codex loads hooks from all active layers; higher-precedence layers don't
replace lower ones"; "If both `hooks.json` and inline `[hooks]` exist in one
layer, Codex merges and warns."

A repo hook therefore ADDS to a global hook; it never shadows one. Not
verified live (no global hook exists here, and installing one would edit the
owner's `~/.codex`).

**There is no hooks feature flag.** `codex --help` documents
`--enable <FEATURE>` / `--disable <FEATURE>` as sugar for
`-c features.<name>=true`; `~/.codex/config.toml` has only `[features] js_repl = false`.
Hooks are gated by the TRUST mechanism of §0, not by a feature flag.
Admin-side, `docs/config.md` in `openai/codex` documents the only kill
switch: "Admins can set top-level `allow_managed_hooks_only = true` in
`requirements.toml` to ignore user, project, and session hook configs".

Our `.codex/hooks.json` uses the documented shape (`{"hooks": {"<Event>":
[{"matcher": …, "hooks": [{"type": "command", "command": …}]}]}}`) and is
correct; some third-party write-ups claiming events sit at the root of the
file are wrong for this version.

## 8. Matchers and timeouts

Matcher: a regex over `tool_name` for the tool events ("Event-specific:
filters `tool_name`, `source`, `agent_type`, `trigger`, `reason`"; `"*"`,
`""` or omitted matches everything; `UserPromptSubmit`, `Stop`, `Interrupt`
ignore the matcher). **Verified live:** a `.codex/hooks.json` with
`"matcher": "apply_patch"` on PreToolUse and `".*"` on PostToolUse produced
exactly one PreToolUse line — for `apply_patch` — while PostToolUse fired for
`mcp__probe__ping`, `Bash` and `apply_patch`. Our `.*` matcher on both events
is right.

Handler fields, per docs and the binary's serde field list
(`…commandcommandWindowstimeoutasyncstatusMessageadditionalContextLimitmcp_toolserverinputpromptagent…`):
`type` (`command` | `mcp_tool`), `command`, `commandWindows`, `timeout`,
`async`, `statusMessage`, `additionalContextLimit`.

Timeouts: "Most events 600 seconds"; "SessionEnd, Interrupt — default 1
second, maximum 3 seconds". Not verified live. `bin/seon-hook` is well inside
600 s for PreToolUse/PostToolUse; nothing it does belongs on `SessionEnd`.

## 9. What codex CANNOT do that Claude Code can

For the agent working on `bin/seon-hook`: every item here is a capability the
hook must NOT assume when `session_id` looks like a codex session.

1. **No `Edit`, `Write`, `MultiEdit`, `NotebookEdit`, `Task`/`Agent`,
   `Read`, `Glob`, `Grep` tool names.** Codex has exactly three families:
   `Bash`, `apply_patch`, `mcp__<server>__<tool>`. Verified live. Every
   `#{"apply_patch" "Edit" "Write"}` branch in `bin/seon-hook`'s `edit-event?` binding in `-main` reduces
   to `apply_patch` for a lane.
2. **No `tool_input.file_path`.** Claude Code hands the hook the path;
   codex hands it a patch envelope string, so the paths must be parsed out
   (`bin/seon-hook`'s `event-file-paths` / `patch-file-paths`). A codex shell write names no path at all — the
   derived-digest scan (`bin/seon-hook`'s `derived-write-refusal`) is the only coverage, and it is
   the ONLY coverage, because there is no `Write` event to catch it.
3. **No structured `tool_response`.** Claude Code's PostToolUse carries a
   map (stdout, stderr, interrupted, …). Codex gives a bare string for
   `Bash` and `apply_patch`; only an MCP call returns an object. A hook that
   reads `(:stdout (:tool_response event))` gets nothing from a lane.
4. **`systemMessage` is invisible to the model.** Verified live: emitted,
   never delivered. Anything the agent must read goes in
   `hookSpecificOutput.additionalContext` or in a `reason`.
5. **`permissionDecision` (`allow`/`deny`/`ask`) does not stop a tool** under
   the lane's `--dangerously-bypass-approvals-and-sandbox`. Verified live.
   Claude Code honours `permissionDecision: "deny"`. A hook that denies this
   way against a lane silently permits the write.
6. **`updatedInput` does not rewrite the tool call** under those flags.
   Verified live. A hook cannot repair a lane's patch; it can only refuse it.
7. **Hooks are snapshotted at session start** (§6), contradicting the public
   docs. Claude Code hooks can be changed for the next tool call; a codex
   lane's hook set is frozen for its whole life, which for our lanes is
   hours.
8. **Hooks require persisted per-entry trust and fail SILENTLY without it**
   (§0). Claude Code has no equivalent gate. This is the single most
   dangerous difference: the hook's absence is indistinguishable from the
   hook passing — precisely the "absence of signal read as health" class.
9. **No `PostToolUse` undo.** A blocking PostToolUse keeps the result from
   the model but the side effect has landed; the hook's own wording
   ("The bytes are already written", the `derived-write-refusal` docstring) is the right
   model for codex and stays right.
10. **No per-project settings merge like `.claude/settings.json`**, no
    `$CLAUDE_PROJECT_DIR`. Codex passes `cwd` in the payload; plugin hooks
    additionally get `PLUGIN_ROOT` and `PLUGIN_DATA`. `bin/seon-hook` is
    already correct to derive its root from `*file*` (the `repo-root` def)
    rather than from the environment.
11. **Events Claude Code does not have** (so nothing in the hook may assume
    they never fire): `PermissionRequest`, `PostCompact`, `SubagentStart`,
    `Interrupt`.

## Reproducing this

The probes are four scratchpad checkouts, each with its own
`.codex/hooks.json` and a `/bin/sh` hook that appends its stdin to a file,
driven by:

```
codex exec -m gpt-6-astra -c model_reasoning_effort=low \
  --dangerously-bypass-approvals-and-sandbox \
  --dangerously-bypass-hook-trust -
```

Dropping the last flag is the §0 A/B. Session rollouts, which are the
authority on what the model actually received, are at
`~/.codex/sessions/<yyyy>/<mm>/<dd>/rollout-*-<session_id>.jsonl`.
