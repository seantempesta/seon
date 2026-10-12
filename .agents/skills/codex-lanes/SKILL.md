---
name: codex-lanes
description: Launch, pause, resume and read Codex implementation lanes through bin/codex-agent; which model and effort to pick; the hook, gate and load rules a lane lives under. Use before any bin/codex-agent call.
---

# Codex lanes

`bin/codex-agent` is the one way to run a Codex lane (its usage header).
Conventions and history: `docs/seon/reference/driving-codex-agents.md`;
the verified hook behaviour: `docs/seon/reference/codex-cli-hooks-2026-09-17.md`.
Every claim here is checked against the launcher and `~/.codex/models_cache.json`.

## Commands

| Command | What it does |
|---|---|
| `bin/codex-agent run <name> <<'SPEC' … SPEC` | launch; streams the transcript to stdout AND `tmp/orchestrator/<name>-stdout.log`; the final summary is written to `tmp/orchestrator/<name>-summary.txt` |
| `bin/codex-agent stop <name>` | PAUSE: stops the codex process tree, verifies every pid exited, preserves the session |
| `bin/codex-agent resume <name> <<'SPEC' … SPEC` | continue with full prior context; the session id is read from the launcher's retained file `tmp/orchestrator/lanes/<name>/sid`, never from transcript text (a lane once printed a fake id); `LANE_SID=<id>` overrides |
| `bin/codex-agent status` | running lanes with pid and elapsed, plus summaries written in the last hour |
| `bin/codex-agent summary <name>` / `watch <name>` | read the summary; follow a live transcript (`tail -n 40 -f`) |

Launch as a harness-tracked background command, BARE (never piped, no
redirect), one lane per shell. Confirm the launch with `status` before
assuming it ran.

## Models (from `~/.codex/models_cache.json`, codex-cli 0.162.1)

| `LANE_MODEL` | Use for (owner 2026-10-12) |
|---|---|
| `gpt-6.1-sol` (always pass `LANE_MODEL=gpt-6.1-sol LANE_EFFORT=medium`) | the default implementation lane: cheaper and faster than Astra; also fixes and diagnosis. Raise effort only for deep, focused work. |
| `gpt-6-astra` | one of the two independent plans in every design round (below); hard performance work and audits when named explicitly |
| `gpt-6-luna` | fast and cheap: probes, mechanical edits |

Dials: `LANE_MODEL` and `LANE_EFFORT`, read on `run` and `resume`. The launcher's own defaults are `gpt-6-astra` and
`low`; pass both dials explicitly on every launch.

### The design round

Any design whose spec is uncertain is a task tree (CLAUDE.md, "Design by independent plans and a hybrid"): a Haiku
dig of `reference-code/`, our code, documents and Git history, then two independent plans, one by Opus and one by
Astra (`LANE_MODEL=gpt-6-astra`), neither seeing the other, then a Fable hybrid, then a narrow check, then tasks. The check is exactly three
questions: the plan's claims about the live system reproduce on `default`; every deletion is committed with the
conversion of its last caller; every phase names a red proof that resolves to a deftest. Sol is not a designer in this round.

## Writing a spec

- Always a quoted heredoc (`<<'SPEC'`): a backtick inside a double-quoted
  string is command substitution and silently eats the launch
  (`docs/TRANSFER_PROMPT.md` lesson 13).
- Name the grounding to read END TO END, the owned paths, the exact
  deliverable (note path, issue notes), the stop rule, and the raw
  evidence paths (gate log line numbers, retained run roots), never an
  attribution; two lanes refuted the orchestrator's stated cause in one night.
- Neutral verbs: verify, falsify, probe, never adversarial words (they trip
  model safety filters).
- NEVER sandbox a lane (usage header): ownership is named paths
  plus path-limited commits.

## The rules a lane lives under

- **Identity:** the launcher exports `SEON_CODEX_LANE=<name>` on run and
  resume and appends its `lane_rule` text to every spec and followup.
- **Tests:** every lane proves its own slice through `bin/lane commit`, naming
  each reaching test. Use a separate `--test NS-or-VAR`
  for each test; select Vars when a namespace has pre-existing reds, and name
  those reds in the commit note. Root runs the broad tiers centrally. A
  documentation-only commit without `--test` neither publishes nor boots (`bin/lane` usage).
  `bin/lane commit LANE -m MSG --branch-evidence AGENT PATHS` instead proves
  `src`/`test` Clojure files by the agent's branch evidence on the live root (`bin/lane evidence AGENT PATH...` asks
  that question alone) and falls back to the throwaway check when it cannot.
- **Hooks:** the launcher passes `--dangerously-bypass-hook-trust` on both
  `run` and `resume`, because Codex silently skips a project hook whose trust snapshot
  no longer matches. Codex snapshots `.codex/hooks.json` at
  process start: after changing it, `stop` and `resume` every running lane. A refusal reaches the model as exit 2 with the
  reason on stderr; a shell write is refused AFTER its bytes are written (Codex has
  no pre-write shell event); a patch write is refused before.
- **Default is the owner's window:** a lane never stops, resets, reforks or
  reloads `default`; RESET NEEDED goes in the commit note.
- **Commits are the heartbeat:** claim files first (`bin/lane claim LANE PATHS`), commit
  each coherent slice through `bin/lane commit LANE -m MSG --test NS-or-VAR --test NS-or-VAR PATHS` (proven on a
  throwaway JVM from the candidate), `bin/lane release LANE` at the end; root
  runs the broad tiers. Nothing is pushed without the owner's authorization.

## Pausing, resuming, reading

- `stop` is the pause. Verify the pids are gone (the command prints them)
  before `resume`; a resume racing a stop yields duplicates or ghost yields;
  when in doubt relaunch under a fresh name.
- A running lane cannot take a message: `stop`, then `resume` with the new
  information as the followup. Every followup states what was committed since,
  what is accepted, and the next bounded item.
- Read `summary` first, then query `tmp/orchestrator/<name>-stdout.log`
  selectively (`tail -c`, `grep`); lane stdout never enters the
  orchestrator's context whole.
- A lane stops for review at a coherent seam or a held file; "held" means
  another lane has uncommitted edits in that path. Check `git status`
  before naming a path protected.

## Sources

`bin/codex-agent` (usage header, `lane_rule`, the `run`, `resume`, `stop`, `watch`, `summary` and `status` cases);
`bin/lane` (usage header); `docs/seon/reference/driving-codex-agents.md`;
`docs/seon/reference/codex-cli-hooks-2026-09-17.md`; `docs/TRANSFER_PROMPT.md`
lessons 1–13; `~/.codex/models_cache.json`.
