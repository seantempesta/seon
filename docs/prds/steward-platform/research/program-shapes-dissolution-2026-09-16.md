---
type: research
status: active
created: 2026-09-16
tags: [research, program-graph, schema, data-model, landing-note, class/p2]
---

# `seon.program/shapes` dissolved into the schema declarations — 2026-09-16

Read end to end before starting: AGENTS.md (§2.2 "Derive or die", §2.5),
`tmp/orchestrator/wave2/repl-rule.txt` (all rules),
[the mirror issue](../../../seon/issues/program-shapes-mirror-the-schema-row-maps-by-hand.md),
[analyzer-facets-2026-09-16](analyzer-facets-2026-09-16.md) (`7cfe02790`),
[source-root-fact-2026-09-16](source-root-fact-2026-09-16.md) (`925ca19fe`),
and the `data-modeling` + `datahike` skills.

## Broken things first

- **`:seon.fn/writes` cannot answer "who writes this attribute", measured.**
  The hint that it might was falsified on `default`: the fact family is
  populated (**463 writers, 2 567 (writer, attribute) refs**) yet
  `seon.test.runner/record-tx`'s writes are **empty**, and so is the writer set
  of every one of the fourteen attributes this change had to classify
  (`:seon.test/pass-count`, `:seon.test/reach-digest`, … , `:seon.ns/steward`).
  That is the facet's own documented under-report — it is span containment
  inside a `seon.db/transact!` usage, and these rows are assembled in helpers
  and handed to a `:db.fn/call` transaction function. The ownership fact had to
  be **declared**, not mined.
- **Two declarations were already wrong in the other direction**, found by the
  same comparison and fixed here: `:seon.fn.file/file` and `:seon.lint/finding`
  did not declare `:seon.schema.admission/source` although **334 of 335** file
  rows and **565 of 704** lint rows on `default` carry it, and `:seon.test/test`
  did not declare `:seon.fn/file` or `:seon.fn/form-span` although **1 744 of
  1 745** test rows carry them. The literal list was the only place those
  attributes were admitted; the schema said they did not exist.
- The shared in-JVM fixture base predates today's schema additions, so
  db-backed regressions needing them are **cold-only**. Recorded as the
  boundary, not worked around; the base was never forced or rebuilt by this
  lane.

## Archaeology: why `shapes` existed and what it excluded

`git log -S owned-attributes -- src/seon/program.cljc`, oldest first:

| Commit | What it did |
|---|---|
| `52423e362` | *Unify program declaration row ownership* — created `seon.program` and the literal table, moving per-family `select-keys` lists out of `seon.fn` and `seon.cluster.run`. The table's job was to have **one** owner for "which attributes are a program row", not to be a second model of the schema. |
| `92d2e39be` | restore pure SCI session forms from facts |
| `fb987d65c` | derive parsed function contract facts |
| `f08d79e96` | project storable Malli properties onto schema rows — introduced the `:seon.program/schema-row-properties` sentinel, because a schema row also carries every qualified Malli property projected onto it, which no map can enumerate |
| `190eed2fe` | *move desk facts outside program rows* — removed attributes the indexer must not own |
| `3402913f3` | index source file spans and lint findings — two new families |
| `f9a46b0bd` | retract program attributes with their current tuple values |

So the table was right to exist (one owner) and wrong to be a list (a second
model). Its exclusions split cleanly:

**Genuine rules, now declared.**
1. Attributes another mechanism writes. `:seon.test/test` declares the
   thirteen run-outcome attributes `seon.test.runner/record-tx` writes
   (`src/seon/test/runner.clj:1717`) plus `:seon.test/pending-subject`, which
   `seon.turn/relation-assertions` (`src/seon/turn.clj:1174`) asserts; `:seon.ns/ns`
   declares `:seon.ns/steward`, which `seon.cluster.agent/steward-call`
   (`src/seon/cluster/agent.clj:96`) writes. Keeping them would be worse than a
   silent strip: `exact-replacement-tx` would RETRACT a runner's result on every
   re-index.
2. The schema family's open row: its attributes are the row's own qualified
   keys, because Malli properties are projected onto it.

**Accidents, now gone.** The identity attribute itself is simply the first
entry of its entity map; and the three declaration/row mismatches above were
pure drift.

## The derivation

One link, declared on the identity attribute, and one exclusion, declared on
the entry:

```clojure
;; resources/seon/schemas/seon.fn.edn
:sym [:string {:min 1 :seon.db/identity true :seon.search/index :symbol
               :seon.program/row-schema :seon.fn/fn
               :seon.program/source-attribute :seon.fn/source}]

;; resources/seon/schemas/seon.test.edn — :seon.test/test
[:seon.test/pass-count
 {:optional true :seon.program/written-by seon.test.runner/record-tx}
 :seon.test/pass-count]
```

`seon.program/shapes-in` reads the identity attribute's `:seon.program/row-schema`,
takes that entity map's own entries as the owned attributes, and drops every
entry declaring `:seon.program/written-by`. `:seon.program/projected-properties true`
on `:seon.schema/schema` selects the open-row rule. **Every absence refuses**
(`:seon.program/declaration-refused`, naming the identity and the missing
member) rather than yielding an empty owned set, which would silently strip the
whole family it describes.

`shapes` is now a function: the no-argument arity answers from the declaration
population resolved once per process — the same static scope the literal had,
and the reason a per-row caller never re-resolves a population that costs a
complete resource merge (**measured 22 ms per `seon.schema/registered-schemas`
call with no projection in hand**, against 14.5 µs for the derivation itself).
`shapes`, `shape`, `canonical-row` and `changed-attributes` each gained an
explicit-population arity for a caller — or a regression — that holds the
operation's own forms.

### The derivation reproduces the literal exactly

Measured in `default`'s JVM against the edited resources, before adopting them,
with the old literal still bound:

| Identity family | source attribute | owned attributes | extra | missing |
|---|---|---|---|---|
| `:seon.fn/sym` | same | 27, same set | 0 | 0 |
| `:seon.test/sym` | same | 14, same set | 0 | 0 |
| `:seon.ns/name` | same | 9, same set | 0 | 0 |
| `:seon.fn.file/path` | same | 4, same set | 0 | 0 |
| `:seon.lint/id` | same | 9, same set | 0 | 0 |
| `:seon.schema/key` | same | `:seon.program/schema-row-properties` | — | — |

## Re-index of `default`'s population

The honest measurement is a **differential**: the same computation, against
the SAME captured database value of `default` (basis `t` 536871534), before
and after the change. An absolute zero is not available — `default`'s
published src rows are stale relative to HEAD (other lanes' edits plus two
indexer changes landed today), so a fresh index legitimately differs from
them for reasons that are not this change.

Six files, `seon.fn/build-artifact` → `seon.fn/reconcile-tx`, digesting the
emitted rows and the complete transaction data:

| File | rows | attributes | tx forms | retractAttribute | rows digest | tx digest |
|---|---|---|---|---|---|---|
| `src/seon/id.clj` | 12 | 29 | 24 | 12 | unchanged | unchanged |
| `src/seon/print.cljc` | 80 | 29 | 115 | 38 | unchanged | unchanged |
| `src/seon/plan.clj` | 87 | 30 | 207 | 48 | unchanged | unchanged |
| `src/seon/schedule.clj` | 38 | 22 | 110 | 18 | unchanged | unchanged |
| `src/seon/env.clj` | 24 | 29 | 96 | 32 | unchanged | unchanged |
| `test/seon/id_test.clj` | 4 | 16 | **0** | **0** | unchanged | unchanged |

**245 rows, 552 transaction forms, 148 attribute retractions — byte-identical
before and after**, each file's source digest unchanged between the two runs.
`test/seon/id_test.clj` is the file whose published rows are current: it
reconciles to an EMPTY transaction, before and after, which is the "zero
retractions and zero assertions for an unchanged file" datum.

## Regressions

| Namespace | Regression | Asserts |
|---|---|---|
| `seon.program-test` | `declaring-an-attribute-on-a-program-row-schema-is-sufficient` | an attribute added to `:seon.fn/fn` in a supplied population is kept by `canonical-row` and named by `changed-attributes` with NO code change; the same entry carrying `:seon.program/written-by` is neither kept nor retractable |
| `seon.program-test` | `every-program-row-attribute-is-owned-or-names-another-writer` | the drift checker: every declared attribute of every program entity map is kept or declares another writer, with loud non-vacuity (six families, non-empty owned sets, each owning its identity, at least one written-by exercised) |
| `seon.program-test` | `program-identity-attributes-are-exactly-the-declared-row-schemas` | `identity-attributes` and both `:seon.program/*-attribute` enums equal the set derived from the declarations |
| `seon.program-test` | `process-resolved-shapes-match-the-current-declarations` | the once-per-process shapes still describe the live declarations |
| `seon.fn-test` | `the-indexer-emits-no-attribute-the-program-row-schema-drops` | the class itself: the attribute set the analysis emits equals what the artifact's canonical rows carry, per row and overall, with a rich-emission guard so an empty analysis cannot read as health |

## In-process proof and its boundary

Every run is `(seon.test/run (#'seon.test/resolve-test 'ns/test)
(seon.operator/connection "default"))` inside `default`'s JVM on a daemon
thread, one at a time, after reloading the test namespace through
`seon.test`'s own loader. No test JVM, no `bin/test`, no `bin/test-fast`, and
the shared fixture delay was never forced by this lane.

| Regression | pass / fail / error |
|---|---|
| `seon.program-test/declaring-an-attribute-on-a-program-row-schema-is-sufficient` | **5 / 0 / 0** |
| `seon.program-test/every-program-row-attribute-is-owned-or-names-another-writer` | **20 / 0 / 0** |
| `seon.program-test/program-identity-attributes-are-exactly-the-declared-row-schemas` | **4 / 0 / 0** |
| `seon.program-test/process-resolved-shapes-match-the-current-declarations` | **1 / 0 / 0** |
| `seon.program-test/optional-attributes-are-replaced-exactly` (existing) | **3 / 0 / 0** |
| `seon.program-test/schema-row-properties-survive-and-retract-exactly` (existing) | **2 / 0 / 0** |
| `seon.program-test/declaration-admission-refuses-ambiguous-or-incomplete-rows` (existing) | **6 / 0 / 0** |
| `seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops` | **7 / 0 / 0** |
| `seon.fn-test/file-artifacts-and-manifests-are-byte-digested-and-deterministic` (existing, fixture-backed) | did not complete inside this lane's window; the JVM was saturated by concurrent lanes and the shared base was deliberately not forced |

`seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops` is
the class killer, and it landed in another lane's commit `0eba4b8c3`: that
lane committed `test/seon/fn_test.clj` while this lane's appended regression
was in the working tree. The regression is at HEAD and is correct; only its
commit attribution is wrong. Recorded rather than rewritten.

## Adoption on `default`, and the two refusals

1. **Self-inflicted, fixed.** The first shape resolver was a `defonce` delay
   over `seon.schema/registered-schemas`. That reads the ACTIVE PROJECTION —
   the cluster's stored schema, which still predated the new declarations
   mid-publication — so it refused, and `defonce` + `delay` CACHED THE
   THROWABLE for the whole process. For roughly one hour that made
   `bin/seon init --dev default` and every in-process `seon.test/run` fail
   for every lane in the JVM with `A program identity attribute declares no
   row schema` naming `:seon.ns/name` (the first family checked, not a
   missing declaration). The resolver now reads the AUTHORED resources
   (`seon.schema.edn/packaged-forms`) — the exact static scope the literal
   table had — and caches only a SUCCESS, so no refusal can outlive the
   reload that fixes it. `seon.program` was reloaded live and proven before
   the next publication; the message has not recurred.
2. **Foreign, still open at hand-off.** Adoption now reaches
   `program rows complete` (90 860 population rows indexed through the new
   derivation) and then fails in initialization rows with
   `:datahike/write-rejected {:kind :entity-id/missing, :cause "Nothing found
   for entity id [:seon.fn/sym \"seon.fn/rooted-source-files\""]}` →
   `The rebuilt source could not preserve test evidence.` That function was
   DELETED by commit `0eba4b8c3`, and `default`'s stored test evidence still
   refers to it. It is not this change: the publication indexes the whole
   program with the derivation in force before it is reached, and no
   `declares no row schema` occurs anywhere in the run. One earlier attempt
   exited 1 with `Source changed while current-src was being analyzed` under
   concurrent editors.

## Verification boundary

Measured on `default` (pid 45917) only, through `mcp__seon__eval_clj` in `jvm`
mode with explicit custody. `default` was never stopped, reforked or restarted,
and its shared fixture base was neither forced nor rebuilt. PROTECTED paths
(`src/seon/cluster.clj`, `src/seon/test/runner.clj`,
`script/seon/fresh_operator.clj`, `test/seon/test_support.clj`,
`test/seon/dev/*`) were not touched; `seon.test.runner/record-tx` is NAMED by a
schema declaration, not edited. Commits are path-limited. The batched isolated
gate is the orchestrator's proof, not a claim made here:
`tmp/orchestrator/gate-requests/program-shapes.txt`.
