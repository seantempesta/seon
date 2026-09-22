# Validator, deletion and oversight diff review — 2026-09-23

Read-only review of the eight requested commits and their landings; AGENTS.md read first.
Citations are pinned: A = `6bf3bde78`, B = `f31074521`, C = `b44585c0b`.
The other requested commits change evidence, a test set comparison, or the plan; they do not repair the findings below.
P1 = correctness/data-loss risk; P2 = contract, performance or diagnostic defect.

## A — validator-single-pass

1. **P2 — Transaction-entity roots break the claimed exact equivalence.**
   `src/seon/db.clj:3231` filters *all* datoms below `tx0` before collecting direct
   arity-bearing roots. The old predicate (`6bf3bde78^:src/seon/db.clj:3225`) checks
   `root = entity` before applying that bound to the ancestor case.
   Example: a transaction entity carries `:seon.fn/sym` and gains an existing arity
   component; only the transaction entity has effective datoms. The component value
   seeds discovery (`src/seon/db.clj:3171`), so its transaction-entity owner is a root,
   but the new direct-root set is empty. Old decision=true; new=false.
   The report contract permits integer entity ids (`resources/seon/schemas/seon.db.edn:283`);
   Datahike supports `:db/current-tx` entity maps (`reference-code/datahike/src/datahike/db/transaction.cljc:948`).
   **Smallest fix:** collect direct arity-bearing entities from unfiltered tx-data;
   apply `< tx0` only to the ancestor-walk inputs, preserving the old predicate.
   Evidence is a pure decision counterexample, not a completed armed transaction.
2. **P2 — The new contract does not cover the accumulator's contents.**
   `src/seon/db.clj:3104` accepts every volatile, including `(volatile! 42)`;
   `:3250` then uses it as a collection of identity attributes. Malformed input enters
   and throws or accumulates the wrong shape instead of refusing at entry.
   **Smallest fix:** declare a volatile holding a set of qualified keywords and validate
   that value; add an incremental arity-change regression alongside the doc-only case.

For ordinary entity roots, no ordering/refusal/id regression found: the before/after
walks (`src/seon/db.clj:3177`) precharge every revisited owner; roots, validation order,
`vswap!` position and component-refusal ids remain unchanged (`:3244`, `:3252`, `:3256`).
The pure comparison passed 3,888 before/after ownership cases with ids below `tx0`.
Laziness preserves doc-only skipping; call facts, arity relations and owned-child
changes trigger the gate, and schema/default identities remain unconditional (`:3553`).
The new test (`test/seon/owned_value_test.clj:107`) uses non-function identities and
never forces this delay; it does not prove the incremental arity decision.

## B — deletion-caller-edge

1. **P1 — Re-analysis loses a newly introduced replacement target.**
   `src/seon/fn.clj:2391` analyzes the selected files, then `:2406` analyzes callers
   separately against the *previous* database. Its first-party set is only stored
   symbols plus that second batch's definitions (`:2264`, `:2269`). Delete producer
   `produce`, add `replacement`, and retarget a schema declaration in the same write:
   the unchanged invoker is selected by its old edge, but `replacement` is absent
   from its first-party set and is filtered out at `:1186`. Publication loses reach.
   **Smallest fix:** supply the selected batch's final function-symbol set to caller
   analysis (subtract removals), or analyze the combined selected/caller population.
   Regression: replacement under a declared invocation retains the new edge.
2. **P1 — A supplied file row is treated as proof of complete reconciliation.**
   `src/seon/fn.clj:3344` widens from mere `:seon.fn.file/relative-path` presence;
   `rows` trusts supplied rows (`:2572`). A file-only row for an unselected B file
   admits B to deletion reconciliation (`:3412`): its omitted declarations/lints
   become removal candidates. It also suppresses B's caller analysis (`:3465`).
   **Smallest fix:** carry completed-analysis paths from the analyzer's complete
   artifacts, and require equivalent completeness evidence for supplied populations;
   a digest/file row alone must not authorize removal or suppress a findings pass.
   Canonical `analyze-rows` supplies complete artifacts; this is the supplied-row boundary.
3. **P2 — Every nonempty analysis gains another whole-schema traversal.**
   `src/seon/fn.clj:2259` passes `(vals forms)` to `declared-function-targets`;
   `:563` walks every nested form even for an empty invocation set. The later row
   builder already walks that population (`:1186`); deletion callers add another batch.
   **Smallest fix:** short-circuit empty attributes, then derive the target relation
   once per immutable declaration world and share it between discovery and row building.
   This is O(all schema-form nodes); it does not re-analyze all source files.
The AVET lookup (`src/seon/fn.clj:2341`) is indexed and proportional to removed names
and stored callers; it then analyzes whole caller files. Declared-only callers with
materialized edges are included; missing historical edges cannot be recovered.
Schema-only additions remain a documented invalidation gap
in `docs/seon/issues/incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives.md:74`.

## C — oversight follow-up

1. **P2 — “Every member has a contract” still does not mean complete contracts.**
   `src/seon/oversight.clj:81` leaves `proc-ping` at `[:maybe :map] -> :map`, even
   though `resources/seon/schemas/seon.oversight.edn:37` now declares its result.
   `(proc-ping :probe/p {})` enters and yields `:ping :reply` with `:passes nil`;
   its own contract accepts that invalid observation. `occupancy` likewise admits
   arbitrary maps before consuming buffer fields (`src/seon/oversight.clj:68`).
   **Smallest fix:** wire `proc-ping` to `:seon.oversight/proc-observation` and declare
   the consumed Flow reply/channel shapes, keeping absent replies explicitly unknown.
2. **P2 — No-`:any` is not satisfied.**
   `resources/seon/schemas/seon.oversight.edn:16` explicitly defines `:proc` with
   `:any`; `src/seon/oversight.clj:81` retains a separate inline `:any` for the same
   pid. Both have polymorphic-boundary explanations, so this is not an undeclared
   exemption, but the requested stronger claim is false.
   **Smallest fix:** reuse the single pid declaration; to eliminate `:any`, first
   declare the supported identifier grammar at the graph producer and use it here.
   Do not silently narrow the dependency's arbitrary identifier domain at a renderer.

Prior finding 1 is fixed for absent members: `live-state` (`src/seon/oversight.clj:152`)
returns explicit missing-state data, rendered by AI and HTML (`:227`, `:279`);
only a present empty armed map means empty fleet. Its output contract now declares
routing contents; present malformed values rely on arming for rejection.
Prior finding 2 is substantially improved but remains open as above.
Prior finding 3's test design is fixed: real handed graph (`test/seon/oversight_test.clj:264`),
visible missing-state cases (`:289`), and a basis-qualified SSE package after a note
wake (`:214`). The landing supplies separate scratch SSE evidence; the committed
armed test bodies remain unrun. This review does not promote that evidence to a test pass.

## Catch audit

No added catch swallows a cause in these diffs. A's catch (`src/seon/db.clj:3263`)
handles its locally tagged refusal and rethrows other exceptions; B and oversight add none.
**P2, inherited in C's touched web owner:** `src/seon/render/web.clj:167`, `:2932`,
`:3017`, `:3034` catch all `Throwable` and return nil/false; `:3413` retains only
message/class, discarding ex-data, cause chain and frames. `:734` also converts any
`FileNotFoundException` from requiring a function (including a missing transitive
resource) into “function unavailable.” **Smallest fix:** handle declared malformed
input/absent-target cases narrowly; rethrow unexpected failures or pass the complete
throwable to the existing error normalizer. These are inherited defects, not new catches.

## Evidence, timing and limits

Disposable probe: `tmp/review-validator-deletion-oversight/probe.clj` (uncommitted).
`bb` runs: 0.04 / 0.05 / 0.06 s. It extracts B's actual target/filter helpers;
models A's two predicates; demonstrates the transaction-id difference, acceptance of
an invalid volatile, file-row-only widening, and the replacement-target loss.
With no invocation attributes, the target helper still visited all 20 supplied forms.
These are unarmed pure probes, not database publication, refusal-envelope or browser proofs.
Datahike pin: `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`; core.async pin:
`dc35f3e0d7bc2eef502e77982f48641f025c8051`. Flow ping returns only responders
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136`).
`bin/seon status` took 0.08 s; MCP runtime_status 313 ms. Default pid 36006 was
**degraded**, missing connection, source commit, SCI context and Flow graph.
No reload, publication, boot, gate or fixture suite was attempted; source review and
pure probes continued independently of this foreign runtime boundary. No source,
test, resource, other lane's files or sessions were changed.
Shell reads/probes were timed (initial reads also reported tool wall times); none
exceeded 1 s before commit. No publication/memory performance claim is made.
