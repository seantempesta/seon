---
type: research
status: complete
tags: [research, runtime, database, web, observability, live-drive]
---

# Independent live-drive observer — 2026-08-06

## Verdict

The drive did not end cleanly. Its final run retained valid process custody but
stopped before evaluation receipt zero. The decisive live fault was not a
missing receipt in isolation: turn-fork rehydration treated three honestly
unrestorable atom rows as restorable, called `seon.blob/get` with a nil digest,
committed a core-fault fact, and left the run open.

I stopped the watch at that exact foreign-run boundary. At
`2026-08-06T17:38:47Z`, the run had been open for 12 minutes 28 seconds, the
database and log had produced no new drive activity for more than four
minutes, and the run still had zero eval receipts. I did not stop, reset,
refork, resume, message, or edit the live session.

The observer independently confirms the drive lane's report that the session
wedged before its first receipt, and narrows the causal boundary to agent defs
rehydration. The drive lane's broader narrative and mutation record are in
[Default-cluster live drive](docs/prds/sci-execution-runtime/research/live-drive-2026-08-06.md).

## Scope and method

Observed target:

- cluster `default`;
- PID `52509`, start instant `2026-08-06T17:21:54Z`;
- process identity `52509-1786036914863`;
- [web endpoint](http://127.0.0.1:7994); and
- prepl port `51998`.

I read the `datahike`, `repl`, and `datastar-web-ui` skills and the localized
[SCI execution runtime instructions](docs/prds/sci-execution-runtime/AGENTS.md)
before observing. Database probes used bounded `seon.db/q` and `seon.db/pull`
calls through JVM-mode `eval_clj`. Page checks used GET requests only. Log
checks read `data/clusters/default/logs/seon.log` without following it
continuously. Polls were separated by observed event boundaries rather than a
busy loop.

The MCP health surface was itself unusable: `runtime_status` returned a
`seon.problems/problems` invalid-output contract error instead of health. The
process identity was therefore cross-checked with read-only `bin/seon status`,
the advertisement, database facts, and the responding ports. This matches
[Keep committed error facts valid in the problems projection](docs/seon/issues/problems-projection-breaks-health-and-root-render.md).

### Observer write caveat

The intended observation was read-only, and I performed no explicit database
transaction. One early `eval_clj` result exceeded the MCP face limit, however,
and that tool's built-in artifact spill committed artifact facts in the
observed cluster at transactions `536871022` through `536871025`. After seeing
that side effect, every remaining query was bounded below the spill threshold.
Page GETs also caused the application itself to commit renderer-failure
messages. The evidence below distinguishes drive facts from these observer-
triggered render/artifact facts; claiming a literally write-free watch would
be false.

## Dependency ledger

The observed seams depend on these checked-in sources and pins:

| Dependency or mechanism | Selected revision | Read boundary |
|---|---|---|
| Datahike | `10540578248e` | `reference-code/datahike/src/datahike/api.cljc`, `reference-code/datahike/src/datahike/writer.cljc` |
| SCI | `2db3358cba91` | `reference-code/sci/src/sci/core.cljc`, `src/seon/sci/eval.clj` |
| core.async Flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `src/seon/cluster/loop.clj` |
| http-kit | `238a85cc555a38892f2f9a7583c9cf5cec0fb201` | `reference-code/http-kit/src/org/httpkit/server.clj`, `src/seon/render/web.clj` |
| Reitit | `0.10.1` | `src/seon/render/route.clj` |

First-party fact owners inspected were `src/seon/cluster/run.clj`,
`src/seon/cluster/loop.clj`, `src/seon/effect.clj`, `src/seon/error.clj`,
`src/seon/render.clj`, `src/seon/render/transcript.clj`, and
`src/seon/sci/eval.clj`.

## Timeline of observed facts

| Time (UTC) | Basis/eid | Observation |
|---|---|---|
| 17:22:00–17:22:04 | run eid `23598` | Bootstrap run `bootstrap:root` opened and closed with 13 eval receipts. |
| 17:22:24 | run eid `23644` | Run `f28ba3ab-b20a-4eec-a86f-06c548a54123` opened and closed; provider result was unreadable JSON reported as `closed`. |
| 17:24:04 | run eid `23670` | Run `bc6b2c7c-8acc-43ae-a642-4aab28095d28` opened and closed with the same provider failure class. |
| 17:25:50 | message eid `23679` | Inbound message `inbound-536871002-0` arrived with ordinal `0`. |
| 17:25:22–17:26:19 | run eid `23675` | Run `f9a0547f-761a-427a-84e1-d81f2764aff7` closed; eval receipt eid `23682` settled with result size 2,638. |
| 17:26:19 | tx `536871007`, eids `23683`–`23685` | Settlement committed three root atoms in the agent's defs as unrestorable, with no value EDN or blob digest. |
| 17:26:19 | run eid `23687` | Run `f56667dc-a2ec-4f92-af47-e37cdb06535c` opened with custody `52509-1786036914863`. |
| after plan freeze | form eids `23698`, `23699` | The run had exactly two ordered forms: a database inspection and `my.run/complete`. Neither acquired an eval receipt. |
| 17:27:50 | error eid `23700` | Core fault: `seon.blob/get` invalid input, nil digest; signature begins `5980…`. Notice message eid `23701` followed. |
| 17:34:08 | final render event before quiet | The last new fact was observer-triggered rendering output, not drive progress. |
| 17:38:47 | basis `536871035` | Run eid `23687` still open with custody and zero eval receipts after 12m28s. Watch ended. |

The three rows for the agent's defs were:

| eid | `:seon.def/key` | stored state |
|---:|---|---|
| 23683 | `["root" "seon.operator.runtime/held-flocks"]` | atom, unrestorable, no value/blob |
| 23684 | `["root" "seon.operator.runtime/running-instances"]` | atom, unrestorable, no value/blob |
| 23685 | `["root" "seon.operator.runtime/root-store-holder"]` | atom, unrestorable, no value/blob |

Each carried `The atom's settled value is not store-faithful.` Source then
closed the causal chain: `src/seon/sci/eval.clj:1329-1359` tests the `atom?`
arm and calls `def-value` before reaching the later unrestorable-reason arm.
The new owner note is
[Skip unrestorable atom rows for the agent's defs before blob rehydration](docs/seon/issues/unrestorable-atom-desk-row-wedges-next-turn.md).

## Invariant checks

Every query used the live immutable database value from `(seon.db/db)`. The
shown forms are the bounded predicates used for the final checks; counts are
from basis transaction `536871035`.

### Every open run has custody

```clojure
(seon.db/q
 '[:find (count ?run) .
   :where
   [?run :seon.cluster.run/id]
   (not [?run :seon.cluster.run/closed-at _])
   (not [?run :seon.cluster.run/process _])]
 (seon.db/db))
```

Result: `0`. The one open run was
`[23687 "f56667dc-a2ec-4f92-af47-e37cdb06535c"
#inst "2026-08-06T17:26:19.882-00:00" "52509-1786036914863"]`.
Custody held even though progress did not.

### Every eval receipt belongs to an existing run

```clojure
(seon.db/q
 '[:find (count ?receipt) .
   :where
   [?receipt :seon.cluster.eval/id]
   (not [?receipt :seon.cluster.eval/run _])]
 (seon.db/db))
```

Result: `0`. A second pull of every referenced run returned no missing entity:
`0`. The inventory contained 14 eval receipts. The stuck run's receipt count
was independently queried as `0`.

### Every effect receipt belongs to an existing run

```clojure
(seon.db/q
 '[:find (count ?receipt) .
   :where
   [?receipt :seon.effect/id]
   (not [?receipt :seon.effect/run _])]
 (seon.db/db))
```

Result: `0`; a referenced-run existence check also returned `0`. No effect
receipts existed during the observation, so the relationship held vacuously
and effect settlement was not exercised.

### Messages have arrival ordinals

```clojure
(seon.db/q
 '[:find (count ?message) .
   :where
   [?message :seon.cluster.message/id]
   (not [?message :seon.cluster.message/ordinal _])]
 (seon.db/db))
```

Result: `16` of 17 message entities lacked the attribute. Only inbound message
eid `23679` carried ordinal `0`. Missing rows included maintenance/error
notices and renderer-failure messages. This invariant failed; the issue is
[Give system-generated messages arrival ordinals](docs/seon/issues/system-generated-messages-omit-arrival-ordinals.md).

### Interruption does not re-execute work

No interruption occurred during the authorized observation, so the recovery
half of this invariant was not exercised. Within the wedge, both planned form
entities remained singletons and receipt count stayed zero; there was no
evidence of repeated execution. This is calibration, not a recovery proof.

### Settled UI is a pure function of the database value

At a settled basis, two consecutive GETs of `/` returned identical 520,416-byte
bodies with SHA-256
`62692756ff4d841bb28798b7e7fe7e7996216d65d3b91d429e084d568334cfb1`,
and neither the basis nor message/error counts changed. `/agent/root` and
`/ns/my.agents.root` returned the same bytes and digest at that basis. Reload
therefore repainted deterministically for the ordinary namespace aliases.

This held despite broken content. Each body contained eight `Renderer
unavailable` faces and 185 duplicated HTML id values, so determinism did not
make the result correct.

## Log observations

At the final census, `data/clusters/default/logs/seon.log` contained 270 lines
and 20,565 bytes.

1. **Raw stack-trace flood — blocker surface.** Four GETs of `/data` produced
   four exception headers and 240 raw stack-frame lines. One ordinary request
   therefore contributes about 60 unreadable lines. The underlying 500 is
   owned by
   [Supply the live SCI context to the data page renderer](docs/seon/issues/data-page-omits-the-live-sci-context.md);
   the raw log face remains ugly output.
2. **Expected transaction refusal logged twice.** Fresh maintenance settlement
   refused missing `:seon.operator.log/path`, then emitted both raw Datahike
   rejection detail and a bounded writer line before the development panic.
   The schema defect is
   [Install maintenance result attributes on a fresh cluster](docs/seon/issues/fresh-maintenance-result-attributes-are-not-installed.md),
   and the duplicate face is
   [One bounded log face per expected transaction refusal](docs/seon/issues/expected-refusal-logs-raw-datom-error-twice.md).
3. **Operator status warning flood.** A healthy-process `bin/seon status`
   printed eight full-path invalid-external-claim warnings. This matches
   [Quiet the unreadable-external-claim flood](docs/seon/issues/status-floods-unreadable-external-claim-warnings.md).
4. **The decisive core fault was concise enough to identify.** The nil-digest
   `seon.blob/get` contract line appeared once in the log and once as a durable
   error fact. It was not a stack flood, though the run failed to settle after
   it.

## Web observations

| Route | Status/result | Final face |
|---|---|---|
| `/` | 200, 520,416 bytes | 643 id attributes, 185 duplicated values, eight unavailable renderers |
| `/agent/root` | 200, byte-identical to `/` | same defects |
| `/ns/my.agents.root` | 200, byte-identical to `/` | same defects |
| `/agent/root/debug` | 200 in 5.76 s, 667,580 bytes | 644 ids, 185 duplicates, five contract violations, 25 renderer-failure strings |
| `/ns/my.agents.root/debug` | no first byte before a 12 s bounded request ended | matches the existing debug response blocker |
| `/data` | 500, 136 bytes | `seon.sci.kernel/context-projection` invalid input: nil SCI context |

The duplicate ids are a direct Datastar correctness defect rather than merely
large markup. Representative id `seon-value-84414bc270622cd498e78b26`
occurred twice in one document. The owner note is
[Make every rendered value id unique within its namespace page](docs/seon/issues/rendered-value-ids-collide-within-one-page.md).

The canonical debug response is tracked by
[Return the namespace debug page without blocking the response](docs/seon/issues/debug-page-blocks-before-first-byte.md).
The `/data` 500 is linked above. Repeated transcript render failures came from
passing a set of about eids to `seon.db/pull-many`; that is owned by
[Pass ordered entity ids to transcript `pull-many`](docs/seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md).

The root face was also materially unreadable: roughly half a megabyte of raw
schemas, functions, database ids, and repeated failures. Existing owners are
[Give render token budgets one config owner](docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md)
and
[Give cluster, config, and bootstrap plan named concise producers](docs/seon/issues/cluster-config-and-bootstrap-plan-render-as-raw-maps.md).

No connected graphical browser was available, so CSS layout, console state,
and live morph animation were not directly observed. HTTP identity,
deterministic reload, response latency, HTML structure, and rendered text were
observed; claims beyond those would be invented.

## Ranked defects and ugly output

1. **Blocker — unrestorable atom row for the agent's defs wedges the next turn before receipt
   zero.** Newly filed:
   [Skip unrestorable atom rows for the agent's defs before blob rehydration](docs/seon/issues/unrestorable-atom-desk-row-wedges-next-turn.md).
2. **Blocker — DOM ids collide within one rendered namespace page.** Newly
   filed:
   [Make every rendered value id unique within its namespace page](docs/seon/issues/rendered-value-ids-collide-within-one-page.md).
3. **Blocker — `/data` returns a deterministic 500 and floods the log with raw
   stacks.** Existing live-drive issue:
   [Supply the live SCI context to the data page renderer](docs/seon/issues/data-page-omits-the-live-sci-context.md).
4. **Blocker — transcript rendering supplies an invalid set to `pull-many` and
   commits repeated failure messages.** Existing live-drive issue:
   [Pass ordered entity ids to transcript `pull-many`](docs/seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md).
5. **Blocker — the problems projection makes MCP health unusable.** Existing
   live-drive issue:
   [Keep committed error facts valid in the problems projection](docs/seon/issues/problems-projection-breaks-health-and-root-render.md).
6. **Friction — 16 of 17 messages omit their explicit arrival ordinal.** Newly
   filed:
   [Give system-generated messages arrival ordinals](docs/seon/issues/system-generated-messages-omit-arrival-ordinals.md).
7. **Friction/ugly — canonical debug blocks before first byte and the agent
   alias takes 5.76 seconds.** Existing live-drive issue:
   [Return the namespace debug page without blocking the response](docs/seon/issues/debug-page-blocks-before-first-byte.md).
8. **Ugly — expected maintenance refusal is double-logged and status emits
   eight cryptic warnings.** Existing owners are linked in the log inventory.
9. **Ugly — ordinary pages are 520 KB with raw facts and eight renderer-
   unavailable faces.** Existing render-profile and named-producer owners are
   linked above.

## Honest calibration

What held up:

- the process identity remained stable and every open run had custody;
- every eval/effect receipt relationship queried cleanly;
- the bootstrap run and one subsequent agent run settled with durable eval
  receipts;
- the inbound message had an explicit arrival ordinal;
- `/`, `/agent/root`, and `/ns/my.agents.root` agreed byte-for-byte at one
  settled database basis;
- repeated settled reloads produced the same bytes without committing new
  facts; and
- the core fault was durably queryable with process provenance.

What was not proven:

- no effect request occurred, so effect settlement was not exercised;
- no interruption occurred, so non-reexecution after recovery was not tested;
- the final run never reached an eval receipt or clean close;
- live Datastar morph delivery was not visible through a graphical browser;
  and
- the observer's first oversized MCP query and page GETs were not literally
  side-effect-free, as disclosed above.

The strongest calibration is that durable facts made the final wedge
explainable without trusting the drive lane: eids `23683`–`23685`, run eid
`23687`, error eid `23700`, and the live source order jointly establish the
failure boundary. The same observation also shows that committing a core fault
does not yet guarantee the affected run reaches a terminal fact.
