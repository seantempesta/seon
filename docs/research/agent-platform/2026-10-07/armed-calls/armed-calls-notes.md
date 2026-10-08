# Armed calls: wrapper hypothesis falsified

The installed wrapper compiles Malli validators and supply plans at arming, then checks only the call's arguments
and result. The reproduced 127k count belongs to the whole MCP pass: its schema storage selection walks the
projection and constructs diagnostic errors to answer boolean storability questions.

## Outcome and ownership boundary

The production defect is **not fixed by this lane**. `bin/lane claim armed-calls src/seon/schema/datahike.clj ...`
refused because `pass-waste` owns that producer. No held file was edited and no other lane was contacted.
`storability.patch` is an **unapplied, unverified proposal for root**, against HEAD's predicate: derive its boolean
directly from the retained storage facts instead of constructing and discarding an error. `pass-waste` may have a
more complete fix; root should reconcile it with that lane's own work, not apply both blindly.

The independent slice adds an MCP entrance regression for the wrapper's actual work count, plus this diagnosis
and a reproducible measurement script. This regression is already green on the installed implementation; it does
not claim to close the whole-pass defect. No production wrapper change is justified by the evidence.

## Reproduction

Default's committed source is `4b4caf1421cc3db8e48069e6e406f03505d07ecc`. MCP uses cluster `default` throughout.
The measuring agent is `armed-calls-measure`; the direct SCI probe uses branch agent `armed-calls`.
`measure.clj` records the exact inputs. The installed `seon.pass-probe/run-mcp` creates fresh agents, measures the
real `seon.mcp/call` entrance on platform threads, and retires its hosted agents in `finally`.

| Probe on unchanged installed code | Wall ms | Allocated bytes | Armed calls | Own commits | Store bytes written |
|---|---:|---:|---:|---:|---:|
| Fresh agent, ordinary 2 KB form | 42.565 | 58,658,104 | 10,752 | 1 | 259,297 |
| Next pass, one-line contracted definition | 193.599 | 317,214,512 | 39,086 | 1 | 639,799 |
| First pass calling that definition | 79.190 | 117,441,616 | **127,445** | 1 | 253,135 |
| Existing agent, warm `(pf 41)` after redefining an existing function | 54.520 | 51,375,968 | 8,087 | 1 | 244,068 |

The first-after-definition row allocated 32,146,304 bytes on `async-mixed`, 83,735,416 on
`ForkJoinPool-1-worker`, 160,088 on the connection thread, 27,680 on `InnocuousThread`, 320 on timer threads,
and 1,371,808 unattributed bytes. These are JVM-wide readings and therefore upper bounds under concurrent work.
No JFR was necessary to distinguish the function evaluation from the complete pass: both owners expose counts.
This is baseline evidence, **not a before/after fix comparison**.

The complete direct SCI replies had values 42, `[1 2 3 4 5 6 7 8 9 10]`, and 5050 for `(pf 41)`,
`(mapv pf (range 10))`, and `(reduce + (map pf (range 100)))`. Their respective `:seon.repl/calls` were
**1, 10, 100**, all `:seon.repl/ms 0` on the repeat. There were no error or diagnostic members. The first
single call earlier took 2 ms. Counting the whole pass as that wrapper's recursive work is incorrect.

## Cause and proportionality

`src/seon/schema/datahike.clj:242` (`compiled-attribute-selection`) walks retained roots and optional members.
`:218` (`declared-storable?`) calls `attribute-declaration` simply to inspect `:seon.error/at`.
`:156-204` derives a declaration or builds a diagnostic containing environment/world evidence.
The first-after-definition profile counted:

| Function | Calls | Inclusive ms |
|---|---:|---:|
| `storable-attribute-in?` | 8,263 | 19 |
| `declared-storable?` | 8,149 | 18 |
| `attribute-declaration` | 8,149 | 16 |
| `compiled-storage` | 8,203 | 0 (integer report) |
| `seon.db/database-value?` | 11,357 | 0 |
| `seon.db/connection-object?` | 10,432 | 0 |
| `seon.env/environment?` | 10,257 | 0 |
| `seon.schema/projection-value?` | 10,130 | 0 |
| `seon.sci.admit/sci-context?` | 10,089 | 0 |
| `seon.schema.internal/reference-id` | 7,579 | 0 |

Settlement took 53 ms inclusive. This work follows projection members and diagnostic construction, not the
one-line function's arity or input size. The existing task is
`seon.schema.datahike/an-ordinary-pass-builds-hundreds-of-storability-refusals`, already persisted in
`resources/seon/tasks/seon.schema.datahike.edn`; no runtime-only task was tagged by the new test.

## Wrapper audit

Pinned Malli: `43c6dca8d2d8df3162b13725939954807be4a4ed`.

| Concern | Installed owner and guarantee | Per-call work |
|---|---|---|
| Input/output validators | Malli `core.cljc:2210-2249`, `-instrument-f`, captures validators before returning its closure | One input and one output validation; optional guard only if declared |
| Compiled contract | `src/seon/instrument.clj:823-843`, `compiled-wrapper`, retains or compiles the arm's schema | No contract compilation on a successful call |
| Explain/refusal | `instrument.clj:895-920`, report callback; `violation` at `:428` | Only after invalid input/output/arity/guard or throwing validator |
| Supply plan | `instrument.clj:930-933`; `call_preparation.clj:768-790`, `arm-plan` | No plan means no preparation closure |
| World lookup | `call_preparation.clj:1118-1136`, `preparing` | Reads the world only for a call that may need supply; `pf` has no supplied member |
| Profiling and digest | `profile.clj:175-205`, `with-cell`; `timed` at `:266` | Held counters and the definition key; two clock reads, no digest derivation or DB query |
| Digest comparison | `instrument.clj:633-646`, `wrap-interpreted` | At installation, not invocation |

The simplest alternative was to leave the already-correct wrapper intact. The proposed predicate change is
EQUIVALENT for registered and absent attributes, native refs, nilable/non-native forms, and secondary-only
attributes: it applies the same refusal/type/secondary conditions without building the refusal. Its storage facts
already belong to the compiled root (`compiled-storage`, Malli `-cached`, `core.cljc:353`). Expected cost is a
registry lookup and retained-fact checks per queried attribute; it eliminates diagnostics but does not eliminate
the entire projection walk. No second cache, registry, arbitrary limit, or wrapper is introduced.

## Health and proof

Both initial `bin/seon status` and MCP `runtime_status` succeeded and reported `:faulted`, 7 open faults,
37 errored receipts, heap live 0.87–1.00 GB, heap max 11.0 GB, and a full 512 MB cache. Existing findings:
`seon.schema/call-with-projection-state` carries tasks `c4ece4f4eec9`/`d27179bab996`;
the zero-hit reach cache has `seon.test.runner/reach-declarations-misses-on-every-branch`;
the origin failure has `seon.sci.arm/a-def-without-line-metadata-refuses-its-origin`.
The oversized definition pass is covered by `seon.schema/a-declaration-write-reloads-the-whole-projection`.
These were not resolved by this slice. The whole-pass counts and allocation remain unreasonable.

Design check: the requested wrapper explanation did not hold against the installed code or counts. Changing it
would optimize the wrong owner. The foreign boundary is the `pass-waste` claim, not in-flight breakage.
The slice uses HEAD plus only its own paths through `bin/lane commit` and names the single reaching test
`seon.armed-calls-test/a-tiny-contract-counts-only-its-invocations`.

Working-tree lint: `clj-kondo --lint src script test --cache false`, 6,846 ms, **0 errors**, 1,460 existing warnings.
Candidate test/commit results follow in the final response. No default restart/reset is needed for this test-only
slice, and none was performed. Source delta 0 lines; new test 24 lines. Root owns completion of the producer fix.
