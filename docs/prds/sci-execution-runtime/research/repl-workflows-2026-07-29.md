---
type: research
status: active
tags: [research, repl, tooling]
---

# REPL workflows — living in a multi-cluster Seon

What a live Seon system is actually good for as a thinking surface, learned by
using one. Every claim below carries the form that produced it and that form's
real output. Nothing here is remembered; where a claim is source-grounded
rather than probed, it says so.

The lane was chartered to experiment and then re-chartered mid-flight to fix a
real blocker with what it found. Both halves are here, because the second is
the proof of the first: the crash bug in §7 was reproduced deterministically in
milliseconds by the harness in §3, after the first hypothesis about it turned
out to be wrong — and the REPL is what said so.

## Contents

1. The one fact that shapes everything: the process-root flock
2. Cross-cluster: what is genuinely useful
3. The observation harness
4. The feedback loop, measured
5. Sessions: the honest mental model
6. Pitfalls that silently mislead
7. The bug: cold resume, and what the evidence says to do about it
8. What belongs in a skill

---

## 1. The one fact that shapes everything

**A cluster is not a store. A ROOT is a store, and clusters are branches in
it.** `resolve-bootstrap` derives `store-dir` as `<root>/store`
(`src/seon/cluster.clj:118-121`), and that one store is opened under a
lifetime `flock` held by the whole process (`src/seon/cluster/store.clj:188-198`).

Observed, not assumed:

```
$ lsof data/clusters/store.lock
COMMAND   PID USER   FD   TYPE NAME
java    61316 sean   76w   REG  data/clusters/store.lock

$ lsof tmp/repl-experiments/clusters/store.lock
java    92485 sean  172w   REG  tmp/repl-experiments/clusters/store.lock
```

Three consequences that govern every workflow below:

- **Only one JVM may host clusters under a given root.** The owner's JVM holds
  `data/clusters`. A second JVM there does not get a second cluster — it gets a
  refusal. So "start a scratch cluster to experiment" means *either* starting it
  inside the JVM that already holds the root, *or* starting your own JVM on your
  own root.
- **`bin/seon start` and the MCP tool only know `data/clusters`.**
  `mcp__seon_cljs__eval_clj` resolves clusters through advertisements under that
  one root, so a scratch JVM on another root is **invisible to MCP**. This is not
  a bug; it is the flock showing through the tooling. You need your own client
  (§2).
- **Clusters co-hosted in one JVM share a crash domain.** `kill -9` on the JVM
  took both `xp-a` and `xp-b` down together. Sovereign in *data* (separate
  branches, separate connections, no shared mutable state) is not sovereign in
  *process*.

The corollary that makes multi-cluster worth it: **one JVM hosting N clusters
means one REPL connection reaches all N**, because the registry is a
process-global atom (`src/seon/cluster.clj:185`).

```clojure
(sort (keys @@#'seon.cluster/running-instances))
;;=> ("xp-a" "xp-b")
```

Note the double deref: `@#'x` gives you the *atom*, not its value. Getting this
wrong is the first thing everyone does (§6).

## 2. Cross-cluster: what is genuinely useful

Start N clusters in one JVM (`docs/prds/sci-execution-runtime/research/scripts/repl-multi-cluster-2026-07-29.clj`):

```
xp-a     prepl=65072  web=http://127.0.0.1:7895   ready=2650ms
xp-b     prepl=65074  web=http://127.0.0.1:7714   ready=459ms
```

**The second cluster costs a fifth of the first** (459 ms vs 2650 ms; after a
kill-9 reboot, 740 ms / 277 ms). The first pays for class loading and the store
open; a sibling forks the ancestor and reuses the held store. So the cost of
"one more cluster to try this in" is a few hundred milliseconds — cheap enough
that you should never experiment in a cluster you care about.

### The client: `docs/prds/sci-execution-runtime/research/scripts/repl-px-2026-07-29.clj` (referred to below as `px`)

One form to one cluster's io-prepl, EDN out. It resolves a cluster name through
advertisements across **both** roots and **refuses a stale one** (pid liveness),
so a cluster that "looks live" in a directory listing can never be probed by
accident:

```
$ tmp/repl-experiments/px html-twins-20260729 '(+ 1 1)'
px: no advertisement for html-twins-20260729 under [data/clusters tmp/repl-experiments/clusters]
```

It also **trims exceptions to five frames**. This matters more than it sounds:
the raw prepl exception map for one unresolved symbol is ~30 stack frames of
JSON. One careless probe can cost more context than the entire investigation.
Compare the same error before and after:

```
;; raw (abridged — the real thing is ~2 KB)
{:via [{:type java.lang.IllegalArgumentException ...}], :trace [[clojure.lang.RT seqFrom "RT.java" 577] ... 30 frames ...], :cause "Don't know how to create ISeq from: clojure.lang.Atom"}

;; through px
{:cause "Don't know how to create ISeq from: clojure.lang.Atom", :phase :execution,
 :trace [[clojure.lang.RT seqFrom "RT.java" 577] ...4 more...], :elided 18}
```

The cause line was all I needed, both times it happened.

### What multi-cluster is actually FOR

Ranked by how often it earned its keep:

1. **A scratch cluster you may destroy.** By far the biggest win, and it is not
   really about "cross-cluster" at all: it is about never having to be careful.
   I planted fabricated runs, killed the JVM, and rebooted, three times, with
   zero risk to anything.
2. **`observe/diff` — one probe, every cluster, grouped by answer.** This turns
   "is this cluster weird, or is this how Seon is?" into one form. The grouping
   (rather than a per-cluster listing) is the whole ergonomic point: identical
   answers collapse and only divergence is visible.

   ```clojure
   (observe/diff (fn [n] (count (d/q '[:find [?a ...] :where [?a :db/ident _]] (observe/db n)))))
   ;;=> {158 ["xp-a" "xp-b"]}      ; same schema population — nothing to see
   ```

   A result with two keys is a finding; a result with one key is a
   *falsification* of "my cluster is special", which is usually what you
   actually wanted to know.
3. **Comparing against the owner's live cluster read-only.** `default` is a
   fully-populated, long-running system; a fresh scratch cluster is not.
   Diffing schema/config/agent counts between them separates "this is broken"
   from "this is empty".
4. **Watching one while driving another.** Real, but I reached for it least:
   because clusters share no mutable state, cross-cluster interference is
   usually not the hypothesis. Reach for it when the hypothesis is about the
   *process root* — the shared store, the shared executors
   (`src/seon/cluster.clj:158-166`), the work launcher.

**What is merely possible and not useful:** running the same *implementation*
experiment in several clusters to "increase confidence". Clusters are sovereign
by construction, so N clusters give you one result N times. Use the second
cluster for a *different* state, never for a repeat.

## 3. The observation harness

`docs/prds/sci-execution-runtime/research/scripts/repl-observe-2026-07-29.clj` — five lenses, each a function of a cluster
name, all read-only. Load once per JVM (`load-file` interns into the JVM, so
every later connection has it — §5).

### Lens 1: the tower — `(observe/system "xp-a")`

Which boot layers stand. Absence marks where boot stopped: `start!` publishes
the instance *as it stands* at every layer, so a degraded cluster is registered
with its REPL up and its upper keys missing (`src/seon/cluster.clj:843-923`).
This is the first thing to check when a cluster "is up" but nothing works.

### Lens 2: the graphs — `(observe/plumbing …)` / `(observe/agents …)`

`flow/ping` is the best single lens in the system, and it is per-graph. The
cluster's shared plumbing:

```clojure
(observe/plumbing "xp-a")
;;=> {:seon.cluster.agent/armer  {:status :running, :passes 0,
;;                                :state {:passes 0, :armed-count 2},
;;                                :queued {:arm 0}}
;;    :seon.render.web/render    {:status :running, :passes 13,
;;                                :state {:passes 13, :watched-agents 0,
;;                                        :tap-count 0, :streaming-agents 0},
;;                                :queued {:interest 0, :stream 0, :pages 0}}}
```

And every agent's own graph, since every agent is its own flow:

```clojure
(observe/agents "xp-a")
;;=> {"peer" {:seon.cluster.agent/mailbox {:status :running, :passes 3,
;;                                         :state {:deliveries 3}, :queued {:wake 0, :episode 0}}
;;            :seon.cluster.agent/turn    {:status :running, :passes 3,
;;                                         :state {:passes 3, :turns 2}, :queued {:episode 0}}}
;;    "root" {…:turn {:passes 4, :state {:passes 4, :turns 4}}…}}
```

Three questions this answers instantly that facts alone do not:

- **is the proc alive** (`:status`) — a parked agent and a dead one look the
  same in the database;
- **has it moved** (`:passes`) — the honest "did my wake arrive?" check;
- **is anything backed up** (`:queued`) — buffer occupancy. On sliding-1
  channels this is 0 or 1 and a persistent 1 means the consumer is stuck.

The per-agent graphs are **not** reachable through `flow/ping` on the cluster
graph — they are separate graphs held in the routing atom
(`:seon.cluster.agent/routing` → `::armed` → `:seon.flow/graph`). Pinging the
routing entry itself throws `No implementation of method: :ping … for class:
PersistentArrayMap`, which is how I learned this.

### Lens 3: the facts — `(observe/roster …)` / `(observe/last-turn …)`

`roster` is "what agents exist and what is each doing", derived: the presence of
`:seon.cluster.agent/run` *is* busy; there is no status to read.

`last-turn` is the single highest-value form in the whole cookbook — the newest
run's forms beside its receipts, ordinal by ordinal. When an agent "did
nothing", the answer is almost always an error receipt sitting right there:

```clojure
(observe/last-turn "xp-a" "root")
;;=> {:run "crash-suffix-run"
;;    :forms    [{:ordinal 0 :source "(def planted 1)"}
;;               {:ordinal 1 :source "(my.message/send \"peer\" …)"}]
;;    :receipts [{:ordinal 0 :interrupted-at #inst "…"}
;;               {:ordinal 1 :result-edn "{:my.message/to \"peer\", …}"}]}
```

### Lens 4: the transaction stream — `(observe/watch! …)` / `(observe/recent …)`

`watch!` installs a `d/listen` that projects each report to `[e a v added]`
tuples. **The listener is wrapped in a try that swallows** — deliberately.
Datahike fires listeners inside the transaction's critical path, so a throwing
or slow listener stalls the transaction that triggered it
(`src/seon/cluster/wake.cljc:6-25`; a prior probe's 800 ms listener stalled a
live transaction). An observation tool that can break the thing it observes is
not an observation tool.

`recent` is the cheaper 80% case — the attributes committed in the last N
transactions, from history, with nothing installed.

### Lens 5: `diff` — §2.

### Prior art

The apparatus in `agent-flow-render-falsification-2026-07-29.md` (flow/ping per
graph + a `d/listen` observer) is the right shape and this is its packaging:
same two ideas, plus the tower lens, the roster/last-turn fact lenses, and the
cross-cluster fold. Reuse this rather than rebuilding either.

## 4. The feedback loop, measured

**Claim under test:** proc step-fns are referenced as vars (`#'f`), so
re-evaluating a `defn` changes a running proc immediately, and only *topology*
changes need a rebuild.

**It is true, and here is the live proof.** The armer proc is built as
`(flow/var-process #'cluster.agent/armer-step :io …)`
(`src/seon/cluster.clj:646-648`). Swapping that var's root while the graph runs,
then committing a new agent:

```clojure
(def probe (atom []))
(def original seon.cluster.agent/armer-step)
(alter-var-root #'seon.cluster.agent/armer-step
                (fn [f] (fn [& args] (swap! probe conj :stepped) (apply f args))))
;; commit a new agent, wait 400ms, restore
;;=> {:armer-steps-before 0
;;    :armer-steps-after  1
;;    :armed-now ("hotreload-witness" "peer" "root")}
```

The running proc picked up the new root on its very next step — no restart, no
rebuild, and the new agent was armed by the wrapped function. (`alter-var-root`
proves the *indirection*, which is precisely the mechanism a `defn` re-eval
rides. `var-process` refuses a non-var step at construction for exactly this
reason: `src/seon/flow.clj:104-108`.)

**What does NOT take effect without a rebuild:** anything in the graph
*definition* rather than a step body — procs, conns, buffer sizes, workload
tags, and the args map closed over at `create-flow`. Those are data captured
when the definition was built.

**And the rebuild is nearly free.** Measured on a live agent blueprint, 5 runs
each:

```clojure
create-flow : [41.0 9.2 7.3 6.8 6.5] µs      ; pure data, allocates no threads
start       : [121.8 49.7 198.3 56.0 53.6] µs
```

So a full topology rebuild of an agent graph is **~60–210 µs** — consistent
with the ~0.3 ms in the flow research, and firmly in the range where "just
rebuild it" is the correct instinct. Channel contents are losable by
construction, so nothing durable is at stake.

**The loop this implies:**

1. probe the live system for the current fact (`observe/*`);
2. change the `defn` in the file and re-evaluate it into the JVM;
3. re-run *the same probe form* against *the same cluster* and compare;
4. rebuild the graph only when you changed topology;
5. reboot only for boot-order, schema-population, or process-identity changes —
   those genuinely cannot be reached by reload, and §7 is an example of a class
   that only a real reboot exposes.

## 5. Sessions: the honest mental model

Probed, one form each, across two separate socket connections:

```clojure
;; connection 1
(do (def session-witness :interned-in-the-jvm) 42)
;;=> 42

;; connection 2 — a brand new socket
{:def-survived (resolve 'user/session-witness) :star1 (try *1 (catch Throwable t :unbound))}
;;=> {:def-survived #'user/session-witness, :star1 nil}
```

**A `def` is JVM state, not session state.** It is interned into a real
namespace, so it survives every reconnection. Only the per-connection bindings —
`*1`/`*2`/`*3`, `*ns*`, dynamic bindings — are session-local.

That gives the three-tier model, each tier verified in this lane:

| operation | what is lost | what survives |
|---|---|---|
| new connection / session restart | `*1`-`*3`, `*ns*`, dynamic bindings | every `def`, every `load-file`, all cluster state |
| JVM restart (incl. `kill -9`) | all vars, all sci ctxs, all channel contents, the graphs | **every committed fact** (proven in §7: receipts, runs and messages all survived a `kill -9`) |
| cluster reset | the facts | the code |

**When a named session earns its cost: rarely.** Because `def` and `load-file`
persist anyway, the only thing a named session buys is `*1`-chaining. Prefer a
`def` with a real name — it is visible to every later probe, to other lanes,
and to you after a compaction, and it cannot be lost by a session restart you
did not notice. Reach for a named session only when a form is genuinely
expensive to name and you are about to use it two forms later.

The practical corollary: **`load-file` your probe namespace once and call it
from every later connection.** That is what makes `observe.clj` usable from a
one-shot client like `px` at all.

## 6. Pitfalls that silently mislead

Ordered by how much time each cost me.

1. **`@#'x` is the var's value; `@@#'x` is the atom's.** Deref a private atom
   var once and you get the atom, and `keys` on it throws a confusing ISeq
   error. Cost: one full exception dump into context.
2. **An exception is thousands of tokens.** A raw prepl exception carries the
   full `:trace` plus `:via`. Always go through a client that trims (§2), and
   never `pull` an entire instance/graph/database to "see what's in it" — ask
   `(keys …)` first. The `:seon.config/on-core-error :record` dial makes this
   worse: a refused Datahike transaction logs the entire error *and* the args
   map, which in my case included the whole tx-data.
3. **`clojure.main` eats your first argument.** `clojure -M:dev -i script.clj a
   b c` silently treats `a` as a *script path to run*, so `*command-line-args*`
   is `("b" "c")`. My first 3-cluster launch quietly started 2 clusters and
   printed no error, because the `-i` script blocked on `@(promise)` before the
   main-opt ran. Use `-- a b c`. **Diagnostic habit:** always print what you
   actually started (`REGISTRY (xp-b xp-c)`), never trust what you asked for.
4. **Shell quoting mangles reader macros.** `'(… @#'x …)'` and `#(…)` inside
   shell quotes turn into something else — `#'` became an anonymous fn in one
   probe. **Send every non-trivial form on stdin via a heredoc**
   (`px cluster <<'EOF' … EOF`). This is the single highest-yield habit in this
   document.
5. **A stale advertisement is not a cluster.** `data/clusters/<name>/prepl.edn`
   outlives its JVM. `read-advertisement` checks (pid, start-instant) liveness
   for exactly this reason (`src/seon/cluster.clj:1195`) — your client must too,
   or you will connect to a *recycled* pid.
6. **A live armed system reacts to your hand-planted facts.** I planted a run
   with a receipt id `crash-e-0`; 240 ms later the *live armer* had woken the
   agent and the turn had created its own receipt for the same ordinal under its
   derived id `["crash-suffix-run" 0]`. I nearly filed a duplicate-receipt bug.
   **When you plant facts into a live cluster you are racing it.** Either accept
   that (it made my reproduction *more* faithful) or plant into a memory
   database with no graph armed.
7. **`tx-meta` lookup refs resolve against db-before.** Referencing a message
   created in the *same* transaction fails with `Nothing found for entity id`.
   Split the transaction.
8. **A `d/listen` observer runs inside the transaction's critical path.** Slow
   or throwing listeners stall the writer. Total and fast, always (§3).
9. **Fixture facts are not boot facts.** My first probe failed because I had not
   created the agent's `:seon.ns` entity, which production's `run/plan-call`
   upserts. A fixture that hand-lists its facts will diverge from the live boot
   path — which is exactly why schema, acquisition and process changes need a
   real reset-boundary proof, not a fixture.

## 7. The bug: cold resume, and what the evidence says

Assigned issue:
`docs/seon/issues/cold-resume-loses-the-defs-and-aliases-the-plan-prefix-established.md`.
Mid-lane, the graduation re-audit filed
`boot-recovery-executes-unstarted-plan-suffix-after-interruption.md`, which
turns out to be the same defect seen from the other side.

### 7.1 The first hypothesis was wrong, and the REPL said so in one form

I reasoned from the source that a failed form breaks the fold, so cold resume
would reproduce in-process with no crash. Driven live
(`docs/prds/sci-execution-runtime/research/scripts/cold-resume-probe-2026-07-29.clj`, plan `["(def x 41)" "(boom)"
"(inc x)"]`):

```clojure
{:passes [[:resume 0 :released] [:close nil :closed]]
 :receipts ({:ordinal 0 :result-edn "…Var #'my.agents.agent-a/x"}
            {:ordinal 1 :error "Unable to resolve symbol: boom"}
            {:ordinal 2 :result-edn "42"})}
```

**All three ordinals ran in one pass and ordinal 2 returned 42.** A red form
does *not* stop the fold — `next-ordinal` branches on the *transaction*
outcome, not the evaluation (`src/seon/cluster/loop.cljc:1053-1062`). That also
answers the sibling issue `a-failed-form-does-not-stop-the-fold.md` empirically:
it does not, today, measured.

Cost of falsifying a wrong plan: one form, about two seconds. This is the
argument for the whole document.

### 7.2 Reproducing it properly — without killing anything

The real precondition is not a crash, it is a fold *starting mid-plan with a
fresh ctx*. That state is just facts: terminal receipts on the prefix. So
`settle!` pre-stamps them, and the drive is a genuine cold resume in
milliseconds:

```clojure
;; A — lost def
(run! ["(def x 41)" "(inc x)"] [0])
;;=> ordinal 1: :error "Unable to resolve symbol: x"

;; B — lost require/alias (the reading half)
(run! ["(require '[clojure.string :as s])" "(s/upper-case \"hi\")"] [0])
;;=> ordinal 1: :error "Unable to resolve symbol: s/upper-case"

;; C — independent forms
(run! ["(+ 1 1)" "(+ 2 2)"] [0])
;;=> ordinal 1: :result-edn "4"      ← executed fine. And that is the problem.
```

Case C is the one that matters. It is not a failure; it is the audit's bug.

### 7.3 The `kill -9` falsifier, with an observable capability

`docs/prds/sci-execution-runtime/research/scripts/crash-suffix-falsifier-2026-07-29.clj`, on scratch cluster `xp-a`. A two-form
plan; ordinal 1 is `my.message/send`, which commits a durable message row — so
"did the suffix execute?" is a query, not an inference.

Before the kill (pid 90996):

```clojure
{:receipts ({:ordinal 0})            ; running, no terminal fact
 :run {:process "90996-1785353285188"}
 :messages-to-peer []}
```

`kill -9 90996`, reboot the same root and cluster name:

```clojure
{:receipts ({:ordinal 0 :interrupted-at #inst "2026-07-29T19:33:34.693Z"}
            {:ordinal 1 :result-edn "{:my.message/to \"peer\", :my.message/content
                                      \"THE SUFFIX EXECUTED AFTER THE CRASH\"}"})
 :run {:closed-at #inst "2026-07-29T19:33:35.586Z"}
 :agent-still-points? false
 :messages-to-peer ["THE SUFFIX EXECUTED AFTER THE CRASH"]}
```

**Confirmed, with a real post-crash effect.** Boot recovery correctly stamped
`interrupted-at` on ordinal 0 — and then executed ordinal 1, which had never
started, and committed a message that did not exist before the kill. The audit
is right, and its capability criterion is met by evidence rather than by
argument.

(Bonus fidelity: the plant *woke the live agent*, so ordinal 0 was genuinely
mid-execution when I killed it — see pitfall 6.)

### 7.4 The two issues are one defect

They are the same mechanism seen from two sides:

- **cold-resume** says: the suffix runs in a ctx that lost the prefix's defs,
  requires and aliases, so it fails through no fault of the agent.
- **suffix-execution** says: the suffix should not be running at all.

Both descend from one decision: `next-ordinal` treats an `interrupted-at`
receipt as settled and hands the fold the next ordinal
(`src/seon/cluster/work.cljc:98-126`), and `next-agent-work` picks up an
*unheld* planned run (`work.cljc:521-526`).

### 7.5 Recommendation: dissolve, do not patch

**A plan is not a list of independent forms; it is a fold over one ctx.** Its
meaning is the sequence. A fold that starts at ordinal k > 0 in a fresh ctx is
therefore *executing a different program than the agent authored* — and cases A,
B and C above are the two ways that goes: it fails confusingly, or it succeeds
and does something in an environment nobody authored.

Every alternative fix is worse:

- **Replay the prefix** — forbidden by the crash model, and dishonest: form 1's
  `(def x (fetch …))` is not reproducible.
- **Persist the ctx** — a sci env holds closures and host objects; there is no
  honest serialization, and it violates derive-don't-store.
- **Attribute the failure** (my original design: derive from the durable
  sources what the lost prefix would have installed, and name the ordinal in
  the error) — this satisfies the cold-resume issue's criteria exactly and
  executes nothing. **But it is a patch on a mechanism that should not run**,
  and it does nothing for case C, which is the safety hole.

The dissolution is a **derived, presence-based rule with no flag, no clock and
no process comparison**:

> A plan may only be executed by a fold that starts at its FIRST ordinal.
> An open planned run whose first unsettled ordinal is not its first ordinal is
> not work — it is an interruption to settle.

Properties: cold resume becomes **unrepresentable** rather than handled; both
issues close; no capability request from an interrupted plan can ever fire
post-crash; and it reuses machinery that already exists — `work/interruption`
already returns runs to bury, and `loop/settle-interruption!` already buries
them (`src/seon/cluster/loop.cljc:529`). It is consistent with the doctrine
`my.run/wait` already documents ("the fresh run has a fresh sci ctx, so no def
survives… what resumes is the AGENT, on its next trigger") and with N3's own
falsifier ("kill -9 mid-turn — next wake shows the one interrupted warning and
**the agent adapts**"). The cost is a lost paid model call, which the crash
model already accepts by ruling ("A lost call is lost; the agent is told").

### 7.6 Why I did not land it — an owner ruling, plainly

I stopped at the design for two reasons, and I want them on the record rather
than implied:

1. **The rule's home is `src/seon/cluster/work.cljc`**, which is outside this
   lane's ownership (loop / run / sci.eval). The predicate could live in
   `run.cljc` as run-model business, but `next-agent-work` must call it, and
   splitting the decision — work says `:resume`, the loop refuses — would be a
   second decision site and exactly the one-mechanism violation the house laws
   name.
2. **This reverses a deliberate, documented, sealed contract.** `work.cljc`'s
   crash walk explicitly designs suffix continuation and reasons about it;
   `loop_test.clj:326-337` (`an-interrupted-form-is-never-re-executed`) asserts
   ordinal 1 follows an interrupted ordinal 0; `turn_test.clj:1651-1659`
   describes completing remaining planned work after recovery. Changing it
   deletes those oracles. Two blocker issues and a graduation audit hang on the
   answer. That is a surface-contract ruling, not a lane's judgment call.

**Recommendation to the owner: adopt the dissolution.** Exact edit list:

- `work.cljc` — `fold-or-close` yields `:resume` only when the first unsettled
  ordinal is the run's first ordinal; `interruption` additionally returns an
  open planned run whose fold cannot start at its first ordinal.
- `loop.cljc` (mine) — `settle-interruption!` accepts a planned run; the
  `:resume` arm loses its cold branch entirely.
- tests — delete `an-interrupted-form-is-never-re-executed`'s ordinal-1
  expectation and the turn_test recovery-completion case; replace with **one**
  recurring regression for the class: a two-form plan with an
  observable-capability suffix, interrupted, asserting **zero** post-recovery
  receipts and **zero** post-recovery message rows. `plant_crash.clj` is that
  test's body; it needs only the fixture cluster.
- archive `cold-resume-…md` as **superseded** by the dissolution, not fixed.

`docs/prds/sci-execution-runtime/research/scripts/{cold-resume-probe,crash-suffix-falsifier}-2026-07-29.clj` are the reproductions
for whoever lands it; both are committed.

**No `src/` or `test/` file was changed by this lane.** The gate is at its
baseline.

## 8. What belongs in a skill

For the `repl` skill rewrite, in priority order:

- **§6 pitfalls 1, 2, 3 and 4** — verbatim. Especially *send forms on stdin, not
  through shell quotes* and *never print a raw exception or a whole instance*.
  These are pure loss-prevention and cost nothing to teach.
- **§5's three-tier table** and the ruling that **a `def` beats a named
  session**. This corrects a real misconception about what sessions buy.
- **§4's loop** (probe → re-eval defn → re-run the same probe → rebuild only for
  topology → reboot only for boot/schema/process) with the two measurements.
- **§1's flock consequence** — "one JVM per root; MCP only sees
  `data/clusters`" — belongs wherever cluster lifecycle is taught, because it is
  the reason a scratch cluster sometimes cannot be started at all.
- **§3's lens table** (which lens answers which question) belongs in
  `seon-flow-architecture` next to the ping section rather than in `repl`; the
  `observe.clj` functions themselves should graduate to real code under a
  `seon.dev.*` namespace if they are to be maintained — a `tmp/` file is a
  probe, not a tool.

Not skill material: §2's cross-cluster ranking and §7 — the first is judgment
that will shift as the system grows, the second is this week's issue.
