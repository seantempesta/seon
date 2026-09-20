---
type: research
status: blocked
created: 2026-09-21
tags: [error-model, kind-retirement, sci, program]
---

# Kind sweep — sci-program

## Result

Stopped before production edits at the error-conversion PRD section-6
required-member collision. The family is not converted. This is a schema
decision, not a stop caused by another lane's in-flight breakage.

Read the binding error-conversion PRD and all four requested landing notes
end to end: test-system, ops-effects, my-protocol, and error-family-1a.
Read the supplied AGENTS instructions, including lane rules 11–16; consulted
the active roadmap and the inventory sections for these files. Used the
data-oriented-clojure, clojure-testing and repl skills. The first history
inspection was `git log -12 --stat -- src/seon/sci`. Existing acquisition,
row-acquisition and kernel facets were inspected; no duplicate facet was
created. Both standing rulings remain accepted.

## Exact section-6 evidence

At inspected HEAD `86c3a73d49596488ab5623b39dd3b8fb6791d73e`:

- `resources/seon/schemas/seon.test.edn:247` declares
  `:seon.test/unknown-error` as base plus required `:seon.test/unknown`.
- `resources/seon/schemas/seon.test.edn:260` declares `:seon.test/expired`
  as `[:and {:description ...} :seon.test/unknown-error]`, adding no member.
- `src/seon/sci/kernel.clj:568-570`, `failure-value`, and
  `src/seon/sci/admit.clj:582-584`, `semantic-value`, name both in one output
  union. These inspected files and the test resource were clean.

The [committed probe](sci-program-facet-overlap-2026-09-21.clj) uses
`seon.schema.edn/packaged-forms` and `seon.schema.form/map-entries`, not a
hand-rostered fixture. It also checks each loaded function's output metadata.
The inherited-entry owner follows aliases and conjunctions at
`src/seon/schema/form.cljc:27-86`; `seon.error/facet-keys` includes vector
`:and` base extensions at `src/seon/error.clj:1875-1892`, so `expired` is not
excluded as a bare keyword alias.

Command:

```sh
clojure -M docs/prds/steward-platform/research/sci-program-facet-overlap-2026-09-21.clj
```

Exact substantive output:

```clojure
:loads
{:sci-program/required-members
 {:seon.test/expired
  #{:seon.error/at :seon.error/layer :seon.error/operation :seon.test/unknown}
  :seon.test/unknown-error
  #{:seon.error/at :seon.error/layer :seon.error/operation :seon.test/unknown}}
 :sci-program/output-unions
 {seon.sci.admit/semantic-value [:seon.test/unknown-error :seon.test/expired]
  seon.sci.kernel/failure-value [:seon.test/unknown-error :seon.test/expired]}
 :sci-program/shared-required-members true}
```

This is not a claim that the alias currently breaks runtime validation.
The 1a note already observed that composed observations satisfy both. The
new decision for this lane is how the later literal section-6 prohibition
applies to those two explicit SCI unions. The prior marker-only and raw-value
rulings do not decide that alias rule. No generic predicate, marker or
per-facet EDN representation was introduced.

## Three priced options

1. **Declare only `:seon.test/unknown-error` in the two SCI unions
   (recommended).** Guarantee: every currently admitted expiry observation
   still satisfies the declared test-unknown facet, and pass-through retains
   its entire value. Cost: approximately 30–60 minutes for the two contract
   edits, preservation assertions and focused verification, excluding the
   remaining sweep. Give up: the redundant expiry name in these two output
   unions; no distinction is currently encoded in the value. The owner must
   confirm this treatment under section 6 and the facet-manifest checks.
2. **Give expiry distinct substantive evidence at its test producer.**
   Guarantee: consumers can identify expiry by a required observation, such
   as its actual bound and unfinished work, rather than message text. Cost:
   approximately 2–4 hours across the test producer, its resource, exact
   contracts and canonical regressions, plus owner coordination. Give up:
   an SCI-only slice; `src/seon/test.clj` and its resource are outside this
   lane's ownership.
3. **Explicitly exempt equivalent aliases at polymorphic pass-throughs.**
   Guarantee: the existing values and both union names remain accepted;
   producer-specific consumers still require meaningful observations. Cost:
   approximately 1–2 hours for a precise PRD ruling and regression covering
   alias equivalence. Give up: the unconditional section-6 shared-member
   prohibition at those boundaries. No such exception was inferred here.

These are estimates, not measured runtimes. The finding is also recorded in
[the issue](../../../seon/issues/sci-error-unions-name-indistinguishable-test-facets.md).

## Census and verification

Matching-line counts for
`:seon.error/kind|seon.error/class|error/error?`; dated to the inspected tree:

| Family | Source before → after | Schema before → after | Tests before → after | Fast tally |
| --- | ---: | ---: | ---: | --- |
| eval | 33 → 33 | 9 → 9 | 16 → 16 | Not run; no changed inputs |
| kernel | 2 → 2 | 7 → 7 | 1 → 1 | Not run; no changed inputs |
| admit | 4 → 4 | 1 → 1 | 1 → 1 | Not run; no changed inputs |
| reader | 2 → 2 | 5 → 5 | 14 → 14 | Not run; no changed inputs |
| program | 11 → 11 | 4 → 4 | 8 → 8 | Not run; no changed inputs |
| Total | 52 → 52 | 26 → 26 | 40 → 40 | No green claim |

Reader's actual path is `src/seon/sci/reader.cljc`, not `.clj`.
Eval test counts include eval, instrumentation, documentation, shown-text and
fork-isolation files; admit includes its declaration-population test. Zero-hit
files are included in these totals.

The probe's one foreground JVM required `seon.sci.eval`, `seon.sci.kernel`,
`seon.sci.admit`, and `seon.program`, printed `:loads`, and exited 0.
clj-kondo on the probe: **0 errors / 0 warnings**. No test JVM or cold gate
was launched. No unchanged test namespace was rerun. No default lifecycle,
adoption, hot reload, shared SCI evaluation or browser operation was performed;
this is file/loaded-metadata evidence, not a live-cluster behavioral proof.

The documentation hook reported two foreign citation errors in
`docs/prds/steward-platform/plan/wave-3a-task-family-spec-2026-09-21.md`
and `wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md` in the
same directory: each cites Datahike gitlink
`fcbd8862800e638dc0f8f5521111f999279cbcd2` while the installed gitlink is
`e11845bac78e1241bca0766ddc07d978bd63d74a`. Those authorities were not edited.
This is a documentation-lint boundary, separate from the zero-warning probe
lint and the verified section-6 condition.

## Held-owner handoffs and remaining work

No held or foreign dirty bytes were edited or included in a commit.
The current kind sites remain with their named owners:

- `src/seon/fn.clj`: 28 matching lines, including read propagation at 49,
  index refusal at 160/195/199, namespace acquisition at 978/993/1039,
  graph reads at 1523–1630, and indexing/admission at 1998–3272.
  Held by publication-dissolution even though clean at inspection.
- `src/seon/schema/edn.clj`: 12 matching lines at
  131, 183, 191, 221, 229, 238, 247, 272, 287, 299, 317, 479.
  Foreign dirty, bridge-step2-walker ownership.
- `src/seon/schema/datahike.clj`: seven matching lines at
  263, 274, 297, 306, 365, 379, 500. Foreign dirty, same owner.
- `src/seon/cluster/source.clj`, `script/seon/fresh_operator.clj`, `bin/*`,
  other held schema paths, cluster/turn and every other inherited dirty path
  remain untouched. No foreign session was operated or messaged.

The complete producer/contract/consumer/message/R8 sweep remains owed after
the section-6 ruling. No facets were added and no stored type changed.
**RESET NEEDED:** no new obligation introduced here; no live stored-kind
census was made and the existing wave reset obligation is not claimed done.

## Cold command owed

After conversion and the required per-family fast passes, the orchestrator
owes the following accumulated cold scope (include only actual changed paths
when landing, and preserve all foreign drafts):

```sh
bin/test --paths \
  src/seon/sci/eval.clj src/seon/sci/kernel.clj src/seon/sci/admit.clj \
  src/seon/sci/reader.cljc src/seon/program.cljc \
  resources/seon/schemas/seon.sci.eval.edn \
  resources/seon/schemas/seon.sci.kernel.edn \
  resources/seon/schemas/seon.sci.admit.edn \
  resources/seon/schemas/seon.sci.reader.edn \
  resources/seon/schemas/seon.program.edn \
  test/seon/sci test/seon/program_test.clj -- \
  seon.sci.eval-test seon.sci.eval-instrumentation-test \
  seon.sci.documentation-test seon.sci.shown-text-test \
  seon.sci.fork-isolation-test seon.sci.kernel-arm-carriage-test \
  seon.sci.admit-test seon.sci.admit.declaration-population-test \
  seon.sci.reader-test seon.program-test
```

Then `bin/test --platform`. Neither was run by this lane. No scratch root,
worktree or continuing JVM was created by this investigation.
