---
type: research
status: active
tags: [contracts, provider, transcript, live-test]
created: 2026-09-15
---

# Run 6 blockers

## Scope and grounding

Read `AGENTS.md`, both assigned issues, the plan README and working edge end
to end. Read the assigned q/pull arities, provider completion parser, reply
reader, attempt/reply and continuation seams, and transcript state/problems
owners. Read the Clojure, Datahike, REPL, provider, testing and web UI skills.
The owner subsequently required contract guards, instrumentation support,
entity/datoms regressions, and the broad-input inventory below.

`default` was alive at PID 23729, prepl 54412, HTTP 7994. MCP answered and
reported one deferred agent. Preserved foreign edits, including
`src/seon/error.clj`, `src/seon/instrument.clj`, generated CSS, and untracked
files. Instrumentation became clean after `d4f8a536c`; the guard changes
start from that committed version. No default restart, refork or reseed.

### Dependency ledger

| Dependency at HEAD | Mechanism read | First-party owner |
| --- | --- | --- |
| Datahike `cdcb5792db8bd599487f099437265d18a31164a5` | `reference-code/datahike/src/datahike/query.cljc`: `normalize-q-input`, `query-input-count`, `query-source-bindings`; `pull_api.cljc`: pull argument-map/positional evidence calls; `api/specification.cljc`: datoms | `src/seon/db.clj` |
| Malli `3517a3cd9271b2083780ac7be1725493905bca2e` | `reference-code/malli/docs/function-schemas.md` function guards; `src/malli/core.cljc` `-instrument-f` checks `[args ret]` after return; `src/malli/error.cljc` resolves `:error/fn` | `src/seon/instrument.clj` |
| SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2` | persistent acquired context used by the existing canonical continuation fixture | `test/seon/turn_continue_test.clj` |
| core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` | existing per-agent proc and bounded event fixture; no new flow mechanism | `src/seon/turn.clj`, `test/seon/turn_continue_test.clj` |

History: `ae2c180a4` introduced the stop configuration; `c15d37d55` already
made no-form replies accepted error evaluations. The repair reuses that path.

## Exact run-6 source

Read from stored evaluation source in default via MCP JVM evaluation:

```clojure
(seon.db/q
  '[:find ?id ?customer ?amount
    :where
    [?e :example/order ?id]
    [?e :example/customer ?customer]
    [?e :example/amount ?amount]]
  [])
```

```clojure
(seon.db/q
  '[:find (pull ?e [*])
    :where [?e :example/order _]]
  [])
```

Before edits, the second form through live SCI returned shown text `[]`,
outcome `ok`, in 5 ms. Datahike legally accepted `[]` as an empty source;
Seon's argument count alone could not distinguish it from the implicit `$`.
`test/seon/run6_db_test.clj` retains both complete source strings.

## Implementation and verification log

- q declares a Malli function guard for parsed input counts, elided `$`,
  database source slots, and argument maps with stray positional inputs.
  The read seam also refuses those shapes. Source-less collection queries
  remain valid. Upstream error values retain their existing propagation.
- Malli guards run **after** a function returns. Both Seon wrappers had
  explicitly excluded `:guard`. They now arm it and explain the guard's
  `[args ret]` value as a normal contract violation. The q body prevents the
  invalid call from reaching Datahike before the guard reports it.
- Normal `stop` with zero assistant text is an ordinary completion. The
  reader produces `Your reply began with a response; send a form.` and the
  existing turn seam stores one error evaluation and continues. The text
  schema must admit the empty string. The reader's short arities also need
  a positive source bound for empty text.
- A session stall derives from the latest closed provider refusal, absent
  accepted reply, remaining turns and plan steps, no open turn, and no next
  work. The same derivation supplies the header and first problems rule.
- Publication initially refused the new predicate because the long-lived
  JVM had not loaded it. MCP `schema/compilable-form` confirmed
  `:seon.schema/unresolved-predicate seon.db/query-call-valid?`. Hot-reloaded
  `seon.db` and `seon.instrument` through MCP and re-armed 974 functions;
  publication/adoption is separately verified below.

### Verification boundaries

The clean-HEAD instrumentation baseline (`bin/test-fast --paths AGENTS.md --
seon.instrument-test`) ran 22 tests / 99 assertions with two failures in
`a-sci-only-arity-miss-names-its-program-graph-arglists`. Its ignored function
registration was a refused write: missing `:seon.schema.admission/source`
and `:seon.fn/ns`. The owned test now supplies both declared facts and asserts
the transaction succeeded. No foreign production owner was changed to fix it.

The first live guard probe returned outcome `error` in 8 ms, with the exact
headline below. MCP's subsequent text projection failed because
`seon.sci.kernel/invoke` received `:seon.db/db nil`; that separate boundary is
recorded in [the MCP projection issue](../../../seon/issues/mcp-sci-error-projection-passes-a-nil-database.md).

```text
seon.db/q violated its contract (invalid-guard): seon.db/q :in [$] does not match supplied arguments [[]]. Every source input must be a database value. Use (seon.db/q query input ...) with the database elided, or (seon.db/q database query input ...) with an explicit database first.
```

The first stall probe falsified an incomplete pull selector: unfinished steps
have no `completed-tx`, so pulling only that attribute returned `[]`. Pulling
the step identity as well returns all seven unfinished run-6 steps. The live
run still has 25 turns left and no next work. No session was resumed.

### Live observations and iterations

- `bin/seon init --dev default --changed src/seon/render/transcript.clj`
  converged. MCP compared published and adopted commit IDs and found both
  `6aa96201-04c4-5d43-a883-b97bbc62cc51`. This was in-place development
  adoption, with no reset boundary or new cluster.
- At that commit the live completion parser returned
  `{:seon.ai/text "" :seon.ai/finish-reason "stop"}`. The reader returned
  `:seon.cluster.reply/no-forms` and the expected repair sentence (combined
  probe 7 ms). The first complete run-6 source also returned an error after
  adoption (133 ms); the separate MCP text-projection defect remains.
- Browser DOM and screenshots of `/agent/juniper/debug` showed the red state
  `stalled: :seon.ai/unparseable-body at 08:46, waiting for an outside wake`
  and `Session stalled · 1` before `Fault notifications delivered · 1`.
  The selected turn is still `e09bbaba8c59`; budget is 5/30 used and 0/7
  steps complete. The browser observation first exercised a hot-reloaded
  renderer; all 974 functions were re-armed, then adoption converged above.
- The combined fast iteration ran 135 tests / 1,156 assertions: no failures,
  one incomplete stalled-render fixture error. Its corrected request includes
  the real caps, evaluation deadline and error policy. The final focused
  query/stall fast run passed 3 tests / 76 assertions. The revised continuation
  test also passed its assertions, including malformed JSON deferral and the
  empty-stop error in the next prompt (the preceding stall fixture was still
  incomplete in that earlier run).
- Item-1 isolated gate passed 78 tests / 864 assertions, no failures/errors;
  coordinator/tests phase 249 seconds. Final combined gate and platform
  counts are recorded below after completion.

Final isolated gate command:

```sh
bin/test --paths \
  src/seon/db.clj src/seon/instrument.clj src/seon/ai.clj \
  resources/seon/schemas/seon.ai.edn src/seon/cluster/reply.clj \
  src/seon/render/transcript.clj test/seon/run6_db_test.clj \
  test/seon/run6_stall_test.clj test/seon/ai_test.clj \
  test/seon/instrument_test.clj test/seon/turn_continue_test.clj -- \
  seon.run6-db-test seon.run6-stall-test seon.instrument-test seon.ai-test \
  seon.db-test seon.loop-proof-test seon.turn-continue-test \
  seon.help-trial-test seon.repl-grammar-test
bin/test --platform
```

The platform gate passed 84 tests / 505 assertions, zero failures/errors;
coordinator/tests phase 79 seconds. Its snapshot digest was
`c42fd7f952237a89b6acf54141553eb8fd85fc2419251dbd0466cb49fe7a5e1d`.

The first combined gate ran 135 tests / 1178 assertions, with six failures
and one error, all in `seon.loop-proof-test/virtual-loop-end-to-end`:
its virtual submission immediately after arming met `agent-already-running`.
The gate confirmed the same task green in a fresh worker with all nine
namespaces loaded and reported `parallel-only`, no detected global-state
leaker. This is an observed virtual-submission boundary, not an attribution
to a foreign edit. The exact evidence is in
[the fixture issue](../../../seon/issues/virtual-loop-fixture-submission-can-race-an-armed-turn.md).
The full command above is being repeated with `SEON_TEST_WORKERS=1`; no
virtual lifecycle source or foreign session is changed to obtain a result.

Ownership accretions explicitly required by the owner's followup: the
instrumentation guard seam and the datoms contract. The completion text
schema in `resources/seon/schemas/seon.ai.edn` also changes because armed
completion outputs otherwise reject the requested empty string. No change
to `seon.error`, `seon.turn`, the default session, or foreign source/CSS.

## Broad input contracts — dated inventory, 2026-09-15

Landing slice 1: `dda9f0366`, database shape guards, both instrumentation
wrappers, exact run-6 regressions, and the completed callable-input inventory.
Slice 2 changes completion parsing/schema and the existing reader only;
the attempt→reply seam in `seon.turn` already supplies the required accepted
error-evaluation behavior and needs no duplicate transition.

Reproduction command (ordinary working-tool search, not production parsing):

```sh
rg -n '\[:\*|:seon.schema/value' src/seon/db.clj src/my src/seon/agent.clj src/seon/plan.clj src/seon/note.clj src/seon/cluster/message.clj src/seon/test.clj
```

Read the enclosing functions and distinguish inputs from outputs and private
implementation data. Eight public functions have matching input contracts:

| Function | Reason or correction |
| --- | --- |
| `seon.db/connection?` | Predicate on any value; must return false for non-connections. |
| `seon.db/database-value?` | Predicate on any value; must return false for non-database values. |
| `seon.db/q` | Heterogeneous Datalog bindings require arbitrary values, but the new guard enforces parsed count/source shape and forbids ignored arguments. |
| `seon.db/datoms` | Index components may include arbitrary stored values. The guard enforces the index/map call shape and positional component count. |
| `seon.db/apply-diff` | Applies changed paths to any prior shown value, including scalars; the changed paths have their own schema. |
| `seon.db/diff` | The Var-target arity forwards heterogeneous arguments to the target's own contract. Its owner derives database slots and target arity from program facts; no second target contract is inferred here. |
| `seon.cluster.message/render-inbox-ai` | Receives either a recipient reference or acquired message maps at the render seam; the function explicitly handles both. Outside this lane's change scope. |
| `seon.cluster.message/render-inbox-html` | Same recipient/reference-versus-message-collection polymorphism, with explicit database custody. Outside this lane's change scope. |

No matching public input in `src/my/`, `src/seon/agent.clj`, or
`src/seon/plan.clj`, `src/seon/note.clj`, or `src/seon/test.clj`.
`seon.agent`'s match is an output map value, not an input.
This is a point-in-time inventory, not a maintained function list.
