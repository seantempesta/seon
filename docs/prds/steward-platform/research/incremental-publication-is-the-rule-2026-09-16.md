---
type: research
status: active
tags: [publication, performance, schema, config, class/p1]
---

# Incremental publication is the rule

Assignment: R1/R2/R3/R4/R7 and §3 of
[the inventory](recompute-from-scratch-inventory-2026-09-16.md), read end to
end, with AGENTS.md §§0–3 and §§5–7, plus workaround inventory items #14, #22
and #26. The lane was resumed on 2026-09-16 after its predecessor stopped
mid-draft; this note is the landing record.

## The one change

**Every changed input names the owner that installs its facts, and an
incremental publication runs exactly those owners.**

`:seon.source/change-class` (`resources/seon/schemas/seon.source.edn:18`) is
the complete, closed set of owners: `:program` (a `.clj`/`.cljc` file, whose
rows the artifact planner and `seon.fn/index!` own), `:schema-resource` (the
declarations `accrete-schema-population!` owns), `:config` (the shipped
document's initialization rows), and `:no-program-facts` (an input that owns
nothing — an issue note, a README, any Markdown). `populate-source!`
(`src/seon/cluster.clj:1650`) branches on the class set: a complete
publication supplies no classes and runs every owner, so the complete path is
unchanged; an incremental one runs only the named owners, so a schema
resource no longer re-indexes the program graph and a config document no
longer re-accretes the schema.

Before this change, `incremental-source-refresh!` classified anything that is
not `.clj`/`.cljc` as `:schema-resource` with no desired artifact, and
`plan-file-change` turned both facts into `structural` reasons — the single
branch that sent **40 of 47 decisions today** into `full-source-refresh!`
(R1/R2).

R3: `docs/seon/issues` left `source-roots` (`src/seon/cluster.clj:1685`) and
`.md` left `source-file?` (`src/seon/cluster/source.clj:94`). **1,743 of the
2,089 files that identified `current-src` owned zero program facts.** An
issue note now changes no digest at all; the publication's issue owner still
indexes the folder as it stands at every publication, and an explicitly
changed note is published through the `:no-program-facts` route so the index
converges immediately. `index-issues!` takes the publication's own root
instead of `"."`, so a fixture checkout indexes its own notes
(`src/seon/cluster/source.clj:563`).

R4: one packaged resource population is retained, keyed on the actual
resource URL and `declaration-stamp` (`src/seon/schema/edn.clj:367`). Both
`packaged-forms` and `declaration-digest` read it. A changed stamp replaces
the entry; parse exceptions are not cached; nothing is retained across a
stamp change.

## R7 — the hunk this lane did NOT land

`src/seon/config.clj` carries another continuation's uncommitted change (the
`compile-manifest` / `compile-settings` split and the removal of
`effective`'s default-cluster arity), and that lane is committing it now.
Landing a memo on top would have committed its unverified work under this
lane's gate, so R7 is recorded here instead of edited in. Against the file
as it stands tonight — `compile-settings` at `:379-418`, `default-document`
at `:308`, `compile-manifest` at `:419` — the exact hunk is:

```clojure
;; Immediately above `compile-settings` in src/seon/config.clj.
(defonce ^:private compiled-settings-cache (atom nil))

;; Rename the existing `compile-settings` body to `compile-settings*` and
;; give it `forms` and `document` as arguments instead of calling
;; `schema.edn/packaged-forms` and `default-document` inside it.

(defn- compile-settings
  [request]
  ;; The compile is a pure function of the declaration population, the
  ;; shipped document's bytes, and the request. Key on the observables,
  ;; exactly as R4 keys `packaged-forms` on `declaration-stamp`.
  (let [forms (schema.edn/packaged-forms)
        document (default-document)
        key [(schema.edn/declaration-digest) document request]
        cached @compiled-settings-cache]
    (if (= key (::key cached))
      (::compiled cached)
      (let [compiled (compile-settings* forms document request)]
        (reset! compiled-settings-cache {::key key ::compiled compiled})
        compiled))))
```

With R4 landed, `packaged-forms` is already one parse per stamp, so what
this hunk still removes is the document read, `admitted-default-document`,
three `validate-layer` calls, `validate-default-decisions`, the
`:seon.config/effective` validation, and the effective digest — all of them
recomputed identically. The measured target is `apply!`'s second identical
call: 2,101 ms writing zero datoms
([turn-bookkeeping-cost](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md):94).
`reconcile/plan` is deliberately NOT memoized: it is a `:db.fn/call` over
the writer's current database and must keep that authority
(`reference-code/datahike/src/datahike/db/transaction.cljc`).

## What is still accretive

The two non-Clojure owners install and update; neither retracts. A row
deleted from `config/default.edn`, or a declaration deleted from a schema
resource, survives an incremental publication and disappears only at the
next complete one. Filed as
[incremental-population-owners-accrete-but-never-retract](../../../seon/issues/incremental-population-owners-accrete-but-never-retract.md),
including why `seon.reconcile/plan` cannot simply be pointed at
`current-src`: its `managed-eids` admits every entity the managing process
first asserted, which on `current-src` is the whole program graph.

Workaround inventory #14 (`valid-source-manifest?`'s catch-all), #22
(`retrying-source-change`) and #26 (the four `full-source-refresh!` escapes)
are NOT dissolved by this lane. Three of the four escapes remain correct
fallbacks (no cached manifest, a structural program change, an analysis
refusal); the fourth — every non-Clojure input — is the one this change
removes.

## Regressions

- `test/seon/incremental_publication_test.clj` publishes a real fixture
  checkout through `cluster/refresh-source!` and then makes five real
  filesystem edits — a schema resource, `config/default.edn`, a `.clj`
  source, a `.md` owning nothing, and an issue note. Each asserts the
  decision is incremental, that it NAMES the expected owner, that no
  complete publication occurred, and that the owner's facts are present in
  the published database. Both Markdown cases additionally assert the
  published source digest is unchanged.
- `test/seon/publication_digest_test.clj` asserts the digest identity
  directly: an issue note rewrite and a new `src/README.md` leave both the
  digest and the per-file digests untouched, while a `.clj` edit changes
  them.
- `test/seon/schema/edn_test.clj` asserts one parse across 1,000
  `packaged-forms` calls plus `declaration-digest`, and a second parse after
  one resource's mtime advances.

## Inherited evidence

The inventory measured 113 hook cycles, 40 complete decisions out of 47,
46 cycles of at least 30 seconds totaling 6,087 seconds, p90 167 seconds,
and two cycles at the operator's 180-second bound.

Fresh live R4 baseline, before the memo: 2,785 forms; three `packaged-forms`
calls took 31.170208, 30.386667 and 29.599625 ms; their declaration stamps
took 1.554750, 1.860000 and 1.589500 ms. After reloading only
`seon.schema.edn` in the host JVM and rearming 1,096 contracts, four calls
took 1.972208, 1.553125, 1.526792 and 1.841292 ms, each returning 2,785
forms. That is a hot-reloaded Var proof, not an in-place adoption claim; the
repeatable form is
[the resource probe](incremental-publication-resource-probe-2026-09-16.clj).

## The live hook, measured 2026-09-16 23:18-23:40 UTC

The requested isolated before/after table is NOT obtainable tonight, and the
reason is itself the measurement. Every publication the hook attempted in
this window REFUSED, for a cause outside this lane:

```
:seon.schema/unresolved-predicate  seon.search/handle?
"Predicate seon.search/handle? has no admitted callable in the corpus projection."
```

That refusal comes from `publish!`'s scratch schema transaction — the
COMPLETE path — so it also proves the default JVM was still running the
loaded code, not this change. `logs/hook-debug.log` holds five publications
in the window; the "after" half of the table would need an adopted
publication, and none succeeded. Default (PID 41413) was not restarted,
reset or reforked.

The BEFORE half is exact, one row per requested kind, `first -> last`
`SOURCE_PROGRESS` timestamp per publication id:

| kind | file | decision | duration |
|---|---|---|---|
| Markdown, no program facts | `docs/prds/steward-platform/research/deletion-semantics-agents-and-turns-2026-09-16.md` | complete publication | 51.2 s |
| Markdown batch incl. an issue note | `plan/reset-batch-2026-09-17.md`, `docs/seon/issues/captured-prompt-history-is-disabled.md`, `research/reset-capture-history-2026-09-17.md` | complete publication | 176.7 s |
| schema resource | `resources/seon/schemas/seon.context.capture.edn` | complete publication | 75.3 s |
| Clojure file | `test/seon/context_capture_history_test.clj` | analysis then refused at `branch publication started` | 26 ms of tail only; the cycle's start predates the retained log |

Every one of those decisions carried the same reason set, and two of its
members are precisely what this change removes:

```
(:added-identity :attribute-retraction :component-or-cardinality-many-addition
 :component-or-cardinality-many-change :deleted :function-contract-change
 :missing-desired-artifact :removed-identity :schema-resource)
```

`:schema-resource` and `:missing-desired-artifact` are the two structural
reasons `plan-file-change` emitted for EVERY non-Clojure input. A Markdown
research note owning zero program facts paid 51.2 seconds of complete
rebuild. After this change it moves no digest and names
`:no-program-facts`; the regression asserts exactly that, because the live
hook cannot.

## Verification boundary

- `bin/test-fast --paths src/seon/cluster.clj src/seon/cluster/source.clj
  resources/seon/schemas/seon.source.edn src/seon/schema/edn.clj
  test/seon/incremental_publication_test.clj test/seon/schema/edn_test.clj
  test/seon/publication_digest_test.clj -- seon.incremental-publication-test
  seon.schema.edn-test seon.publication-digest-test`. That is an ITERATION
  result, not the isolated gate's proof: no per-worker isolation, no
  retained run root, no platform tier, no recorded result facts.
- `seon.cluster.source-test` was RUN and is NOT part of this lane's green.
  It is red at HEAD without this lane's files: a `--paths` overlay of one
  Markdown file alone on HEAD `6a2201f29` produced the identical 1 failure
  and 9 errors — every one `the source seal transaction was refused` /
  `:datahike/write-rejected :transaction/validation-rejected` at
  `src/seon/cluster/source.clj:81`, plus
  `incremental-first-party-publication-retains-complete-scalar-rows`
  expecting `:seon.db/invalid-write` and reading `nil`. Foreign cause, named
  here rather than repaired by this lane.
  Green: **19 tests, 1,083 assertions, 0 failures, 0 errors**, run
  2026-09-16 23:48:31–23:54:00 UTC on the HEAD-plus-paths snapshot of
  `e873756d0`.
- `non-program-inputs-have-their-own-publication-owner` is one long test
  whose single fixture publication exceeds the runner's 300-second silence
  horizon when all three test slots are busy (load average 25). It tripped
  the liveness backstop at exit 124 on an otherwise-passing run; it was
  rerun under `SEON_TEST_SILENCE_SECONDS=1200`. The declared-long silence
  allowance (`src/seon/test/runner.clj:569-576`) is a coordinator task
  mechanism and does not reach a `bin/test-fast` in-JVM test. The green run
  took 196.7 s for that test with one competing slot.
- One defect this rerun found and fixed: the first draft read the published
  digest off `source/current`, which carries only the branch and commit ID
  (`src/seon/cluster/source.clj:152-162`). `digest-before` was therefore
  `nil` and both Markdown digest assertions compared nothing to nothing —
  absence of signal read as health, the exact class AGENTS.md names. The
  regression now queries the digest datom the way `current-publication`
  does (`src/seon/cluster.clj:1944`) and asserts `(string? digest-before)`
  before comparing.
- No `bin/test` gate was launched. `--platform` was not run: this lane's
  slice touches no declared platform regression, and the two remaining
  slots were held by other lanes throughout.
