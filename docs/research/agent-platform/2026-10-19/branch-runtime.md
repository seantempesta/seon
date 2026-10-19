---
title: "Branch runtime: a branch's change is live for its whole runtime"
type: research
status: approved P0–P2 (owner 2026-10-19); P3 and P4 need their own go-ahead
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

Phase status: P0 committed (sci fork `b6c51cd9` on branch `seon-ctx-escape`, bumped in f04cbe446 with a restart
batch; it is live after root restarts default). P1 is wired onto the content-addressed mechanism and stopped at a
patch, `branch-runtime-wire.patch` next to this note (lane runtime-wire, below): both real-program proofs and every
invariant test are green and the two test namespaces gain no red, but a branch with a function change pays the
interpretation of interop-heavy platform functions (`seon.id/digest` 124x, `:similar-by-name-warm` 2.45 GB against
57 MB), so the hub-change proof misses its bound. The leaf rule on top of it was tried and cut (lane runtime-leaf,
below): it fixes `seon.id/digest` but leaves `:similar-by-name-warm` at 2,452 MB. P2 is partial in the patch. P3 and P4 are not approved.

P1 attempt (lane runtime-p0-p2, 2026-10-19). The patch is `branch-runtime-p1.patch` next to this note. It adds
`seon.sci.eval/analyse-loaded-functions!` to `acquire-program!`, the proof
`seon.sci.eval-test/a-branch-helper-change-is-live-for-its-src-callers-on-that-branch`, the conversions, and a task
with its proof for the analysis refusals.

- The proof is green in a check JVM: the branch answers `"(BRANCH BRANCH)"`, while main's base and the compiled
  `form-text` answer `"(a 1)"`.
- On default, in a disposable fork of a real agent context, analysis refused 39 of 4,442 rows (the classes are in
  the patch's task).
  - Analysing the 4,403 `src` defns took 745 ms and 1.34 GB warm.
  - Arming their 3,486 contracts took 1,034 ms and 1.36 GB, with the call-preparation snapshot read once rather than
    once per arm, which cost 3.2 s and 1.6 GB.
  - Per call, interpreted and armed against compiled and instrumented: `form-text` 7.4 vs 8.1 µs,
    `seon.id/digest` 3.7 vs 4.1 µs, `my.task/current` 240 vs 287 µs. The armed wrapper dominates, so the 2-3×
    measured uninstrumented does not show.
  - Without arming, a default-filled call fails: `(my.task/current)` gets "Wrong number of args (0)".
- The blocker is the cost per base derivation. Each `with-database` fixture, adoption and loaded commit derives its
  own base, with fresh SCI Vars, so every base pays about 1.8 s and 2.7 GB.
  - `seon.sci.eval-test` and `seon.sci.branch-execution-test` with long tests ran 597 s on the candidate against
    28.6 s at HEAD.
  - The candidate had 11 reds that HEAD lacks:
    - 4 assert the reversed rule (compiled roots).
    - 3 exceed their duration.
    - 4 changed behavior: custody content nil, the eval-form-once schema rows, a test-namespace def answering a
      refusing root, and the contract-allocation status.
- The design's "analysed once per process, shared by content" assumes one SCI Var per program name per process.
  Today each base mints its own Vars (`copy-host-var` per derivation), so an analysis cannot be shared between bases.

Options for the owner, simplest first:

1. **Lazy analysis per base Var.** Each SCI-runnable `src` Var's base root analyses and arms its definition from the
   loaded commit on its first call, in the base, once. Cost then follows the functions called, and a fixture that
   calls few `src` functions pays for few. The risk is that this resembles the deleted per-branch lazy roots, though
   it reads an immutable commit, never a moving branch.
2. **Recommended: one process program context, which P2's mechanism builds first.** Analyse `src` once per process
   into Vars shared by every base. A base or branch is then a fork that binds only the names whose digest differs,
   one cached analysis per distinct definition. This reorders P2's delta fold ahead of P1, and costs one design
   round over how bases are derived.
3. **Land the patch as is.** This means about 1.8 s and 2.7 GB per base derivation and a test suite about 20× slower
   where it derives bases. It is not proportional to the change, so it is not recommended.

**The owner chose option 2 on 2026-10-19:** ONE process-wide base. `src` is analysed and armed once per loaded
program (about 1.8 s and 2.7 GB allocated, once per JVM load). Every base, branch, test fixture, adoption and agent
forks it and binds only the names whose definition digest differs. P2's difference binding is built first, and P1
lands on it (lane runtime-base).

#### Design: content-addressed analysis, a runtime is a name → digest map (lane runtime-base)

The owner refined option 2 the same day: "main, and 10/100/1000 branches each rewriting every function, many
overlapping. Which design lets me merge my own versions of arbitrary branches together and still put together a
running runtime efficiently and quickly that will always be accurate?" There is no pinned special base: main's
runtime is only the common case.

What exists: `seon.sci.eval/program-context` keeps one `:seon.sci.eval/program-context` entry in `seon.cache` per
loaded commit and store (`cluster-base-key`), each built from scratch by `derive-base-ctx`. That mints fresh SCI
Vars per base (`copy-host-var`), so no analysis can be shared between bases. A branch's overlay
(`derive-overlay-ctx`) is already a `sci/fork` of its loaded base plus its interpreted delta.

- **The Var space: the one thing with no data form.** One SCI context per JVM loaded program holds every program
  namespace, its aliases and imports, and one SCI Var per name, rooted at the compiled copy (`copy-host-var`). It
  is today's base, without analysis. It lives in the process registry (`seon.operator.runtime`), because Vars are
  identity and every analysed body holds them: a second Var space would split every cached analysis. It is built
  on the first acquisition after a JVM load and replaced when `seon.sci.eval/loaded-identity` moves (an adoption
  reloaded namespaces). Nothing else is pinned.
- **An analysed function is keyed by content** and held in `seon.cache` (kind `:seon.sci.eval/analysed`), evictable:
  - The key is the definition digest, the digest of its namespace's declaration (aliases and refers decide
    resolution), the digests of the macros its analysis expanded, the projection's contract digest and the caps it
    arms with, plus the Var space it resolves against.
  - The value is the analysed, armed function. Its body holds the callees' Vars, never their values.
  - N branches holding one version cost one analysis. A miss costs one analysis: 0.3 ms average over all of `src`
    (4,496 defns in 1.37 s), 0.64-2.1 ms and about 52 KB for a single cold one (measured above).
- **A runtime is an immutable name → digest map,** derived from Datahike facts only (`:seon.fn/sym`,
  `:seon.program/definition-digest`, admission, `:seon.fn/host-bound?`). A branch's map shares structure with
  main's. Composing branches means choosing a digest per name, the same choice a Datahike merge makes.
- **Building a runtime** is `sci/fork` of the Var space plus `:sci/var-bindings` from each SCI Var to its analysed
  function: a cache lookup per name, and an analysis only for a digest not yet cached. A runtime built from another
  runtime binds only the names whose digest differs, O(changed).
- **Calls are late-bound** through the Var, read in the executing context's own binding (sci `cff2cefb`, off-thread
  since P0 `b6c51cd9`). A caller's analysis never depends on its callees' versions, so every combination answers
  accurately.
- **Exceptions, counted on default at 8fe13867d** (5,023 `src` function rows): 7 `defmacro`s, 86 rows
  `:seon.fn/host-bound? true` (2 `deftype`, 2 `defrecord`, 1 `defmethod`, 0 `defmulti`, 0 `defprotocol`), 492
  value definitions (`def`, `defonce`).
  - Host-bound declarations and values are platform: their Var root is the compiled one in every runtime, and
    values are never re-evaluated, so interpreting adds no second copy of process state.
  - A macro's callers carry the macro's digest in their analysis key, so a branch-changed macro re-analyses only
    its callers on that branch.
- **Test fixtures and agents** fork the Var space and bind their runtime's map; they never rebuild the Var space.
  Branch, fixture and agent runtimes are evictable `seon.cache` values only where caching saves real work.
- **Source of truth.** The runtime maps, analysis keys and plans derive only from Datahike facts. The JVM
  contributes only the compiled roots of host-bound declarations and values, which the base already binds today.

#### Invariants, their tests and measured numbers (lane runtime-base, 2026-10-19)

The owner (2026-10-19): trivial continuations of an existing env and creating a branch "are supposed to always be
cheap and correct and not sometimes broken based on what is on 'main' (the compiled JVM). It shouldn't matter how
they are mixed it should just work." Each invariant is a test. The mechanism is `seon.sci.runtime` (`space`,
`analysed`, `bind`); its tests run on a synthetic program, and the real-program tests stay red until the base is
built from it.

| Invariant | Test | Status, measured |
|---|---|---|
| 1. A runtime depends only on its name → digest map, never on main's compiled JVM | `seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers`: 40 generated chains of up to 6 branches over 7 names in 3 versions, each runtime compared with its own source evaluated from scratch in a fresh SCI context | green |
| 1, on the real program: on a branch with a change, no SCI-runnable `src` function runs its compiled Var (main may run compiled: it has no overrides) | `seon.sci.eval-test/no-sci-runnable-src-function-runs-its-compiled-var` | **red** at HEAD; green with `branch-runtime-wire.patch`: all 4,463 runnable `src` functions analyse |
| 1, on the real program: a branch's helper change reaches its `src` callers | `seon.sci.eval-test/a-branch-helper-change-is-live-for-its-src-callers-on-that-branch` (P1's proof) | **red** at HEAD; green with the patch: the branch answers `"(BRANCH BRANCH)"`, main's base and the compiled caller `"(a 1)"` |
| 2. Creating a branch is a fork plus an empty difference | `seon.sci.runtime-test/creating-a-branch-and-continuing-an-env-cost-only-what-changed`: no analysis, no binding, under 1 MB allocated | green; fork plus empty bind 0.4 µs (10,000 runs on default, JVM mode) |
| 3. Continuing an env with nothing changed pays nothing; one change pays one analysis and one binding | same test: the unchanged continuation analyses and binds nothing and allocates under 1 MB; one change is one analysis, one binding, under 4 MB | green; a tiny `defn` analyses in 35 µs, a real `src` one in 0.3 ms average (1.37 s over 4,496) |
| 4. Composing branches re-analyses nothing already cached | `seon.sci.runtime-test/composing-branches-analyses-each-distinct-digest-once`: A changes `h1`, B `h2`, C composes A's `h1` with B's `h2` and analyses nothing, binding only `h2` | green |
| 5. 100 branches × 100 rewrites at 50% overlap: memory follows distinct analysed functions plus 100 small maps | `seon.sci.runtime-test/a-hundred-branches-analyse-each-distinct-digest-once`: the analysis count equals the distinct digests | green |

Scale, measured on default in JVM mode with the same mechanism inline (1,000 one-line functions; GC'd heap before and
after, so noisy at about ±2 MB):

- Main's runtime: 1,000 analyses in 36 ms, 3.4 MB retained.
- 100 branches of 100 rewrites (50 shared versions, 50 unique each): 5,743 new distinct analyses (6,743 in all) in
  145 ms, 19.6 MB retained, about 3.4 KB per one-line function.
- The estimate: distinct analysed functions × their size, plus 100 maps of about 100 entries (about 1 MB). The
  measured retention matches it: the analysed functions dominate. For real `src` functions, about 18 KB each.

Rerun with `bin/seon check src/seon/sci/runtime.clj test/seon/sci/runtime_test.clj test/seon/sci/eval_test.clj
--test seon.sci.runtime-test` (3.7 s for the six tests, 2026-10-19).

#### The real program on the mechanism (lane runtime-wire, 2026-10-19)

What the patch builds, by symbol (none of it is on HEAD):

- **The Var space** is `seon.operator.runtime/sci-var-space`: one SCI Var per program name for the process
  (`seon.sci.runtime/space-var`). `seon.sci.eval/copy-host-var` answers that Var, its root and metadata following the
  host Var, so every base, overlay, fixture and agent shares Var identity and no analysis is split. Every built
  context is forked at birth (`seon.sci.eval/build-base-ctx`), so it owns none of the shared Vars and a write is its
  own binding (`seon.sci.eval-test/cluster-contexts-share-no-writable-sci-stock-vars`).
- **The rows.** `seon.sci.eval/runtime-rows!` reads the loaded program's SCI-runnable `src` rows (definition digest,
  the namespace declaration's digest, the digests of the program macros each calls) once per base, and makes the
  base resolve them as their files do (refers, own `deftype` classes, private interns, the clojure.core names SCI
  lacks).
- **Analysis by content.** `seon.sci.runtime/analysed` keys an analysis by space, definition digest, namespace digest,
  macro digests and what it is armed with: the compiled Var's own contract and policy (`seon.sci.eval/armed-as-compiled`).
  4,463 analyses and their arming cost about 1.5 s once per JVM (`seon.cache` kind `:seon.sci.runtime/analysed`,
  load-ms 1,487-1,576); every later base hits.
- **Who runs analysed.** Main's program context keeps its compiled roots (owner: main has no overrides). A runtime
  whose functions differ binds main's analysed functions once (`seon.sci.eval/bind-analysed!`, from
  `derive-overlay-ctx` and from `interpret-affected!` on an agent's own change): 31 ms, 43 MB, then its delta
  overrides them. The bindings are recorded as inherited, so they are never the context's own definitions.
  `seon.sci.kernel/program-definition?` tells the loaded program's definition from a branch's, so neither predicate
  holding-apart (`held-apart-var`) nor `fork-cluster-ctx`'s re-arming treats them as changes; before that fix one
  `fork-cluster-ctx` recompiled every contract (31 s, 27 GB).
- **Producer fixes the analysis exposed.** A use of `print-method`/`print-dup` is host-bound (`seon.fn/host-bound-callers`:
  SCI's are refusing stand-ins). `seon.schema/runtime-predicate`, `projection-bindings` and
  `projection-state-bindings` answer the SCI Var when they run interpreted, so an agent's `seon.db/transact!` on a
  changed branch keeps working (`seon.sci.eval-test/evaluation-custody-is-derived-only-from-the-cluster-context`).

Measured in throwaway check JVMs (concurrent checks, so wall time is approximate; allocation and calls are not):

- `seon.sci.eval-test` and `seon.sci.branch-execution-test` with long tests: 33-44 s against HEAD's 29.7 s, the same
  9 reds as HEAD and none new (`the-evaluator-remains-live-after-its-namespace-reloads` turned green).
- Per call, interpreted on a changed branch against compiled on main, 300 calls through one SCI evaluation:
  `seon.print/form-text` 5.5 vs 1.9 ms (2.9x); `seon.render.value/core-text` 297 vs 75 ms and 346 vs 115 MB (4x);
  `seon.ref/brief` 3.5 vs 2.7 ms (1.3x); `seon.id/digest` 153 vs 1.2 ms and 466 vs 4 MB (**124x**).
- The ordinary-operation table (`seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates`),
  whose agent changes functions in its earlier rows, so its later rows run on a changed branch: patch against HEAD,
  allocation and calls: `:similar-by-name-warm` 2,453 vs 57 MB (0.9-3.3 s vs about 0.1 s); `:hub-behavior-change`
  1,037 vs 880 MB, 295k vs 196k calls, 4.1 vs 1.0 s (the proof's bound was 318-852 MB, its estimate 40 MB);
  `:seon-db-nine-forms` 1,452 vs 981 MB; `:current-state-with-task` 308 vs 207 MB; `:reuse-one-unchanged-test` 144 vs
  30 MB. `:unchanged-request` is equal (18.2 vs 18.0 MB): an unchanged agent pays nothing.

Where the gap comes from: not re-analysis (every analysis after the first is a cache hit) but interpretation of
interop-heavy hot functions. `seon.id/id` hashes with `MessageDigest`, `ByteBuffer` and `ArrayDeque` in a loop; each
interop call in SCI resolves reflectively and boxes, so it is 100x, not the 2-3x measured on `form-text`. Every
digest, write and reach computation on a changed branch calls it.

Options for the owner, simplest first:

1. **Keep the hot interop functions compiled by a producer rule.** Count which runnable `src` functions are mostly
   host interop (type-hinted calls, primitive arrays) and decide host-bound for them at `seon.fn/host-bound-callers`,
   as `print-method` now is. A branch's change to one of them then refuses as host-bound changes do. Cost: a rule
   and its census; risk: the rule is a heuristic beside the analysis.
2. **Bind analysed functions lazily per Var on a changed runtime**, so only what the branch's calls reach is
   interpreted and `seon.id`-style leaves called by compiled entrants stay compiled. Correctness for callers that the
   branch's change does not reach is unchanged; it does not remove the cost on paths that do reach the change.
3. **Land the patch as is**: correct for every combination, an unchanged agent pays nothing, a changed branch pays the
   numbers above on every request.

Not yet true or not measured:

- **The hub change with its reaching tests on a branch** was not run through the done rule here; the cost-table row
  `:hub-behavior-change` above is the measured request.
- **A publication run and a test run interpreted against compiled** are not measured separately; the
  `:similar-by-name-warm` and `seon.id/digest` rows say they will be dominated by the interop-heavy functions.
- **A name a branch adds** gets a context-local Var from SCI's `declare` in that runtime: isolated and O(1), but its
  callers' analyses are not shared across runtimes. `seon.sci.runtime/space-var` can mint the shared Var; the overlay
  does not use it yet.
- **The delta** is still interpreted per derived context (`install-row!`), not by `seon.sci.runtime/analysed`, and a
  branch-changed macro does not re-key its callers' analyses on that branch (the key carries main's macro digests).
- **Escaping analysed functions** re-enter the analysis fork they were defined in when called on a context-less
  thread (P0's rule), which holds a partial base.
- **Now redundant, kept for P3:** `seon.sci.eval/bind-lazy-members!`, `lazy-root`, `promote-member!`,
  `source-database`, `*deriving-database*` and `executing-ctx` (closure members already resolve late on a changed
  runtime); `seon.sci.eval/reinterpret-inherited-callers!`, `stale-reach`, `reinterpret-reach!` and `inherited?`
  (bodies hold the space's Var); `seon.fn` `source-callers`' `:branch` rule, `selection-walk`'s `::platform-tests`
  and `seon.cluster.source/merge-members`' `:seon.source/deferred` (tests reaching a change through `src` callers can
  now run on the branch).

#### The leaf rule, tried and cut (lane runtime-leaf, 2026-10-19)

The owner approved exploring option 1's narrow form: a function whose own `:seon.fn/calls` name no program definition
but itself and no run-time resolution (`resolve`, `ns-resolve`, `requiring-resolve`, `find-var`, `eval`,
`load-string`, `load-file`, `intern`, `var-get`, `the-ns`, `ns-publics`, `ns-interns`), with its analysis complete
(`:seon.program/analyzed-source-digest`), keeps its compiled Var on a changed runtime; a branch that changes it
interprets it. The patch is `branch-runtime-leaf.patch` next to this note, on top of `branch-runtime-wire.patch`:
one predicate, `seon.sci.eval/leaves` (19 lines), consulted once in `runtime-rows!` (3 lines), and
`no-sci-runnable-src-function-runs-its-compiled-var` narrowed to non-leaves plus "the changed helper is a leaf".

- **Census on default:** 1,095 of 4,457 SCI-runnable `src` functions are leaves; 88 call a resolving function.
  None of the named expensive ones is a leaf: `seon.id/digest` calls `seon.id/id`, which calls `hex`,
  `default-length` (a `def`) and `structural-digest`; `seon.print/form-text` calls `literal`; `core-text` calls 6
  and `brief` 8 program functions. The interop loops of `seon.id` sit in leaves (`structural-digest`, `hex`).
- **Proofs with the rule, throwaway check JVM:** `seon.sci.runtime-test`, the P1 proof (a branch changes the leaf
  `seon.print/literal`; its caller `form-text` answers `"(BRANCH BRANCH)"`), the narrowed invariant,
  `cluster-contexts-share-no-writable-sci-stock-vars`, `evaluation-custody-is-derived-only-from-the-cluster-context`
  and `seon.sci.branch-execution-test` are green, except `branch-definitions-and-unchanged-callers-share-one-context`,
  red on HEAD too: 25 vars, 139 assertions.
- **The cost table on a changed branch** (`ordinary-operations-cost-no-more-than-their-estimates`), MB allocated:

  | Row | HEAD | wire | wire + leaf |
  |---|---|---|---|
  | `:similar-by-name-warm` | 57 | 2,453 | 2,452 |
  | `:seon-db-nine-forms` | 981 | 1,452 | 1,435 |
  | `:hub-behavior-change` | 880 (1.0 s) | 1,037 (4.1 s) | 1,049 (1.8 s) |
  | `:current-state-with-task` | 207 | 308 | 245 |
  | `:reuse-one-unchanged-test` | 30 | 144 | 62 |
  | `:redefine-one-form` | 332 | 343 | 341 |

- **Per request, after the hub change, wire vs wire + leaf** (one probe JVM each): 300 `seon.id/digest` calls 475
  vs 25.5 MB (unchanged agent 24.4 MB): fixed. `my.program/similar` 2,449 vs 2,449 MB: unchanged.
- **Why it misses:** the hot interop sits in functions that call one trivial program helper, so they are not leaves:
  `seon.embed/nearest` (a `FloatBuffer` dot product; calls `float-view`), `seon.fn.analyzer/invoke-kondo` (379 MB
  self in nine-forms on a changed branch, absent unchanged; calls `cache-directory`, `config-directory`,
  `analysis-config`) and `seon.db/query-index-patterns` (181 vs 4.6 MB; calls `pull-index-patterns`). None of them,
  nor `seon.id/id`, is in the reverse closure of `seon.db/as-of-key` (2,395 functions).
- **SCI interop per call** (fork `b6c51cd9`, a type-hinted `FloatBuffer` dot product, 192,000 inner iterations, two
  `.get` each): compiled 6.7 ns and 0.26 B per iteration, SCI 176 ns and 418 B (26x). The same loop without interop:
  5.8 vs 44 ns (7.6x). So an interop call costs about 65 ns and 190 B interpreted: the call site's inline cache
  (`sci.impl.evaluator`) finds the method list, then `sci.impl.interop/invoke-instance-method-with-methods` allocates
  an argument array, boxes each argument and result and reflects through `Reflector.invokeMatchingMethod` every call.

Ruled out by the owner's test ("doesn't really help ... cut it"): the rows stay far from HEAD. Not landed.

- **Interop made cheap, tried (lane sci-interop, SCI pin 33039c6e, commit 34d64a09e):** a cached call site invokes
  its resolved method through one invoker per method (an unreflected `MethodHandle` behind a
  `MethodHandleProxies` instance of `sci.impl.reflector.Invoke0..8`), with no argument array; and every SCI
  function, which carries metadata, now holds it itself instead of being wrapped by `with-meta` in a `RestFn`
  that sent every call through `applyTo`. The dot product falls from 418 to 148 B per iteration (the same loop
  without interop: 89); interpreted `seon.id/id` from 164 to 74 KB per call (compiled: 15). The rows with the wiring
  patch, MB allocated: `:similar-by-name-warm` 2,455 -> 841 (HEAD 56), nine forms 1,442 -> 1,384 (HEAD 973),
  hub behavior change 1,037 MB / 5.9 s -> 988 MB / 2.0 s (HEAD 871 MB / 1.0 s), current state with task
  308 -> 289 (HEAD 235), one unchanged test 143 -> 55 (HEAD 29). What remains is interpretation, not interop:
  `seon.embed/nearest`'s numeric loop boxes each long, double and float (about 426 MB for one call), and on a
  changed branch the wire's arming (`seon.schema/contract-references`,
  `seon.schema.internal/assert-compilable-schema!`), `seon.id/structural-digest` and
  `seon.db/query-index-patterns` dominate. Each SCI function call still derefs the dynamic
  `sci.ctx-store/*ctx*` (`sci.impl.fns/in-defining-ctx`), which allocates a `MapEntry` in `Var.getThreadBinding`:
  about a tenth of interpreted `seon.id/id`'s allocation.
- **What would make the rule work:** compile what does not reach the change, not what calls nothing: on a changed
  runtime, interpret only the reverse closure of the changed names (cached analyses, so O(closure) binding) and keep
  every other function compiled. That is a closure walk, which this round excluded.
- **The wiring without the rule** is not acceptable on cost: every request on a changed branch pays the rows above
  (`:similar-by-name-warm` 43x, nine forms +48%).

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
