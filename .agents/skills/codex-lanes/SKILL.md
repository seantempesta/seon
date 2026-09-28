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

## Models (from `~/.codex/models_cache.json`, codex-cli 0.156.1, 2026-09-23)

| `LANE_MODEL` | Efforts (default) | Use for (owner 2026-09-23) |
|---|---|---|
| `gpt-6-astra` (launcher default) | low … max, ultra (medium) | the FIXER (owner 2026-09-28): follows each landed Opus slice, profiles it at the REPL and commits fixes for super-linear or repeated work and law violations in root-assigned files; also diff review and diagnosis |
| `gpt-6-sol` | low … max, ultra (medium) | implementation from a written PRD or plan doc — owner: "try out gpt 6 sol that's new"; compare against Opus lanes on clean, small, bug-free code |
| `gpt-6-luna` | low … max (medium) | fast and cheap: probes, mechanical edits |

Only the GPT-6 versions are used (owner 2026-09-23). Dials: `LANE_MODEL`
(default `gpt-6-astra`), `LANE_EFFORT` (launcher default `low`, not the model's
`medium`) (`bin/codex-agent:26`, `:54-55`).

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
- **Tests:** assigned lanes start no test requests (AGENTS.md "Stay inside
  the assignment"); they name the focused tests their slice needs and root
  runs them centrally. The appended `lane_rule` still tells a lane to iterate
  with `bin/test-check`, so every spec must say explicitly that the lane runs
  no tests.
- **Hooks:** the launcher passes `--dangerously-bypass-hook-trust` on both
  paths, because Codex silently skips a project hook whose trust snapshot
  no longer matches (`c41dd408b`). Codex snapshots `.codex/hooks.json` at
  process start: after changing it, `stop` and `resume` every running lane
  (`bin/codex-agent:27-30`). A refusal reaches the model as exit 2 with the
  reason on stderr; a shell write is refused AFTER its bytes land (Codex has
  no pre-write shell event); a patch write is refused before.
- **Default is the owner's window:** a lane never stops, resets, reforks or
  adopts `default`; RESET NEEDED goes in the landing note.
- **Commits are the heartbeat:** path-limited (`git commit --only -- …`),
  one coherent slice each; the orchestrator gates cold and pushes.

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
