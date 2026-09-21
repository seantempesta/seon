---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, render, program-graph]
---

# Pulling a `:seon.fn` row in SCI returns "Restart the JVM…" instead of the row

## Resolution — 2026-09-16, sci-pull

There was no stale-Var check on the read path. The sentence is
`seon.problems/stale-var-ai`, a problems-surface row producer, SELECTED for the
pulled row: `:seon.problems/stale-var` was declared
`[:map #:seon.render{:ai … :html …} [:seon.fn/sym :seon.fn/sym]]`, and maps
are open, so that shape is satisfied by every `:seon.fn` row — `:seon.fn/sym`
is the family's `:db.unique/identity`. No `:seon.fn` shape declares an AI pair,
so the problems row won unopposed for all 4,868 of them, at any pull pattern.
The pair was dead as a selection target as well: `ai-prose` and `html-report`
call both producers directly on the rows `stale-vars` derived, and the sibling
finding rows declare no pair.

The render pair is removed from the schema. The shape, both producers and the
problems surface are unchanged. The value renderer is NOT the defect: HEAD
deliberately restored declared AI pairs there (`cecfaf428`) so attempt and
evaluation entities render through them.

Measured on `default` pid 45917, before → after at
`seon.render/schema-producers` for `{:seon.fn/sym "seon.turn/next-agent-work"}`:
`["seon.problems/stale-var-ai"]` → `[]`.

Regression: `seon.render.value-test/a-pulled-function-row-is-its-attributes-not-steering-prose`
evaluates the two-argument `seon.db/pull` through a real forked cluster SCI ctx
— without `:seon.render.value/structural? true`, the option the existing
structural twin passes and an agent never sets. Run in-process against the
pre-edit fixture base it reproduced the filed sentence verbatim and failed all
three assertions, so it is a real class regression. It has no in-process green:
that needs a fixture base built from the edited resource, i.e. the cold gate.

`doc` was probed at the same time and is CORRECT — it returns the complete
documentation map. `dir` is separately broken and now filed as
[dir-of-a-namespace-returns-an-elision-with-nothing-shown](dir-of-a-namespace-returns-an-elision-with-nothing-shown.md):
`(dir seon.turn)` returns an elision omitting 41,040 characters and showing
nothing. That is the remaining half of "the agents could not read the source".

[Exact evidence, live probes and verification boundary](../../prds/steward-platform/research/sci-pull-restart-sentence-2026-09-16.md).

## Problem

Observed 2026-09-16 on BOTH the isolated `trials` cluster and the running
`default` cluster, from SCI evaluation mode — the mode every agent evaluates
in.

```clojure
;; mode: sci, namespace my.agents.root, cluster default
(seon.db/pull (seon.db/db) '[:seon.fn/sym] [:seon.fn/sym "seon.turn/next-agent-work"])
```

returns, verbatim, the shown text

```
Restart the JVM to remove stale loaded Var seon.turn/next-agent-work; it is absent from the published program graph.
```

Not a map, not a typed `:seon.error/value` — a sentence. It reproduces for
every function symbol tried (`seon.turn/next-agent-work`,
`seon.sci.eval/documentation-value`, `seon.sci.eval/directory-value`), for
public and private functions alike, and inside a vector of three pulls each
element is replaced by its own copy of the sentence. The same pull in JVM
mode returns the row.

The entities exist: `:seon.issue/functions` refs resolve to them and the JVM
pull reads them. The sentence is a call-preparation / stale-var diagnostic
substituted for a value that a pure read produced, so this is also a law 2.4
violation — an unavailable observation must be a typed unknown, and a read
that succeeded must not be replaced by advice.

Why it matters now: the issue family's whole shape is "the block LINKS, the
agent reads the source with `doc` or `seon.db/pull`"
(`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md` §6). In the
issue-context trials the `evidence-first` candidate spent three of its opening
forms pulling the three linked functions and received this sentence three
times; it never located the code and made no edit in twenty turns.

Acceptance: a pull of a function row from SCI returns the row, with a
regression on the canonical harness pulling `[:seon.fn/sym …]` through the
agent evaluation path and asserting a map; and a genuinely stale loaded Var
is reported as a typed value, never as replacement text for a successful read.
