---
type: research
status: active
date: 2026-09-08
tags: [research, docs, agent, runtime]
---

# Docs and skills landing — additive turns and shown text

## Scope and authority

Read AGENTS.md and
[the agent record and turn loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
end to end, including binding §10 and superseding §13–§15.
Read every rewritten architecture page and skill entrypoint end to end.
The skill-creator instructions were also read before rewriting skills.

Only documentation changed. No production code, protected lane file,
cluster lifecycle, or provider call was changed or invoked. AGENTS.md
edits are confined to vocabulary rows; the existing seon.id row is
byte-for-byte unchanged. Every commit names its document explicitly.

## Result

- The six architecture pages describe entity blocks, one render pair per
  entity schema, data-returning dir/doc, ordinary system turns, refresh
  over every distinct read form, persistent agent SCI contexts receiving
  base diffs, compaction as wipe, and live objects with stored shown text.
- [Agent runtime](../../../seon/architecture/agent-runtime.md) contains
  two Mermaid diagrams: additive context and the since-query diff.
- Six affected decision documents now follow the target; fresh per-turn
  forks and session curation are explicitly superseded.
- The handoff uses the binding lane gate and later PRD rulings.
- Vocabulary includes turn, system turn, read evidence, shown text,
  private layer, live result object, and history.
- Nine skill entrypoints retain verified source mechanisms and explicitly
  distinguish the PRD target from unfinished implementation. The seven
  assigned core skills were rewritten; the owned config and canvas
  entrypoints also had direct stale guidance and were corrected.

The document commits begin at `f2f7994a5`; the final architecture/skill
checkpoint is `1a9b669d7`. Two issue commits follow:
`bb455b8de` and `3974b0589`.

## Dependency and source evidence

Source was read directly; no library behavior was inferred from memory.
Line locations below identify the committed source inspected at
`028289d24` on 2026-09-08. Concurrent edits may move a Var; its name
is the relocation key, not a reason to infer a changed contract.

| Mechanism | Dependency/source and first-party boundary |
|---|---|
| Reusable SCI context, fork isolation, interning | `reference-code/sci/src/sci/core.cljc:330,345,260`; target integration remains the turn lane's responsibility |
| Temporal exclusion/inclusion | `reference-code/datahike/src/datahike/db.cljc:143`; `src/seon/db.clj:1547,1560,1574` |
| Read plans, revisions, semantic replay | `src/seon/db.clj:468,561,1177`; no claim that the whole-history algorithm had landed |
| Writer-authoritative transaction function | `reference-code/datahike/src/datahike/db/transaction.cljc:1152`; `src/seon/db.clj:2193` |
| Transaction input forms | `reference-code/datahike/src/datahike/api/impl.cljc:30` |
| Schema population and bridge | `src/seon/schema/edn.clj:303,348`; `src/seon/schema/datahike.clj:122,194,231,314` |
| Config derivation and application | `src/seon/schema/edn.clj:67`; `src/seon/config.clj:330,358,504,533`; `script/seon/fresh_operator.clj:2161` |
| Real fixture and bounded waits | `test/seon/test_support.clj:553,587,361,463,479` |
| Instrumentation | `src/seon/instrument.clj:685` |
| Flow lifecycle and workloads | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:168`; `flow/impl.clj:174`; `impl/dispatch.clj:82`; `src/seon/flow.clj:123` |
| REPL grammar and pair | `src/seon/repl.clj:246,315,323`; retained storage internals were explicitly not claimed as §15 support |
| Stable evaluation ids | `src/seon/id.clj:33,42,49` |
| Web route, package, and drain | `src/seon/render/route.clj:5`; `src/seon/render/web.clj:1937,2875,3633`; `reference-code/http-kit/src/org/httpkit/server.clj:321` |
| Honest generator output | `reference-code/malli/src/malli/generator.cljc:468,475` |

## Measurements and checks

At checkpoint `3974b0589`, the 25 changed documents, including the two
issues and excluding this landing note, total 173,191 UTF-8 bytes versus
363,931 bytes before `f2f7994a5`: 190,740 fewer bytes. The 23
architecture/handoff/vocabulary/skill files changed by +1,566/-5,017 lines.
Counts are dated measurements, not a maintained inventory.

Checks:

- `git diff --check`: passed.
- Relative Markdown links and balanced fenced blocks across those 25
  documents: zero missing targets or unbalanced fences.
- The two Mermaid blocks were checked as source; no browser-rendered
  Mermaid proof is claimed.
- `bin/seon status`: default alive, PID 14049, prepl 58925, HTTP 7994;
  process-root footprint 3.65 GiB, 576.49 GiB usable.
- MCP with default root/cluster selection: `(+ 1 1)` returned 2.
  A second read-only probe returned
  `{:seon.docs/connection? true :seon.docs/basis-t 536879166}`.
- HTTP GET `http://127.0.0.1:7994`: 200.
  These prove live reachability, not new turn behavior or browser repaint.

The bundled skill validator was run for all nine edited skills through
`uv run --with pyyaml`. All nine pass after retaining the supported
name/description frontmatter. The repository Markdown hook still rejects
those valid skill files under its generic document type/status rule.
The exact conflict and its reproduction are recorded in the issue below.
The frontmatter correction checkpoint is `d2ad98c58`; the byte counts
above remain the explicitly dated pre-correction measurement.

## Remaining authority boundaries

- [Skill frontmatter validators disagree](../../../seon/issues/skill-frontmatter-validators-disagree.md):
  repository required-fields errors conflict with the bundled validator.
- [Remaining superseded instruction bodies](../../../seon/issues/turn-prd-still-conflicts-with-unowned-instruction-bodies.md):
  the AGENTS.md body outside vocabulary and supporting skill references
  are outside this lane's allowed edit paths.

The first two Markdown hooks also reported that the concurrently moved
config-apply issue file was absent at its old path. Later document hooks
completed without that error. No other lane's file or session was altered.
The issue index is left to the orchestrator as instructed.

## Gate

Bare `bin/test` ran against snapshot
`e1dc7d2a073863448a97d9f4c7460ae00a9a3c9e`, including concurrent working-tree
edits, under `tmp/test-runs/run.0nlmFQ`. With no recorded green basis it
selected 73 platform and 1,407 bulk tests. It reached bulk execution and
reported this boundary at 2026-09-08T18:53:26Z:

```text
WORKER-GLOBAL STATE CHANGED by
seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence
worker=pool-2
:seon.test.runner/drift-removed
["seon.ai/complete" "seon.bootstrap/next-entry" "seon.sci.eval/evaluate"]
:seon.test.runner/drift-removed-count 3
```

This re-observes the existing
[instrumentation-drift issue](../../../seon/issues/a-platform-test-leaves-its-worker-stripped-of-every-contract.md).
It does not establish which concurrent edit caused it. No production
file, foreign session, or foreign gate was changed to investigate further.
Following the assignment's stop-at-shared-breakage boundary, this lane
sent TERM to its own launcher PID 16682. The launcher's reap backstop
terminated coordinator PID 45137; recorded runner exit 137, launcher
exit 143, and retained root are cancellation evidence, not a completed
suite verdict. A subsequent process-table check found none of its nine
worker PIDs remaining.

The focused namespaces (`seon.repl-test`, `seon.db-test`,
`seon.schema.datahike-test`, `seon.render.block-test`,
`seon.test-support-test`) and separate `--platform` invocation were not
run. No `--all`/`--full` invocation or worktree retry occurred. No owned
background shell remains. Documentation validation and live reachability
checks above passed; the required correctness gate is incomplete.
