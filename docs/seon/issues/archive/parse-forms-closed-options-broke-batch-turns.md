---
type: issue
status: closed
severity: blocking
tags: [issue, agent, cljs]
---

# parse-forms closed options schema broke every :batch turn

## Problem

`seon.agent.turn/reply-program` calls
`(repl-internal/parse-program raw-reply {:seon.repl/current-ns starting-ns})`
and `parse-program` forwarded that combined options map straight into
`parse-forms`, whose declared options schema (added at `3a0dbd31`,
qualified at `f49268cd`) was a CLOSED map whose only key
`:seon.repl/strip-fences?` was REQUIRED. The live pod instruments
`parse-forms` from that declared schema, so every run-attached `:batch`
turn failed with `:malli.core/invalid-input`
(`:seon.repl/current-ns ["disallowed key"]`,
`:seon.repl/strip-fences? ["missing required key"]`) before any reply form
could evaluate.

## Evidence

Live generate-code drive on the isolated `gencode` cluster, 2026-07-21
01:44:11Z (`logs/clusters/gencode/pod/4c90744e-….log`): planner
`spicy-lies-marry`'s real Kimi K3 reply parsed into
`turn ▸ run-turn! error :malli.core/invalid-input` with
`:seon.error.malli/fn-sym seon.repl.internal/parse-forms` and args
`["…reply…" {:seon.repl/current-ns my.agent.spicy-lies-marry}]`. The
pre-existing behavior test
`namespace-program-can-start-in-the-active-repl-namespace` passed because
plain test bundles never instrument declared schemas — only the live pod
enforces them.

## Root cause and fix

The parser owns only `:seon.repl/strip-fences?`; `:seon.repl/current-ns`
belongs to `project-program`. Fixed in place: `parse-program` now forwards
`(select-keys options [:seon.repl/strip-fences?])` to `parse-forms` and the
full map to `project-program`, and the option key became `{:optional true}`
so the empty selection validates. Regression pinned by
`parse-forms-declared-schema-accepts-parse-program-forwarding`
(validates the exact forwarded args against the DECLARED metadata schema,
which uninstrumented test bundles otherwise never exercise), and the
schema-pin test updated to the corrected contract. Gate:
`seon.repl.internal-test` 47 tests/373 assertions and
`seon.agent.turn-test` green; live proof is the completed generate-code
drive on the rebuilt `gencode` cluster.
