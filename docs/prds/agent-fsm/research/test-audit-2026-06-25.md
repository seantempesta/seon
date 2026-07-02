---
type: research
status: active
tags: [research, testing]
---

# Test-suite audit — mechanism vs pinned prose (2026-06-25)

A read-only survey of `test/seon/**` + `test/my/**` asking "are tests testing
the RIGHT things?" The validated instinct: tests that pin agent-facing PROSE or
exact render OUTPUT churn when content is iterated; they should pin the
MECHANISM/CONTRACT. (Trigger: `my/soul_test.cljs` pinned a SOUL.md phrase →
broke on an owner edit → rewritten to the read-live mechanism.)

Stats: **125 active test files, 22 `.disabled`.** 4 prose-pins to convert, ~5
numeric assertions to review, 6 coverage gaps.

## Category 1 — prose/exact-output pins → convert to mechanism checks

| File:line | Pins | Fix |
|---|---|---|
| `render/code_test.clj:209` | exact ns-doc phrase `"Test namespace alpha"` | assert the render INCLUDES the ns-doc field (present + non-blank), not the phrase |
| `render/live_tile_test.cljs:159` | exact loader prose `"Updating this panel"` | assert the loader STATE/class (`:updating`), not the user string |
| `render/live_tile_test.cljs:330` | exact prose in the AI renderer | assert dispatch to ai-format, not the text |
| `render/chat_test.cljs:288` | exact agent reply `"on it — 3 workouts logged"` | assert the last-reply EXTRACTION mechanism (agent→human filter, peer-exclude); text is test data (borderline — the agent judged this one a real mechanism test) |

## Category 2 — `.disabled` inventory (22 files)

**PARKED-PENDING (real invariants, format settling — restore later):**
- `agent_context_test.cljs.disabled` (1405 lines) — pins the v4 signature-manifest P5 removed; restore when **P2** namespaces format locks (tracked #11). Biggest coverage win.
- `agent_loop_test.cljs.disabled` — loop stop-policy / `unanswered-live-inbound?` / empty-turn guard; restore after the loop format settles (relevant to #4/#6).
- `agent_retry_test.cljs.disabled` — LLM transport-failure retry boundary.
- `runtime_test.clj.disabled`, `session_test.clj.disabled` — JVM-track (registry / session lifecycle); parked-complex (CLJ side mostly ignored now).

**INFRASTRUCTURE (slow, multi-JVM, CI-only — not dead):** `flow/*.clj.disabled` (8 files) — domain-integration/harness/pool/topology, ~30s+, need agent classpath. Document as a CI/integration tier, don't treat as dead.

**REVIEW (unclear):** `agent/turns_test.cljs.disabled`, `db/datahike/flow_test.clj.disabled`, `gym/driver_test.cljs.disabled`, `gym/paid_test.cljs.disabled` — inspect dead-vs-parked individually.

## Category 3 — over-specific numeric assertions

- **KEEP (intentional guards, documented):** `eval/memory_safety_test.cljs:37` (`store-edn-cap = 16384`, OOM guard), `:108` (`result-row-cap = 50`).
- **REVIEW (mechanism vs tuning):** `ai_test.cljs:198,212` (`max-tokens = 2048` — provider config or calculated?), `ai/gemini_test.clj:256` (`default-timeout-ms = 60000`), `ai/agent_test.clj:173,212` (`5000`/`100` — these are PARSE-contract results; test the parse fn / property-gen, not the hardcoded example), `warn_test.cljs:524` (`"1500ms"` — if auto-generated from the number, pin the generation not the string).

## Category 4 — coverage gaps (mechanisms with weak/zero tests)

1. **Recursive render / resolve-slot dispatch** — no test of nested `:ai` within `:html` hiccup or the slot-resolution chain. (The keystone's core walker.)
2. **Loop stop-policy / tx-meta provenance** — the live test is disabled; no active test of the turn-weave + halt predicate + cause/stop-reason stamping (3a added this; should have a test).
3. **`.internal` boundary** — nothing verifies agents can't reach `seon.db.internal/*` / private fns (the whole `.internal` refine-wave premise).
4. **Errors-as-values envelope** — per-error-type tests exist, but no holistic invariant that every error path yields `{:ok? false :error {…}}`.
5. **Doc-section loader / section-split** — no test of the section-boundary cut (the just-landed P3 loader + the section-quoting being unified).
6. **Byte-stability of the cacheable prefix** — tests check CONTENT, none assert SHA-of-prefix is invariant across two renders (the universal caching rule; P1 verified it live but no standing test).

## Ranked highest-value fixes

1. Restore `agent_context_test.cljs.disabled` once **P2** locks the namespaces format (#11) — biggest coverage spike for least effort.
2. Convert the 4 Category-1 prose pins to mechanism checks (one-liners; unblock content iteration).
3. Add a **byte-stability** test (`SHA(prefix)` invariant across two renders) — the caching contract, currently only live-proven.
4. Resolve the Category-3 REVIEW assertions (parse-contract → property tests; tuning → documented constant).
5. Add an **`.internal` boundary** guard test.
6. Add a **loop stop-policy / tx-meta** test (or restore the disabled one) — covers 3a.

## Notes
- This is the deliverable for task #14. The Category-1 conversions + the
  byte-stability + `.internal` + loop tests are low-risk, file-disjoint cleanups
  that can ride alongside the relevant feature units (P2 → #11 + doc-section
  test; the loop/render tests with their units).
