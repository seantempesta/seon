---
type: issue
status: open
severity: friction
tags: [issue, config, schema]
---

# Derived config attributes omit `:seon.config.ai.backup/api-key-variable`

## Problem

Three tests fail at HEAD because `config/default.edn` declares
`:seon.config.ai.backup/api-key-variable` while the DERIVED config
attribute set omits it. Found by the `per-cluster-history` lane and
reproduced in the `open-maps` lane's full suite: 862 tests / 4,276
assertions, 3 failures, 0 errors — all three this one cause, no others.

## Evidence

The declaration is present and structurally IDENTICAL to its three
siblings, so the schema is not the difference:

- `resources/seon/schema.edn:775` `:seon.config.ai.backup/model`
- `:780` `:seon.config.ai.backup/endpoint`
- `:785` `:seon.config.ai.backup/api-key-variable`
- `:790` `:seon.config.ai.backup/timeout-ms`

All four carry `{:min 1, :seon.config/optional true,
:seon.config/per-agent true}` on a `:string` (or `:int` for
`timeout-ms`). `config/default.edn:199-202` sets all four to
`:seon.config/absent`, deliberately — its comment explains the backup
descriptor row is explicitly absent so `disposition` never returns
`:failover-now` unless a cluster sets the dial.

So the defect is in the DERIVATION of the config attribute set, not in
the declaration or the default. Something drops exactly one of four
otherwise-identical keys.

## Why it was not fixed on the spot

Diagnosed at session close on 2026-08-02 by the orchestrator, which
confirmed the four declarations are identical and therefore that the
obvious one-line fix (adding a missing marker property) does NOT apply.
The real cause is in the derivation and deserves fresh reading rather
than a guess — the standing rule is that a wrong fix is worse than an
open issue.

Related but distinct: `config-dial-discovery-has-three-authorities.md`
already records that config-dial discovery has more than one authority.
Check whether this is an instance of that defect before fixing it
separately; if it is, resolve them together and close this one as its
worked example.

## Acceptance

- The derived attribute set contains all four
  `:seon.config.ai.backup/*` keys, and the three failing tests pass.
- The fix names WHY one key was dropped — if discovery has a rule that
  silently excludes a key, that rule is the defect, not the key.
- A regression that fails against the current derivation and passes
  after, so a fifth key added later cannot be dropped silently.
