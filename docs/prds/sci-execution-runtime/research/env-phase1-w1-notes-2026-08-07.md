---
type: research
status: complete
tags: [research, runtime, platform, flow, sci]
---

# Phase 1 lane W1 — the environment value and the one constructor

Lane W1 of the sealed [seon.env PRD](../plan/seon-env-prd-2026-08-07.md).
Read END TO END before any edit, as the lane spec requires:
the PRD (all ten rulings and the complete Phase 0 findings log), the
[Phase 1 lane specs](../plan/seon-env-phase1-specs-2026-08-07.md),
the [test-infrastructure spec](../plan/test-infrastructure-spec-2026-08-07.md),
and [env-phase0-flow-carriage-2026-08-07.md](env-phase0-flow-carriage-2026-08-07.md).

Commits: `a808ad980` (the value, the constructor, the carriage),
`19d61b1b4` (the graduated class regressions and the test-site migration),
`ee00c6dd3` (the interrupt arm rides the environment).

## What landed

| Deliverable | Where |
|---|---|
| the environment schema, existing key names, connection not database value | `resources/seon/schemas/seon.env.edn` |
| the container, the two constructors, scoping, carriage | `src/seon/env.clj` |
| refusal + merge at the flow crossings | `src/seon/flow.clj` |
| boot's 0->1 construction; the environment on the cluster ctx | `src/seon/cluster.clj` |
| agent graphs carry it scoped to their agent | `src/seon/cluster/agent.clj` |
| a turn's request context carries it scoped to run + form | `src/seon/sci/eval.clj` |
| the background io submission names its cluster | `src/seon/effect.clj` |
| the evaluation submission names its cluster | `src/seon/cluster/loop.clj` |
| the test bracket's subset constructor | `test/seon/test_support.clj` |
| the graduated class regressions | `test/seon/env_test.clj` |

## Design decisions taken at implementation, and why

### One declaration, no second list

Every question the constructor asks — which members exist, in what
dependency order, which layer a refusal blames, which members the 0->1
production constructor requires — is read from the one
`:seon.env/environment` schema through `schema/schema-definition`, in Malli
entry order, via entry properties `:seon.env/layer` and
`:seon.env/boot :required` (ruling #47's "declare it explicitly in Malli
properties"). `seon.env` keeps no roster. The derivation is memoized on the
complete declaration value, never on a "current" slot.

### Two constructors, one mechanism

- `environment` — subset construction. The only unconditionally required
  member is `:seon.boot/cluster-name`. This is what "store + facts, no web"
  means in practice, and it is what lets a Flow-plumbing test name its
  cluster in one line without standing up a branch.
- `boot-environment` — the production 0->1 constructor. It additionally
  requires every `:seon.env/boot :required` member and refuses at the FIRST
  layer that did not stand, naming that layer in a flat error value. Boot's
  caller turns that value into the existing degraded-instance throw through
  `refuse-incomplete-environment!`, so there is no second failure protocol.

Placing the strictness in `boot-environment` rather than in every
construction is what makes PRD ruling 4 true where it matters ("boot is a
0->1 process… a partial environment is never handed out") without pretending
that a test with no branch has one.

### Member shapes are validated by the declared contract, not a second validator

Construction does NOT compile a per-member validator. It cannot: the member
predicates (`seon.db/connection?` and friends) resolve through a cluster's
corpus projection, which the store layer has not necessarily reached when the
earliest environment is built — probed directly, a bare
`schema/valid-candidate-value?` on the environment throws
`Predicate seon.db/connection? has no admitted callable in the corpus
projection`. Shapes are therefore validated wherever an environment is
accepted, by the one declared `:seon.env/environment` contract, which is the
ordinary mechanism. Construction owns the ordered ABSENCE refusal, which is
what the PRD actually asks of it.

### The launcher cannot be a member of the environment its own procs carry

`:seon.flow/work-launcher` is a boot-required member, and the launcher's own
graph is built by `start-work-launcher!`. Boot therefore builds this cluster's
environment twice: once up to the facts layer for the launcher's own plumbing
procs, and once complete — same cluster, same connection, same projection,
one extra member — for the ctx and every later carrier. This is recorded
explicitly in `stand-cluster-runtime!` rather than smoothed over.

### The refusal is fail-closed at the construction function

`var-process` refuses `args` with no `:seon.env/environment`, exactly as it
already refuses a non-Var step and a `:mixed` workload. The Phase 0 flow
report's falsification is honoured: flow's own `:params` assertion cannot
make this refusal because `start-proc` assoc's `::flow/pid` into args, so
`args` is always truthy. `start-work-launcher!` and `start-error-fanout!`
require it on their request maps for the same reason — they build procs.

This is a deliberate NARROWING of three declared inputs (`:seon.flow/`
`io-submission`, `work-submission`, `work-launcher-request`,
`error-fanout-request` each gain a required key), which the accretion rule
calls breakage. It is the intended breakage: the whole point is that a
crossing which cannot name its cluster stops existing.

### Scoping is not construction

`env/scope` may narrow only `:turn`-layer members (agent id, run id, form
ordinal, the interrupt arm). No consumer can quietly replace a connection, a
projection, or a work launcher on the way across a boundary, so "running code
receives, never constructs" (ruling 1) stays enforceable rather than
aspirational.

### The arm rides the environment (W2 handoff, wired here)

W2 made the interrupt arm a value. W1 wired the two calls at the one crossing
that needed them: `submit!`/`submit!!` capture `(kernel/current-arm)` onto the
submission's environment under the declared optional `:seon.sci.kernel/arm`
member, and the io task, the compute task, and the terminal callback each run
inside `kernel/adopt-arm`. Reading the arm from submission DATA is what
survives the Phase 3 `bound-fn*` deletions. A nil arm is not a refusal.
This closes
[interrupt-arm-does-not-cross-a-thread-hop](../../../seon/issues/interrupt-arm-does-not-cross-a-thread-hop.md).

## Acceptance evidence

### The class regressions — `test/seon/env_test.clj`

`bin/test seon.env-test seon.flow-test`: **22 tests, 238 assertions, 0
failures, 0 errors.**

| Class | Regression | What it asserts |
|---|---|---|
| fork carriage | `a-fork-resolves-its-own-environment-across-a-thread-hop` | 16 sci forks x 4 rounds on virtual threads; every fork's code resolved ITS fork's environment (0 mismatches of 64); the shared base still reads `"BASE"`, so no fork's assoc reached it |
| flow carriage | `a-submission-delivers-exactly-its-own-environment` | two real work launchers, 16 io + 16 compute submissions, decoys installed in `seon.db/*conn*` and `seon.effect/*request-context*`; io, compute, and every `complete!` read exactly their own submission's environment, and io ran with the dynamic carriers at their root nil — the audited defect reproduced from the other side while the data path delivered correctly |
| arm carriage | `a-submission-carries-the-submitting-threads-interrupt-arm` | an io submission from inside an armed extent delivers the submitting thread's arm to the io thread (`identical?` both carried and adopted); an unarmed submitter delivers none, without refusal |
| refusal | `a-crossing-that-names-no-environment-is-refused-where-it-is-built` | `var-process`, `start-work-launcher!`, `submit!`, `submit!!` each refuse and name their boundary |
| construction | `construction-refuses-up-front-and-names-the-failed-layer` | the absent-member refusal names `:store`; boot's refusal names `:branch` — the FIRST layer that did not stand; subset construction succeeds; an undeclared member is carried, not refused (maps are open); scoping refuses a non-turn member |

### The reset-boundary live proof

Schema and acquisition changed, so a fixture load path is not sufficient.
Own isolated operator roots, never the shared default cluster:

```
bin/seon --root tmp/w1-operator init      # :current-src commit 6a765afe-…
bin/seon --root tmp/w1-operator start w1
● w1 boot: namespaces / repl / store / branch / recovery / config
● w1 boot: program / work-launcher / agents / web
● w1  http://127.0.0.1:7953  prepl=49652
```

Every layer stands, including the two that are new work (`work-launcher`
receives the pre-graph environment; the ctx receives the complete one).

Live, through the cluster's own prepl:

```clojure
{:cluster "w1"
 :members [:seon.boot/cluster-name :seon.config/on-core-error
           :seon.db/connection :seon.flow/work-launcher
           :seon.schema/projection :seon.sci.admit/caps]
 :launcher-identical? true      ; the environment's launcher IS the instance's
 :connection-identical? true    ; the environment's connection IS the branch's
 :fork-carries "w1"             ; sci/fork carries it, unchanged
 :printed "#seon.env/environment{:seon.boot/cluster-name \"w1\", …}"}
```

### Two-cluster isolation

A second cluster in its own operator root (`tmp/w1-operator-b`, cluster
`w1b`) booted through every layer and reports its OWN environment:

```clojure
{:cluster "w1b" :handle-carries-same? true
 :printed "#seon.env/environment{:seon.boot/cluster-name \"w1b\", …}"}
```

Two live clusters, two distinct environment values, each one the identical
object its own loop handle and its own ctx carry.

## Foreign reds, attribution PROVEN rather than assumed

Three namespaces are red at HEAD and none of them is this lane. The method
was a throwaway git worktree at `4f5b8c5ac` — the commit BEFORE W1's first
commit and before the sci pin bump — with the old sci pin
(`2db3358c`) extracted from the submodule's own object store, running the
same namespaces there. The worktree was removed afterwards.

| Namespace | At HEAD | At `4f5b8c5ac` (pre-W1, old pin) | Verdict |
|---|---|---|---|
| `seon.render.web-test` | every SSE patch read times out; ~7 tests error at `test-support/await-event!` | IDENTICAL — same tests, same timeout at the same read | pre-existing |
| `seon.gen.loop-test` | 3 errors, "the planner attempt census did not commit" | IDENTICAL — 3 errors, same names | pre-existing |
| `seon.cluster.boot-test` | 2 failures in `explicit-refork-destroys-the-old-branch-and-forks-current-source` (`:seon.cluster/created?` nil; the old branch's message survives the refork) | IDENTICAL — 2 failures, same test | pre-existing |

Recording this because a wrong attribution costs another lane real time, and
because "red after my change" is not evidence that a change caused it.

## Defects met, with honest attribution

1. **A cohosted second cluster in ONE JVM cannot boot** —
   `bin/seon --root tmp/w1-operator start w1b` (a second cluster in the
   already-running JVM) fails with
   `seon.cluster/require-activation! violated its contract (invalid-output)`,
   `:seon.activation/schema-keys nil`. The SAME cluster boots cleanly in its
   own operator root. NOT this lane's change: `require-activation!` runs
   before every line W1 touched, and the operator wrapper applies
   instrumentation under the FIRST running instance's projection state, so
   the second cluster's contract is compiled against the first cluster's
   projection. This is Defect II of the
   [parallel isolation audit](parallel-isolation-audit-2026-08-07.md) — one
   compiled validator generation for the whole JVM — observed end to end at
   the boot boundary rather than in a probe. It is exactly what the Phase 3
   "move the compiled caches onto the projection" slice repairs, and it is a
   useful acceptance test for that slice.

2. **The environment declaration must be read ONCE, not per construction.**
   `seon.env/members` originally called `schema/schema-definition` on every
   construction and every scope. With no projection bound — the state on
   exactly the hot paths (a flow submission, a turn's request context) —
   that falls through to `seon.schema.edn/packaged-forms`, which re-reads and
   re-merges every schema resource from disk on each call. A
   virtual-thread-aware `jcmd Thread.dump_to_file` of a wedged
   `seon.cluster.loop-test` caught the responsible virtual thread inside
   `merge-schema-resources`; the namespace went from 91 s to still running at
   15 minutes. Fixed here by reading the declaration once (`204e94421`):
   measured after, construction 0.37 us and scope 0.19 us. The OWNER defect
   is filed separately —
   [packaged-forms-rereads-every-schema-resource-per-call](../../../seon/issues/packaged-forms-rereads-every-schema-resource-per-call.md)
   — and note that `seon.config/registration-defaults` calls
   `schema-definition` inside a `keep` over config keys, so one call there is
   one complete resource merge PER CONFIG KEY.

3. **`current-src` publication is red in a long-lived JVM whenever a NEW
   first-party namespace lands.** Every edit this lane made returned
   `ADVISORY — current-src publication failed`, ultimately
   `Syntax error compiling at (seon/sci/eval.clj:1723:29)` — the reload path
   cannot see `seon.env` because the running JVM never required it. A fresh
   `clojure -M:dev` load of the same tree is clean, and
   `bin/seon --root … init` publishes cleanly. The hook's reload closure
   needs to require newly added namespaces (or say plainly that a new
   namespace needs a refork) rather than reporting a syntax error in an
   unrelated file.

## Ugly output met (standing order)

- **The boot failure face is a single unbroken ~9,000-character line.** The
  cohosted-cluster failure above printed the whole `:via`, the whole
  `:trace`, the whole `:seon.boot/instance` (including the Datahike
  connection, the file lock, and both executors), AND the same
  contract-violation message four times over — once in `:message`, once in
  `:seon.error/message`, once in `:seon.instrument/problems` as a
  fully-expanded `:seon.print` node tree, and once in `:cause`. The one
  useful fact — "`require-activation!` returned a value missing
  `:seon.activation/schema-keys`" — is unreadable inside it. A boot refusal
  deserves a declared `:seon.render/ai` producer.
- **`bin/seon status` prints eight identical `record unreadable
  /…/claims/roots/<uuid>.edn: The external claim is invalid.` lines** and
  never says WHY a claim is invalid or what to do about it. Eight
  indistinguishable uuids is noise, not diagnosis.
- **`bin/seon --root PATH` refuses a path it could create**: `--root requires
  an existing isolated operator-root directory` — the operator creates every
  other directory it needs. `mkdir -p` first is a papercut on the exact
  command the standing rules tell every lane to use for destructive drills.
- Confirmed again from the Phase 0 report: `clojure -M:dev` prints an
  incubator-module warning and an `environ :java-home` overwrite warning
  before every single result, and a malformed probe writes its error report
  under the OS temporary directory rather than a claimed root.

## Handoff

- **W2**: its two calls are wired and proven at the submission level; its
  issue is closed with the proof named above.
- **W3**: nothing here passes anything through `sci/init` options — the
  environment is `assoc`'d onto the ctx, exactly as Phase 0 finding 1
  requires — so the new pin's loud refusal of unknown init keys is
  compatible. The call-preparation hook reads `:seon.env/environment` off the
  runtime ctx with `seon.env/of`.
- **Phase 3**: the three `bound-fn*` sites (`src/seon/flow.clj`, the compute
  work-fn, the io `complete!`, and `join-error-fanout!`) are UNTOUCHED by
  design; they are deleted in the same change as the dynamic-var readers.
  The arm no longer depends on them.
