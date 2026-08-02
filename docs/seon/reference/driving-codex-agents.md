---
type: reference
status: active
tags: [reference, agent]
---

# Driving Codex agent lanes

Seon has two orchestration surfaces, selected by the orchestrator that owns the
work. They are not interchangeable.

- A Codex orchestrator launches and supervises new lanes through Codex's native
  collaboration task tree. It does not invoke `bin/codex-agent` to create a
  substitute tree.
- A Claude orchestrator has no Codex-native task tree, so it launches Codex
  lanes through `bin/codex-agent` as harness-tracked background commands.

An orchestrator independently reads source and diffs before accepting a lane's
report. A summary is a claim, not proof.

## Claude harness commands

```sh
bin/codex-agent run <name> "<complete spec>"
bin/codex-agent status
bin/codex-agent watch <name>
bin/codex-agent summary <name>
bin/codex-agent stop <name>
bin/codex-agent resume <name> "<follow-up>"
```

`run` and `resume` also accept their prompt on standard input. The wrapper owns
the Codex command shape, session-id capture, the `-o` summary, transcript
streaming, and lane/session exclusion. Its defaults are `gpt-5.6-sol` with high
reasoning effort; a Claude orchestrator may set `LANE_MODEL` and `LANE_EFFORT`
for a bounded lane.

Launch `run` or `resume` bare as the harness-tracked background command. Do not
wrap it in `nohup`, append `&`, redirect its output, or pipe the wrapper through
`head`, `tail`, or `grep`. The wrapper's stdout is the user's live task-panel
view and is simultaneously persisted to
`tmp/orchestrator/<name>-stdout.log`. The final message is written to
`tmp/orchestrator/<name>-summary.txt`.

Read the bounded summary first, then query the log selectively with `tail` or
`rg`; do not load a whole multi-megabyte transcript into the orchestrator's
context. If a lane was launched outside the current owner, make it visible with
a separate harness-tracked `bin/codex-agent watch <name>` command.

`resume` discovers the prior session id from the lane log and preserves its
Codex context. Stop a lane whose direction has become invalid, then resume it
with the correction. Never start two live processes for one lane or session;
the wrapper refuses both collisions.

## Lane contract

Every lane specification names:

- one coherent result and its acceptance evidence;
- exact owned and protected paths;
- the architecture and roadmap boundary it advances;
- exact dependency revisions, vendored source paths, and first-party idioms it
  must read;
- its required path-limited commit or durable report; and
- the rule to stop and report an in-flight cross-lane breakage without editing
  another lane's files or session.

Lanes are never sandboxed. Audit evidence must be writable, reviewable, and
committable; path ownership, path-limited commits, and independent diff review
are the safety boundary. No lane pushes the shared branch.

The shared checkout is normal. Preserve unrelated edits, never use `git add
-A`, and commit with `git commit --only -- <explicit-owned-paths>`. A returned
lane frees its slot only after the orchestrator verifies and integrates or
rejects its claims.

## Codex-native orchestration

A Codex orchestrator uses its native spawn, message, interrupt, follow-up, list,
and wait operations. The native task tree is the ownership and supervision
surface. If Codex inherits a lane previously started through
`bin/codex-agent`, it may inspect, stop, or collect that lane for a safe
handoff, but launches all new work natively.

## Sources checked

- `bin/codex-agent` — Claude-side command grammar, defaults, session ownership,
  stdout/log/summary paths, and resume behavior.
- Root `AGENTS.md` — orchestrator selection, lane ownership, supervision, and
  shared-tree rules.
