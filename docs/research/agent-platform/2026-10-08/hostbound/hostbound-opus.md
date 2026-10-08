---
title: "Host-bound code and platform changes: what an agent can prove at its REPL, and what root does for the rest"
type: research
status: active
created: 2026-10-08
lane: hostbound-opus
---

# Host-bound code and platform changes from an agent's REPL

What exists: an agent's branch runs in a fork of the cluster base SCI context. The base binds every loaded program
function to its compiled JVM Var root (`seon.sci.eval/install-first-party-namespaces!`); acquiring a branch interprets
the branch's overridden functions plus their whole static reverse closure from stored source
(`seon.sci.eval/interpret-overlay!` over `seon.fn/reverse-closure`); a host-bound declaration
(`:seon.fn/host-bound?`, from `seon.fn/host-bound-callers`) keeps its compiled Var or refuses by name
(`seon.sci.eval/install-row!`). Platform loops (the eval path, the writer, Flow procs, boot) run the JVM's compiled
Vars and see a change only after the commit is loaded by a new JVM (`bin/seon start --head`). Platform changes are
proven today by the throwaway check JVM (`bin/seon check`). Principal approach of this note: keep that split, make the
boundary visible as **execution evidence** instead of guessing it from the call graph, make the interpreted side cheap
(lazy) and complete (same eval request), and turn the throwaway check into a **root-owned merge step whose result is
data on the agent's task**.

Measured on default (commit `6a244149d` loaded, HEAD `8bfd1c3aa`), 2026-10-08 17:19-17:45 JST, agents
`hostbound-opus-probe`…`probe4` (own branches) and JVM-mode reads. Default was `:degraded` before its 08:30Z restart
(10 over-bound warnings, 26 errored receipts, `seon.cache` at 511.5 of 512 MB) and `:faulted` after it.

## 0. Decisions for the owner, ranked

| # | Decision | Recommendation | Why first |
|---|---|---|---|
| D1 | What proves a branch change: the static reach (today) or what actually executed | **Execution evidence** (RF-3 option 1): a member records the definition digests whose interpreted bodies it executed; a gate (RF-2, `merge!`) accepts a changed digest only when a green member executed it, in a run whose runner is the loaded program (§1.4) | the static reach is wrong in both directions (§1.3): it lists 106 tests for `seon.id/digest`, and it misses every indirect call; and a run driven through the branch's own runner can certify itself (§1.4) |
| D2 | Where platform changes are proven | **Root's `merge!` proves what the branch could not** (option A, §2): resources and schema files by the in-JVM rehearsal (seconds), unexecuted or host code by the throwaway JVM (minutes), results stored as an ordinary test run on the merge request; a **warm check JVM** (A2) only if the queue stays the bottleneck | the only option that runs the real compiled code without changing the shared JVM; "root does these things" is exactly the merge step |
| D3 | How much the branch interprets | **Lazy closure**: bind each reverse-closure member to a stub that interprets on first call; same rule inside the eval request as after it | one override of a widely used function costs 4.7-12.9 s per acquisition today (§1.2); the same eval request sees a different program than the next (§1.1) |
| D4 | Who writes files and commits | **`merge!` writes back and commits** (root's task); the agent never needs the checkout; root batches the restart that loads it | the JVM loads an immutable archive, so a merged program reaches the JVM only through a commit and a restart (§4 step 10) |
| D5 | How a merged platform change reaches the running JVM | decide between restart per batch (today, 43.5 s downtime, agents lose private state) and **in-place adoption from the new archive** for function-body changes (§5 P5) | every platform fix an agent makes ends in this step; with restarts, root must batch, so agents wait |
| D6 | What a test an agent writes can call | Test namespaces' Vars in the agent's SCI on `require`, and SCI `binding` of a host dynamic Var binds the host Var (fork change) | test helpers are absent from every agent context today (§3, measured 0 publics) |

Square pegs the owner should rule on are in §5 (P1 two program stores; P5 adoption is a restart; P2 "a branch never changes the platform"
versus "agents fix the system"; P3 static reach as evidence).

## 1. What an agent's branch can and cannot reach

### 1.1 The mechanism, measured

| Probe (agent `hostbound-opus-probe`) | Result |
|---|---|
| `(in-ns 'seon.id) (defn digest … "zzzzzzzzzzzz")` then, **same eval request**, `[(seon.id/digest 12 [1]) (seon.id/evaluation "a" 1)]` | `["zzzzzzzzzzzz" "2838520d49f8"]`: the core caller `seon.id/evaluation` still ran the compiled `digest` |
| **next eval request**, `(seon.id/evaluation "a" 1)` | `"zzzzzzzzzzzz"`: acquisition interpreted the caller from source |
| JVM mode, `(seon.id/digest 12 [1])` | `"c7cfebc92d5d"`: the host Var is untouched; nothing leaked to the platform |
| next request, `(my.test/check {:seon.test/changed ['seon.id/digest]})` | refused inside `seon.test.runner/program-digest` (its armed output contract saw `"zzzzzzzzzzzz"`): the **test runner itself** ran interpreted on the branch, because it is in `digest`'s reverse closure |

So the rule today: an overridden function and its whole static reverse closure are interpreted in the branch fork
after the eval request settles (`interpret-overlay!`); inside the eval request that defined it, only agent-authored
callers are re-interpreted (`seon.sci.eval/inherited-callers` filters on `::agent-authored?`, LR-STALE2). That is a
REPL-law gap: the forms after a `defn` see a different program than the next eval request does.

The last row is the second finding: interpreting the reverse closure also interprets **platform code that the agent's
own tools call** (`my.test/check` → `seon.test/run` → `seon.test.runner/program-digest`). A branch change to a deep
function can break the agent's own test tool on its branch. That is sound Clojure (it is what a reload would do) but it
means a platform-reaching change makes the agent's tools run its change.

### 1.2 Cost of interpreting the closure

| Measure (JVM mode, default) | Value |
|---|---|
| `seon.fn/reverse-closure` of `seon.id/digest` | 1,490 ms before the restart, 337 ms after it; 2,349 affected symbols, 21 of them host-bound (of 85 on default) |
| `seon.fn/reverse-closure` of `seon.db/connection-object?` | 190-210 ms; 949 affected and 804 tests, through 4 direct callers. The functions that run it most are the armed contracts validating `:seon.db/connection`, through the compiled Malli registry that holds the JVM Var: no call edge, and a branch change to it never runs there (§1.3, 3) |
| `seon.sci.eval/base-ctx` of my branch (one override, 2,350 interpreted) | 12,868 ms first, 4,668 ms again (not cached) |
| `base-ctx` of main | 30 ms |
| raw `sci/eval-string*` of 400 core function sources in a fork | 133 ms total, about 0.33 ms each (342 evaluated; the 58 failures are aliases my raw probe did not install, e.g. `io/file`, `gen/one-of`) |
| an eval request on that branch through `seon.mcp/call`, agent context carried | 98-110 ms wall |
| after default restarted (08:30Z), a new branch with the same one-function override (body unchanged, docstring changed): `base-ctx` | 5,322 ms first, 4,993 ms again; 2,362 interpreted |
| the `defn` itself on that branch (warm, before the restart) | 1,308 ms, 44,513 armed calls: the Var render's pending-test count walks the closure (2,655 `seon.db/read-declarations`) |
| interpreted against compiled, the same source (`seon.id/hex` on a 200 KB byte array, mean of 5) | compiled 3.1 ms, interpreted 1,030 ms: **335×** (interop inside a loop is reflective in SCI) |
| `seon.id/sha-256` on a 2 MB array (the work is host `MessageDigest`) | compiled 0.68 ms, interpreted 1.98 ms: 3× |

The carried agent context hides the acquisition from ordinary eval requests. A test request acquires the candidate
once on a fork and each member forks that (`seon.test` request world: "Acquired once on a fork, never on the
execution's own context: each member forks this"), so the cost is per test request, not per member: every test
request on such a branch, and so every automatic test step after an eval request (F4b, loaded at the restart), pays about 5 s
before its first test runs. A `my.test/check` on the second branch ran over 32 s before the MCP bridge gave up and was
lost to the restart; its total is not measured. Over 1 s is a finding: task
`seon.sci.eval/a-branch-overriding-a-widely-called-function-acquires-in-seconds` (EDN in §6).

The 335× row is the practical limit on interpreting platform code: a branch that overrides a function under a hot
loop makes every test through that loop run interpreted, and the test's time bound, not its assertion, decides the
result.

### 1.3 What stays unreachable, by cause

Interpreting the closure covers ordinary Clojure called from SCI. What it cannot cover, with the reason:

1. **Host-bound declarations** (85 on default). `reify`, `proxy`, `deftype`, `defrecord`, `definterface`, `import`, and
   JVM macros from namespaces SCI does not interpret (`clojure.core.async/go` reads compiler locals from `&env`). Their
   compiled bodies call compiled Vars, so a branch change below them is invisible through them. In `digest`'s closure:
   `seon.flow/join-graph!`, `seon.cluster/serve!`, `seon.sci.eval/install-row!`, `seon.cluster.store/open-store!`,
   `seon.fault/unfold!` and 16 more. By a rough text classification of the 85 sources (a disposable probe, not a
   classifier): 30 `reify`, 12 `deftype`/`defrecord`, 7 `proxy`, 6 core.async `go`, 30 other JVM macros (many are
   test.check generators built with `gen/let`, plus `seon.cluster/serve!`, `seon.render.web/handler`,
   `seon.sci.eval/install-function-from-database!`). The last group is the cheapest to shrink: a JVM macro that does
   not read `&env` can be exposed to SCI as a macro (SCI's `copy-var` of a macro Var), after which its callers
   interpret; `seon.fn/interpreted-namespaces` then counts that namespace. `reify`, `proxy` and types stay host-bound:
   SCI's default `:reify-fn` (`sci.impl.opts/default-reify-fn`, fork at `010c86df`) implements protocols and `Object`
   only; a JVM interface needs a `:reify-fn` that supplies a precompiled class per interface set, which is how
   babashka ships a fixed list. `go` stays (babashka maps it to a thread, which changes parking semantics).
2. **Compiled entry points the platform drives itself.** The eval path (`seon.mcp/call` → `seon.batch` → recording),
   transaction functions on the writer thread (`[:db.fn/call adoption-guard-tx …]`), Flow procs (their transforms are
   Vars), `d/listen` listeners, schedule handlers, the web handler, boot. The overlay lives only in the agent's SCI
   context; these loops run the JVM's Vars. By the owner's rule ("a branch never changes the platform") this is
   correct, not a bug: the agent's own eval request must not run its half-written evaluator.
3. **Indirect references.** The static call graph (`:seon.fn/calls`) misses values captured at load time: a `def` of a
   map of functions, `defmethod` bodies, protocol extensions, a Var named in a Malli schema (`#'seon.db/connection-object?`
   in `:seon.db/connection`), `requiring-resolve`, clojure.test fixtures registered with `use-fixtures`, Flow step
   symbols. A test reaching such a function through an indirect hop runs compiled code and its green proves nothing
   about the branch. No static fix exists; this is why D1 asks for execution evidence.
4. **Host dynamic Vars.** `(binding [seon.db/*read-evidence-sink* sink] …)` in SCI binds SCI's copy only; host code
   reading the Var sees nothing (pilot friction 5). A test cannot observe reads, profiles or any dynamic-Var-carried
   behavior of compiled code.
5. **Not program entities at all.** Schema resources (`resources/seon/schemas`), `config/`, `deps.edn`, `bin/`,
   `script/`, submodule pins, boot order. These need a JVM that boots them.
6. **Test namespaces.** Absent from the agent's SCI (§3).

Where interpreting more stops being sound:
- **Identity.** An interpreted function is a new object. Code comparing functions (`identical?`, a cache keyed by a
  function, a registry holding the compiled object) sees two worlds. Same for types: a `defrecord` re-evaluated in
  SCI is another class, so `instance?` across worlds fails; that is why such declarations are host-bound.
- **State.** Re-evaluating a `def` of an atom or a `defonce` makes new state. The overlay interprets function rows
  only, which keeps this sound; it must stay that way.
- **Termination.** SCI interrupts interpreted code only (AGENTS.md "Bound admission"); more interpreted platform code
  is more interruptible, not less, so this does not limit it.
- **Speed.** 3× where the work is in host calls, 335× for a loop with interop (§1.2). A test bound that fires only on
  a branch is the symptom. Interpreting the closure is sound but can be useless for hot platform code: that code is
  proven in option A's JVM, not on the branch.

Extending LR-STALE2 to core callers inside the eval request is sound under the same rules (it is what the next eval
request already does). Extending interpretation beyond the static closure is not possible without execution
observation, and beyond host-bound declarations is not possible in SCI at all.

### 1.4 The judge must not run the defendant's code

The §1.1 refusal came from `my.test/check` called at the agent's REPL: its SCI stack trace named `my.test/check` and,
in the earlier attempt, `seon.test/run` at its source line 2236, so both ran **interpreted from the branch** because
both are in `seon.id/digest`'s reverse closure. A branch that changes anything under the runner (reach digests,
result capture, the report) therefore tests itself with its own changed runner when the agent calls `my.test/check`.
A change that makes the runner report green would certify itself.

The automatic step (F4b) and root's `merge!` are entered from compiled code (`seon.turn`, root's merge task), so their
runner is the loaded platform even when the members interpret the branch. The rule that follows, and that RF-2 and
`merge!` must apply: **evidence counts only from a run whose runner is the loaded program** (the run records its
runner's program identity: the loaded commit), never from a run driven through interpreted platform code. Options:
1. (recommended) Record the runner identity on each run and refuse others as evidence; the agent may still call
   `my.test/check` on its branch to see what its changed runner does, which is useful when the runner is the subject.
2. Make the `my.*` tools always bind their compiled Var on a branch (a branch cannot change its own tools). Simpler,
   but an agent fixing `my.test/check` can then never try its fix at the REPL.

## 2. For what remains: options

The remaining set is (1), (2), (5) and the indirect part of (3) when the indirect hop is compiled platform code.
Each option below with guarantee, cost, how the agent sees results and how root reviews.

| Option | Guarantee | Cost | Agent sees | Root reviews |
|---|---|---|---|---|
| **A. Root-run isolated JVM as a merge step.** The agent changes the code on its branch, its interpretable proofs run there; `merge!` sees changed digests with no executed evidence (D1) or a file class from §1.3 (5), and runs the isolated check as part of the merge, on the merge branch's write-back archive; the run is stored as `:seon.test.run` on the merge request | real compiled code, fresh boot from the exact archive, the store a disposable copy | today 2 min 20 s - 3 min 35 s per check plus queueing for one of two slots (repl-first §1; pilot: 207 s, 126 s of it waiting) | the merge request's run in its current-state block: red members as data, through the value printer | the diff value plus the stored run; nothing new to learn |
| **A2. A warm check JVM** owned by root (a throwaway root kept booted on a disposable store at the main commit); each isolated run sends the changed files, reloads them (`clj-reload` or `require :reload` in order), runs the named tests, then resets to the main commit | as A for function bodies; weaker for boot, schema publication and dependency changes, which still take a fresh boot | one JVM resident (heap cap 4 GB by dial, actual much less unmeasured); a run is reload (30-1,600 ms per namespace, PB-03a) plus tests | same as A | same as A |
| B. Per-agent hot-reload JVM | compiled semantics per agent | one JVM per agent: a second store (the store lock is single-process), boot 38 s (`ready-ms` 38,157 on default), memory per agent; 16 GB fits two or three | its own REPL, a second entrance | two worlds to diff |
| C. clj-reload in the main JVM on a disposable branch | none: a reload replaces global Var roots for every cluster and agent | cheap | — | — |
| D. A second Clojure runtime in a child classloader in-process (shimdandy pattern) | compiled semantics | a second full Seon load in the heap, the same store-lock problem, classloader leaks | — | — |
| E. Make platform code interpretable: thin host-bound shells around pure functions | moves code from (1)/(2) into the interpretable set | refactors, one per shell | its ordinary branch proofs | ordinary merge |

**The check is already two steps, and only one needs a JVM.** `seon.operator/check!` first rehearses in the running
JVM (`seon.cluster/rehearse-source!`: the candidate archive is published and adopted onto disposable branches of an
in-memory overlay of the store; its docstring records 1.6 s for HEAD, 5.1 s for one changed test file, 6.5 s for three
files with a schema resource), then boots the throwaway JVM only when tests are named (`candidate-tests!`). Its own
known issue: host code does not reload in the rehearsal. So the rule for option A splits by what changed:

| Changed | Proof | Cost |
|---|---|---|
| ordinary functions, tests, an agent's `register!`ed schema | the branch (D1), then `merge!`'s run on the merge branch | seconds |
| schema resource, config file, task resource written back | the rehearsal, run by root's `merge!` in the live JVM | 2-7 s |
| a function no green member executed interpreted (host-bound, platform loop, indirect hop), boot or publication code | the throwaway JVM | 2-4 min |
| `deps.edn`, a submodule pin (fork bump), `bin/`, `script/` | the throwaway JVM, then root's restart; root-only (it changes the classpath every agent shares) | 2-4 min plus restart |

C is refused: it is a reload, which is adoption, and adoption is root's after merge. D adds a second system. B is A2
per agent with a second store and no reviewer; it fails the 16 GB rule with more than two agents.

**Recommendation: E as a standing direction, D1 for everything interpretable, A for the rest, A2 when the check
queue is the bottleneck.** The agent's flow does not change: `defn` on its branch, proofs run, `request-merge!`. What
changes is that `merge!` knows, from execution evidence, which changed digests no green member executed, and for
those (and for every non-program path) it runs the isolated check as one of its steps and keeps the merge branch
until it is green. Root's restart (`start --head`) after a landing is unchanged.

Guarantee of the combination: every merged digest either executed interpreted under a green member on the merge
branch, or ran compiled under a green member of the isolated check. Nothing merges on reach alone.

What the agent does at its REPL, per case (the same forms in every case; only what proves it differs):

| The agent changes | At its REPL | Its reply shows | Then |
|---|---|---|---|
| an ordinary function | `in-ns`, `defn`; the step runs proofs and reaching tests | reaching results, with each changed digest marked executed or not (D1) | `request-merge!` |
| a function under a host-bound caller or a platform loop | the same `defn`; it can call the function directly and test it directly | the digest marked "not executed by any green member", with the reason (the host-bound caller or the compiled entrance it sits under) | `request-merge!`; `merge!` adds the isolated run |
| a host-bound declaration (`reify`, a type, `go`) | the `defn` is recorded (agent branches do anything); `install-row!` answers its refusal ("must change through the loaded source files") as the evaluation's value and binds a refusing root for later calls (read in source, not probed here) | "host-bound: proven by root's isolated run at merge" as data, with the request form | `request-merge!`; the isolated run is its only proof |
| a schema | `register!` on its branch; its data writes see it | the usual | `request-merge!`; write-back plus rehearsal at merge |
| a dependency or fork pin | nothing at the REPL: it is not a program entity | — | a root task; root runs the throwaway check and restarts |

Outside lanes follow the same rule through RF-2: `bin/lane commit --branch-evidence` accepts the executed digests and
sends the rest to `bin/seon check`, so a lane changing ordinary functions never boots a JVM, and a lane changing the
evaluator still does.

## 3. Agent-friendly fixtures

What an agent at its REPL needs to write and run a test: `deftest` in the test namespace, the helpers that namespace
uses, a world to run in, and a way to run exactly that test.

| Need | Today | Change |
|---|---|---|
| `deftest` in a file-backed test namespace | refused by the writer's digest check (pilot friction 3); fixed in `7f0458ffd` at HEAD, not yet loaded on default | prove after the next restart |
| helpers (`seon.test-support/with-database`, `agent-value`, `evaluate`) | **0 public Vars** in the agent's SCI for `seon.test-support`, `my.program-test`, `seon.id-test`; the JVM holds 53, 7, 7; the database holds 67 rows for `seon.test-support` (64 not host-bound) | cause verified after the 08:30Z restart: on a fresh JVM `(find-ns 'seon.test-support)` is nil (tests load it later), `install-first-party-namespaces!` binds only namespaces `find-ns` holds when the base is built (`:when host-namespace`), and the base is cached per loaded commit, so a namespace a test run loads later never reaches any agent. Fix at the owner: an agent's `require` of a first-party namespace the base lacks host-`require`s it and binds its Vars as the base does (SCI's `:load-fn` is the seam), and the base advances by that namespace (the PB-03 advance shape) |
| the world | `with-database` is already the right thing: a branch off the member's commit through `seon.cluster.agent/acquire-context!`, released after; the runner already gives each member its own branch | nothing new; teach it in the skill as the one fixture, and keep `fresh-store?` for store-global subjects |
| run exactly one test | `my.test/check` selects by reach only; naming a test as changed refuses (pilot friction 11); `seon.test/run` with `:seon.test/identities` exists but its `:seon.test/execution` is not supplied to an agent's call (measured: refused, missing `:seon.test/execution`) | `my.test/check` accepts `:seon.test/identities` (the request schema already has the key) |
| helpers the agent writes that need its world | an agent-authored function declaring `:my.program/context` and `:seon.db/connection` in its map argument is **not supplied** (measured: `hostbound.scratch/probe-run` refused, missing both) | supply fills declared world members in a map argument for agent-authored functions too (AGENTS.md "Supply defaults"; pilot friction 12 is the same class) |
| observe compiled behavior (reads, allocation, calls) | SCI `binding` of a host dynamic Var does not reach host code | fork change in SCI: binding a copied host dynamic Var also pushes the host thread binding (owner: fork freely); plus the eval-alloc measure for cost proofs |
| failure output | clojure.test's reporter prints into `:out` | the runner's report as data (RF-6 row 6) |

The test-support namespace is 1,309 lines mixing two audiences: agent-facing fixtures (`with-database`,
`fork-cluster-ctx`, `agent-value`, `evaluate`, `reply`, `refusal-data`) and platform fixtures (published roots,
operator roots, file stores, provider servers). Split it: the agent-facing part is small and interpretable; the platform
part is what option A runs.

## 4. The change → merge path end to end, step by step

| # | Step | Works today? | Fix |
|---|---|---|---|
| 1 | `defn` on the agent's branch | yes, 1-56 ms | — |
| 2 | later forms in the same eval request call through core callers | **no**: core callers run compiled until the next request (§1.1) | D3: re-interpret core callers in the request as acquisition does, lazily |
| 3 | tests reaching the change run automatically | before the 08:30Z restart, proofs only (F4a): my probe had no task, nothing ran, and the Var render listed 106 pending tests; after it, every reply carries `:seon.turn.loop/tests` with a `:seon.turn.loop/reaching` summary (F4b loaded) | with 106 reaching tests and a 5 s acquisition (§1.2) the 10 s step bound leaves most pending, so D3 comes before the step is useful for deep changes |
| 4 | the reaching set | static reach; 2,349 callers and 106 tests for one deep function | keep as the selector, but D1 decides proof |
| 5 | each test runs the branch's code | yes for the interpreted closure; no below host-bound members, through indirect hops, or in platform loops (§1.3) | D1 records what executed; D2 for the rest |
| 6 | acquisition cost of such a branch | 4.7-12.9 s per acquisition | D3 lazy stubs; cache the overlay by loaded commit plus override digests, not by branch commit |
| 7 | write-back (`my.program/write-branch!`) | yes (pilot: 52-484 ms), with frictions: undigested `in-ns` namespaces block it (pilot 8), same-request call answers a merge message (pilot 10) | those tasks |
| 8 | commit | `bin/lane commit` always boots the check (207 s in the pilot); `seon.cluster.branch-evidence/branch-evidence` landed (`871a28901`) but is not wired | RF-2 wiring, with D1 as its proof rule |
| 9 | merge request and `merge!` | gaps G1-G11 (merge.md §7): new contracted functions cannot arm on the merge branch, schemas block diff, tools throw, merge lands on the checked-out branch | merge.md rows; add the isolated step (D2) |
| 10 | the merged program reaches the JVM | only by commit and restart: default runs from the immutable archive `data/source/6a244149…` (its `user.dir`, `seon.fs/source-directory` and classpath), so `require :reload` can only reload the archive's bytes, and development adoption refuses bytes that differ (`verify-development-sources!`) | D4: `merge!` writes back and commits; root batches `start --head` (43.5 s measured) or adopts in place (D5) |
| 11 | RED-5 (cached program classes shadow reload) | default has no program classes on its classpath (`seon/id__init.class` resolves to nothing); only check JVMs use them | RED-5 only matters for A2's warm JVM: it must reload from source |
| 12 | the agent's next context | a branch survived a restart in the repl-first run but mine did not (§6, other findings); private SCI defs never survive (repl-first friction 10); the agent sees the merge result only by polling the request | the merge request's state in the agent's current-state block |

## 5. Square pegs

**P1. Two program stores.** The JVM loads files from a git archive; SCI interprets Datahike program entities. An
agent's change is a database change; landing it is a file change plus a git commit plus a restart. Every gate
(write-back's reindex proof, RF-2's file = branch check) exists to keep the two equal. Options:
1. Database first, files derived (recommended): `merge!` writes back and commits as one root step; the agent never
   touches files; RF-2 is root's tool, not the agent's. Cost: `merge!` grows a git step; root owns the checkout.
2. Files first: an agent branch is a git branch and the database indexes it. Cost: agents edit files, losing the
   REPL as the medium; contradicts "files are the write-back".
3. Keep both equal by gates (today). Cost: two merges and two proofs per change, and lanes keep hitting the seams
   (pilot frictions 8-10).

**P2. "A branch never changes the platform" versus "agents fix the system from the REPL".** Both are right; they meet
at platform code. Options:
1. Accept the split (recommended): the interpretable part is proven at the REPL, the rest by root's isolated step
   (D2), and E shrinks the rest over time.
2. Per-agent JVMs (B): full compiled fidelity, at 16 GB fits two agents.
3. Let a development cluster's agents reload the shared JVM: fast, but every other agent runs the change; only safe
   with one agent.

**P3. Static reach as evidence.** The call graph selects tests well enough but cannot prove execution (§1.3, 3). RF-3
option 1 makes evidence positive. Options: execution evidence (recommended); static classification of platform
callers (over-classifies: nearly everything is reached from a compiled caller); a declared platform flag (a roster,
forbidden).

**P5. Adoption is a restart.** Default's JVM is pinned at boot to the archive of its commit (`user.dir`, the
classpath and `seon.fs/source-directory` all `data/source/6a244149…`), so a merged change to platform code reaches it
only by `start --head`. Measured for the 08:30Z restart (`operator-start-20261008T173059.461.edn`): total 43.5 s
(down 3.0 s, launch 28.5 s, adopt 6.7 s, archive 2.8 s), every agent's private SCI state lost, the cluster
`:faulted` and busy with test members for minutes after. The in-place path exists (development adoption,
`seon.cluster/load-development-definitions!`, `require :reload` in requires order) but reads the classpath, which is
the old archive, and `verify-development-sources!` refuses differing bytes. clj-reload shows the seam that frees it
from the classpath: it loads each namespace from a file's content with `Compiler/load` (`clj-reload.util/ns-load-file`,
`reference-code/clj-reload` at `61c6fa7`), so a JVM can load the new archive's files without a new classpath.
clj-reload also `remove-ns`es before loading, which Seon must not copy: Flow graphs, the SCI base and other holders
keep the old Var objects, while Seon's `require :reload` keeps each Var and replaces its root. Options:
1. Restart per landing batch (today). 43.5 s of downtime per batch and lost private state; nothing new to build.
2. **(recommended for function-body changes)** In-place adoption from the new archive: the loaded source directory
   becomes part of the loaded identity and advances with adoption; changed namespaces load with `Compiler/load` from
   the new archive in requires order, keeping Vars; PB-03 then advances the base. Changes to types, protocols, boot,
   schema resources the boot path reads and the classpath still restart. Cost: reload 30-1,600 ms per namespace
   (PB-03a) instead of 43.5 s, and agents keep their state. Sacrifice: a reload hazard class (instances of a
   redefined record, a `defonce` holding an old function) that a restart never has; RED-5 must hold (no cached
   class shadows a changed source).
3. A second default JVM started on the new commit while the old one serves, then switched: no downtime but two heaps
   and a store-lock handover the operator does not have.

**P4. Whole-closure interpretation.** Interpreting 2,350 functions to change one is whole-program work for any widely
called function. The lazy stub (D3) makes cost follow what executes: each closure member's SCI Var holds a function
that interprets the member's source on its first call and replaces its own root. Cost per acquisition: one stub per
member (a Var root assignment) plus the closure walk, which must itself drop below 1 s (LR-STALE3 family).

Probed on a plain `sci/init` context (no shared state): `a/mid` bound to a stub that evaluates `(defn mid …)` in
namespace `a` and then applies `@#'a/mid`; `b/top` calling `a/mid` answered 110 then 120 with one install. SCI callers
resolve the Var, not its value, and `defn` on an existing SCI Var keeps the Var, so callers see the interpreted body
from the first call on. The stub also answers HB-2: a same-request redefinition installs stubs for the closure, which
costs the walk, not the interpretation. Two limits: concurrent first calls interpret twice (harmless for a `defn`, so
no lock), and the stub must carry the row's metadata (`:arglists`, `:doc`, the contract) so `doc` and the Var render
read the same Var before and after.

## 6. Rows, cards and tasks this implies

Cards (proposed ids; root assigns):

- **HB-1 Execution evidence** (= RF-3 option 1; Opus). The runner records per member the definition digests of the
  branch's interpreted bodies that executed. The seam exists: `seon.profile` keeps one cell per symbol and scope, and
  interpreted calls count in the `:seon.profile/context` cell apart from `:seon.profile/host` (measured: `my.test/check`
  has both). The cells are process-cumulative, so a member needs its own capture (the armed wrapper reports into a sink
  bound for the member and conveyed to its threads), not a before/after diff that concurrent members pollute. RF-2 and
  `merge!` accept a changed digest only when a green member executed it, in a run that records its runner as the
  loaded program (§1.4). Proof:
  `seon.test.runner-test/a-member-records-the-branch-digests-it-executed`, plus one test where a reaching test passes
  through a `defmethod` hop and the change is reported unexecuted.
- **HB-2 Same-request core callers** (Opus, one REPL path). The forms after a `defn` see it through core callers as the
  next eval request does. Proof: `seon.repl-differential-test/a-core-caller-sees-a-same-request-redefinition`
  (`(seon.id/evaluation "a" 1)` after redefining `seon.id/digest` answers the new value in the same request).
- **HB-3 Lazy closure overlay** (Opus). `interpret-overlay!` binds stubs; cost follows executed members. Proof: the
  acquisition of a branch overriding `seon.id/digest` costs the closure walk (itself under 1 s) plus the members a test executes, and HB-2 green.
- **HB-4 Test namespaces in the agent's SCI** (Opus: base construction; cause verified in §3). Proof:
  `(count (ns-publics 'seon.test-support))` positive in an agent context, and an agent `deftest` using
  `support/with-database` runs green through `seon.mcp/call`.
- **HB-5 `my.test/check` runs named tests** (Sonnet). `:seon.test/identities` accepted. Proof: naming one test runs
  exactly it.
- **HB-6 Host dynamic binding from SCI** (Opus, SCI fork). Proof: `(binding [seon.db/*read-evidence-sink* s]
  (seon.db/q …))` in SCI appends to `s`.
- **HB-7 Isolated run as a merge step** (Opus, merge spec). `merge!` runs the isolated check when D1 leaves a digest
  unexecuted or a non-program path changed; the run is stored on the request. Proof: a merge request changing a
  host-bound function stops with the isolated run named, then merges when it is green.
- **HB-9 A host-bound change answers its route** (Sonnet). `install-row!`'s refusal "must change through the loaded
  source files" tells a product agent to do what it cannot. It becomes data naming the route: the change is recorded
  on the branch and is proven by root's isolated run at merge, with the `request-merge!` form. Proof: a branch
  redefinition of a host-bound function answers `:seon.sci.eval/proven-at-merge` (or the variant the errors spec
  names) and `my.program/diff` lists it.
- **HB-10 In-place adoption from the new archive** (Opus, boot-critical; only if the owner picks D5 option 2). The
  loaded source directory advances with the loaded identity; `load-development-definitions!` loads each changed
  namespace from the new archive's file with `Compiler/load` in requires order, keeping Vars (no `remove-ns`);
  types, protocols, boot-path and classpath changes still restart. Depends on RED-5 and PB-03b. Proof:
  `seon.cluster.adoption-reload-test/an-adopted-source-change-replaces-the-var` (RED-5's proof) run with the new
  archive in a different directory from the boot archive, plus an agent's private def surviving the adoption.
- **HB-8 Warm check JVM** (research then Opus; only if the queue stays the bottleneck after RF-2). Research: how
  clj-reload (`reference-code/clj-reload`) orders unload and reload, keeps `defonce`, and whether a reset to the main
  commit is cheaper than a reboot.

Tasks for their owners (the task resources are held by lane `task-census`; EDN ready to add):

```clojure
;; resources/seon/tasks/seon.sci.eval.edn
#:seon.task{:name "a-branch-overriding-a-widely-called-function-acquires-in-seconds"
            :kind :function
            :intent "`seon.sci.eval/base-ctx` of a branch whose only change is `seon.id/digest` interprets its whole reverse closure (2,350 functions) and takes 12.9 s first, 4.7 s again (main: 30 ms); `seon.fn/reverse-closure` alone is 1.5 s. Done when acquisition cost follows the closure members that execute."
            :must-read #{seon.sci.eval/interpret-overlay! seon.fn/reverse-closure}
            :evidence #{"docs/research/agent-platform/2026-10-08/hostbound/hostbound-opus.md"}}
#:seon.task{:name "a-core-caller-runs-compiled-in-the-request-that-redefines-its-callee"
            :kind :function
            :intent "After `(defn seon.id/digest …)` on a branch, `(seon.id/evaluation \"a\" 1)` in the same eval request runs the compiled `digest`; the next request runs the branch's. `inherited-callers` re-interprets only agent-authored callers. Done when the same request answers as the next one."
            :must-read #{seon.sci.eval/inherited-callers seon.sci.eval/reinterpret-inherited-callers!}
            :evidence #{"docs/research/agent-platform/2026-10-08/hostbound/hostbound-opus.md"}}
#:seon.task{:name "test-namespaces-have-no-vars-in-the-agent-context"
            :kind :function
            :intent "`seon.test-support`, `my.program-test` and `seon.id-test` have 0 public Vars in an agent's SCI context while the JVM holds 53, 7 and 7. Done when a test namespace's Vars resolve in the agent's context once it is required."
            :must-read #{seon.sci.eval/install-first-party-namespaces! seon.test-support/with-database}
            :evidence #{"docs/research/agent-platform/2026-10-08/hostbound/hostbound-opus.md"}}

;; resources/seon/tasks/seon.sci.edn (or the owner of supply)
#:seon.task{:name "an-agent-function-is-not-supplied-its-world-members"
            :kind :function
            :intent "An agent-authored function whose map argument declares `:my.program/context` and `:seon.db/connection` refuses both as missing when called with `{:ids …}`; first-party functions are supplied. Done when supply fills declared world members for agent-authored functions."
            :must-read #{my.test/check}
            :evidence #{"docs/research/agent-platform/2026-10-08/hostbound/hostbound-opus.md"}}
```

Other findings met while probing (not this note's subject; for root to file):
- JVM-mode `eval_clj` as `root` while root's agent had a run open answered the value and then "The run was evaluated
  but not recorded." (`run transition refused: agent-already-running`). agent-loop.md §3.2 says MCP JVM evaluation is
  not serialized; its recording still collides with the agent's open run. An outside agent using JVM mode as root
  loses its record whenever root is busy.
- After the 08:30Z restart my first probe branch (`:agent-75d011efa023`) was no longer in `d/branches`; the
  repl-first note saw a branch survive a restart. Whether root reset or the restart unlinked it is not established.
- An outside agent cannot clean up its own branches. `(my.agent/retire-branch! {:seon.store/branch <own>})` from the
  owning MCP agent answers `run transition refused: admission-closed` and the branch stays; from another agent it
  refuses "still has a connection in this process". Branches `:agent-37c7d503cf6b`, `:agent-e993b350ef75` and
  `:agent-b9e9a06bccf6` (this lane's probes) remain for root. Same class as merge.md G8.
- The reaching-test summary on a reply that only called `retire-branch!` read `{:seon.test/passed? false
  :seon.turn.loop/red []}`: not passed, with nothing red and nothing named as run.

## 7. Proposed split (owner message: extra lanes)

The note answers the five questions at design level. Three parts are bigger than one note and want their own lanes:
(1) HB-1 execution evidence design and its gate rule, Opus, with SCI's call hook as the seam; (2) HB-8 research on
warm reload JVMs (clj-reload, tools.namespace, nREPL `load-file`, Babashka), Sonnet with web access; (3) the
test-support split and HB-4/HB-5, Sonnet.

## 8. Limits of this note

- Not measured: the total of a `my.test/check` on a deep-override branch (lost to the restart), the warm check JVM's
  memory, the slowdown of the runner itself when interpreted. Refuted by reading `seon.test`: per-member acquisition
  (it is per request).
- Default restarted at 08:30Z mid-probe (not by this lane); measurements before it ran on `6a244149d`, after it on
  the restarted root, which was busy running about 80 test members while I probed, so its wall times are upper bounds.
- The 400-source interpretation probe used `sci/eval-string*` without installing each namespace's aliases; the real
  install path (`install-function-from-database!`) does, so the 15% failures are my probe's, not the program's.
- All probes ran on my own agent branch or read-only in JVM mode; default's program and JVM Vars were not changed
  (verified: `(seon.id/digest 12 [1])` in JVM mode answers the compiled value).
