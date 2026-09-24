# Lane fix-partial-request-profile — landing (2026-09-24)

`seon.render/request-profile` is the one entry that turns a request into a render
profile; it now refuses a carried non-profile at its input and names a carried
refusal as the profile member's refusal, so no partial profile reaches
`seon.error/prepare-result` or `seon.print/fit`.

## Broken things first

- `seon.flow-test/turn-evaluation-completion-is-a-flat-diagnostic-value` is red:
  `test/seon/flow_test.clj:948-949` names cluster `"flow-evaluation"`, which the
  fixture database holds no config row for. It now fails at entry with "Effective
  configuration requires a matching cluster row with every required dial." Not an
  owned file; the test should fork with the seeded cluster name
  (`test-support/fork-cluster-ctx connection`) or seed that row.
- `seon.render.call-test/declared-producer-refusals-stop-context-rendering`
  (call_test.clj:28-29) expects the config refusal message; since c1cd41b25 a request
  that names no cluster is refused by name first. That was red before this lane
  touched anything. It is a stale assumption and not owned here.
- `seon.render-simplification-test`: 38 fail / 10 error in 14 members, the same
  counts before and after this lane's second edit. None of them name
  `request-profile`. The causes are `seon.fn/analyze-forms` row refusals,
  `seon.plan` renderer ambiguity, and a `:seon.error/at` missing in
  `renderer-failure`. I could not run the parent commit for a baseline without a
  second JVM (verification limit).
- The publication hook reported a foreign degradation during the second
  publication: `seon.test/green?` refused a result missing `:seon.test/run-basis-t`.
  The change still installed; the REPL probe below proves it.

## Diagnosis

- The logged fault (seon.log:64303-64304) was not a hand-built partial profile. The
  carried "profile" was the `seon.config/effective-in` refusal for the cluster
  `"flow-evaluation"`, tagged `:seon.render/refused-member :seon.render/profile`
  (all five profile keys missing). `render.value/prepare` → `seon.print/fit`
  (value.clj:608) was the first to refuse it, then `prepare-result`
  (eval.clj:3037, i.e. before c1cd41b25).
- c1cd41b25 already removed that producer path: `evaluate` no longer carries a
  refused profile, and `shown-result` throws it. f7ae12104/57d89df55 (3-arity
  fork keeps its base cluster) is a separate path and did not affect it.
- The remaining hole was at the entry. A carried value went through
  `(or (:seon.render/profile request) …)` unchecked. A partial profile was caught
  only by the output contract, which mis-names it as a return-value fault. A raw
  config refusal (no `refused-member`) passed through untagged, so callers that
  test `refused-member` (such as `render-html`) would have used it as a profile.

## Change

- `src/seon/render.clj` `request-profile`: the input contract declares
  `[:seon.render/profile {:optional true} [:or :seon.render.profile/profile :seon.error/value]]`.
  A carried error gets `:seon.render/refused-member :seon.render/profile`, the same
  tagging the derived branch already does.
- Regression: `seon.render-simplification-test/a-carried-partial-profile-is-refused-at-request-profile`.
  It checks that a complete profile passes identically and that a partial one is
  refused at `:input` with path `[:seon.render/profile :seon.render.profile/id]`.
  It fails on the parent, where the refusal was an `:output` refusal at
  `[:seon.render.profile/id]`, as observed at the REPL before the edit.

## Evidence (default, JVM mode)

- Before: a partial carried profile raised "request-profile refused return value
  at [:seon.render.profile/id]".
- After: it raises "request-profile refused request at
  [:seon.render/profile :seon.render.profile/id]". A carried
  `config/effective db "missing-profile"` returns
  `{:seon.render/refused-member :seon.render/profile, :seon.config/missing-effective "missing-profile"}`.
- Cost: a carried complete profile takes 1.03 µs per call before and 1.05 µs after
  (1000-call warm loop, armed). There is no regression.
- Tests (bin/test-check default): the regression passes (4 assertions, 3108 ms on
  first run including acquisition, 290 ms reused).
  `missing-render-profile-remains-a-flat-error` was red after the first edit (input
  schema `[:or profile request-error]` refused the raw config error); it passes
  after the second.
  `an-evaluation-renders-with-the-profile-its-caller-names` passes (2135 ms).
- Publication through the hook took 7.1 s. `acquire-context!` accounts for 11 s
  inclusive across 11 calls, and `derive-base-ctx` for 7.8 s across 3 calls. This
  is shared publication cost and not caused by this change; it is reported per
  the law.

## Size and commits

- render.clj +11/−3; test +17. Net +25 lines.
- Commit: see `git log -- src/seon/render.clj` (this note ships in the same commit).
- Released paths: src/seon/render.clj, test/seon/render_simplification_test.clj,
  this note. src/seon/sci/eval.clj was not edited.
