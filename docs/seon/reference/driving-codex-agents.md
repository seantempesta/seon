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

For a read-only investigation instead, swap that one flag for
`-s read-only -c approval_policy=never`.

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

## Sandbox and approvals — how much power to give it

Two dials: what it can touch (`-s`) and whether it pauses to ask
(`-c approval_policy=`). For autonomous work you want no pauses, so set
`approval_policy=never` and pick the sandbox:

- `-s read-only` — cannot write. Audits, traces, investigations.
- `-s workspace-write` — may run commands and edit files inside the working root,
  but is blocked from writing outside it (and network is restricted). Good default
  for real changes confined to the repo.
- `-s danger-full-access` — no restriction at all (writes anywhere, full network).
- `--dangerously-bypass-approvals-and-sandbox` — shorthand for full access **and**
  no approval prompts in one flag. This is the "just let it do everything" switch.

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
4. **Verify independently.** This is the whole point. For a read-only audit,
   re-run the check yourself (`rg`, `ls`, read the cited `file:line`) and confirm
   the claim. For a write task, `git diff` / `git status` and read the actual
   change — confirm it matches the spec and touched nothing else.
5. **Accept or correct.** If it is wrong or incomplete, iterate on the same
   session by explicit id (see "Resuming a session" below).

## Resuming a session — the reliable recipe

Resume lets you iterate: correct or extend a run while Codex keeps the full context
of what it already did (verified — a resumed run remembered and edited the file its
first turn created). But `codex exec resume` is NOT `codex exec` with an id bolted
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

- sandbox/approvals go through `-c sandbox_mode=...` / `-c approval_policy=...`, or
  the single `--dangerously-bypass-approvals-and-sandbox` flag (which resume accepts);
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

## Proven-working example (read-only audit + verify)

```bash
# Drive
codex exec -m gpt-5.6-sol -c model_reasoning_effort=low -s read-only \
  -c approval_policy=never --skip-git-repo-check -o summary.txt \
  "List the .cljs and .cljc files directly inside src/seon/db/ (not subdirs).
   Filenames one per line, then a count. Do not modify anything." < /dev/null

# Verify independently — did its claim match reality?
ls -1 src/seon/db/ | grep -E '\.(cljs|cljc)$'

```

Codex reported 6 files; `ls` confirmed the same 6. Accept.
