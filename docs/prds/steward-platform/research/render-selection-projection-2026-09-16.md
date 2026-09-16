---
date: 2026-09-16
lane: render-selection
surface: render-selection
---

# Render selection asks the database value it was handed

Two bounded items: the projection precedence at the render selection seam,
and the stale `seon.error/render-ai` golden in `seon.html-views-test`.

## 1. Selection derived its projection from the SCI ctx

Issue:
[render-selection-reads-its-projection-from-the-ctx-not-the-handed-database](../../../seon/issues/render-selection-reads-its-projection-from-the-ctx-not-the-handed-database.md),
filed by the `sci-pull` lane.

### Reproduced first — default, pid 95853, jvm mode

The cluster's own database value and its own ctx, with a SECOND database
value carrying the same projection minus one declared pair
(`:my.plan.item/item`'s `:seon.render/ai`):

```clojure
(:seon.render.selection/selected (seon.render/selection request))                 ; db carries the pair
;; => seon.plan/render-item-ai
(:seon.render.selection/selected (seon.render/selection (assoc request :seon.db/db db-no-pair)))
;; => seon.plan/render-item-ai   <- the handed value's projection was never read
```

Both answers came from the ctx. The `:seon.db/db` the request carries, and
the projection on it, were not consulted anywhere in selection.

### Fix — one derivation, stated once

`seon.render/request-projection` (`src/seon/render.clj:70`) is now the single
place selection asks for a projection, with the precedence `seon.db` reads
already use:

1. the projection CARRIED BY the request's database value
   (`seon.db/carried-projection`, `src/seon/db.clj:981`; values carry it at
   `src/seon/db.clj:141`; reads prefer it at `src/seon/db.clj:950`);
2. a `:seon.schema/projection` supplied on the request;
3. only when neither is present, the acquired SCI context's projection.

It is total: a request with no database, no supplied projection and no ctx
returns nil, which selection already reads as "nothing is declared". That
totality is what the ctx-less floor render needed the old `some->` guard for
(`project-node*`), so the guard dissolves into the derivation.

Every selection seam that holds the request now calls it — namespace
candidates, `selection`, `selection-inspection`, the schema stage's
projection, `call-static-evidence`, `invoke-selected`, `project-node*`,
`raw-output`, `render-form`, and the source-intent check. Two ctx-only
readers are deliberately unchanged: `call-cache-evidence` and
`retained-program-current?` ask whether a RETAINED call's program and
projection evidence still match the acquired CONTEXT, which is a question
about the ctx, not about the value being rendered.

Also dropped: the now-unused `ctx` destructuring in `selection` and
`selection-inspection`.

### What `request-profile` deliberately keeps

`seon.render/request-profile` sits three lines below the new helper and asks
a DIFFERENT question — which config facts the presentation profile comes
from — with its own order: supplied projection, then the database's carried
one, then `schema/handed-projection`, and a typed missing-projection refusal
when none exists. It was NOT folded into `request-projection`, because that
helper also answers from the SCI ctx: a request carrying a ctx but no
projection would then derive a profile where the peer's `15a15e9c1`
regression requires the refusal. Unifying the two needs that refusal's
boundary decided first; the divergence is recorded here rather than resolved
quietly.

Built on the peer's `15a15e9c1` (one render profile per turn, carried on
every evaluation request), which is untouched: `request-profile` keeps its
own carried-profile short circuit and its typed missing-projection refusal.

### Live proof — default, pid 95853, jvm mode

Precedence, on the live cluster's projection (`p` = carried, `p-no-pair` =
same projection with that one pair removed):

```clojure
(seon.render/request-projection {:seon.db/db db-no-pair :seon.sci.eval/ctx ctx}) ; => p-no-pair
(seon.render/request-projection {:seon.schema/projection p-no-pair
                                 :seon.sci.eval/ctx ctx})                        ; => p-no-pair
(seon.render/request-projection {:seon.sci.eval/ctx ctx})                        ; => p (ctx)
(seon.render/request-projection {:seon.render/value value})                      ; => nil
```

and the selection A/B that the defect made unanswerable:

```clojure
(#'seon.render/schema-producers p        request value :seon.render/ai) ; => [seon.plan/render-item-ai]
(#'seon.render/schema-producers p-no-pair request value :seon.render/ai) ; => []
```

No ctx was mutated to ask either question.

### Regression

`test/seon/render_simplification_test.clj` —
`selection-asks-the-handed-database-value-s-projection`. One entity, one
ctx, two database values: the fixture's own, and the same value carrying a
projection with this value's declared AI pairs removed. The second
projection is DERIVED from the first through
`seon.schema/matching-shapes-in`, never a hand-written roster, so it cannot
drift from what actually declares a pair. It asserts both selections
(`seon.plan/render-item-ai` vs the floor `seon.render.value/render-ai`) and
that the ctx still declares the pair afterwards — the ctx-mutation the issue
says a prober should not need.

## 2. `fault-pairs-preserve-ai` — the GOLDEN was stale

`seon.html-views-test/fault-pairs-preserve-ai` failed 2 of 14 (batch 54):

```
expected: (= (golden :fault) (error/render-ai fault))
actual: (not (= "#:seon.error{:kind :example/failed, :message \"Connection lost\"}" "Connection lost"))
```

The golden, not the render function, is stale. `seon.error/render-ai` used
to be literally `(pr-str (merge …))` (`597ef8b68:src/seon/error.clj:1310`),
and `test/seon/fixtures/html_views_ai.edn` froze that dump. HEAD's
`render-ai` (`src/seon/error.clj:1558`) renders the fact's data — refusal
text, else the message, plus recorded occurrences — which is what
`:seon.render/ai` means: an entity's AI projection through its declared
pair. An attribute dump is what a value with NO declared pair prints, so the
golden was asserting the absence of the pair it is named for. The
error-graph lane (`45998fdbf`…`3f4f0cdf2`) and the peer's `b166c4246` /
`42661e5b0` moved messages onto occurrences and changed `faults-form`
around it; nothing in that arc argues for the dump.

Live, pid 95853, jvm mode:

```clojure
(seon.error/render-ai {:seon.error/kind :example/failed
                       :seon.error/message "Connection lost"})
;; => "Connection lost"
(seon.error/render-ai (assoc fault :seon.error/occurrence-count 3))
;; => "Connection lost Occurrences: 3."
```

Fixed on the golden side (both `:fault` and `:faults` entries), and the test
now also asserts the BEHAVIOUR those bytes stand for — that the rendered
text is not the `#:seon.error{…}` dump, and that occurrence evidence
reaches it — so neither side can go stale silently again.

## Verification boundary

- Live: default pid 95853, jvm mode, read-only probes plus the adopted
  `src/seon/render.clj`. No cluster was started, stopped, reforked or reset.
- In-process: `seon.test/run` on the named tests through `seon.test`'s own
  loader (results below).
- NOT verified here: the batched gate. `seon.fn/tests-reaching` (and
  therefore `seon.test/check`) was refusing on default while this landed —
  a foreign defect in the peer's gate-set derivation — so no reach-derived
  selection was taken. Gate request:
  `tmp/orchestrator/gate-requests/render-selection.txt`.
