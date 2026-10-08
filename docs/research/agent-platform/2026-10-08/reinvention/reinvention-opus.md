---
type: research
status: proposed (lane reinvention-opus, 2026-10-08)
tags: [dependencies, reinvention]
---
# Reinvention census: where Seon re-implements a dependency

Lane `reinvention-opus`, 2026-10-08, HEAD `f1df3b6e3`. This is research only: no source was changed, and no
prototype code was loaded into default. The live probes evaluated existing Vars and library functions over
default's data in JVM mode. Each one's form and result is quoted where its claim depends on it.

This answers the owner ruling of 2026-10-08: "stop reinventing what already exists; bring real gaps to me case by case".

**Pinned revisions read:**

- malli `43c6dca8`, sci `ce0aa53b`, core.async `dc35f3e` (1.10.874-alpha3), clj-reload `61c6fa7` (1.0.0), clojure
  `3bc2b3e9` (1.12.5), fipp `255a190` (0.6.29), clj-kondo `57252e07`, edamame `196ea5e`, konserve `8700ca7`.
- Jars from `~/.m2`: caffeine 3.2.4, ring-codec 1.3.0, hasch 0.4.100, tools.namespace 1.5.0.

**What is on the classpath matters.**

- The base `clojure -Spath` has 105 jars. It includes ring-codec, hasch, core.cache, core.memoize and
  data.priority-map.
- It does **not** include tools.namespace, spell-spec, puget, hiccup or clj-reload.
- The first four reach default's JVM only through the `:test` alias (tools.build and flow-monitor).
- clj-reload is vendored but on no classpath. I checked default's JVM with `io/resource`:
  `clj_reload/core.clj` is false, and `clojure/tools/namespace/dependency.cljc` is true.

## Ranked table

The ranking is lines deleted × (1 / risk). A REPLACE row can be launched as a slice. An OWNER DECISION row needs a
ruling first.

| # | Ours (`file:line`, lines) | Library seam (`file:line`) | Gap, verified | Verdict |
|---|---|---|---|---|
| 1 | Reader declaration facts: `seon/sci/reader.cljc:195-395` (~200) | clj-kondo `:analysis {:var-definitions {:meta true}}`, already configured at `seon/fn/analyzer.clj:40`, and already run per batch at `seon/turn/batch.clj:171` | None in kind. Already filed as `seon.sci.reader/reader-head-check-is-a-second-declaration-derivation` | REPLACE |
| 2 | Instrumentation restore state: `seon/instrument.clj:1177-1294` (`remove!`, `state`, `replaced-definitions`, `restore!`; ~118) | `malli.instrument/unstrument!` (`malli/instrument.clj:158`) | Production calls none of these. Only `test/seon/instrument_test.clj` does. CLAUDE.md also forbids restoring reloaded roots | REPLACE (delete) |
| 3 | Edn symbol token predicate: `seon/program.cljc:72-115` (~44) | `clojure.edn/read-string` round trip | None material. See item 3 for the measurement | REPLACE |
| 4 | Problem expectation prose: `seon/error.clj:769-829` (`value-description`, `bound-text`, `schema-expectation`; ~60) | `malli.error/error-message` and its `:errors` table (`malli/error.cljc:10-172`, `:288`) | Phrasing only: Malli says "should be …", Seon says "expected …, got …". An `:errors` option table expresses that | REPLACE |
| 5 | Wake buffer `CountedSlidingBuffer`: `seon/cluster/agent.clj:107-134` (~28) | `async/sliding-buffer` (`core/async.clj:103`) | Only a dropped count, and no source reads it (`rg ':dropped'` finds nothing). It shows only in Flow `ping` datafy | REPLACE |
| 6 | Query-string parsing `form-pairs`: `seon/render/web.clj:157-166` (~10) | `ring.util.codec/form-decode-map` (ring-codec 1.3.0, `codec.clj:169`), on the base classpath | A repeated key becomes a vector instead of last-wins. See item 6 for the probe | REPLACE |
| 7 | Process LRUs over core.cache, and `memoize` held in Vars: `db.clj:1451`, `:1457`, `sci/eval.clj:3096`, `:3339`, `call_preparation.clj:402`, `test.clj:416`, `schema.clj:444`, `:2067`, `fn/analyzer.clj:258` | `seon.cache` (Caffeine). This is a second cache owner, not a reinvention | The owner already ruled one central cache. A `memoize` local to one call is fine | REPLACE (already ruled) |
| 8 | REPL reply map built by `str`: `seon/repl.clj:275-296`, read back at `:81-98` | The value printer. prepl's `{:tag :ret :val :ns :ms :form}` (`core/server.clj:194-212`) as the key set | This is a second printer, against the existing ruling | REPLACE (already ruled) |
| 9 | Two topological sorts: `seon/cluster.clj:1858-1881` (24) and `seon/sci/eval.clj:2387-2424` (38) | clj-reload `topo-sort` (`clj_reload/parse.clj:163`), on no classpath. tools.namespace `dependency/topo-sort` is test classpath only | Library cost and availability, measured (item 9). The two of ours duplicate each other | OWNER DECISION |
| 10 | `seon.call-preparation` (1,130) | Malli `default-value-transformer` with `:default/fn` (`malli/transform.cljc:484-520`) | Real gap for positional arguments, partial for map entries (item 10) | OWNER DECISION |
| 11 | Contract arming and collection: `seon/instrument.clj:823-1175` (~350). The core is already `m/-instrument` (`:916`) | `malli.instrument/-strument!` (`malli/instrument.clj:17-40`) | Real gaps (item 11) | OWNER DECISION |
| 12 | Layout prediction: `seon/render/value.clj:899-1000` (~100) | fipp engine fits test (`fipp/engine.cljc:203-208`) | Real gap: fipp has no incremental layout-length API | OWNER DECISION |
| 13 | Structural hash `seon.id`: `seon/id.clj:22-215` (~190), including a stack-safe `pr` | hasch `uuid` (on the base classpath; Datahike uses it) | Real gap, measured (item 13) | OWNER DECISION |
| 14 | Spelling distance: `seon/error.clj:1828-1839` (12) | Malli `-levenshtein` (`malli/error.cljc:256`) is private. spell-spec is test classpath only | No public seam on the base classpath | OWNER DECISION |
| 15 | Cache per-kind counters and the one-derivation claim: `seon/cache.clj:40-51`, `:121-158` (~45) | Caffeine `recordStats` and `AsyncCache.get(K, Function)` | Partly real (item 15) | OWNER DECISION |
| 16 | Hiccup serializer: `seon/render/hiccup.clj` (525) | hiccup 2 `hiccup2.core/html` | It would be a new dependency. Gaps: sorted attribute order, camelCase style keys | OWNER DECISION |
| 17 | Flow impl reach-ins: `seon/flow.clj:11-15`, fault port `:304-322` | Flow's public API (`flow.clj:76-302`) | Real gap: Flow's graph error channel is sliding and drops faults (`flow/impl.clj:102`) | OWNER DECISION |
| 18 | `seon.profile` (843) | JFR (JDK); HdrHistogram is not on the classpath | Real gap: JFR samples, while Seon needs exact per-Var call counts and self time per world | KEEP (owner may confirm) |
| 19 | Cause chain: `seon/error/refusal.clj:392-439` | `Throwable->map` (`core_print.clj:473`). `ex-triage` is already used at `sci/kernel.clj:733` | Real gap: `Throwable->map` has no cycle guard and no suppressed throwables | KEEP |
| 20 | Test report adapter: `seon/test/runner.clj:177-385` | `clojure.test/report` rebinding. It already is that seam | Not a reinvention. kaocha is not on the classpath | KEEP |
| 21 | SCI context build and rebase: `sci/eval.clj:375`, `:2960`, `:3428` | `sci/init` and `sci/fork`, already used | Real gap: `sci/fork` cannot rebase a fork onto a new base while keeping private Vars | KEEP (owner may confirm) |
| 22 | Delimiter repair: `seon/sci/forgive.clj` (462) | No dependency on any classpath (no parinfer) | Nothing to reuse | KEEP |

**Lines deleted:**

- The REPLACE rows 1–6 delete about 460 lines and add about 40.
- Rows 7–8 are rulings that already exist.
- The OWNER DECISION rows hold about 2,400 lines. Most of them are not duplicates of what the library does.

## Items

**1. Reader declaration facts vs clj-kondo.**

- What ours does: `function-declaration`, `test-declaration`, `schema-declaration` and `namespace-info` re-derive
  from each form what clj-kondo already reports. They cover name, arglists, doc, private, `:malli/schema` meta,
  aliases and refers.
- clj-kondo already runs per batch (`turn/batch.clj:171`) and at indexing (`fn/analyzer.clj:40`, `:meta true`).
- CLAUDE.md names clj-kondo's analysis, joined by exact source span, as the owner of these facts. The task is already
  filed.
- Slice: read the declaration from the batch's kondo analysis by span, then delete those reader functions.
- Risk is medium. The reader is on every evaluation, so time one probe batch on the parent and on the change.

**2. Instrumentation restore state.**

- `state` snapshots Malli's private `-function-schemas*` atom (`instrument.clj:1187-1190`, reached with `@#'`).
  `restore!` puts back captured roots.
- No production caller exists. `rg` finds only `instrument_test.clj:1486-1493` and `remove!` at `:670`/`:1331`.
- The CLAUDE.md test rules ("never restoring reloaded roots") already retire this.
- Slice: delete it and the tests that only test it. Risk is low.

**3. Edn symbol token predicate.**

- What ours does: `edn-symbol-text?` re-implements EdnReader's token and symbol pattern by hand.
- Probe: over all 14,724 interned Var names, `(try (let [v (edn/read-string s)] (and (symbol? v) (= s (str v)))) (catch Exception _ false))`.
  - It agrees on every name, and on its documented edge cases (`a//`, `a/b/`, `/x`, `a/1`, `+1`, `nil`, `a:`,
    `a::b`, `#a`).
  - Cost: 0.32 µs per name against 0.21 µs for ours. The comment says it runs per edge name, and 0.1 µs × edges is
    negligible.
- Slice: replace the body and keep the predicate's name and contract. Risk is low.

**4. Problem expectation prose.**

- What ours does: `schema-expectation` and `bound-text` hand-write a per-type noun table, with bounds ("a string of
  length at least 1").
- Malli ships the same table:
  - `default-errors` gives `:string` min/max, `:int` and `:double` bounds via `-pred-min-max-error-fn` (`error.cljc:10`),
    `:enum` "either … or …", and every core predicate.
  - It also takes a user `:errors` map (`error-message`, `:288`).
- Slice: one `:errors` table phrased in Seon's "expected" voice, passed to `me/error-message`. Then delete
  `bound-text` and the `case` in `schema-expectation`.
  - Keep its `:and`/`:or` composition (Malli answers unknown there).
  - Keep `value-description` for the "got …" half; Malli has no counterpart.
- Risk is low to medium: shown text changes, and tests assert data.

**5. Wake buffer.**

- What ours does: `CountedSlidingBuffer` is `sliding-buffer 1` plus a `swap!` counter of drops.
- Readers: `rg` finds no reader of `:dropped` in `src` or `test`. It surfaces only through `datafy`, which Flow's
  `ping` walks (`flow/impl.clj:89-90`).
- Slice: `(async/chan (async/sliding-buffer 1))` at `agent.clj:134` and `:596`, then delete the deftype. This also
  removes an atom.
- Risk is low, unless the owner wants overwritten wakes counted. In that case they belong in status, not a
  hand-made buffer.

**6. Query-string parsing.**

- What ours does: `form-pairs` hand-splits on `&` and `=` with `URLDecoder`.
- Probe against `ring.util.codec/form-decode`, on the base classpath through reitit-ring/ring-core:
  - `form-decode` agrees on `"a=1&b=%20x"`, `"k=v%3D%3D&&z="` and `"x=a+b"`.
  - It returns a string, not a map, when the input has no `=`.
  - It returns `{"a" ["1" "2"]}` for a repeated key.
- `form-decode-map` (`codec.clj:169`) always returns a map, which fixes the no-`=` case.
- Slice: call `form-decode-map` and delete `form-pairs`. A repeated key becomes a vector, which is arguably correct.
  Risk is low.

**7. Process LRUs.**

- core.cache is a library, so this is not a reinvention. These are the second and third cache owners that CLAUDE.md
  forbids.
- Each site becomes a `seon.cache` kind keyed by content.
- A `memoize` closed inside one call (`fn.clj:3485`, `:3660`, `db.clj:4231`, `:5295`, `test/runner.clj:1735`,
  `test.clj:1389`) is a per-operation fold and stays.
- `fn/analyzer.clj:258` holds `(memoize manifest-roots)` in a Var. Convert it.

**8. Reply map.**

- What ours does: `reply-map` concatenates `"#:seon.repl{"`, `":" (name k) " " text` and `"}"`, then `read-shown`
  parses printed text back into values.
- That is the second-printer pattern, which is already ruled out.
- Slice (Opus, one-path semantics): build the reply as a map whose keys are prepl's (`:val :ns :ms :form`) plus
  Seon's (`:result`, `:findings`), and print it once through `seon.print`.

**9. Topological sorts.**

- Probe on default's data: 520 namespaces and 4,711 `:seon.ns/requires` edges.
  - Ours, `seon.cluster/reload-order`: 354 ms cold, 70 ms warm.
  - `clojure.tools.namespace.dependency/topo-sort`: 198 ms. It also drops 22 namespaces that have no edges.
  - Sorting by `topo-comparator` with ties broken by name: 641 ms.
- clj-reload's `topo-sort` is the same layered Kahn sort with sorted ties as ours. It is on no classpath.
- Ours is itself duplicated: `seon.sci.eval/namespace-order` is a second Kahn sort.
- The gap: there is no faster library on the base classpath, and the closest library is the same algorithm.
- Options:
  - (a) **Recommended.** Keep one sort and delete `namespace-order`, about −38 lines.
  - (b) Add clj-reload to `deps.edn` and call its `topo-sort` from both. That deletes about 60 lines and adds a
    dependency.
  - (c) Promote tools.namespace to `:deps`, at a 3× cost.

**10. Call preparation vs `default-value-transformer`.**

- What Malli covers:
  - `add-defaults` fills **missing map keys** whose entry or ref schema declares `:default` or `:default/fn`.
  - `:default/fn` is `m/eval`'d to a function and called at each decode (`transform.cljc:488`, `:504`). A world member
    read at call time could therefore be a `:default/fn` on the registered schema.
- The gap: Malli never inserts a missing **positional argument**.
  - `:cat` decoding walks only the values present.
  - Seon's core feature is a shorter call resolved to a unique placement under the declared slot schemas
    (`resolve-count`, `call_preparation.clj:522`, and `partial-insertions`, `:952`).
  - Also, `defaults` keyed by type is computed at compile time (`#(constantly (% schema))`, `:493`), not per call.
- Side finding: default's live store holds 4 supplied-default rows (`:seon.db/db`, `:seon.db/connection`,
  `:seon.agent/id`, `:my.program/context`).
  - The first three are shadowed by `seon.batch/world-members` (`supply-index`, `call_preparation.clj:613`).
  - So only `:my.program/context` needs the row machinery. That machinery is `snapshot`, `derive-snapshot` and the
    core.cache memo (`:244-510`, ~270 lines).
- Options:
  - (a) Make `:my.program/context` a world member and delete the row machinery (~270 lines). Keep positional
    planning. Map-entry filling stays ours too, because it shares the plan.
  - (b) Option (a), plus move map-entry filling onto `default-value-transformer` with `:default/fn` on the world
    schemas.
  - (c) Keep everything as it is.

**11. Arming vs `mi/-strument!`.**

- seon.instrument already uses `m/-instrument` as the wrapper (`instrument.clj:916`). `-strument!` cannot replace the
  arming around it, for four verified reasons:
  - It calls the 2-arity `(m/-instrument dgen f)` (`malli/instrument.clj:36`), so no `:registry` option reaches the
    compile. Passing a pre-compiled schema works around that.
  - One compile failure throws out of its `doall` and stops the remaining Vars. Seon arms the rest and returns a
    registration error.
  - It re-wraps every time, with no unchanged-wrapper check (`current-wrapper?`).
  - It has no profile bracket.
- The refusal builders, about 480 lines (`:171-780`), are Seon's error contract. Malli's report carries only
  `{:args :input}` and needs a re-explain.
- The real, smaller duplicate is item 4.
- Options:
  - (a) Keep arming; take items 2 and 4.
  - (b) Upstream a 3-arity `-strument!` and continue-on-failure to the Malli fork, then call it (about −80 lines).
  - (c) Rewrite onto `mi/instrument!` with Malli's global function registry. This conflicts with per-projection
    contracts and is not recommended.

**12. Layout prediction.**

- `render.value` re-derives fipp's fits test incrementally (`flat-width`, `extras`, `breaks?`, `settle`). It does this
  so each breadth-first admission is charged its laid-out length.
- fipp's engine is a one-pass transducer (`annotate-rights` → `annotate-begins` → `format-nodes`). It answers "what is
  the length of this whole document" only by running it.
- Re-running fipp per admission is O(n) each, so O(n²) overall. That is why the prediction exists.
- Options:
  - (a) Keep it.
  - (b) Admit level by level on flat width, run fipp once per level, and cut the level that overflows. That is
    O(levels × n) and deletes about 100 lines; it needs a measurement on the printer's golden values.
  - (c) Add an incremental fits API to a fipp fork.

**13. Structural hash.**

- Probe on 2,000 small maps:
  - `seon.id/id` takes 2.53 ms; `hasch.core/uuid` takes 5.69 ms.
  - On a 10,000-deep vector, `seon.id` succeeds and hasch throws `StackOverflowError`. This is the exact failure
    `seon.turn/a-deep-value-loses-the-run` guards.
- Both are order-independent over maps and sets.
- `id/printed` (`id.clj:22-95`) is a stack-safe re-implementation of `pr`. It is used to sort and compare
  (`schema/datahike.clj:520`), which CLAUDE.md forbids, as well as for source text.
- Options:
  - (a) Keep `id`, convert `printed`'s compare and sort callers to data comparison, and leave `printed` only for
    source text.
  - (b) Adopt hasch and accept a depth bound and 2× cost.
  - (c) Keep both as they are.

**14. Spelling distance.**

- What ours does: a 12-line Levenshtein that ranks a namespace's names near a missing symbol.
- The library versions are not usable:
  - Malli's is private and keys only map entries.
  - spell-spec's is private and not on the base classpath.
- Options:
  - (a) Keep the 12 lines.
  - (b) Call `@#'malli.error/-levenshtein`, a private Var that is not a supported API.
  - (c) Delete the suggestion and show the namespace's names unranked.

**15. Cache counters and claim.**

- Caffeine's `StatsCounter` callbacks (`recordHits(int)`, `recordMisses`, `recordLoadSuccess(long)`,
  `recordEviction(int, RemovalCause)`, from `javap`) receive no key. One weighted cache shared by all kinds therefore
  cannot give per-kind hits and misses.
- Separate caches per kind would break the one byte budget. The per-kind `LongAdder`s (~12 lines) are the gap.
- `claim`/`settle!` reproduce `AsyncCache.get(K, Function)`'s one derivation per key. That method would also remove
  failed futures, but it would log a Caffeine warning per failure, which is why `settle!` completes normally.
  Replacing `claim` saves about 8 lines.
- Options:
  - (a) Keep as is.
  - (b) Add `recordStats()` for whole-cache totals beside the per-kind adders.
  - (c) Drop per-kind counters and report Caffeine's totals only.

**16. Hiccup serializer.**

- hiccup 2 is not on the base classpath.
- Its runtime `html` escapes by default and accepts a `:style` map.
- It does not sort attribute keys (Seon relies on that for identical bytes) and does not normalize camelCase style
  keys.
- Options:
  - (a) Keep ours.
  - (b) Add hiccup 2 and pre-normalize attributes (about −400 lines; one new dependency).
  - (c) Use Datastar's own hiccup support, if its SDK carries one. This was not verified.

**17. Flow internals.**

- The fault `WritePort` exists because Flow's graph error channel is a sliding buffer that drops faults. `flow/stop`
  also closes it before an unwinding proc reports (`flow/impl.clj:102`, `:179`).
- Those are real losses that a public seam cannot fix.
- Options:
  - (a) Keep the reach-ins and pin them with a test.
  - (b) Patch the core.async Flow fork to accept an `:error-chan` option, then delete the reify.
  - (c) Accept fault loss. This contradicts "deliver every error".

**18–22. Kept.**

- **18:** JFR has no exact call counts.
- **19:** Seon's chain adds the cycle guard and suppressed throwables that `Throwable->map` lacks. `ex-triage` is
  already used.
- **20:** The runner rebinds `clojure.test/report` as intended. Its extra work is evidence recording.
- **21:** SCI's `fork` is one-way, and CLAUDE.md requires that private state survives program change.
- **22:** No delimiter-repair library exists on any classpath.

## Health observed during the probes

- `runtime_status` reports default `:faulted`, with 6 open faults. The top one is
  `seon.render.ns/task-ai refused its result at [:seon.task/id]` (task `68f9fc8b8da1`, already filed).
- `seon.fn/analyzed-files` is over its bound: 18 calls, mean 1.43 s, max 7.9 s. Its health row carries no task.
  `seon.fn/index!`, its caller, carries three (`resources/seon/tasks/seon.fn.edn`), and those probably cover it.
- My topological-sort probe took 1,014 ms in total, flagged `:slow`. The time went to the `topo-comparator` sort,
  which is the library alternative I measured, not Seon code.
- `reload-order` takes 354 ms cold over all 520 namespaces. Adoption normally passes it only the changed set.
