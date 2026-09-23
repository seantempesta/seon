---
type: landing
status: in-progress
created: 2026-09-23
---

# Reviewed error constructor cut

The existing diagnostic preserves a supplied observation and derives whole-cause evidence only from its optional Throwable. The smallest composition adds a positional arity to that same Var, retaining literals outside the reviewed grammar; construction remains O(member count), with existing O(cause links + frames) Throwable work.

Authority: `error-constructor-cut-manifest-2026-09-23.md`, reviewed and folded at `0f0d9f619`; user launch and ownership release supersede the stale ledger rows. Admission now holds exactly current dirty paths plus `test/seon/render/value_test.clj`. In `tmp/error-constructor/census.clj`, `held` is that one explicit path and `held?` is `(or (held f) (dirty f))`. Each selected census records the current ledger digest and whole-file hashes. The unchanged preview checks them; application verifies every roster before/after hash before writing any file.

Malli seam: gitlink `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, `reference-code/malli/src/malli/core.cljc:1210` open maps and `:2237` arity grouping. The dependency working tree is foreign and untouched. Contract compilation uses the existing `seon.contracts-compile-test/check`, absolute checkout paths, and the live carried projection only when its packaged forms match the checkout.

## Verification boundary

The user reserved adoption and proving tests for the orchestrator. No adoption, restart, test request, push, or additional JVM was run. The live JVM source root was `data/source/b66a654382d730debd05c40cb1d22558546c763f`, with publication off. Static contract checks read checkout bytes explicitly; they do not establish runtime adoption, constructor overhead, reaching coverage, or passing tests. Dirty stopped-lane source/test/dependency files remain foreign boundaries.

Parent JVM probe: with explicit root `/Users/sean/src/seon`, cluster `default`, private session `sol-error-constructor`, call `(seon.error.refusal/diagnostic {:seon.error/at at :seon.error/layer :x/y :seon.error/operation 'a/b})` for `(java.util.Date. 0)`. Returned the exact three-member value; `(identical? at (:seon.error/at returned))` was true. Parent contract findings `[]`; combined probe/check 56.998 ms (prepl 70 ms). This is parent behavior only.

E0 is split at existing owner boundaries because complete regression changes exceed the provisional 40-line allowance. E0a couples the helper to error-owner conversion and prose deletion; E0b carries blob conversion and its producer regression. Neither is a helper-only slice.

| Slice | Sites | Src + / − / net | Test + / − / net | Kondo | Packaged contracts | Proving namespaces |
|---|---:|---|---|---|---|---|
| E0a | 4 | 20 / 44 / −24 | 60 / 31 / +29 | 0 errors, 74 existing warnings; 150 ms | `[]`, 110.274 ms | `seon.error.refusal-test`, `seon.error-test`, `seon.schema-test`, `seon.refusal-grammar-test` |
| E0b | 4 | 12 / 20 / −8 | 19 / 0 / +19 | 0 errors/warnings; 23 ms | `[]`, 27.582 ms | `seon.blob-error-test` (also retain `seon.blob-test` corruption coverage) |

E0a hand work: exact reviewed additive arity; extend both-entry whole-value/cause/domain tests, arm both entries under one projection in the producing-contract case, assert argument order/identity and original ex-info cause, and exercise actual `prepare` with overriding source evidence and its exact declared schema. The preview removed only `refusal-prose`, its obsolete test block, and converted its two renderer fixtures. Retained error-owner O/X literals preserve evaluation order and unsupported contexts. Existing cause-policy repairs are outside this mechanical cut. The +5 net E0a lines are regression evidence; source shrinks 24 lines. Later caller cuts repay this test growth before integration.

Initial E0 selected census: 365.874 ms; preview admitted eight sites and four files, net −50 before helper/regressions. The required pre-residue lint found eight four-argument calls before the arity was added; final E0a lint has zero errors. No operation initiated by this lane exceeded one second so far. Cache hit/miss counters are not exposed by these static tools; the checker reuses an equal packaged projection through its existing candidate input.


E0a commit `bab838388`. E0b re-censused the clean blob file (97.739 ms), applied only its four admitted sites, and added the actual stalled-input producer's whole-value assertion under its unchanged `:seon.blob/input-stalled-error` declaration. The unsupported `stage-file!` literal stays unchanged. Source shrinks eight lines; the 19 regression lines explain the temporary net growth. E0 total: source −32, tests +48; 111 src/test additions. New assertions are authored, not executed; orchestrator still owes armed runtime/cost/coverage and all five E0 proving namespaces.


## Sequential single-file slices

Every row has zero lint errors and packaged findings `[]`. All tests remain orchestrator proof obligations. No producer contract was changed in these slices. Script/status/roster/lint/contract evidence is in `tmp/error-constructor/<slice>-*`.

| Slice and path | Sites | Src + / − / net; test net | Kondo warnings / ms | Contract ms | Proof namespace | Hand residue |
|---|---:|---|---|---:|---|---|
| E1: `src/my/background.clj` | 1 | 3 / 5 / -2; 0 | 0 / 11 | 21.237083 | `my.background-test` | None |
| E2: `src/my/program.clj` | 4 | 14 / 21 / -7; 0 | 1 / 27 | 27.987833 | `my.program-test` | X literal retained; existing read-result catch retains class/message/data but omits the cause chain (outside this cut) |
| E3: `src/seon/agent.clj` | 1 | 4 / 5 / -1; 0 | 1 / 17 | 25.1875 | `seon.cluster.agent-identity-test` | Two O literals retained to preserve header evaluation order |
| E4: `src/seon/ai.clj` | 14 | 41 / 70 / -29; 0 | 0 / 42 | 27.304333 | `seon.ai-test` | Three C literals retained with attached comments; first also has interleaved header order. Existing extra-body catch keeps only its declared read-message |
| E5: `src/seon/await.clj` | 1 | 3 / 5 / -2; 0 | 0 / 13 | 22.220208 | `seon.await-test` | None; existing merge order retained |
| E6: `src/seon/background.clj` | 2 | 6 / 10 / -4; 0 | 0 / 12 | 24.328125 | `seon.background-test` | None |
| E7: `src/seon/bootstrap.clj` | 4 | 12 / 20 / -8; 0 | 0 / 28 | 23.072166 | `seon.bootstrap-test` | One X literal retained; outer ex-info unchanged |
| E8: `src/seon/call_preparation.clj` | 5 | 15 / 25 / -10; 0 | 3 / 40 | 24.954542 | `seon.call-preparation-test` | One X literal retained; supply catch still carries its original Throwable as offending evidence |
