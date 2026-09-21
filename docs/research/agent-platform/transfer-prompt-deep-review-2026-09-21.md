---
type: reference
status: transfer prompt for a fresh deep-review session (2026-09-21)
created: 2026-09-21
tags: [agent-platform, review, transfer-prompt]
---

# Deep review of the agent-platform plan: find the obvious wins we missed

You are a principal engineer doing an independent, in-depth review of the Seon
agent-platform plan at `/Users/sean/src/seon` (branch `steward-platform`). The
plan has been through: Opus deletion audits → Fable first pass → astra reviews →
Codex integration → one Fable second-perspective pass. Your job is different from
all of those: assume the plan is still too timid and too big, and hunt for the
obvious wins everyone missed. The owner reads your output personally.

## The owner's words, which bound everything

- "agents who are not researching in reference-code to see if the work even has
  to be done or if we can just modify one of our dependencies which we own and
  maintain … the codebase has become a bloated mess just to achieve all tests
  passing. There has not been a serious concern for even algorithmic analysis."
- "We basically have a global accessible system in the form of databases and
  caches AND can maintain the appearance of separate systems all running in the
  same JVM due to immutability and forking. These operations are cheap and very
  fast. The entire system should be extremely fast and responsive."
- "Clojure projects tend to be 10x smaller than conventional software. I want to
  see that represented in my codebase." (Today: src 90,162 lines, test 98,985,
  schemas 14,256. The plan's specs sum to ≈55,000 src — a 40 % cut, not tenfold.)
- "I don't want everything to be gated on tests running to completion … We are
  breaking shit temporarily and ripping out a lot of bad code; if the plans are
  good we repair it and write better tests to replace the garbage."
- "Everything is just data being processed. When and how we process the data is
  the most important aspect to this system."
- SECONDS, NOT MINUTES: anything over ~2 s is explained by algorithm; over 10 s
  needs the owner. A fork is a branch pointer. A no-change request is two commit
  ids compared. An edit's cost is proportional to the changed declarations.

## Read first, end to end, in this order

1. `AGENTS.md` (the laws: §2 the five design laws; §3 data and schema).
2. `docs/prds/agent-platform/plan/README.md` — the integrated plan (four cuts,
   shared contracts, the order inside the cuts, the no-test-gate rule, size honesty).
3. The eight specs in `docs/prds/agent-platform/plan/lane-*.md` (A1 projection,
   A2 Datahike, B1 publication/operator, B2 SCI/turn/render, B3 errors/tasks/config,
   B4 tests, C1 profiling, D1 isolation/merge/write-back). Each has a §0 "for the
   owner", a data-flow table, a reading list of `reference-code/` blocks, a REPL
   protocol, ordered commits, "better than the floor" probes, tests, size target.
4. `docs/research/agent-platform/fable-second-perspective-2026-09-21.md` — what
   the last pass changed and why; its "unresolved decisions" table.
5. `docs/prds/agent-platform/research/synthesis-2026-09-21.md` and
   `durable-goals-and-rulings-2026-09-21.md` (the mission, 55 targets, 89 product
   rulings, 36 retired directions — do not propose a retired direction).
6. The six `deletion-audit-*.md` and seven `data-pack-*.md` in that research
   directory (verified citations and counts), and the astra `review-*.md` notes
   under `docs/research/agent-platform/`.
7. `docs/prds/agent-platform/landing/fresh-start-2026-09-21.md` — the current
   live baseline (default was reset from zero; gates not yet green; three live
   error signatures).

## Hunting grounds (where I would look first)

- **What survives is still large.** After the cuts: `turn.clj` ≈3,700, `render/web.clj`
  ≈2,500, `sci/eval.clj` ≈2,500, `fn.clj` ≈2,300, `db.clj` ≈3,300,
  `fresh_operator.clj` ≈900, `bin/seon-hook` ≈1,500, `ai.clj` 1,682 untouched,
  `print.cljc` 1,377 untouched, `bootstrap.clj` 932 → 120 conditional. Ask of each:
  what would this be as ONE function over immutable data, and which library
  already does the rest?
- **The reset from zero** (≈5–6 min): clj-kondo complete analysis 15 s (serial;
  `:parallel` groups by directory — probe per-file groups in our fork), population
  compile 13 s, one Datahike transaction of 107,049 ops 36 s (attribute the
  writer's phases; `load-entities` is a diagnostic lower bound), issue indexing 6 s
  (notes parsed in src — B3 deletes), unresolved-callers readback 4.9 s (probably
  equal to analysis-time rows by construction). Target ≤ 60 s. What is the
  algorithm, not the path?
- **Read currency and the since-diff**: 407 of 435 retained reads were
  program-acquisition pulls (now ruled out of evidence). What remains, and does
  the system turn need any of it once acquisition is excluded?
- **The forks we own** (`reference-code/{datahike,konserve,clj-kondo,malli,sci}`):
  `:db.type/any` admission, pull's silent 1,000 cut, GC dry-run, an expected-basis
  argument on `merge!`, `default-errors` keyword types, clj-kondo cache staleness
  and `<stdin>` writes, per-file `:parallel` groups. Each is a few lines in a fork
  we control; find the ones nobody proposed.
- **Cluster-per-task (D1)**: a full cluster start (config, recovery, graphs) per
  task versus a branch plus the agent's retained SCI fork hosted by the shared
  cluster. The 2026-09-19 ruling chose cluster-per-task; if the measured start is
  seconds, propose the smaller shape with its guarantee stated.
- **The test corpus** (99 K lines, 2,136 deftests at 46 lines each): the plan
  deletes machinery tests and defers "hoisting" to agents. Is there a fixture
  shape that makes most setup vanish (one canonical branch + ctx fork per
  namespace, seeded once)?
- **Schemas (14 K lines) and config (92 dials → ≈50)**: what does Malli's own
  `m/walk`/registry make unnecessary in `schema.clj` (3,904 → 3,550 is a small
  cut for a "read the compiled registry" design)?
- **Eleven render paths, six clipping sites, one law** (AI render functions and
  the value renderer are the only clipping spots; HTML never clips).
- **The operator and hook**: every command is one prepl request; what of
  `fresh_operator.clj` and `bin/seon-hook` is still not that?
- **Proof gates that can be dissolved by a construction** (the last pass found one:
  per-context wrapper installation replaced a two-generation proof). Find the next.

## Method and constraints

- Read the vendored library seam at the current gitlink before judging any
  mechanism; cite `file:line`. Do not trust a spec's citation: open it.
- You may run read-only evaluations against the live `default` cluster through
  the seon MCP `eval_clj` tool (mode `jvm`, `read_only true`), one at a time; record
  each form and value. No `bin/test`, no `bin/seon`, no resets, no suites, no JVM
  launches. A Codex session may be editing `src/`/`test/` concurrently: check
  `git status` and `bin/codex-agent status` first and leave source, tests, schemas,
  `AGENTS.md`, operator config and the branch alone.
- For every win: what data, when computed, where carried, what triggers
  recomputation, what is proportional to what; what it DELETES (file:line, count);
  what guarantee is retained and where; the probe that decides; risk. Rank by
  lines deleted per unit of proof required.
- Distinguish deletion from moving code, from a new registry or cache, and from
  keeping the old mechanism behind a new name. Challenge complexity that a
  reviewer added as readily as complexity in the first draft.
- No invented nouns; Clojure, Malli, Datahike, SCI, core.async, clj-kondo terms
  only. Use verify / probe / falsify. Do not propose a retired direction (goals
  note §5). Do not silently change a settled owner requirement; for a genuinely
  unresolved architectural choice present three concrete options, simplest viable
  first, with guarantee, cost and what is given up.

## Deliverable

1. A ranked wins note at
   `docs/research/agent-platform/deep-review-wins-<date>.md` (≤ 300 lines,
   tables): each win as above, plus the wins you looked for and did NOT find
   (with the evidence), and the three largest remaining unknowns.
2. Integrate the justified wins directly into the owning plan sections in
   `docs/prds/agent-platform/plan/` — no appended review log, no second plan,
   the README only where a shared contract or the order changes — and commit
   path-limited (`git commit --only -- docs/prds/agent-platform/plan docs/research/agent-platform/<note>`).
3. Report to the owner: sober, broken things first, the three biggest wins with
   numbers, the decisions that are his with priced options, what you could not
   verify.
