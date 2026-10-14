---
title: "The catalogue of truths: what each derivation depends on, and the key that shares it"
type: research
status: design proposal (one of two independent plans); nothing installed
date: 2026-10-14
lane: truths-opus
basis: "HEAD a1e485f31; default read in JVM mode as root, read-only (no SCI on default); source read at HEAD with `git show`, the in-flight lanes' working-tree edits named where they matter"
---

# The catalogue of truths

Owner (2026-10-14): list what we know to be true, then make each truth cachable across every permutation of
branch and SCI context. A derivation can be shared exactly when its result is a function of named content and
nothing else. So each row below says "X depends only on A, B, C", names the key today, and names the gap.
There are two kinds of gap. **Over-keyed**: an extra input (branch, commit, revision, owner, instance) blocks
sharing but is safe. **Under-keyed**: a hidden input (a binding, the clock, host files, an uncaptured read, a captured
instance) makes sharing unsafe. The fix for over-keying is a content key, with history keys kept only as cheap
aliases that point to it (the pattern `seon.db`'s `::projection-alias` and `seon.sci.eval`'s `::program-key` already
use). The fix for under-keying is to make the input explicit, or to make it impossible to read.

## 0. Measured first (default, JVM mode, read-only, 2026-10-14 ~16:00Z)

`(seon.cache/status)`: 509.0 of 512.0 MB weighted. `:hits/:misses/:evictions/:load-ms` are counts since boot.

| kind | MB | hits | misses | evict | load ms | reading |
|---|---:|---:|---:|---:|---:|---|
| `seon.db/value-projection` | 257 | 2 | 28 | 4 | 785 | 50% of budget, 6% hit rate (health: `discipline-warning`) |
| `seon.db/projection` | 149 | 10 | 16 | 2 | 838 | 29% of budget |
| `seon.schema/declaration-projection` | 38 | 43 | 5 | 1 | 1579 | |
| `seon.schema/projection` | 21 | 6 | 4 | 2 | 1884 | |
| `seon.fn.analyzer/parsed-facts` | 16 | 127 | 582 | **538** | 2143 | content-keyed, but displaced |
| `seon.program/source-resolver-names` | 1 | 12745 | 10529 | **8833** | 2347 | content-keyed, but displaced |
| `seon.cluster/file-derivation-key` | 4 | 4986 | 2285 | 603 | 924 | content-keyed, but displaced |
| `seon.sci.eval/program-context` | **0** | 12 | 6 | **6** | 2895 | ≈480 ms per derivation, **none retained** |
| `seon.test.runner/reach-graph` | 1.9 | 1 | 14 | 8 | 4000 | ≈286 ms per miss |
| `seon.test.runner/reach-index` | 3.5 | 0 | 15 | 9 | 611 | |
| `seon.test.runner/reach-node` | 5.9 | 75328 | 3859 | 53 | 2267 | content-keyed; works |
| `seon.config/effective` | 0.5 | 248 | 50 | 11 | 29 | commit-keyed |

**M1: projections, one content held 34 times (top row by memory and by displacement).** Default retains 34
projection entries across both kinds. Grouped by `[forms function-contracts]` they are **11 distinct contents**,
in groups of 7, 5, 4, 4, 3, 3, 3, 3, 1, 1, 1. Each entry's 3,760 form values are **identical objects** to its
nearest neighbour's except for 0–11 forms (12 entries differ from another by 0 forms). Across all 34 entries there
are only about 3.4k distinct form objects out of 127,840 slots. The same holds for `function-contracts`,
`schema-dependencies` and `definition-strings` (97–100% of values identical, measured on the first 500 keys of
each). The weigher (`seon.db/projection-bytes` = 2,850 × forms) still charges each entry its full ≈10.7 MB:
**353 MB weighed for about one copy of the forms plus small deltas.** Three things are not shared per entry: the
`::compiled` memo atom (0 to 1,287 compiled schemas each, measured), `canonical-keys` and the Malli `registry`.
Each committed entry's key also retains its declaration datoms, about 35,100 per key plus a tx vector of the
same length (`declaration-content-key`), and that is not weighed. The cost lands on others. The 6%-hit projections
evict program contexts (≈480 ms each, 0 of 6 retained), parsed facts (538 evictions) and resolver names (8,833
evictions), all of which have high hit rates. Real retained bytes are UNMEASURED: clj-memory-meter is only on the
`:measure` alias.

**M2: reach on a branch (second by cost per request).** `seon.test.runner/reach-digests` for one test on a
branch whose index is not held costs **322–345 ms and ≈500 MB allocated** (`seon.profile/measure`, 205 reads,
3 branches measured: 286, 322, 345 ms). Repeating it on the same value costs 1.6–68 ms. A miss happens whenever the
branch's attribute revisions differ from a held index's (`::reach-index-by-revision` keyed by revisions). The
incremental path finds a prior only through `::reach-head` keyed by `reach-owner` with a lower basis-t. So a
branch never starts from a sibling's or main's index, even when 99% of the nodes are equal. An earlier pass of
15 reach calls over 3 branches cost 4.1 s and 8.4 GB allocated.

**M3: program context (third).** About 480 ms per derivation (2,895 ms over 6 misses), and **0 bytes held**: all 6
entries were evicted. Program contexts are content-keyed (`program-content-key`), but the cache cannot keep one
under projection pressure. Lane overlay-impl owns the derivation, and M1 owns its eviction.

**M4: test results, today's repeat rate (lower than assumed).** Stored terminated members on every branch since the
last reset: 172 distinct executions over 171 identities `[sym reach-digest input-digest evaluator]`, all
`:interpreted`, 1 input digest, 18.2 s of `run-ms` in total. **Exactly one identity ran twice, and it was red**
(1.05 s wasted). No identity has mixed outcomes. Five test symbols ran on two branches, each time with two distinct
reach digests (real program differences in `seon.context`). Main holds 0 members. Cross-branch reruns of identical
tests are therefore rare in today's store. The red repeat is the case today's rule misses. Volume will grow with
live chains, and the B1 design is correct, but on measured payoff today M1 comes before it.

## 1. The catalogue

Columns: **truth** (the inputs the result is a function of), **key now** (HEAD unless marked), **gap**, **corrected
key**, **owner/lane**. "Over" means an extra key member blocks sharing. "Hidden" means an unkeyed input.

| # | Derivation | Truth: depends only on | Key now | Gap | Corrected key | Owner |
|---|---|---|---|---|---|---|
| 1 | **Test outcome** (one member's terminated pass/fail/error counts and failures) | the test's static closure content (definition digests including resolver context, schema leaf digests: the reach digest); the declared non-program inputs (`:seon.test.run/input-digest`: widening files, deps, gitlinks, config); the task inputs a task-reading member judges (`task-reach-digests`); the evaluator kind | HEAD: none across branches (each branch re-selects by its own stored runs; `seon.test` `::changes` includes the branch). **B1 in flight (uncommitted)**: `seon.test.runner/green` `[sym reach-digest input-digest evaluator]`, greens only, with a main-head fallback | **Over**: reds not retained (M4's one repeat). `input-digest` is whole-checkout, so any widening file invalidates every outcome; that is safe, so it stays. **Hidden**: database facts a body reads that are neither program nor task inputs (an ambient `seon.db/db` query over branch data), wall clock, files outside widening roots, dynamic calls (excluded already: `executed-roots` `::stale` keys nothing) | same key; value = the outcome, **red included** (section 3); boundary failures never retained | b1-test-reuse |
| 2 | **Reach digests** (each test's closure digest) | the set of `[name leaf-digest out-edges]` of the program nodes and schema nodes in the closure | `::reach-index-by-revision revisions`; `::reach-graph [revisions (set test-symbols)]`; `::reach-index [(set test-symbols) nodes leafs]`; prior via `::reach-head owner` | **Over**: Datahike attribute revisions (history, per branch), owner, and the requested test set. M2: a branch never reuses a sibling's index | graph/index keyed by **program content** (no test-symbol set); prior = the held index with the most equal nodes, of any owner (the `seon.db/nearest-base` rule applied to reach); revisions stay as an alias | runner.clj (b1 holds it) |
| 3 | Reach node | one declaration's attributes, plus its behavior digest when it has a doc | the whole declaration map + `::leaf` | exact; over-keyed by hashing every attribute (cheap: 75k hits) | keep | — |
| 4 | Reach attribute | the datoms of one attribute | `[attribute shape revision]` | **Over**: revision. Equal across forks of one commit, distinct after independent equal writes | keep: the derived consumer (#2) is where content keying pays | — |
| 5 | **Schema projection of a database** (`seon.db/content-projection`, `speculative-report-projection`) | the declaration content: forms, function contracts and options as the builder derives them | `[::declaration-content builder <every declaration datom with e and tx>]`; `[::advanced builder <revision key> <written datoms with e, tx>]` | **Over**: entity ids, tx ids and revision history. 34 entries hold 11 contents (M1). The key retains about 35k datoms and is not weighed. **Hidden?** whether any projection field (`catalog`, `shape-rows`) depends on e or tx; proof below | canonical `[::content builder (id/digest content-of-projection)]`, every history key an alias of it (`::projection-alias` exists). Intern after derivation: digest the derived value's content, and when it is held return the held instance | db.clj (free) |
| 6 | **Compiled schema / contract product** (the projection's `::compiled` atom, `seon.schema/contract-products`) | the form of key k, the forms of k's schema-dependency closure, compile options, predicate functions | per projection instance | **Over**: the instance. Each of 34 projections recompiles (0–1,287 each). **Hidden**: a compiled Malli schema closes over the projection's `registry`, so sharing one across projections retains the old registry, its forms and its projection | `seon.cache` kind `[k closure-digest options-digest]`, compiled against a registry of k's closure only (so it captures nothing larger than its truth) | schema.clj (free; measure first) |
| 7 | `seon.schema/projection` | forms, function contracts, options | `[forms function-contracts options]`, whole maps hashed per lookup | exact but the same truth as #5 in a second layer; whole-map keys cost equality on instance change (the B4 lesson) | `[declaration-digest-of-content options-digest]` | schema.clj |
| 8 | Declaration projection, packaged population, authored shapes | the packaged declaration digest | digest (B4, f8a2773be) | fixed: 62 population entries became 4 misses | keep | done |
| 9 | Program context, cluster base | the loaded commit's program (the JVM's compiled Vars) | `[store commit]` | exact: the loaded commit *is* the platform. Equal content under two commits happens only across restarts | keep | overlay-impl |
| 10 | **Program context, overlay** | base, plan digest (held definition digests incl. resolver context, namespace rows, test rows), projection delta, armed dials | `program-content-key` | exact for content; **hidden**: deriving thread's `seon.db/*conn*`/`*read-database*` (overlay-hybrid phase 1), an init's host reads, a context-less call's defining context (D1). Retention: 0 of 6 held (M3) | keep the key; phase 1 isolation; retention follows from #5 | overlay-impl |
| 11 | `::program-key` pointer | store, program and dial revisions, supplied loaded commit | that tuple → content key | history-keyed **pointer** to a content key, 1 KB: the right pattern | keep | — |
| 12 | Loaded definition digests | loaded commit | `[store commit]` | exact | keep | — |
| 13 | Overlay plan | held definition digests vs the loaded commit | `[store basis commit]` | **Over**: basis | phase 5 of overlay-hybrid (plan from P's plan) | overlay-impl |
| 14 | **Config effective** | the cluster's config entity, the dial attributes' content, the declaration digest | `[commit cluster declaration-digest]` | **Over**: commit. Every recording transaction is a new commit, so a new miss: 50 misses, 7 calls per request (perf-request) | `[(db/content-revisions db config-attributes) cluster declaration-digest]`: the same revision rule `program-key-input` uses for dials | config.clj (B2) |
| 15 | **Render invocation** | the render function's code (program evidence), projection evidence, selection input, and the reads it made (read evidence, checked by `seon.db/read-evidence-current?`) | `[branch-scope selected output h(program) h(projection) h(input)]` | **Over**: `branch-scope`. Read evidence is already checked against the viewer's database. **Hidden**: any read not captured as evidence (a render reaching `datahike.api` directly) | drop `branch-scope`; currency stays the read-evidence check | render.clj |
| 16 | Parsed facts | source text | the text | exact; displaced (538 evictions) | keep | perf-analyzer |
| 17 | File derivation key | file bytes, macro/inline dependency digests, used schema forms, world key | `[code kondo input world start]` | exact; displaced (603) | keep | — |
| 18 | Source resolver names | source, qualifiers, symbols | `id/id` of those | exact; displaced (8,833 evictions of 10,529 misses) | keep | — |
| 19 | Task file declarations | path (a task's namespace comes from its file name), text, forms | `[forms path text]` | exact: the path is a true input here | keep | — |
| 20 | `seon.test` changes | program revisions vs base | `[cluster base branch revisions]` | **Over**: branch | B1 already drops it (uncommitted) | b1-test-reuse |
| 21 | Vector pull | projection, cells, selector | `[projection-identity cells? selector]` | over-keyed by instance; becomes content once #5 interns | follows #5 | — |
| 22 | Definition digest at the writer | canonical row + namespace resolver context | none: computed per write | not a sharing gap; unmeasured cost | measure first | db.clj |
| 23 | Health derived | name, the reads it made | `derivation-key database name reads` | UNVERIFIED (not traced) | trace | health.clj |
| 24 | Context units / current-state block | the agent's task, its references' content, the read evidence of each unit | none: rebuilt per request (≈821 reads on a repeat) | not cached at all; the truth is a render (#15) over attached units | render invocation per unit once #15 is content-keyed | S3a (seon.context) |

Rows 1, 2, 5, 6, 14 and 15 are the open gaps. Rows 9–13 belong to overlay-impl. Rows 16–18 are correct, and they
recover once #5 stops displacing them.

## 2. Hidden inputs: how each is made explicit or excluded

| Hidden input | Where it can leak | Made explicit or excluded by |
|---|---|---|
| Ambient dynamic bindings (`seon.db/*conn*`, `*read-database*`, `*ns*`, print and profile Vars) | program context (observed: L2 installed under root's binding and refused without it), any derive fn run inside a request | the derivation runs with Seon's evaluation bindings cleared. Overlay-hybrid phase 1 does this for program contexts. The general form: a `seon.cache/lookup` derive fn sees only its arguments. Owner question 1 |
| Reads of the database | renders, health, task-reading tests | captured as read evidence and checked for currency (renders: `read-evidence-current?`); for tests, the task inputs digest. A read outside `seon.db` is a defect of its producer (AGENTS.md: direct `datahike.api` only in the owners) |
| Host files | tests, analysis | declared and digested (`input-digest` widening roots; file SHA-256 in #17) or not read |
| Clock, randomness, thread identity | tests (deterministic by law), SCI context-less calls | tests: bounded generators, and a mixed outcome for one identity is the observation of a leak (section 3). SCI: rebinding reached callers in q (overlay D1) |
| The JVM's loaded program | compiled evaluator, base context | the loaded commit is part of the key (#9, #12); the `:compiled`/`:interpreted` evaluator kind in #1 |
| Captured instances (a registry, an atom, a connection) | compiled schemas (#6), projections' memo atom | compile against a closure-only registry; memos move out of the value into content-keyed kinds; values never hold connections (render: `retain-invocation!` already dissociates db, snapshot and projection) |
| History ids (e, tx, revision, commit) | #2, #4, #5, #14 | over-keying, not unsafe: aliases to a content key |

**Hidden-input proof shape (one table deftest per row, asserted on data):** derive with the declared inputs I in
two environments that differ in everything else (another branch, another thread, a bound root connection, a
different commit of equal content, a later clock). Expect `=` values and **one** cache miss (`seon.cache/status`
misses delta = 1). Then vary each declared input alone. Expect a different value and one more miss. A row whose
proof cannot be written lists the input it found as a new key member, or excludes it by construction.

## 3. Red results

**Yes: retain a terminated red at its key exactly like a green.** The owner's rule ("a test result is reused at its
key, red included") and AGENTS.md's ("unknown or red cannot reuse green") do not conflict. Reuse means answering
*the recorded outcome* at that identity. A reused red is reported red, with its run id and its failures, and
never as green. Neither rule converts one outcome into another.

- **What is retained:** a member whose execution terminated inside its body. That covers pass, assertion failure, and an
  exception thrown by the body (`:seon.test/error-count` from clojure.test's report). These are functions of
  content.
- **What is never retained:** boundary failures, which are not outcomes of the content. That covers a timeout, an
  interrupt, a missing `:seon.test.member/terminated-tx`, a `:seon.test.member/error` boundary failure, or a recording
  refusal. Each reads as unknown and is rerun.
- **Selection:** a held red at the current identity selects *no execution*. The task's proof stays red until
  something in its key changes: the agent edits the program (a new reach digest), or asks for an explicit rerun
  (`:seon.test/rerun?` exists and bypasses reuse).
- **Merge:** a reused red blocks exactly as an executed red does.
- **Flakiness:** two outcomes recorded for one identity are a positive observation of a hidden input. The rerun
  path writes the second outcome, and the test gets a problem task naming the identity. M4 measured 0 such
  identities today.
- Change to B1: `retain-greens!` becomes `retain-results!` (drop the green predicate; keep `terminated`), its value
  holds the counts plus the run id, and `carried-greens` becomes `carried-results`. Its main-head query drops
  `[(> ?pass 0)] [?m :seon.test.member/fail-count 0] [?m :seon.test.member/error-count 0]` and keeps
  `(not [?member :seon.test.member/error])`. The carried member records the reused counts and failures refs.

## 4. Memory: one copy per content

1. **Intern projections by content (#5).** After a derivation, digest the result's content (forms, function
   contracts, options, every field except memo atoms). If that digest is held, return the held instance and
   alias the history key to it. Measured: 34 entries become 11 contents. At full weight that is about 118 MB
   instead of 353 MB, an estimate from the measured count. The weight rule stays cumulative, which is overlay D4's
   argument (undercharging is the unsafe direction). Weighing deltas against a shared lineage needs clj-memory-meter
   evidence first; that is a later measurement, not a phase.
2. **Key by digest, never by datoms.** The committed key holds about 35k datoms per entry, unweighed. A 64-char
   digest replaces it. The digest is still computed from those datoms (≈5 ms, the existing pass), but the cache
   then holds only the digest.
3. **Move memos out of values (#6).** The `::compiled` atom per projection becomes one kind shared by closure
   digest. That deletes 34 private memos and one source of non-sharing.
4. **Eviction:** Caffeine's W-TinyLFU by weight is correct once weights are honest. Today the 6%-hit kinds win by
   their weight, not their value. No cost-aware eviction is proposed; honest weights and one copy are the fix. The
   expected observation is that `program-context` is retained (more than 0 bytes) and the evictions of
   `parsed-facts` and `source-resolver-names` fall. Each phase's measure records these.

## 5. Phases (one derivation per phase, ordered by measured payoff)

| # | Phase | Red proof (data) | Cost proof (`seon.profile/measure`) | Files / lane |
|---|---|---|---|---|
| 1 | **Projection interning by content (#5)**: canonical key = builder + content digest; `::declaration-content`/`::advanced` keys become aliases; the datom vector leaves the key | `seon.db-test/equal-declaration-content-holds-one-projection`: two branches of one commit each transact the same registration; `identical?` projections, one entry. Second row: a content-equal value with different e/tx ids derives an `=` projection (the hidden-input row for e/tx) | weighted bytes of the two projection kinds after a fixed sequence of 10 registration requests on two branches: parent vs change; and `program-context` retained > 0 after the same sequence | `src/seon/db.clj`, `test/seon/db_test.clj` (free) |
| 2 | **Reds retained (#1)**, as a patch to B1 | `seon.test.branch-reuse-test/a-red-is-answered-at-its-identity-on-another-branch`: run red on branch A; branch B with the same program answers red with A's run id and zero members admitted; an explicit rerun executes; a timeout is not carried | the M4 case: the second request's test step ms vs parent | b1-test-reuse's files (patch to that lane) |
| 3 | **Config effective by config revisions (#14)** | `seon.config-test/effective-is-shared-across-commits-that-change-no-config`: two recording commits, one miss | one trivial `eval_clj` request: `seon.config/effective` derivations 1 (today 7 calls, a miss per commit) | `src/seon/config.clj`, `test/seon/config_test.clj` (free) |
| 4 | **Reach prior across owners (#2)**: a held index of any owner, nearest by equal nodes; graph keyed by content, not test set | `seon.test-reaching-test/a-fresh-branch-reuses-a-siblings-reach-index`: digests equal to a cold derivation's | M2 probe: reach on an unheld branch from 322–345 ms to ∝ changed nodes | `src/seon/test/runner.clj` (after B1 releases) |
| 5 | **Compiled schemas by closure digest (#6)**; measure first: count distinct closures among the 34 projections' compiled keys; drop the phase if compile time per projection is under 50 ms | `seon.schema-test/a-compiled-schema-is-shared-by-equal-closures` and its retention row: a shared compiled schema does not retain another projection's registry | sum of compile time over the 10-request sequence of phase 1 | `src/seon/schema.clj` |
| 6 | **Render invocation without branch scope (#15)** | `seon.render-test/an-equal-render-on-another-branch-is-reused`, plus the hidden row: a render whose read changes on the viewer's branch re-renders | per-request render ms on a second branch | `src/seon/render.clj` |
| — | Program context rows (#10, #13): overlay-hybrid phases 1–5 as designed | as filed there | as filed there | overlay-impl |
| — | Parsed facts (#16): perf-analyzer, as filed | | | perf-analyzer |

Phase 1 comes first. It costs the least, it is measured as the largest, and it restores three other caches'
retention. Phase 2 is small and closes the owner's red asymmetry. Phases 4 and 5 wait on measurement or on lane
releases.

## 6. Owner questions

1. **Derivations see no ambient bindings, generally.** Should `seon.cache/lookup`'s derive fn run with Seon's
   evaluation bindings cleared, the general form of overlay phase 1? Options: (a) yes, at the one cache owner,
   recommended: it is the only place every derivation passes, it makes the hidden-input proof hold by construction,
   and it is a mechanism, not a check; (b) per derivation, as each row is proven; (c) no, rely on each row's proof.
2. **Red retention scope (section 3).** Options: (a) body outcomes retained, boundary failures never, recommended;
   (b) greens only, today's B1; (c) everything, boundary errors included. (c) would carry timeouts as facts of content,
   and they are not.

## Evidence and limits

- Forms run on default in JVM mode as root, read-only: `seon.cache/status`; `seon.cache/retained` over
  `:seon.db/projection`, `:seon.db/value-projection` and `:seon.schema/declaration-projection`, comparing with
  `identical?`/`hash`; stored members across the 13 non-base branches through `seon.db/branch-database`; and
  `seon.profile/measure` of `seon.test.runner/reach-digests` on unheld branches (322, 345 ms; ≈500 MB). The
  `seon.profile/measure` output is JVM-wide, so `seon.cluster.source/capture-paths` appearing in one window is
  attributed to concurrent work, not to reach.
- The dig's §2 line "retain-greens! … VERIFIED" describes **uncommitted** b1-test-reuse work, not HEAD. HEAD has no
  `seon.test.runner/green` kind.
- UNMEASURED: retained bytes (no clj-memory-meter on the default classpath); whether any projection field depends
  on e/tx (phase 1's second proof row decides it); compile time per projection (phase 5 measures it first).
