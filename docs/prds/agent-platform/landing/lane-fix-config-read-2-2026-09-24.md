---
type: landing
status: landed in source; install pending RESET NEEDED; tests not yet run against the new code
lane: fix-config-read-2
created: 2026-09-24
follows: docs/prds/agent-platform/landing/lane-fix-config-read-2026-09-24.md
---

# Lane fix-config-read-2 — 2026-09-24

Oversight, `transcript/agent-history`, the transcript outline's calibration,
`render/request-profile`, `render/walk` and the web service no longer look up the
cluster name in a database row. Each one reads the name from the caller's context
environment (`seon.env/of` → `:seon.boot/cluster-name`), or takes it as an explicit
argument. Each calls `seon.config/effective` once with that name and passes its
typed refusal on.

## Broken first

- **No test has run against this code.** Hook publication is `:off`: default runs
  the committed archive `data/source/ce73846…`. So `bin/test-check default …` would
  run the old code, and none was run. **RESET NEEDED / root adoption.** After
  `start --head`, run:
  - `bin/test-check default --test seon.oversight-test/every-consumer-reads-the-cluster-its-caller-names`
    (this is the regression);
  - `--ns seon.oversight-test`, `--ns seon.render.transcript-test`,
    `--ns seon.render.web-test`, `--ns seon.render.web-debug-test`;
  - a render-tier sample: `seon.render.entity-pairs-test`, `seon.render-source-test`,
    `seon.render.page-settings-test`.
- **Behaviour change: `render/request-profile` now refuses** a request that carries a
  database but neither a profile nor a context whose environment names the cluster.
  - It returns `:seon.render/refused-member :seon.render/profile` and
    `:seon.error/member :seon.boot/cluster-name`.
  - Before, such a request got whichever cluster's config the `[:find ?c .]` guess
    picked, or the default profile when the database had no cluster row.
  - Every production caller I inspected already carries a ctx or a profile: web
    service, turn, sci eval, prompt and transcript. Tests that call render with only
    `:seon.db/db` would now go red; I did not enumerate them. The no-database path,
    projection only, still gets the default profile, as before.
- **The web service throws instead of returning a refusal.** Its pages have no
  refusal slot, so `cluster-effective!` throws the refusal whole as `ex-data`.
  - It throws when the service's ctx names no cluster, or when the named cluster has
    no config.
  - This is a panic-visible core fault (service misconfiguration), not an agent
    mistake.
- **`(agent-history {})` no longer works.**
  - `:seon.render.transcript/history-request` now **requires** `:seon.boot/cluster-name`,
    and there is no call-preparation supplier for it, so a zero-argument call is
    refused by its contract.
  - A supplier would take one initialization row in `config/default.edn` plus one
    contracted `seon.env` supplier function beside `supplied-agent-id`. Neither file
    is owned by this lane (see "Out of scope"). The only caller in the repo is
    `transcript_test.clj`, which now passes the name explicitly.

## Defect, re-confirmed live (default JVM, read-only)

The probe used default's database value with one extra `{:seon.cluster/name "decoy"}`
datom added through `d/db-with` (a throwaway value; nothing was written):

- the rows are `["decoy" "default"]`, and the old
  `[:find ?n . :where [_ :seon.cluster/name ?n]]` returned **`"decoy"`**;
- the instance ctx's `(env/of ctx)` names `"default"`.

## Change

- **`seon.oversight`**
  - `cluster-name` and `ping-timeout-ms` are deleted; `context-cluster-name` reads
    `(env/of ctx)`.
  - `fleet-value` now takes `[db cluster-name routing]` and returns one of:
    - the fleet;
    - `:seon.oversight/unavailable` — the routing members it lacks, or
      `[:seon.boot/cluster-name]` when no name is given;
    - the config refusal, unchanged.
  - `flow-status` keeps its `[db instance]` arity and reads the instance ctx's
    environment, so the `seon.cluster` caller did not change.
  - `unit` reads the request's `:seon.sci.eval/ctx`, which the web caller already
    passes.
  - `ai-story`, `html-table` and `unavailable-text` render the config refusal visibly
    as unavailable, never as an empty fleet.
  - Schema:
    - `:seon.oversight/request` declares an optional `:seon.sci.eval/ctx`;
    - the `:seon.oversight/unit` value admits the config refusals;
    - the `:missing` description is updated.
- **`seon.render.transcript`**
  - `agent-config` and its `config/defaults` fallback are deleted.
  - `agent-history` calls `config/effective` with the request's
    `:seon.boot/cluster-name` and returns the refusal unchanged. Its bounded body is
    now `agent-history-under`.
  - `outline-calibration` returns the calibration refusal instead of swapping in
    `tokens/shipped-calibration`. The shipped prior remains only for an agent with no
    recorded model.
  - The outline renders the refusal through `error/render-html`.
- **`seon.render`**
  - `request-profile` reads the name from the request ctx's environment.
  - `custody-cluster-name` is deleted. `walk` reads `*walk-context*`'s ctx
    environment and prints a config refusal instead of deriving a profile from it.
  - Added the `seon.env` require.
- **`seon.render.web`**
  - `current-cluster-name` now takes the service and reads its ctx's environment.
  - New `cluster-effective!` is the one `config/effective` call for `current-page`,
    the debug page and `/data`.
  - `ensure-namespace-owner!` names the new agent's cluster from the service.
  - Also edited, all one-token changes needed to pass `service`:
    - the three call sites;
    - the `:as service` destructuring;
    - the forward `declare`;
    - the `seon.env` require.

    These are lines outside the `:3121` range this lane owns; recorded here for the
    ledger.
- **Regression:** `seon.oversight-test/every-consumer-reads-the-cluster-its-caller-names`.
  - Setup, all through canonical writers: `named-a` and `named-b` are seeded with
    different `max-children`; `unconfigured` is a cluster row with no config; and
    the branch's own row is also present.
  - The named cluster answers in oversight (fleet), `request-profile` (3 vs 5) and
    `agent-history`.
  - `unconfigured` refuses with `:seon.config/missing-effective "unconfigured"`
    through all three.
  - The existing oversight tests now pass a ctx. A new case asserts that routing with
    no ctx is `{:seon.oversight/missing [:seon.boot/cluster-name]}`.

## Evidence

- **Live probe, default JVM.** The new `seon.oversight` source was loaded as the
  throwaway namespace `tmp.fix-config-read-2.oversight`. The new `request-profile`
  body was loaded the same way, as `tmp.fix-config-read-2.render`. Both namespaces
  were removed afterwards.
  - The probe ran against the decoy database value and the live instance routing.
  - Oversight:
    - `fleet-value … "default"` gives agents `["root"]`;
    - `flow-status db instance` gives `["root"]`;
    - a nil name gives `{:seon.oversight/missing [:seon.boot/cluster-name]}`;
    - `"decoy"` gives `{:seon.config/missing-effective "decoy", :seon.config/error-key :seon.config/cluster, :seon.error/operation seon.config/effective-in}`,
      and `ai-story` reads "Live fleet state is unavailable: Effective configuration
      requires a matching cluster row with every required dial."
  - `request-profile`:
    - named through the ctx, it gives `max-children 32`, the same as live;
    - with no ctx it gives `{:seon.error/member :seon.boot/cluster-name, :seon.render/refused-member :seon.render/profile}`;
    - with no database it gives the default profile, unchanged.
- **Timings: 20-call mean, warm, one JVM** (the old code is armed; the new tmp copies
  are not instrumented):

  | Operation | Old (ms) | New (ms) |
  |---|---|---|
  | `ping-timeout` config read | 0.711 | 0.536 |
  | `oversight/unit` | 2.261 | 2.191 |
  | `request-profile` | 0.696 | 0.626 |

  One query fewer each; no regression.
- **Contract compile.** `seon.contracts-compile-test/check`, the hook's checker, ran
  in the live JVM over the four source files against the live projection:
  `findings []`, 290 ms.
- **clj-kondo.** 0 errors across the four sources and two tests; the warnings were
  already there.
- **Snapshot load.** `git archive HEAD` plus this diff, with `reference-code` and
  `.cpcache` linked, run under `clojure -M:dev:test`:
  - all four namespaces and both test namespaces loaded in 65.2 s (cold compile),
    peak memory 3.78 GB;
  - `:seon.oversight/request`, `:seon.oversight/unit` and
    `:seon.render.transcript/history-request` compile against `packaged-forms`;
  - the new `agent-history` arglist resolves, and the regression Var exists;
  - the snapshot was removed afterwards.

## Size

Counts cover four source files, two schema EDN files and two test files.

| Area | Added | Removed | Net |
|---|---|---|---|
| Source (four files) | +134 | −99 | +35 |
| Schema EDN | +7 | −5 | +2 |
| Tests | ≈ +45 | | |

## Out of scope — needs other owners

1. **Call-preparation supplier for `:seon.boot/cluster-name`.** This needs
   `config/default.edn`, one initialization row, and `src/seon/env.clj`, a
   `supplied-cluster-name` shaped like `supplied-agent-id`. With both in place,
   `(agent-history {})` works again and other agent-facing readers can declare the
   name.
2. **`src/seon/sci/eval.clj` `database-effective-config` / `instrumentation-config`**
   still guesses the cluster and defaults `on-core-error` to `:record`. That belongs
   to another lane, as the prior landing note records.
3. **`transcript` ledger `::calibrations`** (`acquire-ledger-data`) still stores
   `agent-calibration` refusals per model. `ledger-turn-body` then reads them as
   calibrations. It was not converted here.

## Commits

See the `git log` entry "fix-config-read-2: oversight, agent-history, render profile
and web read the caller's named cluster".

Paths released after root reads this note:

- `src/seon/oversight.clj`
- `src/seon/render/transcript.clj`
- `src/seon/render.clj`
- `src/seon/render/web.clj`
- `resources/seon/schemas/seon.oversight.edn`
- `resources/seon/schemas/seon.render.transcript.edn`
- `test/seon/oversight_test.clj`
- `test/seon/render/transcript_test.clj`
