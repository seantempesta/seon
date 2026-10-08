---
type: research
status: proposed
created: 2026-10-08
tags: [sci, forks]
---

# SCI forks, Var identity, and branch redefinition

Research lane `sci-vars-sol`, 2026-10-08. Documentation only.

Seon shares analyzed SCI code, forks its namespace environment, and copies inherited
Vars when a branch writes them. The copy changes the namespace binding but leaves
the references inside shared code pointing at the previous Var. The right composition
is shared code and stable Var identity, with bindings selected by the executing SCI
context. SCI should own that selection; Seon should own program acquisition and evidence.

**Recommendation:** pursue a Var-focused SCI change with stable handles and context-owned
bindings, starting with persistent binding values so ordinary forks stay cheap. Assess
the existing upstream prototype before designing it. Centralized re-analysis is a
bounded interim repair; cloning namespace Vars alone does not solve the problem.
Neither changing `sci.core/fork` alone nor moving the copy into analysis removes all
of Seon's repairs.

## Scope, authority, and observed installation

Read AGENTS.md end to end, especially “The program is entities,” “An agent's context,”
and “Read the dependency first”; the REPL, data-oriented Clojure and testing skills;
[branch-tests notes](../branch-tests/notes.md); commits `9aea3140779faf999e41bd5d14fd5c63e070e8a2`
and `f0652b92a573380f186bceb9121bceb4853b9821`; and both task entries in
resources/seon/tasks/seon.sci.eval.edn:

- `seon.sci.eval/a-def-re-evaluated-in-a-fork-names-the-inherited-var-in-its-body`.
- `seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee`.

The SCI gitlink and checkout both name `97de20044082cb5fed710aed54d7220296c816da`,
branch `seon-env-hook`. The local copy-on-write change is `72150fd44c81a405387cc1aea1e4ea86247b8420`;
metadata ownership and consecutive inherited-handle writes received subsequent fixes.
These are maintained-fork mechanisms, not upstream guarantees.
An installed JVM probe resolved sci/core.cljc under
data/source/pins/reference-code-sci/97de20044082cb5fed710aed54d7220296c816da,
confirmed the one-argument fork, and found all three Seon re-analysis Vars.
It answered in 1 ms (`result/e4431a8429663`); checkout and installed pin agree.

At entry, `bin/seon status` and MCP `runtime_status` reported default **faulted**,
PID 78227, start instant 2026-10-08T11:49:51.713Z, committed source archive
`ade6e305919add23890098150bab1f5753618b62`. It reported one failed test, five open
faults, and performance warnings. The live task records the model agent
`live-flash-2` calling the corrected callee while its automatic proof repeatedly
called the old one after `9aea31407`. This lane did not repeat that model run or
claim that its failure was fixed.

All runtime evidence below came through MCP `eval_clj`, **JVM mode**, agent
`sci-vars-sol`, cluster `default`, using disposable local `sci.core/init` contexts.
No application connection was written by a probe, no default agent's SCI context
was touched, no source was loaded, and default was never restarted. MCP records
the research agent's ordinary observations. The scratch contexts became unreachable
after each form; returned evidence contains only small values.

## What SCI actually resolves

| Seam at the pinned revision | Inputs and guarantee | When work happens; cost |
|---|---|---|
| `sci.core/init`, `sci.impl.opts/init`, `sci.impl.opts/init-env!` | Options, optionally an existing `:env` atom. An explicitly shared atom means shared namespace state. | Initialization merges configuration. Sharing `:env` does not create a branch. |
| `sci.core/fork` | A context; creates another env atom over the held persistent value and assigns a new generation. Namespace maps, Var objects, roots, namespace objects and referenced user objects initially remain shared. | Per fork, O(1) in program size. No walk of 5,700 functions. |
| `sci.impl.resolve/lookup*`, `sci.impl.resolve/resolve-symbol` | Analysis context and symbol. Resolve locals, aliases/refers, namespace entries, classes and permissions. | Symbol resolution during analysis. Alias spelling is resolved then, not repeated at every call. |
| `sci.impl.analyzer/analyze`, `sci.impl.analyzer/return-call` | Resolved Var and analyzed children. Ordinary value reads capture that Var and dereference it; ordinary JVM call nodes invoke the captured Var. | Analysis captures identity; execution reads that object's current root. Built-in fast paths, constants and macros have separate analysis behavior. |
| `sci.impl.analyzer/analyze-var` | `(var name)`; returns the resolved Var as the analyzed value. | Analysis captures the actual object, including in a deftest's self reference. No namespace lookup at execution. |
| `sci.lang/->Var`, `sci.impl.vars/getRawRoot`, `sci.impl.vars/get-thread-binding` | SCI's own Var stores a mutable root and metadata; deref consults its thread binding when applicable, otherwise its root. Var invocation dereferences it. | Per read/call: direct object access, no fork namespace lookup. Dynamic bindings use Var identity. |
| `sci.impl.analyzer/init-var!`, `sci.impl.analyzer/analyze-def` | Definition name and form. Create an unbound Var if absent, but keep an existing inherited Var before analyzing the initializer. | Once per definition, before analysis of the body. This is too early to use the later owned copy. |
| `sci.impl.evaluator/eval-def` | Evaluated initializer and metadata. Copy an inherited ordinary Var into the current generation, publish it, then bind its root. | After initializer analysis/evaluation. The body can already contain references to the old Var. |
| `sci.impl.utils/owned-var`, `sci.impl.utils/bind-root!`, `sci.impl.namespaces/sci-intern` | Context and Var/name/root. Context-aware writes copy an inherited Var or reuse its already-owned replacement. | First write allocates a Var and persistent map path; subsequent writes reuse the owned Var. Reads through the old handle are not redirected. |

`sci.impl.fns/fun` and `sci.impl.fns/gen-fn` also capture the function-creation
context and evaluate the body with it. A proposed late-binding node that simply
looks up `(:env ctx)` can therefore still look in the **base** when an inherited
function executes. Execution-context selection is part of the fix.

There is already an appropriate entry seam: `sci.impl.interpreter/eval-form` binds
the evaluating context through `sci.ctx-store/with-ctx`. Runtime `resolve`, implemented
by `sci.impl.namespaces/sci-resolve`, uses `sci.ctx-store/get-ctx`. A scratch inherited
caller explicitly using `(resolve 'g)` saw the child's redefinition, although the
ordinary analyzed `(g)` caller did not. This demonstrates the missing distinction;
rewriting Seon's functions to call `resolve` would be another workaround.

### Root cause and two different failures

In base, h's analyzed body contains Var G0. Immediately after fork, both namespace
tables also name G0. Defining g in the child installs G1 in the child's namespace
table. A newly analyzed `(g)` resolves G1; inherited h still invokes G0. Base and
sibling isolation on a **child write** succeeds, while redefinition propagation fails.

The second failure is inside a newly redefined body: `init-var!` retains T0;
analysis of `(var t)` embeds T0; `eval-def` then installs T1. A real Clojure `deftest`
uses that self reference to dispatch through its Var's `:test` metadata. Calling
the new test as a function can consequently run T0's test body, while handing T1
to `clojure.test/test-var` runs the new metadata body. Pre-owning the definition in
`init-var!` fixes this particular failure, but leaves inherited h pointing at G0.

Copying is shallow in another significant sense: a parent that still owns G0 can
mutate it after a child fork. An inherited child reference then sees that parent's
later root. The present fork is not an immutable snapshot of all Var bindings.
Freezing an accepted base avoids this operationally; a general snapshot guarantee
requires immutable binding values or ownership protection on **both** sides.

## Measured reproductions

The essential first probe is reproducible as one local JVM form:

```clojure
(let [base (sci.core/init {})
      _ (sci.core/eval-string* base
          "(defn g [] :base) (defn h [] (g))
           (defn self [] #'self) (defn value [] g) (defn handle [] #'g)")
      child (sci.core/fork base)
      sibling (sci.core/fork base)
      _ (sci.core/eval-string* child
          "(defn g [] :fork) (defn self [] #'self)")
      copied (sci.core/fork base)
      _ (doseq [[n entries] (:namespaces @(:env copied))
                [_ v] entries
                :when (and (= n 'user) (sci.impl.utils/var? v))]
          (sci.core/bind-root! copied v @v))
      _ (sci.core/eval-string* copied "(defn g [] :clone)")]
  {:child (sci.core/eval-string* child
            "[(g) (h) ((value)) ((handle)) (identical? (self) #'self)]")
   :base (sci.core/eval-string* base "[(g) (h)]")
   :sibling (sci.core/eval-string* sibling "[(g) (h)]")
   :copied (sci.core/eval-string* copied "[(g) (h)]")})
```

Observed data: child `[:fork :base :base :base false]`, base and sibling
`[:base :base]`, copied `[:clone :base]`. The original, slightly larger form
answered in 5 ms, result `result/e5b78b0517e63`; no error or diagnostic was returned.

A second 2 ms probe (`result/e6dfa0ae4b87b`) defined a captured function value,
a Var handle, and a closure calling g, then redefined g in child and parent.
Child answered `[:child :base :parent-after-fork :parent-after-fork]` for
`[(g) (captured) (handle) (closure)]`. The captured **function value** staying old
is ordinary Clojure behavior; the Var handle and closure following the wrong
branch are the defect. In an unforked context, unmap/reintern answered
`[:new :old :old false]` for new g, inherited h, saved old Var, and identity:
a correct repair must retain this distinction. The same probe confirmed two
`sci.core/init` calls with one `:env` atom expose each other's definitions.

The actual deftest probe, 5 ms (`result/eea9294f2e2b4`), used a local note collector
and the host Clojure test namespace copied into a fresh SCI context:

```clojure
(let [notes (atom [])
      n (sci.core/create-ns 'clojure.test)
      base (sci.core/init
             {:namespaces
              {'clojure.test (sci.core/copy-ns clojure.test n)
               'user {'note (fn [x] (swap! notes conj x))}}})
      _ (sci.core/eval-string* base
          "(require '[clojure.test :refer [deftest]])
           (deftest check (note :base))")
      child (sci.core/fork base)
      _ (sci.core/eval-string* child "(deftest check (note :child))")
      direct (binding [clojure.test/*report-counters*
                       (ref clojure.test/*initial-report-counters*)]
               (sci.core/eval-string* child "(check)") @notes)
      _ (reset! notes [])
      runner (binding [clojure.test/*report-counters*
                       (ref clojure.test/*initial-report-counters*)]
               (clojure.test/test-var (sci.core/resolve child 'user/check))
               @notes)]
  {:direct direct :runner runner})
```

Observed `{:direct [:base], :runner [:child]}`. This is a dispatch probe, not a
passing regression test: it deliberately uses notes rather than assertions.
The inherited explicit-resolve probe answered `[:child :child]` in 1 ms,
`result/e3033f3f3d3ff`, including a host invocation explicitly scoped to child.
The MCP replies did not expose allocation figures; the allocation evidence below
comes from a thread allocation counter, not a guessed reply field.

## Ranked options and their prices

Let V be namespace Vars (the requested scale is about 5,700 functions; actual V
also includes tests and other definitions), R executed Var reads/invocations,
K changed bindings, C distinct reached callers/tests, E traversed dependency edges,
and S the source analyzed for those declarations. Costs below describe **additional**
binding work, excluding the existing read/record/print path and test execution.

| Rank / option | Guarantee | Cost per eval request | Cost per fork | Sacrifice |
|---|---|---|---|---|
| 1. (d) Centralize reached-code re-analysis; simplest viable interim repair for stored named declarations | Named source callers/tests in every receiving context resolve its current Vars, if reach and reconstruction are complete. Pre-own before analyzing each replacement. | On K changes, O(E + S) for the union of reach; deduplicate across changed seeds. No-change requests need no repair. | SCI fork stays O(1); a receiving fork with displaced bindings also pays O(E + S). | Re-executes declaration initializers/macros, replaces closures and requires complete reach. Cannot retarget escaped anonymous closures or saved Var handles by re-analyzing named rows. |
| 2. (c) Stable SCI Var handles, context-owned bindings — **recommended** | Inherited analyzed calls, value reads, self references, metadata and saved Var handles use the executing branch's binding of the same identity. Parent and sibling remain independent. Captured function values remain values. | Persistent implementation: O(R) binding lookups plus O(K) bounded-depth persistent updates; no reverse-closure repair for SCI identity copies. | O(1) persistent-value fork; bindings must be immutable values, not a shallow map of mutable Var cells. | Requires a SCI semantic change across reads, writes, metadata, dynamic binding and entry selection. Actual Seon overhead must be measured before adoption. |
| 3. (a) Namespace lookup at execution for analyzed references | Ordinary inherited calls can follow current names once active execution context is selected; call and value positions, `(var ...)` and test metadata all need coverage. | O(R) qualified namespace lookups, with aliases resolved once during analysis. | O(1), retaining existing copy-on-write writes. | Name lookup alone changes unmap/reintern semantics and misses old handles. Adding identity preservation and snapshot bindings turns this into option 2. Useful prototype, incomplete permanent contract. |
| 4. (b) Eager clone of namespace Vars | Child writes isolate the table's cells; **no propagation guarantee for inherited bodies**. | Normal direct Var reads; rebuilding bodies adds O(S) acquisition and can repeat effects. | O(V) allocations; O(total source) if all bodies must also be rebuilt. | Shallow root copies retain old references. Complete graph cloning is a different, much larger design, including arbitrary closures and test metadata. Reject table cloning as this bug's fix. |
| 5. (e) Adopt upstream draft's dense stable-handle bindings | Prototype implements context-selected binding state with much broader live-state isolation. Must verify Seon's proof shapes and integration. | O(R) slot accesses and context selection; writes update local slots. | O(live state slots), plus its snapshot copying policy. | Broad unmerged dependency change, quiescent snapshots, extra isolation semantics, and measured hot-path overhead in its own benchmarks. Evaluate its Var subset before adopting the whole feature. |

### Why option 2, and what “every reference” means

Keep the Var's identity stable across redefinition within a lineage. The executing
context owns root/metadata/watch bindings for that identity; a fork shares their
immutable values until a write. Namespaces resolve a name to the handle, and
analyzed code retains that handle. Removing a namespace name does not erase the
binding a previously captured handle names; reinterning mints a new identity.
No shared indirection cell may have its root overwritten for the child: that would
redirect base and sibling too. The **cell's binding must be context-specific**.

Use SCI's entry context with lineage checks, including host-invoked test bodies
and inherited functions. Do not accidentally replace the active child context
with the function's captured base. `sci.impl.types/->Node` supplies an execution
context, but `sci.impl.fns/fun` currently supplies its captured one for the body.
Existing runtime `sci.impl.namespaces/sci-resolve` demonstrates the useful active
context seam. This is library work, not a new Seon evaluator or registry.

This guarantee covers Var references, **not** a function value already stored in
a local, collection, partial, or `def captured g`. Those values do not acquire a
new meaning when g changes in Clojure either. Macro expansions and `^:const`
substitutions also do not update retroactively. Keep those analysis rules distinct
from fork selection. Private atoms and host objects need not be deep-copied to
fix this assignment; keep Seon's existing private-state lifetime.

Start with the simpler persistent representation. Dense slots are an optimization
only if measured R versus fork frequency warrants O(V) snapshots. Do not infer that
price solely from 5,700 functions: Seon forks for evaluations, clean test requests,
test members, and fixture branches. Benchmark representative requests and memory,
not only tight arithmetic loops.

### Local cost evidence and limits

Two bounded JVM forms built 5,700 synthetic SCI Vars in namespace bench and used
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` for the current thread.
Three samples followed bounded warmup. This does not measure retained heap, total
Seon acquisition, or a proposed implementation.

| Operation | Three samples | Current-thread allocation |
|---|---|---|
| Existing `sci.core/fork`, 1,000 operations/sample | 138.4, 78.3, 72.8 ns/fork | 536 bytes/fork |
| Deref one held SCI Var, 100,000/sample | 14.3, 5.90, 5.22 ns/read | approximately zero |
| `get-in` through current namespace map then deref, 100,000/sample | 63.7, 62.1, 62.6 ns/read | 64 bytes/read |
| Copy all 5,700 Vars by repeated context-aware `bind-root!`, 5 forks/sample | 4.98, 3.89, 3.16 ms/fork | 18.09 MB/fork |
| Bulk rebuild the bench namespace table with `new-var`, 10 forks/sample | 0.543, 0.442, 0.424 ms/fork | 1.54 MB/fork |

The first form answered in 97 ms (`result/e2ca368ca8f61`), the bulk-copy form in
20 ms (`result/e2c7b0efcde9d`). The literal path vector in the namespace probe
allocates; a captured path or direct nested lookups can change its price. These
are prices for specific scratch algorithms, not a prediction that a correct
SCI binding lookup costs 64 bytes. Table cloning's relatively modest time does
not repair its missing guarantee. The branch-tests notes report 11–17 ms for a
50-caller reverse-reach read; that number excludes source analysis and is historical
evidence, not this lane's measurement.

To repeat the measurements without a source file, use these local JVM bindings;
the record returned by each sample is the evidence to retain:

```clojure
(let [n (sci.core/create-ns 'bench)
      entries (into {} (map (fn [i]
                             (let [s (symbol (str "f" i))]
                               [s (sci.core/new-var s i {:ns n :name s})])))
                    (range 5700))
      base (sci.core/init {:namespaces {'bench entries}})
      v (get entries 'f1)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      alloc (fn [] (.getThreadAllocatedBytes
                    ^com.sun.management.ThreadMXBean bean tid))
      sample (fn [f count]
               (let [a (alloc) t (System/nanoTime)]
                 (dotimes [_ count] (f))
                 {:ns-per-op (/ (double (- (System/nanoTime) t)) count)
                  :allocated-bytes-per-op (/ (double (- (alloc) a)) count)}))
      direct (fn [] @v)
      lookup (fn [] @(get-in @(:env base) [:namespaces 'bench 'f1]))
      copy-fork (fn []
                  (let [c (sci.core/fork base)]
                    (doseq [[_ v] entries] (sci.core/bind-root! c v @v)) c))
      bulk-fork (fn []
                  (let [c (sci.core/fork base)
                        generation (:sci/generation @(:env c))]
                    (swap! (:env c) update-in [:namespaces 'bench]
                           #(update-vals %
                              (fn [v]
                                (sci.core/new-var
                                  (sci.impl.vars/toSymbol v)
                                  (sci.impl.vars/getRawRoot v)
                                  (assoc (meta v) :sci/generation generation)))))
                    c))]
  (dotimes [_ 10000] (direct) (lookup) (sci.core/fork base))
  (dotimes [_ 3] (copy-fork) (bulk-fork))
  (mapv (fn [_]
          {:direct (sample direct 100000)
           :lookup (sample lookup 100000)
           :fork (sample #(sci.core/fork base) 1000)
           :copy (sample copy-fork 5)
           :bulk (sample bulk-fork 10)})
        (range 3)))
```

## Upstream: an existing design, not an installed answer

The original [SCI #369](https://github.com/babashka/sci/issues/369) introduced reuse
without newly defined state leaking back. The [upstream state documentation](https://github.com/babashka/sci/blob/5fe89056384418be50d232b4278344cb3a1a526b/README.md#state)
illustrates a newly created name, not inherited analyzed callers. Current upstream
`sci.core/fork` at `5fe89056384418be50d232b4278344cb3a1a526b` still makes a fresh atom
over the same env value. Its docstring promises isolation for **new** Vars.
[Pinned upstream source](https://github.com/babashka/sci/blob/5fe89056384418be50d232b4278344cb3a1a526b/src/sci/core.cljc).
Local history also contains the earlier `4b423a347c251b41c841d996c5206c1c9c68ec15`
clone-vars experiment; it is not an option in today's pinned API.

GitHub issue search for fork in SCI titles/bodies found
[SCI #1083](https://github.com/babashka/sci/issues/1083), which considers persistent
bindings versus dense local slots. It chooses dense snapshots in its prototype.
There were no issue comments at inspection. This is an open design discussion,
not evidence that the maintainer guarantees its semantics.

[Draft PR #1084](https://github.com/babashka/sci/pull/1084), inspected via GitHub's
API, was open, draft and unmerged, head `2b1c31016187e0753b00d98fef8a97ce3baefd67`:
37 files, 6,034 additions and 537 deletions. Its opt-in forkable mode preserves
stable handles and isolates roots, metadata and more. It also covers atoms,
multimethods, type state and continuations, beyond this assignment. Its reported
500,000 interpreted calls cost 12.5 ms in standard mode and 32.0 ms in forkable
mode; 2,000,000 Var reads cost 23.9 versus 59.4 ms. Those are the author's
benchmarks, not Seon measurements or this lane's validation. Standard mode keeps
the historical fork semantics. Do not pass its proposed options to the installed
fork: `sci.impl.opts/init` validates options and this mode is absent.

The draft source has exactly the relevant composition:
`sci.impl.world/var-value`, `sci.impl.world/var-value-at` and
`sci.impl.world/register-var!` select/update per-context state by stable handle;
`sci.impl.world/active-ctx` chooses an active related context for inherited closures.
[Draft binding owner](https://github.com/whilo/sci/blob/2b1c31016187e0753b00d98fef8a97ce3baefd67/src/sci/impl/world.cljc),
[draft Var implementation](https://github.com/whilo/sci/blob/2b1c31016187e0753b00d98fef8a97ce3baefd67/src/sci/lang.cljc).
These symbols are verified in that draft and **absent from the installed pin**.
Study its seams/tests first; ask the SCI owner to separate the needed Var behavior
if the broader feature is not the right dependency change. No upstream messages
or paid provider runs were sent by this lane.

## Where Seon's current repair must run

`9aea31407` adds the common `seon.sci.eval/stale-reach` selector and
`seon.sci.eval/reinterpret-reach!` installer at the evaluation fork and receiving
live base. It pre-owns all selected callers/tests before analyzing any, necessary
for mutual references and deftest self references. `f0652b92a` addresses namespace
load cycles in `seon.sci.eval/namespace-order` and `seon.turn.batch/form-facts`;
that is a separate acquisition defect, not a binding fix.

For option 1 to cover **named** declarations, the same repair must be an invariant
of installing bindings, not a growing list of symptoms:

1. After a replacement becomes visible in the active evaluation, before the next
   form or inherited caller uses it (`seon.sci.eval/reinterpret-inherited-callers!`).
2. After exact evaluated roots/metadata land in a receiving base, including rows
   outside the current request (`seon.sci.eval/transfer-evaluated-roots!`,
   `seon.sci.eval/install-evaluated-rows!`).
3. After acquisition, carried private definitions, namespace/refer bindings, or
   another writer's program change change the Var objects a context resolves
   (`seon.sci.eval/install-selected!`, `seon.sci.eval/fork-for-turn`,
   `seon.sci.eval/fork-cluster-ctx`).
4. When the automatic step creates its clean request context and each member's
   context (`seon.turn/test-step`, `seon.test/clean-request-handle`). An equal database
   program in `seon.sci.eval/acquired-database?` is not evidence that inherited
   analyzed bodies point at the same runtime Vars.

Compute the union of changed seeds once, carry exact runtime evidence, copy all
selected Vars before analysis, and repair each receiving context only when its
bindings differ. The present selector limits itself to agent-authored source;
enumerating call sites does not remove this completeness limit. Database static
reach does not include every anonymous closure or Var handle a user can retain.
Replaying definitions can duplicate initializer effects and alter captured state;
it is therefore not a general implementation of Clojure redefinition.

## What can disappear, and what must remain

A small `init-var!` change using `sci.impl.utils/owned-var` **before** analysis is
a well-scoped immediate fix for the self-reference task. It does not solve
inherited references, parent snapshot mutation or the clean-test-world shape.
An analyzer call wrapper alone also misses value position, Var values, metadata,
dynamic bindings and captured context.

A complete Var-focused library change can remove `stale-reach`,
`reinterpret-reach!`, `reinterpret-inherited-callers!`, their inherited-Var
heuristic and the “copy before analysis because fork changed identity” work.
It is not honest to promise a tiny fork-only patch: the entry and all Var
operations must agree, and Seon's transfers must preserve identity.

`seon.sci.eval/transfer-evaluated-roots!` currently transfers root values and
metadata via `sci.core/intern`. New declarations also need their logical Var
identity transferred: otherwise a source closure can retain a newly created
handle that the destination never installed. Redefinitions reuse the existing
identity; unmap/reintern must preserve the deliberate new identity. Use a SCI-owned
installation seam for this, and prove transfer without replaying initializers.

Keep program acquisition, arming, digest-based evidence, test reach selection,
macro re-analysis when explicitly reloaded, and the interpretation of ordinary
compiled callers that must see an interpreted branch change. A compiled JVM
caller still calls JVM Vars: SCI Var indirection cannot change it. Host-bound
platform declarations remain compiled until reviewed merge/reload, as AGENTS.md
requires. The deletion is the **SCI copy-induced** repair, not all affected-source
installation in `seon.sci.eval/acquire-program!`.

## Implementation cards: approximately fifteen-minute slices

These are proposed follow-up slices, not implemented APIs or passing proofs.
Do not start the next card if the current one contradicts the data; report the
gap. The semantic change needs the project's independent review before landing.

| Slice / owner | Smallest work | Positive proof and stop boundary |
|---|---|---|
| 1. SCI definition ownership | Pre-own an inherited definition in `sci.impl.analyzer/init-var!`. Extend dependency tests for self reference. | Fork and redefine a function returning its own Var and a deftest; both name/run the replacement, base/sibling unchanged. Keep inherited-caller case red to show the boundary. |
| 2. SCI design/proof card | Inspect #1084's Var and entry tests; specify stable identity, snapshot binding and active-context semantics for the narrower use. Prototype persistent lookup on scratch data. | Fork/parent/sibling/grandchild matrix, held Var, closure, alias/refer, metadata and unmap/reintern. Compare read-heavy and fork-heavy costs. No second Seon registry. |
| 3. SCI binding owner | Implement stable-handle root binding/deref/invoke within SCI's existing env owner. | Extend `sci.core-test/fork-copy-on-write-test` and `sci.vars-test/redefine-var-test`: inherited call and value read follow child; parent writes after fork do not leak; captured function value stays old. Stop before metadata/async if either needs a different identity. |
| 4. SCI entry and metadata | Make related active contexts govern inherited bodies, test metadata, dynamic binding and host invocation. Use existing SCI entry/context mechanisms. | Deftest self dispatch, recursive/global Var lookup, explicit host invocation, binding unwind, unrelated contexts and two concurrent siblings. Snapshot state, not execution. If too large, split entry from metadata/binding. |
| 5. Seon transfer integration | Preserve evaluated Var identities across base installation and clean-world/member forks through the SCI seam. | Extend `seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition` with an assigned main proof and the automatic **model-agent** step described by the task, then a second request. Assert positive test execution, actual tested digest and completion. Same-request new self-referencing test and effectful initializer each execute once. |
| 6. Seon deletion | Delete only copy-induced re-analysis and pre-copy logic; retain compiled-to-SCI interpretation and evidence. | Existing `seon.repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition`, `seon.repl-differential-test/another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee`, inherited-test proof, and `seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled`. Prove absence of caller source re-evaluation, preserved closure/atom state, and unchanged initializer count. |
| 7. Root dependency landing | Publish/adopt the fork pin and consumers on a disposable live-store branch; candidate boot and named tests, then root's batched default restart. | Parent/change timings and allocation for the same eval, automatic proof step and fork population; report every regression. Broad tiers run centrally. No default restart by an assigned lane. |

The production model-agent proof is essential: the task already records a green
MCP regression alongside a red model step. An implementation cannot substitute
that older green for the failing shape. Proofs belong on the existing tasks;
tag new behavior rows, not a second task describing the same defect.

## Landing and verification limits

Only this document is owned by `sci-vars-sol`. Shared working-tree changes appeared
in src/seon/turn.clj, test/seon/repl_differential_test.clj, test/seon/cluster/agent_test.clj,
test/seon/turn_work_test.clj and task resources during research; no edits, session
messages or recovery actions were made on them. Candidate validation must use
HEAD plus this path, not those in-flight files. Existing default reds include the
live inherited proof; the task file also names
`seon.sci.eval-test/showing-a-value-realizes-and-stores-only-what-is-shown` as red at
`9aea31407`. That printer behavior is outside this document's boundary.

Required `clj-kondo --lint src script test --cache false --parallel` completed in
5,002 ms with **0 errors and 1,494 warnings** (exit 2 for warnings). This is not a
zero-findings claim. No source/test lines changed. No full suite, installed repair,
retained-memory improvement or browser behavior is claimed. The commit gate requests
`seon.sci.eval-test/sci-fork-copies-existing-var-roots-on-write` and
`seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition` by Var,
using HEAD plus this documentation path in a throwaway JVM. These are baseline
checks of the studied behavior, not proofs of the unimplemented recommendation;
no executable declaration is changed by this document.
