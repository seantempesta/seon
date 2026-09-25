---
type: landing
status: current
created: 2026-09-24
scope: One render path slices 0a, 0b, 1a and part of 1b landed; 1b remainder and 1c wait on two rulings
---
# Lane render-dispatch-opus: one render path 0a, 0b, 1a, 1b (part) — 2026-09-24

Design: `docs/research/agent-platform/one-render-path-design-2026-09-25.md` §1, §6. Root ruling (follow-up): slice 1 split
into 1a/1b/1c; the ledger row was widened to error.clj, render/transcript.clj, program_test.clj, render/walk.clj,
render/web.clj and the six namespace-stage tests: `test/seon/render_simplification_test.clj`,
`test/seon/render_source_test.clj`, `test/seon/render/transcript_run_test.clj`, `test/seon/render/retained_test.clj`,
`test/my/turn_test.clj`, `test/seon/schema/datahike_parity.edn`. Only three fixtures exist
(`test/seon/render_simplification/fixture_{a,b,ambiguous}.clj`), not four.

## Broken or unproven first

- **Expected REDs in files outside this row** (one-line fixes each; owners or root):
  - `test/seon/contracts_fixture.clj:53` asserts the `:schema` stage selects a refusal's renderer. After 1a the `:declared`
    stage selects it first when the refusal's operation is contracted. Fix: accept `#{:declared :schema}`.
  - `test/seon/error_test.clj:715` asserts `(qualified-symbol? (:seon.render/ai notice))`, and `:735` asserts the notice names
    `instrumentation-prose`. After 1b the notice carries no renderer; `:seon.error/notice` declares `seon.error/notice-prose`.
    Fix: drop `:715`; at `:735` assert `(= (error/instrumentation-prose notice) (error/notice-prose notice))`.
  - `test/seon/oversight_test.clj:164-165` assert the unit's explicit `:seon.render/ai`/`:seon.render/html`. After 1b the
    unit carries `:seon.render.call/function seon.oversight/fleet-value`. Fix: assert that key.
- **1b remainder and 1c are stopped** on two genuinely new decisions (options below). 1c cannot delete the namespace stage
  before the namespace-page ruling, nor the schema stage before transcript's subject is converted (converting it while
  structural matching lives would let any map holding `:seon.turn/id` + `:seon.agent/id` select the prefix renderer).
- **Nothing is adopted or tested.** LOAD RULE: no reload into default, no test request. All schema declarations await
  publication and adoption.
- **schema.clj:3366 nested-selector targets need a root schema-test run.** `selector-target-schema` (`schema.clj:3366-3379`)
  reads `:seon.program/row-schema` from a sub-selector's identity attributes; after 0a a nested `{:ref [:seon.agent/id …]}`
  derives against `:seon.agent/agent`, where a wildcard nested selector previously refused `:seon.schema/unknown-pull-target`.
- **Base pair side effect (1a).** `:seon.error/base` now declares `seon.error/render-ai`/`render-html` (root ruling). Until the
  schema stage is deleted, structural matching also sees base; an error map whose specific schema does not match now renders
  through `seon.error/render-ai` instead of the value floor, and a specific schema tying base on specificity would be an
  ambiguity. Unproven either way; root's render tests below cover it.
- RESET NEEDED: no.

## Commits

| Commit | Slice | Paths | Lines |
|---|---|---|---|
| `9b4a7522f` | 0a | `src/seon/program.cljc`, `src/seon/render.clj`, 36 `resources/seon/schemas/*.edn` | +92 / −48; 39 EDN lines edited in place, net +44 |
| `05dc32444` | 0b | `src/seon/render.clj` | +62 / −0 |
| `1b7c0493c` | regression | `test/seon/render/entity_pairs_test.clj` | +27 / −0 |
| `3784764c1` | 0 | `test/seon/program_test.clj` (family = row schema + source attribute) | +4 / −3 |
| `8ebb23736` | 1a | `src/seon/render.clj`, `seon.error.edn` (base pair), `seon.render.call.edn` (`:seon.render.call/function`), `seon.render.edn` (`:declared` stage name) | +46 / −6 |
| `3fce22c5e` | 1a | `test/seon/render_simplification_test.clj` (stage vectors include `:declared`) | +5 / −5 |
| `2e42564f5` | 1b | `src/seon/oversight.clj`, `src/seon/error.clj`, `seon.oversight.edn`, `seon.error.edn` | +29 / −21 |
| `959f14142` | 1b | `src/seon/sci/eval.clj` (shown-result carriage), `src/seon/render.clj` (`head-function` public) | +8 / −2 |

Net: **+225 / −37 = +188 lines** across the eight code/test commits; nothing is deleted yet (1c deletes ~260).

## What landed

- **0a.** A disposable scripted sweep (one line per attribute, refused on ambiguity; its file/line/owner table is exactly the 39 lines of `git show 9b4a7522f -- resources`)
  added `:seon.program/row-schema <owner>` to 39 data identity attributes. Each owner is the attribute's only
  partition-declaring entity schema. For `:seon.agent/id`, the owner is `:seon.agent/agent` rather than `:my.plan/agent-state`.
  Two attributes were skipped because no entity holds them: `:seon.config/agent` and `:seon.source/digest`.
  `seon.program/source-family?` now defines a program family as row-schema **and** source-attribute. It is used by
  `identity-attributes`, `shapes-in` and `declaration-families`. `seon.program.edn` updates the property description.
  In `seon.render`, `entity-owners` is cached once per projection (identity → row, row → entry keys plus `:db/id`), and
  `entity-owner` returns the single row schema that names every identity present and owns every key present.
- **0b.** In `seon.render`:
  - `output-alternatives` is cached per `[projection function]`. It uses Malli `-function-schema-arities` and `-function-info`
    output, flattened through `:or`/`:maybe`/`:and` and named unions, without `:seon.error/base`.
  - `satisfied-alternatives` validates a value with `schema/projection-validator` (Malli's cached `m/validator`).
  - `error-alternatives` reads the value's own `:seon.error/operation`.
  - `head-function` resolves a top-level call's head with `sci/find-ns`, `sci/binding [sci/ns …]`, `sci/resolve` and
    `sci/var->symbol` to a contracted Var that is not a macro.

- **1a.** `declared-producers` (render.clj): an error's own operation's satisfied alternatives, else the root call's
  `:seon.render.call/function` returns (only when the node is `identical?` to the request's root value), else the entity
  owner; pairs are read through `internal/entity-properties`, so `[:and :seon.error/base [:map …]]` finds its own pair or
  the base pair. The `:declared` stage runs before `:schema` in `selection`; nested `declared-producer` asks it first. No
  match falls through; nothing is removed.
- **1b (part).** `oversight/unit` supplies `:seon.render.call/function seon.oversight/fleet-value`; `:seon.oversight/fleet`
  and `:seon.oversight/unavailable` declare `ai-story`/`html-table`, whose input became `:seon.render/unit` (the old
  `:seon.oversight/unit` input made publication refuse the pair: probed). `error/notice` stops stamping `:seon.render/ai`;
  `:seon.error/notice` declares new `seon.error/notice-prose`, which makes notice's former arm choice. The successful
  evaluation in `sci/eval.clj` resolves its form's head and carries it into `shown-result`; `prepare` hands that unit to
  `project-node` with the root value, so no `render/value.clj` edit was needed (not "after floor lane").

## Evidence

All MCP calls used JVM mode, root `/Users/sean/src/seon`, cluster `default` (PID 67823, faulted, 15 open faults) and `read_only`.
The probes ran in throwaway namespaces `render-dispatch-opus.probe-0a` and `render-dispatch-opus.probe-0b`;
disposable probe files were removed after the run. No default Var was redefined.

**Census.** 48 identity attributes. Only the 6 program families carried row-schema before this change. Each data identity has
exactly one entity schema that declares a partition, except `:seon.agent/id` (2) and `:seon.fn/sym` (2, already declared). 261 ms.

**0a probe.** The 39 changed forms were overlaid onto the carried projection with `projection-with-schema` (760 ms, probe only).
Results:
- 45 identity rows, 43 owned row schemas; deriving the owners took **1.13 ms**, once per projection.
- Program families are unchanged: `#{:seon.schema/key :seon.fn/sym :seon.ns/name :seon.lint/id :seon.test/sym :seon.fn.file/relative-path}`.
- Owner results:

| Value | Owner |
|---|---|
| Envelope `{:seon.agent/id :seon.turn/id}` | nil |
| Agent id plus a foreign key | nil |
| Partial agent | `:seon.agent/agent` |
| Live pulled root agent, 0 foreign keys | `:seon.agent/agent` |
| Error id plus signature | `:seon.error/error` |
| `{:db/id 1}` | nil |
| `{}` | nil |
| Function row | `:seon.fn/fn` |

**0b probe.** Run on the projection without its cache holder, so the probe wrote nothing into the shared cache. 12 lookups took **1.0 ms**
in total, uncached. Results:

| Lookup | Result |
|---|---|
| `ambiguity`'s own operation | `[:seon.render/ambiguous-error]` |
| Uncontracted operation | nil |
| Contracted operation, no satisfied alternative | `[]` |
| `seon.fs.jvm/flat-error` | 16 alternatives |
| `(seon.render/selection {})` | `seon.render/selection` |
| `(schema/projection-validator …)` in `seon.render` | `seon.schema/projection-validator` |
| `let`, `->`, literal, interop, missing namespace | nil |

**Reach census (0b).**
- Of 326 paired schemas, 131 are named returns of some contracted function.
- 254 of the 326 pairs name the generic `seon.error/render-ai`. `:seon.error/base` declares no pair.
- The non-generic error producers reachable only through operation lookup are `seon.error/index-refusal-prose` (12),
  `mcp-prose` (5), `render-faults-ai` (2) and `seon.background/render-ai` (3).

**1a/1b probes** (scratch namespaces, projection overlays without the shared cache holder):
- `declared-producers`: live root agent → `[seon.cluster.agent/render-identity-ai]` (html `render-identity-html`), the same as
  today's `schema-producers`; ambiguity error → `[seon.error/render-ai]`, same as today; envelope, attribute-scoped request,
  no projection and uncontracted error → `[]`. 7 lookups 3.7–5.2 ms uncached.
- Overlaying `:seon.error/base` with its pair and `:seon.render.call/function` publishes (94 ms). The fleet pair publishes
  once `ai-story`/`html-table` take `:seon.render/unit`; then fleet → `ai-story`/`html-table`, unavailable → `ai-story`,
  and a nested fleet (not the root) → `[]`.

**Not measured.** Hot-path render timing, because dispatch is unchanged, and allocation.

## Stopped: 1b remainder and 1c need two rulings

**Decision 1: namespace-owned pages.** `walk.clj:774`, `bootstrap.clj:324` and `web.clj:1463` pass `:seon.render/namespace`
so a namespace can render its own `:seon.ns/ns` row (`my.turn/namespace-unit` = `[:seon.ns/name [:= my.turn]]` with
`my.turn/render-namespace-ai` → `seon.run/render-namespace-ai`, plus `my.turn/usage-form` for `:seon.render/form`). Under
rule 3 that row is owned by `:seon.ns/ns`, whose pair would capture it.
1. **Simplest (recommended): delete namespace-specific pages.** `my.turn`'s page renders through the `:seon.ns/ns` pair; its
   lifecycle prose moves to the namespace docstring; delete `render-namespace-ai` (run.clj, my/turn.clj), the three fixtures
   and their tests with the namespace stage. Guarantee: one ns page renderer. Cost ~−120 lines. Sacrifice: the curated
   my.turn page and usage form.
2. **The ns pair delegates by declaration.** The `:seon.ns/ns` renderer looks up a function the namespace declares on its
   row (a new optional attribute, e.g. `:seon.ns/page`), resolved and contract-checked. Cost ~+40/−100. Sacrifice: a
   renderer symbol stored as data, which the design disallows.
3. **Keep the namespace stage for `:seon.ns/ns` rows only.** Cost ~+5/−80. Sacrifice: a second selection path survives.

**Decision 2: MCP head Var (`cluster.clj` mcp-project).** MCP JVM-mode `prepare` carries no `:seon.sci.eval/ctx` or database
(`cluster.clj:466-471`), so `project-node` never runs (`render/value.clj:470`): no declared renderer applies to any MCP value
today, and a head Var would change nothing. The form also never reaches `mcp-project`, and a JVM-mode head is a JVM Var, not
an SCI one.
1. **Simplest (recommended): MCP JVM mode stays floor-only**; the design line for MCP is removed; MCP SCI mode already goes
   through `shown-result`. Cost 0. Sacrifice: JVM-mode results never use declared pairs.
2. Give MCP's `prepare` the cluster ctx and database, and resolve the JVM form's head with `clojure.core/resolve`, passing the
   form from `mcp-valf`. Cost ~+20 in cluster.clj plus the MCP bridge caller (script/seon/dev/mcp.clj, not in this row).
   Sacrifice: SCI producers run on every MCP JVM read (time-limited, but new cost and failure surface).
3. Only the ctx/database half: nested errors and entities get declared pairs in MCP; no head Var. Cost ~+6.

**Ready once ruled (1c, estimated ≤100 added):** transcript.clj:2126 gets a contracted `prefix-subject` producer and
declared pair in place of its explicit request (lands with the schema-stage deletion); delete the explicit-value,
explicit-request, namespace (per ruling) and schema stages, specificity ranking, pulled/transacted matching and
`cost-shape-key`'s matching; keep `matching-shapes-in` for diagnostics; update the six tests.

## Out of scope, recorded elsewhere

- The MCP exception face is replaced by `seon.cluster/first-seon-frame refused trace at [3 2]: expected a string, got nil`.
  It reproduced here from a `load-string` NPE. Already recorded as D4 in
  `docs/research/agent-platform/repl-investigation-2026-09-23.md:109` (fix E5 in `error-floor-investigation-2026-09-23.md:210`).
- MCP results were clipped at 32 children (`:seon.render.profile/max-children`), for example a 48-row census. That is design
  audit site 2/13, owned by render-floor-opus.

## Root runs (serially, after adopting the commits)

1. `bin/test-check default --ns seon.render.entity-pairs-test`
2. `bin/test-check default --ns seon.program-test`
3. `bin/test-check default --test seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops`
4. `bin/test-check default --ns seon.schema-test` (schema.clj:3366 nested-selector targets)
5. `bin/test-check default --ns seon.schema.datahike-test`
6. `bin/test-check default --ns seon.render-simplification-test`
7. `bin/test-check default --ns seon.oversight-test` (expect `:164-165` RED until fixed)
8. `bin/test-check default --ns seon.error-test` (expect `:715`, `:735` RED until fixed)
9. `bin/test-check default --ns seon.render.web-test`
10. `bin/test-check default --ns seon.sci.eval-test` (shown-result head carriage)
11. `bin/test-check default --changed seon.program/shapes-in`, `--changed seon.program/declaration-families`,
    `--changed seon.render/selection`, `--changed seon.error/notice`, `--changed seon.oversight/unit`

Held paths: this lane's row stays held for 1b remainder/1c after the two rulings.
