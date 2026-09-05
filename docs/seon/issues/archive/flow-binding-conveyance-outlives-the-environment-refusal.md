---
type: issue
status: resolved
severity: friction
tags: [issue, flow, runtime, isolation, testing]
---

# Delete flow's binding conveyance so the environment refusal is load-bearing

## Problem

W1 landed the environment as data on every flow crossing and a
construction-time refusal at each one (`env/refuse-absent-environment!` at
`src/seon/flow.clj:120,677,734,929`). The PRD's own flow-carriage
constraint 3 says the `bound-fn*` conveyance sites must be deleted **in the
same change**, "while conveyance remains, a forgotten environment is
invisible on `:compute` and fatal on `:io` (the exact audited signature)"
([PRD, Phase 0 flow-carriage findings](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)).

All three sites survive:

- `src/seon/flow.clj:681` — `completion (bound-fn* complete!)` in `submit!`;
- `src/seon/flow.clj:740` — `work-fn (bound-fn* work-fn)` in `submit!!`;
- `src/seon/flow.clj:991` — `(bound-fn [] …)` in `join-error-fanout!`.

So today the dynamic carriers (`seon.db/*conn*`,
`seon.effect/*request-context*`, the schema projection bindings) still
arrive at the far side of every crossing. That is not itself wrong — Phase 3
owns their deletion — but it means the new refusal cannot yet catch anything
real: a submission that named the WRONG cluster's environment, or work that
reads custody from the conveyed binding instead of the carried environment,
behaves identically to correct code. The class is half-closed, and the
half that is closed is the half that already had a loud refusal.

This is a sequencing observation, not an accusation that W1 landed the wrong
thing: deleting the conveyance before Phase 3 converts the named readers
would break custody everywhere. What is missing is the record that the
constraint is UNMET, and a proof that closes the gap when Phase 3 lands.

## Evidence

`rg -n "bound-fn" src/` on 2026-08-08 at `8e65e484c`:

```text
src/seon/effect.clj:319:  (bound-fn [] (handler request effective))
src/seon/flow.clj:681:    completion (bound-fn* complete!)
src/seon/flow.clj:740:    work-fn (bound-fn* work-fn)
src/seon/flow.clj:991:    (bound-fn []
```

The refusal itself is real and does fire — `var-process`, `submit!`,
`submit!!`, `start-work-launcher!` and `start-error-fanout!` all throw a flat
`::absent-environment` naming the boundary — and every production caller was
converted (`src/seon/cluster.clj:2061-2100,2163`,
`src/seon/cluster/agent.clj:297-320`, `src/seon/cluster/loop.clj:334`,
`src/seon/effect.clj:509`). The gap is only that nothing yet DEPENDS on it.

## Owner

`seon.flow` (`submit!`, `submit!!`, `join-error-fanout!`), sequenced with the
seon.env Phase 3 conversion of the named dynamic-var readers.

## Acceptance criteria

- The three conveyance sites are deleted in the same change that converts
  the last reader of `seon.db/*conn*` / `seon.effect/*request-context*` to
  the carried environment.
- One regression proves the refusal is load-bearing: work submitted with a
  DIFFERENT cluster's environment reads that cluster's custody, and work
  whose submitter had a binding but no environment member fails loudly
  rather than silently inheriting the binding frame. The Phase 0 negative
  control (`tmp/env-probes/env_probes/probe_a_env_on_fork.clj`) is the
  model — it reproduced the audited defect 192/192 off-thread.
- `rg -n "bound-fn" src/seon/flow.clj` returns nothing.

## Resolution — 2026-08-08 (`226da97f8`)

All three sites deleted; `rg -n "bound-fn" src/seon/flow.clj` returns
nothing.

The acceptance criterion that mattered — "one regression proves the refusal
is load-bearing" — was written BEFORE the deletion and proven non-vacuous
afterwards.
`seon.env-test/a-submission-delivers-exactly-its-own-environment` now records
what each crossing read from `seon.db/*conn*` and
`seon.effect/*request-context*` as well as from its submission, and asserts
the decoys are absent at all three: io work, compute work, and the terminal
callback. Restoring the conveyance fails it, measured both ways:

```text
restore submit!!'s work-fn wrap  -> "compute work inherited no submitter
                                     binding" FAILS, 16/16 rows carrying
                                     :decoy-connection and :decoy-context
restore submit!'s complete! wrap -> "the terminal callback inherited no
                                     submitter binding" FAILS
```

Deleting before Phase 3 converts the named readers turned out to be safe
because nothing in the two surviving production submitters depended on the
conveyance: the run loop's compute work establishes its own bindings INSIDE
the evaluation (`src/seon/sci/eval.clj:1711-1735`,
`render/call-with-walk-context` in `src/seon/cluster/loop.clj:1287`), and the
one background terminal that DID read a conveyed binding —
`seon.effect/settle-value!` reaching for admission dials — now receives them
as data read once on the requesting thread (`f3b8eabda`).

The named remainder is at the OTHER effect execution boundary, not here: `seon.effect/dispatch`
still uses `bound-fn` for the foreground handler, because
`src/seon/shell/jvm.clj:290` and its peers have not yet been converted to
take their environment as an argument. That conversion is Phase 3's, and it
is tracked on
[the effect-execution issue](../the-effect-door-runs-capability-handlers-unarmed.md).
