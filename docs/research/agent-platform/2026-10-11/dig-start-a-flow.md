---
title: "DIG: From a commit to a running agent Flow"
type: research
status: fact-gathering for design phases
---

# From a commit to a running agent Flow: current path, history, and open questions

## 1. Current code path (symbols and reads/writes)

| Stage | Functions | Reads | Writes |
|---|---|---|---|
| **Branch creation** | `seon.cluster.registry/fork!` (line 357) and `branch!` (line 210) | roster (Datahike branches), from-branch data | new branch head, fork entity |
| **Agent execution setup** | `seon.cluster.agent/acquire-context!` (line 1045), `seon.turn.world/world` (a74fd3031, line 46) | connection, agent id, branch, task | execution handle (cached SCI ctx, projection state) |
| **SCI environment fork** | `seon.sci.eval/base-ctx` (line 3633), `/copy-base-ctx` (line 3601), `/derive-overlay-ctx` (line 3624) | loaded program commit, cluster base (cached), program identity, dials | base context (cluster-wide), overlay fork (per-agent), program snapshot |
| **Graph definition** | `seon.cluster.agent/graph-definition` (line 564) | agent id, cluster handle | pure data: mailbox/turn/schedule procs, connections |
| **Graph start** | `seon.flow/start-graph!` (calls Flow's `create-flow`, `start`, `resume`) | graph definition, executor, projection state | Flow graph, started promise, executor |
| **Arming** | `seon.cluster.agent/arm!` (line 1251) and `arm-graph!` (line 1302) | connection (agent entity id), execution, routing | armed entry (graph, started, executor), routing table, wake channel, primed mailbox |

## 2. Reference code seams (library ownership)

| Concept | Pinned | Guarantee | Cost |
|---|---|---|---|
| `datahike.api/branch!` | ([fork](../../../.git/modules/reference-code/datahike), `versioning.cljc:233-235`) | idempotent by roster; lost race reads as success | 17 ms + fork-entity tx |
| `datahike.api/commit-as-db` | — | immutable snapshot from commit id | 0 (no materialization cost) |
| `datahike.api/listen` | — | registers observer at the seam in `seon.sci.eval/watch-program-identity!` | per-transaction callback |
| `sci/fork` | ([fork](../../../.git/modules/reference-code/sci), `fork.cljc`) | independent mutable state (ns map, Var overrides) | 1 ms, <1 MB (R4: bdf7411b5) |
| `sci/init`, base context building | — | interprets source, assembles bindings | 24 ms (cold), 7.6 ms (cached) |
| `clojure.core.async.flow/create-flow` | — | pure data, no threads allocated | instant |
| `flow/start` + `flow/resume` | — | allocates procs and threads; stateful executor | 10-14 ms, 15 MB |
| `konserve.core/get`, `update` | ([fork](../../../.git/modules/reference-code/konserve)) | key-value store operations | inside broader calls |

## 3. History: past attempts and why they changed

| Attempt | When | Why removed / changed | Lesson |
|---|---|---|---|
| **seon.plan (task steps)** | until cea904e92 | Replaced by runtime-follows-db; steps were brittle control flow | Design the data flow, not the control |
| **Per-branch armer graph** | until c6113d2f3 (R1 design) | `db<->flow` starts all graphs directly; eliminates duplicate work | Compose primitives (branch + graph) at one level |
| **Fences and guards** | reverted c53c9306e | "NO GUARDS OR FENCES": protections hide root causes (CLAUDE.md 2026-10-11) | Fix the system design, not symptom protection |
| **Context holding a database value** | 48523bb20 | `lazy-root` reopens commit on first call, not closed-over value | Don't capture what changes; derive on demand |
| **Lazy acquisition** | 4f81411fa | Cached acquisition bases; `acquired-database?` compares by revision not value | Content keys cache entries, not identity |
| **SCI overlay fork cost** | bdf7411b5 (R4) | Cached overlay fork sources by content; miss was 388 ms, now 1.28 ms | Cache at every derivation with content keys |

## 4. Measured numbers (R1 design, c6113d2f3; start-a-flow benchmark pending)

| Leg | Measured | With R4 | Lower bound | Note |
|---|---|---|---|---|
| Fork | 47 ms, 5.7 MB | same | 45 ms | branch pointer + fork-entity tx |
| Attempt + agent rows | 32 ms, 0.45 MB | same | 30 ms | one main tx (goal: R1) |
| SCI environment (cold branch) | 388 ms, 399 MB | 1.28 ms (warm fork) | 1 ms | R4's overlay cache hit |
| Contract references (reach miss) | 98 ms / 1,072 calls | — | — | CACHE-2 card needed |
| Proof reach (fresh fork) | 70–80 ms, 127–139 MB | same | <5 ms | CACHE-1: key by program content |
| **Launch total** | **190 ms, 70 MB** | **160 ms, 70 MB** | **90 ms, <15 MB** | Lower bound via R1 + CACHE-1 |
| **First context** | 94–177 ms, 148–354 MB | same | 2–5 ms | Reach graph + contract refs |
| **Start to first context** | **270 ms, 210 MB** | **240 ms, 210 MB** | **95 ms, <20 MB** | Lightness proof: `starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` |

## 5. Cache inventory: what cannot hit on a fresh fork

| What | Today | Path | Card |
|---|---|---|---|
| Base context (cluster-wide, keyed by commit) | hits main's entry | start, turn | — |
| Overlay fork (per-agent, keyed by program basis) | hits on fork with unchanged program | start, turn | **CACHE-1 needs the miss to hit** |
| Proof reach digests | misses 70–80 ms on fresh fork, same program | start, turn | **CACHE-1**: key reach by program content |
| Contract references | no card | inside reach miss | **CACHE-2**: key by contract form digest |
| Task basis, program identity | cached by commit | start, turn | — |

## 6. Open questions and contradictions

1. **What blocks CACHE-1 from hitting on a fresh fork?** Reach is keyed `[content-revisions tests]` (r1-db-flow-design.md line 180), so unchanged program should hit. Measure the actual key and miss cause on default.
2. **Reach miss under `d/with`?** Probes and tests using `d/with` overstate cost (line 180); verify if the lightness proof uses committed branches or `d/with`.
3. **Contract references: per-reach or per-contract?** CACHE-2 (line 181) proposes keying by contract form digest, proportional to changed contracts. Does `seon.schema/contract-references` walk only reached symbols, or is the miss on the whole projected schema?
4. **World construction cost unrecorded.** W1 (a74fd3031) introduced `seon.turn.world/world`; target under 5 ms but never measured on the start path.
5. **Parallel test members bounded pool timing.** R1 table (line 199–212) shows 3–4× wall gain at 4 members, but requires per-member cost under 50 ms (CACHE-1 + CACHE-2). What is the actual per-member p90 after caching lands?
