---
title: Implementation cards from the 2026-10-08 audits
type: reference
status: active
created: 2026-10-08
---

# Cards from the 2026-10-08 audits (lane cards-audits)

Every finding of today's audits that no other row covered is now a ledger row
([agent-loop-ledger.md](../agent-loop-ledger.md)); this page holds the owner questions those rows wait on, the order
rules between deletions and fixes, and the card index. Card conventions are the ones in [README.md](README.md)
("Common to every card"). Seams were verified at HEAD `3f69939e4` plus the uncommitted working tree where a lane holds
the file (noted per card).

Sources read in full: `storage-audit/storage-audit-opus.md`, `storage-audit/storage-audit-astra.md`,
`reinvention/reinvention-opus.md`, `reinvention/reinvention-astra.md`, `vectors/vectors.md`, `dials/dials.md` (lane
dials-16gb still holds it; read as of this commit), `defn-why/defn-why.md`, `review/plan-review-opus3.md`,
`review/plan-review-astra3.md`, `clojure-way/clojure-way-opus-clojure-way.md`,
`clojure-way/research-agenda-clojure-way.md`; authority `review/owner-rulings.md` and AGENTS.md "Words and where they
come from". **Pending:** `red/red-1.md` does not exist yet (lane led-red1 holds it); RED-1 keeps its row until it lands.

Excluded by assignment: transaction-census rows (cards-recording); S4, CS, SR-H, SR-I and BLOB-K (cards-context); CS,
F4, S6-06a, A1, F6, F7 (cards-tasks).

## Owner questions (one list; answered 2026-10-08: every recommendation accepted except AU-Q15 and AU-Q17, deferred; `review/owner-rulings.md` last bullets)

Each is a place where Seon keeps its own version of something a library does, or a storage choice the rulings left
open. The ruling "stop reinventing" says keeping ours is the owner's call, case by case, with the gap stated. Nothing
below is implemented until the owner (or root citing an authority) answers here. **(recommended)** marks the
recommendation.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q1. Filling missing function arguments from the agent's world (`seon.call-preparation`, 1,130 lines).** Malli's
`default-value-transformer` fills missing *map keys* from `:default`/`:default/fn`, but never inserts a missing
*positional* argument, which is what CLAUDE.md's "supply defaults by declared schema name" needs. Also: of the four
stored supplied-default rows on default, three are already answered by `seon.batch/world-members`; only
`:my.program/context` needs the ~270 lines of row machinery (`snapshot`, `derive-snapshot` and its core.cache memo,
`call_preparation.clj:244-510`).
1. **(recommended)** Keep the positional planner as Seon's (the gap is real), make `:my.program/context` a world member
   and delete the row machinery (UL-10), and fill missing map members with Malli's transformer and `:default/fn`.
2. Delete supply: callers pass world arguments explicitly; CLAUDE.md's supply law is withdrawn. Deletes most of the
   namespace; every agent-facing call gets longer.
3. Keep everything as it is.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q2. Installing contracts (`seon.instrument`, 1,335 lines).** The wrapper is already Malli's `m/-instrument`.
Malli's own loop `malli.instrument/-strument!` cannot replace ours because it passes no registry, stops at the first
failing Var, re-wraps unchanged Vars and has no profile bracket. Two smaller copies sit beside it: a primitive-function
test copied from Malli's private one (`instrument.clj:65-81`), and a ranking that picks one failed branch of an `:or`
(`instrument.clj:379-427`) where Malli reports all of them.
1. Keep our loop; take only the deletions that need no decision (UL-3a, UL-5).
2. **(recommended)** Change our Malli checkout (`reference-code/malli`, used as a local root): a 3-arity `-strument!`
   that takes options (registry), continues past a failing Var and returns the failures, and a public primitive-fn
   predicate; then call them and delete about 100 lines. Show Malli's full `:or` explanation through the value printer
   instead of our ranking.
3. Rewrite onto `mi/instrument!` with Malli's global function registry. Conflicts with per-branch contracts; not
   recommended.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q3. Printer layout prediction (`seon.render.value`, `flat-width`/`extras`/`breaks?`/`settle`, about 100 lines).**
Fipp lays text out in one pass and cannot say "how long would this be" incrementally, so we re-derive its fits test to
charge each breadth-first admission its length.
1. Keep it.
2. **(recommended)** Admit nodes level by level on flat width, run fipp once per level and cut the level that
   overflows (O(levels × n)); delete the prediction. Needs a measurement on the printer's golden values first.
3. Add an incremental fits API to a fipp fork (fipp comes from Maven; no fork exists).

**Answered (owner 2026-10-08): the recommendation.** **AU-Q4. Structural hash (`seon.id`, 190 lines).** hasch's `uuid` is on the classpath (Datahike uses it), but measured
2.2× slower on small maps and it throws `StackOverflowError` on a 10,000-deep vector, which `seon.id` handles (the
exact case `seon.turn/a-deep-value-loses-the-run` guards). Our stack-safe `id/printed` is also used to sort and
compare, which CLAUDE.md forbids.
1. **(recommended)** Keep `seon.id`; convert `printed`'s compare and sort callers to data comparison (PR-24 already
   lists `schema/datahike.clj:525`); `printed` stays only for source text.
2. Adopt hasch and accept a depth limit and the cost.
3. Keep both as they are.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q5. "Did you mean" suggestions.** A 12-line edit distance ranks a namespace's names near a
missing symbol (`edit-distance`, `names-in`, `error.clj:1834-1860`). Malli's distance is private and only suggests map keys.
1. Keep ours.
2. **(recommended)** Make Malli's edit distance public in our Malli checkout and call it; use Malli's
   `with-spell-checking` for schema key typos.
3. Delete the ranking; list the namespace's names unranked.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q6. Cache statistics (`seon.cache`, AT-8).** The ruling says Caffeine's `recordStats` replaces our counters. The
gap: Caffeine's stats callbacks get no key, so one shared weighted cache cannot report hits and misses per kind.
Separate caches per kind would break the one byte budget.
1. **(recommended)** `recordStats` for whole-cache hits, misses and evictions; per-kind entries and bytes computed on
   demand in one pass over the map; per-kind hit rates are lost. Keep `claim` (Caffeine's atomic `get` forbids the
   nested lookups our derivations make).
2. Keep per-kind counters beside `recordStats` as a named exception.
3. Research a Caffeine stats extension first.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q7. HTML serializer (`seon.render.hiccup`, 525 lines).** hiccup 2 is not on the classpath; it does not sort
attribute keys (we rely on that for identical bytes) and does not normalize camelCase style keys.
1. Keep ours.
2. **(recommended)** Add hiccup 2, sort attributes and normalize style keys before calling it (about −400 lines, one
   new dependency; a `deps.edn` change waits on task
   `seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check`).
3. Use the Datastar SDK's hiccup support if it has one (unverified).

**Answered (owner 2026-10-08): the recommendation.** **AU-Q8. Flow internals (`seon.flow`).** Two gaps. (a) Flow's graph error channel is a sliding buffer that drops
faults and `flow/stop` closes it before an unwinding proc reports (`flow/impl.clj:102`, `:179`), so we reify our own
`WritePort` and reach into `flow.impl.graph`, `flow.spi` and `core.async.impl.*`. (b) Our I/O launcher adds bounded
concurrent submissions and one completion after actual exit, with three atoms per submission. core.async comes from
Maven; there is no fork.
1. Keep the reach-ins, pinned by a test, and move the launcher's atoms into proc state (AT-4).
2. **(recommended)** Start a core.async fork with an `:error-chan` option and delete the reify; run each admitted I/O
   job as an ordinary proc with its counts in proc state.
3. Accept fault loss (contradicts "deliver every error").

**Answered (owner 2026-10-08): the recommendation.** **AU-Q9. EDN symbol test (`seon.program/edn-symbol-text?`, 44 lines).** `clojure.edn/read-string` agrees on all 14,724
interned names and every edge case at a negligible cost. The gap: the reader throws on non-symbol text, so the
replacement needs a `catch` in a predicate, which CLAUDE.md's three-catches law does not list.
1. **(recommended)** Replace it with the EDN reader and record the predicate's catch as a named exception to the
   catch law (a deliberate translation of "not a symbol" into `false`).
2. Keep ours.
3. Ask edamame for a non-throwing token test (no such API today).

**Answered (owner 2026-10-08): the recommendation.** **AU-Q10. Read evidence (`seon.db`, about 430 lines of query capture and replay).** Datahike already gives dependency
plans; ours also proves that writes to other entities sharing an attribute leave a read unchanged, sometimes by running
extra queries. Read evidence also repeats text: 49% of its plan, revision and request characters on default are
copies (storage audit finding 9, row SR-R).
1. Accept attribute-level invalidation (more re-renders) and delete the pattern capture and replay.
2. Move precise read evidence into our Datahike fork's query execution.
3. **(recommended)** Measure first (row SR-R0: how often precise evidence avoids a re-render on default), then choose
   1 or 2; SR-R (store each plan once) waits on this answer, since option 1 deletes what it would shrink.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q11. Function signatures (`fn/signature.cljc`, `program.cljc:768-1008`, `fn/analyzer.clj:565-667`).** We re-parse
source for binding structure clj-kondo does not expose. clj-kondo is our fork.
1. Use only clj-kondo's arity and metadata fields and stop storing the extra binding detail.
2. **(recommended)** Expose exact bindings from our clj-kondo fork's existing parse and delete our re-parse.
3. Keep the re-parse.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q12. Checked writer validation (`db.clj:3700-3836`, `4097-4348`).** Datahike validates types and refs, not whole
Malli owning values, multiple owners, component cycles or our deletion rules. defn-why measured the checked writer at
14 MB per `defn` (`transaction-report-datom?` called 21,000 times for 157 datoms).
1. **(recommended)** Delete our duplicate normalization of submitted values and validate the final report once through
   Datahike's report hook (PB-10 lands inside this slice).
2. Reduce to Datahike's native rules (weaker guarantees).
3. Keep it as an approved extension and fix only the hot predicate (PB-10 alone).

**Answered (owner 2026-10-08): the recommendation.** **AU-Q13. Schema policy around Malli (`schema.clj:63-177`, `:589-645`, `schema/internal.cljc:47-151`, `:329-425`,
`schema/admission.clj:198-227`).** Malli compares forms but not meaning under different local registries, lets the later
map entry win where we refuse conflicts, accepts shapes our admission refuses, and our admission walks schema syntax
by hand.
1. **(recommended)** Keep only the policy checks (scope-aware equality, conflict refusal, admission rules) as callbacks
   over Malli's `walk` and `merge`; delete the hand-written grammar walk.
2. Use Malli's native merge and accept every Malli-valid schema.
3. Keep as is.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q14. clj-kondo inputs and its cache (`fn/analyzer.clj:215-472`, `:668-795`).** We keep private mirrors so a
rejected branch definition never becomes a shared dependency fact.
1. **(recommended)** Feed all captured files in one run with the cache off and reuse only dependency inputs.
2. Expose an immutable dependency-analysis input in our clj-kondo fork.
3. Keep the mirrors.

**Deferred (owner): asked after its deleting row lands.** **AU-Q15. Neighborhood reads for rendering (`render/walk.clj:189-538`, `:675-838`).** We pick neighbors by schema
links and distance; Datahike's pull follows a selector. The S4 context rows delete the model-context caller
(`declared-sources`, `render.walk/neighborhood`), so ask after S4-07b3.
1. **(recommended)** Compile one selector from those choices and use Datahike's pull.
2. Plain pull selectors as the contract (navigation changes).
3. Keep ours for distance and continuation only.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q16. Which blobs are still referenced (`cluster/registry.clj:435-583`).** Our blob-root walk reads Datahike's
sorted-set internals per branch and checks only each branch head and its history; Datahike's collector visits every
retained commit. A blob referenced only from a retained older commit (a `:db/noHistory` attribute, a purged datom, a
removed schema) is outside our walk (unproven loss risk, astra finding 1).
1. Use Datahike queries per digest attribute inside the existing extension (simple; repeats reads of shared nodes).
2. **(recommended)** Add an attribute-value fold to our Datahike fork's collector, visiting the commits it already
   marks, and delete our walk.
3. Approve our walk with a regression test; leave the retained-commit gap.

**Deferred (owner): asked after its deleting row lands.** **AU-Q17. Namespace reader state (`sci/reader.cljc:75-225`, `:384-448`).** edamame's `parse-ns-form` parses `ns`
clauses; ours also tracks `in-ns`, runtime `require` and refer renames without evaluating. UL-1d deletes the reader's
declaration functions first; ask after it.
1. **(recommended)** Use `edamame.core/parse-ns-form` for `ns` and keep only the runtime transitions.
2. Resolve through an evaluating SCI context.
3. Keep ours.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q18. Display tokenizer (`repl.clj:416-467`).** A small lexer classifies reply text for display; AGENTS.md says
never parse code with strings. The gap: reply text can hold printer elisions that are not valid Clojure.
1. Show plain escaped text and delete the lexer.
2. **(recommended)** Tokenize with rewrite-clj and leave regions it cannot parse plain.
3. Keep the lexer.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q19. `future`/`pmap` in agents (`sci/kernel.clj:669-719`).** SCI's future addon conveys bindings but its `pmap`
calls the host `future-call`, bypassing our accounting, so we copied `pmap` (27 lines).
1. **(recommended)** Add an injectable `future-call` to our SCI checkout's addon and delete the copy.
2. Remove `pmap` from what agents get.
3. Keep the copy.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q20. How norms run in a merge (MA-8a-c).** Datahike's norm runner calls each norm with a connection and transacts
separately (`norm/norm.clj:140-161` at `86c3301a`), and `ensure-norms!` reads resources; the approved design wants pure
`db → tx-data` norms inside the one guarded merge transaction. The rulings approved norms as the feature, not this
replacement mechanism.
1. **(recommended)** Add a seam to our Datahike fork that computes ordered norm tx-data and keeps Datahike's installed
   `:tx/norm` tracking, then compose it with the guarded merge.
2. Approve Seon's own runner as written in MA-8c (records `:tx/norm` itself).
3. Run norms through `ensure-norms!` as separate transactions after the merge (not atomic).

**Answered (owner 2026-10-08): the recommendation.** **AU-Q21. Program source over 4,096 bytes.** 286 definitions (1.9 M characters) exceed the storage-roles threshold and
stay in datoms, copied into every index leaf and node version.
1. Store it as a blob by the one rule (`:seon.fn/source-blob` plus size); every source reader changes.
2. **(recommended)** Keep source in datoms; attack its memory through the byte-bounded node cache (DH-4) and by
   shrinking oversized functions (a 28 KB `defn` is itself the defect).
3. Keep source only in Git for main's program and in datoms only on branches that change it.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q22. One query cache or two (after DH-6 measures Datahike's hits).** Several `seon.cache` kinds wrap a single
`d/q` keyed by commit (e.g. `:seon.sci.eval/loaded-function-rows`, 23 MB, 0 hits).
1. **(recommended)** Keep Seon kinds only for derivations of more than one query; delete kinds that wrap one query.
2. Raise Datahike's weight limit and delete every single-query kind.
3. Make Datahike's result cache a `seon.cache` kind through a fork seam.

**Answered (owner 2026-10-08): the recommendation.** **AU-Q23. "Was this value cached at time t?"** Caffeine keeps no history.
1. **(recommended)** Answer "unknown" (SR-AUDIT does this; no writes).
2. Store occasional compact cache summaries.
3. Record every admission, eviction and hit (not recommended on a 4 GB heap).

**Answered (owner 2026-10-08): the recommendation.** **AU-Q24. The free-disk floor (dials.md D31).** `:seon.config.maintenance/min-usable-bytes` ships 50 GiB; on a
256 GB laptop that reports "low space" with a fifth of the disk free. `:seon.config.maintenance/min-usable-ratio` also
exists.
1. Keep 50 GiB.
2. **(recommended)** 10 GiB, so a 16 GB-RAM laptop with a small disk is not flagged by default, with the ratio as the
   second floor.
3. Drop the byte floor and keep only the ratio.

**Keeps to confirm (no change unless the owner objects):** `seon.profile` (JFR samples; we need exact per-Var counts);
SCI context rebase (`sci/fork` is one-way, private Vars must survive); the cycle-safe cause chain beside
`Throwable->map` (no cycle guard, no suppressed exceptions); the `clojure.test/report` adapter (kaocha not on the
classpath); the file and process adapters (no-follow custody, verified process exit); Datahike merge decisions by
declaration identity; MCP as the entrance (nREPL would add, not delete).

**Answered by an existing authority (now rows, no question):** reload ordering → clj-reload (AGENTS.md table, UL-9);
process LRUs and global `memoize` → `seon.cache` (CLAUDE.md, SR-J); the reply built by string splicing → the value
printer (PR-17, extended); the counted wake buffer → `async/sliding-buffer` (nothing reads its count, UL-6);
query-string parsing → `ring.util.codec/form-decode-map` (UL-7); ordered values → the installed vector cells (ruling);
delimiter repair → kept (ruling "lightweight forgiveness"); the REPL loop's stage names → clojure.main's (ruling, CN-20).

## Order: deletions before fixes

A row that deletes code goes before any row that would fix the same code; the fix row is dropped or narrowed.

- UL-6 deletes `CountedSlidingBuffer`; AT-4 no longer moves it.
- UL-5 deletes `schema-expectation`/`bound-text`/`collection-member-problem`; MS-08 lands after it and does not polish them.
- S4-01 adds `seon.db/last-agent-ordinal`; PB-09 converts `seon.turn/terminal-agent-ordinal` to it instead of tuning the
  old query; RP-14/RP-15a delete more of the same queries, so PB-09 covers only what survives them.
- DH-4b replaces Datahike's core.cache node LRU (and its `swap!` per node access) with a byte-weighed cache; DH-5's node
  half is therefore dropped, DH-5 keeps only the query-result and plan halves.
- SR-L corrects which attributes are blob references; SR-G2 caches that answer; SR-AUDIT reads it. AU-Q16 option 2
  would delete all three's walk, so SR-L and SR-G2 stay small.
- SR-P deletes `put!`/`put-binary!` after SR-D (landed), so no later row re-adds a blob writer without a fact.
- SP-1a deletes `:seon.error/data-edn` (reverting `b233d838f`'s fix with it); SR-T is retired for SP-1a, SP-3, SP-4.
- AU-Q10 option 1 deletes the read-evidence capture SR-R would shrink: SR-R waits on it.
- AU-Q12 option 1 rewrites the checked writer: PB-10 waits on it.
- AU-Q1 option 1 deletes the row machinery and core.cache memo in `call_preparation.clj` (UL-10); SR-J's slice for
  that LRU therefore waits on AU-Q1.
- The LRU counts of dials.md (D4-D7: `db.clj`, `call_preparation.clj`, `sci/eval.clj`, `test.clj`) move into
  `seon.cache` through SR-J's slices, never into new count dials.
- S5-02 deletes stored profile measurements: RO-10 now keeps output samples in the live profile cells, and DIAL-10c
  (`measurement.clj`'s `min-calls`) is dropped if S5-02 deletes its consumer. S4-07b deletes prompt selection: DIAL-16
  is dropped if `cluster/prompt.clj` goes.
- PR-18 already returns `my.program` search and sizes as whole ranked data: dials.md D27 (search limit 8, largest 20)
  goes there, not to a dial.
- RED-3 was already green (`ad831095f`); retired, not scheduled.

## Card index

Rows from the audits and their state (the ledger row is the authority for dependencies). "card next" means the row is
fully specified in the ledger and its card is this lane's next batch.

| Row | Card | State |
|---|---|---|
| UL-3a | [UL-3a](UL-3a.md) | ready |
| UL-3b | — | waits on root's split of eight test conversions |
| UL-4 | [UL-4](UL-4.md) | ready |
| UL-5 | [UL-5](UL-5.md) (UL-5a; UL-5b noted) | ready after PB-07a |
| UL-6 | [UL-6](UL-6.md) | ready after led-cacheb releases `cluster/agent.clj` |
| UL-7 | [UL-7](UL-7.md) | ready |
| UL-8 | card next | waits on led-nk1, led-cacheb |
| UL-9 | — | waits on task `seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check` |
| UL-10 | — | ready once carded (AU-Q1 answered) |
| UL-10b, UL-12 … UL-25, DEPS-1 | — | rows from the answers; each waits on its fork row or file release (ledger) |
| FK-MALLI, FK-KONDO, FK-DH, FK-ASYNC, FK-SCI | — | fork rows, one fork commit and one bump each; root batches the restarts |
| UL-11 | [UL-11](UL-11.md) | ready (diagnosis) |
| SR-P | [SR-P](SR-P.md) | ready after blob-konserve releases `blob.clj` |
| SR-L | [SR-L](SR-L.md) | ready after led-cacheb releases `registry.clj` |
| SR-G2 | — | after SR-L |
| SR-T | [SR-T](SR-T.md) | retired: superseded by SP-1a, SP-3, SP-4 (cards-storage-point) |
| SR-S | [SR-S](SR-S.md) | ready |
| SR-ENUMa | card next | after SR-L, SR-S |
| SR-ENUMb | — | after SR-ENUMa |
| SR-PERMIT | [SR-PERMIT](SR-PERMIT.md) (Opus) | after SR-P, led-cacheb, blob-konserve |
| SR-EFFECT | — | after EN-1 |
| SR-STATUS | [SR-STATUS](SR-STATUS.md) | ready |
| SR-STREAM | — | after BLOB-K |
| SR-R0 | [SR-R0](SR-R0.md) | ready (diagnosis) |
| SR-R | — | after SR-R0 |
| SR-AUDIT | — | after SR-G2 |
| SR-HITS | — | after LR-CACHEb |
| SR-BRANCH | — | after MA-15, F5, F7, MA-14b |
| PB-07a | [PB-07a](PB-07a.md) | ready |
| PB-07b | [PB-07b](PB-07b.md) (b1, b2) | after PB-07a, led-ul2 (b1), led-cacheb (b2) |
| PB-08 | — | waits on led-cacheb (Opus) |
| PB-09 | — | after S4-01 |
| PB-10 | — | ready after led-cacheb (AU-Q12 answered) |
| PB-11 | — | after PB-03 (Opus) |
| PB-12 | [PB-12](PB-12.md) (PB-12a fork, PB-12b Seon) | ready |
| PB-13 | — | after PB-07b |
| PB-14 | [PB-14](PB-14.md) | ready (diagnosis, Astra) |
| PB-16 | [PB-16](PB-16.md) | in flight (stopfix-projection) |
| PB-17 | [PB-17](PB-17.md) | ready (diagnosis) |
| PB-15 | [PB-15](PB-15.md) | ready after led-nk9, led-nk1 |
| DH-4a | [DH-4a](DH-4a.md) | after dials-16gb (`store.clj`), LR-CACHEb (gitlink) |
| DH-4b | — | after DH-4a (Opus design check) |
| DH-5, DH-6, DH-7 | — | after LR-CACHEb (Datahike gitlink) |
| DH-8 | — | after BLOB-K (konserve compressor) |
| CN-24 | — | after PR-07, RO-4 |
| VEC-10 | [VEC-10](VEC-10.md) | ready (diagnosis) |
| DIAL-3 | [DIAL-3](DIAL-3.md) | ready |
| DIAL-5 | [DIAL-5](DIAL-5.md) | ready |
| DIAL-8 (absorbs DIAL-13) | [DIAL-8](DIAL-8.md) | after dials-16gb, led-nk1 |
| DIAL-17 | [DIAL-17](DIAL-17.md) | after dials-16gb (AU-Q24 answered: 10 GiB) |
| AT-8 | [AT-8](AT-8.md) | after SR-H, SR-J (AU-Q6 answered) |
| DIAL-2, DIAL-4, DIAL-6, DIAL-7, DIAL-9 … DIAL-12, DIAL-14 … DIAL-16, DIAL-18 | — | each row's Deps; carded when their files free |
