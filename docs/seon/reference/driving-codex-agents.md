---
type: reference
status: active
tags: [reference, agent]
---

# Driving Codex agents as an orchestrator

You are the **orchestrator**, not the implementer. You write a spec, hand it to a
Codex agent via `codex exec`, capture its summary, then **independently verify**
what it actually did against the source and diff before accepting. Codex's summary
is a *claim*, not proof — never trust it without checking the real artifacts.

Verified against Codex CLI **0.144.6**, logged in via ChatGPT (no API key needed).

## The one command (full-access — it runs commands and edits files)

```bash
codex exec \
  -m gpt-5.6-sol \
  -c model_reasoning_effort=low \
  --dangerously-bypass-approvals-and-sandbox \
  -o /abs/path/summary.txt \
  "<the full spec / instructions>" \
  < /dev/null
```

`--dangerously-bypass-approvals-and-sandbox` is one switch meaning: no sandbox, no
approval prompts, may run any command and edit any file. It runs fully
autonomously and returns when done. The final agent message lands in the `-o`
file — that is the summary you read back.

**Never sandbox a lane** (owner ruling 2026-07-26) — see the section below.

## Launching under the Claude Code harness — bin/codex-agent, tracked, never nohup

`bin/codex-agent` is the one launcher — it bakes in every convention below
(model/effort dials, `-o` summary, `tee`-streamed stdout,
stdin-via-`-`): `bin/codex-agent run <name> "<spec>"` (or spec on a heredoc),
plus `status`, `watch`, `summary`, and `resume` subcommands. Do not
hand-roll `codex exec` shell for lanes; the rest of this section is the
rationale and the contract the script implements.

When the orchestrator is a Claude Code session, launch every lane as a
harness-tracked background command: run `bin/codex-agent run ...`
through the Bash tool with `run_in_background: true` — **no `nohup`, no
trailing `&`**. Verified 2026-07-24 (probe lane `bmxosu25m`): the lane
appears in the user's background-tasks panel under the Bash call's
description, keeps writing its `-o` summary and stdout log exactly as
before, and the harness re-invokes the orchestrator automatically when
the process exits.

Why this is the rule: `nohup ... &` detaches the process from the
harness — the user's UI shows nothing running, the orchestrator gets no
completion signal, and every session ends up hand-rolling
sleep-and-poll watcher loops to compensate (2026-07-24 morning: four
detached lanes, an invisible fleet, and an owner asking "what are you
waiting for?"). Tracked launches delete that whole failure class.

Mechanics that still apply unchanged: `< /dev/null` (stdin gotcha
below), `-o` summary files, and independent verification of the diff.
Stream stdout through `tee`, never a bare `>` redirect —
`codex exec [flags] "<spec>" < /dev/null 2>&1 | tee
tmp/orchestrator/<lane>-stdout.log` — so the user's task panel shows
the lane's live transcript while the log file still persists (owner
request 2026-07-24: watchable agents). This also protects the
orchestrator's context: background-task stdout never enters the
conversation on its own — the orchestrator gets a one-line completion
notification, reads the `-o` summary, and queries the stdout log
selectively (`tail`/`rg`), never a whole-file read (transcripts reach
megabytes). Give each Bash call a
description naming the lane (that string is what the user's panel
shows). Detached `nohup` remains
acceptable only when a lane must survive the orchestrator session
itself dying — the rare case, not the default.

To make an already-detached lane watchable after the fact, launch a
tracked background `tail -n 40 -f tmp/orchestrator/<lane>-stdout.log`
whose description names the lane — the panel entry then streams that
lane's live transcript. Kill the follower when the lane lands.
Supervisor/watcher loops are intentionally silent between events; give
the user a `tail -f` follower to watch, never the watcher.

## CRITICAL gotcha — always redirect stdin

`codex exec` reads stdin whenever stdin is not a TTY and **blocks until EOF**.
Under any automation, pipe, or tool that leaves stdin open it hangs forever (looks
like a stuck multi-minute run). **Always append `< /dev/null`.** To pass the spec
via stdin instead of as an argument, pipe it deliberately and end the args with a
bare `-`: `printf '%s' "$SPEC" | codex exec [flags] -`.

## Choosing the model — two independent dials

- **Model:** `-m <name>`. Available now: `gpt-5.6-sol` (default), `gpt-5.6-luna`,
  `gpt-5.6-terra`, `gpt-5.5`, `gpt-5.4`, `gpt-5.4-mini`, `gpt-5.3-codex-spark`.
- **Reasoning effort:** `-c model_reasoning_effort=low|medium|high`.

Cheap or mechanical task → default sol at low. Hard reasoning or audit → raise the
effort or pick a stronger model. Per-run overrides never touch
`~/.codex/config.toml` (which holds the persistent default `model` and
`model_reasoning_effort`).

## NEVER SANDBOX A LANE

Owner ruling, 2026-07-26. Every lane runs
`--dangerously-bypass-approvals-and-sandbox`. `bin/codex-agent` hardcodes it and
has **no sandbox dial**; do not add one back, and do not hand-roll `-s` on a
lane.

**Why, concretely.** A read-only audit lane completed a 63-file inventory, then
had its single `apply_patch` rejected by the sandbox. Its counts survived in the
`-o` summary; **its per-file evidence sentences did not**, and the report had to
be reconstructed by hand. A sandbox does not make an audit safer — it makes the
audit's *output* unrecordable. Every lane, audit included, must commit its own
report and file its own issue notes, so read-only is never the right mode.

**What actually enforces ownership**, and it is not the sandbox:

- name the lane's OWNED PATHS in the spec, and name the paths other lanes own
  as forbidden;
- require path-limited commits (`git commit --only -- <paths>`), never
  `git add -A`;
- review the diff yourself afterwards — that review is the safeguard.

An audit that must not change source is told so in its spec and proven by its
diff, not prevented by a flag that also stops it recording what it found.

Other placement flags: `-C, --cd <DIR>` sets the working root (fresh `exec` runs
only — `resume` has no `-C`, see below); `--add-dir <DIR>` adds extra writable dirs
alongside the workspace.

**Shared-tree note:** this repo is a shared checkout with other agents active. A
full-access Codex editing here can collide with concurrent work, so keep specs
narrowly scoped to owned paths and review the diff after every run. Verify what it
did with `git status` / `git diff` before accepting; that review is the safeguard.

## The orchestration loop

1. **Spec.** Write tight, falsifiable instructions: exact scope, what to produce,
   what not to touch. For structured results add `--output-schema schema.json`
   (a JSON Schema) so the final message is machine-checkable JSON.
2. **Drive.** Run the command above. Add `--json` to also stream every step as
   JSONL to stdout (a full trace of commands it ran and files it read).
3. **Read the summary** from the `-o` file.
4. **Verify independently.** This is the whole point. For an audit, re-run the
   check yourself (`rg`, `ls`, read the cited `file:line`) and confirm the claim.
   For a write task, `git diff` / `git status` and read the actual change —
   confirm it matches the spec and touched nothing else.
5. **Accept or correct.** If it is wrong or incomplete, iterate on the same
   session by explicit id (see "Resuming a session" below).

## Resuming a session — the reliable recipe

Resume lets you iterate: correct or extend a run while Codex keeps the full context
of what it already did (verified — a resumed run remembered and edited the file its
first turn created). This makes stop-and-report **free**: an implementer that halts
to flag a better seam or a spec gap loses nothing — the resumed session picks up
with all its grounding intact. Owner ruling (2026-07-21): every spec should say
this explicitly and encourage early stops; the seam corrections they produce are
exactly what we want to learn from. But `codex exec resume` is NOT `codex exec` with an id bolted
on, and this is exactly where "resume doesn't work" comes from. Three rules:

**1. Capture the session id on the first run.** Add `--json` to the fresh `exec`
run and read the `thread_id` from the first `thread.started` event — that IS the
session id:

```bash
SID=$(codex exec --dangerously-bypass-approvals-and-sandbox --json \
        "<spec>" < /dev/null \
      | grep -m1 thread.started \
      | python3 -c "import sys,json;print(json.load(sys.stdin)['thread_id'])")
```

(You can also find it later as the UUID in
`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`, in the `session_id` field.)

**2. Resume by explicit id — never `--last`.** `--last` grabs the newest recorded
session, which is often a multi-agent sub-agent from other tooling and fails with
`direct app-server input is not allowed for multi-agent v2 sub-agents`. Only
top-level runs (`originator: codex_exec`) resume, so pass the id you captured.

**3. Resume has a reduced flag set.** It rejects `-s`, `-o`, `-C`, `--json`, and
`--output-schema` (e.g. `error: unexpected argument '-s' found`). So:

- resume accepts the single `--dangerously-bypass-approvals-and-sandbox` flag,
  which is the only mode a lane ever runs (see "NEVER SANDBOX A LANE");
- there is no `-C` — the working dir is the process cwd, so `cd` into it first;
- there is no `-o` — read the final message from stdout.

Working full-access resume:

```bash
( cd /path/to/workdir && \
  codex exec resume "$SID" \
    --dangerously-bypass-approvals-and-sandbox \
    "<follow-up correction>" < /dev/null )
```

## Useful extras

- `--json` — JSONL event stream (`thread.started`, `item.completed`,
  `turn.completed` with token usage) for tracing.
- `--output-schema <FILE>` — force the final response into a JSON shape you define.
- `-o, --output-last-message <FILE>` — where the final summary is written.
- `--ephemeral` — do not persist a session file to disk.
- `codex apply` — apply the latest Codex-produced diff to the working tree as
  `git apply`.
- `codex exec resume <id>` — continue or iterate a run (see "Resuming a session"
  above; prefer an explicit id over `--last`).

## Known harmless noise — the models-cache error

```text
ERROR codex_models_manager::cache: failed to load models cache:
  missing field `supports_reasoning_summaries` at line 88 column 5
ERROR codex_models_manager::manager: failed to renew cache TTL: ...
```

This is a bug in codex-cli **0.144.6 itself**, not your config and not stale data.
The model catalog served to the account omits `supports_reasoning_summaries` for
every model, but this build's cache reader treats that field as required, so it
logs on every load/TTL-renew. Deleting `~/.codex/models_cache.json` does NOT help —
Codex rewrites it in the same shape it cannot read. **It has zero functional
impact**: runs and model selection work right through it (0.144.6 is the latest
release, so there is no upgrade that fixes it).

To silence it, prefix runs with a log-level override that mutes just that
subsystem:

```bash
RUST_LOG=codex_models_manager=off codex exec [flags] "<spec>" < /dev/null
```

## Proven-working example (audit + verify)

An audit is kept read-only by its SPEC and proven by its diff — never by a
sandbox flag, which would also stop it writing its report.

```bash
# Drive
codex exec -m gpt-5.6-sol -c model_reasoning_effort=low \
  --dangerously-bypass-approvals-and-sandbox \
  --skip-git-repo-check -o summary.txt \
  "List the .cljs and .cljc files directly inside src-old/seon/db/ (not subdirs).
   Filenames one per line, then a count. Change no source." < /dev/null

# Verify independently — did its claim match reality?
ls -1 src-old/seon/db/ | grep -E '\.(cljs|cljc)$'

```

Codex reported 6 files; `ls` confirmed the same 6. Accept.
