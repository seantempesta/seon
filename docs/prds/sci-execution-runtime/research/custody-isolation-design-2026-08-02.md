---
type: research
status: active
tags: [research, sci, runtime]
---

# Cluster custody and isolation — the current model, its failure modes, and design options

One JVM hosts many sovereign clusters. Each cluster is one Datahike branch plus one
live SCI context, and first-party functions are installed into that context as the
real compiled JVM Vars. This document states what the model actually is today
(with `file:line`), demonstrates its failure modes, answers the owner's proposed
per-cluster-var design with source evidence, and offers options for the three
questions asked. Nothing here was implemented; no production code changed.

Every claim is either read from source at the cited line or produced by a read-only
probe committed at `research/scripts/custody-probe{,2,3,4,5,6,7}-2026-08-02.clj` (run with
`clojure -M:dev`, load-only, no cluster started, no live cluster touched). Claims I
could not verify are marked UNVERIFIED.

This report builds on and does not re-derive
`research/sci-runtime-interrogation-2026-08-02.md` (commit `6bce7993b`), which pins
the SCI revision and the reachability census. A separate lane owns
`research/sci-var-semantics-2026-08-02.md`; where our scopes overlap I answered the
question anyway because my recommendation depends on it, and I state the answer with
its evidence so the two reports can be fused by comparison rather than by another
round trip.

## 0. The headline, first

1. **THE MOST SEVERE FINDING — arbitrary code substitution into every other
   cluster.** A context that holds a reference to another cluster's ctx map can
   rewrite that cluster's entire program with `swap!` and `assoc-in`/`dissoc` alone —
   no interop, no privileged function, no Var mutation. Demonstrated end to end in
   probe 7 (§2.1): a function deleted out of a victim context, and an
   attacker-authored function injected into it and then executed by the victim. The
   ctx is carried on the cluster instance (`resources/seon/schema.edn:1488`,
   `:seon.sci.eval/ctx`), and every instance is reachable through
   `@seon.cluster/running-instances`. This is strictly worse than cross-cluster
   writes: it is arbitrary code execution in every other cluster's agent world.
2. **The compiled runtime itself cannot be redefined — verified exhaustively.**
   `alter-var-root`, `with-redefs`, `var-set`, `intern`, `binding`, and
   `push-thread-bindings` against a raw `clojure.lang.Var` reached from SCI all throw,
   because SCI's `IVar`/`IBox` protocols are not extended to `clojure.lang.Var`
   (probe 6, §2.2). One exception: **`alter-meta!` succeeds, JVM-globally.**
3. **The owner's per-cluster-SCI-var design cannot work as stated** —
   `sci/copy-var*` copies a var's *dereferenced value* into a brand-new sci var
   (`reference-code/sci/src/sci/core.cljc:136`), so the sci-side and compiled
   `seon.db/*conn*` are unrelated objects. Probe 4:
   `(binding [seon.db/*conn* :SCI-VALUE] (host/peek))` where `host/peek` is a
   compiled fn reading the compiled var → **nil** (§3.1).
4. **But the owner's stated GOAL — "they can't reference anything outside of this
   context" — is achievable, by a smaller change than any new custody mechanism.**
   The goal is a *reachability* property, and the hole is entirely in our own install
   seam (`src/seon/sci/eval.clj:932` installs `ns-interns`, private vars included),
   not in SCI.

## 1. The current model, as it actually is

### 1.1 Custody is one thread-local dynamic Var, bound at two seams

`seon.db/*conn*` is the ambient custody (`src/seon/db.clj:15-17`). Reads that elide
the database argument resolve `(d/db *conn*)` once at the public-call boundary, and
an unbound custody returns a flat error value rather than an NPE
(`src/seon/db.clj:45-56`).

There are exactly two binders in `src/`:

- `seon.sci.eval/evaluate` — `(binding [db/*conn* (or connection db/*conn*)] …)`
  (`src/seon/sci/eval.clj:1445`), wrapping the whole guarded evaluation through
  `admit`'s realization (`src/seon/sci/eval.clj:1537-1545`), so a lazy value cannot
  lose custody before it is realized.
- `seon.render/walk-context` — `(binding [*walk-context* context, db/*conn* (or
  (:seon.store/branch-connection context) db/*conn*)] …)` (`src/seon/render.clj:106-110`).

Both use the same `or` fallback. The connection is an OPTIONAL field of the
evaluation request (`resources/seon/schema.edn:1278`). Intent is recorded at
`plan/seondb-facade-contract-spec.md:55-65`.

### 1.2 The SCI context is per-cluster and is used AS GIVEN

`cluster-ctx` builds one base context and cold-acquires the program into it
(`src/seon/sci/eval.clj:1211-1234`), called once per cluster at boot
(`src/seon/cluster.clj:1368`). `evaluate` deliberately does not fork it
(`src/seon/sci/eval.clj:1428-1431`).

`sci/fork` is `(update ctx :env (fn [env] (atom @env)))`
(`reference-code/sci/src/sci/core.cljc:326-331`) — a shallow copy of the env atom.
Probe 5 pins the asymmetry exactly: a `def` in the fork that *rebinds an existing
var* IS visible in the original (`shared` → 99, because the Var object is shared and
`def` bindRoots in place), while a *new* def made in the original after the fork is
NOT visible in the fork (`Unable to resolve symbol: newvar`). So fork is a
namespace-visibility device, not an isolation boundary. Used in exactly one place,
the `ns-unmap` path (`src/seon/sci/eval.clj:1452-1454`).

**Verified good news:** the ctx *is* a real confinement boundary between clusters.
Probe 3 shadowed `seon.db/q` inside context A (`(in-ns 'seon.db) (def q …)` →
`:A-poisoned`) while context B still resolved the untouched host Var. A `def` inside
SCI interns a new sci Var in that ctx's env; it reaches neither the JVM Var nor
another cluster's ctx.

### 1.3 The compiled host surface is installed WHOLESALE, including private Vars

`install-loaded-first-party-namespaces!` computes membership as the intersection of
core-provenanced `:seon.ns` rows and Clojure's loaded namespace set, then calls
`(sci/add-namespace! ctx namespace-name (ns-interns host-namespace))`
(`src/seon/sci/eval.clj:908-932`). The *set of namespaces* is computed, correctly.
But `ns-interns` returns **every** intern, public and private.

Measured (probe 1): `seon.cluster` has 72 interns, **51 of them private**. All 51
are installed into every cluster's SCI context under their bare symbols. The
orchestrator independently observed the same on the live `default` cluster
(`#'seon.cluster.store/held-flocks`, `#'seon.cluster.store/release-store!`,
`#'seon.cluster.store/panic-on-core-error?`, `#'seon.cluster.run/refuse!`).

### 1.4 What that makes reachable

Probe 1, evaluating inside SCI:

- `@seon.cluster/running-instances` → the live atom (`src/seon/cluster.clj:188`,
  `^:private`). Its value maps cluster name → instance, and each instance carries
  `:seon.store/branch-connection` (`src/seon/cluster.clj:1071`, `:1471`) along with
  the flow graph, routing atom, and prepl server.
- `@seon.cluster/root-store-holder` → the process-root store holder atom
  (`src/seon/cluster.clj:236`, `^:private`), holding the store under the lifetime flock.

Probe 3 lists the reachable **public** surface — resolvable from any agent eval in
any cluster: `seon.cluster/{refork!,start!,stop!,populate-source!,root-executors}`,
`seon.cluster.registry/{reset-cluster!,retire-branch!,branch!,ensure-cluster!}`,
`seon.cluster.store/{transact!,open-branch!,open-store!,release-store!}`.

So the cross-cluster write path is three ordinary calls, no interop, no privacy escape:

```clojure
(let [other (get @seon.cluster/running-instances "someone-elses-cluster")]
  (seon.cluster.store/transact! (:seon.store/branch-connection other) tx-data))
```

`transact!` takes its connection as an explicit argument
(`src/seon/cluster/store.clj:464-470`) and validates nothing about whose cluster it
belongs to. The ambient custody Var is not consulted on that path at all.

A destructive variant is shorter: `reset-cluster!` or `refork!` destroys a branch;
`release-store!` drops the process-root store and its flock out from under every
cluster in the JVM. I did NOT execute any of these — the claim is from reading their
definitions and confirming the symbols resolve.

### 1.5 What is already closed — SCI is deny-by-default and it is working

Probe 2 confirms, and the orchestrator's live-cluster probe independently confirms:

- `(alter-var-root #'seon.db/q …)` → throws (`No implementation of method:
  :getRawRoot of protocol: #'sci.impl.vars/IVar found for class: clojure.lang.Var`).
  SCI's Var protocol does not extend `clojure.lang.Var`.
- `(.bindRoot #'seon.db/q …)` → `Method bindRoot on class clojure.lang.Var not
  allowed!` — instance interop is gated by the ctx's `:classes` map
  (`reference-code/sci/src/sci/impl/evaluator.cljc`, `impl/analyzer.cljc`, the
  `"Method … not allowed"` sites). Seon's base ctx admits only `Throwable` and
  `Error` (`src/seon/sci/eval.clj:208-211`).
- `(Thread. …)` → `Unable to resolve classname`; `reify` on `java.lang.Runnable` →
  unresolved; `proxy` → `no proxy-fn`; `(.. obj getClass getClassLoader (loadClass
  "java.lang.Thread"))` → `Method getClass … not allowed!`; `future`, `pmap`,
  `Thread/currentThread` unresolvable.

So **an agent cannot spawn a thread, forge a class, or mutate a compiled Var
today**, and the JVM `seon.db/q` Var was verified unchanged after the attempts.
This is a load-bearing constraint to PRESERVE, not a permanent fact — see §6.

## 2. Can an agent change the actual runtime?

The owner asked specifically. Two different answers, and the difference matters.

### 2.1 The interpreted runtime of ANOTHER cluster: YES, completely (probe 7)

`research/scripts/custody-probe7-2026-08-02.clj` constructs a victim context with
`(defn important [] :CORRECT)` and an attacker context that merely holds the victim
ctx map as an ordinary value. From inside the attacker's interpreter, using only
`clojure.core`:

| attacker form | result |
|---|---|
| `(str (type (:env other/ctx)))` | `"class clojure.lang.Atom"` |
| `(sort (keys (:namespaces @(:env other/ctx))))` | the victim's full namespace list |
| `(swap! (:env other/ctx) update-in [:namespaces 'user] dissoc 'important)` | `:done` |
| victim then evaluates `(important)` | **`THREW: Unable to resolve symbol: important`** |
| `(swap! (:env other/ctx) assoc-in [:namespaces 'user 'important2] (fn [] :ATTACKER))` | `:done` |
| victim then evaluates `(important2)` | **`:ATTACKER`** |

The ctx is an ordinary map whose `:env` is an ordinary atom
(`reference-code/sci/src/sci/core.cljc:312-331`), so anything holding the map holds
write access to the interpreter. No interop is involved — `swap!`, `assoc-in`, and
`dissoc` are core functions every agent has. In production the ctx is reachable
because it rides the cluster instance (`resources/seon/schema.edn:1488`) and every
instance is in `@seon.cluster/running-instances` (§1.4).

**Severity.** Cross-cluster *writes* (§1.4) corrupt data another agent can at least
see. This corrupts the other cluster's *code*, silently, at a layer no database
audit shows: the victim's next eval simply behaves differently. Custody design does
not touch it — there is no call to check. Only reachability closes it.

### 2.2 The compiled JVM runtime: NO for values, YES for metadata (probe 6)

Against a throwaway `(defn victim [] :ORIGINAL)` installed as its raw
`clojure.lang.Var`, from inside SCI:

| attacker form | result |
|---|---|
| `#'throwaway/victim` → `(str (type …))` | `"class clojure.lang.Var"` — the real object, as the Codex lane reported |
| `(#'throwaway/victim)` | `:ORIGINAL` — callable through the var |
| `(alter-var-root #'throwaway/victim …)` | THREW `No implementation of method: :getRawRoot of protocol: #'sci.impl.vars/IVar` |
| `(with-redefs [throwaway/victim …] …)` | THREW, same protocol miss |
| `(var-set #'throwaway/victim …)` | THREW `:setVal of protocol: #'sci.impl.types/IBox` |
| `(intern 'throwaway 'victim …)` | THREW `:bindRoot of protocol: #'sci.impl.vars/IVar` |
| `(binding [throwaway/*dv* …] …)`, `(push-thread-bindings …)` | THREW `Can't dynamically bind non-dynamic var` |
| `(.bindRoot #'seon.db/q …)` (probe 2) | THREW `Method bindRoot on class clojure.lang.Var not allowed!` |
| **`(alter-meta! #'throwaway/victim assoc :pwned true)`** | **SUCCEEDED — `(:pwned (meta #'throwaway/victim))` is `true` on the JVM** |

Host values verified unchanged after the whole battery (`victim` → `:ORIGINAL`,
`*dv*` → `:orig`). So **an agent cannot redefine a compiled first-party function**,
in its own cluster or any other. SCI's protocol-based var operations simply do not
dispatch on `clojure.lang.Var`, and `.bindRoot` is blocked by the `:classes` gate.
That is a genuine structural guarantee and it should be *pinned as an invariant*
(#7), because it holds by SCI's implementation rather than by our design and a
`:classes` expansion (ruling #32 clause 3) would end it.

The `alter-meta!` exception is real and process-global. It matters because
`seon.instrument` derives from var metadata (`src/seon/instrument.clj:9`, "loaded
public var carrying `:malli/schema`"; `:115`; `:147`
`(some-> function-symbol find-var meta :arglists)`), and the `doc` and namespace-page
projections read `:seon.fn/doc`/`:arglists`. An agent can therefore alter what
instrumentation and documentation say about a compiled function for every cluster in
the process. It cannot change what the function *does*. Ranked below §2.1 but above
everything in §3; the fix is the same one — reachability, since `alter-meta!` still
needs a var object to name.

## 3. Failure modes

**F1 — Ambient inheritance silently picks the wrong cluster.** Both binders use
`(or connection db/*conn*)` and the request field is `{:optional true}`
(`resources/seon/schema.edn:1278`). Any caller that omits it on a thread still
carrying another cluster's binding runs against the wrong branch and *succeeds*. The
fallback converts "I forgot to say which cluster" into "use whatever was left over."

**F2 — Explicit-argument custody bypass is designed in.** Every `seon.db` read takes
an optional explicit database first argument (`src/seon/db.clj:136-145,190-208,226-244`),
and ruling #41 amendment (5) (`plan/README.md:1857-1870`) makes explicit-or-elided
the contract for the whole namespace including `transact!`. Custody governs only the
elided case, forever.

**F3 — Custody is a property of the THREAD; the thing it must match is a property of
the CTX.** The ctx is per-cluster by construction (`src/seon/cluster.clj:1368`); the
custody is per-thread. They agree only because today's one caller passes both from
the same map (`src/seon/cluster/loop.cljc:1042-1043`). Two facts that must agree,
derived from two places, eventually disagree.

**F4 — A captured connection outlives the binding.** An agent may
`(def c (:seon.store/branch-connection (get @seon.cluster/running-instances "x")))`.
That def lands in the cluster's session image and is reachable from every later eval
by every agent in that cluster.

**F5 — Thread escape is currently fail-closed by SCI's class gate, not by custody
design.** Clojure conveys dynamic bindings through `future`/`send`, not raw executor
submits, so a `seon.db` call landing on another thread sees `*conn*` unbound and
returns `::missing-connection-binding` (`src/seon/db.clj:47-51`). Correct — but it
depends on `future` staying absent and on no first-party function accepting an agent
closure and running it elsewhere.

**F6 — A closure permanently carries its DEFINING ctx's vars.** Probe 5: a `(fn []
base)` created in ctx3 returns ctx3's current `base` even when invoked from the host
and even when invoked *inside* ctx4 where `base` is `:CTX4`. SCI resolves symbols to
Var objects, and the closure holds the object; redefinition is seen through the var
(`[(f) (g)]` → `[2 2]` after `(def base 2)`). This cuts both ways: it is exactly why
a per-ctx custody var *would* travel correctly with agent closures, and exactly why a
closure that crosses ctxs is a leak. Closing §1.4 closes the crossing.

**F7 — Ruling-number collision in the ledger.** `#20` appears twice
(`plan/README.md:1641` "AGENTS CALL ANY FUNCTION IN THE SYSTEM"; `:1100` the
rendering north star), as does `#30` (`:1879` the persistence gate; `:863` the
published `current-src` branch). `AGENTS.md` cites "#20" for callability, so the
intended referent is `:1641`, but the ledger is ambiguous as written. A
durable-authority defect, not a runtime one.

## 4. The owner-proposed design, answered

> "Rather than one process-global dynamic var, give EACH CLUSTER'S ctx its OWN var
> carrying that cluster's connection, so an agent in cluster A cannot even NAME
> cluster B's connection… I want it so they can't reference anything outside of this
> context."

### 4.1 The decisive question: can compiled `seon.db/q` read an sci-side var?

**No.** Source: `sci/copy-var*` ends `(new-var nm @clojure-var new-m)`
(`reference-code/sci/src/sci/core.cljc:111-136`). It reads the Clojure var's value
*once*, copies `:dynamic` and other metadata, and constructs a fresh
`sci.lang/->Var` (`core.cljc:41-48`). There is no delegation, no proxy, no shared
box. `sci/binding` pushes thread bindings on the *sci* var
(`core.cljc:148-155`, `vars/push-thread-bindings`), which the compiled var never sees.

Probe 4, four measurements on one ctx where `seon.db/*conn*` was installed as
`(sci/copy-var db/*conn* ns-obj)` and `host/peek` is a **compiled** `(defn host-peek
[] db/*conn*)`:

| form | result |
|---|---|
| `seon.db/*conn*` (sci read, default) | `nil` |
| `(binding [seon.db/*conn* :SCI-VALUE] seon.db/*conn*)` | `:SCI-VALUE` |
| `(binding [seon.db/*conn* :SCI-VALUE] (host/peek))` | **`nil`** |
| host `(binding [db/*conn* :HOST-VALUE] …)` then sci reads `seon.db/*conn*` | **`nil`** |

The two vars are fully disjoint in both directions. So the design as stated would
produce a var an agent can bind and inspect, that **no `seon.db` function actually
reads** — the worst possible outcome: a custody control that looks like it works and
silently does not. If an sci-side `*conn*` is ever exposed, it must be a *read-only
projection*, or better, a compiled accessor `(seon.db/current-connection)` that reads
the compiled var truthfully.

Two further mechanics for completeness (probe 5):

- **Per-ctx means a distinct var OBJECT per ctx.** Sharing one `sci/new-dynamic-var`
  object across two ctxs gives thread-local bindings on one object, not isolation.
- **Redefinition / session-image restore / ns-unmap fork.** Closures hold var
  objects, so a restored or redefined custody var is seen live by everything holding
  it. But `sci/fork` shares var objects, so a rebinding `def` inside the ns-unmap
  fork propagates to the original ctx (`shared` → 99). A custody var living in the
  sci env would therefore be *writable from inside the fork and visible outside it*.
  That is a further reason not to put custody in the sci env.

### 4.2 What the owner actually wants, and the design that delivers it

"They can't reference anything outside of this context" is a **reachability**
property, not a variable-scoping one. Today the only reason an agent can reference
cluster B at all is that we installed 51 private Vars of `seon.cluster` into its
context (§1.3). Fix the install seam and the goal is met — without a second custody
mechanism, without splitting SCI-side and host-side semantics, and without any
per-agent permission machinery.

Concretely: **§5 Option 2B (install `ns-publics`) + relocation of the process-global
roots + §4.3 Option 1A (custody derived from the ctx, host-side).** The custody var
stays exactly where compiled code can read it; the *context* becomes the single
source of which cluster that is; and the connection of any other cluster becomes
unnameable because nothing installed into the ctx exposes it.

### 4.3 Question 1 — how custody should be established

#### RECOMMENDED — Option 1A: custody is derived from the ctx; the request field is deleted

Attach the cluster's custody to the context at `cluster-ctx` build time
(`src/seon/sci/eval.clj:1211-1234`) as `::custody {:seon.cluster/name …
:seon.store/branch-connection …}`, and have `evaluate` bind the **compiled**
`db/*conn*` from `(::custody ctx)` with no `or` fallback. Delete
`:seon.store/branch-connection` from `:seon.sci.eval/request`
(`resources/seon/schema.edn:1278`) and apply the same rule to
`seon.render/walk-context`.

- **Guarantee.** For any evaluation, the elided-argument custody is exactly the
  cluster whose program the interpreter is running. The two facts that must agree
  (F3) collapse into one fact with one derivation. F1 becomes unrepresentable rather
  than tested-against, because there is no longer a field to forget.
- **Cost/risk.** Small and mechanical: one `assoc` at `cluster-ctx`, one changed
  `binding`, one schema field removed, and ~6 call sites stop passing the connection
  (`src/seon/cluster/loop.cljc:1042-1043`;
  `src/seon/render/web.clj:326-331,568,793,1061`). Risk: a fresh one-off ctx
  (`(or ctx (build-base-ctx))`, `:1431`) has no custody, so `*conn*` stays unbound —
  which is already the correct fail-closed answer.
- **Operational trade-off.** A one-off eval with no cluster can no longer reach a
  database by inheriting somebody's binding. Deliberate.
- **Capability given up.** Running one ctx against a different branch per call.
  Nothing does that, and per-cluster ctx is settled (ruling #31, `README.md:2049-2067`).
- **Can an agent still get it wrong?** Not for the elided case. F2 and F4 are §5's job.

#### Option 1B: keep the ambient binding, but make it required rather than inherited

Change `(or connection db/*conn*)` to `connection` at both binders and make the
schema field required.

- **Guarantee.** F1 becomes loud instead of silent (388 instrumented vars, dial
  `:panic` — `README.md:2049-2067`).
- **Cost/risk.** Two `or`s and one schema keyword. Smallest possible change.
- **Capability given up.** None.
- **Can an agent still get it wrong?** The *system* can: a caller may pass a
  well-formed connection belonging to the wrong cluster and nothing notices. It fixes
  forgetting, not mismatching — the strictly weaker half of 1A. Interim only.

#### Option 1C: a per-cluster SCI var (the owner's proposal, as stated)

- **Guarantee.** None for compiled `seon.db` calls — refuted in §3.1 by source and probe.
- **Verdict.** Do not adopt in this form. Adopt its *goal* via 1A + 2B. If an
  agent-facing name is wanted, expose a compiled `(seon.db/current-connection)`
  accessor so the value shown is the value used.

#### Option 1D: derive custody from the agent identity at each call

`seon.render` already has the query (`custody-cluster-name`, `src/seon/render.clj:120-128`).

- **Cost/risk.** Circular: resolving agent → cluster needs a database value, which
  needs a connection — i.e. a process-level name→connection map, which is
  `running-instances`, the exact thing §4 makes unreachable. Also a Datalog query on
  every elided read.
- **Can an agent still get it wrong?** Yes, worse: an agent able to influence its own
  `:seon.cluster.agent/cluster` fact would move its own custody. **Do not adopt.**

#### Option 1E (owner's Option C): per-cluster function injection at the install seam

At install, bind the *context's* var for each database function to a function that
already closes over that cluster's connection. Cluster A's `seon.db/q` and cluster
B's become different function objects; an agent has no way to name the other.

**Is it a second mechanism?** No — and this is its strongest argument. Seon already
rebinds a context's var at install: `install-function-contract!` calls
`sci.vars/bindRoot` on the ctx's var with `instrument/wrap-interpreted`, applying
each agent function's committed Malli contract (`src/seon/sci/eval.clj:782-793`;
`src/seon/instrument.clj:243-268`). Option C extends that seam rather than adding one,
which is exactly what the one-mechanism law asks for.

**Is the selection computable?** Yes, and it must be. "Which functions take a
branch-connection or a database value" is a Datalog query over facts that already
exist: `:seon.fn/spec` holds the parsed contract and ruling #33 makes input schemas
refs to schema entities, so the predicate is "first input schema is
`:seon.store/branch-connection` or `:seon.db/database-value`". A hand-maintained
roster would violate the no-hand-lists law and must be rejected outright — agreed,
without reservation. So on the "hacky" axis, C's *selection* is principled.

**Where the line actually falls — four problems, in increasing severity.**

1. **It cannot use `bindRoot` on what is installed today.** Probe 6 proves
   `sci.vars/bindRoot` does not dispatch on `clojure.lang.Var`
   (`No implementation of method: :bindRoot of protocol: #'sci.impl.vars/IVar`), and
   `install-loaded-first-party-namespaces!` installs the *actual compiled Vars*
   (`ns-interns`, `src/seon/sci/eval.clj:932`). Option C therefore forces those
   namespaces onto `sci/copy-var*` — which copies the dereferenced value once
   (`core.cljc:136`) and so **destroys the hot-reload property the install seam
   explicitly relies on**: "`ns-interns` supplies the namespace's real Vars, not
   copied roots, so a re-evaluated `defn` changes the next host call without
   reacquisition" (`src/seon/sci/eval.clj:911-914`). Losing live redefinition of core
   code against a running cluster is a direct hit on the fix loop, which the standing
   goal ranks above the queue.
2. **It solves only the elided path — the same path Option 1A already solves — and
   leaves the harder one open.** Ruling #41 amendment (5) keeps the explicit
   db/connection argument on every function (`README.md:1857-1870`). An injected
   closure pre-fills the elided arity; it does nothing about an agent that passes a
   foreign connection explicitly (F2) or holds one in a def (F4). So C buys no
   guarantee 1A does not already buy, at materially higher cost.
3. **Two behaviors under one name — and it is the bad kind.** The owner dislikes
   this, and here the divergence is not cosmetic: `seon.db/q` called from compiled
   first-party code resolves ambient custody, while `seon.db/q` in an agent's context
   is a different object with custody baked in. Agent-authored code is *committed to
   the program graph* and may later be executed by compiled paths; a function whose
   meaning depends on which tier evaluated it is precisely the "ported defect" shape
   the conversion test rejects. 1A has one name, one behavior, one derivation.
4. **It does not touch §2.1.** The severe finding is arbitrary rewrite of another
   cluster's ctx env. Per-cluster function objects live *inside* that env and are
   overwritten by the same `swap!`. No custody design closes §2.1; only reachability
   does.

**The conditional the orchestrator asked for.** C's unique claim was independence
from "can compiled code read an sci-side var". That question is now answered — **no**
(§4.1, probe 4 plus `core.cljc:136`) — which kills Option 1C but leaves 1A untouched,
because 1A binds the *compiled* var and never asks anything of the sci side. So C's
one exclusive advantage is moot. Stated as a conditional for fusion with
`sci-var-semantics-2026-08-02.md`: *if* that lane confirms compiled code cannot read
an sci-side var (my finding), recommend 1A; *if* it somehow finds a linkage I missed,
1A is still the recommendation because it is strictly simpler, and 1C becomes a
viable alternative — C is not preferred under either answer.

- **Guarantee.** Correct elided custody, same as 1A.
- **Cost/risk.** High: forces `copy-var*` on the whole core surface, loses hot
  reload of core code into live clusters, and adds a contract-driven selection query
  to the install path.
- **Operational trade-off.** Core-code changes need reacquisition or a cluster restart.
- **Capability given up.** Live redefinition of compiled first-party functions
  against a running cluster.
- **Can an agent still get it wrong?** Yes — explicit-argument bypass survives, and
  §2.1 is untouched.
- **Verdict.** Principled in *construction* (existing seam, computed selection), but
  it fails the "not hacky" test on the outcome side: it makes one name mean two
  things across the tier boundary in order to solve a problem a `binding` already
  solves. **Do not adopt.** Keep the seam's precedent in mind — it is the right place
  if we ever need genuinely per-cluster function values for a reason 1A cannot serve.

#### The three designs compared

| | today: ambient thread-local | 1C: per-cluster SCI var | 1E/C: per-cluster injection | **1A: ctx-derived (recommended)** |
|---|---|---|---|---|
| Can an agent get it wrong? | yes — silent wrong-cluster on inheritance (F1) | n/a — compiled code never reads it, so it is wrong for everyone (§4.1) | explicit-arg bypass survives; §2.1 untouched | not for the elided path; explicit path handled by 2A |
| Closure defined in A, invoked later | reads whatever is bound at call time (F6) | would travel correctly — closures hold var objects (probe 5) — but the value is never read | travels correctly; closure holds A's injected fn | reads A's custody iff invoked under A's ctx; otherwise unbound → flat error (fail-closed) |
| Session-image restore | unaffected — custody is not session state | restored var could shadow custody; fork shares var objects, so a rebinding `def` inside the ns-unmap fork escapes to the parent (probe 5) | injected fns are env entries and are restorable/overwritable as data | unaffected — custody lives on the ctx map, outside the env atom and outside the session image |
| Redefinition | unaffected | agent can `binding`/`def` over it; looks effective, is not | agent can `def` over the injected fn in its own ctx (probe 3 shadowing) | agent cannot reach it from SCI at all |
| Total mechanism count | 1 | 2 (host var + sci var, disjoint) | 2 (ambient for core code + injected for agent code) | **1** |

Recommendation: **1A**. One mechanism, one name, one behavior, fewest lines, and the
only one of the four where the two facts that must agree are the same fact.

### 4.4 Composing with the one definition seam

`research/definition-seam-design-2026-08-02.md` (commit `a970a8f59`) records the
owner's direction to centralize overrides at the one seam where a function's value
enters a cluster's context, and constrains any custody design to land there and
compose with contract instrumentation as one order of operations. Taking that
constraint seriously changes nothing about the recommendation, but it deserves a
direct answer rather than a deferral.

**First, the fact that decides it: the seam physically cannot carry custody for
compiled first-party functions.** The seam has two halves that share
`seon.instrument`. For interpreted rows it is `sci.vars/bindRoot` on the *context's*
var (`src/seon/sci/eval.clj:785-793`) — per cluster, fine. For compiled first-party
vars it is `seon.instrument/apply!`, which rebinds the **one process-wide
`clojure.lang.Var`** (`src/seon/instrument.clj:61` describes exactly that
`alter-var-root` wrapper). There is one compiled `seon.db/q` per JVM and N clusters,
so a custody value baked in at that half would be the *same* custody for every
cluster — incoherent by construction. Custody at the seam is therefore only
implementable for the interpreted half, which means forking `seon.instrument` into a
host path and an interpreted path that differ in what they inject. That is precisely
the outcome the seam note's own open question 4 forbids ("custody injection must not
fork that"). Probe 6 adds the second obstacle independently: `sci.vars/bindRoot` does
not dispatch on `clojure.lang.Var`, so the compiled Vars installed today
(`ns-interns`, `src/seon/sci/eval.clj:932`) cannot be rebound at the seam at all
without switching them to `sci/copy-var*` and losing live redefinition
(`src/seon/sci/eval.clj:911-914`).

**Second, 1A introduces no wrapping path, so it cannot stack a second wrapper on the
first.** The rejection criterion in the constraint — "a parallel wrapping path" —
selects against Option C, not against 1A. 1A adds one `binding` in `evaluate` and
deletes a schema field; the definition seam remains the single place a function's
value is rewritten, and the number of value-rewriting mechanisms stays at one. The
principled statement of the split is: **the seam owns what a function IS in this
cluster; the evaluation owns what database it is looking at.** A function's value is
the same in every cluster — that is why one compiled Var can serve them all — while
the database differs per evaluation. Encoding a per-evaluation fact into a
per-cluster value is what forces the two-behaviors-one-name divergence in §1E.

**Third, if the owner rules that custody must land at the seam anyway, here is the
single transformation, exactly as asked.** One call site replaces
`install-function-contract!`; there is one composed value and one order:

```clojure
;; value = (contract (custody base))  — custody innermost, contract outermost
(sci.vars/bindRoot
 sci-var
 (-> @sci-var
     (custody/supply-elided  arity-input-refs cluster-custody)   ; 1
     (instrument/wrap-interpreted function-symbol spec-edn …)))  ; 2
;; then, at the same call site, as an EFFECT rather than a wrapper:
;;   (run-affected-tests! ctx committed)  — future work, accept-and-report
```

- **Which happens first, and why.** Custody, innermost. The committed contract is
  declared over the arity the agent calls; under ruling #41 that is the *elided*
  arity, and custody injection is what makes that arity real by supplying the
  argument. If instrumentation wrapped first it would validate an argument vector
  that is one argument short of the schema it was compiled from, so the seam would
  need a second contract for the injected shape — a second mechanism by another name.
  Injecting first means `:scope #{:input :output}` validates exactly the arguments
  the function receives, which is what it promises.
- **Selection.** Computed from ruling #33's parsed contract facts, never enumerated:
  the arities whose `:seon.fn.arity/input-refs` lead with
  `:seon.store/branch-connection` or `:seon.db/database-value` (the orchestrator's
  live counts: 9 and 42 on `default`). A roster would violate the no-hand-lists law
  and is rejected outright.
- **Resulting arity.** Unchanged in count. `supply-elided` does not remove an arity;
  it conses the cluster's custody value onto an invocation that arrived one argument
  short of a custody-leading arity, and otherwise passes through — so the explicit
  arity keeps working and F2 remains open exactly as it does under 1A.
- **Resulting error behavior.** Unchanged, with no new error path. A custody failure
  (absent or foreign) is produced *inside* the composed value as the function's
  ordinary flat `:seon.error` value, which every `seon.db` output schema already
  admits (`src/seon/db.clj:135,198,240`). Instrumentation on the outside therefore
  sees a legal return, and the `:panic`/`:record` dial keeps its current meaning: a
  custody mistake is a value the agent reads, a contract violation is
  instrumentation's decision.
- **Room for definition-time test execution.** Preserved. Test running is an effect
  at the call site, not a layer in the composed value, so the order above is
  `value = (contract (custody base))` with `effects = [install, run-affected-tests]`.
  Custody-innermost forecloses nothing, and accept-and-report stays compatible with
  ruling #30 because no stage in this composition refuses a definition.

**Verdict.** The composition is expressible, so the constraint is satisfiable — but
it buys the same guarantee 1A buys, at the cost of forking `seon.instrument`, losing
live redefinition of core code, and making one name mean two things across the tier
boundary. **Recommend 1A, and record that the seam remains the right and only home
for value rewriting — contracts today, definition-time tests next.** If the owner
rules for the seam regardless, the block above is the design; nothing else in this
report changes, because §2.1 and §1.4 are reachability problems that neither answer
touches.

## 5. Question 2 — preventing cross-cluster writes and core-runtime mutation

Ruling #20 (`README.md:1641-1656`) forbids restricting callability. Ruling #30
(`:1879-1893`) names this exact blocker — "agent evals reach
`seon.cluster.store/transact!` and can commit arbitrary same-cluster facts" — and
says the fix is a persistence gate, never a callability restriction. Ruling #31
(`:2049`) sets the posture: "functions should check their inputs." The options below
are those rulings applied literally.

### RECOMMENDED — Option 2B (first, because it is the actual hole): install `ns-publics`, not `ns-interns`

Change `src/seon/sci/eval.clj:932` from `(ns-interns host-namespace)` to
`(ns-publics host-namespace)`.

- **Guarantee.** `running-instances` and `root-store-holder` — the two atoms that
  make every other cluster's connection, the flock, the flow graph, the routing atom
  and the prepl socket reachable — stop being reachable at all. 51 of
  `seon.cluster`'s 72 interns leave agent space (probe 1). Making the reference
  unobtainable is strictly stronger than validating what is done with it, and it is
  one line.

- **Is this compatible with ruling #20?** I argue yes, and the argument is *already
  in the codebase*, not invented here. `:seon.fn/private?` is a first-class COMPUTED
  fact of the program graph, derived by clj-kondo analysis at each definition site
  (`src/seon/fn.clj:249`; schema `resources/seon/schema.edn:1970`) — the same shape as
  the workload-classification precedent, and explicitly not a hand-maintained list.
  Every agent-facing projection **already filters on it**: the `doc` surface
  (`src/seon/sci/eval.clj:946`, `[?function :seon.fn/private? false]`), the namespace
  page (`src/seon/render/ns.clj:297`), and the bootstrap drive
  (`src/seon/bootstrap_drive.clj:195`). So the program graph *already declares* that
  the agent-facing function surface is the public one; the install seam is the one
  place that disagrees with it. Excluding private vars does not narrow the declared
  surface — it makes the install seam consistent with the surface the graph has
  declared all along. What #20 repealed was the exclusion of *first-party
  namespaces*; nothing in it speaks to implementation details that the graph itself
  marks private and that no agent-facing projection has ever shown.

- **Legitimate dependency on reaching a private var?** I found none. The interpreted
  agent-authored install path is unaffected (it goes through
  `install-program-row!`, not this seam). No `my.*` toolkit function resolves a
  private first-party symbol. The documented surface is public-only by the three
  filters above, so anything an agent learned to call from its context is public.
  UNVERIFIED: I did not run a live cluster after the change; the falsifier is a
  bootstrap eval plus `(seon.render/walk)` on a scratch cluster.

- **Cost/risk.** One line. Risk is a loud break in some path that today depends on a
  private var; the probe above finds it in minutes.
- **Operational trade-off.** Debugging an internal from an agent eval now needs the
  MCP `eval_clj` path (which runs outside SCI) rather than the agent's own REPL.
- **Capability given up.** Calling private first-party functions from agent code.
- **Can an agent still get it wrong?** Yes — the public surface still contains
  `refork!`, `reset-cluster!`, `retire-branch!`, `stop!`, `release-store!`. But all
  require naming a victim, and without `running-instances` an agent cannot enumerate
  the other clusters. Reachability drops from "every cluster in the process" to
  "clusters whose names it guesses." Option 2A stops the writes.

- **This is also the ONLY thing that closes §2.1.** The ctx rides the cluster
  instance (`resources/seon/schema.edn:1488`), and the instance is reachable only
  through `running-instances`. Cut that reference and no agent can name another
  cluster's ctx map, its env atom, its connection, its flow graph, its routing atom,
  or its prepl socket. There is no per-call check that could substitute, because
  `swap!` on a reachable atom is not a call into any function we own.

- **Companion, same wave: relocate the process-global roots.** `ns-publics` hides
  `running-instances` and `root-store-holder` *because they happen to be private*.
  That is one `defn`-vs-`defn-` away from regressing. The durable form is that the
  process-root registry lives in a namespace that is not part of the cluster's
  program graph at all — an operator-owned namespace never installed into any ctx —
  so its visibility does not depend on a metadata flag. Invariant 8 below is what
  pins this.

### RECOMMENDED ALONGSIDE — Option 2A: custody validation at the one write door

Every write in `seon.db` (which per ruling #41 is *every* write, since `transact!`
moves there and the 16 bypassing `d/transact` sites migrate — `README.md:1846-1852`)
validates that its target connection is the caller's cluster and returns a flat
`:seon.error` value on mismatch. With 1A in place the comparison is an identity
check against `(::custody ctx)`. When custody is unbound (first-party non-agent
callers, boot, operator), the check is skipped — the system's own code is not the
threat model.

- **Guarantee.** No agent evaluation commits a datom to a branch other than its own,
  whether the connection was elided, captured in a def (F4), or dug out of a
  surviving reachability path. Closes F2 and F4 for writes. This is a *contract on a
  function's input* — exactly ruling #31 — and restricts nothing about what may be
  called, so it is unambiguously compatible with #20.
- **Cost/risk.** One `if` at one door plus the error value. The risk is completeness:
  it is worth exactly as much as the ruling-#41 sweep is complete; any surviving
  direct `d/transact` site is a hole. That sweep is already ordered, so this rides it.
- **Operational trade-off.** A legitimate cross-cluster write (export/import,
  operator tooling) must run outside an agent evaluation, where custody is unbound.
  That is the right shape and matches how `bin/seon` already works.
- **Capability given up.** Agent-authored cross-cluster coordination by direct write.
  Cross-cluster *reads* survive if a connection is somehow reachable.
- **Can an agent still get it wrong?** It can still read another branch, and
  destructive lifecycle calls are not writes and so are not covered. That residual is
  2C/2D.

### Option 2C: make destructive lifecycle unreachable by a computed rule

Derive an "operator surface" by reachability over `:seon.fn/calls` from the
operator's entry points (`script/seon/fresh_operator.clj`), the way workload
classification derives `:compute`/`:io` from annotated leaves
(`research/workload-classification-2026-07-28.md`), and exclude that computed set
from installation.

- **Guarantee.** `refork!`, `reset-cluster!`, `retire-branch!`, `release-store!`,
  `start!`, `stop!` stop being reachable — computed, not named.
- **Cost/risk.** Material: a reachability derivation, an operator-entry-point fact,
  and re-derivation on hot reload. It also excludes *public* functions, which is a
  clearer tension with #20 than 2B's privacy argument.
- **Capability given up.** Agent-driven cluster lifecycle, which the vision
  eventually wants.
- **Verdict.** Record it; do not build it now. 2A+2B already remove the ability to
  name another cluster's connection.

### Option 2D: accept the residual explicitly, as with AI credentials

State plainly that after 2A+2B an agent may still (i) read another cluster's data if
it obtains a connection, and (ii) call destructive lifecycle functions against a
cluster name it guesses — accepted until the persistence gate (ruling #30) exists.

- **Guarantee.** None; it is honesty, and it is required so the residual is a
  decision rather than an oversight.
- **Risk.** Real: `reset-cluster!` against another lane's cluster is unrecoverable
  data loss, and the 2026-07-29 symlink-deletion incident is the precedent for how
  expensive one reachable destructive call is.
- **Verdict.** Correct *as the written residual after 2A+2B*, not as the whole answer.

### The recommendation, stated once

**2B + 2A, with 2D as the written residual, and the root relocation in the same
wave.** 2B removes the ability to obtain the reference; 2A makes any reference
nonetheless obtained useless for writing; the relocation stops 2B's guarantee from
depending on a metadata flag; 2D states what is left. 2C is recorded and deferred.

Note what none of this needs: no allowlist, no per-agent grants, no capability
tokens, no second interpreter, no process fence, no new enforcement mechanism.
One line, one `if`, one namespace move.

## 6. Question 3 — the minimal invariant set

Each is a property over all inputs and interleavings, with the construction that
makes the failure class unrepresentable.

1. **Custody derivation is total and single-sourced.** For every evaluation `E`
   under context `C`, the connection bound to `seon.db/*conn*` during `E` is
   `(::custody C)` — all requests, all threads, all interleavings.
   *Construction:* 1A deletes the request field, so there is no second source to
   disagree. *Test:* generate arbitrary requests (including ones carrying foreign
   connection-shaped values) against N contexts; assert the bound connection is
   always the context's.

2. **No inheritance.** For every evaluation whose context carries no custody, the
   binding is `nil` — never a value left over from the calling thread.
   *Construction:* removal of the `or` fallback. *Test:* run evaluations on a thread
   with an arbitrary pre-existing `*conn*`; assert the observed binding is the
   context's or nil, never the pre-existing one.

3. **Elided-argument custody correctness across the whole `seon.db` surface.** For
   every function, arity, and both interface forms (positional and Datahike's
   argument map), the elided db/connection resolves to `(::custody C)`.
   *Construction:* one `current-database-value` helper (`src/seon/db.clj:45-56`) used
   by every elided path. *Test:* generative over function × arity × interface — this
   is what ruling #41's dual-interface expansion will most easily break, because it
   multiplies entry points.

4. **Write custody.** For every write from an evaluation under `C` with any target
   connection `K`: it commits iff `K` is `C`'s connection; otherwise it returns a flat
   `:seon.error` value and no branch's basis advances. *Construction:* 2A at the one
   door. *Test:* two clusters, generated tx-data, generated own/foreign target;
   assert the non-target branch's `:max-tx` is unchanged in every case.

5. **Cross-cluster write isolation (the end-to-end form of 4).** For any clusters
   `A`, `B` and any agent form `F` evaluated in `A`, the set of branches whose basis
   advances during `F` is a subset of `{A}`. *Test:* seeded generative forms
   including deliberate attempts at each known reachability path.

6. **Context confinement under redefinition.** For any form `F` in cluster `A`'s
   context, cluster `B`'s context resolves every symbol to the same value before and
   after `F`. *Construction:* per-cluster ctx with its own env atom. *Status:* holds
   today (probe 3). Pin it — it makes the shadowing footgun local, not global.

7. **Compiled Var immutability from SCI.** For every compiled first-party Var `V`
   and agent form `F`, `(var-get V)` on the JVM is `identical?` before and after `F`.
   *Construction:* SCI's `IVar` protocol does not extend `clojure.lang.Var`, and
   instance interop is `:classes`-gated. *Status:* holds today (probe 2). Pin it,
   because a future `:classes` expansion (ruling #32 clause 3, `README.md:2090-2093`)
   would silently break it.

8. **Reachability closure.** The set of symbols resolvable in a cluster context
   equals the installation rule applied to the current database value and the loaded
   namespace set — no more, and in particular no symbol whose `:seon.fn/private?`
   fact is true and no symbol from an operator-owned namespace.
   *Construction:* one computed derivation (`install-loaded-first-party-namespaces!`)
   plus the root relocation. *Test:* compare the ctx's symbol inventory against the
   rule recomputed independently. This catches an `ns-publics` → `ns-interns`
   regression, a `defn-` → `defn` slip on a process root, and the next well-meaning
   "just install one more thing."

9. **Process-root integrity.** For any agent form `F`, the root store holder count
   and the flock are unchanged, and no cluster starts or stops as a side effect
   unless `F` called a lifecycle function explicitly.

10. **Totality of the error path.** Every custody violation, on every path, is a flat
    `:seon.error` value — never a throw into the run loop, never a partial commit.
    *Construction:* `transact!`'s existing four-outcome never-throw contract
    (`src/seon/cluster/store.clj:432-497`) extended with the custody outcome.

11. **Foreign-context integrity.** For any clusters `A`, `B` and any agent form `F`
    evaluated in `A`, cluster `B`'s env atom is unchanged by `F` — no namespace
    added, removed, or rebound. *Construction:* `B`'s ctx map is unreachable from
    `A` (2B plus the root relocation). *Test:* snapshot `@(:env ctx-B)` around
    generated forms in `A`, including forms that deliberately search reachable
    values for anything ctx-shaped. This is the regression for §2.1, the most severe
    finding, and it is the one property no custody design can provide.

12. **Compiled Var metadata immutability.** For every compiled first-party Var `V`
    and every agent form `F`, `(meta V)` on the JVM is unchanged by `F`.
    *Status:* **currently VIOLATED** — `alter-meta!` succeeds (§2.2), and
    `seon.instrument` derives from var metadata (`src/seon/instrument.clj:115,147`).
    *Construction:* the same reachability cut; `alter-meta!` still needs a var
    object to name, and after 2B the ones that matter are fewer, though not zero
    (public compiled Vars remain nameable by design under ruling #20). If this
    invariant cannot be made to hold by reachability alone, it belongs on the
    explicit-residual list (2D) rather than being quietly dropped.

13. **No-concurrency-primitives closure (the constraint to preserve).** For every
    agent form `F`, every `seon.db` call made during `F` executes on the evaluating
    thread. *Construction:* today, SCI's `:classes` gate and the absence of `future`
    (§1.5). *Why pin it:* this invariant is what makes thread-local custody safe at
    all. It is exactly what giving agents flow-backed concurrency primitives would
    break — see §6.

Invariants 1–3 belong to Question 1; 4–5 and 8–9 to Question 2; 6–7, 10 and 11 are
standing properties that currently hold and whose *breakage* is what must be
detected. Eleven properties, one regression each, no example-test fences.

## 7. The constraint to preserve, stated for the record

Thread escape is not a current failure mode, and the reason is §1.5: agents have no
concurrency primitives. Everything that follows depends on it — thread-local custody
is only safe because agent code cannot leave its thread, and `binding` conveyance
(`future`/`send` convey; raw executor submits do not) is the only thing standing
between a captured closure and a custody-free write.

What would reintroduce the hazard, in order of likelihood:

1. **Giving agents flow-backed concurrency primitives** (the owner has expressed
   interest). Any such primitive must carry custody explicitly into the spawned work
   — the same `::custody` value from the ctx — rather than relying on ambient
   conveyance. Design it as a compiled first-party function that captures
   `(::custody ctx)` at submit time and rebinds it in the worker, never as a raw
   channel/executor handle handed to agent code.
2. **Expanding `:classes`** (ruling #32 clause 3 couples this to the per-eval
   host-interop fact). Admitting `java.lang.Thread`, `Runnable`, `Callable`, or any
   class exposing an executor re-opens F5 immediately, and also breaks invariant 7.
3. **Any first-party function that accepts an agent-supplied function and runs it
   elsewhere.** `seon.cluster/root-executors` is public and returns both executors
   today; it is currently harmless only because the interop to submit to them is
   class-gated.

Invariant 11 is the regression that catches all three.

## 8. The single most dangerous thing found

`install-loaded-first-party-namespaces!` installs `ns-interns`, so
`@seon.cluster/running-instances` hands any agent, in any cluster, the live Datahike
connection of every other cluster in the JVM (`src/seon/sci/eval.clj:932`;
`src/seon/cluster.clj:188`; verified by probe and by the orchestrator's live probe).
From there `seon.cluster.store/transact!` writes to another cluster's branch with no
check (`src/seon/cluster/store.clj:464-470`), and `seon.cluster.registry/reset-cluster!`
destroys one. The ambient custody Var is consulted on none of those paths, so no
improvement to custody *binding* alone touches this. It is one line to close the
reference and one `if` to close the write.
