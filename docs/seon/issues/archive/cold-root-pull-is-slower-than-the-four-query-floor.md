---
type: issue
status: resolved
severity: blocker
tags: [issue, render, context, performance]
---

# Cold root pull is slower than the four-query floor

## Problem

The W2 schema-derived root acquisition enters `seon.db` exactly once, but the
isolated cold pull takes 1,795.387292 ms in the acceptance fixture. The prior
four-query acquisition floor is 46.0 ms, so the replacement is 39.0301585×
slower on cold acquisition even though unchanged acquisition is read-free.

## Evidence

The clock started after fixture and SCI request construction and surrounded
only `seon.render.walk/root-acquisition`. The result contained one member. The
query observer counter independently proved the operation sequence was exactly
`[:pull]`; projection acquisition issued no hidden query.

Full measurement context is recorded in
[W2 change-flow acceptance evidence](../../prds/sci-execution-runtime/research/w2-change-flow-acceptance-2026-08-12.md).

## Acceptance

- Profile selector construction, Datahike pull execution, and result indexing
  separately on the seeded cluster.
- Preserve the concrete forward/reverse selector, one-read membership oracle,
  component evidence, and exact invalidation semantics.
- Record a cold root-only sample at or below the 46 ms four-query floor without
  using elapsed time as a correctness verdict.

## Owner

Render acquisition performance after W2.

## 2026-08-12 post-ambient-resolution measurement

The hypothesis that W2's 1,795.387292 ms cold pull was dominated by ambient
schema resolution is falsified. A fresh JVM and fresh in-memory branch used the
recorded W2 fixture, constructed the request before the clock, and timed only
`seon.render.walk/root-acquisition`:

`{:seon.render.walk/cold-pull-ms 1673.052083,
  :seon.render.walk/member-count 1,
  :seon.render.walk/four-query-floor-ms 46.0}`

That is 122.335209 ms (6.8%) faster than the recorded before value, but still
36.37× the four-query floor. The ambient conversion did not collapse this
cost; the residue is real and this issue remains open for its own profile.

## 2026-08-12 cold attribution

The W2 acceptance evidence and this issue were read end to end before the
probe. The dependency boundary is Datahike fork
`407e9328851ccce318148188f1d284646eb64132` and datalog-parser fork
`08a32d8f2facde9986e257e3df2807104402bf59`.
`datahike.pull-api/pull-with-evidence` parses the selector once for the pull
and again for `pull-dependency-plan`; `seon.db/decode-pull-result` parses it a
third time. None of those owners memoizes the parsed selector.

The isolated live probe used
`bin/seon --root tmp/coldpull-root start coldpull`, restarted the JVM before
each cold sample, constructed the request before the clock, and timed only
`seon.render.walk/root-acquisition`. The live root sample contained 29
members:

| Phase | Cold wall time |
|---|---:|
| Complete root acquisition | 1,524.701 ms |
| Selector generation | 1.514 ms |
| Datahike pull with evidence | 1,102.008 ms |
| Three selector parses, inclusive across pull/evidence/decode | 1,013.093 ms |
| Result decode, including its third parse | 414.036 ms |
| Membership index | 6.245 ms |
| Stable evidence-result capture | 0.172 ms |
| Evidence fingerprinting after acquisition | 1.262 ms |

The selector has 669 top-level entries at distance 1. A second cold-process
sample against an absent lookup removed result size from the equation while
preserving the same selector. It measured 1,400.948 ms total: 1.679 ms selector
generation, 1,054.606 ms pull with evidence, 1,073.142 ms across the same three
selector parses, 342.372 ms decode including the third parse, 1.468 ms
membership indexing, 0.003 ms stable-result capture, and 3.624 ms evidence
fingerprinting. Datahike pull execution itself was below 0.280 ms in that
sample. These nested timings are not additive: the parse total is contained
inside the pull/evidence and decode totals.

The requested remaining categories are zero inside the measured boundary.
`root-acquisition` invokes no render function, performs no admission/print,
and consults no render-candidate index. Those operations begin later in
`neighborhood`/`history`; they cannot explain a clock surrounding only
`root-acquisition`. Selector generation is per acquisition today, but its
1.5–1.7 ms cost also cannot explain the residue. The existing 19.6 ms cold raw
Datahike pull remains consistent with this attribution: the regression class
is compilation and repeated traversal of the large concrete selector around
the index pull, not the index pull itself and not ambient projection.

### Owner ruling required — exactly three options

1. **Compile the concrete pull once at the Datahike owner (recommended).**
   Add one maintained Datahike pull-plan boundary that parses the EDN selector
   once, derives its exact dependency plan once, executes from that parsed
   value, and hands the same parsed value to Seon decoding. Acquire that plan
   once per immutable schema generation plus distance/caps. Guarantee: the
   current forward/reverse selector, one-read membership oracle, component
   evidence, and invalidation semantics remain unchanged. Cost/risk: moderate
   coordinated changes in the maintained Datahike fork and `seon.db`, with a
   direct fork regression plus the W2 acceptance proof. Operational trade-off:
   retain one immutable compiled plan per active schema-generation/profile
   key. Gives up: nothing in the W2 contract; the dependency API gains an
   explicit compiled-plan value.
2. **Make every current traversal DAG-aware within one call.** Preserve the
   EDN-facing APIs, but identity-memoize shared nested selector parsing and
   dependency walking in datalog-parser/Datahike, and compile Seon's decoder
   options once per shared parsed subpattern. Guarantee: no process-lifetime
   cache and no contract change; cost becomes proportional to unique selector
   nodes rather than repeated occurrences. Cost/risk: medium-to-high changes
   across three traversal owners, with careful alias, recursion, and CLJ/CLJS
   fork proofs. Operational trade-off: invocation-local memo tables and more
   complicated walkers. Gives up: the present simple tree-walk
   implementations.
3. **Return to layered small reads.** Replace the recursive all-ref pull with
   depth-layered pulls/queries and combine their evidence into the membership
   result. Guarantee: each read stays small and can return toward the measured
   four-query floor. Cost/risk: high first-party redesign of membership,
   component evidence, and invalidation, with more database round trips.
   Operational trade-off: latency depends on depth and read count. Gives up:
   W2's exactly-one-read membership oracle, so this option requires explicitly
   overruling the current acceptance contract.

No production code was changed pending this ruling.

## 2026-08-12 retained changed-run attribution

The 61-minute `bin/test --changed` boundary retained at
`tmp/test-runs/run.LksNlc` is this acquisition defect under the real parallel
runner, not an unattributed runner spin:

- `test-run.txt` records shared-base preparation PID 87947 exiting zero after
  64 seconds, followed by selected-runner PID 88819 running from 01:21:26 to
  02:22:55 and being reaped with exit 137 only after the launcher received
  TERM.
- Pool worker PID 88834 acquired the packaged test projection once. Its
  retained stderr contains six consecutive thread dumps taken at 02:19:11,
  after 3,391 seconds of worker lifetime. In all six, the main thread is
  parked in `seon.cluster.prompt-test` awaiting
  `seon.render/acquire-context!`.
- In every dump, carrier `ForkJoinPool-1-worker-4` is mounted on virtual thread
  187 inside `datalog.parser.pull/parse-pattern`. The complete first-party
  stack is `datahike.pull-api/pull-with-evidence` → `seon.db/pull` →
  `seon.render.walk/root-acquisition` → `seon.render.web/acquire-root` → the
  render proc. The repeated dump therefore identifies the active operation,
  including its virtual thread; no runner frame is doing compute.
- The worker heap is saturated in every dump: 16,777,216 KiB committed and
  16,744,263–16,744,272 KiB used, with 2 of 2,048 G1 regions free. G1 worker
  threads 0–13 each report about 255–257 seconds of CPU, roughly 59.7 minutes
  in aggregate, while the mounted parser carrier reports 85.8 seconds. This is
  an allocation/GC collapse during the recursive selector parse, not a test
  assertion failure or a cold schema-population fallback; the pool-3 log
  contains no such fallback warning.

This raises the acceptance boundary: the compiled-plan repair must prove the
same prompt/render-path acquisition completes with bounded allocation under a
real changed-path runner worker, in addition to meeting the 46 ms cold latency
floor. The runner itself is only the observer and needs no separate issue or
change for this retained incident.

## 2026-08-12 spine-repair gate confirmation

The spine repair's changed-path gate selected 71 platform tests and 1,075 bulk
tests because the changed schema resource has no program-graph reachability
edge. The platform tier completed green. Emitted bulk results, including the
changed root-acquisition, prompt, fault-encoding, and render-web owners, also
remained green before the same allocation collapse stopped the gate from
publishing a terminal verdict.

At 31 minutes, worker PIDs 6359 and 6363 each held about 17 GB RSS and remained
CPU-bound. Virtual-thread-aware dumps retained at
`tmp/test-runs/run.4BPt75/worker-6359-thread-dump.json` and
`tmp/test-runs/run.4BPt75/worker-6363-thread-dump.json` independently show the
same recursive `datalog.parser.pull/parse-pattern` boundary. PID 6359 reaches
it from `seon.sci.eval-test` through `seon.render.walk/neighborhood`; PID 6363
reaches it from `seon.render.web/render-step` through
`seon.render.web/acquire-root`. Both pass through `datahike.pull-api/pull-with-evidence`,
`seon.db/pull`, and `seon.render.walk/root-acquisition`.

The gate was interrupted at that named owner and retained under
`tmp/test-runs/run.4BPt75`; it is not a failure in the spine repair or in the
protected test runner. This second real-runner observation strengthens the
existing compiled-plan acceptance criterion rather than creating another
issue.

## Owner ruling 2026-08-12 (morning)

Option 1 is ruled: compile the concrete pull once at the Datahike owner.
Parse at selector generation (schema publication), retain one immutable
compiled plan per schema-generation/profile key, and hand that parsed value
to evidence derivation, execution, and Seon decoding — the same
derived-state-rides-the-value pattern as the environment projection. The
W2 one-read membership contract is unchanged.

## Resolution 2026-08-12

Datahike commit `cdcb5792db8bd599487f099437265d18a31164a5` compiles each
unique shared selector subpattern once and makes dependency derivation
DAG-aware. Seon pins it at `db67d8ab1`, and `2e814eec1` retains the resulting
root pull plan in the schema projection's compiled cache for direct, web, and
through-SCI acquisition paths.

The isolated depth-2 plan probe previously exhausted a 2 GiB heap and the
original through-SCI test exhausted a 4 GiB heap. After the fork repair, plan
derivation completed in 24.719917 ms with 12,434,840 allocated bytes, one root
acquisition, and five unique subpattern parses. The focused cold acquisition
sample after integration was 7.260791 ms against the recorded 46.0 ms floor.

The now-fast plan exposed a second allocation owner in the same failing path:
`config/effective` was resolved 36 times by render argument construction,
allocating 57,385,026,968 bytes in 17,227.545875 ms. Commit `17dac676e`
resolves it once in `seon.render/walk` and carries the resulting profile
through every guarded render argument. With the fixture's explicit effective
defaults, the through-SCI probe resolved configuration once, completed in
336.320875 ms, and allocated 281,125,960 bytes.

`public-walk-is-callable-through-an-agent-sci-eval` now counts exactly one
root plan derivation shared by through-SCI, direct, and web acquisition, one
effective-config resolution for the render operation, and an allocation bound
below 1 GiB. It and
`require-context-rows-persist-namespace-lookup-refs` passed together in three
independent focused JVM runs. The maintained Datahike regression passed in
all three configured test platforms: 6 tests, 30 assertions, zero failures.

## 2026-08-12 integration-gate evidence

The complete `bin/test --all` attempt at `12d9dcee8` loaded all 124 test
namespaces, completed the 71-test platform tier, and advanced 1,176 of 1,178
selected test rows through their worker `END` event without a recursive pull
stall, heap saturation, or OOM. In particular,
`cold-root-pull-records-an-informational-latency-sample` completed in 42 ms,
the other root-pull regressions completed, and the prompt/render-path tests
that previously exposed `datalog.parser.pull/parse-pattern` allocation
collapse returned. The run eventually exited 124 at a distinct agent-test
worker liveness boundary recorded in
[[parallel-test-stress-exposes-eleven-isolation-sensitive-tests]]. This issue
therefore remains resolved: the integration gate did not reproduce its
selector-parse stall/OOM class.

The keystone lane's separate 24.2-second live-path measurement for a pull of
189 members is not explained by this gate. The gate did not time that exact
live request or member cardinality, so the measurement remains an explicit
open line in [[live-root-pull-of-189-members-takes-24-seconds]].
