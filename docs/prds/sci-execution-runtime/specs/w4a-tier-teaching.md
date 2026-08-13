---
type: prd
status: active
tags: [prd, architecture, agent]
---

# W4a — tier-aware system teaching + generate-code contract phrases

Working dir /Users/sean/src/seon, branch codex/runtime-reliability-refactor.
SHARED tree: touch ONLY src/seon/agent/ctx.cljs, config/system.edn,
src/my/plan/internal.cljc, and one new test file
test/seon/agent/ctx_teaching_test.cljs. Path-limited commit only. Never
edit any CLAUDE.md. src/seon/host.clj is owned by a CONCURRENT lane.

ROLE: principal engineer. Better seam → STOP and report with evidence.

GROUNDING (confirm each):
1. docs/prds/sci-execution-runtime/research/audit-doc-drift-2026-07-21.md
   §surface 1 — the two system-texts: config/system.edn:236-269
   (:seon.config/system-text graduated override) and the shipped
   fallback in src/seon/agent/ctx.cljs:775-887. Both currently teach
   the Bun-child/async world ("NO JVM — no java.*", ^:async/await
   contract). That guidance is TRUE for child-tier agents and FALSE for
   host-tier agents (presence of :seon.execution.host/eval-socket-path
   on the agent entity = host tier; see seon.execution.host dispatch).
2. docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md
   design addenda — the sync Datomic-shaped db idiom, CLJC-first
   teaching, data-crosses/handles rules, and the owner teaching
   contract: think through ALL changes; write (1) data model/specs
   first, (2) dependency functions other namespaces require, (3) main
   namespaces requiring them; ANY write order — the parser loads in
   dependency order; mistakes → overwrite, LAST VERSION WINS.
3. src/my/plan/internal.cljc `development-teaching` (~:1764) — already
   ~90% aligned; the drift is "ClojureScript"-specific phrasing and
   "awaits top-level Promises".
4. How ctx blocks derive: the system-text render must be able to read
   the agent's tier fact at render time (find how other ctx blocks
   query agent facts — follow that exact idiom).

GOAL — teaching derives from facts, never misteaches either tier:
1. The fallback system-text in ctx.cljs becomes TIER-AWARE: it renders
   the platform/eval-contract section from the agent's tier fact —
   child tier keeps today's guidance (async contract, no-JVM) VERBATIM;
   host tier renders the sync idiom (plain synchronous calls, no
   ^:async/await needed, JVM platform — java interop like
   java.util.Date works, prefer portable CLJC forms like inst-ms,
   platform capabilities via my.* functions). Shared sections (identity,
   database, toolkit) stay single-sourced — only the platform-contract
   section branches. Structure it as data/render, not string surgery:
   one fn per section, tier chooses the platform section.
2. config/system.edn's :seon.config/system-text override: apply the
   SAME tier-aware treatment consistent with how the override mechanism
   works (if the override is a static string, convert the platform
   section to the derived mechanism or report the structural conflict —
   do not fork the mechanism; if the override renders through symbols
   like other blocks, branch there).
3. Both tiers' texts gain the generate-code contract phrases (data
   model first → dependency fns → main namespaces; any write order;
   parser orders; last version wins) where writing-code guidance lives.
4. my.plan/internal development-teaching: fix the two drift phrases —
   platform-specific "ClojureScript" wording becomes platform-neutral,
   "awaits top-level Promises" becomes tier-neutral phrasing (the
   runtime executes the form and returns its value; async detail
   belongs to the tier contract, not this block).
5. NO new teaching mechanism: this strengthens the existing block
   render fns in place.

TESTS (new CLJS ns; existing ctx-test idioms; BEHAVIOR/PRESENCE, never
exact wording — context prose is tuned continuously):
a. child-tier agent (no tier fact): rendered system-text contains the
   async-contract markers and no JVM-affirmative guidance (assert via
   stable structural probes — e.g. presence of the section, not exact
   sentences; pick minimal robust probes like "js/" mention vs
   "java.util" mention);
b. host-tier agent (tier fact set): sync/JVM guidance present,
   "NO JVM" class guidance absent;
c. both tiers: generate-code contract markers present (probe for
   last-version-wins concept via a stable token you place, e.g. a
   section heading);
d. development-teaching renders for both tiers without platform-wrong
   phrasing (probe absence of "ClojureScript" in that block).

GATE: focused ns + bin/seon test changed --path src/seon/agent/ctx.cljs
(read its advisory output), then full bin/test-cljs green (report
honest counts; expect ~1458 tests. STOP on any pre-existing failure).

COMMIT: one path-limited commit
  git commit --only -m "Derive tier-aware system teaching from agent facts" \
    -- src/seon/agent/ctx.cljs config/system.edn src/my/plan/internal.cljc test/seon/agent/ctx_teaching_test.cljs

SUMMARY: grounding confirmations, seam findings (esp. how the override
mechanism constrained #2), the structural probes chosen, gate counts,
unresolved items.
