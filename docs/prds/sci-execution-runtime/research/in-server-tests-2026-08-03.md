---
type: research
status: active
tags: [research, testing, runtime]
---

# In-server test execution: one runner, three callers

## Verdict

The design intent is sound and the prize is large — **10.32 s spawned versus
~25 ms warm in-process for the same namespace** (measured below) — but it is
gated on two facts nobody had checked:

1. **The live cluster JVM has no `test/` on its classpath.** `bin/seon` starts
   clusters with `-M:dev` (`script/seon/fresh_operator.clj:244-245`), and
   `test/` lives only in the `:test` alias (`deps.edn` `:test` `:extra-paths`).
   I probed the live default cluster: 110 classpath entries, none ending in
   `/test`.
2. **Classpath cannot be added from a prepl eval.** Each prepl evaluation gets
   a fresh `DynamicClassLoader` stack (probed: root DCL identity `1214946665`
   on one eval, `1343170620` on the next; `.getURLs` empty after an `addURL`
   in the previous eval). So the fix is a launch-line change (`-M:dev:test`),
   not runtime classloader work.

And it carries one real hazard the spawned path does not have, which I hit on
the first live probe (§4).

Recommended shape: **`seon.operator/test!`** — one function, three callers,
`test/` on the cluster launch classpath, `require :reload` before running,
run on a named interruptible thread, return a report value. `bin/test` keeps
the spawned path for the `--full`/long tier and for every process-unclean
namespace, and that class stays **declared** (§2), not guessed.

## What I read end to end

`bin/test` (whole file), `src/seon/test/runner.clj` (574 lines),
`test/seon/test_support.clj` (306 lines), `script/seon/dev/changed_test.clj`
(521 lines), [operator-integration/README.md](../../operator-integration/README.md)
(whole), [test-call-edge-design-2026-08-03.md](test-call-edge-design-2026-08-03.md)
(verdict + ledger), and the `plan/unsettled.md` 2026-08-03 evening rulings
block (lines 3162-3189). Probe scripts are committed at
`tmp/in-server-test-probe.clj` and `tmp/in-server-test-probe2.clj`.

## 1. Current mechanics, and what each protection buys

| Protection | Where | Guards against | In-server disposition |
|---|---|---|---|
| Script byte snapshot re-exec | `bin/test:16-22` | Bash splicing new bytes of an edited script into a running invocation (cost a 36-minute gate) | **Not needed** — the caller is a function, not a re-read script |
| Isolated operator root per run (`tmp/test-runs/run.XXXXXX`) | `bin/test`, `mkdir`/`mktemp` block | Tests writing into the developer's real operator root: process records, advertisements, logs, store | **GIVEN UP for in-server.** Any test that writes an operator root must stay spawned |
| Copy-on-write clone of `bin config resources script src test` | `bin/test` clone loop (`cp -c`/`--reflink`) | Canonical-path analysis resolving through symlinks into the real checkout; tests mutating tracked source | **GIVEN UP.** In-server runs against the live checkout |
| Retention/reaping policy (3 newest inactive roots, 24 h, pid liveness) | `bin/test` bb reaper block | Unbounded run-root growth while keeping failure evidence | Not applicable; in-server keeps no root |
| No-follow recursive delete via `seon.fs/delete-recursively!` | reaper + cleanup calls | The 2026-07-29 symlink-following deletion incident | Unchanged (not used in-server) |
| Liveness backstop: 300 s reporter silence → thread dump → `Runtime.halt(124)` | `runner.clj:266-300`, `silence-seconds` | A wedged suite hanging CI/the developer forever | **Must be replaced** — `halt` in a live cluster JVM kills the system (§4) |
| Tier selection from `:seon.test/long` metadata | `runner.clj:391-424` | Process/boot-bound giants in the fast loop | Reusable, but see the missing-fact defect in §3 |
| Per-test capture → `:seon.test.result` facts (opt-in cluster) | `runner.clj:232-293, 308-369`; refuses `default` (`runner.clj:337-343`) | Result facts polluting the owner's cluster | Directly reusable as the agent-visible report (§5) |
| `seon.dev.changed-test` selector | `script/seon/dev/changed_test.clj` | Running the whole gate for a one-file edit | Selection logic is reusable; **its executor is not** — it shells `bin/test` twice (`run-operator!`/`run-writer!`, lines ~370-385) with a 300 s process timeout |

The selector's own analysis is a **second clj-kondo run** over `src` +
`script/seon/dev` + operator/writer test roots (`analyze-host`,
`host-corpus`), rebuilding a namespace-dependency graph that the program
graph already holds as facts. That duplication is a separate defect; it is
also the reason the selector is slow enough that nobody runs it casually.

## 2. Fixture isolation

`seon.test-support/with-database` is the default fixture (44 of 95 test files
use it). It is **entirely in-memory**:

- one canonical base per JVM: `{:store {:backend :memory :id (random-uuid)}
  :commit-graph? false :keep-history? true}` (`test_support.clj:86-100`),
  populated once through the production `cluster/populate-source!`
  (`test_support.clj:59-73`) from one shared source manifest delay
  (`test_support.clj:35-39`);
- each invocation gets a **branch of that in-memory base**
  (`with-branched-database`, `test_support.clj:257-278`), deleted in `finally`,
  with a **branch-name lease pool** so retained heads are bounded by peak
  nested concurrency, not by trial count (`test_support.clj:41-48`);
- `:seon.test-support/database-id` / `:fresh-store?` take a slower
  fresh-memory-store path (`test_support.clj:243-255`) — 6 files use it.

**Measured (probe 2, fresh `-M:dev:test` JVM):** 50 consecutive in-process
runs of `seon.blob-threshold-test` (a branch-fixture namespace) →
heap 99 MB before, **99 MB after**; lease pool ended at exactly **one** name
(`:seon.test-support.fixture/0`); 1,240 ms for 50 runs (**24.8 ms/run**).
There is **no disk store growth at all** for this fixture class, so "discard
and GC reclaims it" is not even the question — nothing is written.

Isolation classes for the default tier:

- **pure in-memory** (the 44 `with-database` users plus the fixture-free
  ones) — trivially in-server-safe, proven by the 50-run probe;
- **process-global mutation** — `alter-var-root` / `System/setProperty` in
  `flow_test`, `instrument_test`, `cluster/agent_test`, `repl_parity_test`,
  `sci/eval_test`; real clusters / child processes / file stores in
  `cluster/boot_test`, `cluster/armed_test`, `cluster/program_restart_test`,
  `cluster/store_test`, `config_application_test`, `operator_test`,
  `oversight_test`, `sci/session_image_test`, `sci/eval_instrumentation_test`,
  `dev/fresh_operator_test`, `dev/mcp_bridge_test`, `dev/edit_feedback_test`,
  `dev/changed_test_test`, `flow/kill_child`, `bootstrap_drive_test`.

**Can process-cleanliness be derived rather than hand-listed?** Yes, and that
is the right answer — but the fact is missing today. `:seon.fn/calls` is
already computed for every caller (`seon.fn/call-targets-by-caller`,
`src/seon/fn.clj:218-236`) and
[test-call-edge-design-2026-08-03.md](test-call-edge-design-2026-08-03.md)
settles that the `deftest` arm discards it. I confirmed the gap in the live
database: **794 `:seon.test` rows, 1,856 `:seon.fn` rows, and zero test rows
carrying `:seon.fn/calls`.** Once that edge lands, "process-unclean" is one
reachability query — the test's call closure reaches a leaf that starts a
cluster, spawns a process, or writes an operator root — exactly the R34
computed-classification pattern, and it composes with the same closure query
the effect/capability indexing already uses. Until then, in-server dispatch
must be *opt-in per namespace*, never a guess.

## 3. Test loading and staleness

The in-server runner must `require :reload` changed test namespaces. The
existing design already covers the ordering: `reload!` in
[operator-integration/README.md](../../operator-integration/README.md) lines
70-86 derives the first-party downstream closure from program-graph namespace
dependency facts, topologically orders it, and `require :reload`s each —
identity-preserving, no `remove-ns`, no text parser. **The test runner needs
no second reload mechanism**: it calls `reload!` with the changed namespaces
and then runs. Measured cost: `require :reload` of `seon.blob-threshold-test`
in a warm JVM was **13 ms** (probe 1).

Staleness detection should be neither mtimes nor the selector's second
clj-kondo run. The edit hook already publishes changed `src/`+`test/` files to
`current-src`, so **the changed set is a database fact, not a filesystem
scan**. The runner takes an explicit namespace list from its caller and asks
`reload!` for the closure; nobody needs to diff timestamps.

**Missing-fact defect found:** `:seon.test/long` is **not a database
attribute**. It is var metadata read after loading (`runner.clj:394-408`),
which is why `-main` must `require` every selected namespace *before* it can
decide what to skip (`runner.clj:544-552`). The analyzer already retains
`:meta` (`src/seon/fn/analyzer.clj:26-28,56,71`), so declaring
`:seon.test/long` as a fact on the existing `:seon.test/test` entity
(`resources/seon/schemas/seon.test.edn`) makes tier selection a query *before*
loading anything. I verified the attribute is absent from the live database.

## 4. Safety inside a live JVM

**The current backstop cannot be reused.** `fire-liveness-backstop!` ends in
`(.halt (Runtime/getRuntime) 124)` (`runner.clj:299`) after force-destroying
descendants — correct for a disposable suite JVM, fatal for the owner's
cluster.

The in-process replacement keeps the same event-driven shape and changes only
the escape:

- **Progress is already an event stream.** `progress-event!` (`runner.clj:82-99`)
  publishes `:begin-test-var`/`:end-test-var` from `clojure.test`'s own
  reporter. That is the observable event; no clock is the primary detector.
- **Run the suite on one named non-daemon platform thread per invocation**
  (a `future` is enough; `future-cancel` interrupts it). The caller parks on
  its completion — an interface that publishes its own readiness, per the
  timeouts ruling.
- **The silence backstop stays a loud last resort** whose firing is a bug
  report: on silence, capture the thread dump exactly as today
  (`persist-virtual-thread-dump!`, `runner.clj:186-210`), `.interrupt` the run
  thread, mark the run `:interrupted`, and **return a flat error value**.
- **A thread that ignores interruption cannot be stopped.** This is the honest
  limit: in-server bounds cooperative and blocking-I/O tests only. When the
  interrupt does not take, the correct response is to record the namespace as
  process-unclean and tell the caller to use the spawned tier — never
  `Thread.stop`, never `halt`.

Memory and threads: measured zero heap growth over 50 runs (§2). Thread leaks
are the genuine residual risk for namespaces that start flow graphs or
clusters — which is exactly the class §2 keeps on the spawned path.

**Hazard the spawned path does not have (found live, first probe).** Running
`seon.blob-threshold-test` inside the live default cluster failed with
`NoClassDefFoundError: datahike/writer$fn__56649$fn__56664` at
`datahike.writer` line 327, reached through `d/create-database` for the
`:memory` backend. Diagnosis: `clojure.lang.DynamicClassLoader`'s static
`classCache` holds `SoftReference`s and removes cleared entries; I read the
field reflectively in the live JVM — **43,721 entries, and both the outer
`datahike.writer$fn__56649` and the inner class were absent**. A rarely
executed code path in a long-lived JVM can therefore lose its dynamically
defined classes permanently, because the defining loader is gone. This is the
same family as the known `malli.generator`/`test.check` stale-class condition
in that JVM. Consequence for this design: **the in-server runner must treat
`NoClassDefFoundError` as a recoverable condition** — `require :reload` the
owning namespace and retry once, and if it recurs, fall back to the spawned
path and report it. I did not run the `:reload` recovery against the owner's
live cluster (reloading `datahike.writer` would redefine the live writer
multimethods); **that recovery is the implementing lane's first falsifier.**

## 5. Agent access

**Tests are ordinary compute, not a capability.** Running a test evaluates
already-loaded first-party code and returns a value; it performs no fs/web/llm
request, so it needs no effect receipt and must not be modelled as one — the
door bounds effects crossing out, not callability (ruling #20). The one
genuinely global side effect is namespace loading, and that is what makes the
namespace-level opt-in of §2 load-bearing.

Surface — one function in the existing operator namespace (which already ships
eight verbs, `src/seon/operator.clj:44-132`):

```clojure
(seon.operator/test!
  {:seon.test.runner/namespaces ['seon.blob-threshold-test]
   :seon.test.runner/tier :default        ; or :full
   :seon.test.runner/reload? true})
```

The form returns either a `:seon.test.runner/run-result` value or a flat
`:seon.error/value`.

The return shape already exists and is already schema'd:
`runner.clj/run!` returns `:seon.test.runner/run-result` — summary counts plus
per-test `{:seon.test/sym, :seon.ns/name, :seon.test.result/outcome,
:seon.test.failure/message}` (`runner.clj:298-322`). `run!` is already free of
the process concerns; **`-main` is the only part that owns `System/exit`, the
halt backstop, and recording.** That is the seam.

Becoming a fact: `record-tx` (`runner.clj:326-369`) already produces the
transaction data, and `record!` already refuses the `default` cluster
(`runner.clj:337-343`). For agent-run tests the natural rule is the inverse of
today's: an agent's test run commits into **its own** cluster (it is that
cluster's own work), so the `default` refusal — written for a foreign suite
process — should become "refuse a cluster you are not the process for". That
is a one-predicate change, and it is what makes coverage queryable.

**Do not conflate this with green-to-install** (evening ruling #1). That
pipeline evaluates an agent-authored function *and its tests* in an SCI
**candidate context** and installs only when green. Those tests are corpus
code in SCI; `test/**/*_test.clj` are host `clojure.test` vars on the JVM
classpath. Two substrates, two runners. The shared piece is the *result
shape* (`:seon.test.result/outcome` + failing case as teaching feedback), and
the shared fact is the test→function call edge (evening ruling #2) that makes
coverage a query on both sides. A single function pretending to serve both
would be the ported-defect shape.

## 6. The dispatch, measured

Detection is already available: the advertisement file
`data/clusters/<name>/prepl.edn` exists and carries host/port/pid/start-instant
(confirmed live via `runtime_status`). Flow:

1. `bin/test` with no `--full` and no `--result-cluster` reads the
   advertisement; if absent → spawn (today's path unchanged).
2. Connect to the prepl; if it does not answer → spawn.
3. Ask for the **declared** tier/cleanliness split. Every requested namespace
   that is process-unclean or `long` goes to the spawned path; the rest go
   in-server. A mixed selection legitimately runs both and merges verdicts.
4. In-server: `(seon.operator/test! …)` → `:seon.test.runner/run-result` back
   over the prepl → printed in today's format → exit code from
   `fail + error` counts, exactly as `-main` computes it (`runner.clj:571-572`).
5. `--full`, `--result-cluster`, and `SEON_TEST_FULL=1` **always force the
   spawned path** — the full tier's value is precisely a fresh classpath and a
   fresh process.

**The prize (all measured 2026-08-03):**

| Path | Load | Run | Wall |
|---|---|---|---|
| `bin/test seon.blob-threshold-test` (spawned, warm caches) | 2.20 s | 5.64 s | **10.32 s** |
| Same namespace, cold `-M:dev:test` JVM, first run | 15.00 s | 7.65 s | 24.77 s total |
| Same namespace, **second run in that JVM** | 0 ms | **28 ms** | — |
| Same namespace with `require :reload` | 13 ms | 38 ms | — |
| `seon.schema-test` warm (5 tests) | 17 ms | 187 ms | — |
| `seon.error-test` warm (25 tests / 72 assertions) | 45 ms | 1,235 ms | — |

So the warm in-process edit→verdict loop for one namespace is **~40 ms against
10.3 s — roughly 250×**. The 5.6-7.6 s the spawned path spends on
`seon.blob-threshold-test` is dominated by building the source manifest and the
canonical in-memory base *once per invocation* (`test_support.clj:35-39,86-100`);
in-server that cost is paid once per JVM lifetime. With 95 test namespaces, the
default tier's measured 174.94 s (`plan/unsettled.md:19-32`) is where the same
saving compounds — though the honest caveat is that the tier's remaining time is
"generated invariants and source publication" per that same addendum, not boot,
so **do not promise a 250× full-tier win**; the near-instant claim is for
single-namespace iteration, which is where the developer actually lives.

## 7. Recommendation

**Owner:** `seon.operator` (the verb) + `seon.test.runner` (the run). No new
namespace, no second runner.

**Seams, in order:**

1. **`test/` on the cluster launch classpath** — `-M:dev:test` in
   `script/seon/fresh_operator.clj:244-245`. One line, and everything else is
   blocked on it. Falsifier: `(clojure.java.io/resource
   "seon/blob_threshold_test.clj")` is non-nil in a freshly started cluster.
2. **Declare `:seon.test/long` as a fact** on `:seon.test/test`
   (`resources/seon/schemas/seon.test.edn`), lifted from the metadata the
   analyzer already retains. Tier selection becomes a query that precedes
   loading. Falsifier: the 30 declared long tests are returned by one Datalog
   query against a freshly published `current-src`, with no namespace loaded.
3. **`seon.operator/test!`** — reuses `runner/run!` unchanged, adds the
   interruptible run thread, the reporter-silence backstop that returns an
   error value instead of halting, and the `NoClassDefFoundError`
   reload-and-retry-once arm.
4. **`bin/test` dispatch** — advertisement probe, prepl call, merge, exit code;
   spawned path untouched for `--full` / long / unclean / no-live-system.
5. **After the test→function call edge lands** (evening ruling #2), replace the
   per-namespace opt-in with the derived process-cleanliness query. Not before:
   a hand list here would be the exact defect this program keeps deleting.

**Falsifiers for the whole design:**

- a source edit + `test!` shows the new behavior without a JVM restart, and the
  same namespace still passes in the spawned path;
- an intentionally wedged test returns an `:interrupted` error value and the
  **cluster survives** (prepl still answers, web UI still serves);
- a namespace that starts a real cluster is refused in-server and routed to the
  spawned path automatically;
- 500 consecutive in-server runs leave heap and thread count flat (the 50-run
  probe is the seed);
- the `NoClassDefFoundError` recovery works (reload + retry) on the class of
  failure recorded in §4.

**Genuinely given up, in-server:** the isolated operator root and the
copy-on-write clone (so no test may write an operator root); classpath-fresh
load proofs and load-order/namespace-conflict detection (a warm image hides
both — this is exactly what `--full` exists to keep); process-identity,
flock, and boot-tower coverage; and the ability to `halt` out of a truly wedged
thread. All four are why the spawned path is kept, not deprecated.

## Defects filed by this research

1. **Test rows carry no call edge** — 794 `:seon.test` rows, zero with
   `:seon.fn/calls`, confirmed live. Design already settled in
   [test-call-edge-design-2026-08-03.md](test-call-edge-design-2026-08-03.md);
   this report adds a second consumer (derived process-cleanliness).
2. **`:seon.test/long` is not a fact** — tier selection requires loading first.
3. **Dynamically defined classes can vanish in a long-lived JVM** —
   `DynamicClassLoader` soft-cache eviction, reproduced live (§4). Affects
   every in-server execution story, not just tests.
4. **`seon.dev.changed-test` runs a second clj-kondo analysis** to rebuild a
   namespace graph the program graph already holds, then shells `bin/test`
   twice. Both halves are superseded by this design.
