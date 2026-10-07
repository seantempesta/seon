---
type: notes
status: design (gate 1: awaiting root's dual review)
created: 2026-10-07
lane: s0-probe
---

# S0: one pass probe for every entrant (design, lane s0-probe, 2026-10-07)

Spec: `docs/seon/specs/agent-loop.md` §3 (budgets, the S0 acceptance workload) and §5 (S0's row). Evidence read
end to end: `plan-research/r-turn-notes.md` (§8 S0 design, §9), `pass-alloc/pass-alloc-notes.md`,
`line-column/line-column-notes.md`, `plan-research/r-record-notes.md` (method). HEAD `ba3b5de0b`; default runs
`18a68b248` (`bin/seon status`).

What exists and the approach, in two lines: every number S0 needs is already produced by an owner (HotSpot's
`ThreadMXBean`, `seon.profile`'s cells, Datahike's commits through konserve's write hooks, the value printer's shown
text and `seon.ai.tokens/estimate`); S0 adds one test-path namespace that brackets ONE pass of each entrant with
those readings and returns the window as data, plus one deftest that runs it against the test world.

## 0. Broken or degraded at probe time

- Default `:faulted`: one open fault (`3d2fb1b41dc8`, fixed by defn-cost, awaiting root's restart); 7 errored
  receipts; over-bound `seon.fn/analyzed-files`, `adopt-entities!`, `publish!`, `index!` (all with tasks).
- `seon.batch/owned?` is the top health item: 7,458,662 calls since boot, untested-hot. It is the per-entry
  predicate of the armed fold that validates every entry so far; linked to the existing task
  `seon.batch/each-fold-step-validates-every-entry-so-far` (08034d6f4d2b), not a new class.
- Found while reading `seon.mcp/call`: its catch clips the request to 200 characters
  (`(subs request 0 (min 200 …))`), a second clip outside the value printer. Filed
  `seon.mcp/a-thrown-call-clips-its-request-text` (c187d12d1e3c).
- A full stat scan of `data/store` (190,138 files) took 2.8 s in one JVM-mode eval (slow finding on my own probe);
  that is why store bytes are NOT measured by scanning the directory (§3).

## 1. Which step of the one loop it serves

None of the read → eval → record → print path changes. S0 is the measurement every later slice reports
before/after (spec §3: "Measured by the S0 probe each slice"). It adds no owner, cache, printer or path; it reads
the owners' existing counters around the entrances agents already use. What it lets go: the per-lane scratch probes
(`pass-alloc/pa.clj`, `r-turn/probe-fixture.clj`, `r-record/probe2.clj`, never committed) that each re-derived the
same windows with different blind spots (whole-JVM allocation only, the profile mark counting itself, store-file
scans), and the third copy of an HTTP provider fixture a probe would otherwise write.

## 2. What exists to build on (verified)

| need | owner today | verified |
|---|---|---|
| entrances | `seon.mcp/call` (JSON in/out) for MCP SCI and MCP JVM; a model turn is woken by an outside message (`[:db.fn/call #'seon.cluster.message/inbound-tx …]`, as `seon.eval.drive/inbound!` writes it) and runs in the agent's own Flow proc; a test run is `my.test/check` from the agent's SCI | `seon.mcp/call` on a fresh platform thread from a JVM-mode eval: one durable commit on the agent branch (hook probe below). r-record: calling it on the eval's own thread is refused by the writer (foreign `*conn*`) |
| model provider as a value | agent overlay `{:seon.config.ai/no-provider false :seon.config.ai/endpoint "http://127.0.0.1:<port>/chat" :seon.config.ai/no-auth true :seon.config.ai/model "s0-fixture"}` through `my.agent/settings!`; an unregistered model keeps the overlay endpoint (ai.clj:356). A JDK `HttpServer` answering one SSE stream | r-turn §2 drove four turns this way on `722f110df`; the same server exists twice in tests (`seon.cluster.agent-test/provider-server!`, `seon.cluster.turn-test/with-provider!`) |
| a running instance in a test | `seon.mcp-entrance-test/with-running-agent`: an armed agent on the member's branch registered in `running-instances` | used by 7 deftests there |
| checked calls | `seon.profile` cells: each armed callable's `:seon.profile/entry-counter` (outermost entries) | 2,591 host cells on default; `seon.db/transact-call` has its cell (151 entries since boot) |
| transactions attempted | every write goes through the private `seon.db/transact-call` (the only `d/transact!`/`d/merge-db!` call in `src`, db.clj:5721), so its entry growth is the attempt count | `rg` over `src` |
| transactions durable, store bytes | Datahike gives every connection of one physical store the same konserve write-hooks atom (`datahike/connections.cljc:62-66`, `connector.cljc:376`); konserve invokes hooks after each successful `assoc`/`multi-assoc`/`bassoc` (`konserve/core.cljc:30-51, 372, 507`); a commit writes its branch head under the branch keyword (`datahike/writing.cljc:587, 749`); a file's name is `(konserve.impl.defaults/key->store-key key)` | JVM-mode probe on default: `k/add-write-hook!` around one MCP SCI `(+ 1 2)` of agent `s0-probe-a` saw 37 `:assoc` keys, exactly one branch key (`:agent-cab8605bb07a`, the agent's branch), 204,921 bytes in their files; removed in `finally` |
| allocation | `ThreadMXBean`: `getTotalThreadAllocatedBytes` (all threads, exited included) and `getThreadAllocatedBytes(long[])` per platform thread | probe below |
| tokens | `seon.ai.tokens/estimate` (the one estimator, shipped calibration) over the texts shown: the MCP reply's text items; the model's request body as the fixture server received it | — |
| clean JVM for before/after | the operator's throwaway root: `bin/seon --root <scratchpad>/s0-probe/root start probe` boots a committed archive on its own store (pass-alloc's method); `bin/seon check … --test seon.pass-probe-test` boots the candidate in a throwaway JVM | pass-alloc used the first; `seon.operator/candidate-tests!` is the second |

## 3. What S0 adds (smallest composition)

Two files, both on the test path (`:test` alias; every cluster JVM, default included, runs with it):

1. `test/seon/pass_probe.clj`, namespace `seon.pass-probe`:
   - `window` — call a thunk (one pass) and return its measurement map. Readings, in this order so the probe's own
     work is outside the window:
     - start: the live profile cells (`seon.profile/cells`, armed: its ~18k validation calls happen here, before
       the counters are read), then each cell's entry counter read directly (`.sum`, no armed call); then
       `getThreadAllocatedBytes` of every live platform thread and `getTotalThreadAllocatedBytes`; then a konserve
       write hook (unique id) appending `[op key]` to a `ConcurrentLinkedQueue` it closes over; then `nanoTime`.
     - end: `nanoTime`; total and per-thread allocation; remove the hook; then the counters of the start cells
       read directly, and only then `seon.profile/cells` again for cells installed during the window (a new
       definition's wrapper), which count from zero.
   - `entrant` passes, each returning its shown texts: `mcp-sci!`/`mcp-jvm!` (`seon.mcp/call` on a fresh platform
     thread, the reply decoded as data); `model!` (writes the outside wake on the agent's home branch, awaits the
     settlement commit of the turn it triggered with a `d/listen` on that branch, then the proc's idle report before
     the next window opens); `test!` (an MCP SCI pass whose form is `(my.test/check {:seon.test/changed [sym]})`
     for one small existing test).
   - `history!` — grow an agent's history to N exchanges of 2 KB shown text cheaply (§5).
   - `run` — the matrix for a slice selection (`entrants`, `kinds`, `histories`, `repetitions`), returning a vector
     of measurement maps. Each JVM-mode MCP call runs one slice so it stays under the bridge's 32 s timeout.
   - The fixture provider: one `HttpServer` serving a queue of reply texts as SSE, recording each request body and
     its receive/respond instants; started and stopped inside `run`.
2. `test/seon/pass_probe_test.clj`: one deftest, `a-pass-of-each-entrant-is-measured`, through
   `seon.test-support`'s running-agent world (below): history 1, kinds ordinary/defn/after for SCI and model, ordinary
   for JVM, one check for test. It asserts DATA only, the facts that make the probe non-vacuous: allocation read
   positive (never the -1 a virtual thread answers), ≥1 durable commit on the agent's branch per recorded pass,
   attempted ≥ durable, store bytes > 0, checked calls > 0, tokens > 0, the model's fixture saw exactly one request
   per model pass, and the defn pass installed a new cell. No time or byte bound is asserted (clojure-testing §1.7);
   declared `:seon.test/long` with the measured operation if it exceeds 5 s.

No duplicate fixtures: `with-running-agent` moves from `seon.mcp-entrance-test` into `seon.test-support`
(`running-agent`), and agent-test's `provider-server!`/`with-provider` move there too (`with-fixture-provider`);
both callers convert in the same commit. turn-test's `with-provider!` additionally applies cluster config; its server
half converts to the shared one if that stays a mechanical edit, else it keeps its copy with a task.

Claims needed after the go: `test/seon/pass_probe.clj`, `test/seon/pass_probe_test.clj`, `test/seon/test_support.clj`,
`test/seon/mcp_entrance_test.clj`, `test/seon/cluster/agent_test.clj` (+ `test/seon/cluster/turn_test.clj` if
converted), and this notes directory. No `src` or schema resource changes; no publication or adoption proof needed.

Measurement record, per pass (fully namespaced, `:seon.pass-probe/…`): entrant, kind, history (exchanges before the
pass), sequence (1 = first pass of that kind on the agent: cold; later = warm), wall-ms (and for the model,
provider-ms from the fixture's instants, so Seon-ms = wall − provider), allocated-bytes (total) and
allocated-by-thread (grouped by thread-name prefix: virtual-thread carriers `ForkJoinPool-…`, `async-mixed`,
`async-io`, the probe's entrance thread, other, and the exited remainder = total − sum), checked-calls (sum of entry
growth) with the top 10 definitions by calls, transactions-attempted (`seon.db/transact-call` growth),
transactions-durable by branch (branch-key writes) with each commit's `:seon.db.transaction/operation`, store-bytes and
store-files (non-blob keys), blob-bytes (`:bassoc`), tokens-shown, and the git sha and JVM pid. Results are recorded
as EDN in `docs/research/agent-platform/2026-10-07/s0-probe/` (committed), never in the database: stored profile
measurements were retired by owner decision §7.2, and a probe must not grow the store it measures beyond its passes.

Contracts: every function carries a one-line Malli contract; the measurement map is one inline `[:map …]` named once
in the namespace. No schema EDN is registered: nothing outside the probe reads these keys (a consumer would justify
registration later).

Simplest alternatives considered:
- `seon.profile/begin`/`explain`: rejected for the window. `begin` costs ~18,600 armed calls and ~3.5 MB per mark
  (task `seon.profile/begin-validates-every-cell-with-armed-predicates`, 561d2f7ee288), `explain` calls `cells` and
  the armed `entries` per cell before reading, and its growths drop cells under 1 ms and over the limit, so no total
  is derivable. The spec allows excluding the mark (§3, "fixed or excluded first"); reading the counters of cells
  taken outside the window excludes it exactly. Fixing `begin` (inline the roster walk) is a 15-minute follow-up in
  `seon.profile` that I can take if root prefers it to exclusion; it would not change the probe's numbers.
- Store bytes by scanning `data/store` for files newer than the start (r-record): 2.8 s per scan on default and it
  attributes other agents' writes; the write hook names exactly the keys written and their branch.
- Transactions by `max-tx` deltas per known connection (pass-alloc): misses test-member branches and the task home;
  branch-key writes cover every branch the pass touched, test members included.
- The model provider as a `:seon.ai/send!` value: not reachable through a turn today (r-turn finding 1); the spec
  names the HTTP fixture endpoint in agent settings, which runs `ai/complete` as installed.

## 4. Allocation, measured correctly

- Virtual threads have no HotSpot count: `getCurrentThreadAllocatedBytes` on a virtual thread answered `[-1 -1]`
  (JDK 26.0.1, default). Their bytes are counted on the carrier: a virtual thread allocating ~100 MB moved the
  carrier `ForkJoinPool-1-worker-4` by 100.0 MB and `getTotalThreadAllocatedBytes` by 107.7 MB (the rest: two
  `async-mixed` threads and the bridge). So the pass's allocation is the total delta, which counts every thread,
  exited ones included, and the per-platform-thread deltas attribute it by role.
- Per-form `:seon.batch/allocated-bytes` stays the line-column lane's (`seon.batch/allocation-is-unmeasured-on-a-virtual-thread`,
  ae0364590441, uncommitted slice 2 in the shared tree makes it absent on a virtual thread). The probe does not read
  it and builds nothing beside it: a pass spans the proc's carrier, the writer's `async-mixed` thread and the
  bridge thread, so only the JVM-wide reading with per-thread attribution measures a pass. I will tell root this so
  slice 2's decision does not wait on S0.
- Noise: the total includes concurrent work. On default it is real (an "idle" 10 minutes wrote 932 store files);
  each record keeps the by-thread split, and the before/after numbers a slice reports come from the throwaway root
  (quiet JVM, same committed archive), with default's run reported beside them. Repetitions report each run, not a
  mean; the summary gives min and median.
- JFR (pass-alloc's attribution at stackdepth 512) stays the tool for "where", run on the throwaway root when a slice
  needs it; the probe answers "how much".

## 5. A 200-exchange history, cheaply

One MCP SCI pass per history size whose text is N distinct forms each returning a 2,000-character string,
`(apply str "e<i>" (repeat 1996 \x))`: N exchanges of ~2 KB shown text (verified: such a string is shown whole,
2,002 characters, no clip) in ONE settlement commit. The armed fold is quadratic in its form count (line-column:
200 one-character forms 545 ms / 163 MB), so a 200-form build is about a second, paid once per agent, outside every
window, and its own cost is recorded as a setup record. One agent per history size (`s0-<sha>-h1`, `-h10`, `-h200`)
carries all four entrants, so every entrant sees the same history; the passes the probe adds are counted in each
record's `history` field (1 → 1 plus earlier probe passes is visible, not hidden).

Limit, stated: today history-proportional machinery grows with the agent's RUN count (pass-alloc: +0.2 MB per earlier
run), which a one-pass build does not exercise. The probe takes `:seon.pass-probe/build` `:one-pass` (default) or
`:per-exchange` (N single-form passes: 200 × ~100-150 ms ≈ 30 s, 200 commits, ~30 MB store) so the run-count curve
can be measured when a slice needs it; S6 deletes that class.

Cold vs warm: the first pass of each kind on an agent is reported as `sequence 1` (cold: for the model, its first
prompt render; for a new agent, hosting and acquisition), repetitions 2..n as warm. A proc-restart cold acquisition
(spec §3's row) exists only after S4's proc-held transcript; until then the throwaway root's first pass after boot
is the nearest measurement and is labeled so.

## 6. Running it

- On default, one JVM-mode MCP form per slice, e.g.
  `(seon.pass-probe/run {:seon.boot/cluster-name "default" :seon.pass-probe/entrants [:mcp-sci] :seon.pass-probe/histories [1 10] :seon.pass-probe/repetitions 3})`.
  The JVM entrant runs only the ordinary kind on default: a JVM-mode `defn` interns a Var in default's JVM, which
  lane rules forbid; the throwaway root runs it.
- On the throwaway root, the whole matrix. Before/after of a later slice = the same `run` on roots booted from the
  parent and from the change.
- Every agent and branch the probe creates is retired with `my.agent/retire-branch!` at the end of `run`; the
  fixture server and the write hook are released in `finally`.
- Expected cost of a full matrix on default: ~100 passes, ~1-3 minutes in total split over slices, ~30-50 MB of store
  (150-700 KB per commit) reclaimed by retention after retirement.

## 7. Open questions for root

1. Exclude the profile mark (this design) or fix `seon.profile/begin` in the same slice?
2. Is moving `with-running-agent` and the provider fixture into `seon.test-support` (converting their callers) the
   wanted scope, or should the probe's deftest live in `seon.mcp-entrance-test` and use its helper in place?
3. The test entrant as an MCP SCI pass of `my.test/check` (how an agent runs a test) rather than a direct
   `seon.test/run` call: the record carries the check form's own `:ms` beside the pass total.
