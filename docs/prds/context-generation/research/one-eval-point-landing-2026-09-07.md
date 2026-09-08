---
type: research
status: complete
date: 2026-09-07
tags: [research, eval, cache, render, sci, program-graph]
---

# One evaluation point and one cache — the landing

What the `one-eval-point` lane changed for PRD step 3
([the agent record and the REPL response](../plan/agent-record-and-repl-response-prd-2026-09-07.md)
§7.3), against the evidence in
[the eval points and caches census](eval-points-and-caches-census-2026-09-07.md)
and [the audit](audit-repl-and-record-2026-09-07.md) §4, under ruling 71
([ledger](../plan/design-ideas-ledger-2026-08-13.md)).

## 1. What the page used to be, and is now

The census's E3 — a *renderer* that forked a turn context, parsed agent source
and evaluated it during a render (`src/seon/render/web.clj:1517-1592` at
`711a7954d`) — is gone. The renderer now calls one function:

```clojure
(loop/preview-sources                     ; src/seon/cluster/loop.clj
 {:seon.cluster.loop/cluster …            ; forks, plans, evaluates
  :seon.db/db … :seon.sci.eval/ctx …
  :seon.cluster.agent/id … :seon.ns/name …
  :seon.cluster.reply/text source :seon.sci.admit/caps …})
```

`preview-sources` owns the fork (`sci.eval/fork-for-turn`), the parse
(`planned-sources` → `reply/sources` → `seon.sci.reader/read`) and the
evaluation (`evaluate-sources`) — the same three steps `resume-turn` runs for
an ordinary turn. `seon.render.web` no longer requires `seon.sci.eval` at all.

Kept, because the audit §4 verified it and ruling 59c requires it: the preview
runs in the ASSIGNED agent's fork with its defs rehydrated, it passes no run
id, so nothing settles, no evaluation entity exists, and no `result/eN` handle
is bound.

## 2. Which cache survived, and why

**The invocation cache survived as the one store of a preview; the render-call
cache stayed what it always was — one slot's presented bytes.**

The census (§2.6) recommended exactly this, and reading the code confirmed the
reason it gave and added two more:

1. **The invocation key IS ruling 71's identity.** `invocation-cache-key`
   (`src/seon/render.clj:634`) is `[producer output program-snapshot-identity
   projection-fingerprint (hash selection-input)]` — code + input, with no
   database identity in it; validity is `same-invocation-evidence?` plus
   `db/read-evidence-current?`. `:seon.render.call/id` is a *slot address*
   (`[:seon.render/html [::fleet-oversight]]`), not an identity of code.
2. **It is already the durable home of a preview between rendering and Add.**
   `context-change-pass` (`src/seon/render/web.clj`) finds the evaluated
   preview by scanning the invocation store for its `source-run-id`; deleting
   that store would have deleted "Add to context".
3. **The call store is not a duplicate for ordinary renders.** An invocation
   entry holds the producer's `raw` output; the call entry holds
   `(present-output …)` of it — `print/fit` + `emit-both`, a full walk of the
   value that is per-slot and profile-dependent. Only in the source/AI preview
   path were the two literally the same bytes, because the old
   `render-source-call` wrote its finished transcript text into both.

So the collapse is: the evaluated forms live in the invocation entry and
nowhere else; the call entry keeps the pointer
(`:seon.render.call/invocation-key`), the run identity it displayed, and its
own presented bytes. `evaluated-invocation` (`src/seon/render/web.clj`) is the
pointer dereference the debug page's comparison now uses.

## 3. Deleted

| Deleted | Was at `711a7954d` | Replaced by |
|---|---|---|
| `render-source-call`'s fork, parse and evaluation | `src/seon/render/web.clj:1570-1592` | `seon.cluster.loop/preview-sources` |
| `reusable-evaluated-preview` — the third hand-written reuse predicate | `src/seon/render/web.clj:1491-1514` | the ordinary invocation cache (`render/reusable-invocation`) |
| `current-read-evidence` | `src/seon/render/web.clj:2097` | `render/refresh-read-evidence`, now public with a contract |
| the preview payload on the call entry | `render-source-call`'s `enrich` | the invocation entry + pointer |
| `::invocations` wiped on every evaluation wake | `invalidate-runtime-derived-state` | the entry's own evidence decides — an install replaces the program snapshot, so every entry misses on its own terms |
| the `:seon.cluster.loop/evaluate` config fact | `src/seon/cluster.clj:2498`, `resources/seon/schemas/seon.cluster.loop.edn:18`, `src/seon/cluster/loop.clj:1553` | a direct `sci.eval/evaluate` call the program graph indexes |

Wiping the invocation store on an evaluation wake was the reason
`reusable-evaluated-preview` existed at all: the store the ordinary path would
have hit was emptied out from under it, so a second predicate was written to
re-read the same preview from the call store. Both halves are gone together.

## 4. The evaluator is a Var again, and the graph can see it

`evaluate-sources` resolved its evaluator with
`(requiring-resolve (:seon.cluster.loop/evaluate cluster))`, so the busiest
evaluation site in the system had no `:seon.fn/calls` edge — the census's own
"reports health because its subject is absent" trap. It is now a direct call.

The config key and its schema entry are deleted. Tests that pin an exact
evaluation replace the Var's root value with `with-redefs`
(`test/seon/cluster/turn_test.clj` `with-cluster`,
`test/seon/cluster/agent_test.clj` `with-connection`) — which reaches proc
threads, unlike a dynamic binding, and agent_test passes the stand-in as a Var
so a test may still redefine the stand-in itself.

### The regression (census §5.3), in `test/seon/fn_test.clj`

- `sci-evaluation-has-one-first-party-owning-namespace` — every non-test
  first-party caller of `sci.core/eval*` is in `seon.sci.eval`. The allowed set
  is derived from the callers' own namespace, never enumerated; "non-test" is
  derived from `:seon.test/ns`, never from a name ending in `-test`; and it
  asserts the edge set and the test-namespace set are both non-empty first, so
  an absent subject fails rather than passes.
- `agent-source-reaches-the-evaluator-through-one-visible-path` — the turn's
  edge `evaluate-sources → seon.sci.eval/evaluate` exists; `evaluate-sources`
  has exactly two production callers, `resume-turn` and `preview-sources`; and
  `preview-sources` has exactly one, `seon.render.web/render-source-call`. Any
  new evaluator of agent source fails the middle assertion.

## 5. Ten loads write nothing

`seon.render.web-test/inspecting-the-page-writes-no-run-evaluation-or-fault-facts`
now performs ten GETs of the debug page and ten reads of its feed and asserts
`db/basis-t` is unchanged across all of them, keeping the named counters
(runs, evaluations, forms, faults) so a failure says which family moved. The
basis is the total measure: a render-cost fact or a committed render fault
moves `:t` while every named counter stays put — the audit measured exactly
that on 2026-09-07 (`:t` advanced by ten across two page loads).

## 6. Measurements

All numbers taken on the live development cluster `juniper-context`
(root `tmp/juniper-context-live`, pid 34741, web `http://127.0.0.1:7766`), by
reading `/ns/my.agents.juniper/debug` and its Datastar feed over HTTP. The
census's own baseline numbers are database timings (record pull 331 µs,
evaluations query 104 µs, `read-evidence-current?` 1 µs); nothing here touches
that path, and it was not re-measured.

**Before** — the JVM serving commit `6a9f55ee-1c87-57de-98b9-e1211af1db8d`
(the tree at `711a7954d`), ten feed reads:

| | first byte | paint complete | bytes |
|---|---|---|---|
| median of ten | 0.003 s | 0.004 s | 228,726 |
| range | 0.003–0.254 s | 0.003–0.254 s | 97,837–228,726 |

**After** — the same JVM after `bin/seon --root tmp/juniper-context-live init
--dev juniper-context` adopted commit `6a9f6177-df85-5268-b35c-157bac81b41d`
(convergence verified by query: the cluster's `:seon.source/commit-id` equals
the commit the publication printed):

| | first byte | paint complete | bytes |
|---|---|---|---|
| first paint after the adoption wake | 1.158 s | 1.158 s | 272,307 |
| each of the nine repaints after it | 0.002 s | 0.002 s | 272,307 |

The page shell is 3,203 bytes and answers in 1.5–3.5 ms; all content arrives on
the feed. The byte growth (228,726 → 272,307) is the adopted commit's content,
not this change.

**What is and is not compared.** The steady repaint is the only apples-to-apples
before/after pair, and it did not regress: 3–6 ms before, 2 ms after. A first
paint after a code-observation wake was not captured on the pre-change code —
that measurement needs the old code live, and this lane may not republish the
development cluster backwards — so 1.158 s stands as a recorded number, not as a
comparison. It is also the case this change is aimed at: before, that wake
emptied the invocation store and every preview on the page re-evaluated; now an
adoption still misses (it replaces the program snapshot, which is correct) while
an evaluation wake that installs nothing reuses.

**Ten loads write nothing, live.** Ten GETs of the debug page interleaved with
ten reads of its feed, with `:t` read through `eval_clj` in `jvm` mode before
and after:

```clojure
;; before and after, identical
{:t 536871259, :runs 9, :evals 9, :faults 14}
```

This is the fact the audit could not get on 2026-09-07, when `:t` advanced by
ten across two page loads because the page was committing a render fault every
pass. The page's ten AI previews render `#:seon.repl{:value …, :ms N}` and none
carries a `:result result/eN` handle, which is ruling 59c holding in the live
page.

## 7. Tallies

`bin/test` over
`seon.render.web-test seon.render-coverage-test seon.render-simplification-test
seon.cluster.loop-test seon.cluster.turn-test seon.render.transcript-test
seon.fn-test seon.render.web-performance-test seon.render-source-test
seon.cluster.agent-test`, run twice: once in this tree, once in a detached
worktree at `711a7954d` (`git worktree add tmp/one-eval-baseline HEAD`, its
`reference-code` symlinked to the main tree's submodules), so attribution is
measured rather than asserted. The spec named `seon.render-test`, which does not
exist; `seon.render-coverage-test` and `seon.render-simplification-test` were
run in its place.

| tree | ran | reds |
|---|---|---|
| baseline `711a7954d` | 246 tests / 1688 assertions | 25 |
| this lane, focused re-run (`turn` + `render-simplification` + `web` + `fn`) | 172 tests / 1117 assertions | 12 |

**New reds: none.** Every red in the focused re-run is a member of the
baseline's 25. The full first pass did produce four of its own — three
`seon.cluster.turn-test` reds and one shape change in
`seon.render-simplification-test` — all from this lane's own test edits, all
fixed before the re-run:

- two `(assoc cluster)` calls left with no key by the mechanical removal of the
  deleted config key, which is an arity exception, not a test failure;
- `with-cluster fake-evaluate` applied to the wrong `deftest` (the property
  helper `generated-turn-agrees-with-durable-facts?` holds the `with-cluster`,
  and the wrap landed on the deftest that followed it);
- `seon.render-simplification-test` handed `:seon.cluster.loop/cluster {}`,
  which the deleted `::evaluator-absent` guard used to catch. It now reaches a
  real evaluation and the fixture carries a real handle. The NPE it produced on
  the way through — an absent cap read straight into a `long` cast — is filed as
  [absent-admission-cap-crashes-the-print-walk](../../../seon/issues/absent-admission-cap-crashes-the-print-walk.md).

New tests, both green: the two census regressions in `seon.fn-test`, and the
strengthened `seon.render.web-test/inspecting-the-page-writes-no-run-evaluation-or-fault-facts`.

## 8. Unfinished

- **The call store still holds its slot's presented bytes.** That is what every
  page slot holds, including root acquisition, fragments and selection
  inspections, which have no invocation at all — so it is not a second copy of
  the invocation's value (the invocation holds `raw`, pre-`present-output`). The
  one place the two were literally the same bytes was the preview, and that is
  fixed. Making `::calls` a pointer-only store would mean re-running
  `print/fit` + `emit-both` on every fast-path hit, or moving presentation into
  a key that does not carry the render profile; neither is an improvement, and
  neither was attempted.
- **`seon.render-simplification-test/authored-source-invocation-reuses-one-stored-run-across-presentations`
  is still red** with six stale assertions from the pre-59c design (a preview
  submitting a durable run). Diagnosed and recorded on
  [its issue note](../../../seon/issues/debug-source-execution-invalidates-its-own-preview.md);
  the oracle rewrite belongs with that note's owner.
- **`seon.render.web/render` answered `unknown` to the oversight ping** on the
  development cluster while the page itself served every request in
  milliseconds. Not investigated by this lane; recorded because a proc that does
  not answer within its ping window is never healthy.
- **A first paint after a code wake was not measured on the pre-change code**
  (§6).
