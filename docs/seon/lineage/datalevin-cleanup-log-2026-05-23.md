---
type: research
status: completed
tags: [research, cleanup]
---

# Datalevin → Datahike cleanup log (2026-05-23)

Sweep of current-state docs to remove stale Datalevin references. The migration to Datahike is complete; current-state docs now reflect that reality.

## Word-choice policy applied

- Generic concept (a Datalog database, Datalog queries, EAV/Datalog model) → **"Datalog"**
- Current product specificity (config, deps, namespace names) → **"Datahike"**
- Historical migration framing in current-state docs → just "Datahike", no story
- Skill name `/datalevin`, PRD file paths like `prds/flow-datalevin-writer/`, issue filenames like `raw-datalevin-conn` → retained as **stable links**, with explanatory notes where the name might mislead

## Architectural facts now in the docs

- Datahike: embedded Datomic-style EAV on LMDB with bitemporal history
- Embedded in the Seon JVM. No separate process. No port 8898. No `data/datalevin/server.pid`.
- Connection lifecycle managed via Integrant
- `(user/reset)` restarts the system normally
- Datomic-compatible Datalog queries; same EAV model; same pull API
- Sole access through `seon.db`; direct calls go via `datahike.api`

## Files updated

| File | Status | Notes |
|---|---|---|
| CLAUDE.md | done | Process Architecture rewritten (was the most factually-broken section); 2 `/datalevin` skill-name refs retained with notes |
| ORCHESTRATOR.md | done | "Separate Processes" section rewritten as "One Process, One Database" |
| AGENT.md | done | Process Architecture rewritten; 1 `/datalevin` skill-name ref retained |
| docs/seon/namespaces.md | done | Namespace inventory was stale — `seon.db.datalevin.*` and `seon.ai.datalevin` listed namespaces that no longer exist. Replaced with current `seon.db.datahike.*` namespaces (conn-process, system, flow, schema, tx-bus) and dropped removed `seon.ai.datalevin` row |
| docs/seon/vision/m1-reliable-runtime.md | done | Rewrote "Datalevin as a separate process" paragraph to describe embedded Datahike + on-disk LMDB |
| docs/seon/vision/m2-trustworthy-data.md | done | Word replacements throughout; one issue-filename ref retained |
| docs/seon/vision/biggest-ideas-2026-05-23.md | done | Section 7.1 "Datalevin vs Datahike" rewritten as "Datahike is the database (resolved)" — that section was meta-commentary about THIS cleanup |
| docs/seon/vision/full-scope-synthesis-2026-05-23.md | done | Two verbatim setups from milestones updated; one verbatim quote from commit 924820e left intact (historical); note added at the synthesis-author paraphrase mentioning the migration |
| docs/seon/vision/index.md | done | Rewrote "The Right Database" section — bitemporal history now mentioned explicitly |
| docs/seon/vision/m6-eval-pipeline.md | done | Word replacements |
| docs/seon/vision/m5-observable-system.md | done | Word replacements ("Datalog query interface", "Datalog query results") |
| docs/seon/vision/capabilities/database-platform.md | done | Full rewrite — was describing separate-JVM model |
| docs/seon/reference/separate-jvm-exploration.md | **left as-is** | Reference / historical exploration doc with explicit 2026-05 migration notes already in place |
| docs/seon/vision/m7-namespace-as-process.md | done | Word replacements |
| docs/seon/vision/m3-convention-uniformity.md | done | Including removal of stale `ai/datalevin.clj` reference |
| docs/seon/vision/full-framing-found-2026-05-23.md | **left as-is** | All remaining mentions are accurate descriptions of past commits or verbatim historical quotes |
| docs/seon/orchestrator/prds.md | **left as-is** | All remaining mentions are PRD file paths (`prds/datalevin-migration/`, `prds/flow-datalevin-writer/`) — real on-disk PRDs |
| docs/seon/vision/prior-art-credits-2026-05-23.md | **left as-is** | The single mention already correctly notes "post-2026-04 migration from Datalevin" |
| docs/seon/vision/m4-discoverable-codebase.md | done | "Datalevin refs" → "Datahike refs"; "Datalevin-backed specificity resolver" → "graph-backed specificity resolver" |
| docs/seon/vision/capabilities/validated-writes.md | done | Body updated; PRD path ref retained |
| docs/seon/vision/capabilities/unified-context.md | done | replace_all |
| docs/seon/vision/capabilities/runtime-tracking.md | done | replace_all |
| docs/seon/vision/capabilities/repl-eval-pipeline.md | done | replace_all |
| docs/seon/vision/capabilities/namespace-persistence.md | done | replace_all |
| docs/seon/vision/capabilities/inter-agent-messaging.md | done | |
| docs/seon/vision/capabilities/function-discovery.md | done | |
| docs/seon/vision/capabilities/data-contracts.md | done | replace_all |
| docs/seon/vision/capabilities/code-graph.md | done | replace_all |
| docs/seon/vision/prior-art-agents-and-evolution-2026-05-23.md | done | "Datalevin/Datahike" → "Datahike" |
| docs/seon/vision/m8-autonomous-agents.md | done | |
| docs/seon/vision/capabilities/test-isolation.md | done | Fixture name corrected: `with-test-datalevin` → `with-test-db` / `with-test-db-fixture` (the actual current fixture names per `test/seon/test_utils.clj`) |
| docs/seon/vision/capabilities/repl-first-development.md | done | |
| docs/seon/vision/capabilities/flow-topology.md | **left as-is** | The single mention is a PRD path ref (`prds/flow-datalevin-writer/prd`) |
| docs/seon/vision/capabilities/data-explorer.md | done | "Datalevin/Datalog equivalents" → "Datahike/Datalog equivalents" |
| docs/seon/vision/capabilities/agent-isolation.md | done | |
| docs/seon/components/flow-topology.md | **left as-is** | Already has explanatory migration note |
| docs/seon/components/agent-system.md | **left as-is** | Already has explanatory migration note |
| docs/seon/architecture/decisions/006-separate-jvm.md | **left as-is** | ADR — historical decision record with explicit migration note already present |
| docs/seon/architecture/datahike-reactive.md | **left as-is** | Already has historical-name note for the `datalevin-reactive` PRD reference |
| docs/seon/_dashboard.md | done | "older JVM substrate (Datalevin + ...)" → "JVM substrate (Datahike + ...)" — the prior framing was wrong; the JVM substrate has been on Datahike for months |

## Smell observations

Several places where the docs are wrong in ways beyond the Datalevin issue:

- **`docs/seon/namespaces.md` is significantly out of date.** It listed the entire `seon.db.datalevin.*` namespace family that no longer exists. It also listed `seon.ai.datalevin` (also removed). The actual `seon.db.datahike.*` namespaces (`conn-process`, `system`, `flow`, `schema`, `tx-bus`) had no rows. Frontmatter `updated: 2026-03-11` predates the migration. Likely many other namespace inventories elsewhere are stale too.
- **Issue and PRD links are unverified.** Many `[[orchestrator/issues/...]]` and `[[prds/...]]` links resolve to historical filenames (`raw-datalevin-conn`, `flow-datalevin-writer`). Whether those files still exist on disk wasn't verified in this sweep; the names were retained as stable links per user instruction.
- **`/datalevin` skill name is misleading.** Three docs reference a skill literally named `datalevin` that covers the Datahike-backed `seon.db` API. Adding "skill name retained" notes is a workaround; the underlying inconsistency suggests renaming the skill itself in a separate pass.
- **`namespaces.md`'s line for `seon.ai.gemini`** is fine, but `src/seon/ai/deepseek.cljs` exists in the source tree and isn't listed.

## Awkward spots in the rewrite

- **CLAUDE.md "Process Architecture" rewrite** required removing the entire two-process narrative. The new section is shorter and arguably less interesting — there's no longer a story about "data survives because Datalevin is a separate JVM". The actual property (data persists because LMDB is on disk) is less dramatic. I leaned into the simplicity rather than embellishing.
- **m1-reliable-runtime.md** opens with a "Datalevin survives application crashes" hook that doesn't survive cleanly when Datahike is embedded. I rephrased to "the on-disk Datalog store survives application crashes" — accurate but less punchy. The whole scenario assumes a separate database process that "survives Seon restarts"; embedded Datahike makes this scenario slightly weaker. The fundamental story (data is on disk so restart is fine) holds.
- **vision/index.md "The Right Database"** previously had a clean bullet list including "Separate process — Survives Seon restarts; TCP client connection on port 8898". I replaced it with bullets about embedded LMDB + bitemporal history. The bitemporal angle is a stronger pitch but doesn't recover the "operational independence" framing.
