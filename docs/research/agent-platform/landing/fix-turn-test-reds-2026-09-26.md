---
type: landing
status: landed (rounds 1-3 accepted; round 4 374e918b7, 29abfed1a, 4e703c93a; round 5 d421b6e1a); tests owed to root
lane: fix-turn-test-reds-opus
created: 2026-09-26
---

# Fix turn-test reds (2026-09-26)

The reds come from fixtures that bypass the canonical producers (`support/program-row`,
`support/replacement-tx`) and from one fixture lookup that depended on map order
(`support/held-handle`). Each class is fixed once at its producer. Where the producer is a
held or ungranted `src` file, this note gives the exact hunk and the lane stops there.

## Still broken (not fixed here)

| Red | Class | Why it stays red | Owner / hunk |
|---|---|---|---|
| `a-replaced-result-is-shown-by-the-one-print-owner` (:2286) | surviving-owner defect | `turn/final-shown` renders without `:seon.sci.eval/ctx`, time limit and core-error policy, so the value renderer never selects the error's declared pair | `src/seon/turn.clj` (pipeline lane). Hunk in [settlement-renders-a-replacing-value-without-the-agent-context](../../../seon/issues/settlement-renders-a-replacing-value-without-the-agent-context.md) |
| `an-open-turn-read-refusal-refuses-the-writer-verbatim` (:1036) | surviving-owner defect | `seon.db` pull, q and db overwrite a passed refusal's `:seon.db/refused-read-operation` (q → pull) | `src/seon/db.clj` (root grant needed). Hunk in [a-read-handed-a-refusal-restamps-its-operation](../../../seon/issues/a-read-handed-a-refusal-restamps-its-operation.md) |
| `fault-unit-lists-agent-faults-newest-first-with-run-links` (:2033, third assertion) | surviving-owner defect | the card re-pulls the error and gets the occurrence turn as `{:db/id n}`: "Turn: identity unavailable" | `src/seon/error.clj` (lost-error-seams lane). Hunk in [a-fault-card-loses-its-turn-identity](../../../seon/issues/a-fault-card-loses-its-turn-identity.md) |
| `recovery-preserves-terminal-receipts-exactly`, `transitions-agree-with-the-model`, `virtual-turns-…` (duration) | design cost | 17-18 s each against a 5 s bound; each step is a file-store commit | harness. See [turn-test-property-members-pay-a-file-store-commit-per-step](../../../seon/issues/turn-test-property-members-pay-a-file-store-commit-per-step.md) and the options below |
| `seon.sci.eval-test/database-provenance-regenerates-the-base-…` | fixture digest class | same class as instrument-test, but `test/seon/sci/eval_test.clj` is held by impl-shape-mirror-malli-opus | hunk below |

## Classes, three questions, dispositions

### A. Undigested analysis rows handed to settlement (surviving producer bypassed)

Members: `unresolved-test-subjects-remain-symbol-values`, `batch-settlement-preserves-declaration-order`,
`settlement-refuses-a-divergent-program-change-since-opening`.

The fixtures built rows with `seon.fn/analyze-form`, which carries no definition digest. Settlement's
`row-tx` passes them to `seon.program/declaration-row`, which mints `(definition-digest row)` with no
resolver context (`src/seon/program.cljc:1284-1292`). The writer digests through
`seon.db/definition-digest-of` with the namespace's resolver context, so it always refuses the minted digest.

REPL, default pid 76020, `d/db-with` value adding namespace `fixture.batch`, form
`(defn ^{:malli/schema [:=> [:cat] :int]} target [] 1)`:
`declaration-row` minted `26f21bc4…`, the writer expects `1a8a8f01…`, `support/program-row` gave
`12f6ad5e…`, and the writer's digest of that row was also `12f6ad5e…`. Analysis took 38 ms and `program-row` 29 ms.

Disposition: retired assumption in the fixtures. They now take `support/program-row` (the canonical
producer). The producer mint is recorded as
[declaration-row-mints-a-definition-digest-without-its-resolver-context](../../../seon/issues/declaration-row-mints-a-definition-digest-without-its-resolver-context.md)
(src/program.cljc needs a root grant). Proven on isolated branches of the live store (pid 84287):
- the batch settles, and the test row stores `:seon.test/subject fixture.batch/target`;
- divergent settlement answers `:committed`, then `:seon.turn/program-row-changed-after-open`; the stored
  source stays `… scratch [] 1)`; the identical re-settle answers `:committed`.

### B. A program row upserted onto a stored declaration (root's scope note)

Members: `seon.instrument-test/host-diagnostics-use-the-loaded-vars-arglists` (fixed, 0d3b96015) and
`seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks` (held file).
In `verify-0440.out.d`, only these two members carry this refusal. The error-test reds in 004 are other
classes (`missing-component-schema`, worker drift).

1b3c0f1cb made `program-row` the analyzed desired row again and added `replacement-tx`, but did not convert
the callers that write that row as a plain upsert. An upsert keeps the owned attributes the new definition
drops, and the writer digests the merged value. REPL, `seon.id/valid?` with the fixture's source: 5 stale owned
attributes remain (`:seon.fn/arities :call-arities :calls :doc :file`); the upsert is refused (expected
`478ea492…`, offending `121699654…`); `support/replacement-tx` commits.

test_support cannot fix this alone. A row cannot carry retractions, and teaching `transacted!` to replace
anything that looks like a declaration would be structural guessing in the one generic fixture writer. The
producer that writes a stored identity already exists (`replacement-tx`). `program-row`'s docstring now says
so. Callers convert with one line each:
- `test/seon/instrument_test.clj:1446` converted (free file).
- `test/seon/sci/eval_test.clj:202` (held; for its owner or root): replace
  `(test-support/transacted! connection [admitted])` with
  `(test-support/transacted! connection (test-support/replacement-tx before '[:seon.fn/sym seon.id/valid?] "(defn valid? {:malli/schema [:=> [:cat [:int {:min 1}] :string] :boolean]} [length id] true)" {:seon.schema.admission/source :agent}))`
  and keep `admitted` for its `:seon.fn/source` reads at :210 and :248.

### C. The fixture handle depended on map order (surviving test_support defect)

Member: `virtual-turns-…` assertion :698, "fresh acquisition cannot restore a private object". `held-handle`
returned any handle whose connection was identical. Armed agents on the fixture's live branch borrow that
connection under `[branch agent-id]`. Once the context-state has more than 8 entries it is a hash map, so an
agent's handle came back and `fork-cluster-ctx` forked that agent's private universe. REPL, pid 84287: with
10 entries the old lookup returned `[branch "x3"]`, and the fixed lookup returned the fixture handle. A
separate probe refuted leakage: an agent's `def` in its own ctx is not visible from the fixture handle's ctx.

Fix: `held-handle` returns only the handle acquired for no agent (the key's agent id is nil). Every
`execution-handle` caller inherits the fix: `fork-cluster-ctx`, `attempt-routing`, `with-database`'s parent,
and `agent-tx`'s cluster lookup. On the live connection, whose only entry is root's agent handle, the new
lookup answers nil, where the old one answered root's handle.

### D. Retired assumptions

- `transitions-agree-with-the-model` (smallest failing case `[[:open "r1" "a1"] [:plan "r1" "digest-a"]]`): the
  plan named `user`, which is not stored on a test branch. The writer refuses a bare namespace identity by name
  (`missing :seon.program/definition-digest … Entity: #:seon.ns{:name user}`). The plan now names the stored agent
  namespace `my.agents.a1` (REPL: commits, 61 ms). The duration red remains (class E).
- `fault-unit-…` "Faults (2)" and newest-first: the message never enters `seon.error/signature` (D13), so two
  messages from one site are one fault. Each fixture fault now names its own operation. REPL: `[:h2 "Faults (2)"]`,
  order `["newer fault" "older fault"]`, and no run link on the newer fault. The run-link assertion stays red (table above).
- `virtual-turns-…` datom counts `[16 6 2]`→`[16 5 2]`, `[30 16 2]`→`[30 13 2]`, 48→45: `(+ 1 1)` records no read
  evidence since ca17618c0. REPL: the evaluation's `:seon.cluster.eval/read-evidence` is `[]`, so it has no datom.

### E. Duration (why turn-test takes 85 s)

These durations are from the stored run abab00602fe6 (gaps between member completion transactions). The
three property/integration members account for 53.4 s: recovery 17.8 s, transitions 18.1 s (6.1 s to fail after 12
trials, plus 11.8 s of shrinking), and virtual 17.5 s. The other 29 members take 31.8 s, mostly 0.5-2.2 s each.
Measured per step on a branch of the live store: branch acquire+release 72 ms, `seon.db/transact!` 48-96 ms, raw
Datahike commit 40 ms, in-memory commit 5 ms, and a fresh memory store with 1,219 attributes 1,150 ms. A recovery
trial is one branch and about 6 commits (≈0.5 s × 30 trials). A green state machine is estimated at 35-45 s (60
trials).

Three options for root, simplest first:
1. Fewer trials (recovery 30→8, state machine 60→8) plus one setup transaction per trial. This fits 5 s and
   weakens coverage.
2. **Recommended:** harness member worlds as in-memory branches of one prepared commit (≈5 ms per step), the
   ruled target (fast-harness lane). This keeps coverage and costs a harness slice.
3. Declared long bounds with measured reasons. These need owner authorization over 10 s and remove nothing.

### F. write-back test

`an-agent-replacement-merges-and-writes-back-its-exact-span`: the submission was refused, the refusal was
discarded, and `await-event!` then waited 20 s for turn `nil`. REPL reproduction (candidate branch of an
isolated branch, the same helpers): under the member's custody `virtual-turn!` answers "This write names branch
:…-wb-c, which is not the writing cluster's branch :agent-…". With no custody bound, or under the candidate's
custody, it returns a turn id. Fix (2c9d6aa3b): `submit!` runs under the candidate connection's custody, and a
refused submission fails at once with its value. Not proven: the armed graph closing that turn, and the merge
and write-back steps. Only root's run proves them.

## Round 2 (root, verify-0610.out.d): digest class A, unowned class B, fresh-store config class C

### A. "The stored definition digest … is not the one its definition produces", fixed at test_support (6380f2d0c)

The members in 0610 are db-test `arity-components-and-callers-are-checked-in-the-final-state` (001),
test-runner-test `a-metadata-only-change-changes-the-reach-digest` (004) and publication-validation
`documentation-writes-do-not-check-unrelated-arities-or-renderers` (009). No fn-test member carries this class
in 002; its reds are dependency analysis, missing schema and spec-transaction assertions.

- Producer: `support/revised-row`'s 3-arity pulled the namespace row with `db/pull '[*]` and passed nil when
  the namespace was not yet stored. The fixture writes its namespace beside the row, so the digest lacked the
  resolver context the writer uses. `program-row` already did it right (the stored row via
  `seon.db/declaration-value`, else the canonical `namespace-row`). That lookup is now one private
  `digest-namespace-row`, shared by both. REPL, isolated branch of the live store: HEAD's revised-row refused
  the db-test arity world and the fixed copy committed it. For a stored namespace (`seon.id`), revisions digest
  identically before and after, so the change is neutral where the namespace exists. This clears db-test arity.
- test-runner-test minted its changed test row's digest through `program/declaration-row`, which holds no
  resolver context (issue below). It now uses `revised-row`: first write ok, digests differ, source kept,
  second write commits.
- publication-validation upserted `{:seon.fn/sym seon.id/id :seon.fn/doc …}` with no digest. A doc is owned,
  so the stored digest no longer matched. It now writes `support/revision`, which commits. The report's
  attributes are `#{:seon.fn/doc :seon.program/definition-digest :db/txInstant}`, and the assertion now
  expects that. The test still uses `with-redefs-fn` over `seon.db` Vars, which violates the no-with-redefs
  ruling; that is left to its owner.
- `program.cljc` (granted): minting fn/test digests only for namespace and schema rows is correct, but it
  reds `program_test`'s `reader-events-have-one-canonical-declaration-row` (held). I reverted it, saved it as
  `tmp/fix-turn-test-reds-program-mint.patch`, and recorded the pairing in the issue. None of the listed
  members needs it.

### B. "Complete component validation refused: unowned-entity" (both files held; hunks verified)

The writer rule (`src/seon/db.clj:3719`, identity-less entities must be owned) is the law. The producers are
the two fixtures, which write identity-less, ownerless entity maps. There is no shared test_support producer.
Members in 0610: db-test `a-store-without-program-schema-admits-its-writes` (001) and web-debug
`reverse-blocks-do-not-depend-on-the-graph-page` (008). error-test in 018 carries `missing-component-schema`
(a component attribute with no schema), which is a different class and left to its owner.

- `test/seon/db_test.clj:2044-2047` (fix-write-cost-opus): install an identity attribute with the dial and write
  the dial under it. Replace the two `transacted!` forms with
  `[{:db/ident :sample.schemaless/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity} {:db/ident :seon.config.eval.result/blob-threshold …}]`
  and `[{:sample.schemaless/id "dial" :seon.config.eval.result/blob-threshold 4096}]`. REPL, fresh file store
  under the member's projection: the bare map is refused `unowned-entity`, the identified one commits, and the
  read answers `[4096]`.
- `test/seon/render/web_debug_test.clj:558-571` (impl-debug-page-ui-opus): add
  `{:db/ident ::id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}`
  to the extra schema, and write `{::id "left" ::left …}`, `{::id "right" …}`, `{::id "hidden" …}`. REPL on an
  isolated branch: bare is refused `unowned-entity`, identified commits, and the reverse query finds the entity.
  The page assertions after the write are not probed.

### C. `config/apply!` on a fresh in-memory store (mcp-test artifact-lifecycle, mcp_test.clj:217): no fix here

Reproduced on a fresh memory store with the installed attributes. The first refusal is
`Nothing found for entity id [:seon.db.process/id "seon.db.process/config"]`: boot writes the process rows
(`seon.cluster/missing-process-rows`, `src/seon/cluster.clj:1306`, written at `:1570`) before config, and the
fixture has none. With boot's process rows written first, apply is refused next: "Configuration names
functions with no program row: [:seon.call-preparation/supplier my.program/supplied-context …]". That check
(`require-functions!`, config.clj:645) resolves function symbols to current rows, which is correct. The names
are old (73ba7c155, 803d463ef), so neither 178a67487 nor 0d3b96015 causes this: a program-less store can never
take a config apply. Fixing config would break the function-row law, and seeding process rows alone moves the
refusal one step. Options, simplest first:
1. **Recommended now:** run the scenario that needs config (`jvm-exceptions-keep-the-whole-cause`) on the
   branched member world, and keep only the blob-identity scenarios on the fresh store (test-side, in
   mcp_test.clj, held by delete-r8-r6-r10 and impl-floor-fipp).
2. Seed the fresh store with process rows and the program partition: seconds per store, and it copies
   boot's work.
3. The ruled target: an in-memory branch of the prepared commit gives blob isolation and program rows
   (fast-harness lane).

### Round-2 root runs

```sh
bin/test-check default --ns seon.program-test
bin/test-check default --test seon.db-test/a-store-without-program-schema-admits-its-writes
bin/test-check default --test seon.render.web-debug-test/reverse-blocks-do-not-depend-on-the-graph-page
bin/test-check default --test seon.cluster.mcp-test/artifact-lifecycle-preserves-identity-paging-and-retraction
bin/test-check default --test seon.db-test/arity-components-and-callers-are-checked-in-the-final-state
bin/test-check default --test seon.test-runner-test/a-metadata-only-change-changes-the-reach-digest
bin/test-check default --test seon.publication-validation-test/documentation-writes-do-not-check-unrelated-arities-or-renderers
bin/test-check default --ns seon.turn-test
```

Verification limit: round 1's load-string syntax copies read the running archive's files (the JVM's working
directory is `data/source/<commit>`), not the working tree. Round 1's syntax proof is therefore clj-kondo
only; round 2's copies were loaded by absolute path.

## Round 3 (root rulings: every file granted)

| Slice | Commit | What | Proof |
|---|---|---|---|
| declaration-row mint | eb848eacd | `program/declaration-row` mints a digest only for namespace and schema rows. A function or test digest comes from `seon.db/definition-digest-of`, and an undigested row refuses at the writer by the missing key. `program_test:833` expects a digest on the schema case only; the function and test cases carry none. | Production callers checked: `sci.eval/definition-row` and `seon.fn` source rows digest first; SCI's reader stage calls it only for namespace rows (`sci/eval.clj:386`); `:seon.program/declaration-row` does not require the digest for those families. |
| class B | 08b57bfcd | db-test's schemaless dial under `:sample.schemaless/id`; web-debug relationship entities carry `::id` | the round-2 REPL probes above |
| class C | 515b7e296 | Option 1 as ruled, with one correction: the blob scenarios need config too (`config/effective-in` refuses a cluster without its row), so all five scenarios run on the member's branched world. The fresh-store counter (`with-redefs-fn` over `support/with-fresh-database`) and the `:seon.test/fixture-observation` reason go. `executed` derives from the scenario list (it expected `:retraction` for `:blob-only`). | Diagnostic run of the edited deftest in a scratch copy, on an isolated branch: all five scenarios executed, 37 pass, 2 red in 3.4 s (below) |
| publication-validation | 42685bc27 | `with-redefs-fn` becomes the armed wrappers' profile call counters (`seon.profile/cells`), with a positive control: the refused call-arity write increments `report-arity-comparison`. The old test counted `arity-mismatches-with`, whose wrapper no write reaches (0 even on the arity refusal), so it was vacuous. | Diagnostic run: 5 pass, 0 fail |

Two reds remain inside the artifact scenarios. Neither is the fresh-store class; both are for the floor lane,
which converts mcp_test next:
- `jvm-exceptions-keep-the-whole-cause`: "the reply keeps every cause link: :seon.probe/member". The shown text
  prints the data as `#:seon.probe{:member 1}`, so the literal `:seon.probe/member` is absent. This is printer
  grammar (namespaced-map syntax) against a substring assertion.
- `retrievable-artifacts-commit-no-transaction`: the advertised requery evaluates through
  `seon.cluster.boot/selected-instance`, whose output contract refuses the test's hand-built `running-instance`
  map (it has no `:seon.boot/config`).

The profile counters are JVM-wide, so a concurrent writer can make the doc-write delta nonzero and cause a
false failure, but it cannot hide a call. `with-redefs-fn` was equally global.

## Round 4 (root run verify-1210, JVM at archive 65bf078ec, which contains rounds 1-3)

### Committed, each clearing its class at the producer

| Commit | Class | Producer and fix | Members it clears |
|---|---|---|---|
| 374e918b7 | digest after the mint retirement (my eb848eacd regressed two members) | program-test's `analyzed-row-tx` handed undigested `analyze-form` rows to settlement. They passed before only because `declaration-row` minted a digest, which the writer accepts when the namespace row carries no resolver context. The helper now digests through `revised-row`. The forged digests (`"aaaa…"`) become two real revisions, and the cross-namespace test row comes from `support/program-row`. | program-test `changed-runtime-redeclaration-builds-a-real-replacement`, `opening-basis-divergence-is-only-claimed-when-it-is-measurable`, `digest-map-compares-two-fixture-branches-from-one-commit`, `typed-cross-namespace-deletion-retracts-function-and-test` |
| 29abfed1a | `missing-component-schema` / undeclared synthetic schema | The writer derives its projection from stored schema rows (`db/carried-projection`), so a projection a fixture carries on its connection declares nothing. `test-support/schema-rows` is now the one helper that declares synthetic `[key definition]` forms as canonical schema rows. error-test and db-test's codec fixture use it; db-test now includes the component child schema. web-debug's private `declare-schemas!` is deleted and its four callers use the helper. REPL on isolated branches: before the rows, each write refuses `missing-component-schema`; after them, each commits. | error-test `complete-error-children-validate-through-the-writer`, db-test `component-expanded-pull-evidence-detects-a-child-only-change` |
| 4e703c93a | same class | fn-test's `canonical-schema-rows` over the branch projection refused the undeclared key ("Missing schema declaration"). The fixture now uses `schema-rows`, whose row commits with a digest. | fn-test `publication-writes-one-definition-digest-per-declaration-family` |

sci.eval-test `database-provenance-…` (eval_test.clj:202) was stale in 1210: the fix, `8074989b9`, is not in
archive 65bf078ec (the archive's copy of the file has no `replacement-tx`). It clears on HEAD.

### Classified, not fixture classes (routing)

| Red | Class | Evidence | Owner |
|---|---|---|---|
| turn-test `settlement-refuses-a-divergent-program-change-since-opening` :1546 | surviving-owner defect, Datahike | The divergent row commits. `declaration-written-by-run?` returns run a's receipt when asked about run b, and the same query with its clauses reordered answers nil. [a-history-query-drops-a-bound-input-by-clause-order](../../../seon/issues/a-history-query-drops-a-bound-input-by-clause-order.md) | Datahike fork (query executor) |
| turn-test `a-refused-generated-form-records-its-refusal` :331 | surviving-owner defect, error render pair | The shown text is a render diagnostic: `seon.error/render-undeclared-ai` → `undeclared-alternative-finding` refused its argument (`:seon.fn.arity/input`, expected `:map`), so the refusal's message never shows. Blob 36afb1d2… on default. | src/seon/error.clj (lost-error-seams) |
| turn-test `a-replaced-result-is-shown-by-the-one-print-owner` :2292 | terminal-refusal half of the print owner | d3ecb9333 fixed the disposition half. The recorded-error half still differs; not probed this round. | pipeline lane (turn.clj) |
| turn-test recovery / transitions / virtual (duration) | design cost | unchanged from class E above (28.7 s transitions, 17.4 s recovery, 13.6 s virtual) | fast-harness lane |
| web-debug `outline-origin-survives-the-issues-deletion` :1073 | RULED (root): the rule was too strict. Fixed in d421b6e1a, see Round 5. Earlier finding kept for reference: | The fixture `retractEntity`s an issue created by `issue/add!`. `seon.db/retention-check` (db.clj:4509) refuses any change of a created issue's authority (`(and (:seon.db/creator prior) changed-authority?)`), even with zero tests and even by its creator, so a created issue can never be deleted. Either the rule is too strict (the creator should delete its own issue) or the test's deletion leg is a retired assumption. | root ruling, then db.clj or the test |
| test-runner-test `result-recording-is-total-under-concurrent-test-retraction` | surviving-owner defect | `seon.test.runner/record-tx` throws "Test completion has no surviving test definition" (transaction outcome unknown), where the test requires recording to be total. | src/seon/test/runner.clj |
| fn-test `declared-function-values…`, `re-index-replaces…`, `the-indexer-emits-no-attribute…` | dependency analysis | "clj-kondo holds no analysis of seon.db / seon.effect" (first-party namespaces absent from the analysis these fixtures read) | routed to root (dependency-analysis class), acknowledged |

## Round 5 (root ruling: a creator's whole-issue retraction is a deletion)

`d421b6e1a`:
- `seon.db/retention-check` now takes the set of entities the write left with no datoms. When the writer
  (tx-meta `:seon.db/user`) is the entity's authority, it admits that entity's key. Every other clause is
  unchanged, so another writer's deletion or authority change is still refused.
- Regression: `db-test/an-issue-deletion-by-its-creator-is-not-an-authority-change`. An issue made by
  `issue/add!` with a real test: another writer's `retractEntity` and its `:seon.issue/created-by` change are
  both refused on `:seon.issue/tests`; the creator's `retractEntity` commits and the issue is gone.
- web-debug `outline-origin-…` now deletes its issue with `:seon.db/user` as its creator.

REPL: the edited check, evaluated in a scratch namespace over real snapshots from `d/with` reports on an
isolated branch:

| Case | Before | After |
|---|---|---|
| creator deletes | refused | admitted |
| other writer deletes | refused | refused |
| other writer changes authority | refused | refused |
| creator removes tests from an unstarted issue | admitted | admitted |

Pre-commit static checks (preamble "PRE-COMMIT STATIC CHECKS", REPL form for uncommitted work):
`seon.contracts-compile-test/check` over db.clj, db_test.clj and web_debug_test.clj found 0 problems in
258 ms. A malformed probe contract finds 1, so the check is live. Every `resources/seon/schemas/*.edn` reads
as EDN.

Root runs:

```sh
bin/test-check default --test seon.db-test/an-issue-deletion-by-its-creator-is-not-an-authority-change
bin/test-check default --test seon.render.web-debug-test/outline-origin-survives-the-issues-deletion
bin/test-check default --ns seon.issue-deletion-test
```

## Deleted / net lines

| Commit | Files | + | − |
|---|---|---:|---:|
| 0d3b96015 | turn_test, test_support, instrument_test | 54 | 70 |
| 2c9d6aa3b | write_back_test | 10 | 5 |
| 6380f2d0c | test_support, test_runner_test, publication_validation_test | 25 | 22 |
| eb848eacd | program.cljc, program_test | 11 | 4 |
| 08b57bfcd | db_test, web_debug_test | 17 | 6 |
| 515b7e296 | mcp_test | 14 | 22 |
| 42685bc27 | publication_validation_test | 34 | 16 |
| 374e918b7 | program_test | 20 | 14 |
| 29abfed1a | test_support, error_test, db_test, web_debug_test | 43 | 25 |
| 4e703c93a | fn_test | 2 | 3 |
| d421b6e1a | db.clj, db_test, web_debug_test | 47 | 11 |

Net across seven code commits: src +5/−3 (program.cljc), tests +160/−142. The additions are mostly comments stating the owning rule at each fixture. Deleted: three hand-built `analyze-form` row paths in turn_test (≈45 lines). Added: the
`held-handle` agent-id guard, the docstring note and the custody wrapper.

## Root runs

```sh
bin/test-check default --ns seon.turn-test
bin/test-check default --test seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span
bin/test-check default --test seon.instrument-test/host-diagnostics-use-the-loaded-vars-arglists
```

Expected: turn-test clears the digest members, the plan case, fault count/order, datom counts and :698. It keeps
the print-owner, read-refusal, fault run-link and three duration reds until their hunks land. Default booted
from committed archive 0f78240df with hook publication off, so these test edits reach the JVM only after
adoption of 0d3b96015 and 2c9d6aa3b.

## Probe hygiene

Scratch namespace `fix-turn-test-reds.scratch` and the copies `…turn-copy`, `…write-back-copy`,
`…instrument-copy` and `…support-copy` were loaded with `load-string` and then `remove-ns`'d. No default Var was
redefined. The isolated branches `:agent-9c27fc2b2815` (removed by the 00:16Z restart) and `:agent-fc78456a8785`
were released, `:fix-turn-test-reds-wb-c` was retired, the MCP branch `fix-turn-test-reds` was retired, and the
`tmp/fix-turn-test-reds-wb-*` directories were deleted. One raw `datahike.api/transact` of a single datom ran on
my own disposable branch to measure the commit floor. No RESET NEEDED.
