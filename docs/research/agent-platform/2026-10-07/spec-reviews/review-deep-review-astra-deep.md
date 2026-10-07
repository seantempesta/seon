# Review of 77c9f5459 — review-astra-deep

**Verdict: changes requested.** A verified Babashka regression changes identities. JVM depth safety covers the four intercepted collection methods, not every accepted value. The original plain deep-vector fix works in the candidate probe.

All source line numbers below refer to **77c9f5459646e775973dcb193bb8ef647c8d0d15**, not the concurrently changing working tree. Reviewed the complete commit, all of `src/seon/id.clj`, both touched tests, lane notes, direct caller sites, and upstream print implementations. Exact probe forms and returned envelopes are in [review-deep-review-astra-deep-evidence.edn](review-deep-review-astra-deep-evidence.edn); the initial standalone form is [review-deep-review-astra-deep-probe.clj](review-deep-review-astra-deep-probe.clj).

## Findings, ranked

### P1 — Babashka's fallback changes canonical text and stored identities

**Location:** `src/seon/id.clj:97`, reached through `:type` dispatch at :83–84 and the fallback at :91.

On **babashka 1.12.212**, these are actual calls to the committed `seon.id/printed` and `seon.id/id`, compared with the parent's fixed-binding `pr` behavior:

| Input | Parent text / 12-digit SHA-256 | Candidate text / identity |
|---|---|---|
| `(tagged-literal 'foo {:a/b [1 2]})` | `#foo #:a{:b [1 2]}` / `c960dcd525e0` | `#foo {:a/b [1 2]}` / `033e76cb8a19` |
| `(with-meta {:x/y 1} {:type :review-deep/unknown})` | `#:x{:y 1}` / `d950aaa9eb9e` | `{:x/y 1}` / `4121a1b2f83d` |

The direct call to host `print-method` skips the SCI-to-host print-binding bridge. The inspected SCI implementation explicitly transfers `*print-namespace-maps*` and the other bindings in `reference-code/sci/src/sci/impl/io.cljc:94–103`; its `pr-on` then calls `print-method` at :76–82. This source explains the observation; the exact SCI revision embedded in the installed bb binary was **not verified**.

**Smallest verified fix:** change the fallback to `(pr x)`, using the already bound writer. A local prototype with just that substitution matched all 56 shallow Babashka cases, including both failures. This retains the explicit stack for intercepted collections. It does **not** fix the JVM recursive fallback described next.

The namespace-load claim is true, but byte compatibility is false. Whether any existing operator-stored identity actually used these inputs is **unverified**; the supplied inputs demonstrably get different identities.

### P1 — The fallback re-enters recursive printing, including from a deep map key

**Location:** `src/seon/id.clj:83–97`. The unrestricted input contracts are at :63, :118, and :160–161.

With:

```clojure
(let [deep (reduce (fn [v _] [v]) [] (range 10000))]
  ...)
```

the exact candidate printer body, evaluated as a local function on the running **Clojure 1.12.5 JVM**, produced:

| Value supplied to the candidate | Result |
|---|---|
| `deep` | success, 20,002 characters, 5.94 ms first probe |
| `(array-map deep 1)` | success, 20,006 characters, 5.11 ms |
| 10,000 nested lists | success, 5.48 ms |
| 10,000 nested singleton sorted sets, constant comparator | success, 3.91 ms |
| `(with-meta deep {:type :review-deep/unknown})` | **StackOverflowError**, 4.06 ms |
| `(tagged-literal 'foo deep)` | **StackOverflowError**, 4.03 ms |
| `(array-map (tagged-literal 'foo deep) 1)` | **StackOverflowError**, 5.51 ms |
| `(malli.core/->Tag :key deep)` | **StackOverflowError**, 5.33 ms |
| `(java.util.ArrayList. [deep])` | **StackOverflowError**, 3.88 ms |
| `(doto (java.util.LinkedHashMap.) (.put :a deep))` | **StackOverflowError**, 3.63 ms |

These values construct successfully; the caught error is from printing, not hashing a deep collection while constructing a map/set. The sorted-set comparator is only a disposable way to construct nested sets without recursive collection hashing.

The dependency paths are explicit in `reference-code/clojure/src/clj/clojure/core_print.clj`:

- :87–90: unknown keyword `:type` strips that metadata and calls recursive `print-method`.
- :292–316: Java collections print children through `pr-on`.
- :320–324: records print fields through `pr-on`.
- :548–552: tagged literals print their form through `print-method`.
- :461–462 and :555–558 expose further recursive paths for dereferenceables and reader conditionals; those deep cases were **not independently executed**.

**Fix:** handle the built-in default-metadata redirect by feeding the stripped value back to the loop. Give the built-in record, tagged-literal, reader-conditional, and Java collection methods frames with their exact existing syntax. Records and Java maps must retain their non-lifted outer map syntax. Keep custom methods delegated, and state that arbitrary custom printer code and lazy realization cannot be made depth-safe merely by moving collection traversal onto a stack. Preserve a local failure boundary for those effects; never silently hash clipped text.

This is an **incomplete fix of the pre-existing depth failure**, not a newly introduced overflow. I verified the direct printer failures, **not** a lost settlement for each wrapper: upstream admission can transform these values. The claim at :53–54 that depth never costs JVM stack is nevertheless false for the function's admitted domain.

### P2 — “Current interface method” is not proof that a method is standard

**Location:** `src/seon/id.clj:67–70,83–90`.

The four comparison values are fetched from the current multimethod table. If a standard interface's method is customized, its replacement is captured as “vector-method” etc. and then bypassed.

Concrete isolated reproduction: copy the current `print-method` methods and preferences into a **local MultiFn**, replace its `IPersistentVector` method with a writer of `#review/vector []`, and lexically hand that table to the unchanged candidate body. For input `[1]`, the table's method prints `#review/vector []`; the candidate prints `[1]`. The shared global table was untouched. Exact form and output are saved.

**Scope:** conditional extension regression, not evidence of an installed interface override. The repository's `seon.env` override is for its concrete Environment class (`src/seon/env.clj:51`), and that shallow case matched.

**Fix:** only intercept a method whose identity is established to be the actual stock implementation, with a defined installation/reload policy; delegate a replacement. Capturing the same mutable table entry immediately before comparing it is not such a guard. If interface replacement is deliberately unsupported, narrow the claim rather than claiming unrestricted `pr` equivalence.

### P2 — Regressions do not cover the compatibility or depth class claimed

**Location:** `test/seon/id_test.clj:44–56`; `test/seon/value_zoo_test.clj:32,77–80`.

The literal table and full deep-vector expected text are useful, and the settlement assertion rejects the original refused/no-value outcome through both entrants. However:

- No Babashka parity check catches the confirmed identity change.
- Deep map values are checked only for a 12-character identity; deep keys, metadata redirects, records and tagged literals are absent.
- The literal table omits most delegated scalar kinds, custom dispatch and nonempty set ordering.
- The changed zoo adds only a plain vector; it does not prove all recursive wrappers.

**Fix:** extend the same compatibility table and depth behavior test, rather than adding one test per type. Compare fixed-binding `pr` on finite shallow representatives; retain literal historical digests; compare streamed identities with SHA-256 of expected UTF-8 text. Add the failing wrappers and deep-key cases. Run the compatibility table under bb as well as JVM. Keep the existing end-to-end settlement regression.

### P3 — Memory and bound claims need correction

**Location:** `src/seon/id.clj:53–54,72–80,92–106,153–154`; lane notes “memory proportional to depth” and “bounded by heap only.”

The explicit stack is O(depth), and lifted maps additionally allocate/retain stripped key/value vectors. Auxiliary space is O(depth + entries retained by open lifted-map frames), potentially O(input size), even for streaming `id`. `printed` additionally retains the output string. Therefore “memory is constant in the value's size” is false.

There is **no execution, node, or output bound in this printer**: :65 clears length/level and :101 keeps advancing sequences. `(seon.id/id (range))` has no terminal result; a cyclic Java collection or arbitrary blocking print method is also outside the guarantee. Those unsafe forms were not executed on default. These are pre-existing limitations, not newly introduced hangs.

Correct the documentation and establish the finite-input/admission responsibility at callers. Full byte-identical canonical output for infinite inputs is impossible; clipping would corrupt identity semantics. Also, the unchanged `storage-data` caller uses recursive `walk/postwalk` before printing (`src/seon/schema/datahike.clj:533–558`), so the printer alone cannot establish depth safety for that entire storage path.

## Compatibility coverage and caller audit

The JVM probe had **58 shallow cases and zero text differences**. This is representative evidence plus source analysis, not a universal proof or a rerun of the commit's claimed 9,048-value corpus.

| Value family | Evidence and controlling code |
|---|---|
| Hash/array/sorted maps, uniform/mixed namespaces, qualified symbol keys | JVM parity; entry order retained, not sorted by the new printer. `id.clj:72–90` reproduces `core_print.clj:228–269`. |
| Sets and sorted sets | JVM parity; same `seq` order, :87 and :99–103 versus upstream :338–340. No claim of order-independent hashes. |
| Vectors, map entries, lists, finite lazy seqs, ranges, empty collections | JVM parity; deep vectors/lists/sets and vector map keys succeed. `id.clj:85–87,95–106`. |
| Metadata; odd symbols/keywords | JVM parity, including space/slash-containing names and unknown keyword `:type`. Metadata suppression follows :65; dispatch matches `core.clj:3693–3696`. Deep redirect and bb failures above. |
| Characters, escaped strings, Unicode, nil, booleans | JVM parity, including control characters and a lone surrogate character. Delegated upstream methods; no new escaping implementation. |
| Integers, bigint/BigInteger, ratio, BigDecimal, floats, NaN, infinities | JVM parity; delegated methods preserve suffixes and special tokens. |
| Regex, instant, UUID | JVM parity; delegated unchanged methods. |
| Records, tagged literals, Java objects/collections | Shallow JVM parity, including Malli Tag and Seon Environment. Deep fallback fails; bb tagged literal changes bytes. Arbitrary effectful `toString`/custom methods are not universally verified. |

Direct production caller inventory at the reviewed commit:

- **Canonical text:** `schema/datahike.clj:523,524,529,558` (storage ordering, identifier readability, storage string); `sci/admit.clj:89` (arbitrary canonical data); `instrument.clj:714` (scalar error keys); `mcp.clj:328` (cluster name); `render/schema.clj:136` (identity lookup); `turn.clj:1735` (requery form).
- **Content IDs:** `task.clj:50,397,582`; `fn/analyzer.clj:340`; `fn.clj:1647`; `schema.clj:3269,3500`; `error.clj:171,540,1249,1282,1608`; `test/runner.clj:1272,1462`; `render/test.clj:127`. Inputs include identity tuples, symbols/keywords/strings/numbers, sorted maps, selectors, admitted arbitrary error values, and failure maps.
- **Fresh UUID/event IDs:** `bootstrap.clj:330`; `cluster/agent.clj:754,1089`; `cluster/message.clj:175,226,781,915`; `cluster/source.clj:919`; `db.clj:5025,5576,5960`; `error.clj:569,1657`; `mcp.clj:194,264`; `render.clj:1780`; `schema/datahike.clj:705`; `test.clj:191,1868,2368`; `test/runner.clj:1238`. These reach the same printer through :162.
- **Internal caller:** `id/digest` makes a vector then calls `id` (:167–172); its callers also hash schema forms, program/source digests, execution tuples, and schedules. Streaming and text both use `print-canonical!` (:121,:141).
- Searched `src`, `script`, `bin`, and tests, including `.cljc` and the publication fixture. No direct script/bin call sites. Test consumers mostly mint fresh names; nontrivial inputs include admitted error data, HTML text vectors, source forms, assertion tuples and writer identities.

## Small-print cost and simpler alternatives

The commit reports **1.13 → 1.74 μs (+54%)**. If reproduced with the same input and harness, that exceeds the repository's 20% regression criterion. The notes do not supply a reproducible benchmark form for that exact row, and I did **not** reproduce that magnitude.

Local, unarmed JVM functions: fixed-binding parent `pr`, exact candidate, and a prototype that invokes the already-resolved leaf method once instead of dispatching again at :97. All include a fresh StringWriter. For `[1 2 3]`, seven warmed batches of 30,000 prints gave medians:

| Parent | Candidate | One-dispatch prototype |
|---:|---:|---:|
| 1.389 μs | 1.432 μs | 1.312 μs |

There were noisy outliers (candidate 2.584 μs, prototype 2.457 μs). These measurements neither validate nor refute the original exact workload. The prototype is only a JVM optimization experiment: it retains the **wrong bb binding behavior**, so it is not a portable proposed fix.

Keep one explicit traversal. Resolve and classify once where the runtime permits it; preserve the `pr` bridge where required. Avoid a preliminary depth walk, stack-overflow-and-retry, or wholesale pre-rendering, all of which repeat work or effects. Fix compatibility first; compare the exact original small input and real schema-form workload under root's controlled run before claiming a performance win.

The largest benchmark evaluation reported **1,074 ms**, proportional to 720,000 deliberately repeated prints including warmup; individual 30,000-print batches were below 78 ms. This was the measurement workload, not an ordinary single-print latency. No added cache or memory profile was run.

## Installation, foreign boundary, and central verification

- `bin/seon status` reached pid **30874**, source archive **a8e3bde5b475e54fead8ed107a86d67d47d10a13**. MCP reported `:faulted`, 14 open faults, 105 failed tests, and 18 regressions; baseline live heap was about 9.5 GB of 11.0 GB. These are not attributed to this commit.
- The live `seon.id/printed` metadata still points to line **59**, and a direct live 10,000-deep call returned caught **StackOverflowError**. The candidate is **not proven installed** there. Candidate JVM evidence used the exact committed function body as an anonymous local function; no shared Var, multimethod, namespace, or JVM was reloaded/replaced.
- Babashka smoke and parity probes loaded the actual committed, clean `src/seon/id.clj`. Load and plain deep-vector hashing succeed (`435880ce4312`); parity fails as above. A first broader fixture attempt used unavailable bb `java.util.LinkedHashMap`; the completed bb fixture uses HashMap. That is a fixture/class-surface limitation, not an additional product finding.
- Foreign dirty files include `src/seon/schema/datahike.clj`, `src/seon/error/refusal.clj`, `src/seon/sci/eval.clj`, `src/seon/test/runner.clj`, and `src/seon/turn.clj`. Review citations use Git content; none was edited or used to claim candidate installation. The lane-note reader/ex-data failures remain outside this review.
- **No test run, restart, worktree, or commit was made.** Publication/adoption/arming, actual settlement of the installed candidate, the 9,048-value corpus, archive-wide lint and contracts compile are **unverified** here. No production/source/test changes were made. Review artifacts only; commit under review is +50 source lines and +18 test lines net.

Root's focused list, before acceptance:

1. `seon.id-test/data-shape-and-explicit-length-determine-identity`
2. `seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol`
3. `seon.id-test/printed-identity-ignores-the-callers-print-bindings`
4. `seon.id-test/canonical-print-is-prs-text-at-any-depth` — extend with the failing cases above.
5. `seon.value-zoo-test/every-zoo-value-comes-back-through-both-entrants-and-the-page` — include its declared long-test policy.
6. `seon.error-result-test/large-result-keeps-complete-and-profile-capped-printer-text` — an actual admitted-value digest consumer.
7. `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`.

Also run the saved bb parity/identity forms, and `clj-kondo --lint src script test --cache false` from root's Git archive of HEAD plus the accepted fix. These are requested checks, **not passes**.

