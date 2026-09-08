---
type: research
status: active
date: 2026-09-08
tags: [research, agent, render]
---

# Agent components landing

Work in progress. No implementation or gate completion is claimed.

Read AGENTS.md and the turn PRD §0, §1a, §4/§4a, §13, and §17 end to end,
plus the plan README and working edge. The AGENTS.md lane-rules preamble
is its copy of PRD §10; AGENTS.md has no separate §10 heading.

## Baseline observation

Playwright with installed Chrome inspected default at
`http://127.0.0.1:7994/ns/my.agents.juniper/debug` on 2026-09-08.
The page renders id, cluster, instructions, namespace, and run separately.
Plan AI executes current/ready/blocked; plan HTML says `map 3 items, depth 0`.
Settings has no stored value. Stored `dir` evaluations return symbol vectors
and also print function names. The context algorithm reports
`Not yet available: seon.eval/of-agent`.

Default PID 91455: runtime_status reports health and Flow unknown (read
 timeout); a subsequent JVM `(+ 1 1)` returns 2 in 3 ms. Existing issue:
[component probe timeout](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
Default was not stopped, reforked, or restarted.

## Dependency ledger

- Datahike: owned refs and pull; `reference-code/datahike/src/datahike/db/transaction.cljc`
  owns `:db.fn/call`, deciding against the writer's database.
- Schema bridge: `src/seon/schema/datahike.clj` derives component facets;
  `resources/seon/schemas/seon.agent.edn` already declares plan/settings refs.
- Overlay: `src/seon/ai.clj` agent-overlay derives keys from
  `:seon.config/agent-overlay` and follows the settings component.
- Plan: `src/my/plan.clj` already follows the plan component, introduced
  by `74b5b4b05`; inspect and improve that owner in place.
- Render integration: page-feed owns web.clj and render internals. Any needed
  change there is recorded here rather than applied to that lane's files.

## Required changes in protected render owners

The following readers still refer to the deleted agent cluster ref. In
`src/seon/render.clj`, `custody-cluster-name`, and
`src/seon/render/transcript.clj`, `agent-config`, remove the agent-id input
and the agent/id + agent/cluster joins from the cluster-name query; the
branch's `[_ :seon.cluster/name ?cluster-name]` fact supplies its cluster.
Update each local caller to pass the database only.

`src/seon/render/agent.clj`, `agent-ai`, must not infer idle from missing
`:seon.cluster.agent/run`. Query `seon.cluster.run/open-for-agent` with the
unit's database and agent lookup, or render identity only. Missing a retired
pointer does not prove idle.

The page must call the agent entity's declared pair once for its scalars,
then each component's pair. Its current per-attribute loop bypasses the
agent's useful identity renderer. This is in the protected page-feed owner.

Protected test `test/seon/cluster/agent_test.clj` still writes the retired
pointer at its fixture around line 608 and queries it around lines 1057 and
1778. Remove the fixture pointer; derive the open turn through its agent ref
and absence of closed-at. These edits were not applied to another lane's file.

## First cut evidence

The isolated subject gate passed 38 tests / 273 assertions, zero failures
and errors (snapshot `run.Qu9gPF`, coordinator/test phase 87 seconds).
The first run exposed an incorrect pulled-ref output contract in the new
query; returning the turn id corrected it. The next run exposed two stale
identity expectations; the branch supplies the cluster for every agent.

The platform attempt `run.fv5GcQ` failed before assertions because pool-4
had no prepared classpath. Its three-worker retry uses the documented
processor-count workaround; see
[worker-count issue](../../../seon/issues/platform-worker-count-exceeds-prepared-checkouts.md).

Scratch root `tmp/components-root`, cluster `components`, HTTP 7809, was
reforked from publication `6aa09695-1061-5ba5-ac8d-65775ce553d7` and seeded
with the updated Juniper fixture. MCP JVM verification returned in 4 ms:

```clojure
{:agent-keys [:db/id :seon.agent/plan :seon.cluster.agent/id
              :seon.cluster.agent/namespace]
 :open-turn "bootstrap:juniper"
 :retired-schemas [nil nil nil]}
```

The schema entries checked were run, cluster, and instructions. There was
no agent toolkit declaration to remove. The scratch armer was paused using
core.async.flow's own pause-proc and its ping confirmed `:paused` before
seeding; Juniper exists and its attempt query returns `[]` (8 ms). No model
turn was used for this proof. The generated CSS artifact was copied into
the worktree's resources after the first screenshot exposed its absence.

[Scratch screenshot](components-step1-2026-09-08.png) and
[exact captured AI/HTML column text](components-step1-2026-09-08.json) show
that cluster, instructions, and run blocks are gone. Identity remains split
by the protected page loop, and the existing plan HTML remains generic at
this first cut. These are not final-render acceptance screenshots.

Platform retry passed 82 tests / 486 assertions, zero failures and errors,
with three workers (snapshot `run.Dz4uN9`, coordinator/test phase 90 seconds).
The subject and platform gate used HEAD plus only the owned paths. The
subsequent source edits only corrected nearby documentation/teaching text.


## First commit adoption

Commit `67fe1675d` adopted onto default successfully; publication
`6aa09972-aadb-56cc-a9dd-ee5ec378034c`. I inspected the default page with
Playwright and viewed its screenshot. Instructions and run blocks disappeared;
a legacy cluster datom still appears. **RESET NEEDED: `67fe1675d`** for the
orchestrator's single refork. Default was not stopped or restarted.


## Settings component

`my.agent/settings!` decides the component identity inside Datahike's writer
and updates it in place. The schema's `:seon.schema/references` supplies the
key set; there is no second dial roster. The turn bound now declares its
per-agent overlay flag. Model settings already followed the component;
evaluation/turn passes merge it into their supplied handle, and the agent's
completion wait reads its override. Missing overlay schema references return
a typed unknown-shape value.

The subject gate passed **17 tests / 130 assertions**, zero failures/errors
(`run.QjAvuD`, coordinator/test phase 87 s). The canonical property took
24,649 ms after the query change. Its earlier fast run was interrupted after
a thread dump proved it rebuilding the entire schema projection per overlay
read; the dump is summarized in the existing
[projection issue](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md).
The regression verifies repeated writes preserve one component, agent isolation,
a changed override changes turn admission, and seven real SCI evaluations
receive the explicit agent deadline.

Scratch adoption `6aa09c1e-d8fb-56db-b1a3-b59707277291` completed. A live update
and read returned in 48 ms:

```clojure
{:settings {:seon.config.eval/time-limit-ms 2500
            :seon.config.run/max-episode-runs 4}
 :same-component true
 :dial-count 29}
```

The dial count is a dated observation of the schema query. The fixture now
seeds these two overrides on its single settings component. The
[scratch screenshot](components-step2-2026-09-08.png) and
[AI/HTML text](components-step2-2026-09-08.json) were captured from the live
page and inspected; useful presentation is the next cut.

Settings platform gate: **82 tests / 486 assertions**, zero failures/errors
(`run.oLleW9`, coordinator/test phase 112 s), with at most three workers.
