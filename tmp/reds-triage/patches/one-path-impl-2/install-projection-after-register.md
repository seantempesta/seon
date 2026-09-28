# A defn naming a schema its agent registered in an earlier run throws out of seon.turn/step

Found by reds-triage-opus on default 1be0f7448, 2026-09-28, branch :reds-triage (MCP branch
tool) with the hosted agent mcp/reds-triage (MCP eval_clj mode sci). Blocks
seon.contracts-plan-test (both tests), seon.contracts-install-test and, likely, the
juniper scenario of seon.loop-proof-test/virtual-loop-end-to-end.

## Reproduction (three ordinary runs of the same agent)

1. `(do (seon.schema/register! :example/order [:string {:seon.db/identity true}])
        (seon.schema/register! :example/amount :int)
        (seon.schema/register! :example/customer :string)
        (seon.schema/register! :example/order-row [:map {:seon.db/attributes true :seon.program/partition :seon.data}
          [:example/order :example/order] [:example/amount :example/amount] [:example/customer :example/customer]]))`
   -> value returned; 1,187 ms, 141,807 armed calls (structural-schema x44,898) — separate >1 s finding.
2. `(seon.db/transact! [4 order maps])` -> :ok
3. in my.agents.juniper: `(defn largest-customer {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map ...]]} [rows] ...)`
   -> NO VALUE: the reply is an escaped exception at :seon.error/layer :seon.agent/agent-graph,
   :seon.error/operation seon.turn/step: "Schema reference :example/order-row is not declared in the
   candidate projection; it is named by my.agents.juniper/largest-customer."
   Frames: seon.schema/projection-with-function-contract <- seon.sci.eval/install-evaluated-rows! (eval.clj:1567)
   <- seon.turn/resume-turn (turn.clj:5618) <- seon.turn/turn <- seon.turn/step. Same on retry.

Meanwhile the branch database holds all four :seon.schema/key entities and
`(seon.schema/projection-from-database branch-db)` contains :example/order-row.

## Cause

`install-evaluated-rows!` seeds its reduction with `(context-projection ctx)`. A registration
made by `seon.schema/register!` in an earlier run is committed but never advances the context's
projection (it is not among this batch's installations), so the carried projection is stale.

## Suggested fix (owner decides)

1. Seed from the committed value the rows were written on: `{:projection (schema/projection-from-database db) ...}`
   (derived once per declaration content), or advance the context projection when register! commits.
2. The install failure must not escape: return it beside the value as a diagnostic + recorded fault
   ("the path is total"); add its error alternative to install-evaluated-rows!'s output union.

Regression to add with the fix: register a schema in one run, defn naming it in the next, value returned and entity written.
