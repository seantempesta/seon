---
type: issue
status: open
severity: high
created: 2026-09-25
tags: [issue, provider, no-provider, paid-calls, ai]
---

# Provider completion ignores the no-provider request attribute

`:seon.config.ai/no-provider` routes to the request attribute `:seon.ai/no-provider`
(`resources/seon/schemas/seon.config.ai.edn:64-71`), and `seon.ai/targets` puts it on the primary target. But
`seon.ai/complete` (`src/seon/ai.clj:1457`) never reads it. It reads the credential and sends the request.

Today the only guard is the turn: `seon.turn` checks `(:seon.config.ai/no-provider settings)` before it calls
(`src/seon/turn.clj:4513`). A second caller of `complete`, or a turn path that skips that check, would make a
paid request in a world whose layers say no-provider.

**Wanted:** `complete` refuses a request carrying `:seon.ai/no-provider true`, before it reads the credential and
before any I/O. The refusal is a declared flat error that says no network call happened, as `::no-credential`
already does. Regression: a no-provider target gives that refusal and the HTTP client is never reached.

Found by lane fix-no-provider-overlay-opus. `src/seon/ai.clj` is outside that lane's files.
