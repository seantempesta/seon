---
type: landing
status: current
created: 2026-09-24
scope: One render path slices 0a and 0b landed; slice 1 stopped with a smaller cut
---
# Lane render-dispatch-opus: one render path slices 0a, 0b (1 stopped) — 2026-09-24

Design: `docs/research/agent-platform/one-render-path-design-2026-09-25.md` §1, §6 rows 0a/0b/1.

## Broken or unproven first

- **Expected RED from 0a: `seon.program-test/program-identity-attributes-are-exactly-the-declared-row-schemas`**
  (`test/seon/program_test.clj:1294-1307`). The test says every attribute declaring `:seon.program/row-schema` is a program
  family. The design now declares that property on every identity attribute, so the test's `declaring` set is 45, not 6.
  The fix is one line at `:1300`: also require `:seon.program/source-attribute`, the rule `seon.program/source-family?` uses.
  I do not own that file, so its owner or root needs to make that edit.
- **Slice 1 stopped before writing.** Its atomic sweep crosses files outside this row and exceeds 100 added lines (see below).
- **Nothing is adopted or tested yet.** Hook publication is paused. No namespace was reloaded into default, and no test request ran
  (LOAD RULE). The 39 schema declarations still need publication and adoption. Until then the installed projection lacks them.
- **Observable accretion in schema.clj, which I do not own.** `selector-target-schema` (`schema.clj:3366-3379`) reads
  `:seon.program/row-schema` from the sub-selector's identity attributes. After adoption, a nested selector such as
  `{:ref [:seon.agent/id …]}` now derives its pulled form against `:seon.agent/agent`. Before, it had no target, and a
  wildcard nested selector refused with `:seon.schema/unknown-pull-target`. I believe this is correct, but it is unproven.
  Root should run the schema tests below.
- RESET NEEDED: no.

## Commits

| Commit | Slice | Paths | Lines |
|---|---|---|---|
| `9b4a7522f` | 0a | `src/seon/program.cljc`, `src/seon/render.clj`, 36 `resources/seon/schemas/*.edn` | +92 / −48; 39 EDN lines edited in place, net +44 |
| `05dc32444` | 0b | `src/seon/render.clj` | +62 / −0 |
| `1b7c0493c` | regression | `test/seon/render/entity_pairs_test.clj` | +27 / −0 |

Net: **+133 source/declaration/test lines, 0 deleted**. Dispatch is unchanged, and the new private functions are not called yet
(clj-kondo reports them as unused private vars until slice 1).

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

**Not measured.** Hot-path render timing, because dispatch is unchanged, and allocation.

## Slice 1: stopped, with the smaller cut

These removals break callers outside this row that the design does not name:
- **Explicit-value stage**: `src/seon/error.clj:722` stamps `:seon.render/ai` with `instrumentation-prose`/`ai-prose` on every
  fault presentation value.
- **Explicit-request stage**: `src/seon/render/transcript.clj:2126` supplies `render-captured-prefix`.
- **Namespace stage**:
  - Tests `test/seon/render_simplification_test.clj`, `render_source_test.clj`, `render/transcript_run_test.clj`,
    `render/retained_test.clj`, `test/my/turn_test.clj` and `schema/datahike_parity.edn`.
  - `:seon.render/form` output through `bootstrap.clj:324`.
- **Carrying the head Var into `shown-result`**: it reaches selection only through `render.value/prepare` → `project-node`
  (`render/value.clj:470-476`). render-floor-opus owns that file. Only a root-identity check inside `project-node` would avoid
  editing it.

About 17 test files also supply explicit producers. The atomic conversion plus deletion is well over 100 added lines across
at least six owners. Proposed smaller cut, for review before writing:
- **1a** (`render.clj`, `sci/eval.clj` shown-result, `cluster.clj` mcp-project; about 45 lines added): add one `:declared` stage
  before `:schema` that applies error → head function → entity owner. Declare `seon.error/render-ai`/`render-html` on
  `:seon.error/base` as the base pair in the same commit. Carry `:seon.render.call/function`, checked against the root value by
  `identical?` in `project-node`. Nothing is removed, so no pair is lost.
- **1b** (error.clj, transcript.clj, oversight, run/my.turn owners): declare pairs in place of explicit stamps and requests.
- **1c** (render.clj plus the six test files): delete the explicit/namespace/schema stages, specificity, pulled/transacted
  matching and `cost-shape-key`'s matching. Keep `matching-shapes-in` for diagnostics.

## Out of scope, recorded elsewhere

- The MCP exception face is replaced by `seon.cluster/first-seon-frame refused trace at [3 2]: expected a string, got nil`.
  It reproduced here from a `load-string` NPE. Already recorded as D4 in
  `docs/research/agent-platform/repl-investigation-2026-09-23.md:109` (fix E5 in `error-floor-investigation-2026-09-23.md:210`).
- MCP results were clipped at 32 children (`:seon.render.profile/max-children`), for example a 48-row census. That is design
  audit site 2/13, owned by render-floor-opus.

## Root runs (serially, after adopting the three commits)

1. `bin/test-check default --ns seon.render.entity-pairs-test` (new regression plus existing pair walks)
2. `bin/test-check default --ns seon.program-test` (expect the one RED above until `:1300` is fixed)
3. `bin/test-check default --test seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops`
4. `bin/test-check default --ns seon.schema-test` (pulled-selector target accretion)
5. `bin/test-check default --ns seon.schema.datahike-test` (stored schema projection of the new property)
6. `bin/test-check default --ns seon.render-simplification-test` (dispatch unchanged)
7. `bin/test-check default --changed seon.program/shapes-in` and `bin/test-check default --changed seon.program/declaration-families`

Released paths: all paths in this lane's ledger row (no further edits pending).
