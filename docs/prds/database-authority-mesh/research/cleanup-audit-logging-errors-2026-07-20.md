---
type: research
status: complete
tags: [research, pod, database, architecture]
---

# Cleanup audit — logging, warnings, and error reporting (2026-07-20)

Read-only audit of logging/printing call sites, throw-into-loop risks, and
stored-warning risks across `src/`. Every claim below is grounded in a file
read or `rg` hit on branch `codex/runtime-reliability-refactor`.

## Dependency ledger

| Concern | Owner | Source |
|---|---|---|
| Pod structured logging | `seon.log` (`error!`/`warn!`/`info!`/`debug!` → console + NDJSON-EDN `logs/pod-events.log`; `*-console!` for console-only; `tail` reads the file) | `src/seon/log.cljs` |
| Library log gate (pod) | `seon.log/quiet-library-logs!` over `taoensso.trove` (+ `taoensso.trove.console`) — datahike/konserve/replikativ route through trove; Seon's own logging does NOT | `src/seon/log.cljs:111-146` |
| Derived warnings | `seon.warn` — check registry, `run-checks`, `render-warnings`; nothing stored, self-healing | `src/seon/warn.cljs` |
| Error values + fault datoms | `seon.error` — `->map` envelope, `record!` (fault-tagged datom, buffered persist), `escalate!` per `:seon.config/on-core-error`; `SEON-CORE-FAULT` stderr marker | `src/seon/error.cljs` |
| Malli instrumentation envelope | `seon.error.instrument` (`report-fn` throws ex-info whose ex-data IS the envelope; caught at the eval boundary and flattened by `->map`) | `src/seon/error/instrument.cljc` |
| Success marker | `seon.result` (`:seon.result/ok?` only) | `src/seon/result.cljs` |
| JVM writer logging | `taoensso.timbre` aliased `log` in `db/registry.clj`, `db/writer.clj`, `db/executor.clj`, `db/transport/uds.clj` (partly), `embed.clj`; libraries route through `taoensso.trove` (gated in `bin/test-writer:26-40`) | requires at `src/seon/db/writer.clj:36` etc. |

## The intended one-owner model

- **Pod**: `seon.log` is the sole logging boundary. Two sinks: structured
  console lines (`ISO ts  LEVEL [source] msg`, tee'd by the supervisor into
  `logs/pod.log`) and the rotated NDJSON-EDN `logs/pod-events.log`. Logs are
  deliberately NOT database rows (`log.cljs:23-29`). Raw `js/console.*`
  inside `seon.log` itself is the documented last resort when logging fails
  (`log.cljs:330,359`).
- **Errors**: everything crossing the agent loop is a `:seon/error`-shaped
  value; catch sites call `seon.error/record!`, which persists the datom and
  escalates `:core` faults via the ONE `:seon.config/on-core-error` dial.
  `record!`'s own `js/console.error` calls (`error.cljs:511,520,600,608` and
  the buffer-full warn at 307) are the loud markers/last-resort path the
  design requires — correct.
- **Warnings**: `seon.warn` derives everything from the db at render time.
  No warning datoms, no ack flags. Confirmed: the only
  `register!` hits matching warn/notif/ack are `seon.warn`'s own render-shape
  schemas, not stored entities.
- **JVM writer**: timbre for first-party logs, trove for library logs
  (datahike). Output lands in `logs/database-server.log` /
  `logs/wire-server.log` (supervisor tee) and `logs/lib*.log`.

## Call-site inventory (84 total hits; groups + verdicts)

### Correct — owning mechanism or documented last resort

- `src/seon/log.cljs` — the owner itself (console sinks, trove gate,
  failure fallbacks at 330/359).
- `src/seon/error.cljs:307,511,520,600,608` — buffer-full warn,
  `SEON-CORE-FAULT` marker, `:crash` exit notice, recursion-fence print,
  `record!`-itself-failed fallback. All by design.
- JVM `log/warn|error|info` via timbre: `db/registry.clj:782,1950,2002`,
  `db/writer.clj` (14 sites), `db/executor.clj:341`,
  `db/transport/uds.clj:547,813,831`, `embed.clj` (5 sites). Consistent
  first-party JVM convention.
- `bin/test-writer:26-40` — trove log-fn filter for expected datahike
  write-error noise. Legitimate test harness.
- Eval print capture references (`eval.cljs:96,348,472,4539`,
  `ctx.cljs:639`, `client.cljs:902`, `diffusion/retrieval.cljs:170`) —
  these are about capturing AGENT println output, not core logging.
- `src/seon/worker_eval.cljs:723-760`, `worker_validator.cljs:176,194` —
  child-process stdout IS the wire protocol (results printed to parent).
  Legitimate, not logging.
- `src/seon/embed/preflight.clj:195-203` — operator/dev CLI tool printing
  its verdict to stdout/stderr. Legitimate.
- `src/seon/db/server.clj:338-370` — boot banner `[database] ...` printlns to
  stdout. Legitimate operator-facing boot lines, though see divergence note.
- `src/seon/repl.cljs:31` — docstring example only, not a call.

### INCONSISTENT residue — should route through `seon.log` (or `seon.error/record!`)

The pod's biggest cluster is `seon.agent.loop`: 15 direct `js/console.error`
and 1 `js/console.warn` (`loop.cljs` lines 308, 739, 769, 784, 803, 808,
827, 843, 914, 972, 986, 990). These are runtime failure reports in the agent loop — exactly the
population `seon.log/error!` (with `:seon.log/agent`) or `seon.error/record!`
exists for. None land in `pod-events.log`, so `seon.log/tail` (the agent-
readable surface) never sees them.

Other pod residue, all bypassing `seon.log`:

| Site | Verdict |
|---|---|
| `src/seon/agent/turn.cljs:211,215,495,576` | route through `log/warn-console!`/`log/error!` |
| `src/seon/agent/run.cljs:591,618` | same |
| `src/seon/agent/schedule.cljs:493` | same |
| `src/seon/db.cljs:355,358` (`listen!` handler failures) | `log/warn!` — these are exactly tail-worthy events |
| `src/seon/eval.cljs:264,2989,3394,4773` | 3394 (`record-eval! tx FAILED`) is a dropped-record event that `check-record-errors` depends on being persisted — verify it also stamps `:seon.eval/record-error`; console line alone would violate "nothing caught without becoming data" |
| `src/seon/ai/typeahead.cljs:907,933`; `ai/diffusiongemma.cljs:487,491`; `ai/openai_compat.cljs:281` (`console.debug` — a sink `seon.log` doesn't even map) | route through `seon.log` |
| `src/my/plan/internal.cljs:1216` | route through `seon.log` |

Roughly 30 residue sites vs one owner. Format also diverges: residue lines
lack the ISO-timestamp/level/source shape, so `logs/pod.log` is a mix of
structured and bare lines.

## Throw / stored-warning findings

- **No stored warnings found.** All `:seon.warn/*` registrations are render
  shapes; checks derive from db facts and self-heal (`warn.cljs` throughout).
  `check-error-cluster` (`warn.cljs:1050`) degrades a throwing check into a
  derived cluster — correct.
- **Value→throw→value round-trips in the turn path**:
  `src/seon/agent/turn.cljs:622-627` and `931-933` take an already-formed
  `:seon.error/message` value and re-`throw` it as ex-info to reach the outer
  catch. Contained (the loop boundary catches and re-values it), but it is a
  smell against "nothing throws into the agent loop" — a `:seon/error` value
  should propagate as a value. Same pattern at
  `src/seon/agent/ctx/canvas.cljs:342-343` (inside its own try — contained).
- `src/seon/agent/ctx/transcript.cljs:349` throws on missing stored time
  from inside a render fn; render fns run under the guarded walker, so it is
  caught, but it converts a data defect into a throw rather than an omitted
  section — borderline.
- `src/seon/agent/fs.cljs:537` (`home-dir` throws when HOME unset) and
  `src/my/blob.cljs:349,370` throw from capability-adjacent code; verify
  callers catch-to-value (fs ops are documented never-throw).
- `seon.error.instrument/report-fn` throws by design (the instrumentation
  channel into the eval catch) — correct.

## Pod vs JVM divergence

- Pod: `seon.log` custom format + NDJSON events file. JVM: timbre default
  format to the supervisor-tee'd log; boot lines are bare `println
  "[database] …"`. Three formats (structured pod line, timbre line, bare
  bracket-prefix println) across one application.
- JVM has no equivalent of `pod-events.log` — no agent-readable structured
  event file for writer-side faults; writer errors are visible only by
  grepping `logs/database-server.log`.
- `logs/` hygiene: hundreds of stray per-run files (`pod-bench-*.log`,
  `pod-plan-*.log`, `pod-cal-*`, probe/repro logs, `kt2b-translate.out`) plus
  inspect-ai `*.eval` files that belong under `src-inspect-ai/` output dirs.
  The durable set is: `pod.log`, `pod-events.log(.N)`, `database-server.log`,
  `wire-server.log`, `cljs-watch.log`, `lib*.log`, `operator/`, `clusters/`,
  `agents/`, `acme/`.

### One convention (recommendation)

One line shape on both runtimes: `ISO-ts  LEVEL [source] message [edn]`
(the `seon.log/console!` shape). Pod emits it natively; JVM sets a timbre
output-fn (and a trove log-fn) producing the same shape, and boot printlns in
`db/server.clj` become timbre info lines. Structured event files stay pod-only
unless a writer-side consumer appears (do not add a second events mechanism
speculatively).

## Ordered fix plan

1. `seon.agent.loop` — replace the 16 direct `js/console.*` calls with
   `seon.log` (or `seon.error/record!` where the event is a fault). Largest
   single win; makes loop failures visible to `log/tail`.
2. Sweep the remaining pod residue (turn, run, schedule, db.cljs listen!,
   eval.cljs, ai/*, my/plan) onto `seon.log`; kill the lone `console.debug`.
3. Verify `eval.cljs:3394` also persists `:seon.eval/record-error` (the
   warn check's input); if console-only, that is a real data loss.
4. Replace the turn.cljs value→throw→value round-trips with plain value
   propagation (early-return of the error map).
5. JVM: one timbre output-fn matching the pod line shape; convert
   `db/server.clj` boot printlns to timbre.
6. `logs/` hygiene: point bench/probe runs at `tmp/`, move inspect `.eval`
   outputs under `src-inspect-ai/`, and prune the stray files (operator
   change, not source).
