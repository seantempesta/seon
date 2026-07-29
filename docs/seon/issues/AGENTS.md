---
type: orchestrator
status: active
tags: [orchestrator, issue]
---

# Issue authority

This directory is Seon's one durable issue authority. Search before creating a
note, record one root cause per file, and follow the lifecycle and severity
contract in [[README]]. The active PRD orders implementation; this directory
prevents verified problems from disappearing when they are out of scope.

An open note stays at this directory's top level. A resolved or superseded note
records its commit plus behavioral/live proof and moves to `archive/`.
[[index]] is the owner's ranked schedule: every open note appears exactly once
under a named running lane or named future wave. Maintain it with triage. The
older `bin/issues-index` severity projection cannot express those destinations
and must not overwrite the schedule.

Every note contains Problem, Evidence, Owner, and Acceptance sections. Report
its path to the agent that launched you. `index.md` is the only schedule; do
not create a second backlog inside an issue.
