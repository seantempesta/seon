---
type: issue
status: resolved
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

## Resolution

Resolved in commit `2e6f1344e`. Receipt and plan-form projections now carry
the run opening instant and numeric ordinal into one fixed-width ordering key;
the receipt id remains only the final deterministic tie-break. No visibility
or supersession query changed.

The regression commits 13 receipts at the same instant and proves that both
the AI and HTML projections preserve ordinal order across the `2`/`10`
boundary. The owning namespace gate passed:

```text
bin/test seon.render.transcript-test
Ran 9 tests containing 116 assertions.
0 failures, 0 errors.
```

The changed-test selector widened to 26 namespaces and did not complete its
operator and writer boundaries. Its remaining failures were outside this
owner: the foreign in-flight Flow/config work left the work launcher without
required config facts and omitted `:seon.config.flow.io/queue-depth` and
`:seon.config.flow.io/concurrency`. Per the shared-tree rule, that wider gate
awaits the foreign lane and did not block the coherent owner commit.

Live proof used newly published current-src commit
`6a72533e-9449-5dca-b6bd-6208381d94cc` (digest
`3dacc0abca13979d7b0437c2a9c1706a8ee4a2c92e51859cc56ea5cb2b5fe665`)
and fresh scratch cluster `transcript-order-live-0804`. Agent `root` had 13
bootstrap receipts with one distinct `:seon.cluster.eval/at`; the database
plan ordinals and ordinals recovered from `render-session-ai` were both:

```clojure
[0 1 2 3 4 5 6 7 8 9 10 11 12]
```

The rendered sequence therefore matches the database plan order, including
the refusal/repair forms at ordinals 7 and 8 and both two-digit ordinals.
