---
type: landing
lane: perf-eval-overhead-opus
date: 2026-09-26
status: measured; one patch for root (config.clj, not applied); the dominant cost needs an owner decision
---

# Landing: why a trivial agent form costs ~155 ms, and what moves it (2026-09-26)

What exists: an MCP SCI form on a branch is one run of the branch's hosted agent
(`seon.cluster.agent/evaluate-source!`). The run is submitted as a durable transaction (`submit-source!`). The
agent's Flow pass then derives its work (`seon.turn/step` → `next-agent-work`), evaluates the form in its SCI
context (`seon.turn/evaluate-sources`), and settles the evaluation record in a second durable transaction
(`settle-batch!`, with the close folded in). Finally it derives the rewake (`more-agent-work?`). The bridge reads the
saved response (`seon.render.transcript/history-entries`, `seon.eval.drive/run-receipts`).

## Broken first

1. **The single-digit-ms target cannot be reached under the ruled durability.** Every form waits on two
   durable Datahike commits of about 64 ms each, 128 of the 155 ms. On this workstation a small commit
   forces 12-16 times. The disk serializes those forces at about 4 ms each, so one commit cannot get below
   about 30 ms. `:sync-blob? false` and `:commit-graph? false` were ruled inadmissible
   (`docs/seon/issues/file-store-commits-pay-five-times-the-fsyncs-they-need.md`). The owner has three
   options, priced below.
2. **The smoke-6 numbers (284-495 ms per form) did not reproduce warm.** They are
   `:seon.eval/duration-ms` (evaluation only), recorded about a minute after default became ready. Thirty
   minutes later:
   - the same forms on a fresh branch took 0-3 ms of evaluation (`(sorted-map 'b 2 'a 1)` 0 ms,
     `(require '[malli.core :as m])` 3 ms, `(m/validate int? 1)` 1 ms);
   - a new contracted `defn` took 73 ms and its first call 23 ms.

   The cold cost after boot is real but was not reproduced. I did not restart default. It is task
   `56b1dad969bd`: profile the first forms at the next restart.
3. **Nothing in `src/` was changed by this lane.** `config.clj` was free, but the preamble routes fixes
   through root first, so the one fix is a patch. JVM-mode probes were bounded, and I ran them in a
   throwaway namespace (`perf-probe`, removed afterwards). Both MCP branches (`perf-eval-overhead`,
   `perf-eval-overhead-2`) are retired.

## Method

- Default pid 28001, HEAD b4b90d405. Other lanes were live in the JVM, so JVM-wide profile growth includes
  their work.
- `tmp/perf-eval-overhead-opus/perf_probe.clj` replays the bridge's `run-form` inside the JVM with a clock
  per stage:
  - `host!`;
  - `evaluate-source!`, which is submission, the Flow pass and settlement;
  - `config/effective`, `history-entries` and `run-receipts`.
- One bounded batch of 20 trivial forms was run twice: once under JFR `settings=profile`
  (`jfr/peo-before.jfr`) and once without JFR. Each batch was bracketed by `seon.profile/begin`/`explain`.
- JFR I/O recordings used a custom config with zero thresholds for `jdk.FileForce`
  (`jfr/peo-io.jfr`, `jfr/peo-io2.jfr`).
- Scratch Datahike file stores in `tmp/` used Seon's own configuration and were deleted afterwards.
- The bridge was measured end to end by piping JSON-RPC into `bin/mcp-server`: 1 call against 6 calls.

## Per-stage table (per trivial form, warm, median of 20; in-JVM unless noted)

"Before" is HEAD b4b90d405 without JFR. "After" is the expected effect of the config patch alone. Only the
dial-walk was measured in isolation on the live projection; the patch is not applied, so the after column is
arithmetic on measured parts, not a re-run.

| stage | owner (file:line) | before ms | after ms |
|---|---|---:|---:|
| MCP bridge (bb): JSON-RPC, prepl write/read, EDN (end to end 168 ms by bridge) | script/seon/dev/mcp.clj `execute-clj-eval` | ~13 | ~13 |
| host lookup (`host!`, roster hit) | seon/cluster/host.clj:124 | 0.04 | 0.04 |
| **submission transaction** (`submit-source!`: run request row + commit) | seon/cluster/agent.clj `submit-source!` → seon/db.clj:4703 `transact-call` | 68.5 (commit ~64) | 68.5 |
| Flow hand-off + pass (await until closed) | seon/await.clj; seon/turn.clj:5727 `step` | (inside the next rows) | |
| work derivation: `next-agent-work` + rewake `more-agent-work?` | seon/turn.clj:2950, :3029 | ~7 | ~7 |
| budget reads: `episode-exhausted!` / `turns-left` / `opening-deferred?` | seon/turn.clj:5320, :2804, :2816 | ~4.4 | ~4.4 |
| acquisition/context (`fork-for-turn`) | seon/sci/eval.clj `fork-for-turn` | 3.9 | 3.9 |
| read (`sci.reader/read`) | seon/sci/reader.cljc | 0.7 | 0.7 |
| evaluate, entry gate and program-change detection (`evaluate`, `call-detecting-program-change`) | seon/sci/eval.clj `evaluate` | 1.8 (whole `evaluate-sources` 4.2) | 1.8 |
| definition analysis | none for a non-defining form | 0 | 0 |
| recording and printing (`shown-result`, `render.value/prepare`, `request-profile`) | seon/sci/eval.clj, seon/render.clj | ~2.5 | ~2.5 |
| **settlement transaction** (`settle-batch!`, close folded in) | seon/turn.clj `settle-batch!` → seon/db.clj:4703 | 62.9 (commit ~64) | 62.9 |
| of both commits: writer-side validation (`validate-write-report`, owned values) | seon/db.clj:4460, :3517 | 9.5 | 9.5 |
| config reads (`config/effective` ×3; `dial-attributes` walk 3.2 ms of it) | seon/config.clj:137, :845 | 4.0 | ~0.8 |
| MCP observation (`history-entries` 2.1, `run-receipts` 1.2, config 1.4) | seon/render/transcript.clj:802, seon/eval/drive.clj:117 | 4.8 | ~3.7 |
| **total in JVM** | | **155** | **~152** |

About 128 of the 155 ms is the two commits. Most of the remaining ~27 ms is spread over about 59
`seon.db/q` per form: 23 ms inclusive, and roughly 1 ms each on the fresh database value each commit
creates, against 0.06 ms on a held value. The profile's growth is JVM-wide and inclusive, and binding
wrappers (`call-with-projection-state`, `with-publication!`) enclose whole operations, so the rows above
use the named leaves, not those wrappers.

## Findings, largest first, with the model error behind each

### 1. Two durable commits per form, each forcing the disk 12-16 times

Task `d741e6812a31` (the owner decision) and task `b08e692710dc` (the fork change).

The force timeline of one commit (`peo-io2.jfr`, 3 forms, 98 forces, 804 ms of forces) has three phases:
- 4-8 index nodes of 120-175 KB are written concurrently. Each is followed by its own store-directory
  force. The disk serializes these at about 4 ms each, so the phase takes about 30 ms.
- The commit record is written: blob force plus directory force, about 8 ms.
- The branch head is written: blob force plus directory force, about 8 ms.

That is about 46-48 ms of forces in a 64 ms commit. The rest is Fressian serialization and `hasch` of about
0.8 MB, about 18 ms of CPU per commit from the JFR samples (konserve serialize 43 samples, hasch 28 samples,
over 40 commits).

One trivial form wrote 14 files totalling 1.6 MB.

Model errors:
- **Durable I/O per form, not per unit of work that must survive.** The submission is made durable only so
  the Flow graph can listen for it.
- **A directory force per node.** All nodes live in one directory, so one force after the group gives the
  same durability and order.

Scratch measurements (default's JVM, Seon's store configuration):

| store | commit | forces on | forces off |
|---|---|---:|---:|
| 100k datoms, `:branching-factor` 4096 | 10-entity transaction | 17-44 ms (median 28.9) | 2.6-7.9 ms |
| 100k datoms, `:branching-factor` 512 | 10-entity transaction | 19-56 ms | 4.1-5.2 ms |
| small | empty transaction | 32 ms | 16 ms |
| raw file-system shape: 6 × 150 KB nodes + two ordered 111 KB records | directory force per node | 38.4 ms median | |
| raw file-system shape, as above | one directory force for the node group | 32 ms median | |

On default itself, an empty transaction measured 45-90 ms, both raw `d/transact` and through
`seon.db/transact!`.

### 2. Config reads walk the whole schema registry

Task `bae29b83a938`; **patch ready**.

`config/dial-attributes` (config.clj:137) filters every key of `:seon.schema.projection/forms` through
`mr/schema` + `m/properties` on every `config/effective` call. That is three calls per form, 1.9 ms each
under load and 0.97 ms quiet.

The answer is a function of the projection alone, and the carried projection is `identical?` across
commits (verified). The patch keeps the answer on the projection's own holder
(`schema/projection-cache-value`, schema.clj:366), which is the existing seam, not a new cache:
- measured hit 0.0008 ms against 0.97 ms;
- the same set is returned;
- `required-dial-attributes` gets the same treatment.

Model error: work proportional to the whole registry per read, for an answer that changes only with the
projection.

### 3. The pass derives its work twice on fresh values

Task `62c45543598c`.

`step` derives `next-agent-work`, runs the turn, then re-derives the same thing through `more-agent-work?`.
The budget reads (`episode-runs`: four queries over the agent's turns since assignment) run again every
pass. The derivation costs 7-8 ms on the fresh value a commit creates, against 2.1 ms on a held value
(probe `derivation-costs`).

Model error: re-deriving what the pass's own report already decides, and history-proportional scans per
form.

### 4. `seon.db/q` wrapper and writer-side report validation

Already filed, not duplicated:
- `seon.db` task "the-query-wrapper-costs-36x-datahike": measured here at 62 µs against Datahike's 3.4 µs on
  a held value;
- "the-checked-writer-is-slow-on-a-fresh-branch": 9.5 ms per form here.

### 5. The cold cost after boot

Task `56b1dad969bd`. Smoke-6's 284-495 ms per form was not reproduced warm (see Broken 2).

## Options for the dominant cost (owner question; simplest viable constraint first)

1. **Keep two durable commits and trim them.** Apply the config patch (−3 ms). Make the fork change of one
   directory force per node group (−~6 ms per commit, durability and order unchanged, about 20-40 lines in
   Konserve/Datahike `write-pending-kvs!`). Do task `62c45543598c` (−5-8 ms) and the `seon.db/q` wrapper
   task (−5-10 ms).
   - Result: about 155 → 115 ms per form.
   - Guarantee: unchanged.
   - Cost: two lanes plus a fork change.
   - Sacrifice: the target.
2. **Recommended: one durable commit per form.** The run request reaches the agent's Flow graph as a value
   (its mailbox), and its row is written in the settlement transaction, not in a commit of its own.
   - Result: about 155 → 60-70 ms per form (−64 ms). With option 1's trims, about 45-55 ms.
   - Guarantee kept: every settled evaluation and its shown text are durable before the caller sees them.
   - Sacrifice: a JVM crash between submission and settlement loses a request nobody was answered for. The
     caller's MCP call fails. Nothing executed durably, so there is nothing to recover.
   - Cost: a design slice across `seon.cluster.agent` (submit/await) and `seon.turn` (open-for-agent reads
     the request from the pass input, not only from listened datoms). It needs owner review against "Listen
     before deriving work", because the wake stays a channel value, and against "Recover facts, not
     execution".
3. **Relax durability for evaluation records only**, for example page-cache durability on a separate
   Konserve store for evaluation facts (`:sync-blob? false` there, forces kept on program rows).
   - Result: about 15-25 ms per form with option 1, and the only path near single digits once the
     derivations are also cut.
   - Sacrifice: an OS crash or power loss can lose the latest evaluation records out of order. That
     contradicts today's ruling for the store, so it is listed for completeness, not recommended.

## Patch for root (not applied; no src edits by this lane)

`tmp/perf-eval-overhead-opus/patches/config-dial-attributes-memo.patch` (`git apply --check` passes on
b4b90d405):
- `src/seon/config.clj`: `dial-attributes` and `required-dial-attributes` go through
  `schema/projection-cache-value`. +16 / −7 lines, docstring included, no new mechanism.
- `test/seon/config_test.clj`: regression `a-projection-answers-its-dial-membership-once`. It asserts that
  the dial set contains `:seon.config.agent/turn-completion-backstop-ms` and that two calls on one
  projection answer the `identical?` set.

Root runs: `bin/test-check default --test seon.config-test/a-projection-answers-its-dial-membership-once`,
and `--ns seon.config-test` for the neighbours. Adoption: an ordinary reload of `seon.config`. No schema
change, no RESET NEEDED.

## Evidence files

All under `tmp/perf-eval-overhead-opus/`:
- `perf_probe.clj` (probe source);
- `before-profile.txt` (JFR run: profile growth plus 20 per-form rows);
- `before-nojfr-profile.txt` (clean run);
- `before-agg.txt` (JFR allocation/CPU by our frames, via `tmp/perf-profile-opus/agg.py`);
- `jfr/peo-before.jfr`, `jfr/peo-io.jfr`, `jfr/peo-io2.jfr`;
- `jfr-io.jfc` (zero-threshold force configuration).

Tasks written: `resources/seon/tasks/seon.config.edn` (new), `seon.cluster.store.edn` (new),
`seon.cluster.agent.edn` (new), `seon.turn.edn` (+1), `seon.sci.eval.edn` (+1).
