# W2 — LLM provider fallback as config data

Working dir /Users/sean/src/seon, branch codex/runtime-reliability-refactor.
SHARED tree: touch ONLY src/seon/agent/turn.cljs, src/seon/agent.cljs,
config/system.edn, and one new test file
test/seon/agent/turn_fallback_test.cljs. Path-limited commit only. Never
edit any CLAUDE.md. src/seon/host/* and src/seon/db/writer.clj are owned
by CONCURRENT lanes — do not touch them; if unrelated uncommitted files
exist when you run gates, report them with your gate results.

ROLE: principal engineer. Accomplish the GOAL; if the source shows a
better seam, STOP and report with evidence.

GROUNDING (mandatory; confirm each):
1. docs/prds/generate-code/research/llm-retry-fallback-resilience-2026-07-21.md
   — the design sketch this implements (§fallback design + the six owner
   decisions; decisions now RULED: timeout IS fallback-eligible;
   planning fallback target is the Muse variant; fallback is per-call,
   cheap-by-default, no silent permanent switch).
2. src/seon/agent/turn.cljs `call-llm!` (~:890) and `llm-retryable?`
   (~:714) — the SOLE retry authority (seon.retry/with-retry!,
   per-attempt provenance :seon.ai.attempt/* datoms, Retry-After,
   bounded attempt wall-clock). Fallback consultation lives INSIDE this
   one mechanism — no second retry/fallback path.
3. src/seon/retry.cljc — the policy combinators; reuse, don't duplicate.
4. config/system.edn:282-305 — named model variants (:planning kimi-k3,
   :execution deepseek-v4-flash) and how variant values copy onto agents
   at birth (src/seon/agent.cljs ~:422, ~:1058-1082). Follow that exact
   copy mechanism for the new attribute.
5. docs/seon/reference/llm-adapters.md — variant/model catalog naming.

GOAL:
1. New agent attribute `:seon.ai/agent-fallback-variants` — an ordered
   vector of named config variants (schema: vector of non-empty
   strings; register in the owning namespace per colocation rules).
   Copied at birth from the variant definition like the other
   :seon.ai/* values. Absent attribute = exactly today's behavior.
2. In `call-llm!`: when the primary model's attempts exhaust on
   RETRYABLE errors OR an attempt deadline/timeout fires, consult the
   fallback list in order (resolved frozen at turn open — read the
   agent facts once, not per attempt). Each fallback gets a bounded
   attempt budget (reuse the same retry policy but with attempts
   reduced — e.g. the remaining total-time budget governs; ground the
   exact shape in what seon.retry already expresses; do not exceed the
   existing total wall-clock cap for the whole call). Non-retryable
   errors (400/401/402/403, context-length, truncation-without-text)
   NEVER trigger fallback.
3. Provenance: the existing :seon.ai.attempt/* datoms already record
   requested/response model per attempt — verify the fallback attempts
   flow through the same recording so "who actually served this turn"
   is answerable from facts alone. Add a fact on the turn/resp marking
   that fallback fired and which variant served, ONLY if the existing
   attempt rows cannot already answer it (derive, don't duplicate —
   check first and report).
4. config/system.edn: give the :planning variant
   fallback-variants ["muse"-variant-name — use the exact existing
   Muse variant name from the config/catalog]. Leave other variants
   without fallbacks. NO model string literals in code — everything
   through config.
5. Streaming/mid-stream failure: if the primary fails mid-stream after
   partial tokens, the fallback attempt restarts the request from
   scratch (the prompt is immutable within the turn). Verify the
   existing attempt machinery already discards partial state; report
   what you find.

TESTS (new CLJS ns; follow existing turn-test idioms — scripted fake
llm-fns, in-memory db; async test patterns per the clojure-testing
skill; behavior not strings):
a. primary times out twice (scripted), fallback variant configured →
   fallback fn invoked, resp ok, attempt rows show both models, turn
   completes :done;
b. retryable-exhaustion (scripted 429s) → same fallback path;
c. non-retryable 402 → NO fallback invocation, error value surfaces;
d. no fallback configured → identical to today (error value after
   retries);
e. fallback also fails → the final error value surfaces honestly and
   attempt rows show the full chain;
f. total wall-clock stays within the existing cap (assert bounded, not
   exact).

GATE: bin/test-cljs with your focused ns green, then the full
bin/test-cljs suite green (report honest counts). Any pre-existing
failure: STOP and report verbatim.

COMMIT: one path-limited commit
  git commit --only -m "Add per-agent LLM fallback variants consulted inside call-llm!" \
    -- src/seon/agent/turn.cljs src/seon/agent.cljs config/system.edn test/seon/agent/turn_fallback_test.cljs

SUMMARY: grounding confirmations, seam findings, the derive-vs-add
provenance decision with evidence, mid-stream findings, gate counts,
unresolved items.
