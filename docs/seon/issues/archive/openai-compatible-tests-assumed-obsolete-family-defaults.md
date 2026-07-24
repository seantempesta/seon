---
type: issue
status: resolved
tags:
  - issue
  - providers
---

# OpenAI-compatible tests assumed obsolete family defaults

Five assertions treated every OpenAI-compatible provider as if it emitted
`reasoning_effort`, and assumed the generic descriptor had no shipped base
URL. Descriptor rows now own those differences.

Resolved on 2026-07-23 by selecting descriptor identities that promise each
tested wire key, asserting omitted keys for the generic row, and constructing
the missing-endpoint case explicitly from a resolved request. The adapter
documentation now states the shipped generic endpoint honestly.
