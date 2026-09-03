---
type: issue
status: open
severity: blocker
tags: [issue, program-graph, settlement, agent, class/p1]
---

# `analyze-form` returns a `{:seon.ns/name nil}` program row and the agent's contracted `defn` never settles

## Problem

On cluster `ctxprobe` (2026-09-03T02:15Z, run `0f813acd-…`, agent root,
model deepseek-v4-flash) root evaluated its bootstrap task — a contracted
`(defn largest …)` preceded by `;` prose lines (form ordinal 5, source
saved at `tmp/ctxprobe-run-32696-forms.edn`). Settlement recorded
`seon.fn/analyze-form violated its contract (invalid-output): missing
required key` ×8: the output tuple's second element was
`{:seon.ns/name nil}` (`:seon.error/diagnostic-offending {1 #:seon.ns{:name nil}}`),
which fails `[:maybe :seon.program/row]`. The `defn` was therefore never
persisted as a program row; root re-pulled `[:seon.fn/sym
"my.agents.root/largest"]` three times, got nothing, and burned a 44-form
run (paid) without completing the task. The core promise — "a `defn`
with `:malli/schema` becomes permanent" — is broken for at least this
form shape.

## Owner

`seon.fn/analyze-form` (`src/seon/fn.clj`, the `program-facts`/row
construction: `namespace-name` comes from `(db/pull database [:seon.ns/name]
namespace-ref)` and the row is built from it) and its caller in the
settlement path (`src/seon/cluster/loop.clj`). Suspects: the run form's
`:seon.cluster.run.form/ns` ref does not resolve to a named `:seon.ns`
row at the database value handed to analysis (an as-of value older than
the namespace row? a lookup ref by string vs symbol?), so the pull yields
`{:seon.ns/name nil}` and that map is returned AS the program row.

## Acceptance

Reproduce with the saved form 5 source on a fresh scratch cluster (a real
turn, not a door def — probes §7); the `defn` settles as a `:seon.fn`
row with its contract, `(doc my.agents.root/largest)` answers, and a
prose-prefixed form settles the same as a bare one. `analyze-form`
never returns a partial row: absence is `nil`, a resolvable namespace is
a complete row, an unresolvable namespace-ref is a typed error naming
the ref. One regression per claim; `bin/test seon.fn-test
seon.cluster.turn-test` green.

## Also observed in the same run (attribute or file separately)

Root's context carried maintenance messages saying "Restart the JVM to
remove stale loaded Var seon.operator/census-processes! …" — the
`seon.problems` stale-var detection firing for the scheduled operator
handlers on a 22-hour-old cluster while lanes edited `src/` and the edit
hook republished `current-src`. Whether the cluster's forked program rows
can legitimately drift from the JVM's loaded Vars this way is the open
question of
[partial-hot-reload-produces-mixed-code-with-no-warning](partial-hot-reload-produces-mixed-code-with-no-warning.md).
