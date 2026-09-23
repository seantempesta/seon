---
type: lane-spec
status: investigation + recommended design (docs only); owner decisions D1–D3 below
created: 2026-09-23
lane: braid-publication (Opus 5.5)
extends: README §4 1.2b remainder (per-declaration reload), 1.4c (candidate branches); B1 §2a steps 13–17; landing lane-opus-publication-2026-09-23.md ("Fix 3", "Hazard", "Next", "Resources")
---

# Publication in parts that fail alone

Vocabulary: AGENTS.md "Simple made easy — the Clojure mindset" (`702eed30e`). A braid is two
concerns tied together so that neither can change or fail alone. This spec names each braid
in today's publication and adoption code, takes them apart into parts that each do one thing,
and plans the slices. It changes no code.

## 1. The two braids, with today's evidence

### Braid A: default's loaded code is tied to every lane's unfinished edits

"Default is the files" (AGENTS.md), but the files are one working tree shared by every lane.
The JVM's loaded code is whatever `require :reload` reads from disk at reload time. That can
be different from what was published and checked. Five incidents on 2026-09-23 come from this
one braid (`docs/research/agent-platform/fix-schedule-2026-09-23.md` 06:20Z–10:35Z; landing
`lane-opus-publication-2026-09-23.md`):

| incident | where the braid is | mechanism |
|---|---|---|
| 06:20Z: Sol's reverted `schema.clj` stayed loaded, and every `bin/test-check` refused | the loaded Var comes from whichever disk bytes a reload read. No fact shows what is loaded | `require :reload` in `load-development-definitions!` (`src/seon/cluster.clj:2151-2160`) |
| 10:35Z: m4-n1's unadopted `seon.flow.edn` was published by another lane's adoption | a publication's schema world reads ALL of `resources/seon/schemas/` from disk | `schema.edn/packaged-forms` (`src/seon/schema/edn.clj:397`, via `packaged-population` `:381`) called at `cluster.clj:2860` (`refresh-source!`) and `:1666` (`populate-source!`); `fn.clj:2955` `declaration-forms` |
| 10:35Z: three lanes' adoptions deadlocked | "may I reload namespace N" is tied to "does N's file on disk equal its published digest", and N was reached only as a dependent | `verify-development-sources!` (`cluster.clj:2299-2318`, added `1f114bdec`) over `development-namespaces` (`:2206`). Any `def` edit seeds every dependent (`compiled-into-callers` `:2183` lists `clojure.core/def`) |
| the check-then-reload window | the bytes that were checked are not the bytes that get loaded: `path-digests` reads the file once, then `require` reads it again | `cluster.clj:2594` (check), `:2609` (reload), `:2611` (check again after) |
| the adopter's own code is replaced mid-adoption | reload runs before the write, so the running adoption calls Vars that the reload just replaced | `development-source-refresh!` (`cluster.clj:2544`). First adoption of `68f769a4a` failed with "argument count of 4" and wrote nothing |
| archive-mode adoption did nothing (`ca9587817`) | "which bytes the lane named" is tied to "which directory this JVM started in" | relative paths resolved in `fs/source-directory`; now refused by name |

### Braid B: one publication is split into steps that can half-happen

On `current-src`, publication already happens as one step. `source/publish!` writes on a
scratch branch and moves the head with `force-branch!` and `:expected-current-commit`
(`src/seon/cluster/source.clj:543-700`). The half-happening is in adoption onto the cluster
branch. `development-source-refresh!` runs these steps in order:

1. `verify-development-sources!`, which reads the disk
2. `ns-unmap` of deleted symbols
3. `require :reload`, which changes the JVM
4. verify again
5. `instrument/apply!`, which changes the JVM
6. `adopt-rows!`, which writes up to three transactions:
   - schema declarations (`cluster.clj:2504-2517`)
   - program rows (`seon.fn/index!`, `fn.clj:3520`)
   - issues (`issue/adopt!`, `issue.clj:966`)

   The record rides whichever of these transactions is last (`68f769a4a`).

A failure after step 3 leaves the JVM on new code while the rows and the record stay old. A
failure between the schema and program transactions leaves new attributes with old rows.
Both states are partial. Neither is the prior whole state or the new one.

The same braid, stated as roles: "the rows are adopted" and "the JVM has loaded them" are
two facts about two different things, the database and the process. `68f769a4a` makes one
transaction stand for both. So the loaded code has to be changed before the rows are written,
and that ordering causes both the self-replacement hazard and the disk-read window.

## 2. What the dependencies already do (read first)

| seam | pin, location | guarantee used here |
|---|---|---|
| `Compiler/load(Reader, sourcePath, sourceName)` | clojure `b18d3adc`, `src/jvm/clojure/lang/Compiler.java:8194-8233` | Takes a `LineNumberingPushbackReader` as it is (`:8198`), so a reader positioned with `.setLineNumber` gives correct `:line` metadata and correct frames. Evaluates each form with `*ns*` bound by the caller (`:8210`). |
| schema datoms applied inside one transaction | datahike `2cc313a6`, `src/datahike/db/transaction.cljc:466` (`schema? (-> (update-schema datom) update-rschema)`) | An attribute installed by an earlier operation in a transaction is usable by later operations in the same transaction |
| `:db.fn/call` receives the writer's current db | `transaction.cljc:1153` (B1 §2a) | `head-guard-tx`, `declaration-changes`, `reconcile-tx-in` and `issue/adopt-tx` can all be decided by the writer inside one transaction |
| scratch branch + `force-branch!` with an expected commit | `versioning.cljc:323`; `source.clj:640-660` | `current-src` publication is already one step (keep as is) |
| stored declaration source | `:seon.fn/source` on every one of 4,797 `:seon.fn/sym` rows (probe P1); `:seon.ns/source` on namespace rows; writer `fn.clj:662` | the published bytes of each declaration are already a fact on the published commit |
| the loaded-commit record read by acquisition | `sci/eval.clj:862-898` `loaded-source`/`loaded-commit-id`; `:2532` `acquired-database?` | a context compares its rows against the commit the record names and interprets rows that differ (B2 §2a). This is what makes it safe for rows to be ahead of the loaded code |
| Clojure agents | `clojure.core/send-off`, `await-for` | one serialized queue of actions against one identity; `await-for` waits for exactly the actions sent so far, with a bound |

## 3. The target: six simple parts

Each part has one job, reads values, and can fail without damaging the parts around it.

| # | part | one job | reads → writes | proportional to | fails alone how |
|---|---|---|---|---|---|
| 1 | **capture** | read each named path's bytes once and digest them | named absolute paths → `{path {bytes digest}}` value (`source/capture-paths`, `source.clj:104`, already does this) | \|P\| bytes | a missing or outside path refuses by name (`ca9587817`). Nothing is written |
| 2 | **publish** | rows for exactly those bytes on `current-src` | capture value + published commit → new `current-src` commit (scratch + `force-branch!`, unchanged) | changed files (analysis), touched rows | a stale head or refused rows leave `current-src` unmoved and retire the scratch (`source.clj:691-694`) |
| 3 | **adopt rows** | copy the published rows onto the cluster branch | ONE transaction: `head-guard-tx`, `declaration-changes`, `reconcile-tx-in` for the program identities, `issue/adopt-tx` | touched identities | refused ⇒ the cluster branch is unchanged. The JVM has not been touched yet |
| 4 | **load** | make the JVM's Vars equal the adopted rows | rows differing between the record's commit R and the adopted commit C → `Compiler/load` of each row's `:seon.fn/source` in its namespace, at its line. A namespace-level case (§3a) loads that file's bytes, read once and digest-checked, with the same call | changed declarations (+ referrers of a changed macro or constant) | a compile failure is an unhandled error: stored and delivered, loud under `:panic`. The record stays at R, so contexts keep interpreting the rows that differ (§5). The next convergence loads again from R |
| 5 | **arm** | wrap the Vars that were just loaded | reloaded Vars → `instrument/apply!` with `:seon.instrument/changed-identities` (unchanged) | changed Vars | the same as 4: the record stays at R |
| 6 | **record** | state which commit the JVM has loaded | one transaction: `adoption-guard-tx` (prior = R), `:seon.source/commit-id` C, `:seon.test/adoption-*` | 1 | refused ⇒ a newer convergence owns the record. Loading the same rows again is idempotent |

Parts 4–6 are the JVM following the rows. They run as one serialized action (decision D1): the
action converges the JVM from the recorded commit to the adopted head. A second request that
arrives while one is running joins the same queue. It never races.

### 3a. Which rows part 4 loads, and from which bytes

Rule measured on default pid of the 14:11Z restart (probe P1/P3):

- `defn`/`defn-` (4,399 rows, all `:seon.fn/inline? false`) and plain `def` (371) are read
  through their Var. Load only the row itself. This is the landing's "`def` leaves
  `compiled-into-callers`" (~2 lines).
- `defmacro` (8) and `^:const`/inline: load the row, then every row whose
  `:seon.fn/references` names it. Measured: `seon.profile/with-cell` has 2 referrers,
  `seon.profile/timed` 2, `seon.bootstrap/doc` 2. The referrer set is a query, not a walk
  over namespace requires.
- `deftype`/`defrecord`/`defprotocol`/`definterface` (17 rows): these are compiled into
  their callers' classes, so they keep today's namespace-level closure (defining namespace +
  dependents by `:seon.ns/requires`). Each namespace in the closure loads its whole file.
- `defmulti` (2): Clojure's `defmulti` does nothing if the Var is already bound, so the
  loader `ns-unmap`s it first. It already does this for deleted symbols (`cluster.clj:2598-2605`).
- Namespace-level cases: a changed `:seon.ns/source` (the `ns` form) or a changed top-level
  form that has no row. The source contains 75 `declare`, 69 `defonce`, 26 `defmethod`,
  41 `schema.edn/load!`, 40 `register-core-predicate!`, 8 `set!` and 1 `doseq`, and none of
  them has a row today. These cases load the whole file. The bytes come from decision D2.
  Probing whether a residue edit is detected today is part of slice S3: the row set gives no
  signal when only a `defmethod` changes (§7 risk 2).

Line numbers: rows store `:seon.fn/form-span` as byte offsets, not lines. Slice S2 adds an
optional `:seon.fn/line` taken from clj-kondo's `:row` at the producer (`fn.clj` `var-row`
~`:660`), so a per-declaration load puts frames on the right line. Probe P4 proved the reader
seam: a `LineNumberingPushbackReader` set to 2180 gives `:line 2180` metadata and the frame
`braid_probe.clj:2180`, and the load took 0.66 ms.

### 3b. Resources: only the named ones

A partial publication's schema forms are the published commit's stored `:seon.schema/form`
rows, with forms read from disk only for the named resource paths. Keys those paths declared
before are removed first. To know which keys a path declared, schema rows need a fact they
lack today: probe P5 shows `:seon.schema/key :seon.flow/executor` has no file reference. S6
adds an optional `:seon.schema/file` ref to the resource's `:seon.fn.file` row, written by the
producer. A publication with no published commit (from zero) still reads everything. The cost
is proportional to the changed resources plus one lookup of the carried projection (memoized
by `:cache-context`).

## 4. What each current mechanism becomes

| mechanism (file:line) | becomes | why |
|---|---|---|
| `verify-development-sources!` (`cluster.clj:2299`) + its two calls (`:2594`, `:2611`) | **DELETE** | declarations load from row source, which is the published bytes by construction. A whole-file load checks the digest of the same bytes it loads, so the check and the load happen together |
| adoption token (`mkdir tmp/orchestrator/adopt-token`, lane rules) | **DELETE** once S5 lands | D1's serialized convergence and the head/record guards serialize adoptions in code. A manual convention does not |
| `load-development-definitions!` (`:2151`), `reloadable-namespace?` (`:2143`) | **REPLACE** with `load-declarations!` (per row, `Compiler/load`) | `require :reload` reads the disk |
| `reload-order` (`:2098`), `namespace-requires` (`:2125`) | **KEEP** | namespace order, then form-span order within a file, so a new callee is loaded before its caller |
| `development-namespaces` (`:2206`) | **SHRINK** to "rows to load": identities + macro/const referrers + type/protocol namespace closure | the rows are the unit, not the namespace |
| `compiled-into-callers` (`:2183`) | **KEEP**, minus `clojure.core/def` | landing "Next" |
| `adopt-rows!` (`:2483`, three transactions + record) | **SHRINK** to one transaction, no record | braid B |
| `seon.fn/index!` `:seon.db/tx-data` (`fn.clj:3620`, `68f769a4a`) | **MOVE**: the caller's operations go BEFORE the reconcile operation (schema before the rows that use it). The only caller is converted in the same slice | the record no longer rides the rows |
| `issue/adopt!` 5-arity (`issue.clj:991`) | **DELETE** if unused after S4. `adopt-tx` is called inside the one transaction | |
| `adoption-guard-tx` (`:2451`) | **KEEP**, guards part 6 | |
| `development-source-refresh!` (`:2544`) | **REORDER**: rows → converge (load, arm, record) | removes the self-replacement hazard: the adopter runs old code from start to finish, and new code is installed afterwards |
| `schema.edn/packaged-forms` at `cluster.clj:2860`, `:1666`, `fn.clj:2955` | **REPLACE** for partial publications with the published forms + named resources | braid A, resources |
| other runtime `packaged-forms` readers (`db.clj:292`, `env.clj:162`, `print.cljc:342`, `instrument.clj:1053`, `cluster.clj:739/:2383/:3694`) | **FLAG, out of scope** | the same braid at runtime: the JVM's schema world comes from disk, not from the value. Owned by the 1.4 projection-as-a-read sweep (README §7 ruling "The projection is a memoized function of the value") |
| `source/publish!`, scratch + `force-branch!` | **KEEP** | already one step |

## 5. Failure at each step leaves a whole state

| fails at | cluster rows | JVM | record | what a context sees | recovery |
|---|---|---|---|---|---|
| 1 capture | prior | prior | R | prior | fix the path |
| 2 publish | prior | prior | R | prior | on a stale head, re-publish the same analyzed rows against the new head (landing "Smaller design for the losing publication") |
| 3 adopt rows | prior (one transaction) | prior | R | prior | the refusal names the moved head |
| 4 load / 5 arm | C | some declarations new, the rest prior | R | rows differ from R, so the context **interprets** them (B2 §2a, `sci/eval.clj:2532`). Host-bound rows that differ refuse by name | an unhandled error: stored, delivered, and a panic under `:panic`. The next convergence loads R→C again; loading the same bytes again is idempotent |
| 6 record | C | C | R, refused because a newer convergence moved it | interprets until the newer record | nothing: the newer convergence owns it |

Only the JVM can be partial, and only between parts 4 and 6. It never goes backwards: every
declaration it holds is either R's or C's. The record keeps naming R until the whole of C is
loaded, and contexts use the record to decide what to trust. Today's order is the reverse:
the JVM goes first, and a failed write leaves loaded code that no row describes.

## 6. Slice plan

Every slice lands as one loadable commit, adopted with `bin/seon init --dev default --changed`,
and its reaching tests run in one `bin/test-check` request.

| slice | change | files | added / deleted src (est.) | regression asserting WANTED behaviour | collision |
|---|---|---|---|---|---|
| S1 | `def` leaves `compiled-into-callers` | `src/seon/cluster.clj` | +0 / −1 | `development-namespaces` for a private `def` edit in a namespace with dependents answers only that namespace | **held by m4-n1** |
| S2 | optional `:seon.fn/line` from clj-kondo `:row` | `src/seon/fn.clj`, `resources/seon/schemas/seon.fn.edn` | +4 / 0 | an indexed `defn` row's line equals its line in the file | free (ledger ~08:55Z) |
| S3 | `load-declarations!`: per-row `Compiler/load` at the row's line in its namespace. Whole-file case: bytes read once + digest + the same `Compiler/load`. Replaces `require :reload` and both verify calls | `src/seon/cluster.clj`; delete the verify cases in `test/seon/cluster/publication_serialize_test.clj`, `publication_delta_test.clj:88` | +35 / −45 | (a) adopting X while dependent Y's disk bytes differ from Y's published bytes succeeds, and Y's Var is unchanged. (b) the loaded Var's `:line` equals its row's line. (c) a `defmethod`-only edit is loaded (residue case) | **held by m4-n1** |
| S4 | order rows → load → arm → record. Rows in ONE transaction; the record in its own guarded transaction | `src/seon/cluster.clj` (`adopt-rows!`, `development-source-refresh!`), `src/seon/fn.clj` (`:seon.db/tx-data` placed before reconcile), `src/seon/issue.clj` (drop the 5-arity) | +15 / −45 | (a) an adoption that changes the arity of `adopt-rows!` itself succeeds the FIRST time. (b) a published declaration that fails to compile leaves the record at R, the rows at C and one stored error fact, and the next adoption converges | **held by m4-n1** (cluster.clj); fn.clj and issue.clj free |
| S5 | serialized convergence (D1): one action "converge the JVM from the record to the cluster head". Callers `await-for` it with the declared bound | `src/seon/cluster.clj` | +20 / −5 | two concurrent adoptions touching one declaration end with the Var holding the newer commit's source and the record naming the newer commit. The adoption token is deleted from the rules and the ledger (orchestrator) | **held by m4-n1** |
| S6 | partial publication schema forms = published forms + named resources; optional `:seon.schema/file` | `src/seon/fn.clj` (schema row producer + `declaration-forms` `:2955`), `src/seon/cluster.clj` (`:2860`, `:1666`), `resources/seon/schemas/seon.schema.edn` | +20 / −5 | an unnamed dirty resource (a disk edit to `seon.x.edn`) does not reach the published projection when another path is published. A named one does | cluster.clj **held by m4-n1**; fn.clj free |

Order: S2 and the fn.clj half of S6 can start now (their files are free). S1, S3, S4 and S5 run
in that order once m4-n1 releases `cluster.clj`. No slice touches `src/seon/cluster/source.clj`
(nsa-s4) or `src/seon/db.clj` (writer-cost-2).

**Projected net:** src about +94 / −101 ≈ **−7**. The larger gain is structural: the verify step,
the token, `require :reload` in adoption, and the three-transaction adoption with its record
threading are deleted, and one loader plus one converge action replace them. Tests: +~140
(five regressions) and −~60 (the verify tests of `1f114bdec` and the machinery-only cases of
`publication_serialize_test`).

**Braids removed:**
- loaded code ↔ disk: load reads rows, or bytes it has digest-checked.
- rows adopted ↔ code loaded: two facts, written in order.
- my adoption ↔ your dirty dependent: loading is per declaration, and nothing is read from
  disk for dependents.
- my publication ↔ your resources: only named resources are read.
- adopter ↔ adopted code: the write happens before the load.

**Braid added:** one serialized converge action (D1), which ties "who asks for an adoption" to
"when the JVM loads". A queue is the standard way to separate those two concerns.

## 7. Risks and unknowns (probe inside the slice, before code)

1. The window between parts 3 and 6 is roughly the load time plus the arm time. A context
   acquired in that window interprets the rows that differ, and host-bound rows that differ
   refuse by name. S4 must time this window (target < 100 ms for a defn edit).
2. Residue forms have no row. S3 needs a signal that the file changed while no declaration
   digest changed. The obvious signal is the file digest, but it also changes on comment-only
   edits (`59d8cc6a6` adopts those while reloading nothing), so it cannot be used unchanged.
   Candidates: a digest of the top-level forms outside every declaration span, taken on the
   namespace row at the producer; or accepting a whole-file load on comment edits. Measure
   the compile cost of the largest file before choosing.
3. Protocols and types reloaded as a namespace closure keep today's behaviour and today's
   cost (17 rows). They are not made per-declaration here.
4. Purges from `accrete-schema-population!` (a non-accretive attribute change) inside the one
   rows transaction are not yet proven. S4 must test a type-changing declaration on a branch
   of default before merging the operations into one transaction.
5. With the arity change in S4, candidate/save-gate callers of `adopt-rows!`
   (`cluster.clj:2759` `save-gate!`) switch to the one-transaction form in the same slice.

## 8. Owner decisions (three options each, recommendation first)

**D1 — how the JVM follows the rows (who serializes parts 4–6).**
- **(1) Recommended: one Clojure agent per JVM runs "converge to the cluster head".** It reads
  the record R and the head C, loads the rows that differ, arms them, and records C. Callers
  use `await-for` with the publication bound. Guarantee: loads are ordered by commit and never
  race. Several requests collapse into one convergence. The token is deleted. Cost: ~20
  lines. Gives up: the load no longer runs on the caller's thread, so the agent's failure must
  be passed back to the caller with its whole cause (`agent-error`), not dropped.
- (2) Load inline under Clojure's own `RT/REQUIRE_LOCK` (the lock `serialized-require` uses).
  Guarantee: the same ordering. Cost: ~5 lines. Gives up: it adds a lock back after 1.3f and
  the `e1dfa0b74` revert, and every `require` in the JVM waits behind a load.
- (3) Keep the adoption token. Cost: 0 lines. Gives up: correctness depends on every lane
  following the `mkdir` convention, and nothing enforces it.

**D2 — where the bytes for a whole-file load come from.**
- **(1) Recommended: read the file once, digest those bytes, and load them only if the digest
  equals the published one; otherwise refuse by name.** Guarantee: what is loaded is exactly
  what was published. Cost: ~8 lines, no new storage. Gives up: it refuses when another lane
  has dirtied that exact file AND the change needs a whole-file load. This is rare: only `ns`
  form, residue, type or protocol edits.
- (2) At publication, store each changed file's bytes as a `seon.blob` keyed by
  `:seon.fn.file/digest`, and load from the blob. Guarantee: it never refuses. Cost: ~25
  lines, plus blob bytes proportional to changed files, and retention ties to the store
  window. Gives up: it adds a store write to every publication.
- (3) Rebuild the file from rows: `:seon.ns/source`, then declaration sources in span order,
  plus a new residue row. Guarantee: rows only. Cost: the most. Gives up: rebuilt text is
  not byte-identical, so the digest no longer proves anything.

**D3 — where the adoption record goes (this reverses `68f769a4a`'s placement).**
- **(1) Recommended: rows in one transaction, and the record in its own transaction after load
  and arm.** Guarantee: the record means "the JVM loaded C". While the rows are ahead of it,
  contexts interpret the difference, which is how the branch model is meant to work. Cost:
  covered by S4. Gives up: a moment, measured in S4, in which the rows are ahead of the
  record.
- (2) Keep `68f769a4a`: load first, then rows and record together. Cost: 0. Gives up: the
  self-replacement hazard and the disk-read window stay, and the JVM can be ahead of every
  row.
- (3) Write rows and record on a scratch branch of the cluster branch, then one
  `force-branch!` of the cluster pointer (landing option B). Guarantee: rows and record land
  together. Cost: ~40 lines. Gives up: it moves the pointer under live agents' custody, and
  the JVM still has to be loaded either before or after, so the hazard comes back.

## 9. Evidence (this lane, read-only on default, MCP session `braid-publication`)

| probe | form (abridged) | value | ms |
|---|---|---|---|
| P1 | count `:seon.fn/sym` rows by `:seon.fn/defined-by`; rows with `:seon.fn/source` | defn- 3,080, defn 1,319, def 371, defmacro 8, deftype 7, defrecord 6, defprotocol 4, defmulti 2; 4,797/4,797 carry source; ns rows carry `:seon.ns/source`; `(seon.fs/source-directory)` = `/Users/sean/src/seon` | 131 |
| P2 | top-level form heads across `src/seon/**/*.clj(c)` (`grep`) | declare 75, defonce 69, schema.edn/load! 41, register-core-predicate! 40, defmethod 26, set! 8, doseq 1 have no row | — |
| P3 | referrers by `:seon.fn/references` of 3 macros; type rows; host-bound rows | 2, 2, 2; 17; 821 | 11 |
| P4 | `Compiler/load` of a one-form `defn` through a `LineNumberingPushbackReader` at 2180, in the throwaway namespace `braid.probe.tmp` (removed afterwards) | `:line 2180`, frame `braid_probe.clj:2180` | 0.66 (load) |
| P5 | attributes of `[:seon.schema/key :seon.flow/executor]` | no file reference; 3,376 schema rows | 126 |

No operation in this lane took more than one second. No default Var was redefined; P4 used a
namespace created for the probe and removed at its end.
