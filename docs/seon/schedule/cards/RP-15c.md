---
title: "Card RP-15c: Convert the old turn fixtures, group 2"
type: reference
status: active
created: 2026-10-08
---

# RP-15c Convert the old turn fixtures, group 2 (of three)

As RP-15b (same helper `seon.test-support/recorded-call!`, same two-way classification). Files (8):
`test/seon/program_test.clj` (`:1095`), `test/seon/receipt_set_attributes_test.clj` (`:27`),
`test/seon/context_capture_history_test.clj` (`:20`; if S4-07b3 deleted capture, delete the file's capture tests),
`test/seon/schema_usage_guard_test.clj` (`:372`), `test/seon/turn_disposition_test.clj` (`:28`),
`test/seon/receipt_write_carrier_test.clj` (`:32`; TX-0 already converted its assertion), `test/seon/loop_proof_test.clj`
(`:212`), `test/seon/ai_test.clj` (`:481`).

Proof: each namespace green; `rg -n 'turn/open-tx'` over them answers nothing.

Commit: `bin/lane commit <lane> -m "RP-15c: turn fixtures record a call instead of opening a run (group 2)" --test seon.program-test --test seon.receipt-set-attributes-test --test seon.context-capture-history-test --test seon.schema-usage-guard-test --test seon.turn-disposition-test --test seon.receipt-write-carrier-test --test seon.loop-proof-test --test seon.ai-test <the eight files>`

Dependencies: RP-15b (the helper). Reset: none.
