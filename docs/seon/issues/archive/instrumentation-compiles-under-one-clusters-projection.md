---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, schema, class/p1, wave/seon-env-p3]
---

# The operator instruments the whole JVM under one cluster's projection state

## Resolution — 2026-09-08

The assignment explicitly chooses one loaded host program and one wrapper per
Var generation. Repeated cluster arm/remove calls preserve those roots.
Contracts compile with the calling request/context projection and are cached
on that projection; Malli's default and global function-schema registries no
longer carry Seon declarations. Both process-global projection caches are gone.
The two canonical clusters regression validates different contracts on the
same Var and proves neither removal disturbs the other.

This supersedes the historical acceptance criteria asking for independently
selected host error policy or removal of the operator's selected arm caller:
JVM host enforcement and boot report caps are shared by the ruled seam;
interpreted function policy stays cluster-local. Atomic source adoption is a
separate open issue linked below. Protected fixture/schedule migration and all
tallies are explicit in the
[landing note](../../../prds/context-generation/research/cluster-scoped-registry-landing-2026-09-08.md).

## Problem

Malli instrumentation alters Var roots PROCESS-WIDE. The operator applies it
inside one selected cluster's `call-with-projection-state`, so N co-hosted
clusters share ONE set of contract wrappers compiled against ONE cluster's
projection.

This is Defect II of the
[parallel isolation audit](../../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md)
— derived state parked in a process-wide slot — at the boot boundary.

## Evidence

`script/seon/fresh_operator.clj:1298-1316`, `refresh-instrument-form`:

```clojure
anchor      (first (filter (fn [instance]
                             (and (map? instance)
                                  (:seon.boot/cluster-connection instance)))
                           (vals instances)))
anchor-name (get-in anchor [:seon.boot/advertisement :seon.boot/cluster-name])
(when anchor (instrument-form anchor anchor-name))
```

`instrument-form` (`:1275-1296`) then reads
`(:seon.sci.eval/projection-state (:seon.sci.eval/ctx anchor))` and calls
`seon.instrument/apply!` inside `schema/call-with-projection-state` for THAT
cluster. `apply!` (`src/seon/instrument.clj:355-397`) collects over
`(all-ns)` and calls `mi/instrument!`, which replaces Var roots for the whole
process; the caps and the `:seon.config/on-core-error` dial come from the
anchor's effective config too.

`add-form` (`:1430`) runs this BEFORE starting a new cluster, and every
`start`/`stop` path re-runs it (`:2082`). The dial and the compiled validators
of whichever cluster happens to be first therefore govern every other cluster
in the JVM.

Verified 2026-08-07: two clusters were booted into one JVM with instrumentation
live and both passed, because both forked the same published commit and their
projections agree in content. The hazard is real but currently unobservable —
it becomes observable the moment two co-hosted clusters hold genuinely
different declarations, which is exactly the
[test-infrastructure spec](../../../prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md)'s
four-worker target.

Ordering note worth keeping: `launch-form` (`:1389`) instruments AFTER
`start!`, `add-form` (`:1430`) BEFORE it. So a fresh-JVM boot runs unchecked
and a co-hosted boot runs checked — which is why
[a-cohosted-second-cluster-cannot-boot](../a-cohosted-second-cluster-cannot-boot.md)
only ever surfaced on the second cluster.

## 2026-08-08 — this now BLOCKS the schema-environment fix, and the mechanism

## is narrower than "instrumentation is process-wide"

Found by implementing
[schema-environment-is-ambient-not-explicit](../schema-environment-is-ambient-not-explicit.md)'s
first acceptance criterion and measuring what broke. Evidence:
[schema-environment-explicit-2026-08-08.md](../../../prds/sci-execution-runtime/research/schema-environment-explicit-2026-08-08.md).

Restricting `seon.schema`'s registry facade to the packaged bootstrap
population — deleting the thread-local half, which is what that criterion
asks for — was implemented and proven green on the schema suites and on
`cohost-boot-test` (two real clusters, one JVM, instrumentation live). It was
REVERTED on exactly one failure out of a 178-test consumer run:

```
ERROR in (seon.instrument-test/applying-uses-the-acquired-projection-without-publishing-it)
clojure.lang.ExceptionInfo: :malli.core/register-function-schema
  at malli.core$_register_function_schema_BANG_ (core.cljc:3068)
  at malli.instrument$_collect_BANG_ (instrument.clj:50)
```

The specific dependency, which the section above does not name:
`malli.instrument/-collect!` reads a Var's `:malli/schema` metadata and calls
`malli.core/-register-function-schema!`, and that function does two
process-global things — it RESOLVES the schema against Malli's default
registry (Seon's facade, hence the thread-local selection), and it WRITES the
compiled contract into `malli.core/-function-schemas*`, one process-wide atom
keyed by namespace and symbol.

Two consequences:

1. Reading the cluster-selecting facade is the ONLY way `seon.instrument`
   currently sees a contract a cluster declared and the packaged resources do
   not. So the schema defect cannot be fixed in `seon.schema` — its only
   choices are answering wrongly on a thread hop, or refusing a caller with
   no other way to ask.
2. `malli.core/-function-schemas*` is a SECOND process-global slot of this
   issue's own class, one the audit did not reach. Even after the operator
   stops picking an anchor, two co-hosted clusters declaring the same
   function contract differently overwrite each other there.

The repair follows: `apply!` compiles the contracts it instruments from the
projection it was given, rather than delegating to `malli.instrument`'s
collection, which cannot be told which environment it is collecting for.

The reverted schema change is recorded verbatim in the registry facade's
comment in `src/seon/schema.clj` and is the falsifier for this issue: with it
re-applied, `applying-uses-the-acquired-projection-without-publishing-it`
must pass.

## Owner

### 2026-09-06 development reload evidence

The operator applies instrumentation dynamically through
`script/seon/fresh_operator.clj`'s `instrument-form` and
`refresh-instrument-form`; literal searches for `instrument/apply!` miss that
invocation. Source publication explicitly removed ALL wrappers before reloading,
then restored them in a finally that covered only publication, not the preceding
reloads. Thus a reload failure could skip restoration; any live call during the
publication interval was unchecked. Root observed 829 eligible public callable
Vars and zero wrapped, then 829 wrapped with no missing Vars after the existing
finally restored them. JVM and fresh SCI `(whoami :invalid)` then both returned
`contract-violated` / `invalid-input`. First-party SCI Vars forward to the actual
host Var, so its restored wrapper also protects that route.

The current correction removes global uninstrumenting and extends the existing
restoration finally around reloads. The opted-in development refresh already
admits exactly one cluster per JVM; it also reapplies the existing owner with
that cluster's exact projection before marking adoption complete. The initial
call refused because effective configuration was read without handing the
projection; that refusal did not prove the new call succeeded. After removing
global uninstrumenting, publication exposed a schema-definition validation
failure in `resolve-malli-form-in` for `:string`. Its `malli-form?` predicate
caught `missing-projection` from the declaration registry and reported false.
Handing one declaration projection to the prospective source publication fixes
that missing input while leaving wrappers installed. The next publication
succeeded: commit `6a9e063f-bac9-5ba6-b504-83c18c105add`, digest
`f61ca1a83f10457e62a0284170a89440573457786ff95fc63560a47a25569a7f`.
Root reran the retained instrumentation probe: 829 eligible / 829 wrapped,
no missing Vars; JVM and fresh SCI invalid inputs were contract errors, and the
existing interpreted wrapper refused its bad output.

Per-Var replacement during reload still has a narrower unchecked interval.
During a subsequent publication root observed 839 eligible / 627 wrapped /
212 missing (including ten private contracted Vars excluded by the then-current
selection). Strict atomic replacement is not claimed. The recurring generated
init regression covers early reload failure, publication failure, and success:
each runs the same restoration once, retains the handed projection, and never
calls global `remove!`.

This does not resolve cohosted policy: `:record` globally unstruments, and one
`:panic` collection compiles process-wide wrappers under one projection. Boot
retains its existing operator mechanism.

`script/seon/fresh_operator.clj` (`refresh-instrument-form`,
`instrument-form`) and `seon.instrument/apply!`. The repair belongs to the
[seon.env PRD](../../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)'s
Phase 3 slice "move the compiled caches onto the projection": derived state
hangs off the value it derives from, so two projections cannot exchange a
validator.

## Acceptance criteria

- Instrumenting under cluster A's projection cannot change what cluster B's
  contracts validate, with two co-hosted clusters holding DIFFERENT
  declarations (the current regression at
  `test/seon/cluster/cohost_boot_test.clj` proves the same-declaration case
  and is the place to extend).
- The `:seon.config/on-core-error` dial and admission caps that govern a
  cluster's contract reports are that cluster's own, not the anchor's.
- No selection of "the first running instance" survives in the operator.

## 2026-09-06 coverage audit: what the wrapper count does and does not prove

A read-only query against the `juniper-context` program graph classified a
first-party function by its indexed `:seon.fn/ast` fact, rather than by a
namespace prefix. This is the source indexer's own distinction: `var-row`
creates source function rows and their contracts from analyzer metadata
(`src/seon/fn.clj:345-394`), while referenced external functions are admitted
as identity-only rows (`src/seon/fn.clj:1738-1759`). The current graph contains
873 first-party function rows:

- 860 public rows, all 860 with `:seon.fn/spec`; zero public rows lack a
  contract;
- 13 private rows, all 13 with `:seon.fn/spec`;
- 3,382 other `:core` function rows without an AST (dependency and external
  program identities), of which 756 are public rows without a Seon contract;
- zero `:agent` function rows in this particular database value, so it is not
  empirical evidence that an authored function was wrapped.

The independent loaded-JVM measurement after the operator's final
instrumentation pass was 829 loaded public contracted Vars and 829 wrappers;
the earlier pass had zero wrappers after a global removal. That proves complete
coverage of the *currently loaded eligible Var set*, not all callable
functions and not all 860 indexed first-party public functions (31 were not
loaded as eligible Vars in that measurement).

The interpreted-function path has a separate, narrower guarantee. Acquisition
selects function assertions by transaction provenance
(`src/seon/sci/eval.clj:1471-1507`), installs every agent-authored row without
filtering on privacy (`:1657-1670`), and installs a wrapper exactly when the
committed row has `:seon.fn/spec` (`:665-675`, `:850-872`). In `:panic` mode,
`wrap-interpreted` calls Malli's one wrapper with
`:scope #{:input :output}` (`src/seon/instrument.clj:438-461`). Thus every
installed, declared, contracted interpreted function gets both input and
output validation in panic mode; an explicit caller argument still reaches
the same wrapper. In `:record` mode the function is deliberately returned
unwrapped (`:463`), so no input or output validation occurs.

The uncovered callable categories are consequently explicit:

- agent-authored `defn`s with no `:malli/schema` are installed and callable but
  have no wrapper;
- anonymous `fn` values have no `:seon.fn/sym` declaration row and therefore no
  independently installed contract wrapper;
- dependency/core SCI bindings represented only by external identity rows are
  callable without Seon's contracts unless their dependency itself validates;
- JVM instrumentation enumerates `ns-publics` (`src/seon/instrument.clj:535-538`),
  so private JVM Vars are outside that mechanism even when the source graph
  records a contract;
- Malli skips primitive function roots (`reference-code/malli/src/malli/instrument.clj:15-26`).

This audit therefore supports “all loaded public contracted eligible Vars were
wrapped” and “all installed contracted interpreted functions validate input
and output in panic mode.” It does not support “all functions are wrapped.”

After the private-Var enumeration amendment was published, root repeated the
same live coverage probe: 839 eligible contracted callable Vars, 839 wrapped,
no missing Vars. The JVM invalid input and existing interpreted wrapper's
invalid input/output were typed contract errors. Published marker:
`6a9e07b4-2452-556e-8084-318bbc695134`, digest
`688303b93f4ead5e2c0e53dcba664dc58427180f165c6803594c6598ac114eb0`.
This supersedes the audit's then-current private-JVM exclusion; the other
uncontracted/dependency categories and the reload interval remain unresolved.

The combined recurring gate `bin/test seon.instrument-test
seon.dev.source-instrumentation-test` passed 24 tests / 188 assertions
(run.NWn8nC). Root also called the actual private filesystem effect handler
with invalid input and observed `seon.instrument/contract-violated` /
`invalid-input`. These are post-publication observations; the documented
per-Var replacement interval remains outside the guarantee.

Cold-start correction: moving the restoration around the complete init body
changed its compilation boundary. A fresh JVM compiled qualified Seon calls
inside the new `let`/`try` before the runtime `require`, producing
`ClassNotFoundException seon.cluster`; a preloaded test worker hid this.
Generated init and named-init now use the existing `ns-resolve` calling
convention for every runtime owner. A fresh child JVM compiles six publish,
non-publish, named, forced, and development init variants while asserting
`seon.cluster` is absent both before and after compilation. The focused gate
passed 2 tests / 95 assertions (run.DSrXLa). Root's normal cold publication and
named non-publish init both succeeded, with publication commit
`6a9e0964-b1c2-51fe-9f6d-94228e188eb5`, digest
`2101a3e72457465d942d1b2eea3f8c090c8bf761c977528bde7cc4dd843aa6ee`.

## Adoption ownership follow-up

The mixed-generation adoption evidence is retained in
[development-adoption-can-mix-host-and-sci-generations](../development-adoption-can-mix-host-and-sci-generations.md).
It is not resolved by stable wrapper ownership.

## 2026-09-08 cohosted concurrency audit reaffirmation

Three agents each in default and beta completed 60 concurrent provider-free
source turns in one JVM (2.90461 turns/s). Private scalar defs and evaluation
handles were agent-local; an installed contracted function was cluster-local.
This does not clear this issue: current `src/seon/instrument.clj:629` still
collects all JVM Vars into Malli's global namespace/symbol registry, and
`:685` applies or removes wrappers globally. First-party SCI callables still
forward shared host Vars. Independent programs/contracts remain unproven and
structurally coupled. No production repair was attempted across held owners.

The [concurrency landing](../../../prds/context-generation/research/multi-cluster-concurrency-landing-2026-09-08.md)
records the complete global-state inventory, measurements and design choice.


The concurrency lane's platform gate at snapshot `d1e8f4435` exited 0
(74 tests / 404 assertions), but its drift detector reported 949 wrappers
removed by `seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`.
That snapshot's finally explicitly calls `instrument/remove!`. The current
working tree has another lane's uncommitted preservation-fixture correction;
this lane did not change or verify it. This measured test-teardown defect is
separate from the production cross-cluster contract ownership defect above.

## P1 verification — 2026-09-15

The archived shared-host-program ruling still applies. The P1 arming change
selects a database argument's immutable carried projection before existing
request/thread/host inputs; wrapper compilation remains cached on that
projection. Report functions are outside this lane's change. The isolated
armed `seon.instrument-test` namespace passed with the database/schema tests;
the core aggregate was 91 tests / 676 assertions, zero assertion failures or
errors, before the test-root result-publication setup was completed.

Default hot reload and explicit re-arming reported 990 instrumented Vars.
The separate shared-host bootstrap policy is preserved, and no complete
cohost lifecycle claim is made. Exact gates and remaining explicit-carriage
work are in the [P1 landing](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md).

## Final P1 carriage handoff — 2026-09-15

Implementation: `b80f78a7c`. The [P1 landing note](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md) records the live probes, measured allocations, exact remaining boundaries and pending orchestrator gate. Database metadata now participates in instrumentation and admission; no running read/admission fallback reconstructs the projection. This closes only the member's read/admission carriage defect, not adoption/lifecycle or the remaining explicitly supplied thread compatibility input.
