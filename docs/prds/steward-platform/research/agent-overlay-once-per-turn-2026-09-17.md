---
type: research
status: complete
created: 2026-09-17
tags: [research, steward, turn-loop, agent-overlay, prompt]
---

# `seon.ai/agent-overlay` read 5×, not 3×, per turn — root cause and fix

2026-09-17. Verified live on `default` (pid 88182), in-process, via
`seon.test/run`/`seon.test/check` on a daemon thread per the REPL-driven
rule; no test JVM was launched.

## Finding as handed off

`seon.turn-loop-test/prompt-and-call-resolve-once-record-settings-and-see-next-turn-config`
asserts `(= 3 (count @overlays))` with the message "profile, prompt and
call each read the overlay; failover reuses it". With the redef installed,
the measured count was 5 in both phases of the test (before-apply and
after-apply). The handed-off hypothesis was that the failover/backup
attempt re-reads the overlay at call time instead of reusing the value the
primary attempt resolved.

## What the failover hypothesis got wrong

Instrumenting `seon.ai/agent-overlay` with `alter-var-root` and capturing
`(.getStackTrace (Thread/currentThread))` on every call (live, in the
`default` JVM) shows the failover/backoff loop in `call-turn`
(`src/seon/turn.clj:4353-4407`) does **not** re-derive settings on
failover: `provider-targets` (`src/seon/turn.clj:3843-3857`) is called
exactly once per `call-turn` invocation, before the attempt loop, and its
`settings` value is reused by both the primary and the backup attempt
(the existing assertion at `test/seon/turn_loop_test.clj:866-872`, that
both attempt rows carry `first-settings`, already proved this). The
failover path is not where the extra reads come from.

## The five actual call sites, traced live

With the stack-trace instrumentation installed and one full run of the
test captured, filtering out the frames belonging to the test's own
opening (`seon.turn/system-turn`, `turn.clj:2174-2178`, which reduces over
every changed-read form outside the test's `with-redefs` window and is not
part of what `@overlays` counts), the 5 reads inside one `turn/turn`
`:call` dispatch are:

1. `seon.turn$turn$pass__2046885.invoke(turn.clj:4929)` — the dispatcher's
   own merge of the overlay into `cluster` before routing to
   `open-turn`/`call-turn`/`generate-turn`/`resume-turn`/`close-turn`.
2. `seon.turn$provider_targets.invokeStatic(turn.clj:3849)` — the "call"
   read: resolves `:seon.ai/primary`/`:seon.ai/backup`/`:seon.ai/settings`
   once for the whole attempt loop.
3. `seon.cluster.prompt$effective_ai_settings.invokeStatic(prompt.clj:56)`
   — the "prompt" read: `cluster.prompt/prompt` resolves the agent's
   effective AI settings once, to size the budget and pick the
   calibration model.
4. `seon.cluster.prompt$acquire_context_report.invokeStatic(prompt.clj:199)`
   — `acquire-context-report` (called from inside `prompt`) calling
   `seon.repl/frame` directly, which itself calls `ai/agent-overlay`
   **again** at `src/seon/repl.clj:44` (now `:44`, the direct
   `overrides` binding) to compute the turn-budget maximum.
5. `seon.turn$max_episode_runs.invokeStatic(turn.clj:2736)` — reached from
   the *same* `repl/frame` call, one frame deeper: `frame` also calls
   `seon.turn/turns-left` (`turn.clj:41`, now removed), which internally
   calls the private `max-episode-runs` (`turn.clj:2733-2736`), which
   reads `ai/agent-overlay` a **third** time for the identical
   `:seon.config.run/max-episode-runs` dial `frame` had just read at site 4.

So sites 1–3 are the ruled "profile, prompt, call" reads. Sites 4 and 5
are both inside one call to `seon.repl/frame` — it read the same overlay
map twice to print "turns left: R of M": once directly (for `M`) and once
indirectly through `turns-left`→`max-episode-runs` (for `R`). Neither read
has anything to do with failover; both fire on every `call-turn` pass that
builds a prompt, success or failure, because `prompt.clj`'s
`acquire-context-report` always appends the budget frame via `repl/frame`.

## The fix

- `src/seon/repl.clj` — `frame` now takes an optional third argument
  (`overrides`, the agent's already-resolved overlay) and, when supplied,
  neither calls `ai/agent-overlay` nor `seon.turn/turns-left` again: it
  derives `remaining` from the public `seon.turn/episode-runs` (which does
  not touch the overlay) against the already-known maximum. The
  two-argument arity kept for `render.clj`/`transcript.clj` (which have no
  settings on hand) now performs exactly one overlay read instead of two,
  because it also stopped going through `turns-left`.
- `src/seon/cluster/prompt.clj` — `prompt` already resolves `settings`
  once (`effective-ai-settings`, site 3 above); `acquire-context-report`
  now takes that `settings` map as a fifth argument and hands it straight
  to `repl/frame`, so the turn-budget frame reuses the SAME resolution
  instead of re-deriving it. `prompt`'s only caller of
  `acquire-context-report` was updated to pass `settings` through; it is
  a private function with no other call sites (`grep -rn
  acquire-context-report src/ test/`).

Net effect: the two duplicate reads inside `repl/frame` are eliminated
entirely for the path `prompt` → `acquire-context-report` → `frame`
exercises; the other two callers of `frame` each drop from two reads to
one.

## Before / after, measured in-process

Both measured via `seon.test/run` on
`seon.turn-loop-test/prompt-and-call-resolve-once-record-settings-and-see-next-turn-config`,
same live JVM, same fixture base:

| | before | after |
|---|---|---|
| overlay reads, phase 1 (`settings-run-1`, one failover) | 5 | 3 |
| overlay reads, phase 2 (`settings-run-2`, no failover) | 5 | 3 |
| test result | 2 failures (`(not (= 3 5))`) | 16→18 assertions pass, 0 fail, 0 error |

## New regression

`test/seon/turn_loop_test.clj` — the existing test's `ai/agent-overlay`
redef now also captures the returned VALUE into `overlay-values` (a
sibling atom to the existing `overlays` call-count atom). Both phases now
additionally assert `(apply = @overlay-values)`: the overlay's exact
returned map is identical across every read within one turn, which is
what makes "the failover attempt reuses it" (and, after this fix, "the
turn-budget frame reuses it too") a provable equality rather than an
absence of evidence.

## Verified in-process on `default` (pid 88182), no test JVM

- `seon.turn-loop-test/prompt-and-call-resolve-once-record-settings-and-see-next-turn-config`
  — 18 assertions pass, 0 fail, 0 error (was 2 failures before the fix).
- `seon.cluster.prompt-test` — every test reaching `prompt`/
  `acquire-context-report` run individually: `basis-only-transactions-do-not-append-history`,
  `calibration-uses-the-agents-recent-attempts-and-config-prior`,
  `identical-context-does-not-depend-on-a-retained-prompt-cache`,
  `later-evaluations-preserve-the-opening-history`,
  `prompt-budget-is-informational-and-does-not-compact`,
  `prompt-prices-the-exact-retained-history`,
  `provider-calibration-reports-over-budget-without-refusing`,
  `unobserved-messages-do-not-rewrite-stored-history` — all pass, 0 fail,
  0 error.
- `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments`
  (reaches `repl/frame` through the debug page) — 98 assertions pass, 0
  fail, 0 error.
- `seon.ai-test/agent-overlay-reads-only-derived-per-agent-attributes` — 2
  assertions pass, 0 fail, 0 error (confirms `ai/agent-overlay` itself is
  untouched).
- `seon.test/check` with `:seon.test/changed
  [seon.repl/frame seon.cluster.prompt/acquire-context-report
  seon.cluster.prompt/prompt]` started the correct reach (turn-loop-test,
  prompt-test, render/web-debug-test) but expired against the shared
  JVM's declared `:seon.test/check-time-limit-ms` (120000 ms) under
  concurrent lane load; the individual `seon.test/run` calls above cover
  every test it had selected and not yet reached.

## Files touched

- `src/seon/repl.clj:39-64` — `frame` gains a three-argument arity.
- `src/seon/cluster/prompt.clj:188-238` — `acquire-context-report` and
  `prompt` thread `settings` through to `repl/frame`.
- `test/seon/turn_loop_test.clj:780-781,837-870,903-919` — captures
  overlay values and asserts equality across the reads in one turn.

## Verification boundary

Adoption confirmed by resolution (`(:arglists (meta (find-var ...)))`
returning the new arities), not by a piped exit code — the automatic hook
publication for these edits hit "Source changed while current-src was
being analyzed; retry" once under concurrent lane load, and an explicit
`bin/seon init --dev default --changed <path>` for both files converged
on the next attempt. `src/seon/turn.clj` was read only, never edited — the
extra reads were never in the failover/backoff loop it protects.
