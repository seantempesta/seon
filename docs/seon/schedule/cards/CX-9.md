---
title: "Card CX-9 Delete prompt selection, its calibration and the turns frame"
type: reference
status: done (55ede54a9)
created: 2026-10-08
---

# CX-9 Delete prompt selection, its calibration and the turns frame

Ledger row CX-9 (group D; replaces S4-07b2 and S4-07a). After CX-1 and CX-7 nothing calls `seon.cluster.prompt/prompt`.
Its token calibration existed only to price retained history against `:seon.config.ai/prompt-token-budget`; with no
selection it has no consumer, so it is deleted rather than moved to the attempt row (retired S4-07a). The window check
(CX-5) uses `seon.ai.tokens/estimate`; the provider's own `prompt_tokens` stays on the attempt row. Lane: Sonnet.

## Deleted (by name in `src/seon/cluster/prompt.clj` unless noted)

`history-contributions`, `priced-unit`, `retained-count`, `dropped-elision`, `selection-segments`, `compose`,
`selection-of`, `select`, `capture-mismatch`, `acquire-context-report`, `prompt`, `calibration-for`,
`model-calibration` and their schemas in `seon.cluster.prompt.edn`; the `cluster-prompt-prompt` delay in `seon.turn`;
`seon.repl/frame` (its only caller was `acquire-context-report`; the block's `:seon.context/turns` carries turns
left). Kept: `request-cluster-name`, `effective-ai-settings`, `validate-request!` if called, `window-check` (CX-5).
Check `:seon.config.ai/prompt-token-budget`'s other reader (`seon.render` profile token ceiling): keep the dial if it
is read there, else retire it with its schema and default. Tests: prompt-test deftests that price retained history;
the `with-redefs` of `prompt/prompt` in `test/seon/cluster/agent_test.clj`. DIAL-16 (calibration dials) is moot.
Net ≈ −330 source lines.

## Proof

`seon.cluster.prompt-test/a-model-call-sends-without-selection` (new): one fixture-model pass; the HTTP fixture
received a request; D: no `seon.cluster.prompt/*` selection row in the `run-model` profile.

## Cost

D.

## Dependencies and order

CX-1, CX-5, CX-7. Files: `src/seon/cluster/prompt.clj`, `resources/seon/schemas/seon.cluster.prompt.edn`,
`src/seon/turn.clj`, `src/seon/repl.clj`, `test/seon/cluster/prompt_test.clj`, `test/seon/cluster/agent_test.clj`.
