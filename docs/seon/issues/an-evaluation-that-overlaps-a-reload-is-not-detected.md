---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, sci, reload, agent, adoption]
---

# An agent evaluation that overlaps a reload is not detected

## Evidence (source, 07a03cdd4)

- AGENTS ("One JVM, many realities") requires that work overlapping a reload detects it, comparing the loaded program
  identity at start and at end, and pushes back.
- `seon.sci.eval/loaded-identity` is read by the test runner (`src/seon/test.clj:947`, `:1153`, `:2053`, `:2183`),
  by `seon.db`'s builder (`src/seon/db.clj:1545`) and by cluster readiness (`src/seon/cluster.clj:625`). No
  evaluation path reads it. A run through `seon.cluster.agent/evaluate-source!`, or a hosted branch run, compares
  nothing across its evaluation.
- The P3b design (`docs/research/agent-platform/one-repl-language-2026-09-25.md` §2.6, finding 2) said that "the
  existing adoption detection still refuses an overlapping evaluation". No such detection exists, so the sixth
  finding-6 regression (reload overlap refuses by name) has nothing to exercise.

## Owner and acceptance

The owner is the evaluation stage (`seon.sci.eval/evaluate`) or its settlement.

Acceptance: an evaluation records the loaded identity it started under. When settlement sees a different one, it
refuses or marks the evaluation by name rather than recording it as current. One regression: a reload between an
evaluation's start and its settlement yields that named result.
