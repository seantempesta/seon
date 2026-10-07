---
type: notes
status: design review (lane review-s0-opus, fresh-context Opus)
created: 2026-10-07
lane: review-s0-opus
---

# Review of the S0 pass-probe design (`s0-probe/design-notes.md`)

Read: lane-rules.md, review-design-spec.txt, spec `docs/seon/specs/agent-loop.md` §1, §2, §3, §4.2, §5, §7, the
design end to end, and the sources below. Probes on default (agent `review-s0-opus`, JVM mode, read-only):
`runtime_status` (`:faulted`, 1 open fault, 7 errored receipts, 4 over-bound, `seon.batch/owned?` untested-hot at
7.46 M calls: the same items the design lists in its §0; nothing new), store class, profile cells, profile mark
cost. No src/test edits.

Verdict: **revise, then go.** The direction is right (read existing owners' counters around the real entrances, no
new owner in `src`, results outside the database). Three measurement claims are false against the code, and the
window duplicates `seon.profile/begin`/`explain` in test code instead of fixing that owner.

## Findings, by severity

### 1. HIGH: the write hook does not see test-world or test-run commits (overlay stores have no hooks)

- `seon.test/run` puts the request branch and every member branch on `store/open-overlay!` (test.clj:1876-1881):
  a `:datahike/overlay` store, i.e. konserve's `TieredStore` with a memory frontend and `:frontend-only` writes
  (`reference-code/datahike/src/datahike/store.cljc:137-152`).
- Its `physical-store-key` is the whole store config including the `:overlay` uuid (store.cljc:56-80), so Datahike
  gives it its own hooks atom (`connections.cljc:62-66`), and `TieredStore` does not implement `PWriteHookStore`
  at all (no `-get-write-hooks` in `konserve/tiered.cljc`; the Object default answers nil,
  `konserve/protocols.cljc:84-87`). `k/add-write-hook!` on it is a silent no-op (`core.cljc` `when-let`).
- Consequences:
  - the design's claim "branch-key writes cover every branch the pass touched, test members included" (§3) is
    false: a `test!` pass's member commits never reach the file store or any hook. Only the recording
    connection's write is durable;
  - the proposed deftest runs inside a member world (`with-running-agent` takes `support/execution-handle`, a
    member branch on the overlay), so its assertions "≥1 durable commit on the agent's branch", "store bytes > 0"
    cannot pass there; and if the hook is put on the shared file store instead, they are vacuous.
- Fix: state that test-member commits are in-memory and not durable (0 store bytes, by design of the runner),
  count them only as attempts (`seon.db/transact-call` growth); in the deftest assert only what a test world can
  show (allocation > 0, checked calls > 0, attempts ≥ 1, tokens > 0, one fixture request per model pass, a new
  cell for the defn). Durable commits and store bytes are proven by the throwaway-root run recorded in the notes.

### 2. HIGH: "branch-key `:assoc` writes = durable commits" miscounts both ways

- Branch creation writes the new branch key with `k/assoc` (`datahike/versioning.cljc:270`): a new agent's first
  pass (its branch is created on first use) would count a commit that is not one; sequence-1 records are wrong.
- `d/merge-db!` writes the head with `k/update` (versioning.cljc:427, op `:update`) and the `:branches` set with
  `k/update` (versioning.cljc:178): missed by an `:assoc`-only filter.
- A multi-key-capable store sends ONE `:multi-assoc` event with `:kvs` and no `:key`
  (`konserve/core.cljc:507`; `datahike/writing.cljc:550-565`). Default's file store answers
  `multi-key-capable? false` (probe), but the memory store answers true, so `[op key]` silently records nothing
  on such a store.
- Not every durable write goes through `seon.db/transact-call` (design §2 says it is "the only" write):
  `seon.cluster.registry` calls `d/branch!`, `d/force-branch!`, `d/delete-branch!` (registry.clj:233-239, 353,
  375) and `seon.cluster.source` calls `d/force-branch!` (source.clj:494, 687). `force-branch!` writes a commit,
  so "attempted ≥ durable" is not an invariant.
- Fix (simpler and exact): with `:commit-graph?` true (Seon's default: `store.clj:221` strips it, Datahike defaults
  true), every commit writes its content-addressed record under its commit id (writing.cljc:578, versioning.cljc:
  421). Count distinct commit ids: for each event (`:assoc`, `:update`, and each pair of `:multi-assoc` `:kvs`),
  read `[:meta :datahike/commit-id]` and `[:config :branch]` from the written value inside the hook (keep only
  those two values, never the value). That gives durable commits per branch, excludes branch creation, includes
  merges and force-branch. Report transact-call growth as "attempts through seon.db" and name the other writers.

### 3. HIGH: the profile window is a second copy of `seon.profile/begin`/`explain`; fix the owner (answers §7.1)

- `begin` (profile.clj:527-541) is already "snapshot the cells, read entry/total counters into a primitive array,
  then nanoTime", and `explain` (profile.clj:553-600) already matches cells by counter identity and counts cells
  installed later from zero. The design's `window` re-implements both in test code.
- Their cost is one fixable thing, measured now on default (JVM mode, one thread):

  | call | µs | bytes |
  |---|---|---|
  | `seon.profile/cells` | 7,216 | 2,773,048 |
  | `seon.profile/begin` | 9,264 | 2,813,880 |
  | `seon.profile/explain` | 17,564 | 5,947,680 |

  and `begin`'s own growth inside a following mark: `seon.profile/cells` ×2, `entries` ×2,498, `histogram?`
  ×6,934, `adder?` ×12,928. The armed output contract of `cells` (`[:vector :seon.profile/cell]`) validates every
  cell's arrays; `explain` calls the armed private `entries` per cell and `cells` again.
- Fix in `seon.profile` (about the 15 minutes the design estimates): `begin` and `explain` walk `roster` directly
  (the body of `cells` without its armed boundary) and read counters directly, and `explain` returns the
  unfiltered totals (sum of calls, the top-N) beside its ranked growths, instead of dropping cells under 1 ms.
  Then the probe calls `begin`/`explain` and has no profile code of its own, the spec's "profiler marks fixed"
  (§3) is met for every caller, and task `seon.profile/begin-validates-every-cell-with-armed-predicates`
  (561d2f7ee288) closes. Exclusion via a copy leaves the owner broken for everyone else.

### 4. MEDIUM: the model entrant runs more than one pass unless the episode is capped

- Today a closed reply without a disposition continues (`continuing-reply?`, r-turn-notes §D3; loop-pass's
  installed run shows call-1 and call-2 for one wake). The overlay in design §2 omits
  `:seon.config.run/max-episode-runs`; r-turn used `{… :max-episode-runs N}` (r-turn-notes:79). Without `1`, one
  window holds several model calls and the fixture's reply queue runs dry, so "exactly one request per model pass"
  fails or the window measures an episode.
- Fix: overlay includes `:seon.config.run/max-episode-runs 1`; the window ends on the settled terminal, not on the
  first settlement commit.

### 5. MEDIUM: missed owner for "write the wake, await the terminal"

- `seon.eval.drive` already writes an outside message (`inbound!`, drive.clj:72-102, private) and awaits facts with
  `d/listen` (`await-fact!`, drive.clj:51-70) for `run-episode!`. `seon.render.web` has a second inbound writer
  (web.clj:2979-3011). The probe's `model!` would be a third.
- Fix: make `seon.eval.drive`'s inbound and await helpers public (or call `run-episode!` with a run cap of 1) and
  use them; do not write a third. If that needs a src claim, it is a two-line visibility change.

### 6. MEDIUM: the fixture provider: one shared fixture, not a new one in the probe

- §3 item 1 gives the probe its own `HttpServer` ("started and stopped inside `run`"), while §3's last paragraph
  moves agent-test's into `seon.test-support`. Pick the second only: the probe uses the shared
  `with-fixture-provider`, extended with the queue of replies, received bodies and instants.
- There are three provider fixtures, not two: `seon.cluster.agent-test/with-provider` (agent_test.clj:331-371),
  `seon.cluster.turn-test/with-provider!` (turn_test.clj:365), and `seon.ai-stream-fold-test/with-provider`
  (http-kit, ai_stream_fold_test.clj:218). The last tests the wire itself and may stay; say so.

### 7. MEDIUM: workload gaps against spec §3

- **Opening and input passes** are in the S0 acceptance workload and are not in the design. They do not exist until
  S3/S4; today's equivalent of an input is the outside-message wake (the `model!` entrant). Say this, label
  today's model pass as the input-pass baseline, and reserve `:opening`/`:input` kinds for S3/S4.
- **Context append** (< 10 ms, ≤ 3× appended text) is a budget row with no measurement in the design. Before S4 it
  is the system turn's refresh (spec §2: ~80% of a model turn); the model record should split it out (it is
  visible as profile growth of the refresh owner), or the design should state it is measured from S4.
- **"200 exchanges"**: the default `:one-pass` build makes one pass of 200 forms, which is not 200 exchanges once
  the transcript is per pass (S4), and does not exercise run-count-proportional work (the design says so). For
  the spec's workload use `:per-exchange` on the throwaway root (≈ 30 s, ≈ 30 MB once per root, outside every
  window) and keep `:one-pass` only as a labeled "200 forms" variant, or drop it.

### 8. LOW: details of the window

- Threads created during the window have no start reading: say their start is 0 (their bytes are in the end
  reading or in the exited remainder).
- Decoding the MCP reply and estimating tokens must happen after the end reading; §3 puts JSON decoding inside the
  pass thunk.
- Profile cell sets are weak (`WeakHashMap`, profile.clj:129-131): a cell installed and collected inside the
  window loses its calls. Negligible, state it.
- On default, store bytes from the hook include other writers' index nodes (only commit records carry a branch).
  Record the commit ids of foreign branches seen in the window as a contamination count, as for allocation.
- The deftest: at today's costs (defn pass ~700 MB, the pass after 1.0-1.3 s / 3.2 GB, a model turn ~2 s /
  3.6 GB, spec §2) a matrix of eight passes is multi-second and multi-GB. Keep the deftest to one ordinary MCP SCI
  pass, one model pass and one defn pass, with `:seon.test/long` naming the operation, and run the matrix outside
  the test tier.

### 9. LOW: claim checks

- Supported: `seon.mcp/call`'s 200-character clip (mcp.clj:397), the task filed for it; `d/transact!`/
  `d/merge-db!` only at db.clj:5721 inside `transact-call` (but see finding 2 for the other writers); konserve hooks
  after successful writes (`core.cljc:30-51, 372, 507`); `key->store-key` = `(str (uuid key) ".ksv")`
  (`impl/defaults.cljc:47-48`); the profile cell keys and `LongAdder` entry counters (live probe); `test/` on
  default's classpath (`seon/test_support.clj` resolves); `seon.ai.tokens/estimate` exists (tokens.cljc:145, a
  `.cljc`, not `.clj`).
- The virtual-thread argument is sound: allocation is charged to the carrier's `JavaThread`, so
  `getTotalThreadAllocatedBytes` deltas (exited threads included) are the right pass total and per-platform-thread
  deltas only attribute. Not re-measured here; the design's own probe (100.0 MB on the carrier for ~100 MB in a
  virtual thread) is consistent.
- `seon.eval.drive/inbound!` is private (drive.clj:72), not a callable entrance as §2 implies.

## One-path check

Which step it serves: none directly; it measures the loop. It adds no src owner, cache, printer or store. Second
mechanisms it would create as written: the profile window (finding 3), a third inbound writer (finding 5), a
fourth provider fixture (finding 6). With those three folded into their owners the probe is one test-path
namespace of readings plus a matrix runner, which is the smallest composition I can see. Results as committed EDN
outside the database is right (owner decision §7.2).

## Cross-design conflicts

- **walk-delete** converts `mcp_entrance_test` ("a form that binds a Var stores no read evidence" → "no evaluation
  stores read evidence") and deletes system-turn deftests in `cluster/turn_test`: the same files S0 claims for the
  fixture move. Root must sequence the claims (S0's move first is the smaller rebase, since walk-delete waits for
  owner approval).
- **walk-delete / S4** delete the system-turn refresh: S0's model baseline will mostly be that refresh. That is the
  purpose, but the baseline must be taken on the parent before walk-delete lands.
- **loop-pass, reader-prose, repl-lookup**: no file overlap found with S0's claim list (reader-prose has no notes in
  the tree; loop-pass has only `runs/`).

## Answers to §7

1. **Fix `seon.profile/begin` (and `explain`) in this slice; do not exclude by copying.** The owner is two small
   functions; the measured cost is 2.8 MB / 9 ms per mark and 5.9 MB / 18 ms per explain, all from armed reads of
   its own roster. The probe then uses the owner, and every other caller (health, slow-call explanations) gets the
   fix. It needs a `src/seon/profile.clj` claim and a regression asserting a mark's growth contains no
   `seon.profile/*` cell.
2. **Move the helpers into `seon.test-support`**, converting callers in the same commit, but only
   `with-running-agent` and one provider fixture (agent-test's, extended with the reply queue and request ledger).
   Leave `ai-stream-fold-test`'s wire fixture. Convert `turn_test`'s server half only if mechanical, after
   walk-delete's claim on that file is settled.
3. **Yes, the test entrant as an MCP SCI pass of `my.test/check`**: that is how an agent runs a test (one path).
   Record the check's own `:ms` and its run result beside the pass, and state that its member commits are on an
   overlay (finding 1), so its durable count is the recording write only.

## Questions for the owner

- None blocking. The one judgment: the deftest's size (finding 8). I recommend three passes in the test tier and
  the full matrix only on the throwaway root.
