---
type: landing
status: landed 44e7ad4b0 76d644bd2 b95676d59 e62d8489c 88099feb0 08a9d70e1 66a35ae86; tests owed to root
lane: fix-unbounded-capture-opus
created: 2026-09-25
---

# Lane fix-unbounded-capture (2026-09-25): errors and facts copy their world

Status: round 1 (before the nuke) fixed one producer and recorded the store census. Round 2 (grant
widened, fresh default pid 39484) wrote the admission edge, the per-node multiplier fix and one more
producer. Both class regressions are committed. No test request was run (LOAD RULE); root runs the
list below. **RESET NEEDED: no.** Hook publication was on while I edited, so default loaded an
intermediate `admit.clj` (it resolves `sci-context?` but not `leaf-edn`). Root should adopt the
committed HEAD.

## Broken things first

1. **Admission copies world objects instead of referencing them.** `seon.sci.admit/open-node`
   (`src/seon/sci/admit.clj:245-360`) enters any record or map. The SCI ctx is a
   `sci.impl.opts.Ctx` record, the environment is a `seon.env.Environment` record
   (`src/seon/env.clj:36`), and the schema projection is a plain map. All three are walked
   node by node. Only the Datahike database value and connection are references today, through
   registry identity-only declarations (`resources/seon/schemas/seon.db.edn:171-192`), and only
   when the admission is handed a projection.
2. **Each admitted byte of a world allocates about 350 bytes.** Measured on default (pid 79957,
   JVM mode, `admit-value` with a 1,000,000-byte bound and the live projection):

   | value | outcome | ms | allocated |
   |---|---|---:|---:|
   | SCI ctx (`sci.impl.opts.Ctx`) | over-bound at 1,000,021 B | 232 | 349 MB |
   | environment (`seon.env.Environment`) | over-bound at 1,000,015 B | 192 | 350 MB |
   | schema projection (map) | over-bound at 1,000,011 B | 200 | 350 MB |
   | Datahike db, projection handed | identity object node | 2 | 0 MB |
   | Datahike db, no projection | `:unserializable` | 3 | 0 MB |
   | Datahike connection, projection handed | identity object node | 1 | 0 MB |

   So a world-carrying error under the default 8 MiB evidence bound costs about 2.8 GB and 1.6 s per
   admission pass. `seon.error/prepare` makes several passes: the full evidence
   (`error.clj:527`), `admitted-size` (`:598`), the `fit-fact-payload` halving loop (`:437`), and
   `prepare-result` (`:460-490`). `prepare-result` renders the offending value through
   `render.value/prepare`, which admits it with `:seon.sci.admit/unbounded? true`
   (`render/value.clj:572`), then stores that complete HTML text as a blob. An **unbounded**
   admission of a world is the 22.8 GB render-refusal case. Per node, the cost is paid by armed
   public functions called per leaf/node: `seon.sci.admit/canonical-edn` and
   `seon.schema/identity-only-projection-in`.
3. **Evidence admission in `seon.error` is never handed the projection.** `bounded-error-admission`
   and `bounded-admission` (`error.clj:238-314`) build requests without `:seon.schema/projection`.
   So even the database and connection become `:unserializable` markers there, not references.

## What the store held before the nuke (measured 2026-09-25 ~22:05Z, `data/store` 124 GB)

- 13,328 konserve files larger than 1 MB, 34.75 GB in total.
- **World-carrying blobs.** 1,661 files (6.44 GB) begin with a schema projection
  (`:seon.schema.projection/canonical-keys` or `/registry` in the first 200 KB). Another 4 files
  (2.32 GB) contain a printed `#sci.impl.opts.Ctx{...}`. That is 1,665 files and 8.76 GB, or 25% of
  the large-file bytes. The census is a first-200 KB grep, so it is a lower bound.
- Largest files:
  - `2f16ee4b…ksv` 894,747,885 B (Sep 24 12:41): a blob whose text begins
    `(not (identical? #sci.impl.opts.Ctx{:bindings {}, :env #object[clojure.lang.Atom …`. This is a
    clojure.test failure whose printed actual form carries the whole ctx.
  - `2c541454…ksv` 842,177,871 B (same minute): the same class.
  - `191adc52…ksv` 554,091,702 B: a test failure message embedding a printed test.check `:shrunk`
    `#error{…}` from a `seon.error/recording` refusal.
  - `11c6128d…ksv` 200,928,716 B and `33aabe60…ksv` 96,886,735 B (Sep 25 12:24-12:26): MCP eval
    artifacts. They are `{:seon.cluster.eval/source "(help)" :seon.db/db {identity} :seon.schema/projection {…whole projection…}}`,
    an evaluation request inside an error value, stored whole by `seon.cluster` (`cluster.clj:479`).
    The database is already a reference there; the projection is not.
- **Error facts on the default branch.** 97 facts carry `:seon.error/data-size`, totalling
  17,232,223 B, and 106 carry `:seon.error/data-blob`. One reached the 8 MiB bound: operation
  `seon.turn/turn`, 8,388,635 B. The next largest are 305,510 B (×11,
  `seon.cluster.agent-test/fixture-evaluate`), then 256,758 B and 158,376 B (`seon.db/transact!`)
  and 144,308 B (`seon.context/capture-tx`). The world copies that cost the most bytes are in
  blobs, not in datoms.

## Root causes

- **(A) Producers put their world into `:seon.error/offending` or `:seon.error/data`.** `rg` finds
  318 `:seon.error/offending` constructions. The ones that carry a world or a request (offending
  `request`, `unit`, `environment`, `state`, `snapshot`, `population`, `run`) are:
  - `src/my/program.clj:34`
  - `src/seon/config.clj:347`, `:542`
  - `src/seon/ai.clj:1181`
  - `src/seon/issue.clj:846`, `:854`, `:1336-1430`, `:1541`
  - `src/seon/env.clj:90`, `:106`, `:212`, `:229`, `:303`, `:422`
  - `src/seon/effect.clj:690`, `:717`, `:779`, `:806`
  - `src/seon/context.clj:70`
  - `src/seon/db.clj:2475`, `:4686`
  - `src/seon/shell/jvm.clj:447`
  - `src/seon/edit.clj:348-480`
  - `src/seon/render.clj:167`
  - `src/seon/render/transcript.clj:791`, `:1049`
  - `src/seon/render/value.clj:256` (fixed here)
  - `src/seon/cluster/process.clj:237`, `:245`
  - `src/seon/test/runner.clj:1518`
  - `src/seon/sci/kernel.clj:679`

  Each should name the member value (the key or keys that were wrong), and should reference the
  world rather than copy it: the database by `:seon.error/basis`, a turn or evaluation by its entity
  ref, a run by its run ref.
- **(B) The storage edge references only two world types.** The fix is to declare the other three
  world types identity-only, using the existing mechanism that already makes the database and
  connection references.

## Fix committed

- `src/seon/render/value.clj` `node-id`: the missing-root refusal's `:seon.error/offending` is now the
  root-identity members the unit has (`select-keys` of five keys), not the unit carrying the ctx and
  database. Linted clean: 0 errors; the 2 warnings are pre-existing shadowed vars. It has not been
  run at the REPL, because root's wind-down stopped probes.

## Round 2: the edge, written (76d644bd2)

- `seon.sci.eval.edn` `:ctx-reference`, `seon.env.edn` `:environment-reference` and
  `seon.schema.edn` `:projection-reference` are identity-only `:fn` declarations, the same mechanism
  `seon.db.edn` uses for the database and the connection.
  - The predicates are `seon.sci.admit/sci-context?` (the `sci.impl.opts.Ctx` record),
    `seon.env/environment?` and `seon.schema/projection-value?` (a map with a registry and a
    fingerprint).
  - I did not mark `:seon.sci.eval/ctx` itself, because its `ctx?` predicate is any map with `:env`.
    It would have turned a user's `{:env "prod"}` into a reference.
- The identities are themselves made of references:
  - ctx → environment, projection and custody connection;
  - environment → cluster name, basis-t, connection and projection;
  - projection → fingerprint and version.
- `seon.error` hands its projection to all six evidence admission helpers
  (`project-observation` gains a 3-arity; its 2-arity callers in `instrument.clj` are unchanged).
- **The multiplier.** The walk derives `schema/identity-only-projector` once per walk instead of
  calling the armed `identity-only-projection-in` per node. Scalar leaves print as a fixed prefix
  plus `print-method`, byte-identical to `canonical-edn` on every scalar face (probed): 458 B instead
  of 7 KB per leaf.
- REPL proof on default: the committed `admit.clj` was loaded as the scratch namespace
  `scratch.fuc-admit`, and three test declarations were added to the live projection with
  `projection-with-schema`. No default Var was redefined.
  - A value holding db, conn, ctx, env and projection admits in 2.36 ms and 1.09 MB. It stores
    5,823 B of EDN, every world object as its typed identity. Before, 1 MB of the ctx prefix alone
    cost 349 MB and 232 ms.
  - A 2,000-entry map allocates 14.99 MB instead of 41.8 MB, with identical EDN; time was unchanged
    (10.6 ms against 9.1 ms, one sample).
- Regressions:
  - `seon.sci.admit-test/a-value-carrying-its-world-admits-references-not-copies`: under 5 ms and
    5 MB, and asserts the typed identities.
  - `seon.error-test/a-fifty-thousand-entry-offending-value-stores-bounded-text-and-a-digest`: the
    fact's `data-edn` stays under the 16,384 B family bound; the staged blob digest retrieves all
    50,000 entries.
- Lines: admit +60, schema +36, error +5 net, schema resources +22, tests +65. There are no
  deletions. This is over 100 added source lines, written under root's widened grant.

## Round 2: producer (b95676d59)

- `src/seon/test.clj:1944`: the schema-drift failure message printed the whole drift with
  `pr-str`. It now uses `value/bounded-text`.
- The test-runner assertion text that produced the 895 MB ctx blob was already fixed by
  `aa00e58fc` (Sep 24 17:39), after that blob was written (12:41).

## Round 3: the three remaining producers

- **`e62d8489c` `error/prepare-result`.** The blob is now the value's admitted artifact: the record
  that `read-artifact` and `artifact-value` read, the same one `get_value` stores. It is written
  under the storage bound, with world objects as references.
  - Deleted: the unbounded HTML-face `render.value/prepare` call in the recording path (1 line),
    which rendered and stored a second, larger copy of the offending value.
  - Caps come from the request (`prepare` passes them), otherwise from the SCI context's
    environment, otherwise a named refusal.
  - `error_result_test` changed its retired assumption (blob equals complete HTML text) to: the blob
    reads back as an artifact whose value is the original value.
- **`88099feb0` `kernel.clj:679`.** A failure that could not be admitted is offended by its
  classifying base (at, layer, operation, message); the failure is no longer carried whole. The
  admission Throwable is still kept.
- **`08a9d70e1` `render.clj:167`.** `request-profile` offends with the request's sorted key names,
  the shape the same function already used three lines above; it no longer carries the request.
- **`render.clj:1098` is not changed, on purpose.** The admission it reaches (`kernel.clj:642`)
  hands the projection, so a world inside a render result is now a reference: the class is closed
  there. What stays unbounded is the render function's own output (hiccup or text), which by
  ruling is never clipped. Its cost follows the output the function already built, about 65 B
  allocated per byte after the leaf fix. Bounding it by the 8 MiB storage bound would make large
  pages go missing rather than bound their presentation.

## Producer sweep: one-line conversions (files outside this lane's grant; not edited)

They share one shape: a refusal about a missing or ill-shaped member offends with the whole
request. The conversion is `:seon.error/offending <request>` → `:seon.error/offending (vec (sort (keys <request>)))`
when a member is absent, or → `(get <request> <member>)` when a present member is wrong. The world
itself becomes the existing reference (the database by `:seon.error/basis`, the run by its ref).
Since `76d644bd2`, any world object these still carry admits as a reference, so each remaining copy
is bounded by the request's own plain data. Deletions: each site deletes one whole-request copy
(one line each, about 40 lines in total).

| site | function | conversion |
|---|---|---|
| my/program.clj:34 | read-result | `(ex-message failure)` is already in the message; offending → the requested fact keys |
| config.clj:347 | admit-initialization | offending → `(type population)` plus the first non-map entry |
| config.clj:542 | compile-manifest | offending → the missing configuration keys (set difference) |
| ai.clj:1181 | truncation | offending → the snapshot's terminal-event fields and byte count; the stream stays in its blob |
| issue.clj:846,854 | detector-rows | offending → the detector symbol |
| issue.clj:1336-1430 (7 sites) | create-tx / assign | offending → `(select-keys request [:seon.issue/id :seon.agent/id <member>])` |
| issue.clj:1541 | add-tx | offending → the author agent id |
| env.clj:90,106 | environment-state / replace | offending → `(type environment)`; an environment is now a reference anyway |
| env.clj:212,229,303 | absent-member-error | offending → the missing member key; the supplied value is a reference |
| env.clj:422 | carry-state | offending → `(type state)` |
| effect.clj:690 | background-time-limit | offending → the supplied limit value only |
| effect.clj:717,779,806 | request* | offending → `(vec (sort (keys request)))` plus `:seon.effect/id` |
| context.clj:70 | selection-refusal | offending → `(select-keys request [:seon.agent/id :seon.turn/id])` |
| db.clj:2475 | missing-pull-selector-error | offending → `(vec (sort (keys request)))` |
| db.clj:4686 | missing-transaction-data-error | offending → `(vec (sort (keys transaction)))` |
| shell/jvm.clj:447 | foreground failure | offending → the argv plus exit/terminal event; stdout/stderr are already blobs |
| edit.clj:348-480 (6 sites) | form / replace | offending → the selector plus the file path |
| edit.clj:124-281 (4 sites, `source`) | source checks | offending → the path plus the source digest, never the source text |
| render/transcript.clj:791 | missing-selected-run | offending → `(select-keys unit [:seon.turn/id :seon.agent/id])` |
| render/transcript.clj:1049 | runtime-owner | offending → the component's `:seon.runtime/agent` value |
| cluster/process.clj:237,245 | run-process! | offending → `(select-keys request [:argv :deadline-ms])` |
| test/runner.clj:1518 | record-latest-tx | offending → the changed provenance keys (a diff), not the run |
| render/web.clj:653 | sources | offending → the source identities (paths or digests) |

## Remaining producers (the edge now references their world, but each still copies its request)

- The `request`/`unit` offending sites listed above; the owners are named by file.
- Round 3 closed `prepare-result`, `kernel.clj:679` and `render.clj:167`. `render.clj:1098` is
  left unchanged, with the reason given above.

## Original design (round 1)

Reuse the registry identity-only mechanism (`seon.schema/identity-only-descriptors-in`,
`src/seon/schema.clj:3887-3952`). Admission then records each world type as a typed reference,
`#:seon.print{:face :object :class C :value <identity>}`, without traversing it.

1. `resources/seon/schemas/seon.sci.eval.edn:172-176` `:ctx` (held by impl-repl-m1): add
   `:seon.schema/identity-only true :seon.schema/identity-projection seon.sci.eval/ctx-identity`.
   `ctx-identity` returns the custody connection's `seon.db/connection-identity` plus the
   projection fingerprint.
2. `resources/seon/schemas/seon.env.edn:3-8` `:environment`: move the identity-only properties onto
   the `:fn` schema, with `seon.env/environment-identity`, which selects
   `:seon.boot/cluster-name` and `:seon.db/basis-t`.
3. A projection reference: a new identity-only `[:fn seon.schema/projection-value?]` in
   `seon.schema.edn`. The predicate is a map holding `:seon.schema.projection/fingerprint`. The
   identity projection selects the fingerprint and version.
   `:seon.schema/projection` itself must stay `map?`, because an identity-only `map?` validator
   would match every map.
4. `src/seon/error.clj` (this row): hand `projection` into `bounded-error-admission`,
   `bounded-admission`, `bounded-text`, `project-observation`, `admitted-size` and
   `fit-fact-payload`, so error evidence classifies worlds too. This is about 12 changed lines.

The estimate is about 30 added lines, with nothing to delete, because the mechanism exists.

Regressions to add with it (root runs; each has a declared bound):

- Admitting an error whose offending value holds a database value, an SCI ctx, an environment and
  a projection finishes in under 5 ms and 5 MB, and records four typed identity object nodes.
- A 50,000-entry value stores bounded fact text plus a retrievable `:seon.error/data-blob` digest.

## Out of scope, owners named

- **Per-node armed calls in the walk.** `canonical-edn` per leaf and `identity-only-projection-in`
  per node (`src/seon/sci/admit.clj:192`, `:250`) cost about 350 B per emitted byte. This belongs
  to `seon.sci.admit`, which is free.
- **The test-failure blob that prints the ctx.** The 895 MB blob is a clojure.test actual form.
  The owners are `src/seon/test.clj` (held by impl-world-injection) and `seon.test.runner`
  `stage-field` (`runner.clj:1195-1215`), which should store the floor-bounded text plus a digest.
- **The MCP artifact that stores an evaluation request whole.** `cluster.clj:479` stores it, and the
  producer is the `sci/eval.clj` refusal carrying its request. Fixed by edge item 3.
- **`render.clj:1098` passes `unbounded? true` for every render result.** The edge fix makes world
  types safe under it, but ordinary large error values are still admitted unbounded.

## Root runs (after adoption of HEAD, serially)

1. `bin/test-check default --test seon.sci.admit-test/a-value-carrying-its-world-admits-references-not-copies`
2. `bin/test-check default --test seon.error-test/a-fifty-thousand-entry-offending-value-stores-bounded-text-and-a-digest`
3. `bin/test-check default --ns seon.sci.admit-test`: identical EDN for the leaf fast path.
4. `bin/test-check default --ns seon.error-test`: the projection threading.
5. `bin/test-check default --ns seon.render.value-test`
6. `bin/test-check default --ns seon.schema-test`: the projector refactor and the new declarations.
7. Publication of the three schema resources: a canonical projection builds with the new
   identity-only keys.
8. `bin/test-check default --ns seon.error-result-test`: the artifact blob.
9. `bin/test-check default --ns seon.render-test`: the request-profile offending shape.

Scratch namespaces `scratch.fuc-admit` and `scratch.fuc` were removed from default with
`remove-ns`. Core predicates keep no global state (`schema.clj:1299`), so nothing else remains.

Seen in passing, not investigated: default reported the branch error "recording writer refused"
(`seon.db/transact!`, branch `agent-83890fd28bcc`, signature `0e5427a1…`) at 22:3xZ. It is not
attributed to this lane.

## Commits

See `git log -- src/seon/render/value.clj docs/prds/agent-platform/landing/lane-fix-unbounded-capture-2026-09-25.md`.
Net source lines: round 1 +6/−3; round 2 about +125/−22 source and +65 tests.

## Round 4: reds on adopted HEAD 6ab6f5fb (verify-reds 019, 020, 007), classified

Baselines compared: `verify-6ab6ae2a-results.txt.d/063.txt` (error-test) and `060.txt` (schema-test).

- **CAUSED, fixed in `66a35ae86`.** Every red below comes from one of three defects.
  - **The scalar-leaf guard.** `leaf-edn` used `if-some` over an `(and …)` guard, so a
    non-scalar leaf took `false` as its prefix and threw `ClassCastException` in `failed-node!`. The
    fix is `if-let`. It caused:
    - `admit-test/a-projection-failure-obeys-the-one-dial`
    - `admit-test/one-print-node-owns-semantic-value-and-result-edn`
    - `admit-test/every-value-projects-bounded-and-reads-back`
    - `error-test/normalization-never-throws`
  - **The projection reference predicate.** It required a stored fingerprint, which declaration
    projections lack, so those projections were still walked (3.5 MB, 170 ms). It caused
    `admit-test/a-value-carrying-its-world-admits-references-not-copies`.
    - The predicate is now registry plus forms. The identity is the stored fingerprint, otherwise
      the content fingerprint, derived once on the projection's holder (38 ms once).
    - The generator gains `:forms`. Root's `[:= {} {}]` repair (`db51da2e7`) is kept; my other
      generators are `:gen/gen` Vars and were unaffected.
  - **`prepare-result` requires a storage bound**, and `sci.eval/shown-result` did not pass one. It
    caused `admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted`.
    - The fix is one hunk in `src/seon/sci/eval.clj:3679`, forwarding the evaluation's caps.
    - That file is held by fix-one-reader-errors for its one-event hunk only, so this hunk is
      disjoint. Root: please record it in the ledger.
- **PRE-EXISTING (red in 063 on `6ab6ae2a`):**
  - `error-test/complete-error-children-validate-through-the-writer`
  - `error-test/error-declared-schemas-persist-through-the-real-occurrence-owner`
  - `error-test/error-identity-and-occurrences-are-owned-by-the-writer`
  - `error-test/row-acquisition-observations-have-no-program-digest-promise`
- **Not a code defect, as far as I can tell.** `error-test/a-contract-violations-fault-keeps-the-value-that-broke-it`
  reports "Worker-global state changed: snapshot-instrumented drift-added my.agent/…". That is the
  instrumented set changing mid-run, consistent with hook publication loading namespaces during the
  run. Re-run it on a quiet HEAD. If it stays red, it goes to the runner and instrument owners.
- **schema-test (007): all PRE-EXISTING.** The same four members are red in 060. None of them
  touches identity-only or `projection-reference`.

## Root runs (round 4, on HEAD ≥ 66a35ae86, serially)

1. `bin/test-check default --ns seon.sci.admit-test`
2. `bin/test-check default --ns seon.error-test`
3. `bin/test-check default --ns seon.error-result-test`
4. `bin/test-check default --ns seon.render-test`
5. `bin/test-check default --ns seon.schema-test`: expect only the pre-existing four.

## Round 5: reds on verify-2350 (017, 018)

- **admit-test**: 80 passed, 0 failed (root). The world-reference regression is green.
- **error-test (017)**: every red member is one of the four PRE-EXISTING members listed in round 4.
  Their 13 failures and 1 error are assertions inside those four members. Nothing is left that
  this lane caused. `a-contract-violations-fault-keeps-the-value-that-broke-it`, the
  worker-global drift red, passed on the quiet re-run, which supports the concurrent-publication
  reading. The 50,000-entry regression is green.
- **error-result-test (018)**: `passed? false` with 19 passed and 0 failed is by design.
  `agent-contract-refusal-retains-its-live-offending-result` declares `:seon.test/long`
  (`long-ms` 60000). It was therefore excluded, and `seon.test/run` counts every exclusion as an
  unfulfilled obligation (`src/seon/test.clj:2352-2374`, `(empty? excluded)`). That member is the
  one that exercises `prepare-result` end to end through a real agent contract refusal, so it
  must run:
  `bin/test-check default --test seon.error-result-test/agent-contract-refusal-retains-its-live-offending-result --include-long`
