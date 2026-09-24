---
type: landing
status: partial — prompt and transcript calibration landed; oversight and agent-history need unowned schema files; install pending RESET NEEDED
lane: fix-config-read
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/cross-cutting-astra-2026-09-24.md
---

# Lane fix-config-read — 2026-09-24

The prompt and the transcript's calibration now read the cluster name from the
environment carried by the caller's context (`seon.env/of`, `:seon.boot/cluster-name`).
They call `seon.config/effective` once with that name and pass on its typed refusal.
Oversight and `transcript/agent-history` are **not converted**: each needs a schema
resource this lane does not own (see "Not done").

## Broken first

- **Only part of row 5 landed.**
  - `src/seon/render/transcript.clj` `agent-config`, the `config/defaults` fallback cited
    at `:855-867`, is still in place.
  - `src/seon/oversight.clj` `cluster-name` / `ping-timeout-ms` (`:38-51`) still guesses
    the cluster from a database row.
  - So the regression "missing config refuses the same way in all three" covers only
    the prompt and the transcript calibration path.
- **No test was run against the new code.** `.claude/seon-hook.edn` sets
  `:current-source {:enabled false}`, so edits do not reach default. The one
  `bin/test-check default --ns seon.cluster.prompt-test` run used the old code: the JVM
  still showed `agent-calibration` arglists `([database agent-id model])` and no
  regression Var. Treat that run as the **before** baseline:
  - `executed 11 / reused 3 / pass 93 / fail 15 / error 2 / 120768 ms`;
  - nearly every red is a duration failure of 9–13 s against the 5 s bound (the machine
    was heavily loaded);
  - `provider-calibration-reports-over-budget-without-refusing` fails its assertions
    (3.2 vs 3.597, sample 3 vs 10);
  - one test has a `capture-mismatch` return-contract error at `prompt.clj:406`.

  None of these is attributable to this diff. **RESET NEEDED / root adoption:** after
  `start --head`, run
  `bin/test-check default --test seon.cluster.prompt-test/the-prompt-reads-the-cluster-its-caller-names`,
  then `--ns seon.cluster.prompt-test`, `--ns seon.render.transcript-test` and
  `--ns seon.render.web-debug-test`. `web_debug_test.clj:828` wraps `agent-calibration`
  with `(last args)`, and the model is still the last argument.

## Defect, confirmed live

A forked cluster's branch holds its parent's rows as well as its own. `record-fork!`
(`src/seon/cluster/registry.clj:240-262`) adds a second `:seon.cluster/name` and
`:seon.config/cluster` row.

On `:cluster-running-fixture`, both queries return `["running-fixture" "default"]`. The
`[:find ?c .]` scalar that prompt, transcript and oversight each used therefore picks
one of two rows, and nothing makes that choice deterministic. The carried environment
names the cluster exactly: `(env/of ctx)` gives `"default"` for default's context, and
branch contexts inherit the handle's environment (`src/seon/cluster/agent.clj:881`).

## Change

- `seon.cluster.prompt`:
  - deleted `config-cluster-name`, the database guess;
  - added `request-cluster-name`, which reads the request's `:seon.sci.eval/ctx`
    environment;
  - `effective-ai-settings` now takes `[database cluster-name agent-id]`. A nil name
    returns `:seon.cluster.prompt/missing-cluster-error`. A missing config row returns
    `config/effective`'s refusal plus `::missing-config`;
  - `agent-calibration` now takes `[database cluster-name agent-id model]`.
- `seon.render.transcript`: `outline-calibration` and `acquire-ledger-data` pass the
  cluster name from the request context's environment.
- The regression is `seon.cluster.prompt-test/the-prompt-reads-the-cluster-its-caller-names`. It checks three cases:
  - a named cluster renders;
  - a named but unconfigured cluster refuses with the same
    `missing-effective`/`missing-config`/`error-key`/`operation` through `prompt` and
    `agent-calibration`;
  - a nil name returns `missing-cluster`.
- The existing calibration test now passes `"prompt-walk"` explicitly.

Net: +82 / −48 across 3 files (src +37/−37 approximately; test +34/−2).

## Evidence

- **Live probe, default JVM.** The new `effective-ai-settings` was evaluated in the
  throwaway namespace `tmp.fix-config-read` (since removed), against default's
  database value:
  - it returns the same 81-key map as the old function;
  - 20-call mean: **0.707 ms after vs 0.872 ms before**, one query fewer;
  - `"no-such-cluster"` gives `{:seon.config/error-key :seon.config/cluster, :seon.config/missing-effective "no-such-cluster", :seon.error/operation seon.config/effective-in}`;
  - nil gives `missing-cluster "root"`;
  - a request with no ctx gives a nil name.
- **Other before timings:**
  - transcript `agent-config`: 1.08 ms;
  - oversight `ping-timeout-ms`: 1.10 ms;
  - `agent-calibration`: 1.44 ms.
- **Hook contract compile:** `CONTRACT_COMPILE post … errors=0` for `prompt.clj` and
  `transcript.clj`. clj-kondo reports 0 errors; its warnings were already there.
- **Snapshot:** `git archive HEAD` plus this diff, with `reference-code` and `.cpcache`
  linked, run under `clojure -M:dev:test`:
  - `seon.cluster.prompt`, `seon.render.transcript` and `seon.cluster.prompt-test`
    loaded in 99,180 ms (cold, loaded machine), peak memory 3.77 GB;
  - the new arglists resolve and the regression Var exists;
  - the snapshot was removed afterwards.

## Not done — needs files outside this lane

1. **Oversight.** `:seon.oversight/request` (`resources/seon/schemas/seon.oversight.edn:11-15`,
   free in the ledger) declares only `:seon.db/db` and `:seon.agent/routing`. The web
   caller (`src/seon/render/web.clj:472-484`) already passes `:seon.sci.eval/ctx`.

   Proposed change:
   - declare `[:seon.sci.eval/ctx {:optional true} :seon.sci.eval/ctx]` in the schema;
   - in `unit`, read the name from `(env/of ctx)` and hand it to `fleet-value`;
   - when routing is present but the name or config is missing, return the config
     refusal as the unit's value, rendered visibly as the refusal and never as an
     empty fleet;
   - `flow-status` (called at `src/seon/cluster.clj:700`) reads the name from
     `(env/of (:seon.sci.eval/ctx instance))`;
   - delete `cluster-name`.
2. **`transcript/agent-history`.** `:seon.render.transcript/history-request`
   (`resources/seon/schemas/seon.render.transcript.edn:1-6`) has only db and agent-id,
   both supplied by call preparation. Nothing that reaches this call carries a cluster
   name. Two routes:
   - require `:seon.boot/cluster-name` and add a call-preparation supplier row that reads
     it from the environment (touches `seon.db` suppliers and
     `seon.call-preparation.edn`); or
   - have the caller hand `:seon.render/profile` plus `:seon.sci.admit/caps`.

   Either way, `agent-config` and its `config/defaults` fallback are deleted. Its only
   caller is `test/seon/render/transcript_test.clj:1090`.
3. **Fourth reader (`src/seon/sci/eval.clj:700-740`, another lane).**
   - Today `database-effective-config` guesses with `[:find ?cluster . … :seon.config/on-core-error]`,
     and `instrumentation-config` defaults `on-core-error` to `:record`.
   - Matching change: `install-function-contract!` takes the ctx it already holds and
     reads `(:seon.boot/cluster-name (env/of ctx))`. It calls `config/effective` once
     and propagates the refusal. The `(or … :record)` default is deleted; a missing
     config leaves contracts unarmed, with the refusal visible, instead of silently
     arming in record mode.
4. **Other re-discovery readers found (not in row 5; owners elsewhere):**
   - `src/seon/render.clj:124-138` (`request-profile`) and `:1756-1761`
     (`custody-cluster-name`);
   - `src/seon/render/web.clj:3121-3126` (`current-cluster-name`, used at `:2262`,
     `:3145`, `:3343`, `:3564`).

   Each uses the same `[:find ?name .]` guess.
5. `transcript/outline-calibration` still turns a calibration refusal into
   `tokens/shipped-calibration` without saying so. It is presentation of an estimate,
   but the refusal is still hidden.

## Commits

See the `git log` entry "fix-config-read: prompt and transcript calibration read the
caller's named cluster". Released paths: none; all three files stay with this lane only
until root reads this note.
