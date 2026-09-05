---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, architecture]
---

# The host base does not resolve the agent-facing surface (q34, W5-0 gate)

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — U9 deletion.** U9's census-to-zero cutover gate owns full
symbol resolution and source-to-installed capability-effect parity before the
fallback children disappear.

## Observed (2026-07-22, live default cluster, host-tier dial ON)

Two independent live evidences on HEAD:

1. Direct probes: `seon.db/entity` and `my.blob/get` are UNRESOLVED on
   the supervised JVM host (they route to the preflight
   unresolved-symbol path). Host boot log: `base-loaded=166/172
   base-failed=6 base-excluded=112`.
2. A real DeepSeek drive (`wet-mammals-go`, 8 turns, post-q29): the
   agent completed its computation but could not reach `message/user`,
   `seon.agent.message/user`, or `seon.agent.lifecycle/complete` — all
   unresolved on the host — retried spellings for 8 turns and the run
   closed `:no-forms`. Under the dial, agents currently CANNOT message
   the human or complete tasks. (Turns all `:done`; q29's containment
   fix held throughout.)

## What this is

The W5 cutover's real precondition: the complete agent-facing surface
(`my.*` toolkit, `seon.agent.message`, `seon.agent.lifecycle`,
`seon.db`, `my.blob`, capability fns) must resolve on the host tier —
whether via base loading, capability registration
(`register-host-capabilities!`, host/context.clj:1377), or an explicit
exclusion with a designed alternative. `base-excluded=112` is currently
an unaudited blackout list.

## Acceptance (owned by W5-0)

- A CENSUS: for every namespace/var the child tier exposes to agents,
  its host-tier disposition (resolved / capability-routed / excluded
  with reason). The census is a conformance gate, not a document —
  computed from source + the live host, red when a new agent-facing
  var lacks a disposition.
- A live drive on the host tier where an agent uses db, blob,
  messaging, and lifecycle completion end-to-end (the q29 drive rerun,
  but finishing with `:completed`).
- The W5-0 retirement preflight includes this gate green BEFORE any
  cutover drive.

## Provenance

Split out of `host-preflight-candidate-ranking-crashes.md` (that crash
is FIXED `16a040e6` and archived; this parity gap is what its probes
tripped over).

Triage 2026-07-23 — **DISSOLVES into the post-P4 census-to-zero/cutover unit**, which owns surface conformance and deletes fallback children/self-host.

## Edge-bundle metadata evidence — 2026-07-23

The same host-surface census must compare capability effect metadata, not only
symbol resolution. A direct registry probe found
`seon.agent.message/user :idempotent` and
`seon.agent.web/fetch :external`, but both `seon.db/db` and
`seon.db/transact!` had no `:seon.capability/effect`. The source vars declare
their effects; `register-host-capabilities!` currently copies arglists and
documentation for the database family but not that metadata.

Acceptance therefore includes exact source-to-installed effect parity for every
callable wrapper. Missing source metadata remains conservatively external;
metadata present at the source must not disappear during installation.

## 2026-07-24 alivegate2 evidence and R53 dissolution

The source-frozen default cluster at HEAD `e147c4217` produced the following
historical evidence while `complete` was still an effectful lifecycle entry.
Fresh agent `young-peaches-rescue` persisted plan root `wb51uk2ayolc`, wrote
three `:my.alivegate2.memory/*` entities in transaction `536871337`, and read
all three rows from the database in later turn `j271p5ey23ky` through successful
eval `r2c5z56nuuty` at transaction `536871352`.

The exact requested completion form then derived an executable JVM plan, ran,
and failed:

```clojure
(seon.agent.lifecycle/complete
 "ALIVE-CAVEAT SYNTHESIS: the later database read returned ...")
```

Eval `tzawq2zlmldj` is `:error`, `:seon.eval/ok? false`, with
`The message platform leaf is not installed.` The agent repeated the same form
in evals `r17ti5foq0rr` and `ih0h3v06ngto`; both failed identically. All ten
DeepSeek attempts are `:success` with response status `200`, so this is neither
provider failure nor model drift.

The retired effectful boundary explains the failure that R53 supersedes:

- `seon.host.context/register-host-capabilities!` installs the lifecycle
  wrappers with `(lifecycle/bind-leaf (host-lifecycle-leaf) database-leaf)`;
- `seon.agent.lifecycle/bind-leaf` binds `lifecycle/*leaf*` and `db/*leaf*`,
  but not `message/*leaf*`; and
- `complete-once` calls `message/message-transaction-for`, whose `leaf-fn`
  requires `message/*leaf*`. The JVM branch has no pod-services fallback.

The registered lifecycle wrapper therefore resolved and entered its owning
function, but its nested message dependency was unbound. That finding is real,
but the leaf-binding repair is not: owner ruling R53 dissolves the effectful
entry rather than widening lifecycle capability bindings.

The runtime contained the failure: run `t7kpxag6nt8x` closed `:no-forms` at
transaction `536871403`, run-holding process and agent current-run refs are absent, and
all ten turns are `:published/:done`. No synthesis message entity exists, so
the alive-caveat gate is honestly NOT cleared. Full evidence is in
`tmp/orchestrator/alivegate2-gate.log`.

## The DISCOVERY surface is also absent on the host tier — 2026-07-25

The census above is written as a *call* census. A source read of
`seon.host.context/register-host-capabilities!` (context.clj:635–1013) shows the
same blackout covers the surface an agent uses to FIND a function it has never
called. Every `::lib` the host registry provisions is enumerable
(`rg -n "::lib '" src/seon/host/context.clj`): `seon.ai.provider`, `seon.db`,
`seon.agent.message`, `seon.agent.lifecycle`, `seon.agent.home`, `seon.embed`,
`seon.agent.fs`, `seon.agent.shell`, `seon.agent.web`, `my.blob`, `seon.db.id`,
`seon.db.protocol`, `seon.schema`, `seon.ai.tokens`, `seon.content-hash`,
`seon.time`, `seon.repl.parse`, `seon.repl.parse.repair`, `seon.agent.ctx`,
`my.plan`, `my.kb`, `my.kb.shared`, `my.skills`, `seon.render.canvas`.

Three of the four discovery paths are therefore unavailable to an agent running
on the cluster JVM:

- `seon.agent.search/grep-graph` — the one literal search over `:seon.fn`
  source/doc/name — is not registered at all, and its only implementation is
  `src/seon/agent/search.cljs` (CLJS, deletion path).
- `my.ns/functions` / `full!` / `compact!` — the "what can I call in X?" API —
  is `src/my/ns.cljs`, `^{:async true}`, so `pure-block?` (context.clj:1060)
  excludes it from the portable slice, and it is absent from
  `host-toolkit-bindings` (context.clj:1298), so it never loads.
- `seon.embed/enabled?` is registered as `(constantly false)` and
  `seon.embed/search-pull` as a fixed `:user-input` error value
  (context.clj:729–739), so semantic recall over `:seon.fn/source` — the ranking
  input `seon.ai.generate-code/ranked-namespaces!` depends on
  (generate_code.cljs:190–226) — is hard-off on this tier.

The fourth path, the pushed `:namespaces` and `:function-menu` context blocks,
has no host caller either: every consumer of
`seon.agent.ctx.namespaces/namespaces-block` and `seon.agent.ctx.menu` is `.cljs`
(`seon/agent/turn.cljs`, `seon/agent/ctx/driver.cljs`, `seon/agent.cljs`,
`seon/web/datastar.cljs`, `seon/ai/generate_code.cljs`).

Consequence for the non-programming-agent demo case: on the surviving tier an
agent can only call what is already in its home requires
(`seon.agent.home/home-ns-require-specs`, home.cljc:95–112 — five namespaces) or
what it already knows by name. It cannot find a corpus function it has not seen.

Acceptance folds into the same census: the census must classify DISCOVERY
functions, not only capability functions, and the host-tier live drive must
include an agent locating a corpus function it did not previously know and
calling it.

## Three independent readers of the same code — 2026-07-25

The same function text reaches three consumers through three unrelated readers:

1. the pod boot indexer (`seon.client/ns-row`, client.cljs:1044) reads the
   admitted `SEON_PROGRAM_SOURCE_PATH` artifact and writes `:seon.ns`/`:seon.fn`
   rows — the corpus every context block and search reads;
2. the JVM host base reads `src/my` from the working directory at runtime
   (`toolkit-source-root`, context.clj:1017, a relative `io/file` + `file-seq`)
   and evals the blocks that pass the `pure-block?` regex; and
3. `seon.host.graduate/rebuild!` (graduate.clj:256) replays `:seon.fn/source`
   rows from the database into the registry.

(2) makes the run-holding process depend on a source tree at its CWD rather than on the
artifact or the database, and it is the only one of the three whose inclusion
rule is a regex over source text. Naming this here so the census can decide
which reader survives.

## R53 contract and acceptance — 2026-07-24

`seon.agent.lifecycle/complete` is not a capability. It is a pure,
schema'd terminal lifecycle value carrying the synthesis text and terminal
intent. Guarded eval returns that ordinary value; it performs no database
write, message transaction, platform operation, or leaf lookup on either tier.

The run-holding process driver owns the interpretation. When an eval result carries that
terminal value, the driver uses its existing canonical formless-reply delivery
path together with its terminal close transaction data to persist the
transcript message, result, run close, and custody release. This is driver
settlement, not a lifecycle wrapper exception.

Acceptance for this R53 slice is:

- No lifecycle message-leaf wiring remains in the host registry or lifecycle
  binding path; the capability census does not classify the pure terminal
  function as a capability.
- A real driver regression evaluates the exact completion form in an
  eval context with zero lifecycle/message/database capability bindings, then
  proves the driver's transaction writes the message and terminal result and
  publishes the turn `:published/:done`.
- One fresh default-cluster run ending in
  `(seon.agent.lifecycle/complete "ALIVE_GATE_FINAL: ...")` yields the
  transcript message entity and `:done` datoms. It is the final synthesis
  proof; alivegate2 remains the already-recorded plan, memory-write, and
  later-memory-read proof in `tmp/orchestrator/alivegate2-gate.log`.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
