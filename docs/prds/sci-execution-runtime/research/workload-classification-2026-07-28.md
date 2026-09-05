---
type: research
status: active
tags: [research, agent, runtime]
---

# Derived workload classification: per-function `:io`/`:compute` scheduling

Owner's question (2026-07-28): can we schedule `:io` vs `:compute` per
function — annotate key functions with metadata, derive everything else
from the call chain, io+compute in one chain means `:mixed` — without
"complicating the shit out of everything"?

## Verdict

Per-function **scheduling** is not a substrate feature; per-function
**classification** is, and it is a ~15-line pure query over facts Seon
already indexes. The substrate schedules per *proc* (and per explicit
executor hop); the derivation classifies per *function chain* and tells
each call site which of the two existing hops to take. The owner's
mixed rule and the fail-closed default encode directly. Nothing new is
built — the bounded work launcher (`seon.flow/submit!!`) and the `:io`
run loop already are the two destinations.

## 1. What the substrate actually supports

Sources: `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-111`,
`.../flow/impl.clj:52-56,148,244-323`, `src/seon/flow.clj`.

**The tag attaches per proc, not per function.** `flow/proc` resolves
`workload (or workload (:workload desc) :mixed)` once at proc
construction (`flow/impl.clj:246-247`). The three behaviors:

- `:mixed` (default) — the proc's entire loop (`alts!!` + transform)
  runs on the `:mixed` executor, an unbounded cached **platform**
  thread pool (`dispatch.clj:71-73`). Guarantee: none. It merely
  isolates unknown code from the other two pools.
- `:io` — the loop runs on the `:io` executor: **one virtual thread
  per task** when available (`dispatch.clj:83-90`; probe confirmed
  `isVirtual = true`). Blocking parks the carrier; effectively
  unbounded concurrency for waiting.
- `:compute` — the loop itself runs on `:io`; **each transform
  invocation** is futurized onto the `:compute` executor and awaited
  with `.get(compute-timeout-ms)` (default 5000 ms)
  (`flow/impl.clj:258-261`). Two teeth in that detail: (a) the
  timeout throws into the proc loop but **never cancels** the compute
  task — a wedged transform still occupies its thread; (b) stock
  `:compute` is an unbounded **cached** pool (`dispatch.clj:71-73`,
  probe: `ThreadPoolExecutor`, platform thread `async-compute-1`) —
  "bounded ≈ cores" is Seon's own doing, via the `:compute-exec`
  override that `create-flow` accepts (`flow/impl.clj:52-56`) and
  `seon.flow/bounded-platform-executor` supplies
  (`src/seon/flow.clj:319,336`).

**Finer than a proc is an explicit executor hop**, and Seon already
owns both hops:

- the run-loop proc is `{:workload :io}` — load-bearing, it blocks on
  the model call (`src/seon/cluster/loop.cljc:21,420,739`);
- an eval hops to `seon.flow/submit!!`, which is backpressure (a
  fixed-buffer channel) plus the bounded compute executor
  (`src/seon/flow.clj:386-420`).

So the honest unit is: **the proc hosts a chain; a classified segment
of the chain hops executors explicitly.** "Per-function scheduling" in
the literal sense (the runtime migrating a running call between pools
at each frame) does not exist on the JVM and would be the complication
the owner fears. What is real: per-function *classification* deciding,
at the one seam where work is submitted, which hop it takes.

## 2. The derivation: metadata at the edges, a query everywhere else

Seon's law already says it: scheduling is core.async's own enum,
DERIVED never declared; `plan-execution` computes placement from the
indexed call graph. The classification is the same computation with a
two-symbol answer.

**Where the metadata lives.** Key functions — the capability leaves —
carry ordinary defn metadata:

```clojure
(defn ^{:seon.workload :io} fetch-page ...)   ; fs/web/llm/db leaves
(defn ^{:seon.workload :compute} score-tokens ...) ; hot pure kernels
```

The indexer already lifts defn facts into `:seon.fn` rows
(`src/seon/schema.cljc:512-527`); `:seon.fn/workload` becomes one more
optional attribute (`[:enum :io :compute]`), populated from var meta at
index time exactly like `:seon.fn/doc`. It is NOT required anywhere —
the owner's constraint. In practice almost no annotations are needed:
the fs/web/llm/db capability leaves are a closed, known set behind the
one bounded evaluation (`seon.effect`), and pure corpus code needs no tag
because purity is already computed (below).

**The propagation rule** (owner's rule encoded, fail-closed per
existing law): collect the leaf tags reachable from the root through
call edges —

- only `:compute` reachable → `:compute`;
- only `:io` reachable → `:io`;
- both in one chain → `:mixed`;
- any unresolved edge (dynamic call, open higher-order, unknown
  symbol) → `:mixed`.

**The edges and the uncertainty signal already exist in the quarry.**
`src-old/seon/program/edge.cljc` stores per-fn `::calls [:set :string]`
(line 11), an `::effect` enum `[:pure :read :idempotent :external]`
(line 30), and `::uncertainties` naming exactly the fail-closed cases —
`:dynamic-call`, `:open-higher-order`, `:unresolved-symbol` (lines
16-24). The fresh `:seon.fn` schema has `:seon.fn/read-attrs` but not
yet the call edges; N5 corpus work re-lands them. Given edges, the
classifier is a reachability fold — probed live (2026-07-28,
`clojure -M:dev`, probe source preserved below):

```clojure
(defn classify [edges tags root]
  (loop [seen #{} frontier [root] found #{}]
    (if-let [f (first frontier)]
      (cond
        (seen f) (recur seen (rest frontier) found)
        (tags f) (recur (conj seen f) (rest frontier) (conj found (tags f)))
        (nil? (edges f)) (recur (conj seen f) (rest frontier)
                                (conj found :mixed))
        :else (recur (conj seen f) (into (rest frontier) (edges f)) found))
      (cond
        (:mixed found) :mixed
        (= found #{:io :compute}) :mixed
        (= found #{:io}) :io
        (= found #{:compute}) :compute
        :else :mixed))))
```

Probe results: `summarize-run` (calls one `:io` leaf and one
`:compute` leaf) ⇒ `:mixed`; `plan-only` (reaches only `:compute`) ⇒
`:compute`; `fetch-page` ⇒ `:io`; `spooky` (edge to an unknown symbol)
⇒ `:mixed`. Reading the tag back is `(:seon.workload (meta #'f))` —
confirmed. **It is a query, not a framework.** In production it is one
Datalog rule over `:seon.fn/calls` + `:seon.fn/workload`, memoized per
corpus basis and recomputed on the same tx that reindexes a changed
namespace — hot reload is free because the classification is derived,
never stored on the consumer.

**A refinement worth taking:** treat the quarry's `::effect :pure` as
an implicit `:compute` tag. Then a chain proven pure and
capability-free classifies `:compute` with zero annotations, and
explicit `^{:seon.workload ...}` remains only for the genuinely
ambiguous key functions the owner wants to mark. Same rule, fewer
tags.

## 3. Sci-eval'd agent code: the split already exists

For agent code the classification is not even needed per function,
because the architecture already forces the shape:

- every sci eval runs on a `:compute` platform thread under the one
  `:interrupt-fn` with `time-limit` (`src/seon/sci/eval.clj`,
  `docs/seon/architecture/agent-runtime.md:140-150`), entered through
  `seon.flow/submit!!` — that IS the `:compute` hop;
- any genuine capability (fs, web, llm, db) leaves the eval as a
  request through the one bounded evaluation (`seon.effect`) and is serviced
  by the run loop / capability leaf on `:io` — that IS the `:io` hop.

So an agent chain that is pure runs entirely on `:compute`; the moment
it touches the world it *structurally* becomes the io-hop; a turn is,
in the owner's vocabulary, `:mixed` by construction, with each segment
already on the right pool. The derivation's real customers are (a)
`plan-execution` deciding admissibility/placement of contract
predicates (pure + capability-free ⇒ safe on `:compute`), and (b)
first-party proc authors picking `{:workload ...}` from evidence
instead of vibes.

## 4. Honest limits

**No preemption.** A fn classified `:compute` that secretly blocks
poisons the bounded pool: probed — a trivial task submitted behind 8
sleepers on a bounded-8 pool waited **301 ms** (the full sleep), pure
queue wait. The classifier reduces the odds; it cannot make the class
unrepresentable, because classification is static and blocking is
dynamic.

**The cheap detectors, all existing mechanisms:**

- `:seon.eval/fn-entries` is already the recorded diagnostic: ~0
  entries over a long wall interval = blocked in a host call on
  `:compute` (the exact case the `:interrupt-fn` cannot see —
  `src/seon/sci/eval.clj:26-35`); huge entries = a spin. Both are
  facts to commit, not limits.
- `seon.flow` already accounts occupancy: `capacity-facts` publishes
  `::available-capacity` and `::wedged-submissions`
  (`src/seon/flow.clj:89-112`), and `submit!!` marks a submission
  wedged when its `time-limit` fires (`src/seon/flow.clj:406-414`).
  `::submission-wait-ms` is the queue-depth watermark: a rising wait
  with full capacity says the pool is poisoned — commit it as a fact
  and let a render surface it. No new machinery.
- A wedged `:compute` thread is *lost* until its host call returns
  (flow's `.get` timeout abandons, `shutdownNow` merely interrupts).
  The recovery is the existing one: replace the launcher
  (`install-work-launcher!` swaps and `shutdownNow`s the old executor,
  `src/seon/flow.clj:357-366`), never per-task murder.

**Massive parallelization shape** (hundreds of concurrent agent turns,
mostly model-call waiting punctuated by short evals) — probed:

| load | bounded-8 platform pool | virtual threads (`:io`) |
|---|---|---|
| 200 × 100 ms blocking | 2583 ms | 107 ms |
| 1000 × 100 ms blocking | — | 105 ms |

Blocking on a bounded pool serializes 25×; on virtual threads, 1000
concurrent waits cost the same wall time as one. The optimal shape is
therefore exactly what is already built, applied consistently:

- **turns live on `:io`** (run-loop procs, capability leaves, SSE,
  model calls) — virtual threads make "hundreds of turns" a non-event;
- **evals and hot pure kernels hop to the one bounded `:compute`
  launcher** (≈ cores, platform threads, `:interrupt-fn`,
  fixed-buffer backpressure) — more than ≈ cores of CPU work buys
  nothing but context-switching, so the queue, not more threads, is
  correct;
- **`:mixed` stays what it is**: the fail-closed default for chains
  the graph cannot resolve — quarantine, not a strategy.

## 5. The one thing the owner should decide

Whether `::effect :pure` (already computed by the edge indexer) counts
as an implicit `:compute` tag, so annotations shrink to the handful of
genuinely ambiguous key functions — recommended, since it derives more
and declares less — or whether `:compute` must always be explicit
opt-in metadata. Everything else falls out of mechanisms that already
exist.

## Appendix: probe

Run 2026-07-28 via `clojure -M:dev -i <probe> -e '(println :probe-done)'`.
Full source lived at `tmp/workload-classification-probe.clj`; the
decisive forms are inlined above (classifier) and here (saturation):

```clojure
(defn saturate [pool n sleep-ms]
  (let [latch (java.util.concurrent.CountDownLatch. n)
        t0 (System/nanoTime)]
    (dotimes [_ n]
      (.execute pool (fn [] (Thread/sleep (long sleep-ms))
                       (.countDown latch))))
    (.await latch)
    (/ (- (System/nanoTime) t0) 1e6)))
;; bounded-8, 200 x 100ms block => 2583 ms
;; virtual,   200 x 100ms block => 107 ms
;; virtual,  1000 x 100ms block => 105 ms
;; trivial task behind 8 blockers on bounded-8 => 301 ms queue wait
;; io thread isVirtual => true; compute thread => async-compute-1 (platform)
;; classify: summarize-run => :mixed, plan-only => :compute,
;;           fetch-page => :io, spooky => :mixed
```
