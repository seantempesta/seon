---
type: orchestrator
status: active
tags: [orchestrator]
---

# Top-level orchestrator workflow

`AGENTS.md` is the one shared repository authority. This file adds only the
workflow unique to the development agent speaking with the human and
integrating concurrent work. Claude reads the same shared authority through
`CLAUDE.md`; `seon-agent` and `seon-verifier` remain compatible Claude/Codex
role aliases, not architecture.

## Own integration

The top-level agent owns the active roadmap, user communication, shared-tree
coordination, final design judgment, and proof that separately completed work
forms one system. Read enough source to make that judgment. Delegation is not a
reason to keep the integrator ignorant of the code or dependency boundary.

Use an agent when a coherent result can proceed independently, parallel work
reduces latency, or independent verification materially lowers risk. Scope by
one question or outcome—not file count or context fragments. Prompts include
the PRD, exact dependencies and `reference-code/` requirement, current source,
acceptance criteria, protected paths, and expected durable report or diff.
Subagents do not delegate again.

## Preserve context durably

Compaction is expected. Repository state, not chat history, is the resume
authority:

- the active PRD roadmap records current position, dependencies, and next work;
- issue notes retain every discovered root cause and acceptance criterion;
- dated research reports retain source evidence and raw external findings;
- test reports/logs retain proof; and
- small coherent commits checkpoint integrated gains.

After compaction, re-read root and localized `AGENTS.md`, the active roadmap,
`git status`, live-agent ownership, and retained evidence. Do not rerun a
completed gate merely to recreate output unless a later change invalidated it.
Do not infer current state from target architecture or conversation memory.

## Work cadence

1. Select the next dependency-ready roadmap unit and state falsifiable success.
2. Observe current behavior, read its owner and exact dependency source, and
   probe the smallest assumption in the REPL.
3. Delegate independent research/implementation when useful; keep integration
   and overlapping shared files at the top level.
4. Review returned evidence and diffs. Ask what could still be false, and use a
   separate verifier when implementation risk warrants independent review.
5. Verify the running system in proportion to the change, update roadmap/
   issues/architecture/local authority that actually changed, and commit the
   coherent gain.
6. At handoff, report completed proof, remaining work, active lanes, protected
   evidence, and any destructive cleanup still requiring owner authorization.

## Shared-tree coordination

Agents share the working tree. Give each one disjoint owned paths where
possible. The top-level agent integrates generated/shared files such as the
issue index after concurrent writers finish. Never treat an agent's uncommitted
work as disposable, and never touch a separately owned worktree, cluster, or
protected evidence file.

Agent reports are claims to review, not completion by themselves. A useful
verification asks whether the agent read the real owner/source, whether tests
assert the invariant rather than wording, whether the REPL/database/browser
shows the expected transition, and what evidence would falsify the result.
