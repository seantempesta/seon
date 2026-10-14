---
title: "Hybrid: the catalogue of truths, one key per content, red included"
type: research
status: hybrid of truths-opus.md (8d39de10f) and truths-astra.md (cc86865c9); design only, nothing installed
date: 2026-10-14
lane: truths-hybrid
basis: "HEAD 1ac32d040; digs dig-content-keys.md (cb5549740), projection-duplicates.md (953e0e933), cache-after-boot.md (42cdf2d68); source read at HEAD plus the named lanes' working-tree edits (proj-content-key `seon.db/declaration-content-key`, b1-test-reuse `seon.test.runner/retain-greens!`), read only; three read-only JVM-mode requests on default as root for §4b (a scratch konserve store under tmp/, deleted)"
---

# Hybrid: the catalogue of truths

Owner (2026-10-14): "a list of things that we know to be true and then make them cachable across all the permutations
of branches and SCI ... a function that hasn't changed run in one SCI branch and another SCI branch using the same
function doesn't need to re-run the test since the results literally can't have changed"; "a test result is reused at
its key, red included". plan.md §0 states the law: every derivation is keyed by the content of exactly what it reads,
never by branch, commit where content would do, instance, URL or deriving thread; each truth is proven by a test that
changing anything outside its inputs leaves the result unchanged.

The two plans agree on the law and on the first win (projections: one content held many times). They differ on
**how a content key is paid for**. Opus keys by content and interns after derivation (digest the derived value); Astra
measured that a fresh digest of a whole population (11.27 ms) costs more than the unchanged-input loader it would front
(7.64 ms) and nearly a thousand times the held lookup (0.012 ms), and so asks that a key be computed where the content
changes and carried, with cheap history aliases (revision, commit) answering ordinary reads. This hybrid takes Opus's
catalogue shape and red-retention semantics, Astra's cost rule and corrections, and adds what both missed: the cache
owner's two entrances already give a derivation two different environments, which decides owner question 1. §1b and
§4b answer the owner's later question (cache only the derived and ephemeral; stored immutables read from their owner;
store a derivation in konserve only where one measurement shows the read far cheaper than the derivation).

**The cost rule (adopted from Astra, stated once).** A content key is admitted only when (key + lookup) is cheaper than
the work it saves on the path it runs on. In practice: a history alias (revision map, commit id: µs) fronts every
ordinary read; the content key is computed only when the alias misses (a declaration changed, a new branch), and it is
either compact (a digest, ≤ 1 KB) or counted in the entry's weight. A derivation that already holds its content's
digest (definition digest, declaration digest, reach digest) passes it; nothing re-digests a population per read.

## 1. The catalogue

One row per derivation. **Truth** = the inputs the result is a function of. **Key now** = HEAD unless marked (wt = a
lane's working tree). **Gap**: *over* = an extra member blocks sharing (safe); *hidden* = an unkeyed input (unsafe).
**Key cost vs saved**: the corrected key's per-call cost against the derivation it fronts, from the two plans'
measurements (result handles in truths-astra.md §"Measured costs"; M1–M4 in truths-opus.md §0). **Owner**: the lane or
phase (§5). Rows are ordered by measured payoff; rows 1–8 are the open gaps.

| # | Derivation | Truth: depends only on | Key now | Gap | Corrected key | Key cost vs saved | Owner |
|---|---|---|---|---|---|---|---|
| 1 | **Schema projection of a database** (`seon.db/content-projection`; kinds `::projection`, `::value-projection`) | the declaration rows the loader reads (entity attributes and values), the loaded builder, effective options | HEAD: `[::declaration-content builder <every declaration datom with e and tx>]`; **wt proj-content-key**: `[::declaration-content builder (frequencies rows)]`, rows = `{attribute #{values}}` per entity, e and tx dropped. Speculative: `[::advanced builder (revision key of basis) <written datoms with e, tx>]` | **Over** (HEAD): e and tx; 34–36 entries hold 9–11 contents (M1; Astra `e36d8dda18d00`), 353–407 MB weighed for about one copy plus deltas; the key retains ~35k datoms unweighed. The `::advanced` path never computes the content key, so a speculative derivation equal to a held content is a second copy. The 6%-hit kind evicts program contexts (0 of 6 retained), parsed facts (538) and resolver names (8,833) | rows key (wt) **digested** to 64 chars, computed only on a revision-alias miss; the `::advanced` entry interned by the same content key of its `:db-after` after derivation (alias, not a value digest); every history key an alias (`::projection-alias` exists) | revision alias 0.0013 ms on every read (`e990adbe65832`); old datom key 1.3 ms / 1.5 MB; the rows key is UNMEASURED (the lane's cost proof); unchanged loader 7.64 ms; a miss ≈ 52 ms (838 ms / 16). Rule: key ≤ 10% of a miss, 0 on a hit | **proj-content-key** (in flight); the `::advanced` interning and key weight are its two additions, or hand to phase 2 |
| 2 | **Speculative projections** (`seon.db/speculative-report-projection`) | the basis's projection and the declaration datoms the report wrote | `::value-projection` retained like a committed one | 268 MB, 25 entries, **2 hits / 30 misses / 5 evictions** (cache-after-boot §2): derived for a write, almost never read again; a refused or test-only `d/with` value's projection outlives its operation | hold the derived projection **in the report's value** for the operation; retain only what commits, under row 1's content key (`hold-committed-projection!` then receives the projection by value instead of probing `::value-projection`) | one alias on commit; no retention for a speculative-only content | **speculative lane** (queued); after row 1 |
| 3 | **Test outcome** (one member's terminated counts and failures) | the test's static closure (reach digest: definition digests incl. resolver context, schema leaf digests); declared non-program inputs (`:seon.test.run/input-digest`: widening files, deps, gitlinks, config); task inputs a task-reading member judges; the evaluator | HEAD: none across branches. **wt b1-test-reuse**: `seon.test.runner/green` `[sym reach-digest input-digest evaluator]`, greens only (`(> ?pass 0)`, fail 0, error 0), main-head fallback | **Over**: reds not retained (M4: the one repeat today was red, 1.05 s). **Hidden**: database facts a body reads outside program and task inputs, wall clock, files outside widening roots; the evaluator *label* is not the evaluator implementation (Astra), covered in-process because the cache is per JVM and across restarts because compiled callees are in the reach digest | same key; value = the **terminated body outcome, red included** (§3); boundary failures never retained | one `present` per runnable test (µs) against a body run (M4: 18.2 s over 172 executions, ≈106 ms mean) | **b1-test-reuse**; red retention is phase 3, a patch to it |
| 4 | **Reach digests** (`seon.test.runner/reach-digests`) | the `[name leaf-digest out-edges]` of the program and schema nodes in each test's closure | `::reach-index-by-revision revisions` (coverage checked at use by `::component-digests`); `::reach-graph [revisions (set test-symbols)]`; `::reach-index [(set test-symbols) nodes leafs]` (content); prior via `::reach-head owner` (lower basis of the same owner) | **Over**: a fresh branch finds a prior only through its own owner's head, so it derives every node: **322–345 ms, ≈500 MB** per unheld branch (M2), 1.6–68 ms repeated. Astra's corrections kept: revisions are persisted across forks (a valid cross-fork alias), the head is a local pointer, coverage is part of the index's meaning | keep every key; add one prior probe: the index held at the branch's **fork base** revisions (`seon.db/basis-revisions` of the parent commit from `seon.test/commit-facts`), then the owner's head; never a scan of `seon.cache/retained` | one `present` (µs) against 322–345 ms | phase 5, `runner.clj` after B1 releases |
| 5 | **Program context** (`seon.sci.eval/program-context`) | the loaded commit's program (base), plan digest, projection delta, armed dials; a predecessor is an input, never a key member | base `[store commit]` (exact: the loaded commit is the platform); overlay `program-content-key` (content) | key exact; **retention 0 of 6** (M3, ≈480 ms each) is row 1's eviction pressure; hidden deriving-thread bindings fixed by overlay phase 1 (632096479) | keep; retention follows row 1 | content key 1.079 ms vs 237–251 ms miss (`e3deb0d73b96d`, `ed28d9e90dc9d`); revision alias 0.124 ms | **overlay-p3** (phases 3–5 as filed in overlay-hybrid.md) |
| 6 | **Compiled schema products** (the projection's `::compiled` memo; `seon.schema/contract-products`) | the form of k, k's schema-dependency closure, compile options, predicate functions | per projection instance, unbounded (ledger SR-J) | **Over**: the instance; each of 34 projections recompiles (0–1,287 each, M1). **Hidden**: a compiled schema closes over its projection's registry; the memo grows after the entry was weighed (Astra) | one `seon.cache` kind `[k closure-digest options-digest]`, compiled against a registry of k's closure only | closure digest per compile (ms) vs compile; UNMEASURED: count compile ms per projection first | phase 6, `schema.clj`; drop if compile per projection < 50 ms once row 1 holds one copy |
| 7 | **Render invocation** (`seon.render/invocation-cache-key`) | the render function's code (program evidence), projection evidence, selection input, the reads it made (checked by `seon.db/read-evidence-current?`) | `[branch-scope selected output (hash program-evidence) (hash projection-evidence) (hash selection-input)]`, full evidence compared on hit | **Over**: `branch-scope`; a colliding 32-bit hash is detected (`same-invocation-evidence?`) but then **replaces** the other content's entry (`retain-invocation!` evicts first). **Hidden**: a read not captured as evidence (direct `datahike.api`, a producer defect by law) | drop `branch-scope`; key the evidence by its held digests (`seon.id/id`, 64) instead of `hash`; currency stays the read-evidence check | digest of small evidence maps per render (UNMEASURED, expected sub-ms) vs a render | phase 7, `render.clj` |
| 8 | **Config effective** (`seon.config/effective`) | the cluster's config entity, the carried projection's dial declarations, the JVM's declaration digest | `[commit cluster declaration-digest]` | **Over**: commit; every recording transaction is a new commit: 50 misses, 7 calls per request | `[(seon.db/content-revisions db config-attributes) cluster declaration-digest]` | `select-keys` on a held revision map (µs) vs a sub-ms pull+merge (Astra: "measure against current sub-ms derivation first") | phase 8, `config.clj`; a correctness row, committed with its measured per-request ms whichever way it goes |
| 9 | Reach node / reach schema node | one declaration's attributes plus its behavior leaf | the whole declaration map + `::leaf` | exact, over-keyed by hashing every attribute; 75k hits, works | keep | — | — |
| 10 | Reach attribute | the datoms of one attribute | `[attribute shape revision]` | over (revision), safe; equal independent writes do not share, content keying pays at row 4 instead | keep | — | — |
| 11 | `seon.schema/projection` (no-base arity) and `declaration-projection` | forms, function contracts, effective options incl. predicate bindings | `[forms function-contracts options]`, `[forms predicates]`: whole maps hashed per lookup | content, exact; the supplied-base arity of `build-projection` bypasses the lookup (Astra), so row 1 is where one copy is enforced; two projections equal in forms but different in `definition-strings` are different values (Astra) and stay so | `[declaration-digest options-digest]` once row 1 carries the digest; otherwise keep | map hash cached by Clojure after first use; equality on an instance change 1.38 ms / 3.6 MB (`ef03964d0a5a1`) | follows row 1 |
| 12 | Vector pull (`seon.schema.datahike/vector-pull`) | storage-relevant schema, cells flag, selector | `[projection-fingerprint cells? selector]` | fingerprint is 32-bit, not equality (Astra); becomes identity-stable once row 1 interns | key by the projection's held content digest | µs | follows row 1 |
| 13 | Declaration projection, packaged population, authored shapes | the packaged declaration digest | digest (B4, f8a2773be) | fixed (62 entries → 3–4) | keep | — | done |
| 14 | `::program-key`, overlay plan, loaded digests | store, revisions, dial revisions, loaded commit | history tuples → content key | pointers, 1 KB: the right pattern; plan over-keyed by basis (overlay phase 5) | keep | — | overlay-p3 |
| 15 | `seon.test/changes` | resolved comparison base, candidate declarations, cluster | wt b1: `[cluster base revisions]` (branch dropped) | base is a true input (`changed-identities` may choose a fork base: Astra); correct once resolved before keying | keep | — | b1-test-reuse |
| 16 | Parsed facts (`seon.fn.analyzer/parsed-facts`) | source text | the text | exact; **displaced** (538 evictions); 0.005 ms hit vs 6.86 ms parse (`eefaf22027c64`); a per-lookup digest would multiply the hit cost 42× (rejected) | keep | — | recovers with row 1; boot-dup-analysis as filed |
| 17 | File derivation key (`seon.cluster/file-derivation-key`) | file code, kondo inputs, reached schema forms, start set | `[code kondo input (::key world) start]` | exact for its own inputs; the whole world key over-keys a file whose reached schemas did not change (Astra); displaced (603) | reached content from the held world, after the cross-file analysis tasks resolve | — | boot-dup-analysis (owns `cluster.clj`, `fn.clj`) |
| 18 | Source resolver names (`seon.program/source-resolver-names`) | source, qualifiers, symbols | `(id/id [source qualifiers symbols] 64)` | exact; **displaced** (8,833 of 10,529 misses evicted); a digest per call | keep; Astra's parse-by-source-then-intersect only if misses survive row 1 | — | after row 1, measure |
| 19 | `seon.ref/document` | text | SHA-256 of the text per lookup | exact; 5.8k misses, 4k evictions; a digest per call where the text itself (cached `String.hashCode`) would do | measure; no phase | — | `ref.clj` (held by s3d-2) |
| 20 | Task file declarations | path (the task namespace), text, forms | `[forms path text]` | exact: the path is a true input (both plans) | keep | — | — |
| 21 | Health derived, untested, problems | name, the reads made, loaded identity | `derivation-key database name reads`; `::untested`/`::problems` by committed identity via `lookup-within` | `::reads` by name is a hint, not truth; outer commit repeats after unrelated writes (Astra); UNTRACED in detail | trace; read evidence + `content-revisions` | — | no phase |
| 22 | `seon.error.refusal/authored-namespace` | namespace, class resource, **live** `find-ns` | namespace only | **hidden**: live namespace existence (Astra, source audit, not reproduced) | cache the classpath classification by its inputs; the live part is read live | — | errors cut; narrow regression first |
| 23 | Definition digest at the writer (`seon.db/definition-digest-of`) | canonical row + resolver context | none, per write | not a sharing gap; UNMEASURED cost; pass the held digest downstream (rows 1, 11) | — | — | measure |
| 24 | Context units / current-state block | the agent's task, its references' content, each unit's read evidence | none: rebuilt per request (≈821 reads on a repeat) | a render (row 7) over attached units, not cached at all | render invocation per unit once row 7 is content-keyed | — | S3a |

### 1b. Where each truth lives (the catalogue's tier column)

Owner (2026-10-14): "the cost of reading an immutable value on disk (like konserve) is really cheap and fast right? do we
even need them to go to the cache? maybe the cache should only be for things that aren't immutable and cheap to read
(derived, ephemeral)?" The rule, decided by measurement (§4b):

- **Owner-read.** Stored immutable data (datoms, index nodes, konserve blobs, file bytes) is read from its owner
  (Datahike's byte-bounded node cache, konserve, the OS page cache) and is never copied into `seon.cache`. Checked
  against every held kind in the dig's §1: no kind holds datoms, nodes or blobs. Two kinds hold a file's **text as
  their key** (`parsed-facts`, `file-declarations`): the key is the caller's own String, one copy, so it is a reference
  and not a second copy; `file-declarations` also charges 2·text in its weight for a value that holds the text, which is
  the one copy to delete when its owner is next touched.
- **Memory-only derived.** `seon.cache` holds derived values that are expensive to make and cheap to hold, and live host
  objects that have no data form (SCI contexts, compiled Malli schemas, registries, compiled fns).
- **Per operation.** A value needed only by the operation that made it (a speculative projection, a transaction's
  plans) travels in that operation's value and is retained nowhere (row 2).
- **Stored derived (konserve, keyed by content digest, shared across branches, sessions and restarts).** Only for a
  derivation that is plain data, content-keyed, and whose re-derivation costs more than about three times its stored
  read (deserialize + decompress), decided per row by one measurement. What is stored is the serializable product, never
  its compiled form, which rebuilds in memory on read.

| Row | Tier | Reason (measured in §4b where marked) |
|---|---|---|
| 1, 11, 12 projections | memory-only derived | the cost is the compile (1.80 s of a 1.95 s cold derivation, **measured**): host objects; the data part reads from konserve in 25 ms against ~150 ms from the database rows, under 10% of the derivation. Not stored. |
| 2 speculative projections | per operation | 2 hits for 25 entries |
| 3 test outcomes | stored at their owner (Datahike facts) + memory pointer | the outcome is already a durable fact; the cache entry is a 256-byte pointer to it; the main-head query is the cross-restart read |
| 4 reach digests | memory-only derived; **candidate for stored**: the index's `::digests` map (plain data, 322–345 ms to derive) | measure the stored read of one index against the derivation before deciding (phase 5's first step) |
| 5, 14 program contexts | memory-only (host objects) | SCI contexts have no data form |
| 6 compiled schemas | memory-only (host objects) | |
| 7 renders, 24 context units | memory-only; **candidate for stored**: a rendered unit is printed data keyed by content and read evidence | measure a unit's render against its stored read when S3a renders units through row 7 |
| 8 config | memory-only | sub-ms derivation |
| 16 parsed facts | **stored derived** (konserve by text digest) when boot's re-analysis is measured to repeat across restarts: parse 7.0 ms vs stored read 0.12 ms per file, 1.2 KB stored (**measured**, 57×) | boot-dup-analysis decides against `.clj-kondo/.cache`, which already is this tier for kondo's own analysis; one disk cache per fact, not two |
| 17, 18 file derivation key, resolver names | as 16, after 16 | |
| 19 `seon.ref/document` | memory-only | parse of one document, not measured; small |
| 20 task declarations | owner-read + memory-only derived | the file is the owner |

**Reconciled counts.** The three digs disagree on how many distinct projections default holds: Haiku 27 classes by `=`
of the whole projection, Opus 11 by `[forms function-contracts]`, Astra 9 by seven input fields including
`definition-strings`. The whole-map `=` compares the `::compiled` memo atom and the registry by identity, so 27 is not
a content count; **9–11 contents among 34–37 entries** is the measurement (snapshots differ). Haiku's "eviction is
live for one kind only" and "parsed-facts 76.9 MB" were true at 14:40Z and false by 16:00Z (538 evictions, 17 MB): a
snapshot, not a claim; and its `retain-greens!` VERIFIED line describes uncommitted b1 work (HEAD has no
`seon.test.runner/green`).

## 2. Hidden inputs

| Hidden input | Where it leaks | Made explicit or impossible |
|---|---|---|
| Seon's evaluation bindings (`seon.db/*conn*`, `*read-database*`, `*read-evidence-sink*`, `*receipt*`, `*open*`; `seon.test/*member*`, `*task*`; `seon.effect/*request-context*`; `seon.batch/*world*`; `seon.sci.kernel/*host-world*`; `seon.print/*budget*`) | any derive fn run inside a request; observed: a branch's `(def L2 (seon.ref/brief (seon.db/db) …))` installed under root's binding and refused without it (overlay phase 1) | the derivation sees none of them (owner question 1, below) |
| Reads of the database | renders, health, task-reading tests | captured as read evidence and checked for currency (`read-evidence-current?`); for tests, the task-inputs digest; a read outside `seon.db` is its producer's defect |
| Host files | tests, analysis | declared and digested (widening roots; file SHA-256) or not read |
| Clock, randomness, thread identity | tests (deterministic by law), context-less SCI calls | bounded generators; a mixed outcome at one identity is the observation (§3); `in-defining-ctx` rebinding (overlay D1) |
| The JVM's loaded program | the evaluator, the base context | the loaded commit is in the key (rows 5, 14); compiled callees are in the reach digest |
| Captured instances (registry, memo atom, connection) | compiled schemas (row 6), projections' memo, render entries | compile against a closure-only registry; memos become content-keyed kinds; values never hold connections (`retain-invocation!` already dissociates db, snapshot, projection) |
| History ids (e, tx, revision, commit) | rows 1, 4, 8, 10 | over-keying, safe: aliases to a content key |

**What both plans missed: the cache owner already has two environments.** `seon.cache/lookup` runs the derivation on
the first caller's thread, so it sees that caller's bindings; `seon.cache/lookup-within` starts it with
`Thread/startVirtualThread` on a plain fn, which conveys no bindings, so it sees root values. The same derive fn can
therefore answer differently by entrance today. A second consequence: a derivation that reads the database under a bound
`*read-evidence-sink*` reports its reads to the enclosing render on a **miss** and reports nothing on a **hit**, so the
render's evidence depends on cache state. Both are fixed by one rule at the owner.

**The one hidden-input proof shape** (a table deftest per row, asserted on data): derive with the declared inputs I in
two environments that differ in everything else (another branch, a bound root connection, a fresh virtual thread, a
content-equal value under another commit, a later clock); expect `=` values and **one** miss (`seon.cache/status` misses
delta 1). Then vary each member of I alone: expect the new input selected and equality with a fresh oracle, not a
different value (Astra: two programs may both return 1; the proof distinguishes inputs, it does not demand injectivity).
A row whose proof cannot be written names the input it found as a new key member or excludes it by construction.

**Decision on owner question 1 (Opus): should every cached derivation run with the evaluation bindings cleared at
`seon.cache/lookup`?**

- (a) **Yes, at the one owner**: `settle!` runs `derive-fn` under `with-bindings` of root values for the evaluation
  bindings listed above, in both entrances. Mechanism, not check: it declares what a derivation receives (its arguments
  and nothing else), makes the hidden-input proof hold by construction, and makes the two entrances one environment.
  Cost: one `pushThreadBindings` per **miss**, zero per hit. Consequence by design: a derivation that reads the
  database returns its read evidence **as data in its value** (as `seon.health/derived` does) and the consumer merges
  it, so hit and miss carry the same evidence. Blast radius: 75 call sites in 20 files; every derivation that was
  silently using a binding becomes a refusal (`seon.db/db` with no connection) in the proof run, which is the point.
- (b) Per derivation, as each row is proven (overlay phase 1's form). Lands the same rule 75 times.
- (c) No; rely on each row's proof. Leaves the entrance asymmetry.

**Recommendation: (a)**, phased as proof first (the deftest over the top kinds, red on HEAD for the entrance
asymmetry) then the rule, with the suite run once on a branch to count what breaks; each red names a producer that
read ambient data and is fixed at that producer (pass the value). The bindings internal to one derivation
(`seon.schema/*projection*` and its siblings, `*compiling-contract*`, progress fns) are bound inside their own
derivation and are not evaluation bindings; they stay.

## 3. Red results

**Retain a terminated red at its key exactly as a green** (both plans; owner's rule). Reuse means answering the
recorded outcome at that identity: a reused red is reported red with its run id and its failure refs, never as green;
"unknown or red cannot reuse green" is about converting outcomes, which reuse never does. A reuse never searches an
older green at a less specific key, never reinterprets red as unknown and never skips a changed reached input (Astra).

- **Retained**: a member whose execution terminated inside its body with at least one assertion: pass, assertion
  failure, an exception thrown by the body (`:seon.test/error-count` from clojure.test's report). Functions of content.
- **Never retained (unknown)**: a timeout, an interrupt, a missing `:seon.test.member/terminated-tx`, a
  `:seon.test.member/error` boundary failure, a recording refusal, **and a terminated member with zero assertions** (pass
  0, fail 0, error 0: Astra; B1's `(> ?pass 0)` already encodes it for greens). Each reruns.
- **Selection**: a held red at the current identity selects no execution; the task's proof stays red until the key
  changes (a new reach digest) or an explicit `:seon.test/rerun?`, which bypasses reuse today.
- **Merge**: a reused red blocks exactly as an executed red; `done-refusals` receives honest current evidence.
- **Flakiness**: two outcomes at one identity are a positive observation of a hidden input (M4: 0 today). Owner
  question 3 decides which answers.
- **Change to B1** (phase 3): `retain-greens!` → `retain-results!`: drop `[(> ?pass 0)] [?m fail-count 0] [?m error-count 0]`,
  add `[(or (> ?pass 0) (> ?fail 0) (> ?error 0))]`, keep `terminated-tx` and `(not [?m :seon.test.member/error])`;
  the value holds pass, fail and error counts, the run id and the failures refs; `carried-greens` → `carried-results`
  and the main-head query likewise; the carried member records the reused counts and failures.

## 4. Memory: one copy per content

1. **One identical projection per content** (row 1, in flight): equal rows → one key before derivation. The remaining
   duplicate source is the `::advanced` path: intern it by the content key of its `:db-after` after derivation (an
   alias, computed once per speculative write), never by digesting the value (11.27 ms, Astra). Expected (estimate
   from the measured count): 34 entries → 9–11 contents, ≈120 MB instead of 353–407 MB; `program-context` retained
   (> 0 bytes); `parsed-facts` and `source-resolver-names` evictions fall. Each phase's measure records these three.
2. **Keys compact or weighed.** The HEAD key holds ~35k datoms per entry; the working-tree rows key holds every
   declaration value. A key that keeps content alive is either a 64-char digest or counted in the entry's weight
   (Astra: "a small alias with a giant key is not a 256-byte retention claim"). Evicting the product must let its
   exclusive graph go: the alias holds the digest, not the rows.
3. **Weighing shared structure.** Projections share 97–100% of objects (M1) but each is weighed in full; overlays
   likewise (overlay D4). Keep the cumulative, inclusive charge: undercharging is the only unsafe direction, because a
   child can keep its parent's graph alive after the parent's entry is evicted. A smaller charge for a shared lineage
   needs clj-memory-meter evidence from a throwaway JVM on the `:measure` alias (object-graph union before and after
   an equal product, a one-row-changed product, and after evicting each parent while children remain): a measurement
   that may follow, not a phase.
4. **Retain versus hold per operation** (row 2). A speculative projection serves the transaction that derived it
   (validation of owning values, the report's `:db-after` reads). With 2 hits for 25 entries, its retention is waste
   for every content that is never committed (a refused write, a test's `d/with`). Hold it in the report's value for
   its operation; on commit the content is row 1's committed entry. This is owner question 2.
5. **Memos out of values** (row 6): the per-projection `::compiled` atom becomes one content-keyed kind (ledger SR-J).
6. **Eviction**: Caffeine's W-TinyLFU by weight is right once weights are honest and contents are not duplicated. No
   cost-aware eviction, reserved percentages or budget increase: the 512 MB dial stays.

## 4b. Owner, cache or store: the measurement behind §1b

Default, JVM mode as root, read-only, 2026-10-14 ~07:56Z (results `result/e8142925aaa6f`, `result/e5fc455c725f2`,
`result/eaa66c2c24d51`); `seon.profile/measure`, thread allocation; a scratch `konserve.filestore` under `tmp/`
(`connect-fs-store`, `:sync? true`), deleted afterwards. The JVM was busy (GC 2.7 s in the window of the first form),
so these are selectors for the rule, not quiet parent/change proofs.

| Operation | ms | alloc | note |
|---|---:|---:|---|
| `seon.schema/load-projection` of default's value, no base (cold derivation) | **1,952** | 1.44 GB | 3,758 forms |
| `seon.schema/build-projection` from the held forms and contracts (the compile alone) | **1,797** | 1.20 GB | so reading the rows is ≈150 ms and compiling is 92% |
| `seon.db/carried-projection`, held, ×100 | 1.5 | 1.1 MB | the alias hit, 0.015 ms each |
| konserve write of the projection's six data fields (forms, contracts, definition strings, admissions, exports) | 66 | 31 MB | 2.02 MB on disk |
| konserve read of the same, ×2 | 25.5, 27.6 | 38 MB | round trip `=`; `pr-str`/`edn/read-string` of the same would be 61 / 74 ms over 3.18 MB |
| `seon.fn.analyzer/parse-facts` of `seon/fn/analyzer.clj` (50,837 chars), ×3 | 21.1 (7.0 each) | 50 MB | |
| konserve write of its facts | 14.1 | 76 KB | 1,233 bytes on disk |
| konserve read of its facts, ×3 | 0.37 (0.12 each) | 224 KB | round trip `=` |

**What it decides.** (a) Reading stored immutable data is cheap (≈80 MB/s deserialized here, 0.12 ms for a small
value), so nothing stored needs a second copy in `seon.cache`; no kind holds one today (§1b). (b) The projection, the
largest kind, is not a storage candidate: its cost is the compile, which produces host objects, and the data part saves
at most ≈150 ms of 1,952 ms; one copy per content (phase 1) and derivation from a base (`load-projection` with a base,
already installed) are its fixes. (c) Analysis facts are a storage candidate by this rule (57× cheaper to read than to
parse, 1.2 KB per file) **only if** a measurement shows the same text parsed again across restarts; within one JVM the
text key already answers in 0.005 ms. clj-kondo already keeps its own disk cache for its analysis, so a facts store is
one per fact kind, owned by boot-dup-analysis with the boot measurement, never a second cache beside kondo's. Reach
indexes and rendered context units are the two other candidates; each gets the same one measurement before its
phase (noted in §1b). No owner question is left open by this measurement.

## 5. Phases, by measured payoff

Each phase: one derivation; a red proof asserted on data; a cost proof through `seon.profile/measure` on the same
end-to-end probe, parent against change on a quiet JVM; its deletions in the same commit. Models per AGENTS.md §"Pick
the model by the work".

| # | Phase | Red proof (data) | Cost proof (`seon.profile/measure`) | Deletes | Files / lane / model |
|---|---|---|---|---|---|
| 1 | **One projection per content** (row 1): the rows key digested and computed only on a revision-alias miss; `::advanced` entries interned by content key after derivation; history keys aliases | `seon.db-test/equal-declaration-content-holds-one-projection`: two branches of one commit each transact the same registration; `identical?` projections, one entry; second row: a content-equal value with different e/tx derives an `=` projection; third row: a speculative report equal in content to a held committed projection adds no entry | weighted bytes of both kinds after a fixed 10-registration sequence on two branches, parent vs change; `program-context` retained > 0 after it; rows-key ms ≤ 10% of a miss and 0 on a hit | the datom-vector key; `::advanced` duplicates of held content | `src/seon/db.clj`, `test/seon/db_test.clj`; **proj-content-key** (in flight, Opus: boot-critical read path) |
| 2 | **Speculative projections per operation** (row 2): the report carries its projection; `hold-committed-projection!` receives it by value; `::value-projection` retains only what commits | `seon.db-test/a-speculative-only-projection-is-not-retained`: a refused write and a `d/with` on a branch leave `::value-projection` bytes unchanged; a committed write's projection answers the next read with one miss | `::value-projection` bytes after 20 speculative writes of which 5 commit: ≤ 5 contents; request ms unchanged | retention of speculative-only contents; the `present` probe in `hold-committed-projection!` | `src/seon/db.clj`; **speculative lane** (queued), after phase 1 (same file); Opus (writer path) |
| 3 | **Reds retained** (row 3, §3) as a patch to B1 | `seon.test.branch-reuse-test/a-red-is-answered-at-its-identity-on-another-branch`: red on A; B with the same program answers red with A's run id, counts and failure refs, zero members executed; a zero-assertion member, a timeout and a recording refusal are not carried; `:seon.test/rerun?` executes | the M4 case: the second branch's test-step ms vs parent (today 1.05 s) | the green predicate in `retain-greens!` and `carried-greens`' main-head query | b1-test-reuse's files; **Sonnet** (specified here), via patch to that lane |
| 4 | **Derivations see no evaluation bindings** (§2, after owner question 1) | `seon.cache-test/a-derivation-sees-no-evaluation-binding`: a derive fn reading `seon.db/db` under a bound root connection returns the same refusal through `lookup` and `lookup-within`, one miss; second row: a derivation's read evidence is the same data on hit and on miss | 1,000 misses of a trivial derive fn, parent vs change: the per-miss cost of the binding push (expected µs); zero on hits | nothing (overlay phase 1's per-derivation bindings become redundant and are removed in the commit that makes them so) | `src/seon/cache.clj`, `test/seon/cache_test.clj`; **Opus** (one REPL path semantics); parallel with 1–3 |
| 5 | **Reach prior from the fork base** (row 4) | `seon.test-reaching-test/a-fresh-branch-reuses-its-fork-bases-reach-index`: a branch forked from main with one changed definition derives reach digests equal to a cold derivation's with node derivations ∝ changed nodes | the M2 probe: reach on an unheld branch from 322–345 ms / ≈500 MB to ∝ changed nodes | nothing (adds one probe; the head stays a local pointer) | `src/seon/test/runner.clj` after B1 releases; **Opus** (test runner) |
| 6 | **Compiled schemas by closure digest** (row 6, SR-J); measure first | `seon.schema-test/a-compiled-schema-is-shared-by-equal-closures` and its retention row: a shared compiled schema retains no other projection's registry | sum of compile ms over phase 1's 10-request sequence; drop the phase if compile per projection < 50 ms once phase 1 holds one copy | the per-projection `::compiled` memo | `src/seon/schema.clj`; **Astra** (performance) after phase 1 |
| 7 | **Render invocation without branch scope** (row 7) | `seon.render-test/an-equal-render-on-another-branch-is-reused`; hidden row: a render whose read changes on the viewer's branch re-renders; collision row: two contents with equal 32-bit hashes hold two entries, neither replacing the other | per-request render ms on a second branch, parent vs change; key digest ms per render | `branch-scope` in the key; `hash` keys; the evict-then-retain replacement | `src/seon/render.clj`; **Opus**; after phase 4 (renders run inside SCI under bindings) |
| 8 | **Config effective by config revisions** (row 8) | `seon.config-test/effective-is-shared-across-commits-that-change-no-config`: two recording commits, one miss; a dial change, a new dial and a cluster change each miss once | one trivial `eval_clj` request: `seon.config/effective` derivations 7 → ≤ 1; the request's ms recorded whichever way it goes (Astra: the derivation may be sub-ms) | the commit member of the key | `src/seon/config.clj`, `test/seon/config_test.clj`; **Sonnet**; any time |
| — | Program context rows 5, 14: overlay phases 3–5 | as filed in overlay-hybrid.md | as filed | | **overlay-p3** |
| — | Rows 16–18: parsed facts, file derivation key, resolver names | as filed; re-measure evictions after phase 1 | | | **boot-dup-analysis** (`fn.clj`, `cluster.clj`) |
| — | Rows 21–24: health, authored-namespace, writer digest, context units | narrow producer regressions before any phase | | | errors cut, S3a |

**Parallelism (file disjointness).** Phase 1 then 2 (both `db.clj`); phase 3 inside b1's files; phase 4 (`cache.clj`,
unclaimed) and phase 8 (`config.clj`, unclaimed) run beside them now; phase 5 waits for B1 to release `runner.clj`;
phase 6 after phase 1 lands (it needs one canonical projection to measure against); phase 7 after phase 4. Phases 1–4
are the measured payoff; 5–8 wait on measurement or on releases.

## 6. Narrow check

**(a) Live claims reproduce or cite a measurement.** Every number above names its source: M1–M4 (truths-opus.md §0,
default JVM mode ~16:00Z), Astra's result handles (`e990adbe65832`, `e26d047924ccd`, `e36d8dda18d00`,
`ef03964d0a5a1`, `e3deb0d73b96d`, `ed28d9e90dc9d`, `eefaf22027c64`), the two digs' snapshots (14:16Z, 14:40Z). The
cache moved between snapshots (471 → 506 → 509 → 510 MB; 31–48 kinds; 34–37 projection entries), so each phase
re-measures its own baseline on the restarted default before its change; a note's number is the experiment's selector,
not its parent measurement. UNMEASURED and named as such: retained bytes (clj-memory-meter is on the `:measure` alias
only); the working-tree rows key's cost and size; compile ms per projection; evidence-digest ms per render; whether any
projection field depends on e or tx (phase 1's second proof row decides). Measured by this lane (§4b): a cold projection derivation 1,952 ms against a 25 ms konserve read of its data fields, and
a 7.0 ms parse against a 0.12 ms read. Claims verified from source by this lane:
`lookup` derives on the caller's thread and `lookup-within` on a bindings-free virtual thread (`seon.cache/settle!`,
`lookup-within`); the eleven evaluation bindings listed in §2 are every public `^:dynamic` Var outside one derivation's
own stack; `retain-invocation!` evicts before it retains; `hold-committed-projection!` probes `::value-projection` by
presence; the b1 and proj-content-key edits are uncommitted.

**(b) Deletions converted in their phase.** Phase 1: the datom-vector key (its last reader is `content-projection`).
Phase 2: `::value-projection` retention of speculative-only content and the presence probe in
`hold-committed-projection!` (its caller receives the value). Phase 3: the green predicate and `carried-greens`'
main-head query (renamed with every caller). Phase 4: overlay phase 1's per-derivation `binding` of `*conn*` and
`*read-database*` in `derive-base-ctx`/`derive-overlay-ctx`, removed in the commit that makes it redundant. Phase 6:
the per-projection `::compiled` memo (`contract-products` converted). Phase 7: `branch-scope` in the invocation key and
the evict-then-retain replacement. Phase 8: the commit member. HEAD boots after each phase; no phase leaves a second
registry, kind or path beside the one it changes.

**(c) Every phase's red proof is named** in §5 (one deftest per phase, table rows for the hidden inputs), red on the
parent by construction: a second entry today (1, 2), a body executed today (3), two environments today (4), a cold
derivation today (5), a recompile today (6), a second entry per branch today (7), a miss per commit today (8).

## 7. Owner questions

1. **Should every cached derivation see no evaluation binding, at `seon.cache/lookup`?** (a) **Yes, at the one owner**
   (recommended): `settle!` runs the derive fn under root values of Seon's evaluation bindings in both entrances; a
   derivation that reads the database returns its read evidence as data; proof first, then the rule, each red fixed at
   the producer that read ambient data. (b) Per derivation, as each row is proven (overlay phase 1's form, 75 times).
   (c) No; each row's proof alone, leaving `lookup` and `lookup-within` as two environments.
2. **Do speculative projections live only for their operation?** (a) **Yes** (recommended): the report carries its
   projection; only a committed content is retained, under the content key (2 hits for 25 entries and 268 MB today).
   (b) Keep retaining them, deduplicated by content interning (saves the duplicates, keeps every never-read
   speculative content until evicted). (c) A separate byte budget for the kind (rejected here: a second policy).
3. **When one identity records two outcomes (a hidden input observed), which answers?** (a) **The newest terminated
   outcome answers and a problem task is filed on the test naming the identity** (recommended: the rerun path writes it
   anyway; the task is the fix's owner; M4 measured 0 such identities). (b) The identity answers unknown until the task
   resolves (reruns every time; a fence standing in for the fix). (c) The first outcome stands (hides the observation).
