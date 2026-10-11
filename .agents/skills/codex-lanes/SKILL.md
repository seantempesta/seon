---
name: codex-lanes
description: Launch, pause, resume and read Codex implementation lanes through bin/codex-agent; which model and effort to pick; the hook, gate and load rules a lane lives under. Use before any bin/codex-agent call.
---

# Codex lanes

`bin/codex-agent` is the one way to run a Codex lane (`bin/codex-agent:1-25`).
Conventions and history: `docs/seon/reference/driving-codex-agents.md`;
the verified hook behaviour: `docs/seon/reference/codex-cli-hooks-2026-09-17.md`.
Every claim here is verified against the launcher and
`~/.codex/models_cache.json` (line numbers re-checked 2026-09-26).

## Commands

| Command | What it does |
|---|---|
| `bin/codex-agent run <name> <<'SPEC' … SPEC` | launch; streams the transcript to stdout AND `tmp/orchestrator/<name>-stdout.log`; the final summary lands in `tmp/orchestrator/<name>-summary.txt` (`bin/codex-agent:9-13`) |
| `bin/codex-agent stop <name>` | PAUSE: stops the codex process, verifies every pid exited, preserves the session (`:451`) |
| `bin/codex-agent resume <name> <<'SPEC' … SPEC` | continue with full prior context; the session id is read from the launcher's retained record `tmp/orchestrator/lanes/<name>/sid`, never from transcript text (`:14-19`, `:393`; a lane once printed a fake id); `LANE_SID=<id>` overrides |
| `bin/codex-agent status` | running lanes with pid and elapsed, plus summaries landed in the last hour (`:536`) |
| `bin/codex-agent summary <name>` / `watch <name>` | read the summary; follow a live transcript (`:508`, `:522`) |

Launch as a harness-tracked background command, BARE (never piped, no
redirect), one lane per shell. Confirm the launch with `status` before
assuming it ran.

## Models (from `~/.codex/models_cache.json`, codex-cli 0.162.1, 2026-10-11)

| `LANE_MODEL` | Use for (owner 2026-10-11) |
|---|---|
| `gpt-6.1-sol` (always pass `LANE_MODEL=gpt-6.1-sol LANE_EFFORT=medium`) | the default Codex lane: cheaper and faster than Astra. Above all an independent **designer**: one of the two plans in every design round (AGENTS.md "Design by independent plans and a hybrid"); also implementation, fixes and diagnosis. Raise effort only for deep, focused work. |
| `gpt-6-astra` | hard performance work and audits when named explicitly |
| `gpt-6-luna` | fast and cheap: probes, mechanical edits |

Dials: `LANE_MODEL` and `LANE_EFFORT`, read on `run` and `resume`. The launcher's own defaults are still `gpt-6-astra` and
`low`, because no test reaches `bin/codex-agent` to prove a change to them; pass both dials explicitly on every launch.

## Writing a spec

- Always a quoted heredoc (`<<'SPEC'`): a backtick inside a double-quoted
  string is command substitution and silently eats the launch
  (TRANSFER_PROMPT lesson 13).
- Name the grounding to read END TO END, the owned paths, the exact
  deliverable (landing note path, issue notes), the stop rule, and the raw
  evidence paths (gate log line numbers, retained run roots) — never an
  attribution; two lanes refuted the orchestrator's stated cause in one night.
- Neutral verbs: verify, falsify, probe — never adversarial words (they trip
  model safety filters).
- NEVER sandbox a lane (`bin/codex-agent:31-38`): ownership is named paths
  plus path-limited commits.

## The rules a lane lives under

- **Identity:** the launcher exports `SEON_CODEX_LANE=<name>` on run and
  resume (`:381`) and appends its `lane_rule` text (`:56`) to every spec and
  followup (`:428`, `:443`).
- **Tests:** every lane proves its own slice through `bin/lane commit`, naming
  each reaching test (`bin/codex-agent:56`). Use a separate `--test NS-or-VAR`
  for each test; select Vars when a namespace has pre-existing reds and name
  those reds in the landing. Root runs the broad tiers centrally. Documentation
  is not a program input: a documentation-only commit without `--test` does not
  publish or boot (`bin/lane` usage). The REPL-first gate is a target until its
  ledger row lands; do not assume that gate exists.
- **Hooks:** the launcher passes `--dangerously-bypass-hook-trust` on both
  paths, because Codex silently skips a project hook whose trust snapshot
  no longer matches (`c41dd408b`). Codex snapshots `.codex/hooks.json` at
  process start: after changing it, `stop` and `resume` every running lane
  (`bin/codex-agent:27-30`). A refusal reaches the model as exit 2 with the
  reason on stderr; a shell write is refused AFTER its bytes land (Codex has
  no pre-write shell event); a patch write is refused before.
- **Default is the owner's window:** a lane never stops, resets, reforks or
  adopts `default`; RESET NEEDED goes in the landing note.
- **Commits are the heartbeat:** claim files first (`bin/lane claim LANE PATHS`), commit
  each coherent slice through `bin/lane commit LANE -m MSG --test NS-or-VAR --test NS-or-VAR PATHS` (proven on a
  throwaway JVM from the candidate), `bin/lane release LANE` at the end; the orchestrator
  runs the broad tiers and pushes.

## Pausing, resuming, reading

- `stop` is the pause. Verify the pids are gone (the command prints them)
  before `resume`; a resume racing a stop yields duplicates or ghost yields —
  when in doubt relaunch under a fresh name.
- A running lane cannot take a message: `stop`, then `resume` with the new
  information as the followup. Every followup states what landed since,
  what is accepted, and the next bounded item.
- Read `summary` first, then query `tmp/orchestrator/<name>-stdout.log`
  selectively (`tail -c`, `grep`); lane stdout never enters the
  orchestrator's context whole.
- A lane stops for review at a coherent seam or a held file; "held" means
  another lane has uncommitted edits in that path — check `git status`
  before naming a path protected.

## Sources

`bin/codex-agent` (usage header `:2-37`, dials `:26,54-55`, lane rule `:56`,
lane identity `:381`, resume record `:393`, stop `:451`, watch `:508`, summary
`:522`, status `:536`);
`docs/seon/reference/driving-codex-agents.md`;
`docs/seon/reference/codex-cli-hooks-2026-09-17.md`; `docs/TRANSFER_PROMPT.md`
lessons 1–13 (2026-09-17); `~/.codex/models_cache.json`.
