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


## Scratch seed and final verification boundaries

Scratch `faults-render` booted from the isolated source at PID 58521, HTTP
7815. Before seed, the effective cluster setting was `no-provider true`,
with zero attempts and faults. Juniper's own settings also carried it before
messages. At 08:11:11 UTC three virtual replies had completed as `(+ 1 1)`;
provider attempts were zero and token-usage rows were empty. Two unrelated
supervision faults remained: `agent-already-running` and root supervision not
committed. This is zero provider faults, not zero faults overall.

After that measurement a synthetic presentation fault was inserted. Scratch
HTTP returned 200, 47,839 bytes. The saved concern block is 3,522 bytes and
contains zero `items, depth` occurrences. It displays kind, message, UTC time,
function, turn link and evidence link through the error pair. Exact direct
AI output is saved in `faults-render-ai-2026-09-09.txt` (82 UTF-8 bytes).
The committed presentation probe records the exact historical scratch API;
its `seon.cluster.run` identifiers predate the other lane's rename.

Screenshot unavailable: CUA reported no available browser, and native Chrome
and Brave both returned `cgWindowNotFound (-10005)`. HTTP evidence proves
served HTML only; no browser-paint claim is made.

A separate agent then used a verified absent credential, with no-provider
removed at cluster level. It produced two separate turns, each attempt ordinal
zero, but FOUR no-credential fault rows. Static refusals no longer retry
inside the AI attempt policy. The loop independently commits an attempt fault
and commits it again during terminal settlement, then admits another turn.
The requested one-fault guarantee therefore remains unproven and falsified by
this probe. Scope extension was requested because this assignment permits
loop edits ONLY for the no-provider branch; no extra settlement edit is made
without that authorization.

Final slice 3 applies the previously isolated hunk to the now-unprotected
loop source, with the landed `seon.turn` attribute names. Concurrent edits in
`src/seon/turn.clj` and its test are preserved and excluded from lane gates.


## Final fault concern integration

The default HTTP response after development reload was 200 / 55,390 bytes.
`faults_render_extract_2026_09_09.py` extracts the actual concern and refuses
an absent subject. The committed default block is 11,618 UTF-8 bytes,
contains nine error cards, and contains ZERO `items, depth` strings. Its AI
column contains the flat credential errors; its HTML column uses the entity
pair. No lifecycle operation was performed on default.

The initial focused fault gate passed 1 test / 15 assertions. The broader
error namespace check exposed an obsolete walk test: its actual output was
only the identity/inbox opening prefix, so it never acquired its fault
subject. That test is replaced by `pulled-fault-concern-uses-the-entity-pair`,
which asserts a real stored entity id and invokes the debug page's real SCI
preview path. Flat attribute-shaped errors retain every input attribute in
AI output; old prose assertions now assert exact flat data instead.

`test/seon/turn_test.clj` is concurrently edited and remains untouched. Its
fault-card test at the dated line 1792 assumes the retired nested HTML card
structure and the text `in run run-1`; those expectations need updating to
the new entity pair when its owner lands. This is a protected test boundary,
not a claim that the complete turn namespace is green.

Final shared commits so far: slice 1 `a90ed5cce`; slice 2 `3f26b8af3`;
slice 3 `a4a0d457c`. Final slice 3 isolated gate: 1 test / 13 assertions.
Adoptions after slices 2 and 3 completed reload and instrumentation but
reported source changed during adoption. The final adoption must converge;
intermediate reload evidence alone does not establish a matching commit id.

Final slice 4 gate: 34 tests / 154 assertions, zero failures or errors.


## Final landing and cleanup

Slice 4 is `5c9c0cc4e`. Final platform gate passes: 83 tests / 490 assertions,
zero failures/errors, with `SEON_TEST_WORKERS=3`. No full suite was run.

Final default adoption CONVERGED at source commit
`6aa118c6-7266-56af-8546-a495cd83ba0f`, digest
`eb8c0666871121927d633d6e51ad41fbe9f600b9741d8dca8f9d810c09d28b3a`.
A live database query of `:seon.source/commit-id` returned that same UUID.
A pull by `:seon.schema/key :seon.config.ai/no-provider` returned the optional,
per-agent `[:= ... true]` declaration. An earlier probe guessed
`:seon.schema/id` and returned a typed invalid-read; that result is not schema
proof. The final installed-key pull is the evidence.

Final HTTP: 200 / 55,391 bytes; extracted concern remains exactly 11,618
bytes, nine cards, zero generic-printer markers. Browser screenshot remains
unavailable. The historical RESET NEEDED warnings above were resolved by the
owner's separate refork and this successful in-place adoption; this lane
never restarted, stopped or reforked default.

The isolated JVM exited through its operator; its store lock became free.
The isolated root and worktree were removed, along with this lane's retained
failed renderer gate after the replacement regression passed. No live runner
held that root. Other worktrees and all foreign source/test edits remain.

The one-fault requirement remains OPEN: the credential probe measured four
faults across two turns. Only AI attempt-policy retries are fixed. The user
scope-extension question has received no answer, so the loop changes remain
limited to the explicitly authorized no-provider branch.
