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
records its commit plus behavioral/live proof and moves to `archive/`. Never
hand-edit [[index]]; regenerate or validate the derived projection with:

```bash
bin/issues-index
bin/issues-index --check
```

Every note contains Problem, Evidence, Owner, and Acceptance sections. Report
its path to the agent that launched you. Do not create a registry table, status
ledger, or second backlog inside an issue.
