---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Datahike fixtures leaked connection references

## Problem

Three query-stats tests dereferenced fresh `d/connect` results without releasing
them. The generated pod also called `d/connect` again when its own logical
connection ID already existed, overwrote the one retained handle, and later
released only one of several acquired references. A config test still assumed
that arbitrary backend store properties could share physical resources and did
not release its successful acquisitions. Suite fixtures then correctly refused
to delete databases with active connection references.

## Resolution

The suite fixture now binds the one immutable database value acquired by its
owned connection. Every test reads that value, and fixture teardown releases the
single connection before deleting the database. The shared `teardown-db` helper
captures immutable configuration before release instead of dereferencing a
released connection. Generated pod `connect` is idempotent for an existing pod
connection ID, and the config test matches the conservative physical-store key
contract and releases every acquisition. Focused query-stats plus combined pod,
config, and capability gates provide the behavioral and lifecycle proof. The
remaining unrelated full-aggregation failures are tracked separately.
