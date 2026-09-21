---
type: issue
status: resolved
severity: blocker
tags: [config, cluster, platform, mcp, render]
created: 2026-09-17
---

# The `default` cluster's effective configuration lost every required fact

## What is observed

On `default` (pid 33583, endpoint `http://127.0.0.1:7994`), at 2026-09-17
~02:5x UTC, `seon.config/effective` for cluster `"default"` refuses:

```
:seon.error/kind :seon.config/missing-effective
"Effective configuration for cluster \"default\" is missing required facts
 [:seon.config/on-core-error :seon.config.agent/turn-completion-backstop-ms
  :seon.config.agent/write-refusal-bound :seon.config.ai/chars-per-token-prior
  :seon.config.ai/endpoint :seon.config.ai/max-tokens] and 62 more."
```

`:seon.config/missing` lists **68 attributes** — effectively every declared
config fact, from `:seon.config.ai/*` through `:seon.config.flow/*`,
`:seon.config.fs/*`, `:seon.config.render.agent/*`, `:seon.print/length` and
`:seon.render.value/max-collection`.

## How it presents

1. **`mcp__seon__eval_clj` is unusable on `default`.** EVERY form returns that
   same refusal in ~1–3 ms without evaluating — the bridge resolves the
   cluster's result projection from the effective config before it evaluates,
   so a broken config makes the tool answer the config error instead of the
   form. `mcp__seon__runtime_status` reports the same value as the cluster's
   `:seon.dev.mcp/runtime`, while `:seon.dev.mcp/cluster-state` still says
   `"alive"`.
2. **Every render path that needs a profile refuses.** The agent debug page
   still returns 200, and the session panel renders a typed refusal —
   `seon.eval/of-agent refused agent-id at []: expected a string …, got nil` —
   rather than content.
3. `bin/seon status` reports the cluster alive with no drift. **Status does not
   see this**, which is the AGENTS §"absence of signal as health" class: a
   cluster whose every config fact is gone reports healthy.

## What is known about the cause

Not attributed. Evidence available at the time of filing:

- Earlier in the same session (~02:1x UTC) `mcp__seon__eval_clj` evaluated
  ordinary queries against `default` normally, so the facts were present then.
- Between those two points, three `bin/seon init --dev default --changed …`
  invocations ran and all three FAILED:
  - `data/operator/operations/init-lifecycle-43862.log` —
    `:seon.operator/lock-hold-timeout` after 180 s, during *development program
    reconciliation*, with the `current-src` publication already complete;
  - `data/operator/operations/init-init-58643.log` — `:seon.boot/refused`,
    "Source changed while incremental publication was being analyzed"
    (`digest-before 1f3120e1…`, `digest-after d30cc060…`);
  - `data/operator/operations/init-init-62187.log` — "Source changed while
    current-src was being analyzed; retry."
- Four to six concurrent lane gates were running throughout, and
  `config/default.edn` plus several `resources/seon/schemas/*.edn` are dirty in
  the shared tree.

A development adoption that reconciles config facts and is then interrupted by
a lock-hold timeout is the obvious suspect, but it is a HYPOTHESIS: nothing
here confirms the retraction happened there, and the lock timeout log should be
read before anyone acts on it.

## Why it matters beyond the outage

Two design questions this exposes, independent of the repair:

1. **A partial config reconciliation can leave the singleton empty.** If a
   development adoption retracts before it asserts, a timeout between the two
   leaves a cluster with no config at all. The reconciliation should be one
   transaction, or the cluster should refuse to run on a config it can see is
   empty rather than refusing every caller one at a time.
2. **`bin/seon status` reports healthy.** The status check asks whether the
   process is alive and whether source drifted; it never asks whether the
   cluster's own configuration resolves. A check that reports health when its
   subject is absent is worse than nothing (AGENTS §1).

## Repair

Not a lane's call: a lane never resets, reforks, or restarts `default`. The
orchestrator's options are `bin/seon config apply default config/default.edn`
(if the facts are simply absent and nothing else drifted) or the ordinary
reset/refork, batching the schema changes other lanes have pending.

## Verification boundary of this note

Observed through `mcp__seon__runtime_status`, `mcp__seon__eval_clj`,
`bin/seon status`, and `curl` against `http://127.0.0.1:7994`. No write, no
repair attempt, and no reset was performed.

## Root cause (2026-09-17 03:40Z, orchestrator, live probes `tmp/orchestrator/config-loss-probe-{1..6}.edn`)

No fact was ever lost. The config entity holds all 79 attributes, asserted in
one transaction (536870922) and never retracted (history count 79 = current
count 79). Every read failed instead:

1. An uncommitted `src/seon/db.clj` hunk (`total-pull-arguments`, the
   small-fixes lane's pull-cap work) was hot-reloaded into the JVM by the edit
   hook in an intermediate form that returned a seq. `append-pull-evidence!`
   (db.clj:719) does `(assoc arguments 0 selector)`, which throws
   `ClassCastException: Cons cannot be cast to Associative` — inside
   `pull-call`'s catch, so EVERY `seon.db/pull` returned a flat
   `:seon.db/invalid-read` value instead of a row.
2. `seon.config/effective-in` (config.clj:579) bound that error map as `row`
   and computed `missing` from its keys — 68 "missing" facts; absence read
   as health at a reader.
3. `seon.config/population-transaction-data` (config.clj:452) read the same
   error as "no such entity", minted a tempid for an existing identity, and
   Datahike refused the conflicting upsert — the `config apply` refusal.
4. The on-disk hunk already carried the vector fix (its docstring names this
   exact throw), but adoption was refused tree-wide by the S3 lane's seam, so
   the JVM never received it.

Repair: the on-disk definition was evaluated into the JVM by hand
(`seon.db` namespace, MCP jvm mode); `seon.db/pull` answers 80 keys,
`seon.config/effective` answers 77 dials, `bin/seon config apply` converged
(4 operations). No restart, no reset.

## What remains open (routed)

- `effective-in` and `population-transaction-data` must return the pull's
  error value instead of reading it as a row — two guard clauses and one
  regression (queued as an Opus fix, spec in the plan).
- The class: an intermediate hot-reload from a lane's in-tree edit while
  adoption is refused leaves the JVM on a broken definition with no signal.
  The hook's post-edit adoption is the seam that should have re-applied the
  fixed bytes; when adoption refuses, the JVM silently keeps whatever loaded.
