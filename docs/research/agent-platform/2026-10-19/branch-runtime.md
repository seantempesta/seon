---
title: "Branch runtime: a branch's change is live for its whole runtime"
type: research
status: lane branch-runtime (Opus), 2026-10-19; for owner approval; check-JVM prototypes, no production code
created: 2026-10-19
---

# Branch runtime: a branch's change is live for its whole runtime

Lane branch-runtime (Opus), 2026-10-19. Research and REPL prototype only. No production code changed.
Pinned revisions: clojure `3bc2b3e9` (1.12.5), sci fork `eb12fae8`, datahike fork `ccba220b`.
Scratch probes are in `tmp/branch-runtime/` (gitignored), so every number below is quoted here in full.
All probes ran in throwaway check JVMs (JDK 26, 18 cores, load average about 2.4), never on default.

The owner's requirement (2026-10-19): "When a branch changes a src function in its own ENV it is immediately active
for its branch and runtime."

## How it works today

The JVM holds one set of compiled Clojure Vars: main's loaded `src` program. An agent's SCI context does not hold
`src` functions as source. It copies each compiled function object into an SCI Var (`seon.sci.eval/copy-host-var`).
"Compiled caller" means one of those copied function objects, for example `seon.ref/answer`. Its body is JVM
bytecode, and its call to a helper reads the JVM Var `#'seon.ref/helper` directly. It never reads the SCI Var.

When a branch redefines the helper, only the branch's SCI Var changes. Compiled `answer` still calls main's helper.

- Before fd373b0c1, Seon re-rooted the changed name's whole reverse-caller closure as lazily interpreted members
  (`seon.sci.eval/lazy-root`, `bind-lazy-members!`, `promote-member!`).
  - For the hub `seon.db/as-of-key`, that closure was 2,345 functions.
  - One change cost 11 GB and 11.5 s, and 130 of 137 reaching tests errored.
- fd373b0c1 cut the walk at loaded `src` callers.
  - The cost fell to 318-852 MB and 0.9 s.
  - The price is the behavior the owner objects to: compiled callers see a branch's change only after merge.
  - The tests that reach a change only through compiled callers are deferred to a platform run at merge
    (`seon.fn/selection-walk` `::platform-tests`, `:seon.source/deferred`).

## For approval

### The design in plain terms

**The program is the branch's database value; the runtime follows it.**

1. **Code identity comes from Datahike alone.** Each function entity holds `:seon.fn/sym`, its source and its
   `:seon.program/definition-digest`, on every branch. `d/branch!` already makes a free branch of them. Nothing else
   holds code identity.
2. **Every function SCI can run is analysed once, by content.**
   - The base program context holds one SCI Var per program name (one object per name for the whole process).
   - Each Var's root is main's analysed function for that name, not a copy of the compiled function.
   - An analysed function is a live host object (a tree of SCI nodes). It cannot be a datom, so it lives in
     `seon.cache` under its analysis key: the definition digest, plus the digest of its namespace declaration
     (aliases and refers decide resolution), plus the digests of the macros it expands.
   - One analysis per distinct definition is shared by every branch and agent that holds it.
3. **Names resolve late, at call time, in the executing context.** This needs no new mechanism.
   - Our SCI fork already makes Var identity global and roots per context (sci `cff2cefb`, "Context-owned Var
     bindings").
   - Analysed code holds the Var object. A call is a Var deref, and `sci.lang.Var/deref` reads the executing
     context's own binding (`:sci/var-bindings` in its env, through `sci.ctx-store/*ctx*`), else the Var's root.
   - A hub change is therefore visible to every caller on that branch at once and on no other branch. Nothing is
     walked or re-analysed.
4. **An agent's env is a fork plus its branch's differences from main.**
   - The env is `sci/fork` of the base program context, which shares everything.
   - It holds `:sci/var-bindings` only for the names whose definition digest on the checked-out database value
     differs from main's. Each binding's root is a `seon.cache` hit by analysis key.
   - The bindings are folded forward from each transaction's own datoms: assoc or dissoc the names that commit
     changed, which is O(changed). This is the pattern of the overlay-plan fold (76d4f4a68).
   - The bindings are a cache of the database's delta, never a second copy of the program. Main's names are the
     Var roots, and the branch map holds only the delta.
5. **Host-bound declarations are leaves.**
   - These are `deftype`, `defrecord`, `definterface`, `proxy` users, and anything else SCI cannot run.
   - Their Var root is the compiled function, the same in every context.
   - Values and state (`def` of an atom, `defonce`, registries) are never re-evaluated. The base keeps the host's
     value, so interpreting adds no second copy of process state.
6. **Platform entrants stay compiled.**
   - The one-path fold, writer, recorder, Flow procs, MCP and the test runner's harness run as loaded code when the
     host calls them.
   - When an agent's evaluation calls a `src` function, that function runs interpreted in the agent's env and sees
     the branch.
7. **Merge is Datahike's `merge!`.**
   - Root rebinds the base Var roots of the merged names to their analysed functions. These are cache hits, because
     the branch already analysed them.
   - A branch binding whose digest now equals main's becomes redundant. It is dropped at that branch's next fold.

### What an agent experiences

- It `defn`s a private helper on its branch, then calls the public `src` function that uses it. The function answers
  with the branch's helper at once.
- The branch's tests reaching that helper run on the branch and see the change, compiled entrance or not. Nothing is
  deferred to merge.
- Main and every other agent keep their own behavior.
- A fresh agent costs a fork (about 0.7 µs) plus cache hits for its branch's delta.
- Platform functions its code calls run about 2.2 to 2.8 times slower than compiled ones (measured below).

### Costs, measured or prototyped

Proof forms and numbers. The probes are `tmp/branch-runtime/{interpret_src,roots,store_growth,bench_vars}.clj`.

**Analysing the whole `src` program in SCI, once** (`interpret_src.clj`):

- Setup: a check JVM loaded all 120 `src` namespaces compiled (13.3 s). An SCI context copied every loaded
  namespace's Vars. Then every top-level `defn`/`defn-` in `src` was re-evaluated from source into its SCI Var.
- **4,496 of 4,504 `src` defns analysed, in 1.37 s.** That allocated 1,265 MB and **retained 80 MB** (about 18 KB per
  analysed function).
- The 8 failures are core functions the probe did not inject (`ref`, `dosync`, `Throwable->map`,
  `StackTraceElement->vec`, `read+string`). Seon's base context binds those.
- Analysing is not running. A `proxy` user (the commonmark visitor in `seon.ref/parsed`) analyses fine and then
  refuses at call ("no proxy-fn"). So host-bound must stay the per-declaration fact `:seon.fn/host-bound?`, judged by
  what SCI can run, not by whether a form analyses.
- Loading `src` namespace files whole into SCI (ns forms included) failed on the first load-time sentinel:
  `seon.schema/register-predicate` compares the interpreted function with the JVM Var. That is why the design
  re-evaluates only `defn`s and keeps values and state as the host's.

**A hub change, live on one branch only, on the real program:**

- On a fork of the full `src` base, the probe redefined `seon.print/literal` (private, called by `form-text` and
  recursively by itself) as `(defn- literal [x] "BRANCH")`.
- Fork: `(seon.print/form-text '(a 1))` → `"(BRANCH BRANCH)"`.
- Base: the same call → `"(a 1)"`. Compiled host: `"(a 1)"`.
- The change cost **2.1 ms and 52 KB** to analyse one defn. Nothing was walked.

**Synthetic two-root proof** (`roots.clj`), where `answer` calls the private `hub` and both are analysed once in base:

| Proof | Result |
|---|---|
| P1: two forks share one analysed `answer` while `hub` differs | base 2, branch 1001, other -999; `#'seon.ref/answer` identical in all three |
| P3: create a branch (`sci/fork`) | 667 ns warm median, 1,416 bytes |
| P4: a change (analyse and bind one defn) | 0.64 ms, 56 KB |
| P2: per call `answer`→`hub` in an SCI loop, hub as Var root vs hub as context binding | 66.2 vs 71.7 ns: **+5.5 ns per contextual deref** |
| P5: a lazy `(map answer xs)` made on the branch, realized outside any context | **[2 3 4], base's hub: wrong** |
| P5b: a lambda created on the branch, realized outside | **[2 3 4]: wrong** |
| P5c: a branch fn value called on a raw `Thread` | 2: wrong |

P5 is the one gap in the fork's mechanism.

- An SCI function called on a context-less thread re-enters its *defining* context (sci `f8a2542d`, `41194667`;
  `sci.impl.fns/in-defining-ctx`). That context is the one whose evaluation created the function object.
- For functions analysed in base, the defining context is base, even for a lambda created while the branch ran,
  because the fn node evaluates with the closing function's `ctx`.
- Inside an evaluation, `*ctx*` is the branch and everything resolves correctly. A value that escapes, such as a lazy
  seq realized later or a callback a host executor runs, falls back to base.
- Fix in the fork (phase 0): a function object created while a context executes closes over that executing context
  (`sci.ctx-store/*ctx*`), not the analysing one.
  - A program Var read in value position on a context other than base yields a function bound to the executing
    context.
  - The cost is one dynamic read per closure creation, and one small allocation per value-position read of a program
    function. Call position is unchanged.

**Interpreted vs compiled, per call, on real Seon functions** (both uninstrumented, same check JVM):

| Function | Compiled | Interpreted | Ratio |
|---|---|---|---|
| `seon.print/form-text` on a nested defn form (printer recursion) | 6.3 µs | 17.2 µs | 2.7× |
| `seon.fn.signature/declaration-metadata` (edamame read of a defn) | 3.5 µs | 9.7 µs | 2.8× |

An earlier run of `form-text` measured 7.8 vs 17.3 µs (2.2×).

The interpreted function's own body slows down, and its clojure.core and library callees stay compiled. Hot paths
called *by the host* (the fold, printer, writer, query engine) stay compiled. Only what an agent's own code calls
pays the 2-3×.

The end-to-end cost of an ordinary eval request whose `src` calls all run interpreted is **not measured**: it needs the
real base context. Phase 4 proves it.

**Datahike: storage, branching and lookup** (`store_growth.clj`):

- Setup: Seon's own store configuration, `seon.cluster.store/datahike-configuration` (file backend, LZ4,
  `:branching-factor 128`, `:diff-buf-size 32`, history kept).
- The store holds 4,500 function entities (`:seon.fn/sym` unique identity, source of about 150 bytes, a SHA-256
  digest).

| Measure | Result |
|---|---|
| Base store, 4,500 functions | 1 MB, 575 files |
| One function change: datoms in `:tx-data` | 3 |
| One function change: commit time | 12.3 ms |
| One function change on main: store growth | **33 KB per commit, 8.3 files** |
| `d/branch!` | **0 bytes** |
| 100 one-function commits on the branch: store growth | **44 KB per commit** |
| AVET lookup `(d/datoms db :avet :seon.fn/sym s)`, warm | 830 ns |
| `(d/entity db [:seon.fn/sym s])` and an attribute, warm | 1,139 ns |
| Persistent map `get` | 24 ns |
| SCI contextual Var deref (above) | +5.5 ns over a plain Var |
| Fold one commit's 3 datoms into a name map | 142 µs, first and unwarmed call (O(changed)) |

How these fit the owner's constraints:

- **Datahike's caches serve the fold, not the call.**
  - Its decoded-node cache (`:store-cache-bytes`, byte-bounded), per-database search cache (`:search-cache-size`) and
    query cache (`datahike.query/query-cache`, advanced per commit by the writer) make the per-commit fold read only
    the transaction's own datoms and the few AVET entries it touches.
  - A per-call AVET lookup costs 830 ns, about 150 times the contextual deref. That would put a Datahike read on
    every interpreted call, so the call path reads the Var.
  - The Var bindings are not a second index. Main's names are Var roots, and a branch holds only its delta: the
    names whose digest differs, usually a handful.
- **The analysed functions need `seon.cache`.** They are live host objects (SCI node closures) with no datom form.
  Keyed by analysis digest and byte-weighted, all of `src` is about 80 MB in the 512 MB budget.
- **Storage per change** is dominated by index-node copy-on-write, not the datoms.
  - A change writes 3 datoms but 33-44 KB of nodes. That matches the store's existing small-commit evidence (72 KB
    before LZ4 and the 128/32 tuning, in the configuration's comments).
  - Source text averages about 1 KB per defn (106,500 lines over 4,504 defns), under the 4,096-byte inline
    threshold. A larger definition already goes to a konserve blob through `seon.blob/inline-or-stage`.
  - Analysis is never stored: it is derived and cached by content.
- **A branch's program costs only its changed nodes.** `d/branch!` wrote 0 bytes, and each change on the branch
  writes about the same bytes as on main.

### Risks and what we would give up

1. **Speed of agent-called platform code: 2.2-2.8× per interpreted function.** The owner accepted this ("Everything
   that they can run should be running in sci"). It falls on agent calls into `src`, never on the host's own
   entrants. Phase 4 measures it end to end before the old path is deleted.
2. **Escaping functions (P5)** are wrong until the phase-0 fork change lands. Today they silently use main's
   definitions outside the evaluation.
3. **Interpreting platform functions from agent code was the pre-fd373b0c1 failure.** 130 of 137 tests errored.
   - Two differences: that design re-rooted *callers lazily* from per-member source reads under a moving database
     (`seon.sci.eval/a-lazily-interpreted-platform-member-cannot-resolve-transact`), while this one analyses the
     whole base once, from one commit, with values kept as the host's.
   - The proof is that the 137 reaching tests of an `as-of-key` change run green on the branch (phase 2).
4. **Contracts.**
   - Malli compiles a schema's `:fn` predicates by value. So a contract instrumenting an interpreted function still
     captures the predicate function it compiled with.
   - `seon.sci.arm/rearm-dependents!` stays until predicates resolve through the env. That is a separate change, and
     out of scope here.
   - Instrumenting the base's 4,496 analysed functions once is an unmeasured cost that phase 1 must report.
5. **Macros.** A branch-changed macro needs its callers re-analysed, so the analysis key includes the digests of the
   macros a definition expands. `src` holds 7 `defmacro`s, so this is rare.
6. **Reflective resolution from compiled entrants.** `src` has 164 `requiring-resolve`/`ns-resolve`/`find-var` call
   sites. Run interpreted, SCI's own resolve sees the env. Run compiled by the host (an entrant), they resolve main.
   That matches the rule that entrants are the platform.
7. **Given up.** The deferred-platform-run design of fd373b0c1, and the guarantee that a branch can never change what
   the platform functions it calls do. The second is exactly what the owner asked to give up.

### Phases with red proofs

Each phase is one loadable slice. HEAD boots after each.

- **Phase 0, SCI fork: a function made or read on a context resolves in that context off-thread.**
  - Red proof: a row in `sci.fork-bindings-test` reproducing P5/P5b/P5c above (expected `[1001 1002 1003]`, today
    `[2 3 4]`).
  - Change: `sci.impl.fns/fun` closes over the executing `sci.ctx-store/*ctx*` when one is bound.
- **Phase 1, the base analyses `src`: a branch's helper change is live for its `src` callers.**
  - Red proof (the task's first proof), a new deftest
    `seon.sci.eval-test/a-branch-helper-change-is-live-for-its-src-callers-on-that-branch`:
    1. A branch redefines a private helper of a loaded public `src` function (e.g. `seon.print/literal` under
       `seon.print/form-text`).
    2. On that branch, through the one path, `form-text` answers the new behavior.
    3. On main, the compiled and base `form-text` are unchanged.
  - It replaces `seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled` and the "a compiled src
    caller keeps its compiled root" assertion of
    `seon.sci.branch-execution-test/a-hub-change-interprets-only-itself-and-its-direct-test`. Both encode the rule
    being reversed.
  - Change: `seon.sci.eval/build-base-ctx` roots each non-host-bound `src` defn's SCI Var at its analysed function
    from `seon.cache` (analysis key above). `copy-host-var` stays for libraries and host-bound declarations.
- **Phase 2, the branch overlay is only its delta.**
  - Red proof: a `seon.profile/measure` assertion on the cost-estimate row `:hub-behavior-change`, parent vs change on
    the same probe.
  - Today it is 318-852 MB and 0.9 s. The expected cost is one analysis (about 50 KB, a few ms) plus the reaching
    tests.
  - This closes `seon.repl/a-hub-behavior-change-costs-hundreds-of-megabytes`.
  - The 137 reaching tests of an `as-of-key` change run on the branch with zero harness errors.
- **Phase 3, no deferred platform tests.**
  - Red proof: in `seon.cluster.source-test`, `seon.cluster.source/merge-members` for a hub change lists no
    `:seon.source/deferred` members, and the done rule's run on the branch covers the tests that reach it through
    `src` callers.
  - It converts the `::platform-tests` rows of `seon.fn-test`.
- **Phase 4, the end-to-end price.**
  - Proof: a `seon.profile/measure` of an ordinary eval request that calls `my.*`/`seon.*` functions (e.g. a
    `my.task/current` plus a render), on the parent and the change.
  - The commit message records the measured ratio.
  - If it is over about 3× on a path agents use every turn, return to the owner before the deletions of phase 2 land.

### What this deletes

- **`seon.sci.eval`:**
  - The lazy-member machinery: `lazy-root`, `bind-lazy-members!`, `promote-member!`, `source-database`, and
    `executing-ctx` where only those use it.
  - The closure half of `interpret-overlay!`: the `seon.fn/reverse-closure` call, `::closure`/`::closure-tests` in the
    plan, `reached`/`callers`, and re-interpreting what reaches Δ. What remains is "bind Δ by digest".
  - The `src`-namespace use of `copy-host-var`.
- **`seon.fn`:**
  - The `:branch` rule of `source-callers`, whose only purpose was stopping the walk at loaded callers.
  - `selection-walk`'s `::platform-tests`. The reverse closure stays only for test selection.
- **`seon.cluster.source/merge-members`:** `:seon.source/deferred`. In `seon.cluster/reaching-run`, the
  `isolated-run` of deferred members.
- **`seon.sci.arm`:** nothing in this design. `rearm-dependents!` and `behavior-held?` serve contract predicates
  captured by value (risk 4).

These are the names on HEAD today. Each phase's commit deletes the listed code together with the conversion of its
last caller.

## The alternatives, priced

### A. Clojure per-thread Var binding (the first candidate)

The idea is to make the changed host Vars dynamic and have the branch's evaluation thread `with-bindings` each one to
its interpreted function.

Verified against `clojure.lang.Compiler` and `clojure.lang.Var`:

- `Compiler.ObjExpr.emitVarValue` emits `Var.getRawRoot()` for a Var that is not dynamic *at compile time*, and
  `Var.get()` (which checks `threadBound`, then `deref`) for a dynamic one.
  - The choice is baked into the caller's bytecode, so `.setDynamic` after callers compiled changes nothing for them.
  - Probe H: `(.setDynamic #'helper-plain)`, then `(binding [helper-plain (fn [_] :branch)] [(caller-plain 1)
    (helper-plain 1)])` → `[2 2]`.
- `Var.pushThreadBindings` refuses a Var whose `dynamic` flag is false. So `.setDynamic` is needed at runtime, but it
  is not sufficient.
- **Direct linking: Seon does not use it.** No `:direct-linking` or `clojure.compiler.direct-linking` appears in
  `deps.edn`, `bin/` or `script/`. With direct linking on, `Compiler.InvokeExpr.parse` emits a `StaticInvokeExpr` that
  bypasses the Var entirely, and no Var-based scheme could work.
- **Making it work without recompiling** means replacing each changed host Var's root with a dispatcher.
  - The dispatcher calls `(.deref v)`, which answers this thread's binding or the root. When the answer is the
    dispatcher itself, it calls the original function.
  - This writes the platform's Var root (and reload or instrumentation re-roots it), for the sake of one branch.

Measured, per call on a hot compiled call, 20 M calls, median of 5 (`bench_vars.clj`):

| Case | ns per call |
|---|---|
| Static Var (`getRawRoot`) | 0.94 |
| Dynamic Var compiled as dynamic, unbound | 2.34 |
| Dynamic Var compiled as dynamic, bound on this thread | 7.7 |
| Dispatcher root, Var never bound | 5.4 |
| Dispatcher root, bound on another thread only | 7.5 |
| Dispatcher root, bound on this thread | 10.8 |
| Push+pop of a binding frame, 1 Var | 147 |
| Push+pop of a binding frame, 10 Vars | 463 |
| Push+pop of a binding frame, 100 Vars | 3,800 |
| Push+pop of a binding frame, 1,000 Vars | 52,800 |

The call through a 1,000-Var frame is unchanged at about 11 ns.

Pricing:

- Per change: O(1), the root replacement.
- Per call: +5-10 ns on changed Vars only.
- Per evaluation: frame push, linear in changed Vars.
- Per thread: only conveying threads see it. `bound-fn*`, `future` and `core.async` `thread`/`go` convey, but a Flow
  proc started outside the evaluation, an executor `submit` and a virtual-thread storage task do not.
- Fidelity: compiled callers on the evaluation thread see the change. Captured function values (`partial`, a map of
  handlers, Malli `:fn` predicates) do not, the same as a Clojure REPL.

It mutates platform Var roots for a branch, and it brings a second binding mechanism beside SCI's context bindings.
**Not recommended.**

### B. Lazy re-rooting of the reverse closure (pre-fd373b0c1)

- Cost per hub change: 11 GB, 11.5 s and 2,345 bindings, measured in fd373b0c1's commit message.
- Fidelity: full, but the runner broke (130 of 137 errors).
- What is left today (cut at `src`) costs 318-852 MB and 0.9 s, with no fidelity for compiled callers.
- The work is proportional to the callers, not to the change. **Deleted by the recommendation.**

### C. Per-call Datahike resolution (name → digest by AVET on each call)

- 830 ns per call warm, about 150 times the contextual deref.
- Only the fold reads Datahike, once per commit.

### D. Merkle keys (an analysis keyed by its callees' digests)

- Late binding through the Var makes this unnecessary for functions, and a hub change would give every caller a new
  key.
- It survives only for macros, whose expansion is analysis-time. The analysis key carries the expanded macros'
  digests.

### E. SCI `:namespaces` overrides alone

These change what *interpreted* code resolves. They cannot reach compiled code, which is today's situation. They
become sufficient only once `src` is analysed into the base, which is the recommendation.

## Evidence index (forms to rerun)

From the repo root:

- `clojure -J-Xmx8g -M -i tmp/branch-runtime/interpret_src.clj -m interpret-src`
- `clojure -J-Xmx8g -M -i tmp/branch-runtime/store_growth.clj -m store-growth`

From `tmp/branch-runtime/` (it has its own `deps.edn`: clojure 1.12.5, core.async alpha3, the sci fork):

- `clojure -M -i roots.clj -m roots`
- `clojure -M -i bench_vars.clj -m bench-vars`

The probes live in gitignored `tmp/`. Phase 0 and phase 1 turn P1, P5 and the `form-text` hub row into committed
tests.
