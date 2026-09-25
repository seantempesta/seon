---
type: landing
status: landed 44e7ad4b0 76d644bd2 b95676d59; tests owed to root
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

## Remaining producers (the edge now references their world, but each still copies its request)

- The `request`/`unit` offending sites listed above; the owners are named by file.
- `seon.error/prepare-result` (`error.clj:~466`) renders the offending value as complete HTML and
  stores it as a second blob beside `:seon.error/data-content`. The fix is to store once and
  reference that one digest. Owner: this lane, next round.
- `render.clj:1098` still admits every render result unbounded.
- `kernel.clj:679` still offends with the whole unadmittable failure. It is left as is because it is
  the only faithful evidence.

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

## Commits

See `git log -- src/seon/render/value.clj docs/prds/agent-platform/landing/lane-fix-unbounded-capture-2026-09-25.md`.
Net source lines: round 1 +6/−3; round 2 about +125/−22 source and +65 tests.
