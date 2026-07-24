---
type: issue
status: resolved
tags:
  - issue
  - testing
---

# Owner-close reconnect test raced retry backoff

The database remote-contract test closed its owner session five milliseconds
after a transaction failure and expected a reconnect attempt to have begun.
The retry backoff could legitimately exceed that timer, producing one connect
instead of two without violating the production contract.

Resolved on 2026-07-23 by latching the replacement connection function. The
test now closes only after the reconnect attempt is observed, then proves the
pending recovery returns an error and makes no further connection attempt.
