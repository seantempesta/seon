---
type: research
status: active
tags: [research, runtime, architecture]
---

# Poll / timeout census — 2026-07-23

Read-only census of every poll loop, sleep loop, retry-with-delay, wall-clock
timeout, and periodic ticker in first-party code (`src/`, `script/`, `bin/`,
`pod-host/`; `reference-code/` and `node_modules` excluded). Grounded in R42
(detect, don't guess: only stall breakers over observed progress, or R27
circuit breakers >=100x measured P99.9, both config facts, loud) and R27 (no
numeric limit literals in runtime code), the paged-boot 120s kill evidence,
and the claim/lease design in `docs/seon/architecture/agent-runtime.md`.

Every row marked VERIFIED was read in source at the cited line. Rows marked
SUSPECTED were located by grep but their control flow was not fully traced.

`pod-host/` (libdatahike-cljs, wasm-tauri) contains no timer/sleep sites.

## Ranked findings — worst first (guessed totals in runtime paths)

Rank = blast radius x staleness risk (does the number go stale as the corpus
or system grows — the exact way the 120s pod-readiness literal went stale).

| # | Site | What it bounds | Mechanism today | Class | Proposed replacement (existing Seon mechanism) |
|---|------|----------------|-----------------|-------|-----------------------------------------------|
| 1 | `src/seon/db/host.clj:15-22` (used :610,:675,:714,:760,:903) VERIFIED | Every JVM host database call (`::call-deadline-ms` 120000), pool acquisition (`::pool-wait-timeout-ms` 110000), interest calls (`::interest-call-timeout-ms` 120000) | Literal defaults map, `"Hardware-derived defaults"` docstring; recovery loop re-polls under the same 120s budget | GUESSED TOTAL + R27 literal | The response frame IS the event; the writer executor already reports progress per request. Convert to a stall breaker over session activity (frames/heartbeats still arriving on the member session) with the ceiling an aero config fact. This is the exact class that killed the healthy paged boot: any legitimately slower db operation (corpus growth, paging) dies at 120s. |
| 2 | `src/seon/db/session.cljs:540` (literal `(call! request 120000)`) and `:505` (`call!` default `15000`) VERIFIED | Every client-tier replica protocol call, including boot-time paged initialization acquisition | Inline literals | GUESSED TOTAL + R27 literal | Same as #1 on the CLJS side: the UDS session already tracks per-request deadlines and pending counts (`uds.cljs` session map); bound SILENCE on the session (no frame observed), not the call total. Config fact. Staleness risk maximal — paged init grows with the corpus and rides these calls. |
| 3 | `script/seon/dev/process.clj:386` (watcher ready-timeout 300000), `:596` (host 180000), `:673` (writer 180000), web-render inherits host's (`:631`) VERIFIED | Operator readiness for watcher / cluster JVM / writer / web-render boot | Literal total-duration `:seon.dev.process/ready-timeout-ms`; only the POD spec (`:515-519`) was converted to the stall shape (`pod-boot-stall-timeout-ms` config fact) | GUESSED TOTAL (the direct siblings of the fixed pod bug) | Apply the same conversion the predfix lane made for the pod: `wait-until-ready!` (`process.clj:942-967`) already supports `ready-stall-timeout-ms` + progress observations; each of these processes has an observable progress signal (watcher flush log lines, writer port-file/advertisement, host readiness log advance). Config facts per process. |
| 4 | `src/seon/config.cljs:1008-1017` `llm-attempt-timeout-ms` (env `SEON_LLM_ATTEMPT_TIMEOUT_MS`, default 120000) VERIFIED | One LLM adapter attempt, raced by `seon.agent.turn/call-llm!` | Env-read numeric default, not a database config fact | GUESSED TOTAL | A streaming LLM attempt emits chunk events — the presentation sink already consumes them. Bound silence between stream chunks (stall breaker), not attempt total: a legitimate long generation (large reply, thinking models) exceeds 2 min while still streaming. Also migrate env → config singleton per the config-through-DB rule. |
| 5 | `src/seon/execution/host.cljs:32` `ensure-host-timeout-ms` 240000 (used `:920`) VERIFIED | `bin/seon ensure host` subprocess (build + boot work) | Private literal, not configurable at all | GUESSED TOTAL + R27 literal | Identical class to the pod-boot kill: ensure does legitimately slower work as artifacts grow. The subprocess emits output (`on-out`/`on-err` tails already captured) — stall breaker over output advance; ceiling a config fact. |
| 6 | `src/seon/execution/host.cljs:30` `default-ready-timeout-ms` 10000 (armed `:536-544`, `:606-617`) VERIFIED | Bun execution child / JVM host session startup before the ready message | Config-overridable (`::ready-timeout-ms` schema'd) but literal default; total-duration shape | GUESSED TOTAL | The child emits a `:seon.execution.message/ready` event and stdout/stderr tails are already captured — bound silence on child output, not total startup. Child startup will slow as the SCI corpus reconstruction grows (same growth vector as the pod boot). |
| 7 | `src/seon/execution.cljs:25` `maximum-invocation-ms` (10 min; consumed `:581`, `:1303`, and `execution/host.cljs:1298-1300`) VERIFIED | One execution-protocol invocation end to end | Public literal; docstring self-declares "contract owner for the W1 aero-to-fact relocation" | GUESSED TOTAL, acknowledged (W1 queued) | The guarded door already runs inside it with real R27 config facts (`:seon.config.guard/deadline-ms`, fuel) — the outer 10-min literal duplicates that authority. W1 should make the guard facts the one owner and demote this to an R27 breaker config fact >=100x measured invocations. |
| 8 | `src/seon/config.cljs:392-453` packages family: `install-deadline-ms` 120000, host `call-deadline-ms` 120000, host `ready-timeout-ms` 30000, `swap-queue-deadline-ms` 5000, `respawn-backoff-ms` 1000 VERIFIED | Package install (npm/maven can legitimately exceed 2 min), package-host calls and boot | Config facts (good) with in-code numeric defaults; total-duration shape | GUESSED TOTAL (fact-shaped, wrong condition) | Package installs stream installer output; the package host follows the seon.db wire pattern (frames = progress). Re-rule each condition to stall-over-output/frames, keep the aero plumbing. Install deadline is the most staleness-prone (dependency trees grow). |
| 9 | `src/seon/eval.cljs:97` `default-timeout-ms` 10000 (deadline race at `:218-235`) VERIFIED | One agent-eval'd form when no per-form budget is set | Private literal; comment self-declares the future is a frozen-database config default | GUESSED TOTAL + R27 literal | Interim self-host engine (dies at the great deletion — do not over-invest), but until then: the form's budget should come from the same guard config facts the JVM door uses; 10s on a form that awaits a legitimate LLM/web call is a guess. |
| 10 | `src/seon/host.clj:56` `startup-read-timeout-ms` 10000 VERIFIED | Reading the startup frame on a newly accepted host session | Literal; docstring: "W1 moves it to a config fact" | Borderline — bounds silence on an accepted connection (stall-shaped) but literal | Keep the stall shape; make it the W1 config fact. |
| 11 | `src/seon/config.cljs:1019-1029` `turn-timeout-ms` (env, default 900000) VERIFIED | Any single await in `run-loop!` (inner bound; run deadline is the outer bound) | Env-read numeric default | Breaker-shaped (calibrated "comfortably above the worst-case retry ladder") but env-sourced and not >=100x-documented | Migrate env → config fact with calibration provenance per R27; condition is acceptable as a hung-step breaker if each awaited step's own progress signal (LLM chunks, db frames) gains stall detection first (#4). |
| 12 | `src/seon/db/server.clj:583` `(deref started (* 5 60 1000) ::start-timed-out)` in the shutdown hook VERIFIED | start! completion when TERM arrives during startup | Inline literal | GUESSED TOTAL + R27 literal (low blast: shutdown edge) | Startup progress is observable (log advance); at minimum a config fact. |
| 13 | `src/seon/agent/web/pod.cljs:22` `default-timeout-ms` 30000 (+ `:506`, `:681`, `:777` AbortController arms), JVM twin `src/seon/agent/web/host.clj` HttpTimeoutException sites VERIFIED | Outbound third-party HTTP fetch / search | Literal default; per-call `:seon.agent.web/timeout-ms` override exists; error message steers to the override | External-boundary breaker (acceptable condition — no progress signal from a foreign server before first bytes) but R27 literal default | Make the default a config fact. Streamed-body reads could bound inter-chunk silence instead of total. |
| 14 | `script/seon/dev/mcp.clj:869` io-prepl deadline, `:1210` `runtime-reconnect-window-ms`, `:62` retry interval 200, `:975` bridge poll 150 VERIFIED (values), SUSPECTED (full flow) | MCP tool eval totals and reconnect windows | Literals in dev tooling | GUESSED TOTAL (dev tooling — caller-visible timeout args are a tool contract, lower severity) | Eval timeouts are per-request tool args (fine); the async bridge is a poll — see poll section. |

## Polls where a push exists

| Site | Polls for | Cadence | Existing push mechanism that replaces it |
|------|-----------|---------|------------------------------------------|
| `script/seon/dev/mcp.clj:1532` parent-liveness loop VERIFIED | Parent process death (orphan prevention) | `Thread/sleep 5000` forever | `java.lang.ProcessHandle.onExit()` returns a CompletableFuture — a pure push; the loop dissolves into one `.thenRun`. Cheapest concrete win in the census. |
| `script/seon/dev/changed_test.clj:474-481` `await-process-absence` VERIFIED | Process-tree death | 10ms poll to a deadline | Same: `ProcessHandle.onExit()` per handle, then `CompletableFuture.allOf`. (`:71-75` start-instant poll and `:453-460` tree-stabilization poll are genuine OS-state races with no event; acceptable, bounded.) |
| `src/seon/db/host.clj:698-724` `sleep-before-recovery-poll!` (10ms backoff under the 120s budget) VERIFIED | Writer finishing an in-flight conflicting request / release | Poll-with-backoff | The writer owns the committed-transaction feed and the interest session on this very connection — completion of the conflicting request is observable as an event rather than re-asked. |
| `script/seon/dev/branch.clj:198-217` release-drain (10ms poll, 5s literal total) VERIFIED | Writer releasing a database-in-use | Poll + literal total | Writer-side event on release; dev tooling, small blast. Total is a guessed literal (R27 flag). |
| `src/seon/db/transport/uds.cljs:25,:645` `deadline-tick-ms` 250 `setInterval expire-deadlines!` VERIFIED | Per-request deadline expiry scan | Fixed ticker | Arm ONE timer for the earliest pending deadline (re-arm on change) — no periodic scan. Minor cost today; literal cadence is an R27 flag. Whether these per-request deadlines survive at all follows rows #1/#2. |
| `script/seon/dev/mcp.clj:1035-1049` async-bridge poll (150ms) VERIFIED | A pod Promise settling, observed through globalThis | Poll via repeated nREPL evals | nREPL is request/response, so a true push needs the eval to park server-side; acceptable dev-tooling compromise — record as known, bounded. |
| `script/seon/dev/process.clj:966,:1009` readiness probes VERIFIED | Log advance / port files / HTTP readiness of supervised processes | 200ms poll under (now) stall or (still) total deadlines | Polling an external process boundary is tolerable; the R42-critical part is the CONDITION (stall vs total — rows above). File-watch (WatchService) on logs/port files would make it push if it ever matters. |
| `bin/test-cljs:67,:179,:190`, `bin/test-writer:64` shell `kill -0` loops VERIFIED | Lock-owner liveness, child shutdown | 0.1-2s shell sleeps | Shell `wait` covers own children; polling non-child pids from sh has no portable push. Dev tooling, bounded, acceptable. |
| `src/seon/db/writer.clj:3317-3333` `run-readiness!` requeue + `Thread/yield` SUSPECTED | A ready committed-report source whose runtime is not (yet) registered | Requeue then `Thread/yield` — a busy-yield spin whenever a ready source has no matching runtime | If `take-ready!` blocks (queue capacity `committed-report-capacity` 256 at `:2784` suggests a bounded queue), the normal path is event-driven; the requeue/yield branch can hot-spin during registration races. Needs a trace: park on the registration change instead of yielding. Flagged, not confirmed. |

## Heartbeats / leases / tickers (protocol liveness)

| Site | Verdict |
|------|---------|
| Claim lease heartbeat (`:seon.agent.run/last-beat-at`, epoch CAS fences; `agent-runtime.md`) VERIFIED (design + watchdog consumer) | LEGITIMATE by design: liveness is committed database facts; expiry judged by the observer's lease policy, takeover fenced by heartbeat+epoch CAS. Not a smell. |
| Watchdog `src/seon/agent/run.cljs:988-1010` + `config.cljs:1046-1056` `watchdog-stale-ms` (config fact, default 1200000) VERIFIED | LEGITIMATE STALL BREAKER: bounds SILENCE (heartbeat freshness), stateless scan derived from datoms (restart-safe), firing is loud (`:crashed` close + `:core` fault into triage). The R42 model citizen. |
| The ONE ticker `src/seon/agent/loop.cljs:646-691` (`default-tick-ms` 30000, `SEON_TICK_MS`/`config/tick-ms` override) VERIFIED | Deliberate stateless-scan backstop (claimable-work offer, due schedules, watchdog pass) beside the push path (`install-wake-trigger!`). Acceptable as the backstop cadence; note: override is an ENV read (`config.cljs:990-998`), not a database fact — config-through-DB drift. Claim-offer scanning is a poll-where-push-exists candidate (committed-feed interest on run facts) once the claim-native driver era settles it. |
| SSE proxy heartbeat `src/seon/web/datastar.cljs:338` (15000 literal) VERIFIED | Legitimate protocol keep-alive (inert SSE comment for proxies). R27 flag: literal cadence, should be a config fact. |
| Bun keep-alive `src/seon/client.cljs:548-557` (60000 literal, debug log) VERIFIED | Self-described placeholder holding the event loop open. Harmless; literal; dies naturally when cluster-host work owns the loop. |
| Partial-publish sink `src/seon/agent/turn/llm.cljc:44-96` (`settle-ms` from `:seon.config.model-stream/partial-publish-settle-ms` context fact, `:307,:464`) VERIFIED | LEGITIMATE: coalescing cadence as a config fact, isolated non-blocking sink — exactly the documented streaming posture. |
| Reactive settle timers `src/seon/reactive.cljs:73-81` VERIFIED | LEGITIMATE: timing policy installed from database-acquired config (`configure!`, `:seon.config/reactive*` facts; defaults `config.cljs:277-279`). |

## LEGITIMATE — verified sound, do not re-audit

- `src/seon/db/executor.clj:324-332` `take-work!`: lock `.wait`/notify blocking take, no timeout — pure event-driven. Worker threads (`:512-521`) just loop on it.
- `src/seon/host/invoke.clj:36-60,:120-133` guarded eval door: fuel + deadline from `:seon.config.guard/*` config facts, error names its governing config key — R27-compliant breaker. (It also `min`s against row #7's literal — fixing #7 leaves the guard facts as the one owner.)
- `script/seon/dev/config.clj:75-92` `pod-boot-stall-timeout-ms`: required config fact, progress-observation reset, calibrated against the measured 257s paged boot, loud missing-key failure — the completed R42 conversion (predfix lane).
- `src/seon/subprocess.cljs:184-191` SIGTERM→SIGKILL `kill-grace-ms` and `src/seon/execution/host.cljs` `cancel-grace-ms`: bounded grace after an explicit stop request = bounding silence after a command, correct shape (literal defaults are R27 flags only).
- `setTimeout 0` sites (`uds.cljs:339,:379,:761` close-notify, event-delivery re-arm; `execution.cljs:1488` exit flush; `loop.cljs:249` wake renew): microtask/event-loop scheduling, not waits — out of scope.
- `src/seon/db/transport/uds.cljc:1500-1512` selector worker: blocking `.select` — event-driven. `default-shutdown-timeout-ms` 5000 (`:182`) is a shutdown-drain grace (correct shape, literal default).
- `src/seon/web/feed.clj:110-140` per-connection vthread drain + `ArrayBlockingQueue` latest-wins mailbox (depth from `::mailbox-depth` config): event-driven.
- `src/seon/retry.cljc`: the retry MECHANISM (policy combinators + interruptible sleep) — infrastructure, callers own the strategy; `max-duration`/`max-retries` are caller-supplied breakers. Sound.
- `src/seon/embed.clj:695-722` Gemini retry backoff (`embed-base-backoff-ms` const): external-API retry with bounded attempts, interrupt-aware — correct condition; literal base is an R27 flag.
- `src/seon/diffusion/gemma.cljs:183-189,:506-511` RunPod job-status poll (3s remote / 250ms local, x200): external HTTP API with no push channel; cold-start deliberately inside the poll budget, documented. Frozen/opt-in subsystem; dynamic-var literals noted, low priority.
- `src/seon/test/runner.cljs:385-399` `with-test-timeout` + `config/test-timeout-ms`: test-harness bound on a possibly-stuck drive — test infra, required.
- `src/seon/web/serve.cljs:744-834` agent-task settlement: the timer RACES a real db-committing listener, and the bound derives from the database run policy (no second literal owner) — event-driven with a policy ceiling. Its ceiling inherits whatever shape the run policy has (#11).
- `src/seon/agent/web/host.clj`, `src/seon/ai/http.clj`: JVM HTTP timeouts surface as errors-as-values with interrupt handling; bounds are the caller-passed/adapter facts (see #4/#13 for the sources).
- `src/seon/host.clj:233,:318`, `src/seon/db/server.clj:581-610`, `src/seon/agent/shell/leaf.clj`, `src/seon/db/writer.clj:4760-4766` (deadline executor thread factory): thread/vthread spawns and blocking joins, not timers.

## R27 literal flags not already covered above

- `src/seon/warn.cljc:562` `slow-eval-threshold-ms` 500 — a warning threshold, not a wait; still a runtime numeric literal.
- `src/seon/execution/host.cljs:31` `default-idle-timeout-ms` 300000 — idle-stop POLICY timer (legitimate purpose, config-overridable; the park/idle policy itself is the recorded U7 seam).
- `script/seon/dev/process.clj:2330-2333` drain-deadline `+ 10000` pad on the containment grace — literal pad.
- `bin/test-cljs`/`bin/test-writer` shell literals — dev tooling, bounded.

## Honest counts

- Sites examined in source (VERIFIED): 46 distinct sites across 24 files.
- SUSPECTED (grep-located, flow not fully traced): 2 — `writer.clj run-readiness!` yield-spin branch; full mcp.clj reconnect flow.
- GUESSED TOTALS in runtime paths (rows 1-13): 13 clusters, of which 5 are pure literals with no config override (rows 1, 2, 5, 9, 12), 3 are env-sourced numbers bypassing the database config authority (rows 4, 11, and `tick-ms`), and 2 are self-acknowledged W1 debts (rows 7, 10).
- Polls with an existing push replacement: 4 concrete (mcp parent-liveness, changed_test absence, db/host recovery poll, branch release-drain); 3 acceptable-as-is with the replacement named; 1 suspected busy-spin.
- Heartbeats/leases verified legitimate: 4 (claim lease, watchdog, partial-publish sink, reactive settle); 2 with literal-cadence R27 flags (datastar SSE, client keep-alive).
- Fully R42-compliant exemplars to copy: `pod-boot-stall-timeout-ms` (stall breaker) and `watchdog-stale-ms` (silence bound, loud, config fact).

## Root-cause synthesis

One class, many instances: a wall-clock total guessed at write time and left
as a literal (or an env default), bounding work whose duration grows with the
system (corpus size, package trees, artifact size, LLM generation length).
The codebase already owns every signal needed for detection — committed-tx
feed and attribute-indexed interest, protocol frames as progress, child
stdout/stderr tails, ready messages, ProcessHandle exit futures, heartbeat
facts. The fix pattern is proven twice in-tree (pod boot stall breaker;
watchdog stale-ms) and should be applied family-by-family, worst first:
database call deadlines (#1/#2), operator readiness siblings (#3), execution
child/ensure startup (#5/#6), LLM attempt streaming (#4).

The census root cause is already ruled (R42, program anchor 2026-07-23 eve);
no new issue note is required beyond this report. The one NEW suspected
defect worth its own trace before filing: the `run-readiness!` requeue/yield
busy-spin branch (`src/seon/db/writer.clj:3317-3333`).
