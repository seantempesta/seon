---
type: research
status: active
tags: [research, agent, cljs]
---

# Override sandbox verification — #8 needs ZERO new attributes

## TL;DR

**Verdict: YES — per-function core override is achievable with ZERO new
attributes.** The single sentence why: a stored override is an ordinary
`:seon.fn` upsert that `core-index-tx`'s `have-fns` presence-dedup never
re-touches (PROVEN: re-index returned 998 rows, `reply!` NOT among them, the
override's tx stayed `536870915` and its source stayed `:OVERRIDDEN`), so the
ONLY obstacle is the blanket core replay-skip at `client.cljs:665` — replacing it
with a provenance test ("drop a core-ns row only when its CURRENT source's tx
origin IS `:core-seed`") lets the override into the replay set and shadows the
compiled var via the already-proven late-binding. The PRD's stated obstacle
("`index-core!` re-upserts the compiled version every boot, bumping its tx past
any stored override") is empirically FALSE; the `:seon.fn/override-target` attr,
the override sort-tier, and the stacking-conflict refusal are all unnecessary.

The audit (Finding 3) wins on every testable point. The only genuine residual
constraints are NOT new mechanisms: (a) the re-export alias hazard (a CLJS
late-binding fact, fixed with a one-line alias re-eval / audit, not an attr), and
(b) kernel-target chicken-and-egg (a loud warning, not an attr). One correction
to the audit's wording: a bare `[:db/retract … :seon.fn/source <val>]` does NOT
auto-revert to the `:core-seed` value — it leaves the row with no current source.
The clean revert is `:db/retractEntity` (or re-asserting compiled source); the
boot reindex then re-seeds the compiled row for free.

All proofs ran in the live pod's `default` seon_cljs session. `@seon.repl/!conn`
is nil (no agent conn bound), but `seon.db/*conn*` IS root-bound to the LIVE
cluster conn — so every DB test below ran against a FRESH isolated
`(seon.client/open-agent-conn!)` `:memory` conn passed explicitly to
`transact!`/`query`/`core-index-tx`; no write ever reached the cluster store, and
the live `seon.agent.message/reply!` var was never redefined (verified:
`(fn? (lookup-value 'seon.agent.message/reply!)) => true` at the end).

Name mapping (audit uses pre-rename names): `substrate-*` → `core-*`,
`:substrate-seed` → `:core-seed`, `index-substrate!` → `index-core!`,
`substrate-index-tx` → `core-index-tx`, `substrate-ns-set` → `core-ns-set`,
`prune-substrate-ghosts!` → `prune-core-ghosts!`.

## Claim 1 — cross-ns override is late-bound (re-proof of PROOF 1)

Conn-free; eval'd into scratch `sandbox.*` nses against `@seon.repl/!compile-state`.

Forms (chained promises, result captured in `!p1`):

```clojure
(seon.eval/eval CS "(ns sandbox.target)" {:ns 'cljs.user :analyze-deps? false})
(seon.eval/eval CS "(defn f [] :v1)" {:ns 'sandbox.target :analyze-deps? false})
(seon.eval/eval CS "(ns sandbox.caller (:require [sandbox.target]))" {:ns 'cljs.user :analyze-deps? false})
(seon.eval/eval CS "(defn call-f [] (sandbox.target/f))" {:ns 'sandbox.caller :analyze-deps? false})
(seon.eval/eval CS "(sandbox.caller/call-f)" {:ns 'cljs.user :analyze-deps? false})   ; r1
(seon.eval/eval CS "(defn f [] :v2-overridden)" {:ns 'sandbox.target :analyze-deps? false})
(seon.eval/eval CS "(sandbox.caller/call-f)" {:ns 'cljs.user :analyze-deps? false})   ; r2
```

Returned value of `@!p1`:

```clojure
{:before :v1, :after :v2-overridden}
```

`sandbox.caller/call-f` was NOT recompiled, yet picked up the redefinition of
`sandbox.target/f`. Confirms the dev `:none` build emits cross-ns calls as fresh
reads of the munged global path. This is the mechanism that makes a replayed
override shadow a compiled caller.

## Claim 2 — re-index does NOT clobber/bump a stored override (THE CRUX)

Fresh `:memory` conn. Seeded `seon.agent.message/reply!` as a `:seon.fn` row under
`{:seon.db/origin :core-seed}` (mimicking `index-core!`), then upserted an
override source for the SAME sym under `{:seon.db/origin :agent}`, then ran the
REAL `seon.client/core-index-tx` and transacted whatever it returned under
`:core-seed` (exactly what boot does).

`@!c2`:

```clojure
{:after-seed     [["(defn ^:async reply! [m] :COMPILED-CORE)" 536870914]]
 :after-override [["(defn ^:async reply! [m] :OVERRIDDEN)"    536870915]]
 :index-tx-count 998
 :index-tx-has-reply? false
 :after-reindex  [["(defn ^:async reply! [m] :OVERRIDDEN)"    536870915]]
 :done true}
```

Decisive:

- `core-index-tx` returned 998 rows but `:index-tx-has-reply? false` — `reply!`
  was dropped by the `have-fns` presence-dedup (`client.cljs:1427`/`1457`:
  `(contains? have-fns (:seon.fn/sym row))`).
- `:after-reindex` source is STILL `:OVERRIDDEN` and its tx is STILL `536870915`
  — IDENTICAL to `:after-override`. The re-index neither re-asserted nor bumped
  the override.

**The PRD's stated obstacle is FALSE.** `index-core!` does not re-upsert a sym
already present in the store; once the override exists, boot re-index is a no-op
for that sym. The compiled var would only ever win because the override never
gets to REPLAY — which is exactly what Claim 3 fixes.

## Claim 3 — provenance-based replay-skip lift works

### 3a — current source's tx carries a non-`:core-seed` origin

Same conn, queried with the `get-else … :seon.db/origin` pattern
(`eval.cljs:1149` style):

```clojure
'[:find ?src ?origin
  :where
  [?e :seon.fn/sym "seon.agent.message/reply!"]
  [?e :seon.fn/source ?src ?tx]
  [(get-else $ ?tx :seon.db/origin :seon.db/untagged) ?origin]]
```

`@!c3`:

```clojure
{:current-source-origin [["(defn ^:async reply! [m] :OVERRIDDEN)" :agent]]
 :reply-ns-in-core-set?  true}
```

The override's current source tx is tagged `:agent`, and `:seon.agent.message`
IS in `(core-ns-set)` — so TODAY's blanket skip (`client.cljs:665`) drops it.

### 3b — simulated lifted filter admits the override

Replicated `query-program-graph-entries`' base query (joining `?origin` via the
same `get-else`), built the entry maps, and compared two skip predicates:

- variant A (today): `(remove core?)`
- variant B (lifted): `(remove #(and (core? %) (= :core-seed (:origin %))))`

`@!c3b`:

```clojure
{:reply-entry {:kind :fn :ident "seon.agent.message/reply!"
               :source "(defn ^:async reply! [m] :OVERRIDDEN)"
               :tx 536870915 :origin :agent}
 :reply-is-core-ns?   true
 :reply-in-replay-A?  false
 :reply-in-replay-B?  true
 :total-fn-entries    192}
```

Under the lifted filter the override row ENTERS the replay set; under today's
filter it is dropped. Combined with Claim 1 (replay-one! → `seval/eval source
{:ns 'seon.agent.message}` shadows the compiled var via late-binding), this is
the complete override path with no new attribute — the `:agent` origin on the
current source's tx is the sole discriminator. (The compiled var is always present
from module load, and replay runs after `core-index-tx`, so ordering vs the index
is irrelevant — the audit's step-2 "no new tier" holds.)

## Claim 4 — retract reverts to compiled (with a correction to the audit's wording)

First tested the audit's literal claim — retract just the override SOURCE VALUE:

```clojure
[[:db/retract [:seon.fn/sym "seon.agent.message/reply!"] :seon.fn/source
  "(defn ^:async reply! [m] :OVERRIDDEN)"]]
```

`@!c4`:

```clojure
{:current-after-retract []}
```

CORRECTION: a single-cardinality `:seon.fn/source` retract does NOT auto-revert
to the prior `:core-seed` value. The entity is left with NO current source. The
audit's phrasing ("the current source datom reverts to the substrate-seed one via
history") is mechanically wrong — datahike does not roll a single-card attr
forward to an older value. The OUTCOME the audit wants still holds though: with no
current source the row leaves the replay set, so nothing shadows the compiled var
and it stands (it was never gone — module load defines it).

History (via `datahike.api/history`) retains every version, confirming nothing is
lost:

```clojure
[["(defn ^:async reply! [m] :COMPILED-CORE)" 536870914 true]
 ["(defn ^:async reply! [m] :COMPILED-CORE)" 536870915 false]
 ["(defn ^:async reply! [m] :OVERRIDDEN)"    536870915 true]
 ["(defn ^:async reply! [m] :OVERRIDDEN)"    536870917 false]]
```

The CLEAN revert path is `:db/retractEntity` (drop the whole override row). After
that, the next boot's `core-index-tx` finds `reply!` ABSENT in `have-fns` and
re-emits the compiled row for free — self-healing:

`@!c4b`:

```clojure
{:after-entity-retract []
 :reindex-has-reply?    true
 :after-reseed [["(defn ^:async reply!\n  \"Reply to whoever woke …" 536870921]]}
```

So `retract-override!` should `:db/retractEntity` the override row (or re-assert
the compiled source), not bare-retract the source value.

## Claim 5 — does the evidence contradict the PRD? (audit wins)

**The sandbox evidence CONTRADICTS the PRD and CONFIRMS the audit.** Specifically:

- PRD §"How replay-skip changes" tier-2 / §"Data shapes"
  `:seon.fn/override-target` exist to defeat an obstacle (boot re-index bumping
  the override's tx) that Claim 2 shows DOES NOT EXIST. `have-fns` presence-dedup
  means a present sym is never re-transacted, so there is nothing to out-rank with
  a new attr or a new sort-tier.
- The discriminator the override needs already lives on the tx (`:seon.db/origin`)
  and is already read by `tee-registered-schema!` (`eval.cljs:1153`) and
  `prune-core-ghosts!` (`client.cljs:1531`). Reuse it; do not register
  `:seon.fn/override-target` / `:seon.fn/override-origin`.
- The "two packages overriding the same target is a conflict the install verb must
  refuse" machinery contradicts the user's explicit "upsert the original function
  and call it a day" — upsert IS last-write-wins; that is the semantics.

Genuine residual needs (NOT defeated by the sandbox, but NOT new attributes
either):

1. **Re-export alias hazard (PROOF 3).** `(def reply! message/reply!)` in
   `seon.agent` value-captures at def-time and does NOT track an override of the
   defining var. This is a CLJS late-binding fact independent of storage. Fix:
   after an override of a defining var lands, re-eval the alias `def` (or audit
   `seon.*` for `(def x other-ns/x)` re-exports). A one-line alias re-eval, not an
   attribute. (`:seon.fn/alias-of` tagging is one cheap way to find them, but the
   override mechanism itself needs nothing.)
2. **Kernel-target chicken-and-egg.** Overriding `seon.eval/eval`,
   `replay-program-graph!`, `db/transact!`, `schema/register!`, etc. can no-op the
   override on the NEXT boot (the store is read THROUGH those fns). The
   fallback-to-compiled net survives it; the mitigation is a LOUD warning at
   install + `:repl`-only posture, not a new attribute.
3. **`:override-dir` is not in the origin enum.** `seon.db/origin` is
   `[:enum :user :agent :system :replay :core-seed :test-run]` (`db.cljs:189`) —
   there is no `:override-dir` member. The PRD/audit both reference an
   `:override-dir` origin. If a distinct origin for the override-dir delivery
   vehicle is wanted, ADD it to that one enum (one-line). But it is OPTIONAL — an
   `:agent`-origin override already passes the provenance test in Claim 3; the
   only reason to mint `:override-dir` is audit/policy provenance, not mechanism.

## Recommendation — the minimal #8 change set (zero new attrs)

1. **`query-program-graph-entries` (`client.cljs:628-694`)** — the single
   load-bearing change:
   - Join the current source's tx origin into the base query: add
     `[(get-else $ ?tx :seon.db/origin :seon.db/untagged) ?origin]` to the
     `:where` and `?origin` to `:find`, carry it into the entry map
     (`{:kind … :ident … :source … :tx … :origin …}`).
   - Replace the blanket core skip at **line 665**
     `(remove #(contains? (core-ns-set) (entry-ns-kw %)))`
     with the provenance-aware skip
     `(remove #(and (contains? (core-ns-set) (entry-ns-kw %))
                    (= :core-seed (:origin %))))`.
   That's it. Already-proven: this admits an `:agent`/`:override-dir`-origin
   override of a core sym into the replay set (Claim 3b) and drops it again the
   moment the row is retracted/reseeded (Claim 4).
2. **No sort-tier.** The existing `(sort-by (juxt #(if (= :ns …) 0 1) :tx))` at
   line 693 is sufficient — the compiled var exists from module load regardless of
   replay order, and an override that calls through to agent corpus already
   replays after `:ns` rows in tx order. Do NOT add the PRD's
   `:ns`=0/agent=1/override=2 tier.
3. **No `:seon.fn/override-target` / `:seon.fn/override-origin`.** Delete those
   from the PRD. Provenance is on the tx.
4. **`retract-override!` = `:db/retractEntity` on the override row** (NOT a bare
   source-value retract — Claim 4). Boot reindex then re-seeds the compiled row.
5. **Keep, unchanged:** the strict-`defn` replay gate already at
   `client.cljs:680-681` (`seval/defn-form?`) — an override is a `(defn …)`, so it
   passes; a bare `(def f (fn …))` override is correctly refused.
6. **Optional (policy only):** add `:override-dir` to the `seon.db/origin` enum
   (`db.cljs:189`) if the override-dir delivery vehicle wants its own provenance
   tag; the mechanism works with `:agent` today.
7. **Keep as separate, non-attr work:** the alias re-eval/audit (residual 1) and
   the kernel-target loud-warning + `:repl`-only posture (residual 2). The
   `fire-on-reply-hooks!` augmentation seam (#27) is orthogonal and stays.

## Could-not-verify / caveats

- **Live var shadow on replay was NOT directly exercised against the compiled
  `seon.agent.message/reply!`** — doing so would redefine a live core var in the
  shared pod. It is established transitively: Claim 1 proves
  `seval/eval source {:ns …}` (the exact `replay-one!` call) shadows a compiled
  caller via late-binding, and Claim 3 proves the override row reaches that call.
  A full end-to-end "boot replays the override and `reply!` returns the override
  value" is a unit test for the implementation phase, on an isolated agent conn +
  fresh compile-state — not run here to avoid mutating the live var.
- **Isolation note for implementers:** `seon.db/*conn*` is ROOT-bound to the live
  cluster conn in the `default` session; `binding` does NOT propagate across
  promise `.then` boundaries (it is not AsyncLocalStorage). Pass the isolated conn
  explicitly to every `transact!`/`query`/`core-index-tx`. `with-tx-context` DOES
  propagate across awaits (it is AsyncLocalStorage-backed), so origin tagging works
  through the chain.

## Cross-references

- `docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md` —
  Finding 3 (this verification confirms it).
- `docs/prds/agent-runtime/overridable-substrate-2026-06-17.md` — the PRD whose
  `:seon.fn/override-target` + sort-tier + stacking machinery this evidence
  refutes.
- `docs/seon/concepts/code-as-data-runtime.md` — upsert + history rubric.
- `src/seon/client.cljs` — `query-program-graph-entries` (628, replay-skip 665,
  strict-defn 680, sort 693), `core-ns-set` (1039), `core-index-tx` (1408,
  presence-dedup 1457), `prune-core-ghosts!` (1467, `:core-seed` join 1531).
- `src/seon/eval.cljs` — `eval` (518), `lookup-value` (286), `defn-form?` (905),
  schema-tee origin query (1149).
- `src/seon/db.cljs` — `:seon.db/origin` enum (189), `with-tx-context` (259).
