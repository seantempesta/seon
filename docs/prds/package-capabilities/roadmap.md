---
type: prd
status: active
tags: [prd, agent, architecture]
---

# Package capabilities roadmap — agent-built benchmark-parity APIs

## Owner rulings (2026-07-21)

- **Clusters are fully independent.** Shared add-only packaging is rejected:
  add-only cannot represent two clusters wanting different versions of one
  package, and the shared tree is an artifact-digest build input. Every
  cluster boots from the same compiled base artifact, then diverges — its own
  database, authored code corpus, and package tree under
  `data/clusters/<name>/packages/`. Bun's global content-addressed cache stays
  shared (download/disk dedupe is safe; resolution trees are not).
- **Agents do the implementations.** Each capability is delivered by a Seon
  agent given a goal-shaped task ("we need browser testing to achieve this
  goal — install the package and build out the API with functions and
  schemas"), not by a human writing wrappers. We learn from their friction to
  improve the API and teaching; a final quality pass later is acceptable.
- **Goal-oriented testing over checklist testing.** The acceptance for each
  capability is a benchmark-shaped goal achieved end-to-end, plus robustness
  gates. Correctness gates, not style ([[../../seon/architecture/laws]];
  scorers gate correctness).

## Outcome

Agents in any cluster can install and use the JS packages that agentic
benchmarks require, through `my.*` capability functions with Malli schemas
that they largely authored themselves. Every capability is proven by a goal
drill, a hostile-input gate, and a restart-survival gate. The buildout
trajectories are captured as data-quality material for the generate-code
pipeline (trustworthy trajectories: real goals, real friction, real fixes).

## Dependencies and sequencing

- **Phase 0 (now, pre-cutover):** operator installs pins at the repo root;
  agents author `my.*` wrapper functions + schemas against them from inside
  today's Bun execution children. Children are process-isolated, so a hung
  browser or a crashing native lib kills only that agent's child — this
  phase is safe before the sci-host containment hardening lands.
- **Phase 1 (post-U13):** `my.pkg/install` exists; agents perform installs
  themselves into their cluster's package root (staged-then-atomic swap,
  config-fact policy gate, provenance). Every Phase 0 capability re-proves
  under per-cluster installation; the install drill becomes part of each
  gate.
- The npm **execution-host decision** (pod vs per-cluster disposable Bun
  package host) is an open owner decision from
  [[../sci-execution-runtime/research/audit-benchmark-pkg-readiness-2026-07-21]].
  Phase 0 does not depend on it (children isolate). Phase 1 browser/native
  work lands wherever that ruling places it.
- Each unit begins with a dependency ledger: exact package pin, its source
  mirrored or vendored per repo policy, and the first-party call sites that
  demonstrate the idiom.

## Capability units

Each unit = one goal-shaped agent task + gates. Model assignment per budget
posture: planning by kimi-k3 (with fallback once shipped), implementation by
DeepSeek workers. One capability at a time per cluster; units are
independent across clusters.

| # | Capability | Package (pin at unit start) | Goal drill (agent-facing task shape) |
|---|---|---|---|
| P1 | HTML extraction | cheerio | "Given these 5 saved pages, extract each product table into schema'd maps and store them as facts a later turn queries." |
| P2 | Browser | playwright-core (+ one shared read-only Chromium) | "This form-behind-JS site: navigate, fill, submit, capture the confirmation number and a screenshot blob." |
| P3 | PDF | pdfjs-dist | "Extract the tables from these 3 PDFs (one malformed) into facts; the malformed one must yield an error value, not a crash." |
| P4 | Spreadsheets | xlsx (SheetJS) | "Read this workbook, compute the summary the task asks for, write a new sheet back." |
| P5 | Word docs | mammoth | "Convert these .docx to text, answer the question that needs cross-referencing both." |
| P6 | Images | sharp | "Resize/convert these images and produce a contact sheet; reject the corrupt one as a value." |
| P7 | Already-native teaching | none (bun:sqlite, fetch, Bun.spawn) | No install — unit is teaching + schemas only, proving the capability docs suffice. |

Unit template (every capability):

1. Agent receives the goal, the package name, and the teaching contract
   (specs first → dependency fns → main namespaces; any write order; last
   version wins).
2. Agent builds `my.<capability>` functions with Malli schemas; docstrings
   are true current-state (they render into context).
3. Gates (correctness, run by the owning test surface, never a new runner):
   - **goal gate** — the drill completes end-to-end from a fresh turn;
   - **hostile gate** — malformed/oversized/hanging input returns a
     `:seon/error` value within its deadline; no crash, no leaked process
     (browser: kill a hung page at deadline without leaking Chromium);
   - **restart gate** — cluster restart; the capability still works and any
     stored facts survive;
   - **Phase 1 adds: isolation gate** — concurrent installs in two clusters
     don't interact; same package different versions in two clusters both
     resolve; a failed/hostile install (broken postinstall) leaves the
     previous tree intact and never touches the pod, host, or another
     cluster; artifact digest unmoved.
4. **Learning capture** — the orchestrator reads the full trajectory and
   files one issue note per friction point (wrong/missing teaching, error
   envelope that didn't steer, API gap, schema fight). Fix-tool-smells
   applies: dispatch fixes immediately when understood. The trajectory
   itself is retained as data-quality material.

## What this PRD does not own

- The `my.pkg/install` mechanism, per-cluster package roots, and the
  execution-host ruling — [[../sci-execution-runtime/roadmap]] U13.
- The generate-code scheduler/worker-context machinery —
  [[../generate-code/roadmap]].
- Benchmark harness changes (amd64 overlay, `:open` web policy for bench
  clusters) — recorded in the benchmark-pkg audit, owned by src-inspect-ai
  work.

## Success measures

- All P1–P7 goal/hostile/restart gates green in one cluster (Phase 0 exit).
- P1–P6 re-proven with agent-performed per-cluster installs plus the
  isolation gate (Phase 1 exit).
- ≥1 previously-failing src-inspect-ai bench family newly passing with the
  capabilities in place, measured before/after.
- A dated friction ledger exists per unit with each item fixed or filed —
  this is the "learning from them to improve the API" artifact.
