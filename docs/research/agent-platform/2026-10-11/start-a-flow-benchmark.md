---
title: From a commit to a paused agent Flow
type: research
status: baseline, fork-revision fix and H9 cold-base attribution measured
---

# From a commit to a paused agent Flow

The existing path composes Datahike's branch, Seon's world map, the cached SCI
fork, the held task's current-state data, and the production agent graph.
The benchmark measures these owners separately with `seon.profile/measure`.
There is no model call. The baseline precedes the fork-revision fix below;
the H9 measurements follow the agreed start-a-flow hybrid.

## Measurement protocol

`start-flow-benchmark/sample!` takes an execution handle, a commit id and an agent
already assigned a task in that commit. The fixture creates and assigns a task
through the ordinary owners, with a real red proof and two must-reads:
`seon.turn.world/world` and `seon.test.runner/reach-digests`. Preparation is outside
the measured start path. Each sample creates a real committed fork and retires it.

Run `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` only in the
disposable `bin/seon check` JVM. Its cold sample first empties `seon.cache`;
the warm sample starts a second fresh branch from the same commit. Default's
cache is never emptied. The script's `sample!` alone is also usable for live warm
measurements, with explicit root/default custody supplied by the caller.

Flow's `clojure.core.async.flow/start` starts paused; Seon's `start-graph!` resumes
unconditionally. The measurement therefore uses `seon.cluster.agent/graph-definition`
and the existing `seon.flow/graph-executor`, then calls Flow's create/start directly.
It observes all three procs with ping and stops and joins the owned executor before
releasing the branch. Paused state must be positively observed, not inferred from
the absence of a provider call. Graph timing includes settings, channels and the
verification ping; proc-thread allocations are separately visible in the JVM window.

Reported allocation is allocation, not retained heap. The thread counter excludes
work performed by the writer and graph threads. The JVM window includes those
threads and unrelated work. `:seon.profile/reads` counts armed database read entrances;
the separate datoms count is `seon.db/datoms` calls, not the number of index nodes or
datoms returned. No measurement here claims to count physical index page reads.

## Floor and cache plan

The numeric floors below are hypotheses to test, not measurements or guarantees.
They exclude JVM boot, fixture preparation and class loading.

| Part | Necessary work and size | Proportional to | Hypothesized warm floor | Owner and reuse |
|---|---|---|---|---|
| Branch | Share immutable index roots; roster pointer; one fork entity transaction; open connection | Stored index roots and fork fact, not program size | 1–10 ms; 0.1–1 MB on the caller; one branch operation and transaction | Datahike stores facts and index roots. No store copy. Source revision information must survive cache eviction by read-through derivation. |
| World | Small map of identity, connection, writer, environment, sink | World's declared members | 0.01–0.1 ms; below 0.02 MB; a few calls, no index reads | `seon.turn.world/world`; the map is owned, not cached. Its immutable configuration/projection inputs can be shared. |
| SCI | Fork a cached program context; attach independent mutable cells and custody | New fork state, plus changed declarations on a miss | 1–2 ms; below 1 MB | `:seon.sci.eval/base-context` and `:seon.sci.eval/overlay-context`, read-through; keys must denote loaded/program content and arming decisions. Weigh retained program structures, not only the shallow context map. |
| First context | Task declaration, proofs, must-read references, agent namespace, unread messages, branch cost | Task and shown references, not all declarations | 2–5 ms; 1–3 MB | Reach graph/index keyed by program content and requested proofs; contract references keyed by contract content plus semantic registry scope. Facts remain in Datahike; large immutable shown values belong in konserve blobs. |
| Paused Flow | Three procs, channels, own executor, start control messages | Proc/channel count | 1–5 ms; below 1 MB allocation, plus three platform-thread stacks | Flow owns lifecycle; live graphs are owned roster entries, not cache values. Blueprint data is cheap construction. |

Every derived entry uses `seon.cache/lookup`: a miss reads its owner and fills the
entry; eviction changes cost, never correctness. CACHE-1 must retain graph edges in
the identity: call-target changes can occur without a source definition digest
change. CACHE-2 must preserve Malli's local-registry shadowing and cross-registry
semantics; a form digest alone does not identify that result for arbitrary compiled
schemas.

## Measurements

Baseline candidate `6ce55cb0a`, based on HEAD `c6113d2f3`, through the isolated
check runner, run `0a02bec059c9`. MB below are decimal thread-allocated MB.
The test ran both samples and asserted task proofs, both references and all three
paused proc states. Four preliminary graph bounds were red because that measurement
was expanded to include handle construction and readiness; the final limits below
cover that complete operation. Fixture creation/assignment and the runner are
outside the five parts (whole run 7,560 ms).

| Part | Cold ms / MB / calls / reads (datoms) | Fresh warm fork ms / MB / calls / reads (datoms) | Time/allocation gap to proposed floor |
|---|---|---|---|
| Branch | 13.53 / 2.029 / 1,865 / 0 (0) | 13.71 / 0.740 / 7,536 / 0 (0) | Near the revised estimate; writer allocation is excluded from the thread figure. |
| World | 0.16 / 0.015 / 43 / 0 (0) | 0.19 / 0.015 / 43 / 0 (0) | Small map, already near the practical floor. |
| SCI | 1,056.23 / 1,787.485 / 81,241 / 29 (8) | 29.37 / 30.966 / 1,323 / 18 (4) | Warm >10×: one declaration-content scan and one overlay derivation on a program-equal fork. |
| First context | 121.42 / 142.787 / 4,521 / 138 (99) | 58.74 / 97.979 / 4,414 / 138 (99) | Warm >10×: one reached-graph rebuild; reference rendering also reads declaration datoms. |
| Paused Flow, including readiness | 2.05 / 0.643 / 357 / 0 (0) | 1.13 / 0.626 / 357 / 0 (0) | Already near the proposed floor; three owned platform threads remain live until stop/join. |

The sum of warm measured parts is **103.11 ms / 130.326 MB**. This excludes
profile counter collection between parts and resource cleanup, and is not an
end-to-end stopwatch measurement. Armed calls and the JVM allocation window can
include concurrent maintenance; branch calls varied from 1,865 to 7,536, so those
counts cannot be attributed exclusively to the caller.

Initial non-long regression limits (ms / allocated MB / calls), rounded above the
observed range: branch 25 / 4 / 10,000; world 1 / 0.03 / 50; cold SCI 1,300 / 2,100 /
100,000, warm SCI 45 / 45 / 2,000; cold context 160 / 180 / 6,000, warm context
80 / 120 / 6,000; graph 5 / 0.8 / 450. These are baseline limits, not target costs.

The algorithmic call floor is one world constructor; one cached SCI acquisition
plus a fork; one branch creation plus its transaction; task/agent lookups and one
lookup per shown proof/reference; and graph definition/create/start plus readiness.
Exact armed-call minima depend on the installed contracts and are not theoretical
constants. The measured world and graph give practical minima; the remaining
owners must remove program-size work before their call floor can be measured.

## Findings and limits

- Initial default observation: commit `c6113d2f3`, PID 8540; degraded by existing
  base-context and overlay-context cache discipline warnings, no open problems.
- A one-proof reach miss on default measured 108.74 ms, 203,093,536 allocated
  bytes, 1,128 armed calls and 11 database reads. This is a reach probe, not the
  full start benchmark. The owning graph load needs the real-fork comparison.
- The edit hook could not acquire the new script namespace in the existing
  default archive. The candidate check publishes and loads the script and test
  together; the hook's failure is not evidence that the benchmark ran.
- Full-tree clj-kondo baseline: 0 errors, 1,513 warnings, 6,789 ms.
- Confirmed CACHE-1 on live default, root JVM evaluation `result/ed82007fa3dda`:
  one real `seon.cluster.registry/fork!` with the source value supplied, then reopen
  the branch, answers identical proof digests but costs **84.27 ms / 126.259 MB /
  11 reads**. Every fork reach-attribute key names the fork-entity commit, while
  main's keys name its earlier program revisions. `fork!` closes its temporary
  connection after the fork-entity write; reopen loses that connection's revision
  ancestry. Remembering only the source commit is insufficient. The revision owner
  must provide content-valid read-through after close/reopen and eviction.

## Fork-revision fix and after measurements

`seon.cluster.registry/fork!` already remembers its source. Its own fork-fact
transaction then produces a new commit; closing the temporary connection without
remembering that value loses the native attribute revisions on reopen. The fix
calls `seon.db/remember-revisions!` with the transaction's `:db-after` before
returning. It changes neither the graph keys nor the SCI cache. The existing
`:seon.db/commit-revisions` kind holds just the native revision map, keyed by the
immutable commit and weighed at insertion as `64 * (4 + attribute-count)` bytes.
No database value or program copy is retained by this addition.

Datahike pin `cb3bce514507ecc96d36b6957ad10f6e892f51cf` supplies the native
cache context and attribute revisions. This preserves evidence already present in
the successful write; it adds no writer stamp or separate cache. Eviction of the
revision chain still falls back to conservative identity and can refill a duplicate
program derivation. Durable content identity/read-through after such eviction is
left to the hybrid; this fix does not claim to solve it.

The new `seon.test.runner-test/a-reopened-fork-reuses-its-programs-proof-reach`
warms ten real proof digests, creates a real fork, closes/reopens it and measures
the same request. Red candidate `71b9fb53`, run `701efe16775e`: identical answer,
**one graph rebuild / 210.577 MB**, failing both the no-rebuild and 5 MB bounds.
Green candidate `1254b98ecd518541cfefa374743d5772c7b504ba`, run `c90c93d90ab0`:
**zero graph rebuilds / below 5 MB**, all four selected Vars passed, 64 assertions.
The final landing `7267d54fb` passed 65 assertions in those four Vars, run
`af2891467a78` (candidate `72996e6605078f770edcf7f2f759b35fd49e080d`).
Its benchmark produced these after rows:

| Part | Cold ms / MB / calls / reads (datoms) | Fresh warm fork ms / MB / calls / reads (datoms) | Warm before → after, ms / MB |
|---|---|---|---|
| Branch | 10.24 / 2.041 / 1,696 / 0 (0) | 9.00 / 0.730 / 6,427 / 0 (0) | 13.71 / 0.740 → 9.00 / 0.730 |
| World | 0.10 / 0.012 / 33 / 0 (0) | 0.08 / 0.012 / 33 / 0 (0) | 0.19 / 0.015 → 0.08 / 0.012 |
| SCI environment | 849.54 / 1,801.051 / 81,241 / 29 (8) | 25.79 / 31.342 / 1,323 / 18 (4) | 29.37 / 30.966 → 25.79 / 31.342 |
| First context | 84.76 / 118.841 / 4,707 / 138 (99) | 12.55 / 10.111 / 3,991 / 127 (88) | 58.74 / 97.979 → 12.55 / 10.111 |
| Paused Flow, including readiness | 1.78 / 0.634 / 357 / 1 (0) | 1.57 / 0.616 / 357 / 1 (0) | 1.13 / 0.626 → 1.57 / 0.616 |

Warm measured parts total **48.99 ms / 42.811 MB**, down from
**103.11 ms / 130.326 MB**. The first-context allocation fell 89.6%; its warm
regression bounds are tightened to **30 ms / 20 MB / 5,000 calls**, with a zero
reach-graph-rebuild assertion. Cold bounds remain unchanged. Small timing changes
in the unaffected world and Flow parts are sample variation, not attributed gains.

Reach misses **do not remain on the fresh warm fork** in this sample: both graph
construction and reach-component calls disappear. A cold first context still builds
the graph once, as expected after eviction. SCI still calls
`seon.db/declaration-content-key` and `seon.sci.eval/derive-overlay-ctx` once on the
warm fresh fork. Thus SCI's duplicate derivation remains and is still >10× the
proposed time/allocation floor. First context remains above its 2–5 ms target;
its 24 vector-query calls and 88 datoms entrances are the next measured work.

The cold SCI acquisition calls `seon.schema/contract-references` **3,604 times**
(the preceding green sample measured **262 ms self time**); the warm fresh fork
calls it **zero times**. H9's attribution below identifies these as the packaged
configuration declaration projection's schema walks, not function arming. This
does not prove repeated changed-contract registration is cheap. CACHE-2 remains
separate work; the schema registration task is unchanged.

### Verification boundary and remaining evidence

- Full-tree pre-commit lint: `clj-kondo --lint src script test --cache false`,
  0 errors, 1,513 warnings, 6,516 ms.
- Baseline script, test and table landed in `a03902ef4`. The source change is only
  the successful fork-write path in `seon.cluster.registry/fork!`.
- Selected green Vars: `seon.test.runner-test/a-reopened-fork-reuses-its-programs-proof-reach`,
  `seon.test.runner-test/a-program-change-reads-only-the-reach-attributes-it-wrote`,
  `seon.test.runner-test/reach-digests-and-memberships-share-content-valid-work`,
  and `seon.start-flow-benchmark-test/commit-to-paused-flow-costs`.
- The final candidate check took 28,238 ms overall: 6,340 ms ready, 888 ms adoption,
  8,458 ms tests including fixtures. Its gate lint also passed: 0 errors, 1,513
  warnings, 5,149 ms. These are platform-check costs outside the
  five measured parts, not an ordinary agent-start claim.
- Default remained on `c6113d2f3`; post-fix numbers come from the isolated candidate
  JVM, not an uncommitted reload into default. The live root/default pre-fix probe
  is recorded above. Default's lane edit hook cannot acquire `start-flow-benchmark`
  from that older archive; one acquisition-refusal fault is recorded for this lane
  (signature `3b59158eb8b31e6e578b5bd04c66db14ee74f418c7c7200f1a3dd3e90195a120`).
  The registry edit hook timed out; its result was unknown. Neither is counted as
  green evidence. Root's adoption/restart is needed for installed post-fix proof.
- Foreign boundary: other lanes' documents, `reference-code/http-kit`, and existing
  unclaimed working-tree paths were excluded from the HEAD-plus-owned-paths candidate.
  Broad tiers belong to root. The two additional registry lifecycle Vars below
  fail before reaching the changed owner, in their unchanged `with-source-store`
  fixture: its initial `seon.test-support/transacted!` has no carried projection.
  Candidate `327ccb567deeb4fddd0509af7487ee7bbb70e365`, run `a0b9cc7a158a`,
  passed all 65 assertions in the four slice Vars and reported these two errors:
  `seon.cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection`
  and `seon.cluster.registry-test/a-reused-branch-name-never-takes-the-retired-forks-merged-base`.
  Unchanged HEAD `7bfcdc75c6880ec9b378393277ba26346f6dc220`, run
  `ec87f0ad2fda`, reproduces both errors (0 assertions, 2 errors, 4,778 ms),
  proving they predate this fix. Their fixture is outside this lane's ownership; the final slice gate names the
  four passing Vars individually.
- Cold means empty `seon.cache` in an already booted JVM; library caches and loaded
  classes remain. The fixture's captured commit equals its parent connection's
  current value. Historical commit acquisition, branch connection reopen time,
  retained heap, physical index reads, and test-member p50/p90 are not measured.
  No lower bound for those is claimed. Every sampled branch and Flow is released.

## H9 attribution: base copying and configuration derivation

The SCI leg copies already armed JVM roots into SCI, then derives an overlay and
forks custody. The smallest changes must remove measured query or derivation
work; adding new wrappers to copied JVM roots would add work and change execution.
No runtime change has been made for these attribution samples.

Candidate `fbb05fc459d9d86780c6a9c15ae71f0a1433b5a2`, run `36046e420696`,
ran the benchmark in the check JVM: 39 assertions passed. Cold SCI measured
**891.92 ms / 1,795.448 MB / 81,231 calls / 29 reads**. These counts come from
`:seon.profile/called`; times are the profile's self-time counters (integer ms).

| Owner | Cold SCI calls | Self ms | Attribution |
|---|---:|---:|---|
| `seon.db/vector-query-call` | 11 | 384 | Datalog execution and its unarmed descendants |
| `seon.schema/contract-references` | 3,604 | 253 | Config's packaged declaration projection, not arming |
| `seon.db/pull-logical` | 10 | 43 | Namespace and other pulled facts |
| `seon.schema.internal/assert-compilable-schema!` | 3,604 | 26 | Same packaged projection rebuild |
| `seon.schema.edn/read-schema-resource` | 217 | 23 | Packaged schema input reads |
| `seon.sci.eval/acquire-program!` | 1 | 21 | Acquisition's remaining unarmed work |
| `seon.sci.eval/function-digests` | 3 | 6 | Base once, overlay's loaded and held values once each |
| `seon.sci.eval/copy-host-var` | 7,711 | 4 | First-party and library Var copies |
| `seon.sci.eval/install-function-contract!` | 0 | — | Existing wrappers are copied with their roots |
| `seon.sci.eval/behavior-changed?` | 0 | — | No changed authored functions in this sample |

The 29 reads are 11 queries, 8 datoms entrances and 10 pull entrances, not one
database read per function. `seon.sci.eval/loaded-digests` is entered twice;
`seon.sci.eval/function-digests` is derived three times. The base's direct digest
derivation does not populate the cache the overlay then uses.

The isolated base-only proof was run separately after emptying `seon.cache`, with
the loaded database and its carried projection in hand. Candidate
`b6c6361500e98a71bae0d3a337f568d208183b80`, run `f5d8cf9a75a4`, measured
**763.86 ms / 2,446.279 MB**: 6 queries took **633 ms** self time, one namespace
pull **39 ms**, acquisition **20 ms**, and the 7,711 Var copies **4 ms**.
`function-digests` ran once. Both `contract-references` and
`install-function-contract!` ran **zero times**. The proof's 300 ms / 300 MB
bounds fail; its initial once-per-contract assertion also failed (**3,442 ≠ 0**).
The proof now requires zero installations: the loaded roots already hold their
wrappers. The context-value and zero-reference-walk assertions pass. Whole test execution
including fixture setup was 4,535 ms. These are separate cache histories: the
whole SCI leg follows a measured branch and world, while the base-only sample
starts immediately after central eviction; their allocations are not additive.

The call path explaining the reference walks is
`seon.sci.eval/armed-dials` → `seon.sci.eval/database-effective-config` →
`seon.config/effective` → `seon.config/declared-projection` →
`seon.schema/declaration-projection`. The last reads all packaged forms and builds
their reference graph after eviction. There is no `contract-product` or
`instrumented` call in the sample. Thus H10's retained-function-contract change
cannot remove these particular 3,604 walks. H9's base already reuses compiled
wrappers; the corrected H9 proof preserves that behavior.

Dependency seams checked: SCI pin `f455b3b4a6a4fe92cd2adde2e19821ab1fe5bece`,
`sci.core/copy-var*` copies the existing root and `sci.core/fork` shares it through
generation isolation. Malli pin `dd13ff81139159778edb0a67b491eccc0baa991d`,
`malli.core/-instrument` constructs a wrapper and `malli.core/validator` caches on
the compiled schema. No new cache kind or dependency change is needed to share
the wrapper already held by the host Var.

Initial H9 health observation: default remained on `c6113d2f3`, PID 8540, with one
open fault, two failed tests and one cache-discipline warning. Its cache was not
emptied. The older archive's edit hook cannot resolve `start-flow-benchmark`;
that acquisition error is not benchmark evidence. All measurements above use
the committed candidate check path. H10's files and all unrelated working-tree
changes are excluded from H9 candidates.

### Six-query attribution and the first-read cost

`start-flow-benchmark/base-queries` retains the six original forms. The first
query-only check reads the loaded program before any benchmark SCI acquisition,
then empties the central cache. Datahike's own query-result cache remains enabled,
as in the original cold-base sample. Candidate
`fbc13b861fb7f66a308260174348603b959cafb3`, run `4ada2f8608ef`:

| Query at its SCI owner | Results | ms | Allocated MB |
|---|---:|---:|---:|
| `seon.sci.eval/load-core-namespaces!` | 123 | 0.33 | 0.139 |
| `seon.sci.eval/acquire-program!`: namespace declarations | 533 | 0.25 | 0.089 |
| `seon.sci.eval/acquire-program!`: namespace names | 533 | 0.21 | 0.063 |
| `seon.sci.eval/function-rows` | 6,399 | 731.64 | 2,071.788 |
| `seon.sci.eval/test-namespaces` | 410 | 0.38 | 0.088 |
| `seon.sci.eval/acquire-program!`: test declarations | 2,807 | 17.58 | 33.160 |

These are first entrances in this test, not claims that boot left every library
cache empty. Repeating them after the SCI benchmark hits Datahike's result cache.
Binding `datahike.query/*query-result-cache?*` to false (the dependency's documented
benchmark seam) after that acquisition gives 76.23 ms / 106.118 MB for the six
raw queries; function rows alone are 23.53 ms / 42.629 MB. Thus a warm query
cannot explain the first-read cost. Datahike pin remains
`cb3bce514507ecc96d36b6957ad10f6e892f51cf`.

The first trial replaced `function-rows` with one identity range and one
`seon.db/pull-many`. Its result matched the original query, but the cold base
still cost 1,165.64 ms / 2,332.372 MB (candidate
`cbed700fe2a44b5cb8f00a7f151da06abc846f15`, run `818e4832bd6b`); a subsequent
function pull cost only 58.29 ms / 122.156 MB. This trial was not landed.
Allocation sampling of the same cold trial in the check JVM showed index-node
restoration through `datahike.index.persistent-set/CachedStorage` and konserve's
Fressian/LZ4 decoding. The stacks reach `datahike.pull-api/resolve-pull-eid` and
the EAVT slices used by pull. Pulling each entity and Datalog's `get-else` both
touch entity-ordered leaves holding much more than the four requested attributes.

The chosen `seon.sci.eval/function-rows` reads four AEVT ranges through
`seon.db/datoms`, indexes their scalar values by entity id and joins in memory.
This replaces per-entity reads with O(function symbols + source values +
admissions + privacy values) work, preserving missing-source and missing-privacy
defaults. The existing `function-digests` uses the same attribute-range approach.
No cache, schema or dependency change is added. The equality proof compares
every returned declaration against the original query on the real loaded program.

Range trial candidate `1aefa290eb2a3faa2efeda2a8080f855fd7a367d`, run
`1163581609a5`: the equality and four-range/no-query/no-pull assertions pass.
The repeated function-row acquisition costs **6.65 ms / 11.633 MB**. The cold
base falls to **517.79 ms / 1,242.088 MB**, so its end-of-H9 budget remains red
and is not claimed green for this first removal. Five queries and one namespace
pull remain; the largest unnecessary query still fetches every test's source
before discarding all core tests. The complete cold SCI leg is **580.51 ms /
787.920 MB**, versus the attributed baseline **891.92 ms / 1,795.448 MB**.
The comparison retains each sample's existing cache history, not a sum of legs
from different tests. Full-tree lint: 0 errors, 1,513 warnings, 6,694 ms.

The first landing check (`4624942324a6306d219d89bb42dff6596804d6ad`, run
`dad0a83991f0`) passed 81 assertions and failed one existing assertion in
`seon.sci.eval-test/acquisition-binds-loaded-first-party-compiled-vars`: the test
expects a loaded Datahike dependency to be unavailable. Its compiled-root identity
and custody assertions pass. The remaining selected acquisition and binding Vars,
including the complete benchmark, passed. The check took 52,612 ms overall,
23,580 ms to readiness and 10,399 ms for tests. This is not an ordinary operation.
Unchanged HEAD `610add01d0c3b899983bf9daa733c6e2adc927be`, run `0bb5c447ae31`,
reproduces that same assertion (3 pass, 1 fail, 3,277 ms). That Var is excluded
from the passing slice gate; the cold-base and config cost proofs remain the
two deliberately red H9 follow-ups described above and below.

### Read selected test declarations before their bodies

`885489132` landed the four-range acquisition. Its final check ran ten named
Vars with 78 assertions green (`0f60c003a5ee`, 50,144 ms check, 9,813 ms tests).
The next removal binds `:agent` at the entrance to the test query, instead of
joining all 2,807 test bodies and discarding core rows in Clojure. The query
retains the same four-column result for selected tests.

Candidate `584ae48e669b6c36071c1e528efae4d93dde5d85`, run `54031a28e407`,
reduces the isolated cold base from **517.79 ms / 1,242.088 MB** to **236.65 ms /
566.663 MB**. Time and zero-installation/zero-reference-walk assertions pass;
allocation remains red. Five queries now fall below the top eight self-time
owners; range reads account for 108 ms and namespace pull 65 ms. The complete
SCI benchmark also passes, but its separately evicted sample is **799.77 ms /
1,631.871 MB**, warm **28.09 ms / 29.433 MB**: this full-leg sample regresses
against the preceding 580.51 ms / 787.920 MB sample and is not counted as a
full-leg gain. The samples retain different preceding test/library-cache
histories. The isolated base is the direct comparison for this removal.
Full-tree lint: 0 errors, 1,513 warnings, 7,319 ms.

### Remove the loaded base's self-comparison

`df70fe7c1` landed the selected-test query; its final benchmark measured cold SCI
**544.47 ms / 814.605 MB**, warm **28.09 ms / 29.375 MB**. The next removal follows
the only caller of `acquire-program!`, `derive-base-ctx`: both hold the loaded
program itself. Comparing its function digests against itself, opening it again,
finding overridden rows and their closure, and merging its rows with itself do
no work needed by the base. Those operations and the now unused
`host-function-rows` and `loaded-function-rows` helpers/cache kind are deleted.
`derive-overlay-plan` remains the owner of branch differences.

Candidate `3a2c2ac6aed828ea5e5b0cf833c49d09a510ce84`, run `f78a9d947ac8`,
measured **203.71 ms / 495.057 MB** for the isolated cold base (previously
236.65 ms / 566.663 MB). Zero digest scans, loaded-database entrances, behavior
comparisons, contract installations and contract-reference walks pass. The
allocation bound remains red for this individual removal; the other 46
assertions, including the full benchmark, pass. Full-tree lint: 0 errors,
1,511 warnings, 7,906 ms.

H2 landed `c566e0401` during this work. Default still runs `c6113d2f3`, PID 8540;
the live adoption half of `bin/seon check` now refuses the changed Datahike
dependency with `JVM RESTART NEEDED`. The throwaway JVM runs HEAD plus the owned
paths and returns the measurements above. **RESET NEEDED** is reported to root;
this lane never restarts default or empties its cache. A failed live adoption
is not a successful landing even when the throwaway tests pass.

### Namespace binding joins: measured candidate

An explicit namespace selector reduced the remaining base allocation only from
495.057 MB to 468.001 MB; it still traversed entity-ordered index leaves and was
not retained. The cold function ranges independently cost **87.88 ms / 184.618 MB**
after eviction. Namespace acquisition therefore replaces the remaining entity
pull with four Datalog attribute joins for requires, aliases, imports and refers,
reusing the namespace names and source/admission declarations already read.
This is a declaration-sized join, with no general pull implementation or cache.
`base-namespace-acquisition-preserves-declarations-and-bindings` compares every
namespace's source, admission and bindings against the former pull on the loaded
program and requires zero pull-many entrances.

Candidate `df1e08308cb3d8705b4d13769843189e6f1da909`, run `35ddb335be07`, passes
all **49 assertions** in the cold-base proof, namespace equivalence and full
benchmark. The isolated base is **132.69 ms / 292.517 MB** (the original base was
763.86 ms / 2,446.279 MB), with 7,712 Var copies, four function attribute ranges,
nine queries, zero entity pulls, and zero digest scans, behavior comparisons,
wrapper installations and reference walks. The repeated namespace join costs
5.55 ms / 16.087 MB. Full-tree lint: 0 errors, 1,514 warnings, 6,656 ms.

The full SCI leg of this sample is **817.62 ms / 1,645.164 MB / 66,830 calls**,
warm **11.71 ms / 5.001 MB / 490 calls**. H2 changed the warm identity path during
these checks; that warm reduction is not attributed to H9. The cold full leg
still includes the config projection and the overlay, separately from the now
green base. Live adoption remains refused by the dependency mismatch, so the
self-comparison deletion and namespace-join change are checked and landed separately.

Root subsequently refreshed default to `696a8cf46`, PID 42206, resolving the
dependency mismatch. The next self-comparison-deletion check
(`e1087123437076f1951048a5bad5001cb5d16209`, run `93920c0023e5`) passed 80
assertions and found one existing red:
`seon.sci.acquisition-cost-test/a-data-only-commit-reuses-the-program-overlay`
observes two `authored-rows` calls instead of zero. Unchanged HEAD reproduces it
in run `06c5bdcc8883` (2 pass, 1 fail, 2,958 ms); it is excluded from the passing
slice gate along with the earlier dependency-visibility red. H3 owns the
remaining program-identity/overlay reuse work.

The benchmark's cold branch allocation also became red after the root refresh:
4.095 MB in run `2d2e80932df2`, 4.421 MB in `d153b993abf4`, against its unchanged
4 MB bound. Unchanged HEAD reproduces **4.426 MB** (the other 38 assertions pass).
This is before SCI acquisition and outside the evaluator change. The full
benchmark is still rerun for each removal, with its SCI observations retained;
its pre-existing branch assertion is reported red, never relaxed or counted
green. The passing slice gate therefore names the individual acquisition and
binding Vars, excluding this benchmark Var as well as the two pre-existing
Vars named above. On unchanged HEAD its cold SCI leg is **838.18 ms /
1,834.565 MB**, establishing the refreshed-store comparison.

### Final H9 verification

The self-comparison deletion landed as `8e6972952`. With namespace joins, the
refreshed-store candidate `1abc642b42c49238793ce0f25ec0e3e185ec56d8`, run
`94303b0f155f`, passes all 49 assertions in the base cost proof, namespace
equivalence and complete benchmark. The base is **130.46 ms / 294.781 MB**,
versus the original **763.86 ms / 2,446.279 MB**; the 300 ms / 300 MB bounds and
all five zero-work assertions pass. Full cold SCI is **776.02 ms / 1,643.217 MB**,
warm **10.35 ms / 4.942 MB**. The branch is 6.36 ms / 1.961 MB in this sample;
the previously reproduced branch-budget reds remain reported, not erased by
this passing sample. The complete check took 28,997 ms: 7,344 ms ready, 1,086 ms
adoption and 7,774 ms tests. No ordinary-operation claim includes that platform
setup. Final full-tree lint: 0 errors, 1,514 warnings, 7,003 ms.

The final passing slice gate names these non-long Vars individually:

- `seon.sci.acquisition-cost-test/a-cold-base-derivation-is-proportional-to-declarations`
- `seon.sci.acquisition-cost-test/base-namespace-acquisition-preserves-declarations-and-bindings`
- `seon.sci.acquisition-cost-test/function-acquisition-preserves-the-loaded-declarations`
- `seon.sci.acquisition-cost-test/a-second-acquisition-of-an-unmoved-branch-interprets-nothing`
- `seon.sci.acquisition-cost-test/a-core-row-installs-without-opening-the-loaded-program`
- `seon.sci.acquisition-cost-test/a-branch-head-read-costs-one-primary-materialization`
- `seon.sci.eval-test/base-context-bindings-resolve-without-dependency-stubs`
- `seon.sci.eval-test/every-public-capability-function-in-the-graph-resolves-in-the-ctx`
- `seon.sci.eval-test/acquired-source-context-forks-have-branch-custody-and-private-defs`
- `seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled`
- `seon.sci.eval-test/an-interpreted-namespace-runs-its-fixture-around-a-deftest`
- `seon.sci.entry-gate-test/every-program-namespace-carries-its-stored-aliases`

The benchmark's separate final run is green as recorded above. The earlier
pre-existing dependency-visibility, data-only-overlay and branch-budget reds
are named with baseline runs in their owning sections. The config cost proof
below remains intentionally red. Root owns broad tiers and installation of the
landed evaluator into default. The foreign boundary is H2's Datahike/revision
change and H10's schema/instrumentation work; this lane changes neither owner's
files. Across these H9 removals, evaluator source is **−6 lines**, tests **+71**,
and the reusable measurement script **+54**, relative to `00da22467`.

### Configuration owner task

`seon.config/a-config-read-rebuilds-the-packaged-projection` names
`seon.config/effective`, `seon.config/effective-in` and
`seon.config/declared-projection`. Its red proof is
`seon.sci.acquisition-cost-test/a-config-read-reuses-its-carried-projection`.
Run `4ada2f8608ef` returns a valid config but measures **366.96 ms / 432.855 MB**,
with **3,604 reference walks and 217 schema-resource reads**, failing both
zero-work assertions. It requires preserving branch-local and newer JVM dials
and their shipped defaults. This is separate from H10's contract arming work;
the config owner is outside the H9 source slice. The task and red proof remain
pending deliberately, not a claimed green result.
