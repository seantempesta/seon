---
type: issue
status: resolved
severity: friction
tags: [issue, database]
---

# Protocol v4 fixtures missed response correlation

## Problem

Protocol version 4 requires the existing request ID on every response, but the
branch operator and replica-open test servers still returned version 3 fixture
maps. Their focused gates rejected otherwise-correct lifecycle responses.

## Resolution

The fixture servers now echo the incoming request ID, including error replies,
and the replica-open fixture derives its reply from the actual request. This
keeps out-of-order response correlation testable without adding a transport
envelope.

## Proof

The focused branch operator gate passes 5 tests/137 assertions, and the focused
replica gate passes 23 tests/121 assertions.
