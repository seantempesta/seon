---
type: research
status: active
tags: [research, schema, reference]
---

# Map-In Rule Audit — every statement + enforcement point (2026-06-08)

## TL;DR

The "all public functions are map-in / map-out, no multi-arity" rule is
stated in **~30 places** (4 are load-bearing instruction files; the rest
are vision/PRD/docstring restatements) and enforced in **exactly one
active code path**: `seon.dev.compliance/uses-map-in?`, surfaced by the
dev hook as a `:no-map-in` violation. That checker is the **only thing
that would auto-flag valid new-rule code** (positional public fns with
per-slot `:catn` specs). It is currently **non-blocking** (`:compliance
{:block false}` in `.claude/seon-hook.edn`), so it warns but does not
reject — still, it is the #1 change.

Good news for the migration: the **schema + instrument machinery is
already shape-agnostic.** `seon.dev.instrumentation` handles `:cat`,
`:=>`, and `:function` schemas and renders per-arity, named-slot errors;
the companion spec `research/positional-db-ops-spec-2026-06-08.md`
verified live that `:catn` named slots + `:function` multi-arity
instrument correctly in both runtimes. No instrument/schema code
special-cases "one map arg." There is **no clj-kondo rule** and **no
edit-tool / MCP validation** that enforces map-in (only delimiter/lint
checks). The agent-facing seed text that teaches the agent the old rule
is `default-conventions` in `src/seon/client.cljs` (the
`:seon.conventions` entity) — NOT `deepseek/default-system-prompt`,
which never states the rule.

**Counts:** ~30 STATEMENTS, 3 real ENFORCEMENT points (1 code checker +
its 2 dev-tools doc descriptions); ~6 generator/test helpers are
already exempt or shape-agnostic.

**Top 3 highest-priority changes:**

1. `src/seon/dev/compliance.clj` — `uses-map-in?` + the `:no-map-in`
   violation. The ONLY code that flags non-map-in public fns. Replace
   "single map arg" check with "has `:malli/schema` whose input is a
   registered `:cat`/`:catn`/`:map` with every slot specced." Rename the
   violation `:no-map-in` → `:unspecced-args` (or drop it and fold into
   `:no-malli-schema` + a new `:unspecced-slot` check). Without this,
   every new positional-but-fully-specced public fn gets a false-positive
   warning on every edit.
2. `src/seon/client.cljs` `default-conventions` (the `:seon.conventions`
   seed entity) — this is the live text the AGENT reads every turn.
   Currently teaches "Every public fn takes ONE map and returns ONE map."
   Must teach the new rule or the agent will keep writing/expecting only
   map-in.
3. `CLAUDE.md` (lines 289 + 426) + `docs/conventions.md` (the
   normative "Public Function Pattern" section) — the canonical
   human+agent rule statements every Claude instance reads at boot.

---

## Table 1 — STATEMENTS of the old rule

Recommended new text honors: validate-everything, positional-with-named-`:catn`-specs OK, prefer-map-in-for-APIs.

| File:line | Current text (abbrev) | Recommended new text |
|-----------|------------------------|----------------------|
| `CLAUDE.md:289` | "Every public function takes one map and returns one map. Every key in both maps is fully namespaced … Both the request and response map are themselves named Malli schemas … `:malli/schema` … points at them." | "Every public function fully specs and validates ALL its arguments and its return via `:malli/schema`. Args may be a namespaced-keyword map (map-in/map-out) OR positional, where each positional slot has its own fully-namespaced spec via `:catn` named slots inside a `:=>`/`:function` schema. **Prefer map-in/map-out for API-like surfaces** (discoverable, extensible); positional is fine for ordinary data-processing fns. Invariant: every arg is named, specced, validated. Keys in any map remain fully namespaced." |
| `CLAUDE.md:426` (Function Instrumentation) | "Public functions follow **map in, map out**. One map argument, one map return. No multi-arity on public functions." | "Public functions fully spec and validate every argument and the return. Map-in/map-out OR positional with per-slot `:catn` specs; multi-arity is allowed when every arity is fully specced (use a `:function` schema). Prefer map-in/map-out for API surfaces." |
| `CLAUDE.md:278` | "…converge on the spec §3 map-in/map-out + `*conn*` shape." | (historical convergence note — leave, or append "; positional-with-`:catn` now also permitted per 2026-06-08 rule change".) |
| `docs/conventions.md:17` | "**Map-in/map-out** → Extensible APIs. Adding a field doesn't break callers." | Keep as the *benefit of map-in for APIs*, but reframe: "**Map-in/map-out (preferred for APIs)** → extensible; adding a field doesn't break callers. Positional public fns are allowed when every slot is specced via `:catn`." |
| `docs/conventions.md:96-99` ("Public Function Pattern") | "Public functions are **map in, map out** with: single map argument using `::keys`; namespaced keys in return; `:malli/schema` referencing request/response schemas." | Rewrite section: two sanctioned shapes — (a) map-in/map-out (`[:=> [:cat ::req] ::resp]`), preferred for APIs; (b) positional with named slots (`[:=> [:catn [::a ::a-schema] [::b ::b-schema]] ::resp]`). Both fully validated. Add a worked `:catn` example. |
| `docs/conventions.md:129-138` (Private Helper Pattern) | "Private functions use positional args for internal convenience." | Keep, but note positional is now also a sanctioned PUBLIC shape (with per-slot specs); privates may remain unspecced. |
| `docs/conventions.md:416` | "**No map-in/map-out** — test helpers can use positional args for brevity." | Keep (test exemption stands). Optionally: "test helpers need no specs at all." |
| `docs/conventions.md:458-465` (Anti-pattern: converter positional) | "BAD: positional args limit extensibility." | Soften to: "Positional is fine when each slot is specced; prefer map-in for converters you expect to extend." Keep the extensibility argument as a *preference*, not a *ban*. |
| `docs/conventions.md:561` (Anti-Patterns block) | "BAD: positional args in public API - harder to extend" | Replace with: "BAD: *unspecced* positional args in public API. Positional is fine WITH a `:catn` per-slot spec." |
| `AGENT.md:281` | "Map-in, map-out public APIs with namespaced keys" | "Public APIs fully spec every arg + return (map-in/map-out preferred; positional-with-`:catn` allowed)." |
| `docs/seon/vision/index.md:73` | "The convention is total: every public function takes one map and returns one map…" | "…every public function fully specs and validates its inputs and output; map-in/map-out is preferred for API surfaces, positional-with-named-slots is permitted; every key carries a real namespace…" |
| `docs/seon/vision/index.md:155` | "one map in, one map out, all keys namespaced, `:malli/schema` on every `defn`." | "fully specced in and out (map-in/map-out or positional `:catn`), all keys namespaced, `:malli/schema` on every public `defn`." |
| `docs/seon/vision/index.md:183` | "convention uniformity (every public function map-in/map-out …)" | "convention uniformity (every public function fully specced — map-in/map-out or positional `:catn`)" |
| `docs/seon/vision/m3-convention-uniformity.md:45` | "Every public function: map-in, map-out … every `defn` without `^:private` must take a single map argument …" | "Every public function: fully specced in+out. Two sanctioned shapes — map-in/map-out (preferred for APIs) or positional with named `:catn` slots. Every public `defn` has a `:malli/schema` declaring both." |
| `docs/seon/vision/m3-convention-uniformity.md:135` | "M3 fully crossed when: every public function has `:malli/schema` with map-in/map-out…" | "…has `:malli/schema` fully specifying args (map or `:catn`) and return…" |
| `docs/seon/vision/m2-trustworthy-data.md:119` | "every function uses map-in/map-out with namespaced keys" | "every function is fully specced (map-in/map-out or positional `:catn`) with namespaced keys" |
| `docs/seon/vision/full-scope-synthesis-2026-05-23.md:131,135` | "Every public function is map-in, map-out, `:malli/schema`…" | "…is fully specced — map-in/map-out or positional `:catn` — `:malli/schema` on the var…" |
| `docs/seon/vision/biggest-ideas-2026-05-23.md:438` | "Map-in / map-out / namespaced everywhere." | "Fully-specced / namespaced everywhere (map-in/map-out or positional `:catn`)." |
| `docs/seon/vision/m6-eval-pipeline.md:36` | eval interceptor: "Map-in/map-out?" as a gate | "All args specced (map or `:catn`)? Return specced?" |
| `docs/seon/vision/m8-autonomous-agents.md:40` | eval pipeline validates "map-in/map-out" | "validates: schema present, concrete types, all args + return specced (map or `:catn`)" |
| `docs/seon/vision/capabilities/repl-first-development.md:21,35` | "map-in/map-out (for `defn`)" gate | "all-args-specced (for `defn`)" |
| `docs/seon/vision/capabilities/repl-eval-pipeline.md:8,18` | "…map-in/map-out) before accepting forms." | "…full arg+return specs) before accepting forms." |
| `docs/seon/architecture/decisions/007-runtime-instrumentation.md:29,52` | "Every public function must follow map-in/map-out convention…" | "Every public function must fully spec its args + return (`:malli/schema`); map-in/map-out preferred for APIs, positional `:catn` permitted." Add an ADR note that the rule was loosened 2026-06-08. |
| `docs/seon/components/database.md:156` | "`seon.db` … the one namespace where map-in/map-out does not apply." | Reframe: under the new rule `seon.db`'s positional ops are no longer an *exception* — they're a sanctioned positional shape (per `positional-db-ops-spec-2026-06-08.md`, each slot gets a `:catn` spec). Update to "`seon.db` ops offer BOTH a map-in arity and a datahike-mirroring positional arity, both fully specced." |
| `docs/seon/orchestrator/issues/map-in-map-out-compliance.md:8-21` | Whole issue: "convention is every public function takes one map … many still use positional args." | This issue's premise is reversed by the rule change. Update: the goal is now "every public fn fully specced (map or `:catn`)", not "convert all to map-in". Many flagged fns may already be compliant under the new rule. Consider closing/rescoping. |
| `docs/seon/orchestrator/issues/missing-malli-schema.md:12` | "…lack `:malli/schema` and don't follow map-in/map-out." | "…lack `:malli/schema` (args and/or return unspecced)." Drop the map-in clause; keep the spec-presence requirement. |
| `docs/seon/lineage/milestone-prior-work.md:114` | "every public function uses map-in/map-out…" | "every public function is fully specced (map-in/map-out or positional `:catn`)…" |
| `docs/seon/components/dev-tools.md:38,66,143-151` | describes compliance checker as enforcing "map-in pattern (single map argument)" | Update to match the new checker behavior (see Table 2): "checks every public fn has `:malli/schema` fully specifying args (map or `:catn`) and return." |
| `src/seon/client.cljs:611` + `default-conventions` (the `:seon.conventions` seed, ~line 615-625) | "Every public fn takes ONE map and returns ONE map. All keys are fully namespaced." | **HIGH PRIORITY (agent-facing).** "Every public fn fully specs+validates its args and return via `:malli/schema`. Map-in/map-out (preferred for APIs) OR positional with per-slot `:catn` specs. All keys in any map are fully namespaced." |
| `src/seon/ai.clj:22` | docstring: "All functions use map-in, map-out with namespaced keys." | "All public functions are fully specced (map-in/map-out here)." (ai.clj is map-in in practice; just soften the absolute claim.) |
| docstrings restating the rule (informational, low priority) | `src/seon/db.cljs:786,798,879,1311`; `src/seon/render.cljs:110`; `src/seon/agent.cljs:507,736,1517`; `src/seon/inspect.cljs:12`; `src/seon/handler.cljs:12,154,158`; `src/seon/fs.cljs:29`; `src/seon/test/runner.cljs:9`; `src/seon/code.cljc:281`; `src/seon/ns/example.clj:11`; `src/seon/ns/view.clj`; `src/seon/render/default*.clj(s)`; `src/seon/ai/gemini.clj:384` — all say "map-in / map-out per house rule". | These describe each fn's actual chosen shape (mostly genuinely map-in) — leave as accurate self-description. Only edit where a docstring asserts the rule as a *universal mandate* rather than describing its own shape. The ns's that document being positional-by-design (`web/reactive/transform.clj:19`, `web/reactive/actions.clj:7`, `server/reactive.clj:13`, `web/browser.clj:36`) become *normal* under the new rule rather than documented exceptions. |
| PRD-historical restatements (very low priority, mostly archival) | `docs/prds/unified-namespace-flow/design.md:86,582,586`; `docs/prds/namespace-bootstrap/design.md:19,101,277`; `docs/prds/schema-unification/design.md:460,467`; `docs/prds/datahike-migration/*`; `docs/prds/agent-runtime/v1.md:1138`; research files under `agent-runtime/research/` | various "map-in/map-out everywhere / no exceptions" | These are dated design docs / research snapshots. Do NOT rewrite history; if a PRD is still active and normative, add a one-line "superseded 2026-06-08: positional-with-`:catn` now permitted" note. The `positional-db-ops-spec-2026-06-08.md` already encodes the new direction. |

---

## Table 2 — ENFORCEMENT points

| File:line | What it currently enforces | Blocks new rule? | Change needed |
|-----------|----------------------------|------------------|---------------|
| `src/seon/dev/compliance.clj:139-154` (`uses-map-in?`) | Returns false unless EVERY arity is exactly one arg AND that arg is a map literal. | **YES — primary blocker.** A valid new-rule positional public fn (e.g. `[a b]` with a `:catn` schema) returns `uses-map-in? = false`. | Replace the arity-shape check with a spec-completeness check: read `:malli/schema`, confirm its input is a `:cat`/`:catn`/`:map` (or `:function` with all arities specced) and that every slot/positional has a registered (non-`:any`) spec. Don't inspect arglist shape at all — inspect the schema. |
| `src/seon/dev/compliance.clj:54-60, 239-271` (`::violation-type :no-map-in` + emission in `check-var`) | Emits a `:no-map-in` violation ("does not use map-in pattern (single map argument)") whenever `uses-map-in?` is false. | **YES.** This is the violation that surfaces in hook feedback. | Rename `:no-map-in` → `:unspecced-args` (or remove and fold into the schema checks). Re-point the message to "argument N has no spec" rather than "not a single map." Drop the enum value `:no-map-in`, add `:unspecced-slot` if doing per-slot. |
| `src/seon/dev/compliance.clj:355-414` (`generate-map-in-signature`, `generate-fix-suggestion`, `generate-request-schema`) | The auto-fix generator REWRITES positional fns into `[{::keys [...]}]` map-in signatures + request/response schemas. | **YES (advisory).** It actively suggests converting positional → map-in, teaching the old rule. | Make the fix generator offer a `:catn` positional spec as an alternative (or default for non-API fns), and only suggest map-in conversion for fns it can't otherwise spec. Stop auto-generating `:any` slots (already a smell). |
| `src/seon/dev/compliance.clj:434-473` (`analyze-namespace`) returns `::with-map-in` count + `::compliant?` derived from violations | Reports per-ns map-in counts; `::compliant?` is false if any `:no-map-in` violation exists. | **YES (transitively, via the violation).** | After the checker change, `::with-map-in` becomes `::with-specced-args` (or keep as an informational metric but stop gating `::compliant?` on map-in-ness). |
| `src/seon/dev/hook.clj:379-399` (`stage-compliance`) + `:611-622, :724-730` (block logic) | Runs `analyze-namespace` on each edited ns, appends `format-violations` to feedback; **blocks only if `:compliance {:block true}`**. | **Partially.** Currently NON-blocking (config has `:block false`), so it WARNS on valid new-rule code but does not reject. Becomes a hard blocker the moment anyone flips `:block true`. | No structural change here — fix the underlying checker (compliance.clj). Hook is shape-agnostic; it just renders whatever violations the checker emits. |
| `.claude/seon-hook.edn` (`:compliance {:enabled true :block false}`) | Config: compliance feedback on, non-blocking. | No (block off). | No change required, but note: this is the safety valve keeping the bad checker from rejecting code today. Don't flip `:block true` until compliance.clj is updated. |
| `src/seon/dev/instrumentation.clj:114-126, 128-138, 157+, 250-305` (`generate-example`, `schema-ref-name`, `format-input-errors`, arity-error formatting) | Runtime instrumentation. Already handles `:cat`, `:=>`, AND `:function` (multi-arity); `format-input-errors` runs `m/explain` on the input schema, surfacing per-position errors (incl. `:catn` named slots). | **NO — already shape-agnostic.** No special-case for "one map arg." | No change. Verified against `positional-db-ops-spec-2026-06-08.md`: `:catn`/`:function` instrument with named-slot errors via stock `malli.instrument`. |
| `seon.schema/register!` + the Malli registry | Registers attribute/shape schemas. No arity/shape assumption about functions. | **NO.** | No change. |
| `src/seon/dev/lint.clj` + `.clj-kondo/config.edn` (+ no `.clj-kondo/hooks/` dir) | clj-kondo lint on edit (PreToolUse) — undefined symbols, arity, delimiters. **No map-in rule, no custom hook.** | **NO.** | No change. |
| Edit-tool / MCP `clojure_replace` validation path (`src/seon/dev/clojure_replace.clj`, `repair.clj`) | Syntax/delimiter validation + cljfmt before write. No convention/map-in check. | **NO.** | No change. |
| `src/seon/dev/review.clj` (Gemini AI review prompt) | Sends recent edited code to Gemini for review; no hard-coded map-in assertion found in the prompt (it references project conventions generically via `docs/conventions.md`). | **Indirect.** Gemini infers conventions from `docs/conventions.md`; if that doc still says "map-in only," Gemini may flag valid positional fns. | No code change. Updating `docs/conventions.md` (Table 1) fixes the review behavior — the prompt pulls conventions from that file. |
| Test/generator helpers: `seon.dev.compliance` REPL comments; pipeline-roundtrip helpers (`test/seon/db/pipeline_test.clj`, `assert-pipeline-roundtrip!`) | Pipeline roundtrip helpers exercise DB *data* schemas, not fn arg shapes. Test code is exempt from map-in per `conventions.md:416`. | **NO.** | No change. (If any generative test calls a public fn assuming a single map arg, it will keep working for map-in fns; positional fns get sampled via their `:catn` input schema by `mg/generate`, which instrumentation already does.) |

---

## Highest-risk items (flagged)

1. **`src/seon/dev/compliance.clj` `uses-map-in?` + `:no-map-in`** — the
   ONLY automated path that would (and today does, as a warning) reject
   valid new-rule code. It inspects arglist *shape*, not the schema.
   Must become a schema-completeness check. Until fixed, every
   positional-but-fully-specced public fn yields a false-positive on
   every edit, and flipping `:compliance {:block true}` would hard-block
   the entire new pattern.

2. **Agent-facing seed: `default-conventions` in `src/seon/client.cljs`**
   (the `:seon.conventions` entity rendered into every turn's ctx). This
   is what TEACHES the live agent the old rule. If not updated, the agent
   keeps writing map-in-only code and mis-validating positional APIs it
   encounters. Note: `deepseek/default-system-prompt`
   (`src/seon/ai/deepseek.cljs:67`) does NOT state the map-in rule, so it
   needs no change.

3. **`docs/conventions.md` "Public Function Pattern" + `CLAUDE.md:289,426`**
   — the normative source every Claude instance (and the Gemini reviewer,
   which reads `docs/conventions.md`) treats as ground truth. Until these
   change, human + AI reviewers will keep flagging positional public fns
   as violations regardless of the code-level checker.

## Notes / smells observed

- `compliance.clj`'s fix generator emits `:any`-typed schemas
  (`generate-request-schema` lines 316-331) — that already violates the
  no-`:any` rule and should be fixed alongside the rule change.
- `docs/seon/components/database.md:156` frames `seon.db` positional as
  "the one namespace where map-in does not apply." Under the new rule
  plus `positional-db-ops-spec-2026-06-08.md`, that framing is obsolete —
  positional-with-`:catn` is a first-class shape, and `seon.db` is its
  canonical example, not an exception.
- The open issue `map-in-map-out-compliance.md` is premised on the OLD
  rule (convert all positional → map-in). The rule change largely
  *resolves* it: rescope to "all public fns fully specced" rather than
  "all map-in."
