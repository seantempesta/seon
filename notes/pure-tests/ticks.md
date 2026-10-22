# Chez engine ticks as the cost proof's time dimension (measured 2026-10-23)

Question: is the Chez timer (`set-timer`, the count Jolt's tick budget already uses,
`:jolt/ticks-spent`) a deterministic count of a form's work per code path, so `seon.cost`
can judge "time" on ticks exactly (as cachegrind judges instruction counts) instead of
CPU ns at 3x slack?

**Answer: yes, with two stated tolerances.** On a code path with no collection, ticks are
bit-identical across 30 runs, 6 fresh processes, idle and under 4 extra CPU burners
(machine load 21 to 47), while CPU time spread 36% to 800%. A collection adds about 11
ticks each (the collect-request handler's own Scheme work), and rarely (9 of 2880 runs) a
whole 1000-tick quantum (Chez's `default-timer-ticks`) is gained or lost. Ticks scale
exactly with n on a linear form and see an extra inner loop that allocates nothing. Their
blind spot: work done in C or in bulk primitives (string copy) costs about 0 ticks.

## Method

- Reading ticks: Jolt exposes no "ticks spent on a normal return" (the budget in
  `jolt-run-interruptible*`, reference-code/jolt/host/chez/java/concurrency.ss:1990, reports
  `spent` only when the budget runs out, and only to the granularity of its 100000-tick
  poll). So the scripts define one Scheme procedure through `jolt.host/scheme-eval-string`
  (the hatch seon.profile already uses, src/seon/profile.clj:36):

  ```scheme
  (define (seon-ticks-of thunk)
    (let* ((big (expt 2 59)) (prev (set-timer big)))
      (thunk)
      (let ((left (set-timer 0)))
        (when (> prev 0) (set-timer prev))
        (- big left))))
  ```

  `set-timer` answers the ticks left (reference-code/chez/s/event.ss:29, it adds the
  `timer-ticks` field to the trap register's remainder), so spent = big - left, exact to
  the tick. With 2^59 armed the handler never fires during the measurement. An empty thunk
  measures 0.
- Forms (scripts in the session scratchpad `ticks/measure.clj`, run through `jolt -A:test
  run` with bin/env's library paths and `--set :seon.config.proofs/seed-salt=0`, see
  pitfalls): `(reduce + (range 1e5))` via a defn; a 1e4 `assoc` map build; `seon.print/
  print-value` of a 200-entry nested map at budget 4000; `sort` of 1e4 ints; `(apply str
  (map str (range 1e4)))`; `(count (reduce conj [] (range 1e6)))` (306 MB allocated, 1-5
  collections per run); an unforced `(map inc (range 1e5))`; a `binding` around a 1e4 sum.
- Each form 30 runs in each of 6 fresh processes (180 runs per form per load), recording
  ticks, wall ns (`jolt.host/mono-nanos`), thread CPU ns (`seon.profile/cpu-ns`, what
  seon.cost times), thread bytes and `gc-count` delta. Idle = the machine as it was (load
  average 21); loaded = plus 4 `yes` burners at nice 0 (load 47), all under `nice -n 15`.

## Results per form (ticks; CPU and wall in µs)

Idle (load 21):

| form | ticks min | ticks max | spread % | steady, no GC | first run extra | CPU min | CPU max | CPU spread % | wall spread % |
|---|---|---|---|---|---|---|---|---|---|
| reduce-1e5 | 579053 | 579072 | 0.0033 | 579053 (all 126) | 0 | 1034 | 9422 | 811 | 895 |
| map-1e4 | 425691 | 425702 | 0.0026 | 425691 (all) | 0 | 1418 | 3487 | 146 | 148 |
| print-value | 5688232 | 5694302 | 0.107 | 5688232 (all) | +5070 / +6070 | 15180 | 23863 | 57 | 204 |
| sort-1e4 | 1440586 | 1440606 | 0.0014 | 1440586 (all) | +12 | 2034 | 9585 | 371 | 371 |
| string-1e4 | 267000 | 267020 | 0.0075 | 267000 (all) | 0 | 581 | 2662 | 358 | 359 |
| gc-alloc (1e6 conj) | 34256762 | 34258773 | 0.0059 | (every run collects) | +30 | 50550 | 189334 | 275 | 273 |
| lazy-unforced | 143 | 143 | 0 | 143 | 0 | 0 | 3 | n/a | n/a |
| binding | 57949 | 57969 | 0.035 | 57949 (all) | 0 | 99 | 128552 | huge (one descheduled run) | huge |

Loaded (4 burners, load 47):

| form | ticks min | ticks max | spread % | steady, no GC | CPU min | CPU max | CPU spread % | wall spread % |
|---|---|---|---|---|---|---|---|---|
| reduce-1e5 | 579053 | 579072 | 0.0033 | 579053 | 1029 | 9853 | 857 | 1100 |
| map-1e4 | 425691 | 425702 | 0.0026 | 425691 | 1380 | 2714 | 97 | 161 |
| print-value | 5688232 | 5693314 | 0.089 | 5688232 | 15763 | 21372 | 36 | 54 |
| sort-1e4 | 1440586 | 1440606 | 0.0014 | 1440586 | 2063 | 15163 | 635 | 642 |
| string-1e4 | 267000 | 267020 | 0.0075 | 267000 | 590 | 1203 | 104 | 107 |
| gc-alloc | 34256773 | 34258762 | 0.0058 | (every run collects) | 50961 | 133336 | 162 | 166 |
| lazy-unforced | 143 | 143 | 0 | 143 | 0 | 2 | n/a | n/a |
| binding | 57949 | 57969 | 0.035 | 57949 | 96 | 140982 | huge | huge |

Every steady run without a collection measured the same tick count, idle and loaded,
in every process. The whole max - min tick spread comes from collections (next section).

### Where the tick variation comes from

Distribution over both loads (count, ticks, collections in that run):

- reduce-1e5: 257 at 579053 (0 GC); 100 at 579064 and 3 at 579072 (1 GC).
- sort-1e4: 283 at 1440586 (0 GC, and 4 with 1 GC that added nothing); 73 at +11..+20 (1 GC).
- print-value: 164 at 5688232 (0 GC); +11..+20 with 1 GC, +22..+23 with 2; first runs
  5693302 (8 processes), 5694302 (2 processes), +11..12 with a GC.
- gc-alloc: modal 34257762 (1 GC), 34257773 (2), 34257784 (3), so +11 per collection;
  2 runs at 34256762 and 4 at 34256773..84 (one quantum, 1000, fewer), 3 at 34258762..73
  (one quantum more).

So: a collection costs about 11 ticks (8 to 20) of the collect-request handler's own
Scheme code, which runs with the trap armed (library.ss `(define-library-entry (event))`:
`collector` calls `restart-timer` before the handler). Which runs collect depends on the
heap the process has, so this part moves with what ran before. Separately, a whole
1000-tick quantum is occasionally gained or lost: 7 of 360 gc-alloc runs (all collecting)
and 2 of 12 first calls of print-value (no collection; its first call does one-time
initialisation). It is never seen in a steady run without a collection. The likely cause
is an event path that resets the trap without accounting the quantum: the `$event` else
branch `($set-timer (constant default-timer-ticks))` when interrupts are disabled at
expiry, or C setting `TRAP(tc) = 1` (c/schsig.c:408, c/prim5.c:1411 `s_nanosleep`). Not
root-caused further.

## Scaling and sensitivity

One process per load, min of 3 runs at each n (ticks identical idle and loaded):

| form | n | ticks | ticks ratio | CPU ratio idle | CPU ratio loaded | bytes |
|---|---|---|---|---|---|---|
| sum-fast `(reduce + (range n))` | 25000 / 50000 / 100000 | 144549 / 289052 / 578053 | 1 / 2.000 / 3.999 | 2.01 / 4.06 | 1.75 / 3.34 | 1325488 / 2650384 / 5300176 |
| sum-fast-fn (an fn reducer) | same | 144544 / 289047 / 578048 | 1 / 2.000 / 3.999 | 2.01 / 4.01 | 2.02 / 3.99 | 1325440 / 2650336 / 5300128 |
| sum-slow (plus 9 empty fx iterations per element) | same | 369550 / 739053 / 1478057 | 1 / 2.000 / 4.000 | 1.95 / 4.02 | 2.00 / 4.30 | identical to sum-fast-fn |
| map-build (assoc) | 2500 / 5000 / 10000 | 95831 / 203164 / 424691 | 1 / 2.12 / 4.43 | 2.25 / 4.49 | 2.29 / 3.85 | 1797040 / 3858736 / 8250000 |
| sort-n | 2500 / 5000 / 10000 | 310175 / 685465 / 1440586 | 1 / 2.21 / 4.64 | 2.14 / 5.0 | 2.11 / 4.7 | 1159968 / 2508528 / 5301840 |

- Linear forms scale exactly (2.000, 3.999: the fixed part is 20 ticks, one call). n log n
  forms show their log factor cleanly (sort: 4.64 at 4n, the n log2 n prediction is 4.6),
  which CPU ratios show only within ±15%.
- The slower implementation of the same function, with the same bytes and the same calls,
  measures 2.56x the ticks (1478057 vs 578048) and 1.6x the CPU (2.27-2.44 ms vs 1.33-1.86
  ms). Bytes and contracted calls cannot see it; ticks see it exactly, every run.
- A tick is a trap check: one per loop back-edge and one per non-leaf procedure entry
  (Chez's `generate-interrupt-trap`; event.ss compiles itself with it off). A call to a
  leaf procedure costs nothing: an opt-2 loop calling a non-inlined leaf `helper` measured
  100003 ticks for 1e5 iterations, the same as the loop alone. Chez's optimize-level 0, 2
  and 3 gave the same 100003 on that loop; `interpret` gave 600015 (6x).

### What ticks do not see (probe3)

| operation | ticks at 1x | at 4x | CPU at 1x / 4x |
|---|---|---|---|
| `(str s s)` of a 1 MB / 4 MB string | 3000 | 3000 | 877 / 3484 µs |
| `make-vector` 1e3 / 1e6 | 253 | 1018 (1e6) | (fill charged 1 tick per ~1000 words: `$use-trap-fuel`) |
| `Thread/sleep 20` | 127 | n/a | 20 ms wall |
| bignum multiply 3^200000 / 3^400000 (2x digits) | 6356999 | 20465999 | 8.0 / 24.2 ms (counted, ratio 3.2 tracks CPU 3.0) |
| `.indexOf` 1 MB / 4 MB | 333408 | 1333408 | counted |
| `hash` 1 MB / 4 MB string | 1002009 | 4002009 | counted |

Bulk copies done in C (string-append, `bytevector-copy!`, any foreign call, the
collector's own work) are about free in ticks, and blocking costs nothing (ticks are work,
never time). A regression that moves work into such a primitive, or a larger copy, is
invisible to ticks; bytes (thread_bytes_allocated) catch a larger copy, since a copy
allocates.

## Pitfalls

- **Collections.** Each adds about 11 ticks; rarely one 1000-tick quantum. Taking the
  minimum over repeats removes the first, not the second (gc-alloc's minimum was itself a
  -1000 outlier). Judge ticks with a small tolerance: max(1%, 1000 x (1 + collections)) is
  far above everything measured (largest relative deviation 0.107%, a first call) and far
  below any change a cost proof cares about (sum-slow is +156%).
- **First call.** print-value's first call costs +5070 ticks (one-time initialisation), sort
  +12. seon.cost/measure's warm call (src/seon/cost.clj:159, `once` before the repeats)
  already absorbs it; keep it.
- **Compilation.** Jolt compiles to Chez machine code ahead of time (AOT cache keyed by
  source, `JOLT_CACHE_DIR`), no tiering or JIT warm-up: the count is a property of the
  compiled code, the same in every process. But it is a property of *that* compiled code:
  a change to Jolt's compiler or inliner, Chez, `optimize-level` or interpreted vs compiled
  evaluation (6x) changes counts. A green result on ticks must be keyed by the runtime too
  (`jolt.host/runtime-fingerprint`), which the reach digest must include. A script's own
  forms evaluated at runtime compile with `(optimize-level)` 0 here; the same function
  compiled by the AOT path may count differently (e.g. `(reduce + (range 100000))` inline
  in a script: 578053; through a defn taking n: 579053, a different path, each exact).
- **Contracts.** An instrumented function's wrapper adds ticks deterministically; a count
  measured with contracts on is not comparable with one off (as `:calls` already is).
- **Laziness.** An unforced lazy seq measures 143 ticks for any n. The measured function
  must force its result (the same pitfall already applies to bytes).
- **`binding`.** No variation: 57949 every run; its cost is in the count like any other.
- **Threads.** The timer is per thread (`$tc-field 'timer-ticks`, trap register in the
  thread context): another thread's work is not counted, except a collection it triggers
  (the rendezvous handler runs in this thread: the +11). Work the measured function hands
  to another thread or a fiber (a `future`, a go block, a Flow proc) is not counted at all:
  a cost proof on ticks measures the calling thread only, like `thread_bytes_allocated`.
- **The timer is shared.** Fibers use it for preemption (fibers.ss:951-954) and
  `jolt.host/run-interruptible` for interrupt polling and the tick budget, and a forked
  test run sits inside one (src/seon/agent.clj:353, with `:seon.config.agent/test-ticks`).
  `seon-ticks-of` above saves and restores the remaining tick, but the outer borrow's
  `spent` then misses the measured ticks, and on a fiber it suspends preemption for the
  measured extent. Correct nesting belongs in Jolt, the owner of the timer.
- **The worktree's config at the time of measuring.** `seon.print` would not load without
  `--set :seon.config.proofs/seed-salt=0`: resources/seon/schemas/seon.config.proofs.edn
  (uncommitted) declares the key required while default.edn sets it to nil, which the
  loader drops as "not stated". The schema entry likely needs `{:optional true}`.

## Verdict

Ticks can be the cost proof's exact time dimension, in the sense `exact` means in
src/seon/cost.clj (`exact`, line 45): the same code on the same input measures the same
on any machine at any load. Measured: zero spread on collection-free paths across
processes and loads, about 11 ticks per collection, and a rare one-quantum (1000) step.
Judge ticks at slack 1.01 with an absolute floor of 1000 x (1 + collections) ticks, keep
wall/CPU ns measured, recorded and shown but never red, and key the result by the runtime
fingerprint. Ticks do not replace bytes (bulk copies and C work are invisible to ticks,
and bytes catch them), so `exact` becomes `#{:bytes :calls :ticks}`.

API. `seon.cost/once` (src/seon/cost.clj:143) would read ticks the way it reads
thread bytes, through seon.profile:

- now, without touching Jolt: a `seon-profile-ticks-of` (the procedure above) defined in
  seon.profile's existing `scm/eval-string` block (src/seon/profile.clj:36), exposed as
  `seon.profile/ticks-of` and called by `once` around `(apply f args)`. That is correct
  off a fiber and outside `run-interruptible`. Inside the forked test runner's budget it
  hides the measured ticks from the budget.
- right: a Jolt primitive on a `jolt/ticks` branch, `jolt.host/ticks-of` (or a
  `jolt.host/run-interruptible` that answers its `spent` on a normal return), placed beside
  `jolt-run-interruptible*` (reference-code/jolt/host/chez/java/concurrency.ss:1990) and
  sharing its arm/handler state, so a measurement nested in a budget or on a fiber charges
  its ticks to the enclosing borrow and keeps the quantum. seon.profile/ticks-of then calls
  that instead of defining its own.

Scripts and raw data (edn lines): the session scratchpad
`/private/tmp/claude-501/-Users-sean-src-seon-jolt-next--claude-worktrees-repo-overview-prioritize-5dd84e/753730a3-f6a1-43a4-aa10-cbdcd3a6d09b/scratchpad/ticks/`
(measure.clj, probe2.clj, probe3.clj, drive.sh, analyse.py, data-idle.edn,
data-loaded.edn, scale-idle.edn, scale-loaded.edn). Nothing left running; the 4 burners
(PIDs 2396-2399) were killed by PID.
