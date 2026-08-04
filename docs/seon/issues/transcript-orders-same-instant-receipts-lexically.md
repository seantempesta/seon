---
type: issue
status: open
severity: blocker
tags: [issue, render, context, bootstrap]
---

# The pinned bootstrap renders out of order in every agent's context

## Problem

`entry-order` (`src/seon/render/transcript.clj:361-365`) sorts transcript
entries by `[(.getTime ::at) kind-rank ::id]`. `::id` is the receipt
identity STRING, `(pr-str [run-id ordinal])`
(`src/seon/cluster/run.clj:531`). When several receipts share one
millisecond — which is the normal case, not an edge case — the tie breaks
lexically, so `["r" 10]` sorts before `["r" 2]`.

The shipped bootstrap run settles all of its receipts in one instant, so
the PINNED prefix that teaches every new agent the loop grammar is
delivered scrambled: `(help)`, then the arity-error beat, then the
persistence query, and only then `(in-ns …)`, `(dir my.run)`, and the
refusal→repair pair. The bootstrap's whole pedagogical design is ordering
(`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md`
§3: form 8 is the refusal, form 9 its repair), and that ordering is exactly
what is destroyed.

Severity is blocker because it silently invalidates the bootstrap
experiment the active plan is built on: an agent graded on whether the
tutorial transfers is being shown a different tutorial than the one
designed, and the scores would be attributed to content rather than to
ordering.

## Evidence

Live on scratch cluster `xcurate0804`, 2026-08-04, agent `root`.

All 13 bootstrap receipts share one instant:

```clojure
{:count 13 :distinct-instants 1}   ; every :seon.cluster.eval/at = 1785875239137
```

The rendered pinned prefix, in projection order:

```text
["bootstrap:root" 0]  my.agents.root=> (help)
["bootstrap:root" 10] my.agents.root=> (largest)
["bootstrap:root" 11] my.agents.root=> (largest [])
["bootstrap:root" 12] my.agents.root=> (seon.db/q '[:find ?spec …
["bootstrap:root" 1]  my.agents.root=> (in-ns 'my.agents.root)
["bootstrap:root" 2]  my.agents.root=> (dir my.run)
…
["bootstrap:root" 9]  my.agents.root=> (largest [{:label "a" :amount …
```

Reproduced independently with a synthetic 12-form run seeded at one
instant (`docs/prds/sci-execution-runtime/research/scripts/curation-supersession-probe-2026-08-04.clj`,
run `probe:M`), which
renders `0, 10, 11, 1, 2, 3, …, 9`.

## Owner

`src/seon/render/transcript.clj` — `entry-order`.

## Acceptance

Same-instant entries order by the run's `:seon.cluster.run/opened-at` and
then by the integer `:seon.cluster.eval/ordinal` /
`:seon.cluster.run.form/ordinal` already present on every receipt and
form, with the id string used only where no ordinal exists (messages).
A regression seeds one run whose receipts share a single instant and
asserts the rendered order equals the plan's ordinal order for both the
AI and HTML twins, with at least ten forms so the 2-vs-10 lexical case is
exercised. Verified live by re-reading a fresh agent's pinned bootstrap
prefix on a scratch cluster.

Related: the curated-session supersession design depends on this, because
a curated run committed in one transaction produces exactly this shape —
`docs/prds/sci-execution-runtime/research/session-curation-transcript-supersession-opus-2026-08-04.md`
§6.5.
