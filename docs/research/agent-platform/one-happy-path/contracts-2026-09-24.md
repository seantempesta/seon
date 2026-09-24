---
type: research
status: read-only census; no source edited
created: 2026-09-24
lane: one-happy-path / contracts (Opus 5.5)
tags: [agent-platform, one-happy-path, malli, schema, instrument, call-preparation, errors]
---

# One happy path: schemas and contracts

Area: `seon.schema` (4,194 lines), `schema/{internal,admission,datahike,edn}` (506 + 487 + 565 + 641),
`seon.instrument` (1,144), `seon.call-preparation` (1,502), `seon.error` (2,299), `seon.error.refusal`
(149) and `seon.fn.schema-shape` (467). That is **11,954 lines**. Spans are top-level-form spans at the
working tree on 2026-09-24. Malli citations are to the fork at `8725a8cb` (`reference-code/malli`, `M/` =
`src/malli/`). This census builds on `shrink-schema-2026-09-23.md` (S1–S8) and
`dependency-already-does-it-audit-2026-09-23.md`. It re-measures their claims instead of repeating them,
and adds the instrument, call-preparation, admission and humanizer rows that neither of them covers.

**Live probes.** These ran on `default` in JVM mode, read-only, in the throwaway namespace
`one-happy-contracts-probe`:
- **P1.** `(m/eval 'seon.schema/malli-form? {::m/disable-sci true})` returns the Var.
  `(m/form (m/schema [:fn 'seon.schema/malli-form?] {::m/disable-sci true}))` keeps the symbol, and it
  validates correctly (`M/core.cljc:2890-2910`, fork commits `3517a3cd` and `8725a8cb`).
- **P2.** `seon.schema/load-projection` over the live database took **483 ms**. The shrink audit measured
  1,012 ms. A plain `mr/lazy-registry` over the **same** forms and contracts, with no predicate binding and
  `::m/disable-sci true`, built in 0.32 ms and compiled **all 5,364** declarations (3,373 schemas + 1,991
  contracts, zero failures) in **12.4 ms**. One key plus its validator took 0.36 ms.
- **P3.** Calling `seon.instrument/armable` with `:x` produced one refusal: 0.20 ms, 2,104 bytes, 19 keys.
  The same Malli explanation appears twice in it: `:seon.error/problems` (from `violation`) and
  `:seon.instrument/explanations` (from `boundary-refusal`).
- **P4.** `(m/explain [:vector :int] [1 "a"])` already reports the member (`:in [1]`). For a list,
  `'(1 "a")`, it reports only `::m/invalid-type` on the whole value. So `seon.error/collection-member-problem`
  fills a real gap and stays.
- **P5.** `runtime_status` profile: `seon.schema/call-with-projection` ran ×79,689 (total 101,135 ms,
  max 12,071 ms), and `seon.db/with-declarations` ran ×171,691 (total 131,800 ms).

## Table (ranked by deletable lines)

| # | concern | our mechanism(s) (file:line, lines) | the one happy path | evidence it duplicates / diverges | delete | keep/adapt | risk + first slice |
|---|---|---|---|---|---:|---|---|
| 1 | build the projection from rows | `schema.clj` `projection-registry` :561 (56), `canonical-value-string` :822 + `projection-fingerprint` :887 (R8, 153), `build-projection` :2142 (215), `materialize-projection` :2525 + delta family (R18, 206), `projection-with-declarations` :2756 + incremental family (R19, 413), `projection-from-rows` :3063 + row loaders (R20, 308) = **1,351** | `mr/lazy-registry` (`M/registry.cljc:81-94`) over the parsed rows; `m/-memoize` on `RefSchema` (`M/core.cljc:1953-2025`) makes refs lazy; validation belongs to the writer | P2: 483 ms against 12.4 ms for the same 5,364 declarations, with identical inputs | ~1,260 | one `(projection forms contracts)` constructor, ~40 | writer validation must cover dependents (stored `:seon.schema/references`); first: shrink S1 (fingerprint), then S4 |
| 2 | supply omitted arguments | all of `call_preparation.clj` (1,502): its own two-tier snapshot memo :545-706 (183), coherence proof over shape rows :202-318 (117), plan :877-1182 (306), supply/prepare :1183-1450 (268) | the host wrapper applies declared defaults from `m/-function-info` (`M/core.cljc:2194`) (ruled option (a), A1 win 5); map-entry defaults are `mt/default-value-transformer` (`M/transform.cljc:484`) | (a) a second memo keyed on Datahike's `:cache-context` beside the `db.clj` projection memo; (b) the hook silently returns the arguments unchanged when the read or snapshot is invalid (`:1480-1490`), so silence counts as health; (c) it bypasses its own contracts through `mi/-f->original` (3 sites) | ~1,200 | ~300 in the wrapper | UNVERIFIED that the transformer covers environment-parameterised suppliers for positional arguments; blocked while `instrument.clj` is held |
| 3 | carry the projection to callers | R9 dynamics and `call-with-*` (`schema.clj:1199-1260`), `handed-projection` :3707, R10 classpath fallback `packaged-forms` :1199 (≈ 710) | the value is passed explicitly (law "Pass the world"): `db/carried-projection` of the db value | P5: 79,689 wraps, max 12 s; `instrument/restore!` and `admission/admit` still fall back to `schema.edn/packaged-forms` | ~650 | — | a scripted sweep of about 75 sites (shrink S8), after row 1 |
| 4 | turn `[:fn sym]` into a callable | `schema.clj` R3 (predicate-symbols-in :130 … `with-predicate-functions` :438, 208) + R4 `compilable-form` :264 / `canonical-definition` :618 (234); `instrument.clj` `predicate-callable` :405 + `bind-contract-predicates` :413 (40), a **third** binder; `register-core-predicate!` :1268, 51 load-time call sites | fork `m/eval` resolves a loaded qualified Var (`M/core.cljc:2890-2910`) under `::m/disable-sci` | P1 + P2: every declaration compiles with no binding step, and `m/form` keeps the symbol, so the inverse `canonical-definition` has nothing to invert | ~480 | `widen-component-children` | the fork already carries the fix; first: delete `bind-contract-predicates` and compile `authored` directly in `compiled-wrapper` :638 |
| 5 | structural mirror of Malli schemas | `fn/schema_shape.clj` (467) + 93 `:seon.schema.shape/*` sites; consumers `instrument.clj:619,630` (expected-shape fingerprint in refusals) and `call_preparation.clj:773` | the compiled schema itself: `m/form`, `m/children`, `m/-function-info` | a stored copy of what the registry already answers (deep-review win 2, scheduled) | ~467 | — | already scheduled (A1 + B1); these two consumers are new: convert them in the same slice |
| 6 | build a contract refusal | `instrument.clj` `violation` :272 (126) **and** `boundary-refusal` :586 (52) + `observation-location` :569 (17); arglists taken from an ambient DB query, `program-graph-arglists` :155 (36) / `diagnostic-arglists` :208 (23); `supplied-entry-problems` :231 (11) queries the db inside the refusal | one `m/explain` (`M/core.cljc:2659`) per report; arglists from Var meta / `m/-function-info` | P3: two projections of one explanation in one value (`:problems` + `:explanations`); `m/explain` runs twice (`:287`, `:609`), and a third time for invalid refusals (`:689`) | ~130 | `violation` → `explain-problem` | refusal schema `:seon.instrument/refusal-result` loses `explanations`; first: delete `boundary-refusal`'s second explain |
| 7 | the admission rule for "complete contract" | `schema/admission.clj` `house-findings` :256 (71) + `form-properties`… `polymorphic-form?` :203-243 (40) walk authored EDN; `schema.internal/permissive-positions` :132 + `assert-complete-schema!` :300 walk compiled schemas; file walker :115-202 (≈ 95) | one rule owner, `schema.internal`, reached through `schema.edn/admit`; the hook sends forms to that owner | source-proven divergence: a `:fn` with no `:error/message` is a **warning** in admission (`admission.clj:292-306`) and an **error** in internal (`internal.cljc:336-346`, which also requires a generator and a qualified predicate); `:maybe` is always polymorphic in admission (`:237-242`) but conditionally admitted in internal (`dcd76fc21`) | ~200 | naming/placement findings (house-only) | `bin/seon-hook:496` calls `admission/admit`; first: delete the polymorphism and predicate walkers in admission |
| 8 | bootstrap admission loop | `schema/edn.clj` `admit-changed-identities` :515 + `admit` :550 (≈ 220 with helpers) | one `projection-with-schema` per changed identity over a lazy registry (shrink E2) | re-admits recursively to resolve references that a lazy registry resolves on first use (P2) | ~200 | the gate | after row 1 |
| 9 | host shape-index maintenance | `schema.clj` R16 `shape-projections` :2401 … (249) | `(projection-cache-value p ::shape-index f)`, the Malli `-cached` pattern (`M/core.cljc:353`) | maintained incrementally, and duplicated in `build-projection` | ~150 | a derive fn | shrink S6 |
| 10 | save and restore JVM instrumentation | `instrument.clj` `function-schemas*` :998, `state` :1003, `replaced-definitions` :1019, `restore!` :1045 (≈ 106) + `remove!` :988 (10, a no-op count) | none: law "No JVM-global test state or restoring reloaded roots" | 0 `src` callers (test-only, 2 + 12); `restore!` rearms through the classpath fallback `schema.edn/packaged-forms` :1082 | 116 | — | tests that snapshot state go with it; first: grep the 14 test sites |
| 11 | pick a projection per call | `arm-var!` :768 switching block (≈ 25) + `supplied-projection` :551 (18) + `request-member` :544 (7) + `validates-loaded-contract?` :754 (14) + `contract-definitions` :715 (23) + `current-wrapper?` :738 (16) | one wrapper per adoption (`boot-wrapper`); other branches interpret differing rows in SCI (CLAUDE "One JVM, many realities") | sniffs three argument shapes on **every** host call and recompiles `compiled-wrapper` whenever a new projection is seen: a second arming path beside `apply!` | ~100 | `boot-wrapper` path | UNVERIFIED that no current caller depends on it; first: count the `supplied-wrapper` resets in a profile run |
| 12 | read the cause chain | `instrument.clj` `registration-cause-data` :531 (11), `schema.clj:465`, `internal.cljc:414`, `error.clj:114`, the `seon.error/refusal` alias :74 (17; 11 callers beside 3 direct) | `seon.error.refusal/chain` over `Throwable->map` (`clojure/core_print.clj:473`) and `refusal` | walkers in 4 files of this area (deletion-hunt N10 counts 19 repo-wide) | ~67 | `error.refusal` | mechanical; first: point the 11 alias callers at `error.refusal/refusal` |
| 13 | describe the expected type | `error.clj` `value-description` :749 (19) + the type table in `schema-expectation` :768 (24) | `me/error-message` with a Seon `:errors` map (`M/error.cljc:288`, `default-errors` :44) | `schema-expectation` already calls `me/error-message` for `:and/:or/:fn` and keeps its own table for every other type | ~35 | `explain-problem`, `problem-sentence`, `collection-member-problem` (P4) | none |
| 14 | the packaged population cache | `schema/edn.clj:342-402` (≈ 60) | `clojure.core.cache.wrapped/lookup-or-miss`, as in `datahike/schema_cache.cljc:8-29` | audit row 7, re-checked; still present | ~30 | — | none |
| 15 | which Vars to arm | `collect-contracts!` :837 (12) beside `armable` :71 (28); `primitive-fn?` :54 (17) copies `mi/-primitive-fn?` (`M/instrument.clj:15`) | `mi/-schema` + `@#'mi/-primitive-fn?`, which is `armable`'s own rule | two filters over `all-ns` for one question | ~29 | `armable` | none |
| 16 | call around the armed contract | 21 `mi/-f->original` bypasses (18 in `instrument.clj`, 3 in `call_preparation.clj`, e.g. `hook` :1480) and `*compiling-contract*` :542 | the wrapper validates once at its boundary; an over-cost contract is a contract defect to fix | the hook comment itself measures ~87 µs of ~93 µs per call spent in the armed wrappers it bypasses | ~21 | — | UNVERIFIED which bypasses remain once rows 2 and 11 go |
| 17 | expand refs for documentation | `sci/eval.clj:1602` `postwalk-replace` of queried forms | `m/deref-recursive` (`M/core.cljc:2834`) | hand substitution of forms that the compiled contract already derefs | ~5 | — | file held by another lane |

**Kept (Seon semantics that no dependency provides):** `schema.internal` completeness and error-declaration
rules; render-contract coherence R15 and pulled-form derivation R21 (no library found, per the shrink audit's
web search); `schema/datahike.clj` Malli→Datahike mapping; the `projection-cache-value` holder (Malli's
`-cached` pattern); `seon.error.refusal/diagnostic` as the one base constructor. 146 hand-stamped
`:seon.error/at (java.util.Date.)` sites remain beside 205 `diagnostic` calls; the error-constructor
manifest already schedules that conversion, so it gets no row here. Generators: 60 `*-generator` defs feed
Malli's own `:gen/gen` resolution (fork `25710a67`); there is no wrapper to delete.

## Totals

**About 5,100 of 11,954 lines are deletable** (rows 1–17), of which about 3,900 are new or re-proved and
about 1,200 (rows 2 and 5) were already scheduled.

**The three highest-value slices:**
1. **The projection is a lazy registry over rows** (rows 1, 4 and 8, about −1,900). P2 measured it on
   `default` today: 483 ms → about 12 ms. The fork already resolves predicates, so the three binders and
   the inverse `canonical-definition` go too.
2. **Explicit projection transport** (row 3, about −650). It removes the 12 s `call-with-projection`
   maximum and the classpath fallback that `restore!` and admission still reach.
3. **One contract refusal and one arming path** (rows 6, 10, 11 and 15, about −375). This means one
   explain, no per-call projection sniffing, and no JVM-state restore.

**What the dependency genuinely lacks** (fix in the fork, not beside it):
- `m/-instrument`'s `:report` can only throw (`M/core.cljc:3120-3139`). Record mode therefore throws a
  marked exception and catches its own marker again (`instrument.clj:700-714`). Fork change: a report may
  return `(reduced value)`, which the wrapper then returns.
- `mt/default-value-transformer` fills map entries from zero-argument `:default/fn`s. It cannot pass an
  environment or place a positional argument, so row 2's residue belongs in the arming wrapper, not in a
  transformer extension. UNVERIFIED whether a `:default-fn` closure per environment would suffice.
- Malli has no schema subsumption (R15) and no pull-result schema (R21). Both are kept as Seon semantics.

## Raised tasks

```clojure
#:seon.task{:id :seon.schema.task/projection-is-a-lazy-registry
            :intent "load-projection builds, fingerprints and re-validates the whole population (483 ms) when an mr/lazy-registry over the parsed rows compiles all 5,364 declarations in 12 ms; replace the build/delta/incremental/row-loader families with that constructor and validate at the writer."}
#:seon.task{:id :seon.call-preparation.task/defaults-in-the-wrapper
            :intent "call-preparation keeps its own snapshot memo, shape-row coherence proof, planner and a hook that silently passes arguments through on an invalid read; apply declared defaults in the one arming wrapper from m/-function-info, as ruled (option a)."}
#:seon.task{:id :seon.schema.task/projection-passed-not-bound
            :intent "Dynamic projection transport and the classpath fallback cost up to 12 s per wrap across 79,689 wraps; pass db/carried-projection explicitly and delete call-with-projection, handed-projection and packaged-forms fallback."}
#:seon.task{:id :seon.schema.task/predicates-resolved-by-malli
            :intent "Three binders (schema R3/R4, instrument/bind-contract-predicates) and 51 register-core-predicate! sentinels turn [:fn sym] into callables that the fork's m/eval already resolves under ::m/disable-sci; compile authored forms directly."}
#:seon.task{:id :seon.fn.schema-shape.task/retire-shape-mirror-consumers
            :intent "Refusal expected-shape fingerprints and call-preparation coherence read the stored schema-shape mirror; read m/form and m/-function-info of the compiled schema so the mirror can be deleted."}
#:seon.task{:id :seon.instrument.task/one-explain-per-refusal
            :intent "A contract refusal runs m/explain twice and carries the explanation twice (:seon.error/problems and :seon.instrument/explanations), querying the database for arglists inside the refusal; build it once from Malli's report and Var metadata."}
#:seon.task{:id :seon.schema.admission.task/one-completeness-rule
            :intent "The hook's admission walker and schema.internal judge the same contract differently (missing :error/message is a warning in one, an error in the other; :maybe handled differently); keep the rule only in schema.internal via schema.edn/admit."}
#:seon.task{:id :seon.schema.edn.task/admit-without-retry-loop
            :intent "The bootstrap admission re-admits changed identities recursively to resolve references that a lazy registry resolves on first use; admit each changed identity once over the lazy registry."}
#:seon.task{:id :seon.schema.task/shape-index-is-derived
            :intent "The required-attribute shape index is maintained incrementally and duplicated in build-projection; derive it once per projection through projection-cache-value."}
#:seon.task{:id :seon.instrument.task/delete-state-restore
            :intent "instrument/state, replaced-definitions, restore! and remove! restore JVM-global instrumentation for tests only, against the no-restoring-roots law; delete them and the tests that snapshot state."}
#:seon.task{:id :seon.instrument.task/one-wrapper-per-adoption
            :intent "Every armed host call sniffs its arguments for a projection and recompiles its wrapper when it sees a new one, a second arming path beside apply!; arm once per adoption and let other branches interpret in SCI."}
#:seon.task{:id :seon.error.task/one-cause-chain-reader
            :intent "Cause chains are walked by hand in instrument, schema, schema.internal and error, and seon.error/refusal aliases seon.error.refusal/refusal; use seon.error.refusal/chain and refusal only."}
#:seon.task{:id :seon.error.task/type-nouns-through-malli-errors
            :intent "value-description and schema-expectation keep a hand type-to-phrase table beside me/error-message; pass a Seon :errors map to me/error-message instead."}
#:seon.task{:id :seon.schema.edn.task/packaged-population-core-cache
            :intent "The packaged population is a hand LRU-of-one with a stamp, a monitor and a forget hatch; use clojure.core.cache.wrapped/lookup-or-miss as Datahike's schema cache does."}
#:seon.task{:id :seon.instrument.task/one-armable-filter
            :intent "collect-contracts! and armable filter all-ns twice for one question and primitive-fn? copies malli's private rule; keep armable over mi/-schema and malli's own -primitive-fn?."}
#:seon.task{:id :seon.instrument.task/no-contract-bypasses
            :intent "21 mi/-f->original call sites call around armed contracts for speed; fix the costly contracts at their boundary and delete the bypasses."}
#:seon.task{:id :seon.sci.eval.task/documentation-contract-deref
            :intent "documentation-contract expands refs by postwalk-replace of queried forms; use m/deref-recursive on the compiled contract."}
```

