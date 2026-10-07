# printers-errors — lane notes (2026-10-07)

Classes: second-clipper (36 sites), error-text (56 sites). Inventory: docs/research/agent-platform/2026-10-07/printers/print-sites.tsv.

## Converted (this lane)

second-clipper
- src/seon/edit.clj bounded-values (5 sites): deleted. `form` keeps every candidate (`:seon.edit/candidates`),
  `exact` keeps every ambiguous line (`:seon.edit/lines`, computed only on the ambiguity branch);
  `:seon.edit/candidates-complete?`/`:seon.edit/lines-complete?` are gone (no reader).

error-text (value kept as data, message fixed text)
- error.clj fault-entities: message fixed, the unacquired fault under `:seon.error/offending`.
- cluster/source.clj declarations-held-tx, write-back-commits: moved identities only in `:seon.error/expected`/`offending`.
- cluster/agent.clj render-situation-ai: trigger shown as its message id (a scalar), not a printed lookup ref.
- problems.clj ai-prose: unknown-test-evidence rows are `{:seon.error/message :seon.error/offending}` data printed by
  the value printer.
- schema.clj assert-acyclic-references!, canonical-definition: messages name the data key (`:seon.schema/cycle-path`,
  `:seon.schema/value`) instead of printing it.
- schema/internal.cljc assert-compilable-schema!, assert-non-nilable-value-schema!: no printed definition; the Malli
  failure stays the cause; the nilable refusal carries its base shape as `:seon.schema/base-definition`.
- sci/arm.clj refusing-root, script/seon/operator.clj prepl-value!: the cause's message is no longer copied; the cause
  is attached.
- test/seon/test_support.clj transacted!: report and entity no longer printed; the refused row is
  `:seon.test-support/offending-entity [index entity]` (test_support_test asserts it as data).

## Judged legitimate (no value printed)

- error.clj bound-text: schema bounds as prose ("at least 1"); misclassified by name. error.clj:891 enum: prints the
  schema's declared literals, bounded by the declaration, not by input.
- "ex-info message copied from an error value" (batch.clj:608, config.clj x3, effect.clj:146, render/transcript.clj
  x2, turn.clj:3460, test_support.clj:205/493, fault.clj:445, test.clj:1725 prefix): the copied text is the error's own
  short message and the value rides as ex-data. They become correct when each producer's message is fixed text.
- script/seon/dev/harness_costs.clj:52: `(str kind)` of a Malli report keyword (scalar).

## Not converted: held files (for their owners)

- db.clj (commit-validation): value-type-error 2015, query-argument-message 2667, pull-call 2954, rejection-message 3519,
  write-error-identity 3956, write-owned-values-error 4121, removed-definition-error 4802, retention-check 5503/5504.
- test/runner.clj (reach-review): run-vars! 439 (value/bounded-text drift), reach-digests 1115/1117; and the
  render.value delegates' last callers 119, 2053 (`value/floor`).
- turn.clj, turn/batch.clj (value-storage): turn 3460 (copy, legitimate), settle-batch-refusal! 3808 (pr-str of every
  proposal with print bounds off), turn/batch changed-since-basis 579.
- sci/kernel.clj (error-frames): memory-pressure-error 145.
- printers-rest holds fn.clj (require-committed! 58/59, assert-clean-analysis! 1379, reconciled-paths 4025/4026),
  config.clj (713/743/828 copies, 673 render.value/bounded-text), test.clj (world-refusal 1725 clips offending to 400
  chars with print.value/bounded-text).
- render.clj walk (1817, 1910): `seon.render/walk` returns its whole walk and errors as pr-str text; the fix (return
  the data, the REPL prints it) breaks test/seon/sci/eval_test.clj:1810-1830 (asserts a string and a regex over it),
  held by value-storage. Not claimed.

## Open decisions (stopped)

1. error.clj admission (bounded-admission, bounded-error-admission, bounded-text, project-observation, admitted-size,
   fit-fact-payload, offending-evidence, offending-record, prepare-result, prepare: 22 sites) is the fault fact's
   inline-field fitting. Deleting it is the errors-spec cut (docs/seon/specs/errors.md §1 "Durable evidence": one
   blob per first occurrence, nothing clipped): it removes or changes the fact attributes `:seon.error/data-edn`,
   `:seon.instrument/args|expected|actual|actual-size`, `:seon.error/capped?`, `:seon.error/data-size`,
   `:seon.error/data-content` declared in resources/seon/schemas/seon.error.edn, seon.error.occurrence.edn,
   seon.error.projection.edn, seon.instrument.edn, read by eval/drive, render/transcript, problems, instrument and
   ~15 test files (sci/eval_test held). It is a schema change proven by adoption on a live-store branch and stores
   whole values, which the value-storage lane is re-deciding (sci/admit.clj). Needs its own Opus slice after
   value-storage lands; not a print-site edit.
2. seon.print.value/bounded-text and throwable-text cannot be deleted yet: render.value uses them as the value
   printer's own last resort (diagnostic-line, plain-projection), the namespaces below the projection (db, schema,
   refusal) have no other printer, and ~60 callers sit in other classes (printed-copy, ui-print, log, cli-print) and in
   held files (sci/eval 3, turn 3, test/runner 2). Ruling needed: is print.value part of the value printer (keep, the
   floor for code that cannot require render.value) or a second printer (then each of those callers keeps its value as
   data first, class by class, and print.value goes last).
3. schema/admission.clj exact-reuse-findings 315 and undefined-slot-finding 414/415: the findings are kondo-shaped and
   bin/seon-hook shows only `:message` (bin/seon-hook:1909), so the keys/path must be in the text for the editing agent
   until the hook prints finding data through the value printer (cli-print class).
4. seon.error.refusal bounded-cost/bounded-data/chain (9 second-clipper sites): held by error-frames.
