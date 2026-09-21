---
type: reference
status: brief for the Fable writers and the astra reviewers, 2026-09-21; deleted at the clean write
created: 2026-09-21
tags: [agent-platform, brief]
---

# Brief for writing the agent-platform plan and specs

## The owner's words today (2026-09-21), verbatim

- "agents who are not researching in reference-code to see if the work even
  has to be done or if we can just modify one of our dependencies which we
  own and maintain … the codebase has become a bloated mess just to achieve
  all tests passing. There has not been a serious concern for even
  algorithmic analysis -- does this work need to be done or can we access it
  from earlier? We basically have a global accessible system in the form of
  databases and caches AND can maintain the appearance of separate systems
  all running in the same JVM due to immutability and forking. These
  operations are cheap and very fast. The entire system should be extremely
  fast and responsive."
- "The goal is to get to the point where the system can improve itself."
- "identify all the code we can delete -- be it test code or code that's
  duplicating behavior (in a worse way) than what the libraries are doing or
  what we are redoing (duplicate code paths, duplicate caches)."
- "a plan that will aggressively cut the code and provide clear instructions
  to the agents implementing it on the reference code to build against
  (files and ideally line selections or blocks…). I want them to read and
  understand good clojure code relevant to what they are implementing before
  they are implementing it. I want them to use the REPL to test ideas,
  inspect data and look at perf. I want them to find even better ways to do
  the fixes and refactoring and to use their superior coding abilities to
  what a normal human would do … I don't want everything to be gated on
  tests running to completion especially when so many of the tests are part
  of the problem."
- "deeply cut in all areas (both source and tests) and then to fix
  everything at the same time. If this experiment doesn't work we can always
  revert back to git."
- "Rather than have a 'cut' prd let's call it agent-platform … it's a clean
  plan for going from where we are now to where we want to be. Cuts and new
  features and refactors all of it."
- "I don't expect these plans to be perfect on first go and I want astra
  agents to improve the plans primarily by better algorithmic selection but
  also fixing errors. That's where we keep making mistakes. Everything is
  just data being processed. When and how we process the data is the most
  important aspect to this system."
- "The architecture should cover the system at a high level including
  references to reference-code so agents can easily find reference code to
  hold in context when implementing code and then the PRD folders should
  have all the details needed to do an implementation."
- Profiling: "we are already instrumenting all the functions so we should be
  able to cheaply profile them … identify chains of functions that are
  executing and the cost and we can then surface automatic issue creation
  for agents to profile and fix." "extremely fast and efficient … couldn't
  have it become the bottleneck or create contention with the database."
  "If an agent forks and overwrites a function … it may have the same
  function symbol but it's not the same function. So we need to have a hash
  or some way to understand what we are measuring and then record it to the
  correct version."

## Decisions taken today (do not re-decide)

- Opus researches; Fable writes the first pass; astra reviews (primary lens:
  better algorithmic selection of when and how data is processed; then
  errors); Fable clean-writes; old docs are DELETED (git is the archive);
  a fresh session implements. Lane concurrency is decided after the plan.
- Deletion scope: everything old except the 369 live issue notes, which are
  audited against the specs' deletion lists and kept only where the subject
  survives. Unreferenced and history-only reference-code repositories are
  unvendored (forks pushed first).
- Tests: the plan deletes process-machinery tests and duplicate classes,
  converts the time escapes, fixes the fixture; hoisting the repeated setup
  is the namespace agents' second task class. First live agent task class:
  contract coverage.
- Ownership seams between lanes: A1 (Malli: schema.clj, schema/*, instrument,
  call_preparation, test/arm, fn/schema_shape) ↔ A2 (Datahike: db.clj,
  schema/datahike, store, registry, blob, seon.db.edn) meet at
  `seon.db/carried-projection` (`db.clj:1219`). B1 (fn.clj, fn/*, program,
  cluster.clj publication sections, cluster/source, operator*, fresh_operator,
  bin/seon, bin/seon-hook, id.clj). B2 (sci/*, turn.clj, run.clj rename,
  cluster/{agent,wake,message,prompt}, flow, oversight, render, render/*,
  print, repl, ai, my/* except my/issue). B3 (error*, issue→task, plan.clj
  lifecycle, config, effect, search, env, bootstrap, the kind/class sites).
  B4 (test.clj, test/* except arm.clj, test_support, bin/test*, seon.test*
  schemas). C1 profiling lands after A1 (needs a per-function content digest
  on every function row, which B1's indexer writes — today the row digest is
  the FILE digest for file-indexed functions: data-pack-c1 §2).
- Errors: D12/D13 as ruled. The ~179 inline error guards are deleted, never
  replaced by a predicate; the reading function's contract declares its
  union; the wrapper validates. `:seon.error/kind` is not in the live schema
  (data-pack-b3 §2): the 799 sites are a pure code cut.
- Read currency: one mechanism, Datahike's cache context; pull never silently
  cuts (fork default); `:db.type/any` admitted (no indexed EDN attribute
  exists: data-pack-a2 §4); validator narrowed to the report's datoms.
- Walk distance is a query-work bound reported as an elision (AGENTS §2.4);
  HTML never clips; the AI render functions and the value renderer are the
  presentation clipping spots; the prompt's whole-unit selection stays.
- Every test carries a bound (default 5 s) and fails over it; a reason
  without a number is a refusal. The platform tier (boot from zero) is the
  one subprocess; everything else runs in the cluster's JVM through
  `seon.test/check` over `run-owned`.
- Reset is free; database data is disposable; every type change is a RESET
  recorded in the spec, never a migration.

## Inputs (read end to end before writing)

`docs/prds/agent-platform/research/`: `synthesis-2026-09-21.md`,
`durable-goals-and-rulings-2026-09-21.md`, the six `deletion-audit-*.md`,
`instructions-and-process-audit-2026-09-21.md`,
`architecture-docs-verification-2026-09-21.md`, the seven
`data-pack-*.md` (verified citations, counts, corrections, open questions),
`issue-notes-lifecycle-sweep-2026-09-21.md`; `docs/research/agent-platform/`
holds the reference-code usage audit when it lands. Data packs correct the
audits; where they disagree, the pack wins (it verified at HEAD). The
archived programs under `docs/prds/steward-platform/` and
`docs/prds/context-generation/` are still readable and are deleted at the
clean write; cite them only where the goals note does not carry the fact.

## Two more owner requirements (2026-09-21, after the brief was first written)

- "I will be personally reviewing the plans so I want very clear
  explanations on what we were doing that was dumb and how with careful
  thinking we can achieve the targets we want in simpler and faster ways
  and with less code." Every spec and the plan therefore OPEN with a
  section for the owner, in plain sentences: what the code does today, why
  that was the wrong shape (the mirror beside the authority; the work
  proportional to the program; the mechanism guarding its own cost), and
  the simpler way stated as data flow: what is computed, when, where it is
  carried, and why it is proportional to the change. No jargon that the
  vocabulary table does not define.
- "Clojure projects tend to be 10x smaller than conventional software. I
  want to see that represented in my codebase." The plan states a SIZE
  TARGET per area and for the whole (today: src 90,162 lines in 109 files,
  test 98,985 in 299, schemas 14,256; the audits' floors reach ≈70 K src),
  and each spec states its area's lines before, the floor the audits found,
  and the target the writer judges reachable by dissolution — with the
  reasoning for the gap between floor and target. A target is a number a
  reviewer can check with `wc -l`.

## The REPL stays up (owner, 2026-09-21)

"The REPL needs to stay up throughout this process so make sure the core
system is going to be solid during these deep cuts and we can easily debug
and identify and fix problems." Consequences for every spec: the commit
order keeps `default` loadable and hot-reloadable after EVERY commit (a
retirement and every caller's conversion is one commit; `require :reload`
of the touched namespaces on `default` is part of landing); each commit
names the one debug probe that shows the system is still sound (a
`runtime_status` read, a fault query, a page fetch, a turn); a cut that
would make `default` unloadable is split until it does not; the recovery
when it breaks anyway is one command (`bin/seon reset --force`) and the
spec says what state that loses (nothing durable — database data is
disposable). No lane stops, reforks or restarts `default`. The system is
started fresh (tmp swept, reset from zero) before the implementation
session begins; the fresh boot's numbers are the baseline every spec's
"before" refers to when it cannot measure on the live cluster.

## What every spec must contain

0. **For the owner: what was dumb, and the simpler way** (see above).
1. **Goal and the numbers that prove it** — before/after measured on the
   REPL, never a green run. Anything over ~2 s is explained by algorithm.
2. **The data flow** — for every mechanism the lane touches: what data, when
   it is computed, where it is carried (which value or connection), what
   event triggers recomputation, and what work is proportional to what. This
   is the section astra reviews first.
3. **Reading list** — reference-code file:line blocks (verified in the data
   packs at the current gitlinks) and the first-party idiom to build on, each
   with one line on what it guarantees. The lane reads before editing.
4. **REPL protocol** — exact forms before and after; Codex reaches the REPL
   through `.codex/config.toml` → `bin/mcp-server` → `seon.dev.mcp`
   `eval_clj` (mode jvm/sci, read_only) against `default`, or a scratch
   cluster `bin/seon --root tmp/<lane>-root start <lane>` (the root directory
   must exist) downed after; no Juniper seed exists — a fresh cluster seeds
   agent `root`.
5. **The work, ordered as commits** — each net-negative or a named feature,
   each leaving HEAD loadable (`clojure -M -e "(require …)"` for the touched
   namespaces), fork commits interleaved and pushed before the deletion they
   enable; RESET NEEDED marked where a stored shape changes.
6. **Better than the floor** — 2–4 candidates the lane probes first for a
   smaller mechanism or a better algorithm, with the probe that decides.
7. **Tests** — what dies, what collapses, each time escape's disposition; the
   lane runs only the tests reaching its change in-process; never a suite.
8. **Done, landing note, stop rules** — landing note path under
   `docs/prds/agent-platform/landing/lane-<x>.md` with exact forms and
   numbers; stop at a held file, at an unsettled design (three options in the
   note), at a seam another lane has not landed (name it).

Rules: every claim cites file:line; unverifiable = marked; verify / probe /
falsify; no new mechanism, cache, tuned constant or noun; explain in
Clojure, Malli, Datahike, SCI, core.async, clj-kondo terms; tables over
prose; under ~400 lines per spec.
