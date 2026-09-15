---
type: research
status: complete
tags: [render, performance, database]
---

# Debug page cost: remove repeated derivation — 2026-09-15

**Design only.** Read AGENTS.md §2.1/§2.4, the Datastar skill and
[page-speed landing](page-speed-and-estimate-landing-2026-09-15.md) end to end;
also used REPL, data-oriented Clojure and Datahike skills. Default pid **69622**
answered MCP health and JVM probes. No source/test edits, test JVMs, reload,
restart, refork, messages or provider calls. HEAD moved from `5258d7bb0` to
`b80f78a7c` as the concurrent projection lane committed. These measurements
exercise its already loaded definitions, not a clean-HEAD comparison.

## Measured breakdown and hypothesis verdicts

`GET /agent/juniper/debug`: **670.685 ms**, HTTP 200, **146,776 bytes**;
plain page warm **20.551 ms**. The morning 130 ms / 67,410-byte page is a
different population. Current population: **45 turns, 88 evaluations,
28 provider prefixes, 27 stable adjacent pairs, 5 directory evaluations**.
At basis **536871658**, one complete warm ledger costs **645.623 ms**:
**642.904 ms / 1,563.938 MB** constructing/validating its Hiccup and
**2.719 ms / 12.919 MB** serializing it. That partition accounts for the
entire measured ledger; HTTP routing/shell/scheduling is outside this probe.

| Section / hypothesis → probe | ms / allocated MB | Verdict and unnecessary work (source line) |
|---|---:|---|
| Rows + summaries → existing `turn-rows`, `ledger-rows` | 0.48 + 0.15 / 1.54 + 0.29 | Small; keep the single acquisition (`transcript.clj:1180,1608`). |
| Evaluations: equal-basis wrapper loses reuse → `ledger-evaluations` | fresh 40.8 / 83.2; same object 15.3 / 59.2; cold 854.6 / 3,051.1 | Confirmed identity mismatch; repeat evidence checks plus per-evaluation emission/label derivation (`web.clj:2420`, `transcript.clj:1514`). |
| Prefix stability → `prefix-problem` | fresh 287.8–346.0 / 633.4–633.7; same object 14.5 / 28.3 | 28 retained calls carry **291 reads**; GET revalidates stable captured prefixes (`transcript.clj:1872`, `render.clj:1262`). |
| Problems → `session-problems` in normal acquisition order | 500.6 / 1,314.6 | Inclusive of prefix/directory/budget; recomputes historical diagnostics (`transcript.clj:1948`). |
| Directory audit → `directory-problem` | fresh 167.3–225.4 / 369.0; same object 4.7 / 11.7 | Reconstructs and renders five historical directories instead of reading their shown text (`transcript.clj:1821`). |
| Budget → `session-budget` | fresh 23.6–32.6 / 50.4–50.5 | Requeries each provider turn's opening plan step (`transcript.clj:1926`). |
| Stories → all 45 `turn-story` calls | fresh 98.3–102.8 / 289.2–289.8; same object 8.6 / 37.6 | Repeats effect queries, also used by expanded bodies (`transcript.clj:1578,2109`). |
| Three expanded bodies → `ledger-turn-body` + serialization | fresh 19.7–22.2 / 68.0 | Repeats calibration/effects; one calibration alone **2.50 ms / 9.78 MB** (`transcript.clj:1638`). Estimate including construction of an 80 KB string is only **0.232 ms / 1.08 MB**; do not optimize arithmetic. |
| Found values; fallback rebuilds | **0 calls on this route; 0 warning bytes** | Default agent route bypasses found values (`web.clj:3071`). Live fallback now refuses, never rebuilds (`db.clj:952`); old warning frequency cannot explain today's floor. |

Section rows are **separate, overlapping microbenchmarks**, not additive
fractions of the 645.623 ms sample. Fresh-wrapper tests share basis
536871656; later cold sample is basis 536871659. Broad MCP result rendering
can itself store evidence, so bases were recorded, not assumed constant.
Same-object whole-ledger results ranged **77.7–304.8 ms**; identity repair
alone is not proven sufficient for tens of milliseconds.

**Carriage hypothesis → probe → verdict:** two `(seon.db/db c)` values are
equal but not identical; their `datahike.db/committed-value-identity` values
are equal. Raw `@c` lacks projection; `db/db`, `as-of` and `history` carry it.
`carry-projection-state` creates metadata wrappers (`db.clj:160`). The old
timing script uses `@connection` under a supplied projection, masking this
distinction. Two complete ledger probes captured **zero** fallback warnings.
No currently failing page read was observed; attributing old logs to a
specific present section would be unsupported.

**JFR check:** `jcmd 69622 JFR.start name=debug-page-cost-plan settings=profile
duration=30s filename=tmp/debug-page-cost-plan.jfr`, then 30 curl GETs;
`jfr print --json --events jdk.ExecutionSample`. GETs ranged **670.6–1543.4 ms**.
Of **2,509** samples, **1,954** were virtual-thread samples. Top frames:
`Util.compare` 113, `AFunction.compare` 91, hash-map `assoc` 89,
`Util.equiv` 85. Virtual inclusive frames: sorted-set chunk creation 131,
Datahike value comparison 90, temporal merge processing 36. **2,385** samples
had no Seon frame within the truncated stack: these establish collection/query
work, not precise section percentages. Scratch recording/JSON removed after summary.

## Kills, simplest first; exact future regressions

All proposed tests use the canonical database fixture, real SCI and armed
contracts; counters observe real calls rather than substitute results.

1. **Recognize the existing committed identity at retained-call boundaries.**
   Compare dependency identity, plus existing projection/program/input evidence,
   before replaying reads. Never use bare basis `:t`, full DB equality, or grant
   this shortcut to speculative/history/since values. Dependency ledger:
   Datahike `cdcb5792`, `reference-code/datahike/src/datahike/db.cljc:385`;
   temporal identity `query.cljc:2658`; first-party `db.clj:642` already follows
   it. No additional cache. Expected warm prefix **≤15 ms**, evaluations
   **≤16 ms** (estimates grounded in same-object samples).
   **`seon.render.retained-test/equal-committed-database-skips-read-replay`:**
   two distinct carried wrappers of one commit return identical retained bytes
   with **zero second-call read-currentness checks**; changed shown text,
   changed program, and another connection must invalidate. Today pointer-only
   reuse fails the zero-check assertion (`render.clj:1262`, `web.clj:2423`).

2. **Delete directory reconstruction from passive GET.** Expected directory
   cost **0 ms**, replacing the current integrity claim with “not checked”.
   Owner choice (effort estimates): **A (recommended), remove this automatic
   audit, ~0.25 day**, preserving shown text but giving up automatic directory integrity;
   **B, explicit diagnostic action, ~1 day**, preserving the check on demand
   but adding a user action; **C, evaluate integrity at original settlement,
   ~2–3 days plus schema/refork coordination**, preserving automatic evidence
   but widening ownership. No stored synthetic success when evidence is absent.
   **`seon.render.web-debug-test/passive-ledger-never-reconstructs-directory`:**
   a saved directory evaluation appears byte-for-byte, with **zero calls to
   `directory-value` and `render-directory-ai`**, and unavailable audit status.
   Today five saved directories enter that reconstruction branch.

3. **Derive page data once and carry it on rows/request.** Bulk-acquire turn
   effects once, attach summaries for story/body reuse; derive calibration once
   per distinct model; carry evaluation emissions/labels rather than regenerate
   them after retained history acquisition. Use existing acquisition ownership
   (`web.clj:2400`) for stable basis-derived values, not another cache. Expected
   stories **≤10 ms**, bodies **≤15 ms**, budget **≤17 ms**; combined warm
   ledger target **30–60 ms**, an estimate requiring implementation measurement.
   **`seon.render.web-debug-test/ledger-derives-shared-data-once`:** two expanded
   provider cards with the same model call calibration **once**, and each
   turn's effects derivation **once** despite appearing in both summary/body;
   resulting billing/effect text equals the canonical facts. Today both callers
   independently invoke these owners. Token estimation needs no new mechanism.

**Execution answer:** no `system-turn` call occurs on this GET route; buttons
invoke separate actions. It does execute render functions: history misses call
the evaluation pair via `walk.clj:902` → `render.clj:907` → real SCI invocation;
that pair reads saved text (`repl.clj:323,387`), not saved source. Prefix misses
also invoke a renderer. Directory audit directly executes the documentation
owner and AI renderer. No saved source evaluation was found on the inspected
route; this is source-path evidence, not a dynamic zero-call counter proof.

**Ownership / boundary:** this lane owns only this note. Future implementation:
`src/seon/render.clj`, `src/seon/render/web.clj`,
`src/seon/render/transcript.clj`, `test/seon/render/retained_test.clj`,
`test/seon/render/web_debug_test.clj`, optionally `web_context_test.clj`.
At entry, `db.clj`, `problems.clj`, `render.clj`, `render/walk.clj`,
`render/web.clj` were protected concurrent edits; at `b80f78a7c` they are clean.
Remaining dirty/untracked projection/fixture research and issue notes stay
foreign/protected; no implementation/test path above is currently dirty.
Related class record: [namespace-page costs](../../../seon/issues/core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks.md).
Reproduce section probes by adapting
`consolidate_debug_timing_2026_09_15.clj` to explicit `(seon.db/db
(seon.operator/connection "default"))`, no supplied-projection binding, and
measure each named owner with `nanoTime` plus ThreadMXBean allocated-byte
deltas on the MCP JVM thread; never infer HTTP virtual allocations from that
thread. Markdown hook reported 11 cross-file lint errors (visible feedback
names foreign `agents-md-audit-2026-09-15.md` gitlink citations); this note's
`git diff --check` is clean. No foreign session operated. Plan complete;
stop after path-only commit.
