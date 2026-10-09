---
title: "Simplify the test design: solve the three problems, not the machinery"
type: review
status: active
created: 2026-10-10
---

# Simplify the test design

Lane fable-simplify, 2026-10-10. Read: AGENTS.md, BEHAVIOR-SPEC, COST-REG, generative-testing, astra-malli-behavior,
designed-task-examples, measurement, the contracts guide, both 2026-10-09 audits and plan.md. Verified against the
checkout (633e25beb) with `rg`/`wc` and one SCI probe on default (archive ffe62e1c3, pid 20363, `:faulted`).

## 1. Verdict

**Essential (keep, already exists or is law):** contracts with registered schema names on both ends; a task's
proofs as deftests tagged `:seon/tasks`; `seon.task/assign!` refusing a task without proofs; the reaching tests
running after every `defn`; `seon.profile/measure` (wall, thread allocation, GC, armed calls, reads);
`clojure.test.check/quick-check` with a fixed seed where a law exists (already used in `seon.id-test`,
`seon.program-test`); the per-request JVM window landed in cdce8a37e (`seon.batch/jvm-window`, shown by
`seon.mcp/eval-clj`, live after the next restart).

**Over-engineered:** everything that turns a cost into a *comparison with stored history under dials*.
COST-REG as carded is baselines captured at assignment (up to 32 member runs), lazy merge-base runs, eight dials,
a `:seon.task/cost-accepted` acceptance key and a new red variant. Two facts make it misfire before it lands:

- An agent's redefinition runs **interpreted** on its branch: `forgive`'s body allocated 280× its compiled self
  (designed-task-examples G4). Every correct agent body is red against main's compiled baseline at 2×/64 MB.
- The incident COST-REG was designed for (`nearest`, 6.3 s / 15.4 GB) passed its proof because the branch ran the
  *compiled* `my.program/source`, never the agent's code (COST-REG Q3, the `call-reach` gap). A cost gate cannot see
  code the test never ran; the fix was reach, and it landed (c5826eb62).

BEHAVIOR-SPEC's growth tests (n…8n, fitted exponent, reference ratio), guards (1 of 77 defects; admission blocked
three ways, §8), the structural schema index and the Malli fork change are machinery for the 9% + 1% classes while
the 26% + 22% + 18% classes need none of it. The owner found growth tests confusing: rightly, because the exponent
missed the very trap it was built for (`sort-by`: 1.08 ≤ 1.25) and needed a second rule to catch it.

**Missing:**

1. **An honest number in every reply.** The redefinition reply said 28 MB; the JVM did 2.1 GB and 2.4 CPU-s
   (measurement.md). Probe today: the evaluation runs on a *platform* worker (`async-mixed-7269`, allocation
   counted, 4.5 MB); the 1.8 GB ran on Flow's **virtual** proc threads (`seon.flow/graph-executor`,
   `Thread/ofVirtual`: custody, fork, settle, test step) and 0.4 GB in the core.async pool, where nothing is
   attributed. The arm-only number is the honest-looking lie.
2. **Proofs.** 590 task entries, 101 distinct task symbols tagged in `test/` (44 with a top-level deftest per the
   audit). Nothing an agent is handed is red first.
3. **The bug input in a test.** The fix that "passed its one reaching test while the bug remained" had no test
   containing the reported input. Nothing in either card says "the examples table holds the reported input".

## 2. The simplest design: five rules an agent can hold

**R1. Every function gets a contract and its reaching tests. Nothing else by default.** Registered schema names on
both ends (contracts guide §1); `defn` runs the reaching tests. No default generative check, no guard, no stored
baseline. Covers the 18% shape class at its source; the law already exists, this rule only stops adding to it.

**R2. A task's proof is an `are` table that contains the reported input, tagged with the task.** Rows: every input
from the task's intent, the empty/absent case, one boundary. Red before the work. This is the 22% example class and
what makes "passed while wrong" impossible for the reported bug. Example (designed-task-examples A, verified):

```clojure
(deftest ^{:seon/tasks #{seon.sci.forgive/forgiving-forgiven-text-changes-nothing}} a-stray-closer-keeps-tokens-apart
  (are [text forgiven spans] (= [forgiven spans] ((juxt :seon.batch/text :seon.batch/spans) (forgive/forgive text :code)))
    "a]a"  "a a"  [[0 1 1 1] [2 3 1 3]]
    "1]2"  "1 2"  [[0 1 1 1] [2 3 1 3]]
    ""     ""     []))
```

A property is an *optional second deftest* when the designer can state a law in one line (idempotence, round trip,
"equals the old slow walk"), written with `tc/quick-check` and `seon.test-support/assert-check!` exactly as today.
It found three `forgive` defects in a second; it is a tool, never a quota. No guards.

**R3. A cost budget is one more `is` in the same proof, on counts, at one real size.** The designer writes the
number from what the work must touch, as the examples already do (B: "reads ≤ 80 + L/2"; C: "datoms ≤ 40 ×
changed definitions + 10"). The meter is `seon.profile/measure` (`:seon.profile/reads`, `:seon.profile/calls`,
`:seon.profile/alloc-bytes`) or the tx report's `:tx-data` count; wall ms is never asserted (5–10× noise) beyond
the existing 5 s bound. Example:

```clojure
(deftest ^{:seon/tasks #{seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels}} gate-set-reads-follow-its-callers
  (let [db (db/db) m (profile/measure #(fn/gate-set db 'seon.fs/attributes))]
    (is (<= (:seon.profile/reads m) 84) "a leaf with nine callers reads about forty times, not the closure")))
```

Counts (reads, datoms, calls of compiled callees) survive interpretation, so they are red/green on the branch.
Allocation and CPU of the agent's own body are trusted only compiled: the merge check (`bin/seon check`) runs the
proof compiled, so an allocation `is` is decisive there and advisory on the branch (shown, named interpreted).
**Contesting:** the agent replies with its measurement and the alternative it sees; root changes the literal in
the test or not. No `:seon.task/cost-accepted`, no dials, no baseline store: the budget is in Git, in the proof.

**R4. Every reply's headline cost is the JVM window, labelled, and 10× over expectation is a finding.** Already
landed (`:seon.cost/jvm-window` beside the arm cost); make it the first cost line, with
`:seon.cost/attribution :includes-unrelated-work`. On a quiet JVM it is the truth; on a busy one it is an upper
bound, which is the right direction for a finding. Two small changes make attribution real for the common path:

- `seon.flow/graph-executor`: `Thread/ofVirtual` → `Thread/ofPlatform`. It is `newThreadPerTaskExecutor`, one
  thread per task, no pool, so nothing starves (measurement.md's starvation worry is about a fixed pool). Default
  runs 189 platform threads today; three parked loops per agent is the cost. Then custody/fork/settle/test-step
  allocation is on counted threads.
- Fold child thread cost into the parent the way `seon.test/measured-body` already folds call sinks
  (`seon.batch/merge-sink!`): add the child's `alloc-bytes` and CPU to the parent's. A few lines, no registry.

What stays unattributed, and is labelled so: the Datahike writer's go-loops and detached cache derivations. No JFR,
no per-call counters, no scheduler.

**R5. A reaching test's stored cost is shown, not gated.** Slice 1 (006fadb61) already stores
`:seon.test.member/alloc-bytes`, `run-ms`, `calls`, `top` per member. The reply shows them beside main's member
for the same test when the branch's is larger. That is what the agent needs to see the 15 GB; the red comes from
R3 and R4, not from a second comparison with history.

**Against the defect evidence (designed-task-examples §1):** cost 26% → R3 + R4; examples 22% → R2; schema 18% →
R1; property 9% → R2's optional second test; guard 1% → cut; lifecycle 23% → out of scope for per-task design (a
stateful platform suite with one owner; not built here).

**Cuts, one line each:**

| Cut | Why |
|---|---|
| COST-REG slices 2–3 (baseline at assignment, 32 runs, lazy merge-base runs, factor/floor red, acceptance key) | interpreted bodies are red against compiled baselines (G4); the motivating incident was a reach bug; R3/R4 catch GB and seconds without history |
| 7 of 8 `seon.config.test.cost` dials | they configure code that does not exist (opus review 2.8) |
| Growth tests (n…8n, exponent, reference ratio) | exponent missed the `sort-by` trap; one count at one real size is what the owner asked for |
| Guards, guard admission fixes, structural schema index, Malli `mg/check` seed change | 1% of defects; three blockers; `tc/quick-check` already seeds |
| Default generative check per `defn` | property 9%; isolation and junk-note problems; keep it as an optional proof |
| `seon.profile.measurement` + `seon.health/definition-comparisons` | COST-REG itself called it a duplicate; traffic-driven p50 per digest moves with callers, not code; it writes per request |
| Real-input capture by the armed wrapper | not built; curated `are` rows carry the inputs we know |

## 3. What can be deleted (verified with `wc`/`rg`)

| What | Lines | Note |
|---|---:|---|
| `src/seon/profile/measurement.clj` (203), `test/seon/profile/measurement_test.clj` (90), `resources/seon/schemas/seon.profile.measurement.edn` (37), call sites in `seon.turn` (~2877) and `seon.turn.batch` (~873), `seon.profile/measured-mark?` and the judged marks | ~380 | only reader is `definition-comparisons`; removes a per-request write (task `a-test-run-upserts-three-hundred-measurement-rows`) |
| `seon.health/definition-comparisons`, its branch of `regressions`, `:seon.health/definition-comparison`/`definition-regression` schema, `health_test` lines 169–232, `health_storage_test` | ~180 | `boot-comparisons` stays (no test covers a boot phase) |
| `seon.config.test.cost` dials except `top` | ~28 | schema file 32 lines; `top` is the only key `seon.test` reads |
| Hand-read `ThreadMXBean` in `forgive_test`, `run_test`, `db_test`, `blob_test`, `test_runner_test` → `seon.profile/measure` | ~40 | one meter |
| Not written: growth helper, structural index, guard admission, COST-REG slices 2–3, acceptance key | 0 now | several hundred lines never added, eight dials never read |

About 600 lines deleted, plus the per-request measurement transaction. Not in this doc's remit but noted for root:
`seon.test.platform` + `my.test/check-platform` (~250) exist because default lags HEAD (opus review §3.1).

## 4. Order of work

1. **Make the number honest (one Opus lane, ~half a day, boot-critical).** Restart default onto HEAD so the
   landed window shows; `graph-executor` to platform threads; fold child cost into the parent; window first in
   the cost line. Proof: the existing `seon.request-cost-test` plus one row asserting a redefinition's reply window
   is within 20% of the JVM counters around the call (measurement-probe.clj already does this by hand). Better by:
   the redefinition reply reads ~2 GB, not 28 MB, and `:seon.cost/alloc-mb` of custody/fork/settle is non-zero.
2. **Delete the comparison machinery (Sonnet, ~1 h).** §3 rows 1–3, callers converted in the same commit; retire
   COST-REG slices 2–3 and BEHAVIOR-SPEC §4/§6.3/§8 in place, cite this doc. Better by: src −600, one fewer
   transaction per request, settle stage shorter (measure before/after with the same probe).
3. **Ten tasks with red `are` proofs (Sonnet, ~2 h).** Root picks ten open tasks with small owners and a known bad
   input; the lane writes one `are` deftest each (R2), with an R3 `is` where the task is about cost, tags them,
   confirms red on default. Only these ten are assignable. Better by: tasks with a red proof 26 → 36; the next
   fan-out cannot pass with the reported input still wrong.

Then one wave-A acceptance run through those ten (both audits' first recommendation), before any new mechanism.

## 5. Questions for the owner

1. **Cut COST-REG slices 2–3 and the acceptance key entirely?** Recommend yes: G4 makes the comparison wrong for
   every interpreted body; R3 budgets in proofs plus the R4 window catch GB and seconds without stored history.
2. **Should a stored member cost ever turn red against main by itself?** Recommend not now: show it (R5); the
   existing 5 s test bound and R4's 10× rule are the gates. Revisit after ten designed tasks, with one factor and
   one floor if a case escaped.
3. **Properties optional, guards dropped?** Recommend yes to both: one optional seeded deftest where a one-line law
   exists; no guard work, no admission fixes, no fork change.
4. **Flow proc loops on platform threads (one word in `seon.flow/graph-executor`)?** Recommend yes: one thread per
   task, no pool, about three parked threads per agent; it is what makes the request's own allocation countable.
5. **AGENTS.md wording.** Replace BEHAVIOR-SPEC §9's paragraph with three lines under "Write tasks as data":
   *a task's proof is an `are` table holding the reported input, tagged with the task; a cost task adds one `is`
   through `seon.profile/measure` on a count at one real size, the number derived from the work and contested by
   measurement; every reply's first cost line is the JVM window.* Recommend root lands it with step 2.
