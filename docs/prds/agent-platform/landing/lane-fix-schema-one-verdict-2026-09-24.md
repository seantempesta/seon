---
type: landing
lane: fix-schema-one-verdict
status: landed-installed (items 1 and 3); item 2 blocked on seon.flow.edn
created: 2026-09-24
---

# Lane fix-schema-one-verdict (2026-09-24)

What exists: `seon.schema.internal/assert-complete-schema!` is the one completeness rule.
`seon.schema.admission/admit` (the hook) reaches it through `schema.edn/admit` and adds
advisory findings. `seon.schema.datahike/malli->datahike-attr-in` owns storage: it
refuses a stored attribute whose form has no Datahike value type. `runtime-predicate`
is the one resolver that loads a predicate's namespace.

## Broken or unproven first

- **Item 2 is not committed. It breaks boot without a seon.flow.edn change, and I do
  not own that file.** Making `assert-non-nilable-value-schema!` use `nilable-union?`
  refuses the packaged `:seon.flow/callback-result` (`[:or :nil :map …]`). The refusal
  comes from `projection-registry`, so building the packaged projection fails.
  Snapshot run: `Execution error … assert-non-nilable-value-schema! (internal.cljc:514)
  schema/register! :seon.flow/callback-result`. With the respelling applied
  (`tmp/fix-schema-one-verdict/flow-respelling.patch`: remove `:nil` from
  `:callback-result`; the four outputs `commit-drop!`, `commit-fault!`, `panic!` and
  `io-complete!` become `[:maybe :seon.flow/callback-result]`), the snapshot arms 1718
  Vars and the regressions pass. `seon.schema-test`, `admission-gate-test` and
  `agent-nil-contract-test` fail exactly as HEAD does. The pieces are ready to land
  together: `item2-internal.patch`, `item2-tests.py` and `flow-respelling.patch`, all
  in `tmp/fix-schema-one-verdict/`. The root needs to give seon.flow.edn to one lane
  for one commit.
- **Storage keeps the `[:or :nil X]` gap as well.** The Datahike bridge refuses only
  `:maybe` (`src/seon/schema/datahike.clj:131`). It stores `[:or :nil :string]` as
  `:db.type/string` (live probe). Nothing refuses a stored nil-union attribute until
  item 2 lands. After that, only a named value schema is refused; an inline one is not.
  The file is not mine.
- **Storage refusal wording.** The owner asked for the message to say "a stored
  attribute needs a storable type". The current message is `datahike.clj:161`: "The
  Malli form has no Datahike value type. Register a concrete storable shape." The
  ex-data is `::value-type-unavailable true`. The meaning is right, but I did not
  reword it because `datahike.clj` is not mine. The refusal also happens where storage
  is derived (`malli->datahike-attr-in`), not at `register!` or in the hook. Both of
  those admit an entity whose member is `:any` (live probe).
- **Installed proof (default runs from the checkout).** Live, `justified-polymorphism?`,
  `undefined-slot-finding`, the new test Var and the wrapped `runtime-predicate` all
  resolve. Results on pid 68592 and then 69730:
  - `--ns seon.schema.admission-gate-test`: run 44c3b98f34ff, executed 9, pass 39,
    fail 0, 17439 ms.
  - `--ns seon.schema.agent-nil-contract-test`: run 730a5ce4b4ae, pass 369, 2734 ms.
  - `--test seon.schema-test/a-predicate-namespace-that-fails-to-load-is-not-an-unresolved-predicate`:
    run b5d446a17a56, pass 9, 1042 ms.
  - `--test seon.schema.admission-gate-test/an-undefined-slot-is-advice-a-stored-one-is-refused`:
    run 408bd126f528, pass 11, 803 ms.
  - Between these runs pid 68592 died with `OutOfMemoryError: Requested array size
    exceeds VM limit` while `seon.fn-test` was running. That is not my test
    (`seon.log` ~69114; heap dump in `data/heap/`).
- `bin/seon status` fails on its own. `seon.cluster.boot/readable-response` refused its
  return value, because `:seon.error/at` was missing from `:seon.operator/response`.
  This is foreign. The MCP `runtime_status` worked.
- **Dead code now.** `seon.schema/assert-complete-contract!` still skips
  `assert-complete-schema!` for core declarations when it is handed
  `reference-advisories` (`schema.clj` ~1623). That was correct before and is still
  correct, since no refusing rule was added. It is only mentioned here as the reason
  the withdrawn first design could not have reached activation.

## Change

1. **a1f1d80ce — an undefined slot is advice everywhere.** Owner rulings, in order:
   no refusal for core `:any`, then "we don't need the ban anywhere". The first design
   (move the core refusal into `assert-complete-schema!`) was withdrawn before any
   commit.
   - `internal/assert-complete-schema!`: an unjustified agent `:any`/`:some` is now an
     advisory entry (`:seon.schema.advisory/undefined`) instead of
     `:seon.schema/undefined-contract`.
   - New `justified-polymorphism?` holds the exemption + reason + generator judgement.
     `permissive-positions` now calls it, so the judgement lives in one place.
   - `admission/undefined-slot-finding` (was `core-polymorphism-finding`) gives one
     `:warning` `:schema-polymorphism` per declaration, for every source, and never
     blocks. The recorded exemption silences it.
2. **Item 2:** blocked (above).
3. **d1ac87720 — predicate load failure is declared.**
   - Two new entries in `seon.schema.edn`: `:seon.schema/unloadable-predicate`
     (`:qualified-symbol`) and `:seon.schema/unloadable-predicate-error`
     (`[:and {:seon.db/attributes false} :seon.error/base [:map …]]`). I searched the
     registry for load or compile errors first and found none.
   - `runtime-predicate` catches `Compiler$CompilerException` and throws
     `refusal/diagnostic` data (`:seon.error/exception-class`, the whole
     `:seon.error/chain`, the frame) with the exception as cause.
     `FileNotFoundException` still answers nil.

## Evidence

The snapshot runs below (`git archive HEAD` plus the diff, run under
`clojure -M:dev:test`, contracts armed through `seon.instrument/apply!`: 1717 Vars on
HEAD, 1718 with the change) happened before root's one-JVM correction. Those snapshot
roots are deleted and no JVM of mine is running. Runner:
`tmp/fix-schema-one-verdict/run.clj`, logs `run-{0,1,2}.log`. Each var was run twice.

| Test | HEAD (run-0) | items 1+3 (run-1) | +item 2 +flow respelling (run-2) |
|---|---|---|---|
| `an-undefined-slot-is-advice-a-stored-one-is-refused` | — | 11 pass, 944 / 429 ms | 11 pass |
| `a-predicate-namespace-that-fails-to-load-…` | 6 pass (old assertions) | 9 pass, 1047 / 933 ms | 9 pass |
| `one-contract-text-gets-one-verdict-at-every-entrance` | 15 pass, 5128 / 4491 ms | 18 pass, 10431 / 6220 ms | 21 pass |
| `a-nilable-value-schema-gets-one-verdict-…` | 2 pass | 2 pass | 5 pass (both spellings) |
| `seon.schema.admission-gate-test` | 10 tests, 41 pass | 10 tests, 50 pass | 53 pass |
| `seon.schema.agent-nil-contract-test` | 374 pass | 366 pass (3 asserts of the retired refusal removed) | 366 pass |
| `seon.schema-test` | 105 pass, 4 fail, 25 error | 108 pass, same 4 + 25 | 111 pass, same |

- The failure set is identical to HEAD in every snapshot (compared by test name). The
  25 errors are "canonical fixture needs an executing test handle" outside
  `seon.test/run`; the 4 fails were already there.
- **Costs over one second (named):**
  - The load-failure regression and the stored-attribute half of the undefined-slot
    regression each build `schema/declaration-projection` over the packaged population
    once, to validate the declared error and to derive storage. That takes about 0.9 s
    armed (90 ms unarmed, live).
  - `one-contract-text-gets-one-verdict-at-every-entrance` was already 4.5–5.1 s on
    HEAD, because it builds the packaged projection and runs 2×N entrance calls. This
    lane added one case. Timings on this machine swung 2× during the run: the unchanged
    `agent-nil-contract-test` went 4.9 s → 10.3 s. So the 6.2–10.4 s is load, not a
    measured regression.
  - The hot-path change removes a throw and calls `justified-polymorphism?` only on
    `:any`/`:some` nodes. Neither this lane nor a live probe measured a regression.
    The test is over its 5 s default bound on HEAD already; it needs a shrink
    assignment.
- Live default (old code): `build-projection` over 3374 forms took 247 ms, unarmed.
  None of the 15 packaged declarations containing `:any`/`:some` is unjustified. The
  only packaged top-level nil-union is `:seon.flow/callback-result`.
- Peak RSS for a snapshot JVM: 3.9–4.3 GB. clj-kondo: 0 errors on the changed files.

## Size

- Item 1: src +39 −32 (internal.cljc +27 −16, admission.clj +20 −20), test +63 −39.
- Item 3: src +21 −4 (schema.clj +19 −4, edn +2), test +16 −7.
- Item 2 (not committed): internal.cljc +8 −4, tests +12, seon.flow.edn +4 −5.

Released paths: `src/seon/schema/internal.cljc`, `src/seon/schema/admission.clj`,
`src/seon/schema.clj`, `resources/seon/schemas/seon.schema.edn`,
`test/seon/schema/admission_gate_test.clj`,
`test/seon/schema/agent_nil_contract_test.clj`, `test/seon/schema_test.clj` (outside
the `test/seon/schema/` glob; I edited it because the S1 regression lives there), and
this note.
