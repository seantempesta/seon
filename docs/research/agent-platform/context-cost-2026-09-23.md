---
type: research
status: measured diagnosis; proposed slices await independent review
created: 2026-09-23
tags: [agent-platform, context, profiling, sci, datahike]
---

# Context cost: why it is being done wrong

The system already has immutable database values, definition digests, indexed
references and SCI forks. The smallest improvement is to pass their changed
inputs to the existing owners, keeping unaffected values, rather than reconstructing
the program or proving a whole history equal again.

**SCI is not taking 1.7 seconds to fork. Seon is rebuilding its input.** A retained
branch acquired in **0.396–0.481 ms** here. An unrelated note update acquired in
**0.515 ms**. Changing one function row triggered **1,320 ms**, including
**1,296 ms rebuilding the base** and **1,036 ms in reverse closure**. Inside that
closure, **959 ms** went to enumerating declared reference edges for the whole
program. Even a source-only whitespace edit, with the stored definition digest
unchanged, caused this rebuild. The missed cache has no incremental continuation:
its miss function constructs a fresh interpreter and re-enumerates the program.

**Prompt generation has a different, worse defect.** Its saved-history query
records `:all` attribute dependencies. An unrelated note update made it spend
**4,068 ms** replaying and comparing history to return the same **681,255 characters**.
Changing one saved evaluation made it spend **8,242 ms** for a 28-character delta.
Most individual render outputs were reused; the expensive work was validating,
decoding and canonically hashing their aggregate read evidence. A warm prompt
still spends **7–10 ms** selecting, composing and hashing 177 contributions again.

The target is a sub-millisecond lookup when inputs have not changed, and
milliseconds proportional to changed entities and affected dependency edges when
they have. A genuinely widely used definition can affect the whole program; a
tiny affected closure must not enumerate it. Producing or sending a new contiguous
prompt string remains proportional to its output bytes; incremental derivation
cannot make serialization of 681 KB constant time.

These are defects to fix, not justifications for seconds of latency. This assignment
authorizes diagnosis and design only, so the fixes below are not implemented.
The existing defect class is [one-form resume takes seconds](../../seon/issues/a-one-form-resume-turn-takes-seconds.md),
with acquisition speed owned by [D1 evidence §2e/O2a](d1-evidence-2026-09-23.md)
and A1/B2; prompt/turn work belongs to B2 in **cut 4**.

## Evidence boundary and method

Measured 2026-09-23, approximately 20:38–20:47 UTC, root
`/Users/sean/src/seon`, branch `refactor/agent-platform`, JVM **48902**, start
`2026-09-23T18:07:01.344Z`. `bin/seon status` and MCP `runtime_status` both
answered. The runtime uses committed archive **ce73846828a5cc32798ef630b5a574f777646f30**;
hook publication is off. Checkout HEAD initially **ab6220793** and other lanes'
dirty files were not loaded, changed, restored or committed here. Source line
references below are to the installed archive unless explicitly marked upstream
or plan. In particular, checkout `seon.fn` and error conversion edits are not
proof of installed behavior.

Installed gitlinks: Datahike **c79cd03a44427ac1734d917c7484c3e529c77716**;
SCI **fcbd8862800e638dc0f8f5521111f999279cbcd2**. Read the REPL,
data-oriented-clojure, Datahike and Flow skills. No src/test edits, reloads,
default Var replacement, provider calls, test gates, new JVMs or worktrees.

MCP JVM session `context-cost-astra`, namespace `probe.context-cost-20260923`.
All database writes and acquisition side effects were confined to
`:context-cost-astra-20260923`, created from commit
`6ab41676-1dd3-5720-9efc-820eca818f03`, initial basis **536872632**.
The branch tool created it in **44 ms** and released/retired it in **14 ms**.
No agent graph was started. Local `let` bindings, not retained probe defs, held
database values. The MCP session has no retained result Vars. MCP's own oversized
result rendering can create diagnostic blob artifacts; this is tool behavior,
not an explicit transaction against default by the probe.

Each measurement uses `System/nanoTime` around the operation, and
`seon.profile/begin` before it followed by `seon.profile/explain`.
`explain-slow` is the automatic >1-second report in the MCP envelope
(`profile.clj:178,204,243`). These are the **armed wrapper's counters**, not a
replacement profiler. Totals are inclusive, recursive calls count repeatedly,
and other threads can contribute. Do not add parent and child times. Direct
timings exclude explanation/rendering; the counters truncate to integer ms.
The profiler observes its own `cells`/predicate calls while explaining: those
8–9 ms entries are measurement overhead, not acquisition. No missing sub-ms
profile row means zero calls. Direct timings resolve that limitation.

## A. Acquisition: fast baselines, actual phases, cache key

Previously measured baselines remain valid comparisons, not repeated results:

| Previous observation | Time | Evidence |
|---|---:|---|
| Quiet newly isolated acquire | 48–68 ms | [test-load study](test-load-vs-dev-system-2026-09-23.md), cost table |
| First acquire at new head | 1,628 ms; derive 1,305; closure 1,087 | Same study |
| Branch / open / SCI fork primitives | 74.65 / 25.62 / 0.021 ms | [D1 evidence](d1-evidence-2026-09-23.md), candidate shape; plan README §7 |
| Warm fixture branch p50 | 37 ms | [plan README §5](../../prds/agent-platform/plan/README.md) |

A newly created/opened branch and an already retained handle are different
operations. This run's initial acquisition was **368.063 ms**: open **10**,
fork-cluster-ctx **80**, config **59** nested within fork, acquire! **274**,
and four db/q calls **326** inclusive across the operation. **No base derivation
occurred.** It reused the parent's program; first reads on this connection were
still costly. Subsequent retained acquisition was 0.396 and 0.481 ms.

| Same retained branch, in order | Direct ms | Outcome |
|---|---:|---|
| Create a valid root-owned note, then acquire | 1,876.117 write; **0.551 acquire** | Data-only head movement did not rebuild |
| Replace only its content datom, then acquire | 179.342 write; **0.515 acquire** | Two effective datoms: retract old, assert new |
| Append newline to contribution-hash source | 88.972 write; **1,320.319 acquire** | Source revision changed; definition digest stayed cfd192… |
| Change its doc and explicitly derive/write definition digest | 70.740 write; **1,626.412 acquire** | Digest 93007a…; interpretation/refusal path also exercised |
| Restore original source/doc/digest on branch | 77.052 write; **932.655 acquire** | Equal content restored, but revision key still new |

The initial 1,876-ms write is a separate writer defect: `transact-call` 1,876,
`write-owned-values-error` 581 ms, with the rest not isolated by this profile.
It cannot be charged to context generation or justified as necessary startup.
Later one-entity writes were 71–179 ms. No operation here exceeded ten seconds.

Actual call tree, first function-row change (ms; nested inclusive):

```text
cluster.agent/acquire-context!                    1320  ×1
  sci.eval/acquire!                              1320  ×1
    sci.eval/base-ctx                            1296  ×1
      sci.eval/derive-base-ctx                    1296  ×1
        sci.eval/acquire-program!                1294  ×1
          fn/reverse-closure → gate-sets-in       1036  ×1
            fn/declared-reference-edges           959  ×1
            fn/gate-set-in                         12  ×1
          sci.eval/function-digests                34  ×2
          db/pull                                 111  ×441
          sci.eval/install-row!                    57  ×2
          sci.eval/copy-host-var                    8  ×3661
          sci.kernel/mark-installed!                6  ×3620
```

This JVM's population is **4,752 branch functions / 4,751 loaded**, not the older
~4,820-row/275-override D1 sample. After restoring the probe change, the actual
digest comparison found **one override**, closure **one affected function and
one test**, yet direct reverse closure still cost **786.622 ms**. Direct warm
digest maps cost **6.394 + 5.324 ms**. This falsifies an explanation based only on
the size of a 275-row override set. The graph setup dominates even a tiny closure.

The explicit digest-change variant cost 1,626 ms: derive **1,160**, closure
**804**, declared edges **748**, install-row **256 ×47**, refusal recording
**444** including a **367-ms transaction**. This was a direct database-row probe,
not the admitted definition publisher: it supplied a digest using
`seon.program/definition-digest`'s one-argument form. It exposed interpretation
refusals and must **not** be reported as successful branch execution or canonical
publication. All refusal facts stayed on the disposable branch. The source-only
variant is the cleaner rebuild-cost measurement; it did not change executable
meaning or claim to regenerate analysis facts.

### What invalidates the base

Installed `sci/eval.clj:2377,2412,2466,2555,2640`:

1. `acquired-database?` first checks the loaded source authority, then whether
   previous/current program bases agree (or are the same immutable value).
2. `program-identity` has a 256-entry commit-id memo. Its value is the fork's
   connection/generation/conservative/attribute revision basis.
3. `base-ctx-key` is **[restricted program revision basis, loaded-program commit
   id, fault-recorder callable]**. The attribute set includes program partition,
   projection inputs, configuration dials and source commit. Base LRU holds four
   entries. It is not simply the branch commit id.
4. An unrelated note leaves those selected revisions unchanged: measured hit.
   A source/doc/digest change advances selected revisions: measured miss. A
   loaded-program adoption, new lineage, conservative revision, different
   recorder, missing identity, or eviction can also require work. Changing back
   to equal content does not rewind a revision: measured 933-ms rebuild.
5. On miss, `derive-base-ctx` is the only continuation. There is no changed-row
   update in that path. It creates the minimal interpreter then calls
   `acquire-program!` over the entire database program.

`acquire-program!` (`:1881`) queries all namespace assertions/names and function
source/admission/private rows, makes both whole-function digest maps, compares
them, derives the reverse closure, pulls all namespaces, queries tests, rebuilds
function/namespace maps and ordering, copies matching JVM roots, installs
interpreted rows and arms their contracts. Costs follow **all functions,
namespaces, tests and declared edges**, plus the affected closure and interpreted
source size. Query caching does not eliminate constructing these maps and copies.

`fn/gate-sets-in` (`fn.clj:1556`) queries all function/test identities, test
symbols, declared edges, file references and handlers before walking seeds.
`gate-set-in` already uses AVET for calls/references/subjects (`:1543`); the
unbound declared-edge query (`:1527`) defeats the small frontier.

Minimum work: retain the loaded digest map with its immutable loaded program;
use admitted changed identities (including removed identities and resolver /
contract changes), read just their rows, traverse affected incoming edges using
indexed bound targets, install/remove only that closure at an idle boundary.
Reuse existing SCI forks and contract compilation. No change means no graph walk,
no digest map, no root copies. Cost target is O(changed rows + reached edges +
affected source), with index seek cost; not O(all program rows).

### Git history: it was not introduced by today's leak fix

Verified with `git log -S`, `git show` and parent source, not inferred from dates:

| Commit | What changed |
|---|---|
| `684f185f8`, Sep 16 | Split pure base derivation from effectful acquisition; `acquire!` regenerated a base unconditionally. Before it, acquisition installed into the supplied context. |
| `430fc91a1`, Sep 20 | Added the acquired database identity fast path and first-use acquisition. On any different database identity, the older whole-base regeneration remained. This is the explicit **head changes → rebuild** branch. |
| `f6a463de6`, Sep 20 | Named `acquired-database?`, deferred idle acquisition, retained the same identity/miss semantics. |
| `39a337013`, Sep 22 | Added loaded-vs-branch digest maps and reverse closure so indirect callers honor branch overrides. Its own commit records **1,658 ms** acquisition. Correctness improved; changed-set processing did not replace enumeration. |
| `1ada78050`, Sep 22 | Made acquire-context! the retained live/isolated entrance. Its context-state monitor surrounds branch/open/fork/acquire work; it did not introduce incremental program installation. |
| `5f2aa93f4`, Sep 22 | Added program-revision identity and base-context-cache, allowing data-only writes to reuse the program. Extracted the old whole derivation as `derive-base-ctx`, used only on misses. Its commit message incorrectly calls fork revisions Datahike's own. |
| `03bd7cfc9`, Sep 22 | Tracks the adoption record's loaded program so loaded changes invalidate correctly even when this branch's rows do not change. |
| `36cfeb2d3`, Sep 23 | Removed database/loaded database from cached bases; copy-base-ctx supplies the caller's database/loaded program. Also copies dynamic host Vars at their root and replaces Connection cache keys with data. **No base-ctx-key or LRU-policy change.** |

The leak fix changes retention and copy cost, not the nominal hit condition.
The installed post-fix JVM demonstrably hits for unchanged and unrelated writes.
There is no before/after hit-rate benchmark here, so this is source proof plus
post-fix observation, not a statistical claim that hit rates are identical under
every workload. Do not revert the leak repair to chase acquisition latency.

## B. Prompt, history and namespace rendering

Production entry is `turn/call-turn` → `cluster.prompt/prompt` at
`turn.clj:4459`, with opening-db, turn id, explicit connection/context/caps.
Measured the same prompt function with no turn id: **current-context preview**,
all saved history, no opening/capture/provider/settlement writes. Historical
as-of prompts and the full turn lifecycle were not timed.

```text
cluster.prompt/prompt
  effective-ai-settings; model-calibration
  acquire-context-report
    render/request-profile
    render/acquire-context!
      render.web/derive-context!
        validate retained evidence OR render.walk/history
          eval/of-agent → query + pull all evaluation rows
          render/render-call per evaluation → saved-text schema pair
    select → compose → repl/frame
    history-contributions → contribution-hash per segment
    tokens/budget-report
```

| Phase (inclusive ms) | First branch prompt | Same basis warm | Unrelated note change | One shown-text change |
|---|---:|---:|---:|---:|
| Direct prompt | **8498.966** | **9.718 / 8.832** | **4067.868** | **8241.552** |
| acquire-context-report | 8485 | 7 / 6 | 4065 | 8239 |
| derive-context! | 8447 | <1 recorded | 4056 | 8233 |
| read-evidence-current? | not top | absent | 4034 ×177 | 4089 ×203 |
| read-evidence construction | 5581 ×177 | absent | absent | 2746 ×2 |
| render.walk/history | 2869 | absent | absent | 1374 |
| eval/of-agent (two arities, nested) | 2734 ×2 | absent | replay bypasses Var | 2496 ×2 |
| replay-read | not top | absent | 1237 ×1 | 1277 ×1 |
| render/render-call | 1496 ×176 | absent | absent | 121 ×176 |
| canonical-value-string (recursive inclusive) | 7147 ×1,352,367 | absent | 5330 ×719,518 | 10703 ×1,439,045 |
| attribute decode | 1145 ×24,261 | absent | 1088 ×24,261 | 2219 ×48,522 |

The first and unrelated prompts have 177 contributions / 681,255 characters;
the changed prompt has 177 / 681,283. The branch-local mutation appended
`\n;; disposable profile delta` to saved evaluation eid **53131**. This is a
controlled invalidation experiment, **not a proposed production edit to immutable
historical shown text**. Production changes append new evaluations or compact by
the owning operation. The note update was 157.041 ms, the evaluation update
80.836 ms, excluded from prompt times.

Warm direct selection `context/selection` is **0.528 ms, zero contributions**.
It is not the source of these 176 history rows: installed `walk/history`
(`walk.clj:927`) calls `eval/of-agent`, not `context/selection`. Do not optimize
the similarly named selection API and claim to have fixed this prompt.

### The existing caches, and why they fail

`render/shared-cache` (`render.clj:1618`) lives in the context environment and
is scoped by fork-only connection/generation identity. A new branch gets its own
cache. `web/derive-context!` (`web.clj:2543`) keys retained AI calls by agent
lookup, turn id and pull selector; individual render calls retain producer,
projection, input and read evidence (`render.clj:721`). It does reuse output.
It does not simply evict all output on every commit.

But root history evidence, inspected in that cache, was exactly:

```clojure
[{:op :pull :attrs #{:seon.agent/id}}
 {:op :pull :attrs #{:seon.agent/id}}
 {:op :q    :attrs :all}]
```

`eval/of-agent` (`eval.clj:9`) passes a wildcard pull selector into its query,
including nested read-evidence. `dependency-revision` (`db.clj:875`) keys `:all`
on commit id. Any write defeats the cheap proof; there are no usable narrow
index patterns for this root aggregate. `read-evidence-current?` (`:1139`)
replays, canonicalizes and hashes the complete result to prove equality.
`read-evidence` (`:904`) hashes read results again when capturing a changed root.
The 4-second unchanged result is **cache validation doing the original work**.
Per-evaluation caching works (176 calls cost only 121 ms after the one-row
change), but the aggregate evidence wraps it in whole-history work twice.

Even warm prompt composition scans contributions, rehashes all 177 segments,
reprices and copies strings (`prompt.clj:184,285,359`). A measured warm call
allocated **40,422,368 bytes on the REPL thread** in **7.425 ms**, with the
allocation interval after profile/begin and before profile/explain. This includes
armed execution and small result assembly, not other threads or retained heap.
It is not a memory-leak measurement. One earlier interval including begin was
41,170,112 bytes. No GC or retained-heap experiment was forced on shared default.

### Namespace rendering is a separate path and has a failed boundary

`seon.render.ns/render-ai` (`ns.clj:795`) was not called in the timed saved-history
prompt. Historical namespace output is already saved text. Namespace generation
for opening/refresh and namespace pages must therefore be measured independently.

For agent-owned `my.agents.root`, the entry emits a dir teaching form:
**17.180 ms first**, **0.840 ms warm**, 90 characters. First call's source-text
formatting cost 15 ms; its two queries cost 1 ms. For non-agent `seon.context`
at distance 1, full rendering **fails**, warm failure **5.200 ms**, after 42 pulls
(2 ms). Exact failure: `IllegalArgumentException: contains? not supported on type:
java.lang.String`; `read-refusal? :54` ← `full-ai-text :533` ← `ai-text :580` ←
`render-ai :826`. Full Throwable map was returned; MCP artifact
`1458aaa318b6ddb27e0c09e9d1c0b3d8383a58fc0236d957dcbee87308f1c890` retains it.
This is not a successful namespace-render performance result.

`render-data` (`ns.clj:370`) rereads namespace, all its function rows, own schemas,
then referenced schema closure. The `::schema-row-cache` atom is newly allocated
per call; it only avoids repeated pulls within that call. It does not cache
unchanged namespace output across calls. B2's namespace slice already replaces
this atom with the projection's schema index and deletes the presentation ladder.
The string predicate bug is a foreign boundary recorded here, not repaired in
another lane's src. No successful full-namespace after-change claim is made.

## Verified dependency seams and the target composition

Read upstream **7f39cccc**, the audit's fetched revision, directly with `git show`.
This is source verification, not an upstream integration test. The
[fork audit](fork-audit-datahike-2026-09-23.md) is correct that `:cache-context`,
attribute revisions and `committed-value-identity` are fork features, not upstream.

| Upstream owner at 7f39cccc | Guarantee and appropriate use | Limit |
|---|---|---|
| `core.cljc:238,251`, listen!/unlisten! | Per-connection successful transaction reports; use existing listener as wake source | Not replay or cross-process delivery |
| `core.cljc:258`, listen-commits! | Durable batch event and ordered reports, local writer | Exists upstream, but do not add a second transport beside the ruled router |
| `versioning.cljc:201,464`, branch!/commit-id | Branches share immutable stored program; commit names a value | A commit names more than one consumer's inputs |
| `core.cljc:197`, datoms; `db/search.cljc:160`, index strategy | Bound E/A/V queries use the indexes rather than enumerating the store | Value scans need indexed attributes; inspect actual edge declarations |
| `dependency_tracking.cljc:77,93,122`, token/valid?/changed | Snapshot-local token certifies no selected attribute/namespace mutation; invalidated in the transaction | Experimental, max 16 groups / 256 terms; **no eid/value selectors**, no temporal/reloaded certificate; nil never proves equality |
| `query.cljc:3170,3296`, db-cache-key / propagate-query-cache | Content-derived bucket and untouched-attribute result propagation | `:all` invalidates; propagation skips buckets >4096 entries; not incremental arbitrary joins |

Do not replace fork revisions with an invented claim that upstream tokens match
entity ids. Use tokens for a few broad program/schema/render-policy groups,
enrolled at the existing write/configuration boundary; never write on a read to
enroll. Exact entity/attribute/value matching belongs to the existing router's
declared interests and the indexed transaction/history data it reads.

The [Flow PRD §4A](../../prds/agent-platform/plan/lane-flow-owns-running-machinery.md)
rules **one offer-only sliding-one listener → router proc → agent mailbox**.
Its callback does no query or cache rebuild. The router captures its own current
database, reads changes since its successfully delivered basis, matches declared
interests, derives changed slices, and advances only after successful delivery.
Do not process an unordered callback report as a complete ordered history, or
drop deltas into a sliding buffer. Install the listener before initial derivation.
Retractions and old/new ref ownership require both sides; empty-result reads
need predicate interests so a new matching entity invalidates them.

Flow PRD gate 4 already requires proof across coalescing, restart, retention gaps,
no-history attributes and schema changes. Where history cannot prove the delta,
report unknown and reacquire the **affected input domain**, never claim a hit.
This recovery cost is separate from the steady-state changed-entity target.

Pure boundaries proposed, reusing owners rather than a new registry:

* A: `(program rows/resolver facts, loaded definition values, contract policy)`
  → installation plan. Apply that plan through SCI at the idle boundary. Keep
  current agent-private objects in their existing context; never cache a mutable
  agent context as if it were a pure value. SCI `core.cljc:345` supplies generation
  forks; the materialization is effectful, its plan and inputs are values.
* B: `(ordered selected evaluation/entity values, renderer definitions, projection,
  profile, budget, calibration, turn frame inputs)` → blocks/contributions/prompt.
  Key each derived block by the **content it reads**, including code and policy;
  keep the existing ordered collection and unchanged blocks. Saved shown text is
  already a value and must not be parsed/re-rendered to rediscover its content.
* The router's match tells which input slice to re-read; it is not a replacement
  content hash or a stamp on a database. Match `{eid, attr, value}` interests,
  including relationship membership and query predicates, then compare that
  slice's content before recomputing. An unrelated write performs no context work
  beyond cheap routing; one changed child refreshes its owning/connected slices.
* Derived values travel with their immutable input authority. Use existing
  core.cache only where no dependency cache owns the result; delete the old
  invocation/replay machinery in the same cut, not a new cache layered over it.

Important spec reconciliation: B2 slice 9 currently says whole view per notification
and deletes the invocation cache. The owner's new requirement makes notification
mean **a matched change for this consumer**, with pure block reuse underneath.
It does not authorize retaining the current generic invocation-evidence engine.
The independently reviewed B2 design must incorporate this clarification before
implementation. HTML can still send a whole view assembled from reused values.

## Reviewable small slices and proof

All line estimates below are **added implementation lines per loadable slice**,
not permission to split a large replacement mechanism into arbitrary pieces.
If a slice cannot fit by composing the named owner, stop and review the smaller
alternative. Tests use canonical branches, real SCI and armed contracts;
no default mutation and no new runner. Commit the same timing probe with each
implementation slice; compare parent/candidate on the same captured program.

| Slice / owning spec | Smallest change; estimated additions | Regression and measurement |
|---|---|---|
| A0, D1 §2e O2a / A1 | Carry loaded digest value with loaded authority; stop rebuilding both maps for a known unchanged set. ≤40, delete duplicate enumeration. B1 supplies complete change facts. | Unrelated write invokes zero map/closure/installer work; <1 ms retained lookup; add/delete/resolver changes cannot hide. Same warm/unrelated/source probe as here. |
| A1, A1/B2 acquisition / D1 O2a | Bind target in declared-reference traversal; use existing AVET walk and per-reached identity lookup, remove whole identity/test/edge setup. ≤70, delete unbound graph construction. | Direct/indirect calls, dynamic declared invokers, file references and removed seed retain correct closure. One seed/one affected vs 10× unrelated program: cost tracks reached edges, milliseconds, no whole-graph query. |
| A2, B2 §2a retained acquisition | Consume admitted changed identities into existing retained SCI generation; update/remove affected rows only. ≤90; delete rebuild branch and later obsolete snapshot/regeneration machinery. | Old fork stays callable, new fork sees changed callee through caller, private atom identity retained, deletion unresolves, host-bound refusal, all contracts remain armed. One leaf change targets <10 ms for a small closure; wide closure reports its actual size. |
| B0, B2 cut 4 / A2 read owner | Narrow history projection to what the saved-text pair uses; avoid fetching/hash-capturing nested read-evidence for presentation. Separate the system-turn read-refresh input already owned by db. ≤40, delete aggregate replay bookkeeping made obsolete. | Byte-identical prompt/capture for unchanged history, actual source/error/interruption/output fields preserved. Repeat 176-entry baseline and unrelated note; zero full-history replay/hash on unrelated write. |
| B1, B2 cut 4 / Flow PRD router | Feed matched changed evaluation/entity ids to existing history derivation; reuse unchanged contribution values, hashes and sizes. ≤90; remove generic invocation-cache validation at this call site. | One append/one child change touches only changed slice; empty query becomes nonempty, retraction, ref reassignment, bursts and restart. Compare 10/200 histories with same one-row delta; target ms proportional to delta, measure output assembly separately. |
| B2, B2 cut 4 prompt/turn | Pure composition over cached blocks and explicit frame/policy; reuse complete result for equal inputs. ≤50; remove repeated contribution hashing and duplicate pricing. | Changed budget/calibration/order/frame invalidates appropriately; no historical text rewriting. <1 ms unchanged lookup; count hash invocations, allocation and serialized bytes. |
| B3, B2 namespace slice 10 | Projection schema index replaces per-render schema atom; remove ladder and use total declared output/error boundary. ≤30 planned; deletion-heavy. | Full namespace render must first succeed (current string predicate failure); changing one namespace function/schema updates only that namespace and dependent schema users. Repeat direct warm/change render plus byte/profile assertions. |

A0 is not sufficient by itself: merely caching the full graph or increasing the
four-base LRU hides some repeats but still makes each real edit proportional to
the whole program. B0 similarly must not just change `:all` to an incomplete
attribute list; membership, ordering, refs and renderer policies are inputs too.
Upstream token migration is coordinated with A2's fork retirement, not a new
per-agent token group (upstream has only 16 groups).

## Reproducible probe forms and limits

All calls used MCP `eval_clj`, explicit root/cluster, private session/namespace
above, `mode jvm`. The two shared lexical prefixes were:

```clojure
(let [i (get @@(ns-resolve 'seon.cluster 'running-instances) "default")
      h (assoc (:seon.turn.loop/cluster i) :seon.store/store (:seon.store/store i))
      m (seon.profile/begin) t (System/nanoTime)
      e (seon.cluster.agent/acquire-context!
         h nil {:seon.agent/branch :context-cost-astra-20260923})
      ms (/ (- (System/nanoTime) t) 1e6)]
  {:ms ms :profile (:seon.profile/lines (seon.profile/explain m 24))
   :basis (seon.db/basis-t @(:seon.db/connection e))})

;; For writes/prompt/phase probes, retrieve the already held branch:
(let [i (get @@(ns-resolve 'seon.cluster 'running-instances) "default")
      h (:seon.turn.loop/cluster i)
      e (get @(:seon.agent/context-state h) [:context-cost-astra-20260923 nil])
      c (:seon.db/connection e) d @c
      request {:seon.agent/id "root" :seon.db/connection c
               :seon.sci.eval/ctx (:seon.sci.eval/ctx e)
               :seon.sci.admit/caps (:seon.sci.admit/caps h)
               :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms h)
               :seon.config/on-core-error (:seon.config/on-core-error h)}
      m (seon.profile/begin) t (System/nanoTime)
      r (seon.cluster.prompt/prompt d request)]
  {:ms (/ (- (System/nanoTime) t) 1e6)
   :chars (count (:seon.cluster.prompt/text r))
   :contributions (count (:seon.context/contributions r))
   :error (when-not (:seon.cluster.prompt/text r) r)
   :profile (:seon.profile/lines (seon.profile/explain m 20))})
```

Measured write expressions substituted for the timed operation, each checked
`:db-after` and surfaced the entire refusal otherwise:

```clojure
(seon.db/transact! c [{:my.note/id "context-cost-unrelated"
                       :my.note/agent [:seon.agent/id "root"]
                       :my.note/content "one note"}])
(seon.db/transact! c [[:db/add [:my.note/id "context-cost-unrelated"]
                      :my.note/content "one changed note"]])
;; Later repeat used "another changed note".
(let [row (seon.db/pull d '[:seon.fn/source]
                        [:seon.fn/sym 'seon.context/contribution-hash])]
  (seon.db/transact! c [[:db/add [:seon.fn/sym 'seon.context/contribution-hash]
                        :seon.fn/source (str (:seon.fn/source row) "\n")]]))
(let [row (seon.db/pull d '[*] [:seon.fn/sym 'seon.context/contribution-hash])
      changed (assoc row :seon.fn/doc
                     (str (:seon.fn/doc row) " Profiling-only documentation."))
      digest (seon.program/definition-digest changed)]
  (seon.db/transact! c [[:db/add (:db/id row) :seon.fn/doc (:seon.fn/doc changed)]
                        [:db/add (:db/id row) :seon.program/definition-digest digest]]))
(let [old (:seon.eval/shown (seon.db/pull d '[:seon.eval/shown] 53131))]
  (seon.db/transact! c [[:db/add 53131 :seon.eval/shown
                        (str old "\n;; disposable profile delta")]]))
```

One preliminary note write incorrectly named `:my.note/text`: it refused in
9.865 ms before mutation, reporting installed candidates and the full shape.
Corrected to declared `:my.note/content` plus its required agent ref. This is
probe error, not a product defect. The function fields were restored from an
explicit read of default before continuing B; the branch was ultimately retired.

Direct phase timing uses the same local timer around
`#'seon.sci.eval/function-digests` on the branch and the snapshot's
`:seon.sci.eval/loaded-database`, constructs the differing symbol set, then calls
`seon.fn/reverse-closure {:seon.db/db d :seon.fn/seeds overrides}`. No Var was
redefined to intercept phases. Namespace probe pulls `[:db/id :seon.ns/name]`,
adds explicit `:seon.db/db`, `:seon.render/distance 1`, and a profile from
`render/request-profile`, then times `render.ns/render-ai`. The failed full
render was rerun with `(catch Throwable x {:failure (Throwable->map x)})` solely
to retain the complete cause and timed profile; it is reported as failure.

No test run was needed to establish a documentation-only diagnosis; no test or
platform gate is claimed. The reproduction exercised installed compiled owners,
their armed wrappers, and branch-local SCI acquisition—not an agent conversation,
browser paint, canonical definition publication, or upstream Datahike runtime.
The namespace failure and interpretation-refusal variant are explicit proof
limits. The D1/one-form issue links above own the performance defects; this note
adds evidence without editing the schedule or another lane's issue files.

Only this document is owned/changed. Net src lines **0**, net test lines **0**.
No publication path changed, so no publication clock or archive load gate applies.
The timing tables and committed forms are the landing evidence for this diagnosis.
Local link checks found no broken document links. The editor's repository-wide
Markdown lint reported pre-existing gitlink citations in other landing notes;
those historical files are outside this lane. They are not validation failures
in this document and were not rewritten.
