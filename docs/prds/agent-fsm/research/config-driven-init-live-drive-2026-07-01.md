---
type: research
status: completed
tags: [research, agent, config, flow, milestone]
---

# Config-driven agent-init — LIVE DeepSeek closeout drive

THE closeout gate: a real DeepSeek agent booted + ran multi-turn + resumed across
a restart on the config-driven system. Default cluster, DeepSeek adapter (live,
key-configured). 2026-07-01.

## TL;DR — PASS, with one honest rough edge (not in config-init scope)

A fresh `cluster reset default` booted root via the new `init-agent!` path; root
ran **14 turns** of real DeepSeek eval, designed a schema, and a `bin/seon
restart pod` mid-task **resumed root + 2 spawned children from the store** with
every config datom intact. The config-driven init + all CP-4/5 dials work
end-to-end live. The one rough edge (agent-authored `schema/register!` reports
`:ok` but the attr isn't installed in the wire DB, so the stored fact isn't
queryable back) is in the **agent-authored-schema → wire-server persistence
path**, NOT the config-driven-init work.

## Init (via the new init-agent! path) — VERIFIED

- root agent entity seeded; **10 context blocks** (the full default tree).
- config datoms present: `:skill/repl` block (from `:my.skills/load [:repl]`),
  `live-tile content = seon.render.system/system-view` (config-driven root
  canvas), transcript `::result-decay` = `[{0 16384}{2 1500}{5 200}]` reified as
  3 `::decay-level` entities.
- provider `:deepseek`, adapter live (key-configured — real LLM calls).

## Multi-turn — VERIFIED

- **14 turns** ran; **30 evals** executed; run closed cleanly on `:no-forms`
  (the FSM empty-turn streak limit fired correctly after the agent stopped
  producing forms). ctx ~10k tokens/turn.
- verb tools worked: the agent used `todo` (created a plan item), `schema/register!`
  (7 successful registers designing `:my.kb.datastructure` + `.operation`),
  `seon.db/transact!`, `db/store-inventory`.
- the agent AUTHORED new namespaces/schemas live (`:my.kb.datastructure`,
  `:my.kb.datastructure.operation`) with a provenance schema
  (`:my.kb/source` + `:my.kb/confidence [:enum :verified :inferred :uncertain]`).

## Configs driving behavior — OBSERVED

- **`:my.skills/load`** → the `:skill/repl` block seeded + rendered.
- **eval-result decay** → the 3-level schedule seeded on the transcript block
  (reified entities). (The gym scenarios + this drive are too short to age an
  eval past offset 2, so the shrink is inert here by design; the bounding is
  proven separately in [[cp5-balloon-measurement-2026-07-01]].)
- **escape-clipping / render** → ctx rendered full each turn (~10k tokens),
  blocks rendered.
- **live-tile content** → root's canvas = `system-view` (config-driven, the
  deleted hardcoded branch's replacement).

## Memory store→retrieve — PARTIAL (rough edge surfaced)

The agent designed the schema + issued the store transact (`:ok true` in
session), BUT the fact is NOT queryable back:

- `:my.kb.datastructure/name` is in the pod's Malli registry (`register!`
  returned `:ok`) but **NOT in the DB installed-schema**, and the stored row
  returns 0 on query-back.
- ROOT CAUSE (hypothesis, confirmed by the split registry/DB state): a
  agent-authored `schema/register!` registers in the in-memory Malli registry,
  but the subsequent `transact!` did not INSTALL the new attr's datahike schema
  into the wire-server store — so the datom silently didn't persist queryably
  even though the eval reported `:ok`. This is the **agent-authored-schema →
  wire persistence path**, a real rough edge to fix, but SEPARATE from the
  config-driven-init work.
- Also two agent-OWN errors (correctly surfaced by the error render, not system
  bugs): `:O(1)`/`:O(log-n)` as EDN keywords are unreadable (`:O(1)` breaks the
  reader → "did not parse, DEFINED NOTHING"); a later transact referenced an
  undefined `log32-n` symbol ("ran NOTHING").

### Task #92 follow-up (2026-07-02) — mechanism PROVEN sound; test coverage hole closed

Investigated the "store `:ok` but retrieve fails" rough edge. The runtime
register→install→transact→query path is CORRECT and was re-proven live end-to-end
against the REAL wire store (not just the pod-local view): a fresh
`schema/register!` of a new attr, then `db/transact!`, then `db/query` returns the
row; `seon.server.wire` (JVM socket REPL 7891) confirmed both the attr's datahike
schema (`:db.type/string`) AND the datom landed in the store — inside a
`with-agent` scope too. `seon.db.internal/transact!*` awaits
`ensure-datahike-attrs!` (which derives the datahike attr-decl via the Malli
bridge and forwards it as a schema-tx over the `:seon-wire` PWriter, schema-before-
data) BEFORE the data tx, so the wire-server always installs schema ahead of data.

The drive symptom did NOT reproduce on a clean pod (the drive's store was since
reset, and the drive interleaved reader-error evals — `:O(1)` etc.). The REAL,
fixable finding: the db suite had a COVERAGE HOLE — every `db_test.cljs` transact
runs against a `fresh-conn` that PRE-installs its attrs via a hardcoded
`smoke-schema`, so NOTHING exercised the runtime installer
(`ensure-datahike-attrs!`). Closed with a regression test
(`transact!-installs-runtime-registered-attr-then-queries-back`) that registers a
brand-new attr, asserts it is NOT pre-installed, transacts, and asserts the attr
becomes installed AND the datom queries back — the exact split-state the drive saw.

## Planning + RESUME across restart — VERIFIED (the strongest result)

`bin/seon restart pod` mid-task → roster `:resumed ["djy-…" "kXL-…" "root"]`,
`:minted []`. After restart:

- root resumed (entity + 10 blocks intact);
- **every config datom survived**: 3 decay levels, `:skill/repl` block,
  live-tile `system-view`;
- **open todos survived** — incl. "Design schema for data-structure facts";
- the agent-authored `:my.kb.datastructure` ns survived.

So init + config + the agent's plan + its authored code all persist across a
restart — the agent can resume from its open plan items. Continuity proven.

## Verdict

The config-driven agent-init system (CP-4 → CP-5.5) is PROVEN live: boot,
multi-turn agentic loop, config-driven context, and resume-across-restart all
work end-to-end on a real DeepSeek agent. The memory store→retrieve rough edge is
a real find in the agent-authored-schema wire-persistence path (flag for a
follow-on), not a config-init regression.

## Operational note (for the next driver)

The message-wake trigger did NOT fire the loop from the fresh-boot inbound (a
boot-time `tx-feed pump failed (wire rpc timeout) — re-subscribing` dropped the
tx subscription the wake listener rides). Worked around by opening a run +
`seon.agent.loop/drive-run!` directly. If a fresh agent won't wake on a message,
check the pod's tx-feed subscription health, not the wake trigger.
