---
type: landing
status: committed, unadopted (hook publication paused; root adopts)
created: 2026-09-25
scope: lost-error seams cut P0 (D-delete), design docs/research/agent-platform/lost-error-seams-design-2026-09-25.md §D
---
# Landing: seams cut P0, D-delete

## Broken or unproved

- **Nothing has been run.** Under the load rule, the lane ran lint and read-only REPL probes only. These edits are
  not published into default, and no namespace was loaded.
- **Temporary gap (named in design §D.2).** Until R3 lands, a host function's output violation under `:record` is
  *returned* to the calling agent by the evaluation boundary. It is not stored as a core fault. Under the default
  `:panic` nothing changes, because the wrapper already threw there.
- **D04 has no regression.** It needs a `db/pull` refusal from a real `db/db` value, and the lane found no
  canonical-world way to cause one. The fix is two lines, and its contract is checked by the armed output.
- **Dead data left for later cuts.** `wrap-interpreted` still takes the positional core-error `mode`, and
  `apply!`'s policy still records it. Both are dead for the wrapper now; removing them changes the `sci/eval.clj`
  callers (R2/R4 files). Callers in `sci/eval.clj`, `cluster.clj` and `boot.clj` still pass
  `:seon.flow/commit-fault!` in arm requests. That key is now ignored, and R1/R2 delete it.

## What changed

| Path | Change |
|---|---|
| `src/seon/instrument.clj` | D01: the outer catch in `compiled-wrapper` is deleted, so under `:record` a boundary refusal is no longer returned as the function's value. The wrapper is Malli's `-instrument` function itself (`reference-code/malli/src/malli/core.cljc:2202-2231`), with the throwing `:report` kept. Also deleted: the unused boundary `marker`; E05's record-mode commit of caller-input refusals; the recorder requirements in `wrap-interpreted` and `apply!`; and `commit-fault!` in the policy. Docstrings are made true |
| `resources/seon/schemas/seon.instrument.edn` | the `:seon.flow/commit-fault!` request member is deleted |
| `src/seon/sci/kernel.clj` | E07: `failure-value`, the one normalizer used by `invoke` and `sci/eval.clj:4122`, `:4442`, rethrows a throwable whose cause chain carries a `:seon.fault/recorded` receipt. Before this, a recorded panic became an ordinary evaluation value. Requires `seon.fault`; a script over the ns forms showed no cycle, since `seon.fault` never reaches `seon.sci.kernel` |
| `src/seon/fault.clj` | `carried-receipt` becomes public (one token) |
| `src/seon/ai.clj` | D02, D03: `attempt-ai` and `attempt-html` declare the floor's `:seon.render.value/missing-root-identity-error` alternative |
| `src/seon/background.clj` | D04: `poll` returns a refused `db/pull` as its declared error, never projected as an empty receipt |
| `test/seon/instrument_test.clj` | Converted: host boundaries in both dials; `wrap-interpreted` under `:record`; SCI contracts in both dials; the sovereign fork, renamed `a-sovereign-sci-fork-refuses-by-throwing-under-both-dials`; cluster-policy. The deleted assertions tested deleted machinery (record-mode returns and a required recorder). New: `armed-compositions-return-only-their-declared-alternatives` (D02, D03, E07) |

Line counts: source −68 / +48; schema −3; test −92 / +79. Net −36 source and schema, −13 test.

## REPL evidence (default, PID 39484, JVM mode, session `lost-error-seams`, scratch ns `tmp.lost-error-seams`, read-only)

- **D01 reproduced.** A `compiled-wrapper` over `[:=> [:cat :int] :string]` and `identity`, called with 42, was
  compared across dials:
  - `:record` **returned** a `PersistentHashMap` with `:seon.instrument/check :output`, and the recorder stub ran
    once;
  - `:panic` threw the same output refusal.

  Two wrappers plus two calls took 1.15 ms. After the change, both dials take the `:panic` path, because `wrapped`
  is the object that path already used.
- **D02 and D03 reproduced.** On the valid unit `{:seon.render/value {:seon.ai.attempt/id "x"}}`, the armed
  `attempt-ai` and `attempt-html` threw output refusals, while the raw floor returned
  `missing-root-identity-error`.
  - Under the projection registry, the new contracts validate that output (`:ai-valid true`, `:html-valid true`),
    a rooted unit (`:rooted-ai-valid true`) and the input (`:input-valid true`).
  - The old `:string` contract refuses it (`:old-ai-valid false`).
- **E07 reproduced.** `failure-value`, given a throwable carrying a valid `:seon.fault/recorded` receipt, returned
  a `:seon.sci.kernel/evaluation` value both directly and wrapped in an interpreter-style `ex-info`.
  `seon.fault/carried-receipt` finds the wrapped receipt.
- **D04.** `db/pull` returns a flat `:seon.db/database-read` refusal map for a refused read, for example an unknown
  lookup attribute. `poll`'s `if-let` treated that truthy map as a receipt.

No timings regress on a hot path. Removing the outer `try`/`apply` frame from every armed call can only reduce
work. It was not measured because nothing was loaded.

## Deleted paths

- `instrument.clj`: the record-mode catch and commit (12 lines); `marker` (1); the `wrap-interpreted` recorder
  requirement (11); the `apply!` recorder requirement (9) and its `commit-fault!` binding (1); the policy `cond->`
  branch (2).
- `seon.instrument.edn`: the `commit-fault!` member (3).

## Root runs

- `bin/test-check default --ns seon.instrument-test`
- `bin/test-check default --ns seon.background-test`
- `bin/test-check default --ns seon.ai-test`
- `bin/test-check default --ns seon.sci.eval-test`, because the SCI contract rows go through `sci.eval`
- `bin/test-check default --ns seon.cluster-test`, because boot's arm request still passes the ignored recorder

Adoption must re-arm the changed wrappers: `compiled-wrapper`, the `ai` and `background` contracts, and the
`kernel/failure-value` body.
