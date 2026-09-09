---
type: research
status: active
tags: [render, faults, ai, verification]
---

# Fault rendering and provider refusal — 2026-09-09

Read the two assigned issue notes end to end, AGENTS.md's copied PRD §10
lane instructions, and the turn PRD §15 end to end. Read the active roadmap
and working edge. Verification uses SEON_TEST_WORKERS=3.

## Inherited state and boundaries

Default PID 38717 answered status and MCP: three error signatures, six failed
runs. The browser connector returned `No browser is available`; native Chrome
and Brave each returned `cgWindowNotFound` (-10005). Screenshot verification
is unavailable through the attached computer surface. Default was never
stopped, reforked, or restarted.

During the first slice the turn rename began editing the shared tree,
including walk.clj, error.clj, loop.clj, and their consumers. Verification
therefore uses `tmp/faults-render-wt` at a991283de, with only this lane's
changes and the repository's reference-code linked. Foreign edits are
preserved. The loop hunk will be recorded here while that owner is editing it.

## Slice 1: ordered evaluation settlement

Live JVM probe before the fix returned:

```
seon.render.walk/ordered-episode violated its contract (invalid-input): must be a print node at [[:seon.repl/settled 0 :seon.sci.admit/print-node]]
```

The settlement contract admits saved shown text, output, and error. Settlement
presence advances the prefix even without an object; shown text never reaches
the structural reference walker. Older callers' actual print nodes retain
their reference discovery, guarded by the dependency-owned `print/node?`.

Dependency ledger: `src/seon/print.cljc:897` owns iterative node validation;
`:778` owns structural references. `resources/seon/schemas/seon.eval.edn`
declares shown text. `seon.eval/of-agent` supplies the stored evaluation in
the canonical fixture regression. No serialization or text decoding is added.

Fast fixture proof: 1 test, 7 assertions, zero failures/errors. Isolated gate: 1 test, 7 assertions, zero failures/errors. The older bootstrap caller still decodes shown text in
`src/seon/bootstrap.clj:599`; that file is concurrently held by turn-rename.
This slice prevents its non-node values from faulting ordered-episode but does
not claim to replace the retired opening algorithm.


## Slice 2: terminal static refusal

Credential, authentication, authorization, model, and request refusals are
terminal even with a backup. Transport-before-send, rate limiting, and server
failures retain the existing bounded transient policy. Real HTTP fixture and
all AI namespace regressions: 52 tests, 236 assertions, zero failures/errors.

The gate exposed and the slice repairs two armed boundary defects: config
refused an absent credential selector before descriptor resolution; and the
sequential stream contract realized the response before its terminal finish
could stop the fold. Credential selection is optional; intermediate target
assembly stays private until descriptor resolution. The stream boundary checks
sequential shape without consuming it, while `stream-event` validates each
line. Existing real HTTP tests prove a finished reasoning-only stream settles
before EOF and does not close a concurrent peer.

Platform gate exited 0. The first default adoption exited 1 during the
concurrent rename. A subsequent JVM read failed at connection acquisition
(`seon.dev.mcp/nil-deref`); no healthy live-render claim is made from that
probe. RESET NEEDED for incompatible default schema publication; the lane
does not operate default's lifecycle.


## Slice 3: settings choose virtual turns

`:seon.config.ai/no-provider true` is an optional per-agent setting on
`:seon.agent/settings`. The fixture sets it before its messages are written.
The ordinary call branch freezes the same no-op reply as `virtual-turn!`,
then uses the existing reply reader and evaluation settlement. It does not
render or capture a provider prompt and creates no attempt.

The canonical fixture uses the production agent creator and `run/open-tx`,
then the ordinary `loop/turn`: fast and isolated gate both pass, 1 test /
13 assertions. The stored reply is `(+ 1 1)`, the saved value is `2`, and
provider-attempt and error queries both return empty collections.

`loop.clj` remains concurrently edited by turn-rename. Its proposed and
tested hunk is in [faults-render-loop-2026-09-09.patch](faults-render-loop-2026-09-09.patch),
not applied to the shared file or included as a production file in this
commit. The scratch worktree includes that exact hunk for verification.
RESET NEEDED: new config/schema publication requires a fresh scratch fork;
default must be reset by its owner if its existing schema rejects adoption.


## Integration after turn rename landed

At 08:13 UTC the shared tree contained only this lane's initial episode
edits and the inherited untracked directories. Turn rename had landed at
46039f5c6, and default returned HTTP 200 / 55,780 bytes / 0.153 s. The lane
therefore integrates each isolated slice in order, using `:seon.turn/*` in
its regressions and loop hunk. The earlier worktree commit ids are prototype
evidence; the final shared-tree commits below supersede their integration
boundaries.

Final integration slice 1: `a90ed5cce`, canonical gate 1 test / 7 assertions.
Final integration slice 2: canonical gate 52 tests / 236 assertions, green.
Adoption reached JVM instrumentation but reported source changed during
adoption; concurrent `seon.turn` edits are preserved.
