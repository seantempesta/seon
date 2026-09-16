---
type: research
status: complete
created: 2026-09-16
tags: [research, steward, program-graph, analyzer, clj-kondo, data-model]
---

# Analysis facets — everything clj-kondo emits, what we keep, and what the discard costs

R5, measured on cluster `default` through read-only MCP jvm-mode probes with
explicit custody. Probe:
[analysis-facets-probe-2026-09-16.clj](analysis-facets-probe-2026-09-16.clj).
Read end to end first: AGENTS.md §2.2 and §3,
[writer-census-2026-09-16.md](writer-census-2026-09-16.md) (its "honest limit"
asks for `:seon.fn/writes` at this seam — §2 row 11 below answers it),
`src/seon/fn/analyzer.clj`, `src/seon/fn.clj:230-860`,
`reference-code/clj-kondo/analysis/README.md`.

**The headline.** clj-kondo emits **124 557 var-usages, 79 625 keywords,
33 902 locals, 75 642 local-usages, 3 362 java-class-usages, 2 953
instance-invocations, 115 protocol-impls, 858 symbols** over the same 332
inputs (147 620 lines) the publication already analyses — **in 4 015 ms with
every facet enabled and linting skipped**. We request six facet families and
store four, collapsed to 63 198 call edges and 32 836 keyword edges. Every
position, arity, macro flag, dispatch value, interop name and language arm is
computed and thrown away at `src/seon/fn.clj:290` and `:313`.

## 1. The facet table

`:seon.fn/calls` holders 5 598 / 63 198 edges; `:seon.fn/keywords` holders
4 332 / 32 836 edges; total database 441 609 datoms (P0, P3).

| clj-kondo facet | Emits (332 files) | Requested today | Stored today | Fact family it would land in |
|---|---|---|---|---|
| `:namespace-definitions` (`:meta` true) | 340 | yes (`analyzer.clj:30`) | `:seon.ns/{name,doc,source}` | `seon.ns` |
| `:namespace-usages` | 3 073 | yes (`analyzer.clj:66`) | `:seon.ns/requires`, `:seon.ns.alias/target-ns` (2 829) | `seon.ns.alias` |
| `:var-definitions` (`:meta` true, `:arglists`) | 6 675 | yes | `:seon.fn/*` (4 764) + `:seon.test/*` (1 779) | `seon.fn`, `seon.test` |
| ├ `:fixed-arities` / `:varargs-min-arity` | on 6 675 | yes (`analyzer.clj:76`) | **discarded** — `:seon.fn/arities` (1 080) comes from the Malli contract, not from source | `seon.fn.arity` |
| ├ `:lang` (cljc arm) | 531 | yes | **discarded**: `jvm-entry?` drops `:cljs` rows (`analyzer.clj:126`, `:211-224`) | `seon.fn` |
| ├ `:protocol-ns` / `:protocol-name` | — | **no** | no | `seon.fn` |
| `:var-usages` | 124 557 | yes | collapsed to 63 198 `:seon.fn/calls` refs (`fn.clj:284-303`) | `seon.fn` |
| ├ `:arity` (117 120 usages) | 117 120 | yes | **discarded** — used only as the "is a call" predicate (`fn.clj:277`) | `seon.fn.call` |
| ├ positions (`row/col/end-row/end-col`, `name-*`) | 124 557 × 8 | yes (`analyzer.clj:17`) | **discarded** for usages; kept for definitions as `:seon.fn/form-span` (5 667) | derive, do not store (§3) |
| ├ `:macro` (38 439 usages) | 38 439 | yes | **discarded** | `seon.fn` |
| ├ `:defmethod` / `:dispatch-val-str` | 37 | **no** | no | `seon.fn` |
| ├ `:private`, `:deprecated` on the used var | — | yes | **discarded** (re-derived from the target row) | — |
| `:keywords` | 79 625 (56 792 qualified) | yes | 32 836 `:seon.fn/keywords` (unqualified dropped, `fn.clj:306-328`) | `seon.fn` |
| ├ `:keys-destructuring` (1 039) | 1 039 | yes (`analyzer.clj:99`) | **discarded** — a destructured key reads as an assertion | `seon.fn` |
| ├ positions | 79 625 × 4 | yes | **discarded** — this is the one that costs us `:seon.fn/writes` (§2 row 11) | joined at index time |
| ├ `:reg` (hook-registered keywords) | 0 (no hooks) | no | no | — |
| `:locals` | 33 902 | **no** | no | derive on demand |
| `:local-usages` | 75 642 | **no** | no | derive on demand |
| `:protocol-impls` | 115 | **no** | no | `seon.fn` (dispatch graph) |
| `:symbols` (quoted/EDN symbols) | 858 | **no** | no | `seon.fn` |
| `:java-class-usages` | 3 362 (247 classes) | **no** | no | `seon.java.class` |
| `:instance-invocations` | 2 953 (303 methods) | **no** | no | `seon.java.class` |
| `:java-class-definitions` / `:java-member-definitions` | classpath-wide | **no** | no | **do not request** — scans every jar; the question is our interop, not the JDK's surface |
| `:context` | n/a | yes (`analyzer.clj:30` does *not* set it) | no | **do not request** — it carries hook data and we register no hooks |
| `:findings` | — | yes | `:seon.lint/*` (1 014) | `seon.lint` |

## 2. Each discarded facet: the task it enables, and the cost

Cost is datoms per file over the 332 inputs, in the aggregated shape of §3
(never one entity per usage — see the refusal there).

| # | Discarded facet | Future task it enables | Datoms/file | Total |
|---|---|---|---|---|
| 1 | var-usage `:arity` | contract/arity mismatch as a query: 117 120 call sites vs the callee's declared arities; today an arity error is only found by loading the code | 193 | ≈64 100 |
| 2 | var-usage positions | rename by span, and blast-radius *with line numbers* instead of a function list | **0 — derive** (§3) | 0 |
| 3 | var-usage `:macro` (38 439) | reach through macros: today a call made only inside a macro expansion is indistinguishable from a direct call, so `function-reaches` over-reports | 5.0 (one flag per edge) | ≈1 660 |
| 4 | `:defmethod` / `:dispatch-val-str` | the dispatch graph: which method answers which value, today answerable only by reading source | 0.2 | 74 |
| 5 | `:protocol-impls` (115) | the other half of the dispatch graph: protocol → implementing namespace → method | 1.0 | 345 |
| 6 | `:java-class-usages` (3 362 / 247 classes) | interop census for portability and for the `.cljc` maximization rule; "which functions pin us to the JVM" is a query | 10.1 | ≈3 362 |
| 7 | `:instance-invocations` (2 953 / 303 methods) | the same census at method granularity (reflection risk, `Thread/getAllStackTraces`-class lies) | 8.9 | ≈2 953 |
| 8 | `:locals` + `:local-usages` (109 544) | argument names for `doc`/`dir` on the 2 904 indexed functions with no contract, and destructuring shape | **0 — derive** | 0 |
| 9 | `:symbols` (858) | finding first-party symbols named in quoted data (the `:seon.render/ai` "function named by text" class of the writer census) | 2.6 | 858 |
| 10 | var-definition `:lang` (531) | cljc portability: which arm of a reader conditional a definition came from | 1.6 | 531 |
| 11 | **keyword positions × var-usage spans** | **`:seon.fn/writes`** — the fact the writer census could not derive. 1 008 `seon.db/transact!`/`transact` call sites, every one with a `:from-var`; 3 033 (writer, attribute) pairs inside those call spans, **2 620 naming an installed attribute, 524 distinct writers, 296 distinct installed attributes** (P2) — against the census's 178 whole-body approximation | 7.9 | **2 620** |
| 12 | keyword `:keys-destructuring` (1 039) | separating "asserts this attribute" from "destructures it" in `:seon.fn/keywords`, which today conflates reads with writes | 3.1 (flag) | 1 039 |

**Row 11 is the answer to the census's known limit, and it needs no new
clj-kondo facet.** Both halves are already requested: keyword entries carry
`row`/`col` and var-usage entries carry `row`/`col`/`end-row`/`end-col`
(`src/seon/fn/analyzer.clj:17`, `:79-87`). The writer fact is a span
containment join computed in memory at index time, then stored as refs. It
over-reports nothing that the call itself does not lexically contain, and
under-reports only tx data assembled in a helper — which the existing
`:seon.fn/calls` closure already covers.

## 3. Schema additions — aggregate, never an entity per usage

**The affordability question, measured.** One entity per var-usage with the
minimum useful attributes (identity, from-fn ref, to-fn ref, file ref, row,
col, arity) is 124 557 × 7 ≈ **872 000 datoms — +197 % on a 441 609-datom
database, for facts a 4-second re-analysis recomputes exactly.** Refused.
The aggregates below total ≈73 500 datoms, **+16.6 %**, and every one of them
is a fact a query needs that re-analysis cannot cheaply answer *across* files.

Positions therefore stay derived: `analyze` on one file is ~12 ms (4 015 ms /
332), so "every usage span of this var" is a bounded derivation at the moment
a rename asks, carrying its answer as a value — not 872 000 stored datoms that
go stale on the next edit.

Names are clj-kondo's own (`var-usage`, `arity`, `dispatch-val`,
`protocol-impl`, `java-class-usage`, `instance-invocation`, `keys-destructuring`),
and every program-entity slot is a ref, never a string:

| Attribute | Type / cardinality | Value | Holders |
|---|---|---|---|
| `:seon.fn/writes` | ref, many → `:seon.schema/key` row | the installed attributes this function asserts at a `transact!` call site | 524 fns / 2 620 refs |
| `:seon.fn/call-arities` | tuple `[ref long]`, many | callee row + the arity actually passed | ≈64 100 |
| `:seon.fn/macro-calls` | ref, many | the subset of `:seon.fn/calls` reached only through a macro usage | ≈1 660 |
| `:seon.fn/dispatch-val` | string, one (kondo's `:dispatch-val-str`, the authored text) | a defmethod's dispatch value | 37 |
| `:seon.fn/protocol` / `:seon.fn/protocol-method` | ref / symbol | the protocol entity and method this var implements | 115 |
| `:seon.java.class/name` | symbol, `:db.unique/identity` | `java.nio.file.Files` | 247 |
| `:seon.fn/java-classes` | ref, many → `seon.java.class` | interop census | ≈3 362 |
| `:seon.java.member/name` + `:seon.fn/instance-methods` | symbol / ref many | method-level census | 303 / ≈2 953 |
| `:seon.fn/destructured-keywords` | keyword, many | the `:keys-destructuring` subset of `:seon.fn/keywords` | 1 039 |
| `:seon.fn/lang` | keyword, one | the reader-conditional arm a definition came from | 531 |

Identity follows the owner rule: `seon.java.class` is identified by its
canonical class symbol and upserts; everything else is an attribute on the
already-identified `:seon.fn` row, so re-publication re-asserts the same
entity. **No per-usage entity is minted anywhere**, because a usage has no
identity of its own — its canonical parts are (from, to, position), and
position is exactly what we refuse to store.

## 4. The one seam, and the exact-replacement discipline

Everything lands in two functions, not ten: `seon.fn.analyzer/analyze`
(`src/seon/fn/analyzer.clj:166`) widens `analysis-config`
(`:analyzer.clj:23`) with `:protocol-impls true`, `:symbols true`,
`:java-class-usages true`, `:instance-invocations true`, and adds
`:defmethod`/`:dispatch-val-str`/`:protocol-ns`/`:protocol-name` to the
`present-values` key vectors at `:79` and `:71`; `seon.fn/var-row` and its
three helpers (`fn.clj:284`, `:306`, `:366`) stop collapsing and start
emitting the §3 attributes. `analyze-forms` (`analyzer.clj:377`) is untouched:
it is the agent-facing lint path and stores nothing.

**Exact replacement, not accretion of a second index.** Each new attribute is
asserted in the same `index!` transaction as the row it decorates, so a
publication that drops a usage retracts the fact with it (`seon.fn/index!`,
`src/seon/fn.clj:2046`). `:seon.fn/writes` supersedes the census's
`:seon.fn/keywords ∩ installed` heuristic at the query, and that heuristic's
two consumers must move in the same commit or the graph answers one question
two ways. `:seon.fn/call-arities` does **not** replace `:seon.fn/calls`: the
ref set stays as the reach index (63 198 datoms of hot join), and the tuple
carries the refinement.

**Cost at the seam: 0 ms.** The 4 015 ms measurement above already had every
proposed facet enabled; the publication today pays that same analysis with
lint on. Turning the facets on is free at analysis time and costs only the
transaction of ≈73 500 additional datoms.

## 5. Regressions — one per class, each failing today

| Name | Namespace | Assertion |
|---|---|---|
| `writes-facet-names-every-transacted-attribute` | `seon.fn-test` | Index the canonical fixture; for every `:seon.fn/sym` whose source contains a `seon.db/transact!` usage, the installed attributes named inside that usage's span equal its `:seon.fn/writes` refs. Fails today: the attribute does not exist. |
| `call-arity-agrees-with-callee-arities` | `seon.fn-test` | Every `:seon.fn/call-arities` tuple's arity is admitted by the callee's `:seon.fn/arities` or its analyzer fixed-arities/varargs minimum; the empty set of violations is asserted, and an injected wrong-arity fixture function appears in it. Fails today: no arity is stored, so the query returns nothing — the check that reads absence as health, which is the recurring failure class. |
| `interop-census-is-a-query` | `seon.fn-test` | `(seon.fn/java-classes db 'seon.fs.jvm/stat)` returns the class entities the source names, and a function with no interop returns the empty set (typed, not nil). Fails today. |
| `macro-reach-is-distinguishable` | `seon.fn-test` | A fixture function calling a target only through a macro has that target in `:seon.fn/calls` **and** in `:seon.fn/macro-calls`; a direct caller has it only in `:seon.fn/calls`. Fails today: the two are indistinguishable. |
| `usage-spans-are-derived-not-stored` | `seon.fn-test` | No installed attribute holds a var-usage position, and `(seon.fn/usages-of db 'seon.db/transact!)` answers from a bounded re-analysis within `seon.test-support/event-backstop-seconds`. Guards the §3 refusal against a later lane storing 872 000 datoms. |

## 6. Prices and order — simplest kill first

1. **`:seon.fn/writes` — 3 h.** One span join over facets already requested,
   2 620 refs, and it retires the writer census's approximation (296 exact
   installed attributes vs 178 guessed). Highest value per hour on this page.
2. **`:seon.fn/call-arities` — 4 h.** ≈64 100 tuples; makes arity mismatch a
   query over 117 120 call sites instead of a load-time surprise.
3. **Interop census (`seon.java.class`, `:seon.fn/java-classes`,
   `:seon.fn/instance-methods`) — 3 h.** Two config keys, one identity
   family, ≈6 300 refs; answers the portability question the `.cljc` rule
   keeps asking by hand.
4. **Dispatch graph (`:protocol-impls`, `:defmethod`/`:dispatch-val-str`) — 2 h.**
   459 datoms total; the last corner of the program graph that still requires
   reading source.
5. **`:seon.fn/macro-calls` + `:seon.fn/destructured-keywords` — 3 h.** Both
   are precision repairs to facts we already store, and both make an existing
   over-report honest.
6. **`:seon.fn/lang` — 1 h, optional.** 531 rows; real only while `.cljc`
   maximization is live, and CLJS is off, so it is last.
7. **Never: `:locals`/`:local-usages`, `:java-class-definitions`,
   `:java-member-definitions`, `:context`.** The first two are derivable in
   12 ms per file, the next two scan the whole classpath to answer a question
   about the JDK rather than about us, and `:context` carries hook data we do
   not produce.

No owner decision is required: every item above is an accretion onto an
existing identity, and the one decision that *could* have needed pricing —
per-usage entities — is settled by the +197 % measurement in §3.

## 7. Files

Owned by this lane: this note and
[analysis-facets-probe-2026-09-16.clj](analysis-facets-probe-2026-09-16.clj).
No source or test file was edited; no test JVM was run; `default` was never
stopped, reforked or restarted.

Protected at the time of writing (`git status`, other lanes' uncommitted
work, all left untouched): `src/seon/turn.clj`,
`test/seon/datahike_fork_test.clj`, `reference-code/datahike`,
`.agents/skills/datahike/references/fork-maintenance.md`,
`docs/prds/steward-platform/research/effects-and-write-back-*`, and the
untracked `build/` and `workers/`.
