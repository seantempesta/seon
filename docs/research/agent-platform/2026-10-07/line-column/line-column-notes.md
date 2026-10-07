---
title: line-column — a form's position costs the text once; allocation measured where it is defined
type: landing
status: slices 1-2 landed; agent evaluations' allocation needs an owner decision (options below)
date: 2026-10-07
---

# line-column (2026-10-07)

Lane `line-column`. Evidence: `forgive-fix/forgive-fix-notes.md` residuals, tasks `f14033845551`
(`seon.batch/line-column-rescans-the-text-before-every-form`) and `ae0364590441`
(`seon.batch/allocation-is-unmeasured-on-a-virtual-thread`). Default ran `18a68b248` (HEAD), JDK 26.0.1.

What exists and the approach: `seon.batch/evaluate-batch` folds read → evaluate → record over one text;
`read-step` takes each form's span from the entrant's read stage, and `evaluate-step` built the entry's
`:seon.batch/line`/`:seon.batch/column` with `line-column`, which copied `(subs text 0 start)` and counted its
newlines with a lazy `filter`. The fix carries a line cursor on the batch: each form's position is scanned on
from the previous form's, so a text's positions cost the text once.

## Which step of the one path this serves

The ONE read of every entrant (MCP code and JVM runs, test bodies, turns): the source position the entry carries.
No new owner or path. Deleted: `line-column` and the per-form text copies (`(subs text 0 start)` in `line-column`,
`(subs text pos)` in `seon.turn.batch/jvm-read-next`).

## Probe state

- `bin/seon status`: default `:faulted` on an open fault with task `3d2fb1b41dc8` (settle wrote another agent's
  branch), over-bound warnings on `seon.mcp/eval-clj`, `changed-identities`, `adopt-entities!`, all with tasks.
- `bin/seon check --help` answers "Unknown option." as a whole printed error map (ugly; `bin/seon help` works).
- An armed refusal I caused (nil request to `jvm-read-next`) printed a generated example of the whole request
  schema: existing task `cde56239621e` (`seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema`).
- Lint on touched files shows two pre-existing printer warnings in `src/seon/turn/batch.clj` (lines 272 and 435,
  `pr-str`-class findings), not in this slice's hunk.

## Cause 1, confirmed at the REPL

`line-column` (installed, JVM mode, platform thread, on a text of two-line defns):

| Text | Forms | ms | Allocated |
|---|---|---|---|
| 0.9 KB | 40 | 0.9 | 2.5 MB |
| 9.2 KB | 400 | 68 | 257 MB |
| 36.8 KB | 1,600 | 1,126 | 4.1 GB |

4x forms -> 16x bytes: quadratic. A second quadratic copy on the same path: `jvm-read-next` built its reader
over `(subs text pos)`, copying the rest of the text before every form.

## Fix 1 (`src/seon/batch.clj`, `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`)

- `line-cursor` (replaces `line-column`): from the batch's `:seon.batch/line-cursor` (new registered key: text,
  pos, line, column) when it is in the identical text at or before `start`, else from 0; one primitive loop, no
  copy. `read-step` advances it for every in-bounds start; `source-of` uses it when it is this form's, else
  scans fresh, so it is correct whatever the read stage answers.
- `jvm-read-next`: `(doto (StringReader. text) (.skip pos))` (O(1)) instead of `(subs text pos)`.

EQUIVALENT: same line and column on 80 starts of a mixed text, at 0 of "", and mid-line; same JVM read steps on
six texts (comments, reader conditionals, fences, unclosed forms, syntax quote), both checked at the REPL against
the installed code. A text change between forms (a delimiter repair, a turn row) rescans that text from 0, as
before.

## Numbers (unarmed `evaluate-batch`, throwaway JVM, HEAD vs change; `fold.clj` in the lane scratchpad)

| Text | Forms | Parent | Change |
|---|---|---|---|
| 1 KB | 45 | 2 ms, 5 MB | 1 ms, 2 MB |
| 10 KB | 450 | 97 ms, 344 MB | 12 ms, 17 MB |
| 41 KB | 1,800 | 1,344 ms, 5.3 GB | 48 ms, 67 MB |
| 92 KB | 4,000 | 5,462 ms, 26.0 GB | 75 ms, 148 MB |

Linear: 37 bytes per character, the fold's own per-form work. Same last line and column at every size.
`line-cursor` alone: 152 KB, 8,000 starts, 3.0 ms, 1.0 MB. Commits and store bytes: none (nothing recorded).

Regression: `seon.run-test/a-forms-position-costs-the-text-once` (40 forms, 250 vs 2,000 newlines between them;
asserts every position and that the added 70,000 characters cost under 4 MB, measured on a joined platform
thread). Red on the parent: 233,314,576 bytes (check run `66975dde5e50`); green on the change (run
`b111a9bfae43`, where `d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change` is red on the parent too,
run `d393236dd286`, a documented D8 red).

## Finding: the armed fold is quadratic in its form count (task `08034d6f4d2b`)

With fix 1 the unarmed fold is linear, but default's ARMED fold is not: the private steps take and return the whole
batch, whose `:seon.batch/entries` every armed call validates. Measured on default (one-character forms, nothing
recorded): 50 forms 47 ms 15 MB, 100 forms 133 ms 47 MB, 200 forms 545 ms 163 MB; 450 two-line defns 8.3 s
1.17 GB (unarmed: 12 ms). Stack samples put 30 of 52 in Malli map validation under `form-step` and `evaluate-step`.
This is the larger cost of a long MCP run and the reason the 92 KB evidence was 8.2 s. Filed as
`seon.batch/each-fold-step-validates-every-entry-so-far` with the fix (per-form steps take the form's own state; the
fold conjoins the entry) and its proof. It reshapes the one-path fold, so it is not patched here.

## Design check

The design held: the batch fold is the owner of a form's position, the cursor is a value on the batch, and every
read stage keeps answering only its span. Awkward: the read stages already know line and column (SCI's
`source-reader`, the host `LineNumberingPushbackReader`), but count them from where each reader started (a version
after a delimiter repair, `.skip`), so reusing them would make each stage answer absolute positions; a cursor in
the fold is one place instead of four.

## Cause 2, confirmed at the REPL

HotSpot keeps no allocation count for a virtual thread: `getCurrentThreadAllocatedBytes` answers -1 there (JDK
26.0.1, default). An MCP `sci` run's form runs on a virtual thread (`(.isVirtual (Thread/currentThread))` true;
its stack ends in `seon.flow/graph-executor`'s virtual thread per proc task, the agent graph's turn proc); a JVM
`jvm` run's on a platform thread; a test body on the runner's `(Thread/ofVirtual)` in `seon.test`. So:

- `seon.batch/allocated-bytes` deltas were -1 - -1 = 0: every SCI form recorded `:seon.batch/allocated-bytes 0`, a
  false measurement. `seon.sci.kernel` already answered its typed unknown (-1, `::measurable false`).
- Every allocation bound in a test body read 0 or -1 and passed without measuring. Proven: with a positive-reading
  assertion added, all eight measured tests went red under the HEAD runner (check run `e2a6f95e77bc`): the
  measurements were vacuous.

| Test (all vacuous before) | Bound | Real measurement on a platform thread |
|---|---|---|
| `seon.instrument-test/registry-sized-contract-evidence-retains-the-offending-object` | 16 MiB | 45,429,240 B: RED, task `40c4f9b9c655` |
| `seon.sci.eval-test/schema-and-contract-declarations-have-bounded-allocation` | 64 MiB | schema 230,233,672 B: RED, task `f994a23477f7` (its line 962 is red on HEAD too, same task) |
| `seon.test-runner-test/a-failure-over-huge-values-reports-and-identifies-within-the-render-bound` | 256 MiB | within |
| `seon.sci.kernel-test/a-failures-cost-follows-its-distinct-frames-not-its-depth` | 50 MiB | within |
| `seon.sci.admit-test/a-value-carrying-its-world-admits-references-not-copies` | 5 MB | within |
| `seon.db-test/supplied-database-pulls-carry-the-projection-across-unbound-reads` | 10 MB | within |
| `seon.blob-test/large-binary-write-and-small-chunk-have-bounded-allocation` | 8 MiB, 1 MiB | within |
| `seon.sci.eval-test/showing-a-value-realizes-and-stores-only-what-is-shown` | +16 MiB | within |

(The real measurements came from a disposable probe test, never committed, that ran the eight tests on a joined
platform thread with their events collected: check run `9cee7a4e1c37`.)

## Fix 2 (`src/seon/test.clj`, `src/seon/batch.clj`, `src/seon/turn/batch.clj`, the eight tests)

The simplest correct choice for each place, with its limit:

- Test bodies run on a platform thread (`seon.test`, one line: `(-> (Thread/ofPlatform) (.daemon true) ...)`):
  HotSpot's per-thread count is exact there, so every existing bound measures what it states. Limit: allocation
  on other threads the body starts (futures, `seon.cache` derivations on virtual threads) is not the body's, as
  before; the kernel adds futures it carries the arm to. A body that outlives its bound holds a platform thread
  until it exits, as it held a virtual one.
- Every allocation bound first asserts its reading is positive, so a return to an unmeasured thread fails loudly
  instead of passing (`(is (pos? allocated) ...)`).
- `seon.batch/allocated-bytes` answers nil on a virtual thread and the entry then carries no
  `:seon.batch/allocated-bytes` (absence, never 0); `seon.turn.batch/jvm-show` passes the record's own unknown
  (-1). Regression `seon.run-test/an-entrys-allocation-is-measured-or-absent-never-zero`.
- The two private measuring helpers (`seon.sci.forgive-test`, `seon.run-test`) measure on the body's own thread.

Rejected: JVM-wide `getTotalThreadAllocatedBytes` around a form (every concurrent agent, writer and test is
counted: an upper bound that fails bounds under load); per-carrier accounting (a virtual thread that parks resumes
on another carrier, and the carrier is not public API); JFR `jdk.ObjectAllocationSample` (sampled and throttled,
an estimate delivered asynchronously).

## STOP: agent evaluations stay unmeasured (owner decision)

Agents' and MCP SCI forms run in their graph's turn proc on a virtual thread, so their allocation is now honestly
unknown (absent / -1), not measured. Measured in default: 13 graphs hold 27 virtual proc threads; a parked thread
costs 1.8 KB RSS virtual vs 41-117 KB platform (500 parked threads, throwaway JVM, `parked.clj`). Options:

1. **Platform threads for every graph's proc loops** (`seon.flow/graph-executor`, one line; simplest): exact
   per-form allocation for every agent evaluation; costs about 40-117 KB per parked proc (27 procs: 1-3 MB; 100
   agents x 3 procs: 12-35 MB), against the flow decision's 8.5 KB per idle graph.
2. **The turn proc alone on a platform thread** (recommended): the one proc that evaluates forms is measured,
   mailbox and schedule procs stay virtual: about 40-117 KB per agent. Flow chooses a proc loop's executor only by
   its workload (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:245-262`: `:mixed`
   -> `:mixed-exec`, else `:io-exec`; a `:compute` step runs on the compute pool under a 5 s `.get` timeout), so
   this means the turn proc declared `:mixed` with a per-graph platform `:mixed-exec`, reversing the flow skill's
   "refuse mixed at construction".
3. **Keep virtual; measure the evaluation on a platform thread** (a thread hop at the fold): idle costs nothing,
   but the hop must carry SCI binding frames, the kernel's thread-local arm and interruption; it changes the one
   REPL path's semantics and costs a thread start per run.

## Design check (slice 2)

The design held for tests (one runner line makes every existing measurement real, and the guards keep it so).
Wrong in the old design: two measuring layers (`seon.batch`, `seon.sci.kernel`) disagreed on unknown (0 vs -1);
the batch now answers absence, and the kernel's -1 stays its record's declared unknown. Awkward: the production
side cannot be made exact without choosing a thread for the turn proc, which is the flow owner's decision above.
