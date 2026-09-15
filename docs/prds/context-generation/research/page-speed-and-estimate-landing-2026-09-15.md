---
type: research
status: complete
tags: [render, performance, ai]
---

# Page speed and token estimates — 2026-09-15

## Scope and grounding

Bounded lane, continued on `steward-platform` after the owner's `6acd8818e`
checkpoint. Read AGENTS.md, the context-generation plan README and working
edge, and the token-estimate issue end to end. Preserve other lanes' edits;
never stop, refork, or reseed default.

Dependency ledger: Datahike `cdcb5792db8bd599487f099437265d18a31164a5`,
`reference-code/datahike/src/datahike/query.cljc:2658` owns committed query
identity. `src/seon/db.clj` owns captured read evidence and currentness.
SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`,
`reference-code/sci/src/sci/core.cljc:330` owns reusable contexts.
`src/seon/render.clj` already owns code/input evidence and retained calls;
`src/seon/render/web.clj` already retains root acquisitions and child calls.
The historical `5613bbf17` change retained each evaluation but still queried
and folded the whole history on every acquisition.

## Item 1 — retained history acquisition

The history root now lives with its child calls in the existing shared render
evidence. Its identity includes agent, selected turn, and pull selector.
Reuse checks the existing code/input evidence and every root/child read.
A current root returns its entries, joined text, and segments without another
history walk; it carries the caller's current database value. Missing roots
always acquire. Changed evaluation facts or code invalidate normally.

Baseline default HTTP 200 GETs: **1.981722, 1.544124, 1.729826 s**.
The existing `consolidate_debug_timing_2026_09_15.clj` probe measured rows
2.734 ms, evaluations 958.568 ms, summary 1.193 ms, prefix 358.495 ms,
panel 1794.525 ms, ledger 3238.394 ms (nested measurements overlap).

After the retained-root change, one fixed-basis HTTP sequence had a cold
2428.572 ms request followed by **644.604, 621.462, 627.128 ms** warm GETs.
Each response contained 84,668 characters. Every before/after basis was
536878532; source was `6aa97734-76d5-5654-8464-7a3d32d6363e`.
The reusable bounded HTTP probe is `page_speed_probe_2026_09_15.clj`.
These are warm measurements, not a claim that code-invalidated acquisition
stays below one second.

Live verification used hot-reloaded Vars with instrumentation re-armed.
Publication was initially delayed/refused by the inherited adoption state;
the log was `logs/current-source-failure.log`. MCP twice timed out at its
10-second bound during read probes; subsequent bounded queries and HTTP
requests responded. No process operation was used to recover it.

The final retained-call refinement also carries the immutable database value
in the existing call evidence. Identical database objects need no read replay;
changed objects still check dependency evidence and code/input identity.
After this refinement: **496.181, 107.413, 98.992 ms**, HTTP 200, **85,246
UTF-8 bytes** each; basis before/after **536878744** for all three requests.
The first warmup was 3292.890 ms. Source generation remained the value above.
The owner reseeded Juniper between the initial baseline and final samples:
these are measured default GETs, not a controlled same-fixture speedup ratio.

Final live recheck at 17:38 UTC: **454.023, 132.128, 130.367 ms**, HTTP 200,
**67,410 UTF-8 bytes** each, unchanged basis **536879377** throughout.
Warmup: 2429.071 ms. Adopted source:
`6aa9821c-0cb0-5eef-8232-366e17eedeec`; current publication was already ahead
at `6aa9825f-7d04-508a-854b-6454185917f1`. The measured live definitions
include this lane's hot-reloaded render and estimate changes; this does not
claim adoption convergence with every concurrent publication.

Fast gate initially: 15 tests / 236 assertions, zero failures/errors. The regression
counts the actual history walk and SCI calls, checks identical retained
entries, preserves history across a changed existing message, and invalidates
on changed shown text. The retained-call regression additionally proves no
dependency replay on an identical immutable database. Final gates below.

## Item 2 — agent observations

The estimator's source file is `src/seon/ai/tokens.cljc`, not
`.clj`. The existing calibration query joined all attempts for a model;
budget and ledger callers did not scope it to an agent or a recent window.

Production callers now fit the agent's latest ten billed attempts for the
selected model. Unbilled attempts do not displace observations; before the
first observation the agent's effective config prior applies. The existing
ratio-of-totals estimator and error band remain the single calculation.
No new schema, cache, provider request, or stored calibration was added.

The later reseed replaced Juniper's current run. An as-of view could not
recover no-history capture/usage values. Read-only materialization of the
original commit **6aa966c2-14ef-5256-b705-84912d5451f3**, basis **536877112**,
recovered all **30 run-7 attempts**, totaling **244,933 billed prompt tokens**.
The probe uses Datahike's `commit-as-db` with secondary indices disabled and
releases the materialized database in `finally`; it never creates a branch.
See `page_speed_estimate_probe_2026_09_15.clj` and the committed fixture
`test/seon/fixtures/run7_token_observations.edn` for per-capture character
counts, UTF-8 byte counts, billed tokens, timestamps, and prompt digests.
The probe asserts each recorded character count against the captured string.

The regression predicts each of the last ten attempts using only the ten
preceding billed observations. Relative errors, in order: **2.222%, 1.824%,
1.471%, 0.916%, 0.800%, 0.645%, 0.780%, 1.381%, 1.486%, 0.749%**.
Every attempt is below 5%; no tested attempt calibrates its own prediction.
The canonical database regression also verifies agent isolation, reverse
insertion order, a full recent window, and config fallback.

Chrome observation on default showed the ledger and, for the current run's
last capture, **rebuilt ≈12,737 tokens · billed 12,523**. That browser check
is separate from the historical run-7 regression.

## Verification boundaries

Earlier isolated render tests passed 15 tests / 240 assertions and the
platform command exited zero, but persistent result recording was rejected:
`:seon.test.runner/persistent-results-recording-failed`, "The cluster rejected
the prepl operation." The test-provenance owner was editing that boundary.
No foreign session or file was operated to repair it.

The independent schema-audit commit `7e35df213` widened retained-call IDs
to their admitted keyword/vector forms. The broader simplification suite
still reports 13 failures and 2 errors after that correction; it is not the
cause of those failures. Clean HEAD `131fa2a56` reproduces **21 tests / 122
assertions, 13 failures / 2 errors** with `bin/test-fast --paths AGENTS.md --
seon.render-simplification-test`. The existing issue
`docs/seon/issues/render-fixtures-dump-context-on-stale-assertions.md` records
this boundary. That suite is unchanged. The added exact-database regression
has its own `seon.render.retained-test` namespace and canonical real SCI fixture.
Final combined fast command used all ten source/test/fixture paths listed
below and six namespaces: `seon.render.retained-test`,
`seon.render.web-context-test`, `seon.render.web-debug-test`,
`seon.render.runtime-test`, `seon.ai.tokens-test`, and
`seon.cluster.prompt-test`: **31 tests / 363 assertions, 0 failures / 0 errors**.

The page-speed isolated `bin/test --paths` gate used `src/seon/render.clj`,
`src/seon/render/web.clj`, `test/seon/render/web_context_test.clj`, and
`test/seon/render/retained_test.clj`, with the four render namespaces above:
**16 tests / 246 assertions, 0 failures / 0 errors**, exit 0. Its final run
did not report the earlier persistent-recording rejection.

The token gate overlays those paths plus `src/seon/ai/tokens.cljc`,
`src/seon/cluster/prompt.clj`, `src/seon/render/transcript.clj`,
`test/seon/cluster/prompt_test.clj`, `test/seon/ai/tokens_test.clj`, and
`test/seon/fixtures/run7_token_observations.edn`, selecting the token/prompt
namespaces plus the required web-debug/runtime namespaces.
Result: **26 tests / 315 assertions, 0 failures / 0 errors**, exit 0.
The final `bin/test --paths` with the same paths and `--platform` passed:
**84 tests / 505 assertions, 0 failures / 0 errors**, exit 0.
Both final gates completed without a persistent-result recording rejection.

The broader simplification namespace remains red at clean HEAD as recorded
above; these green scoped gates do not claim that pre-existing suite is fixed.
Default remained pid **23729** throughout; no stop, restart, refork, reseed,
provider request, or agent message was performed by this lane.

## Landing and cleanup

Page-speed commit: **a2bca009c**. The token-estimate commit contains this
final update, its fixture/probe, and the archived resolved token issue.
Both slices are path-limited on `steward-platform`.
All lane shell sessions finished. Successful test roots were removed by the
runner; the failed broad-gate root was removed after exit and an operator
status check reported no live clusters or orphan JVMs there. Lane scratch
logs and the thread-sample file were deleted. No scratch cluster or worktree
was created; unrelated shared-tree files were preserved.
