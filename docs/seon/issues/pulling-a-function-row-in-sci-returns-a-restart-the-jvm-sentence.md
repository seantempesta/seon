---
type: issue
status: open
severity: blocker
tags: [issue, sci, render, program-graph]
---

# Pulling a `:seon.fn` row in SCI returns "Restart the JVM…" instead of the row

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
