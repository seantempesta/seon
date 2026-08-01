---
type: research
status: active
tags: [research, database, agent, testing]
---

# Grader mechanics — grounding the four open questions (2026-08-01)

Grounding audit for `plan/grader-in-fact-space-2026-08-01.md`. Every claim
below is either a `file:line` reading of current source or a live result from
a scratch cluster booted for this audit
(`tmp/grader-probe/clusters`, cluster `graderprobe`, probe scripts
`tmp/grader-probe-{1..13}.clj`, all committed to `tmp/` so the numbers are
reproducible). No production file was edited.

Probe environment: `clojure -M:dev`, own process root, own store, own
`current-src` publication (7.35 s), cluster boot on the already-loaded JVM
712 ms.

## Q1 — Precedence: does an agent-authored row beat the compiled var?

**YES, for functions, at the door, and the containment is real.**

### Mechanism (source)

`seon.sci.eval/acquire!` (`src/seon/sci/eval.clj:726-890`) runs in this order:

1. `install-loaded-first-party-namespaces!` (`eval.clj:655-679`) —
   membership is the intersection of **core-provenanced `:seon.ns` rows** and
   **namespaces this JVM has loaded**; each one is bound with
   `(sci/add-namespace! ctx ns (ns-interns host-ns))`, i.e. the real compiled
   Vars;
2. `install-program-doc!` (`eval.clj:748`);
3. **then** agent-authored namespace rows, function rows, and test rows
   install through the interpreted path (`eval.clj:749-890`), each row
   `sci/eval-form`ed into its namespace (`eval.clj:595-615`).

Every row-set is filtered by `agent-authored?` (`eval.clj:743-745`), which is
`(= :agent (admission-source db source-tx))`. Provenance is **derived per
asserting transaction**, never stored:
`seon.schema/admission-from-asserting-transaction`
(`src/seon/schema.cljc:581-607`) calls a row `:core` only when the asserting
transaction's process's first assertion precedes the **source seal** — the
first `:seon.source/digest` assertion (`schema.cljc:548-566`,
written last by `seon.cluster.source/publish!` at
`src/seon/cluster/source.clj:158-163`). Anything else **fails closed to
`:agent`**.

So: re-assert `:seon.fn/source` for an existing core function from a live
cluster process and that row flips to `:agent`, which moves it from step 1
(compiled) to step 3 (interpreted) — and step 3 runs last.

### Live falsification (probe 2)

Target `seon.cluster.registry/cluster-branch` on a booted cluster:

```
BEFORE admission :core
BASELINE door  => :cluster-probe
AFTER  admission :agent          ; after one ordinary d/transact of new source
installed count 1
AFTER  door     => :HIJACKED-probe
compiled var    => :cluster-probe
```

The agent's row wins inside the door; the JVM Var the driver calls is
untouched. The driver's containment is structural, not incidental: the run
loop resolves its evaluator with `requiring-resolve`
(`src/seon/cluster/loop.cljc:972-973`) and calls `acquire!` per run
(`loop.cljc:995-996`) — the ctx is a per-run artifact, so nothing an agent
writes can reach compiled code.

### The four edges that decide the design (all live-proven)

**(E1) Containment is finer than "the driver".** Compiled function → compiled
function calls are Var-linked and never see the fact-space rewrite, even for
two functions in the same namespace, even called *from inside* the door
(probe 3): hijacking `seon.ai.tokens/estimate-chars` to `(* budget 100)` gives
`estimate-chars` ⇒ `100` through the door, while `clip-str`, which calls it,
still returns `"abcd…"` — the compiled body. **A fact-space rewrite changes
what INTERPRETED callers see. Every compiled caller of that function keeps the
old behavior.** For a grader this means: rewriting a leaf changes agent-facing
behavior; rewriting a function the runtime itself calls changes nothing at all.

**(E2) One bad row bricks the whole branch.** `acquire!` **throws** — it has no
per-row containment, and the loop does not catch it (`loop.cljc:995`). A core
function whose source uses a namespace alias cannot be re-interpreted, because
`install-loaded-first-party-namespaces!` installs `ns-interns` only — no
aliases, refers, or imports. Hijacking `my.run/wait` (its body uses
`str/blank?`) produced:

```
acquisition => {:failed "Unable to resolve symbol: str/blank?"}
```

…for **every** subsequent evaluation on that branch, not just calls to
`my.run/wait`. Every agent on that cluster is dead until the branch is
reforked.

**(E3) The flip is one-way.** Re-transacting the byte-identical original
source does not restore `:core`: provenance follows the *asserting
transaction*, so the repaired row is still agent-authored, still interpreted,
still broken (probe 4). The only repair observed is `cluster/refork!`
(38 ms, probe 5). Corollary: an identical re-transaction of an unchanged value
is a **no-op** in Datahike (1 datom, no new assertion), so provenance survives
a redundant write (probe 6) — the flip needs a real value change.

**(E4) Touching a namespace row erases the namespace.** Change
`:seon.ns/source` for a core namespace and the ns leaves the core-provenanced
set, so its compiled bindings are never installed — while its function rows are
still `:core`, so they are never installed either. Probe 6, after appending one
comment line to `seon.ai.tokens`'s ns source:

```
admission now :agent
estimate        => Unable to resolve symbol: seon.ai.tokens/estimate
clip-str        => Unable to resolve symbol: seon.ai.tokens/clip-str
chars-per-token => Unable to resolve symbol: seon.ai.tokens/chars-per-token
```

The entire namespace vanishes from agent land, irreversibly on that branch.

**(E5) There is no ownership guard.** An agent evaluating a `defn` with
`:seon.cluster.run.form/ns` pointing at `seon.cluster.registry` produces a
well-formed program row for that core namespace (probe 5). Nothing in
`seon.program` (`src/seon/program.cljc:97-129`) or the reader restricts which
namespace an agent may author into. Fact-space rewriting of core is therefore
already *possible*; what is missing is that it is *safe*.

**(E6) Reachability is JVM-load-dependent.** `seon.test.runner` was
unresolvable through the door until the probe JVM `require`d it, after which
the same acquisition bound it (probe 8). What core code an agent can see is a
property of the hosting process, not of the branch.

### Design consequence

The plan's "rewrite ANY seon function in fact-space" is true **only** for the
leaf-function case, and today it is a loaded gun:

- rewriting a function whose source uses its namespace's aliases/refers throws
  during acquisition and bricks the branch (E2) — that is most of `src/`;
- rewriting anything at all is irreversible on that branch (E3);
- rewriting a namespace row destroys the namespace (E4);
- a rewrite only reaches interpreted callers (E1).

Minimum for the design spec to be honest:

1. **Fence acquisition per row.** A row that fails to install must degrade to
   an absent/`:seon.error` binding with a durable fault fact, never an
   exception out of `acquire!`. Without this the grading harness's first
   creative agent kills its own cluster. *(issue-worthy; not filed — this lane
   is bounded to one file)*
2. **Install the interpreted binding environment with the function.** Either
   the ns row's aliases/refers must be installed for core namespaces too (they
   are already stored: `:seon.ns/aliases`, `:seon.ns/refers`,
   `program.cljc:13-15`), or a rewrite must be refused when the source
   references anything not resolvable in the row-derived environment. Today it
   is neither — it is accepted and then explodes.
3. **State the containment rule in the spec**: fact-space rewriting is an
   *agent-visible* rewrite. To change runtime semantics you change compiled
   source and republish `current-src`; the grader's lever is the agent's
   world, not the driver's.
4. **Namespace rows are not a rewrite surface** until (E4) is fixed.

## Q2 — Fork as a driver operation

**The primitive exists; the driver-level operation does not; agents have no
door to it at all.**

### What exists (source)

- `registry/branch!` (`src/seon/cluster/registry.clj:157-195`) — create branch
  from a branch keyword **or a commit UUID**, idempotent, the one owner;
- `registry/ensure-cluster!` (`registry.clj:197-219`) — takes
  `:seon.source/commit-id` explicitly and refuses `::source-absent`;
- `registry/reset-cluster!` (`registry.clj:221-248`) and `retire-branch!`
  (`registry.clj:250-281`);
- `cluster/start!` (`src/seon/cluster.clj:1375-1474`) → `stack-tower!`
  (`cluster.clj:1282-1373`).

### What is missing

`stack-tower!` hard-codes the fork source to the **published** `current-src`
commit (`cluster.clj:1293-1301`), and `refork!` does the same
(`cluster.clj:1641-1664`). `:seon.boot/start-request` has no commit key. So
"fork commit X as cluster Y and boot its loop" is **not one function today**;
it is `registry/ensure-cluster!` + `store/open-branch!` + everything
`stack-tower!` does after the fork, and the only supported source commit is
the newest publication.

Agent-side the gap is total: the agent-facing surface is `my.run` (two
dispositions) and `my.message` (`src/my/`), plus **read-only** `seon.db/q` and
`seon.db/pull` (`src/seon/db.clj:118,185` — there is no transact). There is no
capability door (`seon.effect` is referenced as not-yet-existing in
`src/my/message.cljc:24`). A grader agent today cannot fork, cannot boot, and
cannot transact anything except the program rows its own declarations produce
(`eval.clj:1006-1016` + `install-program-row!`, `eval.clj:552-648`).

### Measured cost (probes 1, 5, 11, 12, 13)

| operation | cost |
|---|---|
| `registry/branch!` from a commit | **18 ms** (20 in a row: 343 ms) |
| `store/open-branch!` | **1 ms** |
| `cluster/start!` on a warm JVM (full tower: REPL, store, fork, connection, schema accretion, recovery, config, root agent, flow graphs, web) | **712 ms** |
| `cluster/refork!` (stop + delete + branch + restart) | **34–38 ms** for the registry half |
| `cluster/refresh-source!` (complete publication) | **7.35 s** |

**Design consequence.** A generation of 20 exam clusters costs ~0.4 s of
branch work and ~14 s of boot — trivial. The work is interface work, in
dependency order:

1. `start!` accepts an explicit `:seon.source/commit-id` (one key through
   `resolve-bootstrap` → `stack-tower!`; `ensure-cluster!` already takes it);
2. one driver function `fork-and-boot` returning the running instance's
   advertisement + branch commit id — the harness's unit of work;
3. only then, if the *overseer agent* is to drive generations from inside the
   system, one capability request through the guarded door. Ruling needed:
   the plan says "mediated by the harness/driver (an effectful capability)",
   and the effect door does not exist yet, so generation zero should be driven
   by compiled harness code, not by an agent.

## Q3 — Tests through the door

**Agent-authored tests install and run; nothing collects their results; the
existing runner cannot touch branch facts.**

### What exists

- `seon.test.runner/run!` (`src/seon/test/runner.clj:81-109`) rebinds
  `clojure.test/report`, calls `clojure.test/run-tests` over **JVM namespaces**
  supplied by the caller, and returns per-test values;
  `-main` (`runner.clj:210-230`) `require`s them from the classpath;
  `record!` (`runner.clj:186-208`) boots a cluster and transacts,
  refusing `"default"`.
- `record-tx` (`runner.clj:123-174`) already produces exactly the
  `:seon.test.result` / `:seon.test.run` / `:seon.test.failure` facts a grader
  wants (`resources/seon/schema/test.edn`).
- `acquire!` installs **agent-authored** `:seon.test/sym` rows by
  `sci/eval-form`ing their source (`eval.clj:609-615`, ordered after all
  functions, `eval.clj:877-889`).

### Live results (probes 7-10, 12)

Committing an agent-authored ns + two `deftest` rows and acquiring gives real
sci test vars:

```
acquired 3
resolve probe-pass => "#'my.agents.grader/probe-pass"
meta keys          => (:test :line :column :ns :name :file)
call fail          => nil        ; and prints: FAIL in (probe-fail) expected: (= 5 (+ 2 2))
test-var fail      => nil        ; same
run-tests          => Unable to … "No namespace: my.agents.grader found"
```

Four concrete gaps:

1. **`clojure.test/run-tests` cannot work in the door** — the compiled
   namespace-based entry needs a real JVM namespace; sci namespaces are not.
   Only `test-var` / calling the test var works.
2. **Results are not values.** `test-var` returns `nil` and the failure text
   goes to the **JVM's** `*test-out*`, escaping the door's output capture
   (`:seon.cluster.eval/output` was absent). `*report-counters*` is a compiled
   dynamic Var that agent code cannot bind (`ref` is not in the sci ctx). A
   grader therefore has no way to learn pass/fail from inside an eval.
3. **Core test rows are unreachable.** The branch carries 664 `:seon.test/sym`
   rows; all are `:core`, so `acquire!` never installs them and no core test
   namespace is loaded in a cluster JVM. A grading branch cannot run the
   repository suite through the door.
4. **The runner's request shape is agent-hostile**: `:seon.test.run/at` is an
   `:inst` (agents cannot construct one — `java.util.Date` is not in the ctx's
   `:classes`, `eval.clj:194-196`) and `:seon.test.run/git-sha` is a required
   40-hex SHA (`test.edn`) that a branch-authored run does not have.

Note the same source reading explains a subtlety the spec must state:
**declaration forms are not evaluated inline.** `evaluate` returns the
declaration's identity string and skips `eval-form!` when the form yields a
program row (`eval.clj:1006-1016`); the definition becomes live only through
`install-program-row!` at the terminal transaction or the next run's
`acquire!`. A `defn` *without* `:malli/schema` yields no row
(`program.cljc:105-116`, `:contracted`) and therefore *is* evaluated inline —
which is why a schema-less `defn` is callable in the same run and a contracted
one is not.

**Design consequence — what to build.** One function, driver-side, in
`seon.test.runner` (strengthen the existing owner; do not add a second
runner):

- input: a database value (the grading branch) + a selection of
  `:seon.test/sym` rows;
- it acquires a ctx from that db, resolves each installed test var, runs it
  with `clojure.test/report` rebound to a **capture** (the mechanism
  `run!` already uses at `runner.clj:92-99`), and returns
  `:seon.test.runner/results` — values;
- the driver commits them with the existing `record-tx`, after
  `:seon.test.run/git-sha` is relaxed to admit a branch/commit identity
  instead of a git SHA;
- exposure to a grader agent is then one capability request, not a new
  mechanism.

## Q4 — Store hygiene at scale

**Branches are free. Transactions are not. Retirement reclaims nothing; only
`collect!` does — and only for retired branches.**

### Source

- `registry/collect!` (`registry.clj:283-293`) calls `d/gc-storage` with **no
  cutoff**;
- `datahike.gc/gc-storage!` (`reference-code/datahike/src/datahike/gc.cljc:83-143`)
  marks by unioning `reachable-in-branch` (`gc.cljc:22-81`) over **every
  roster branch**, then sweeps everything else older than the store's safe
  point;
- `konserve.gc/sweep!` (`reference-code/konserve/src/konserve/gc.cljc:8-40`)
  deletes **every key in the store not in the whitelist** and older than the
  cutoff timestamp;
- `retire-branch!` removes the roster entry only (`registry.clj:250-281`,
  citing `versioning.cljc:261-289`).

### Measured (probes 12, 13, one scratch store)

| step | result |
|---|---|
| 20 fresh `branch!` from one commit | 343 ms, store **+32 KB total** (~1.6 KB/branch) |
| one small transaction (2 tiny rows) into each of the 20 | 3.23 s, store **+31.6 MB** |
| marginal cost, 10 successive transactions on one branch | **130–176 ms and 1.53–1.58 MB each**, regardless of payload size |
| `retire-branch!` × 20 | **−241 bytes** (roster key only) |
| `collect!` while the branch is live | swept **0**; 15.5 MB of dead intermediate commits stay |
| `collect!` after retirement | swept 580 objects in 252 ms, store back to **exactly** the pre-fork byte count |
| second `collect!` | 0 (idempotent, as documented) |

So: **a fork is free; every commit costs ~1.55 MB of index nodes; nothing is
reclaimable until the branch leaves the roster.**

Projection for the harness: 20 exam branches × N turns × ~3 transactions per
turn (receipt start, terminal, message) ≈ 60·N transactions per generation
≈ **93 MB × N** per generation, unreclaimable while the generation's branches
are alive for review. At N = 20 turns that is ~1.9 GB per generation.

Three things follow:

1. **Adopt `:fuse-index-roots?`.** The pin already carries the opt-in
   (`reference-code/datahike/src/datahike/config.cljc:37-41,71`) and our
   configuration does not set it (`src/seon/cluster/store.clj:155-174`). The
   upstream sweep records the measured 5.3× write-amplification win
   (`research/upstream-delta-sweep-2026-07-31.md:12,156`, owned by
   `docs/seon/issues/file-store-commits-pay-five-times-the-fsyncs-they-need.md`).
   1.55 MB → ~0.3 MB per transaction changes the harness's whole budget.
2. **Give `collect!` its cutoff.** Already filed:
   `docs/seon/issues/storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md`.
   This audit supplies its missing measurement: 10 tiny transactions on a live
   branch produced 15.5 MB the current call reclaims **zero** of. This is a
   harness prerequisite, not a nicety.
3. **`seon.blob` (ruling #25) and `collect!` are on a collision course.**
   `konserve.gc/sweep!` deletes any key not in Datahike's reachable set, and
   that set is built purely from branch heads, commit records, and index nodes
   (`gc.cljc:22-81`). Blobs written with `bassoc` onto the **same** konserve
   store would be swept by the next `registry/collect!`. The blob tier must
   either live in its own konserve store or the mark must be extended with a
   blob whitelist in our fork. This must be settled before both features land
   together. *(issue-worthy; not filed — this lane is bounded to one file.)*

Retention policy the spec must state: a generation's branches are the audit
record, so they stay in the roster until reviewed, and **nothing is reclaimed
until they are retired**. Either budget the disk (≈2 GB/generation today,
≈0.4 GB with root fusion) or snapshot what review needs (transcripts,
scores) into a keeper branch and retire the rest immediately.

## Generation zero — the minimal implementation list, in dependency order

The owner's generation zero is a few clusters, one objective, transcripts read
by humans. Ranked by what blocks what:

1. **Fence `acquire!` per row** (`seon.sci.eval`). Without it the first agent
   that authors a contracted function referencing an alias bricks its cluster
   and the generation's data is a mystery. This is the only item that is a
   correctness prerequisite rather than an ergonomic one.
2. **`start!` accepts `:seon.source/commit-id`** (`seon.cluster`, one key
   through `resolve-bootstrap` → `stack-tower!`; `ensure-cluster!` is ready).
   This is what makes "fork the agent's ending commit and grade it" possible
   at all.
3. **One driver function `fork-and-boot`** returning branch, commit id, and
   advertisement — the harness's unit, compiled, not agent-facing. Generation
   zero is driven by compiled harness code; the effect door is not needed yet.
4. **Branch-facts test running** in `seon.test.runner`: run installed
   `:seon.test` vars from a supplied database value with `report` captured,
   return `:seon.test.runner/results`, commit with the existing `record-tx`;
   relax `:seon.test.run/git-sha` to admit a commit id.
5. **`collect!` cutoff** (already-filed issue) — needed before the second
   generation, not the first.
6. **`:fuse-index-roots?`** adoption measurement — same window; it is the
   difference between 2 GB and 0.4 GB per generation.
7. **Then** the spec decisions Q1 forces: whether core-namespace rewriting is
   permitted at all (E4 says no, today), and whether interpreted rewrites
   install their namespace environment (E2). Both are owner rulings, and both
   should be made before an agent is invited to "rewrite any seon function".

Explicitly **not** needed for generation zero: an effect door, an agent-facing
fork capability, blobs, and the overseer loop. Those arrive with generation N.
