---
type: report
status: committed, awaiting root runs
created: 2026-09-25
tags: [agent-platform, turn, read-evidence, performance]
---

# Lane fix-read-sink-opus: landing (2026-09-25)

`seon.turn/evaluate-sources` collected read evidence by binding `db/*read-evidence-sink*`
around the whole install. That covered acquisition, the program-identity checks, profile
derivation, gate tests and rendering, so the platform's reads counted as the form's. The fix
passes the sink as request data, reusing `seon.sci.kernel/invoke`'s `:seon.db/read-evidence-sink`.
`seon.sci.eval/evaluate` binds it only around `sci/eval-form` and the success shown result.

A second slice stops `turn/restore-evaluations!` from discarding a failed restoration.

## Broken or open first

1. **Nothing is proven installed.** Default (pid 37460) runs HEAD 8e92c6b6c; `form-read-sink`
   does not resolve there. Root must adopt ca17618c0 and b14f2baeb, then run the commands below.
2. **The regression's query assertion is expected to be RED until one more line lands, in
   `src/seon/call_preparation.clj`, which this lane does not hold.** On a cold context, the first
   call of a program function derives the call-preparation snapshot inside the form's evaluation.
   That derivation's 14 queries (`call-preparation/derive-snapshot` via `memoized-snapshot:613`)
   land in the form's scope: on a fresh branch the form read 15 times, 1 of them its own.
   Because the derivation is memoized, only a cache miss records it. That makes it evidence of
   the cache's state, not of the form. The fix is the existing seam, `binding [db/*read-evidence-sink* nil]`,
   the same one call_preparation.clj:314 and :735 already use. It is in
   `tmp/fix-read-sink/call_preparation.patch` (+4/−1). **Grant needed: call_preparation.clj.**
3. **Restoration under `:panic`.** A failed restoration is stored and delivered by
   `sci.eval/restore-or-unavailable!` through the context's `:seon.flow/commit-fault!`, which boot
   wires to `fault/record!`. It never applies the `:panic` policy (`fault/fault!`), so a panic
   cluster records the fault and keeps running. The owner of `restore-or-unavailable!` should
   decide this; this lane did not change it, to avoid recording the fault twice.
4. Finding: on a lane branch, the reacquisition after one namespace-row write took 14.5 s
   (`derive-base-ctx` 11.6 s, `acquire!` 16.4 s inclusive, from default's profile lines). I found
   it during a probe and did not pursue it. It needs an issue from root. The durable path is
   suggested as `docs/seon/issues/branch-reacquisition-after-a-namespace-write-costs-14-s.md`
   (not written; the owner ranks).

RESET NEEDED: no.

## Evidence (REPL, default, disposable branches `fix-read-sink{,-2,-3}`, all retired)

The probe put a watch on a sink atom and captured each append's frames. It then called
`sci.eval/evaluate` with a lane branch's context, the way `script/seon/dev/mcp.clj:535` does.
"In scope" counts the appends whose stack passes through `eval-form!` or `shown-result`: that is
exactly what the new binding captures.

| form | reads before | in scope (after) | eval ms | digest ms |
|---|---|---|---|---|
| `(inc 1)` warm | 5 | **0** | 3.6–5.6 | 0.7–0.9 |
| `(quote 1)` | 5 | **0** | 4.1 | 0.25 |
| `(seon.db/q '[:find ?name . …])` warm | 6 | **1** | 3.5 | 0.32 |
| same, cold context | 20 | 15 (14 are call-preparation; see open 1) | 468 | 4.1 |

Where the platform reads came from:

- 4 × `db/database-identity` ← `commit-id-of` ← `acquired-database?` / `program-identity` ←
  `call-detecting-program-change`;
- 1 × `config/effective` pull ← `render/request-profile`;
- a refusal's render query (`error/refusal-text`), which stays out because only the success
  render is in scope.

In stored turns, `(inc 1)` holds 7 reads. `(quote 1)` (evaluation 73041, a system opening) holds
**7,286**:

- 5,235 program-graph walks over `:seon.fn/calls`, `:seon.fn/references` and `:seon.test/subject`;
- 447 namespace-row wildcard pulls;
- 214 config reads.

All of these are outside `eval-form!`, so the binding excludes them.

Restoration: `restore-or-unavailable!` was run with a planning step that throws, and the refusal
as its cause. It gives `:restore-failed`, the chain `["Restoring a refused program change threw."
"The batch was refused."]`, and one delivery. The new `restore-evaluations!` body, run as a scratch
copy, answers that error; with nothing to restore it answers nil. The test's evaluation (from
`unrun-evaluation`) and refusal validate against `:seon.sci.eval/evaluation` and `:seon.error/value`.

## Changes

- **ca17618c0** read-sink scope:
  - `sci/eval.clj` +15/−4: `form-read-sink` (the request's sink, else the ambient one, as in
    kernel.clj:612), bound around `sci/eval-form` and the success `shown-result`;
  - `seon.sci.eval.edn` +3: optional request key;
  - `turn.clj` +19/−16: request key in place of the binding, then re-indentation;
  - regression `seon.turn-test/a-forms-read-evidence-is-only-what-the-form-read` (+38).
- **b14f2baeb** restoration:
  - `restore-evaluations!` takes the refusal, restores last form first, and answers the first
    unavailable error;
  - both callers answer it in the refusal's place: `settle!`'s `:seon.error/value` and the turn
    report's refusal;
  - `turn.clj` +22/−14;
  - regression `seon.turn-test/a-refused-batch-whose-restoration-fails-answers-the-stored-restoration-error`
    (+41). It uses the real producer and the real `fault/record!` recorder, and asserts the chain
    and one committed fault.
- Net source lines: +59/−34 = **+25**. Tests: +79.
- Deleted: the whole-install sink `binding` (turn.clj) and the `doseq` that discarded each
  restoration's answer.

Duplicate check (`rg read-evidence-sink`): no second installer of the form's sink remains.
The other bindings have their own scopes:

- the page's render and walk captures: render/web.clj ×7, walk.clj:449;
- `invoke`'s request key;
- call_preparation's two nil exclusions.

Those are the one seam, not copies.

## Root runs (serially, after adoption; add the call_preparation patch first for the query assertion)

```
bin/test-check default --test seon.turn-test/a-forms-read-evidence-is-only-what-the-form-read
bin/test-check default --test seon.turn-test/a-refused-batch-whose-restoration-fails-answers-the-stored-restoration-error
bin/test-check default --ns seon.turn-test
bin/test-check default --ns seon.sci.documentation-test
bin/test-check default --ns seon.read-evidence-test
bin/test-check default --ns seon.rereads-test
bin/test-check default --ns seon.sci.restoration-test
bin/test-check default --test seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
```

After adoption, repeat the probe: a new turn's `(inc 1)` should store 0 reads.

## Verification limits

- The load rule applied: no tests ran, and no namespace was loaded into default.
- clj-kondo reports 0 errors, and warnings are unchanged: 61 in eval.clj, 20 in turn.clj, 6 in
  turn_test.clj.
