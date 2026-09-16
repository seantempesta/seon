---
type: research
status: complete
created: 2026-09-16
tags: [research, steward, program-graph, analyzer, clj-kondo, data-model, landing-note]
---

# Analyzer facets, landed: `:seon.fn/writes` and `:seon.fn/call-arities`

Items 1 and 2 of
[analysis-facets-2026-09-16.md](analysis-facets-2026-09-16.md) §6, read end to
end before starting, together with AGENTS.md §2.2 and §3,
[writer-census-2026-09-16.md](writer-census-2026-09-16.md),
`.agents/skills/data-modeling/SKILL.md`, `src/seon/fn/analyzer.clj` and
`src/seon/fn.clj:230-900`.

Every probe ran on cluster `default` through `mcp__seon__eval_clj` in `jvm`
mode with explicit custody (`(seon.operator/connection "default")`). No test
JVM was launched; `default` was never stopped, reforked or restarted.

## 1. What landed

| Attribute | Shape | Derivation |
|---|---|---|
| `:seon.fn/writes` | ref, many → `:seon.schema/key` | qualified keywords lexically inside a `seon.db/transact!` var-usage span, admitted only when the schema population declares them |
| `:seon.fn/call-arities` | tuple `[string long]`, many | `(callee :seon.fn/sym, arity)` for exactly the pairs `:seon.fn/calls` already carries |

Both are emitted by static indexing (`seon.fn/analysis-rows-by-file` →
`seon.fn/var-row`) and by runtime admission (`seon.fn/analyzed-form`), on
function rows and on test rows alike. **Neither needed a new clj-kondo
facet**: keyword positions, var-usage spans and `:arity` were already
requested and normalized (`src/seon/fn/analyzer.clj:17`, `:79-87`) and then
discarded at `src/seon/fn.clj`'s collapse points. `analysis-config` is
unchanged, so the research's "cost at the seam: 0 ms" holds.

`seon.fn/arity-mismatches` is the contracted query over the second fact. It
returns a report, not a bare list, because the mismatch list alone is the
project's recurring failure class: an empty vector beside
`:seon.fn/arity-checked 0` would read as health while reporting that nothing
was compared.

## 2. The owner ruling that measurement falsified

The research asked for `:seon.fn/call-arities` as a many tuple **`[ref long]`**
— "refs not strings". **Datahike cannot store a ref inside a tuple.** Probed
2026-09-16 on a fresh in-memory store declaring
`:db/valueType :db.type/tuple, :db/tupleTypes [:db.type/ref :db.type/long],
:db/cardinality :db.cardinality/many`, transacting one tuple with a tempid
and one with a lookup ref. Both transactions succeeded; the stored datoms
were

```
[4 :t/ca [-1 2]]                ; the tempid, verbatim
[5 :t/ca [["t/sym" "a"] 3]]     ; the lookup-ref vector, verbatim
```

Tuple members are never resolved. `check-tuple`
(`reference-code/datahike/src/datahike/db/transaction.cljc:1016`) only
`s/valid?`-checks them, and `:db.type/ref` is `:db.type/id` — "a Long or a
string" (`reference-code/datahike/src/datahike/schema.cljc:6`) — so a tempid
string passes validation and is stored as data. A `[ref long]` tuple would
store an unresolvable value: a fake ref that pulls nothing and joins nothing,
the "silent fallback that happens to be right" §2.4 forbids.

Two shapes satisfy the ruling's *intent* instead. Both were measured:

1. **Interned `(callee, arity)` identity rows** with `:seon.fn/call-arities`
   a ref set into them. Population: **4 892** distinct `(callee, arity)`
   pairs over **67 914** edges ≈ 82 600 datoms. Real refs and real joins —
   but a new identity family that `seon.program/identity-attributes`,
   `seon.fn/published-index-rows` and `seon.fn/reconcile-tx` must all learn
   (a fork reads published rows by identity attribute, so an unlisted family
   would silently drop and leave every tuple dangling), all outside this
   lane's owned paths; and orphaned rows accumulate, because program
   identities never retract.
2. **A `[string long]` tuple** carrying the callee's `:seon.fn/sym` identity
   value: **67 914 datoms**, one per edge, exact replacement free, zero
   interaction with the identity machinery. `:seon.fn/pending-calls`
   (`resources/seon/schemas/seon.fn.edn`) already names a callee by that same
   identity value in that same schema.

Shape 2 landed, and the attribute's docstring records the measurement.
`:seon.fn/writes` **is** a true ref set, exactly as ruled — the ruling holds
wherever Datahike can honour it.

**Owner decision wanted:** whether to spend option 1's cross-file change so
that the callee side of a call arity is a real ref. It is a real
improvement, it costs ≈ 15 000 more datoms and edits to three files this
lane did not own, and the query above answers the same question either way.

## 3. Measurements

Static derivation over `src/` + `test/` (332 inputs), run inside `default`'s
JVM against the shipped `seon.fn` functions:

| Question | Number |
|---|---|
| `seon.db/transact!` writers (`:seon.fn/writes` holders) | **470** |
| `:seon.fn/writes` refs | **2 691** |
| distinct declared attributes written | **334** |
| `:seon.fn/call-arities` holders | **5 548** |
| `:seon.fn/call-arities` tuples | **67 941** |
| `:seon.fn/calls` edges under the same rule | **63 200** |
| arity refinements beyond the reach index | **4 741** |
| distinct `(callee, arity)` pairs | **4 892** |

The census comparison the research asked for: the writer census's
whole-body `:seon.fn/keywords ∩ installed` approximation reported **178**
attributes; the span join reports **334** declared attributes over **2 691**
(writer, attribute) refs. The research's own probe measured 524 writers /
2 620 installed pairs / 296 installed attributes; this lane's numbers differ
because the shipped path filters `:cljs` reader arms (`jvm-entry?`), counts
only `seon.db/transact!` (there is no `seon.db/transact`), and admits any
**declared** schema key rather than only the 677 attributes installed on
`default` today.

### Arity mismatches — the list

**Zero.** Over **7 866** call sites whose callee declares arities (1 078
contracted callees of 5 548 arity holders), no stored call arity falls
outside its callee's `:seon.fn.arity/min` / `:seon.fn.arity/max`.

That zero is a true negative, not a blind check: re-running the same
predicate with every arity incremented by one flags **6 449** of the same
7 866 sites. Two independent reasons the real list is empty — clj-kondo's
own arity linter blocks a wrong-arity call at the edit hook, and the
codebase loads — so the value of the fact is the *future* mismatch, and the
report's coverage counts are what keep the silence legible.

No issue entity was filed for a mismatch, because there is none to file.

## 4. Files

Owned and changed by this lane:

- `src/seon/fn.clj` — `usage-caller`, `span-contains?`, `write-seam-symbols`,
  `writes-by-writer`, `call-arities-by-caller`, `write-refs`,
  `declared-arity-bounds`, `arity-admitted?`, public `arity-mismatches`;
  `var-row` now takes one `edges` map instead of two positional edge maps;
  `analysis-rows-by-file` and `analyzed-form` build it.
- `resources/seon/schemas/seon.fn.edn` — the two attributes with their
  derivations in their docstrings, plus the `arity-mismatches` report
  schemas, and both attributes added to the `:seon.fn/fn` row map.
- `resources/seon/schemas/seon.test.edn` — the same two optional entries on
  the `:seon.test/test` row map, so a test that writes is as queryable as a
  function that writes.
- `test/seon/fn_test.clj` — five regressions (§5).
- `docs/seon/issues/write-seam-is-a-named-set-not-a-declared-fact.md` — the
  one hand-maintained set this work introduced, and how to dissolve it.
- this note.

Nothing under `src/seon/cluster.clj`, `src/seon/test.clj`,
`src/seon/test/runner.clj`, `src/seon/issue.clj`, `src/seon/turn.clj` or
`reference-code/` was touched. Concurrent foreign edits in
`test/seon/cluster/turn_test.clj` and
`docs/seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md`
were left untouched.

## 5. Regressions

All in `seon.fn-test`, on the canonical harness
(`seon.test-support/with-database`, real analyzer, no mock):

| Name | Asserts | Failed before |
|---|---|---|
| `writes-facet-names-every-attribute-a-declaration-transacts` | the two declared attributes inside the `transact!` span are refs; an undeclared keyword is not; a reader that *names* `:seon.agent/id` has no `:seon.fn/writes` while `:seon.fn/keywords` still carries it | yes — the attribute did not exist |
| `call-arities-refine-exactly-the-stored-call-edges` | the exact tuple sets, and that for every row the refined callees equal its `:seon.fn/calls` targets | yes |
| `static-index-and-runtime-admission-agree-on-analysis-facets` | `seon.fn/rows` and `seon.fn/analyze-forms` derive the same two facts for the same source, with a non-vacuity guard | yes |
| `arity-mismatch-is-a-query-over-stored-call-sites` | a deliberate out-of-range tuple on a contracted callee found from the database is the sole mismatch, the admitted one is not, and the coverage counts are present | yes — the query answered nothing |
| `re-index-replaces-analysis-facets-exactly` | after `reconcile-tx` for a narrowed source, the removed attribute ref and the removed arity tuple are retracted, not accreted | yes |

The callee in the mismatch regression is *derived from the database* rather
than named, and the test fails loudly when the canonical population installs
no contract arities — otherwise it would be the absence-as-health check it
exists to prevent.

## 6. Open at hand-off

1. **Live database counts are not yet observed.** The attributes are
   installed on `default` (`:db/valueType :db.type/ref` many, and
   `:db.type/tuple` with `:db/tupleTypes [:db.type/string :db.type/long]`
   many — both confirmed on the live schema), but no publication has yet run
   the *new* indexer: `bin/seon init --dev` reloads `seon.fn` **after** it
   builds the branch, so an indexer change needs a second successful
   publication to appear in the rows. Attempts were repeatedly refused by
   concurrent editors (`:seon.cluster/source-changed-during-adoption`,
   `source changed while current-src was being analyzed`), and the next
   complete publication by any lane will carry it. The §3 numbers are the
   derivations run in-process on the same tree; the database counts should
   equal them.
2. **One publication attempt reported
   `✗ Development JVM instrumentation did not restore contracts.
   #:seon.instrument{:registered 1090, :instrumented 0}`** while a second
   lane's `bin/seon init --dev default` was running against the same
   cluster. It did not recur on the attempt that succeeded. Two concurrent
   in-place adoptions of one development cluster is the suspected cause;
   reported here rather than diagnosed, because the sweep is the
   orchestrator's.
3. **In-process regression runs are not yet recorded.** `default`'s prepl
   was reserved for the gate and then saturated by five lanes while this
   slice was finishing; the five regressions lint clean and are written
   against the canonical fixture, but they have not been run
   `(seon.test/run …)` in-process. Gate request: `seon.fn-test` (existing
   namespace, validated against `test/`).
