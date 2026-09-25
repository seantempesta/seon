---
type: landing
status: complete
lane: fix-test-rows-d-opus
created: 2026-09-25
authority: docs/research/agent-platform/red-baseline-census-2026-09-25d.md (classes 2 ROWS, 11 WORLD, 6 SHAPE tests, CFG)
---

# Lane fix-test-rows-d: census d ROWS, WORLD, SHAPE (tests) and CFG

## Broken or not fixed first

- **Nothing here was run.** Under the LOAD RULE no test request ran. Every edit passed clj-kondo with 0 errors. No
  namespace was loaded into default. Each canonical helper output was probed read-only at default's JVM REPL (MCP
  `eval_clj`, scratch namespace `fix-test-rows-d-scratch`). Root runs are listed below.
- **FIX-SRC, left red: `seon.turn-boot-test/root-opening-with-diagnostic-errors-does-not-park`.** The failing
  assertion is `(string? (:seon.turn/id pass))`, and it asks for the wanted behaviour.
  - `seon.turn/step` (turn.clj:5525) says that `:open` "mints its run inside the turn, so the report supplies it".
  - `open-turn` (turn.clj:4250) reports `(report :released 0)`. The `report` closure in `turn` (turn.clj:5160) adds
    `:seon.turn/id` only from the *work*, and an `:open` work item has no id.
  - Result: the pass never names the turn it opened, so step's `run-id` atom and its state `:seon.turn/id` stay unset.
  - Fix belongs in src/seon/turn.clj: open-turn reports the `id` it minted.
  - Proposed issue: `docs/seon/issues/open-turn-report-omits-the-opened-turn-id.md`.
- **Not fixable in owned files: `seon.help-trial-test/preflight-…`.** The committed trial subject
  `docs/prds/context-generation/research/help_trial_2026_09_09.clj:174` queries `[?message :seon.message/inbox ?agent]`.
  That attribute was retired in a50424f6b.
  - Recorded evidence (run `bd2463ffc9b0`): `:seon.trial/messages` is a `seon.db/q` invalid-read refusal, because the
    attribute is not installed.
  - Fix: the query uses `:seon.message/to`, in that doc file's owner lane.
  - The member also took 12.5 s against a 5 s bound. That needs a measurement once it is green.
  - This lane moved only its seed to `seed-cluster!`.
- **Src finding (CFG producer, confirmed): `sci.eval/projection-state` invents a cluster name.** At eval.clj:181 it
  builds every environment with `{:seon.boot/cluster-name "current-src"}`.
  - A context from bare `eval/cluster-ctx db conn` therefore names a cluster that no fixture branch configures, so
    `render/request-profile` refuses.
  - The tests now take their context from `test-support/fork-cluster-ctx`, which names the seeded cluster or the
    handle's.
  - The invented name is still a violation of "never an invented cluster name". fed60b7b9 (render-dispatch lane)
    records the same producer.
- **Src finding: cluster-default dials ignore which cluster is asked for.** Two reads take whichever config row the
  query happens to return:
  - `seon.turn/max-episode-runs` (turn.clj:2697), `[?config :seon.config/cluster _]`.
  - `seon.agent/effective-settings` (agent.clj:131), `[_ :seon.config/cluster ?name]`.

  A member branch holds boot's config row plus the fixture's seeded row, so agent-situation read 100 and run6 read 99.
  The tests now set the bound as the agent's own override (`seon.agent/settings!`), which is the read's first
  precedence. The read itself should resolve the agent's cluster. Proposed issue:
  `docs/seon/issues/cluster-default-dials-read-an-arbitrary-config-row.md`.
- **Law violations left in owned files (out of this lane's scope):**
  - `effect_test.clj`: `with-redefs` over `flow/submit!` (background-settlement) and `with-redefs-fn` over
    `seon.effect/dispatch` (interrupted-handlers).
  - `sci/eval_test.clj`: `with-redefs` over `call-preparation/hook` (call-preparation-receives…) and over
    `schema.edn/packaged-forms` (database-provenance).
  - `help_test.clj`: `with-redefs` over `turn/evaluate-sources`.
  - `help_trial_test.clj`: `load-file` of the research subject.
- **Not edited (held by another lane):** `sci.eval-test/a-selected-render-inherits-the-live-arm…` (HEAD eval_test.clj:2193,
  containing :2221) uses the same bare `eval/cluster-ctx`, so it has the same CFG cause. Its owner can apply the same
  one-line `fork-cluster-ctx` change.
- **RESET NEEDED:** none.

## Per-member table

| member (class) | change | commit |
|---|---|---|
| effect-test, all 14 (ROWS) | Agents come from `test-support/agent-tx`, and turns name `[:seon.agent/id "effect-agent"]` instead of nesting a bare agent map. `install-capability!` / `install-arm-probe!` become one `install-owner!`: `program-fn-row` analyzes a synthetic defn with `:seon.workload :io` and `:seon.effect/capability (symbol #'handler)`, and the request schemas are inline. capability-fixtures asserts the digest, `:io` and the exact handler. | 5cab443ba |
| sci.eval a-turn-fork, an-acquired-function, agent-contracts (ROWS) | Agents via `agent-tx`. The function row is its own transaction. | 1e23bc1c6 |
| sci.eval every-public-capability, schema-and-contract allocation, acquisition-binds, call-preparation, public-walk (CFG) | `eval/cluster-ctx` becomes `test-support/fork-cluster-ctx connection`. | 1e23bc1c6 |
| sci.eval bare-dir (SHAPE) | doc `:in`/`:out` are asserted against `my.fs/read`'s declared `:seon.fn/spec`. | 1e23bc1c6 |
| sci.eval a-configless-database (WORLD) | **Deleted.** It asserted the record-mode default that 39ca6521a retired. `seon.sci.fault-policy-test/an-unconfigured-cluster-refuses-arming…` owns the replacement. | 1e23bc1c6 |
| fn-test settled-form (SHAPE) | The work names `:seon.turn.work/situation :resume`. The injected report returns a `:seon.turn.loop/turn-report` map, and the test compares `(juxt outcome forms-run)`. | 805d284a0 |
| fn-test incremental-tempid-rewrite (ROWS) | Namespace rows come from `program/declaration-row` over the extended projection, built once. | 805d284a0 |
| turn-test a-wake-meeting (SHAPE) | The injected report returns a turn-report map and records `[outcome forms-run]`. | 805d284a0 |
| schema.datahike edn-backed (ROWS), fixed-tuples (PROBE, same class) | Holders are `test-support/namespace-row`, with unqualified `codec.<id>` names. | a980fda60 |
| agent-situation (WORLD) | `seed-cluster!` "situation" without a dial, plus `seon.agent/settings!` max-episode-runs 2. Hand-built my.turn/my.message rows dropped. | 749e69fc1 |
| run6-stall (WORLD) | `seed-cluster!` keeps only no-provider; `settings!` max-episode-runs 3. The other four run6 reds (stall header, rule count) should follow once `turns-left` is right. Not verified. | 749e69fc1 |
| help-test (RENDER/ROWS) | `seed-cluster!` "help" replaces `config/apply!` plus a bare `:seon.cluster/name` add. The render-ai "lookup-ref vector" was the first map entry of a no-such-agent error. | 11c1030a2 |
| help-trial preflight (WORLD) | Seed via `seed-cluster!`. **Still red** (see Broken). | 11c1030a2 |
| turn-boot root-opening (SHAPE) | **Unchanged**, src finding. | — |

## Evidence

- Read-only probes on default (pid 35446):
  - `agent/creation-tx` under `d/with` assigns `:seon.agent/branch :cluster-default`. So the effect refusal came from
    the nested bare agent maps, not from agent-tx.
  - `program-fn-row` of the synthetic capability defn yields `:seon.effect/capability`, `:seon.fn/workload :io`,
    `:seon.fn/calls #{handler}` and a digest in 40 ms. The `arm-probe-handler` row is private and has a spec.
  - `declaration-row` over the extended ns projection keeps `::identity-value`/`::identity-ref` and derives digests.
    `build-projection` took 153 ms.
  - The `my.fs/read` spec is `[:=> [:cat :my.fs/read-request] [:or :my.fs/read-result :my.fs/error]]`.
  - `:seon.config.run/max-episode-runs` is one of the 34 `ai/agent-setting-attributes`.
  - `recorded-member-results` for run `bd2463ffc9b0` shows the retired `:seon.message/inbox` read.
- Lines in owned paths: +118 / −153, net −35, across 6 commits. The largest commit adds 43 lines.
- Timings: none beyond the probes. No test executed.

## Root runs (serially)

```
bin/test-check default --ns seon.effect-test
bin/test-check default --ns seon.sci.eval-test
bin/test-check default --test seon.fn-test/settled-form-records-calls-across-every-program-namespace
bin/test-check default --test seon.fn-test/incremental-tempid-rewrite-follows-the-installed-attribute-type
bin/test-check default --test seon.turn-test/a-wake-meeting-an-open-turn-releases-without-a-fault
bin/test-check default --ns seon.schema.datahike-test
bin/test-check default --ns seon.agent-situation-test
bin/test-check default --ns seon.run6-stall-test
bin/test-check default --ns seon.help-test
bin/test-check default --ns seon.help-trial-test          ; expected red until the trial file reads :seon.message/to
bin/test-check default --ns seon.turn-boot-test           ; root-opening expected red until open-turn reports its id
```

Released paths: every path in the fix-test-rows-d-opus row.
