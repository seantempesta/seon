---
type: research
status: active
tags: [database, research, flow]
---

# Restore completion transaction coordinate

## Question and shortest falsifier

After a restore completion transaction is followed by another main-branch
transaction, `seon.db.restore/record!` can still find the completion entity's
transaction id in history but cannot reconstruct the original immutable commit
id from the local temporal view. Returning the later head would falsely name a
different coordinate.

The shortest falsifier is: commit completion at `C`, commit one later
transaction at `L`, retry the same completion identity, and require the retry to
return `C` without emitting another transaction. A second falsifier inserts a
Datahike force commit at the completion transaction's same `t`; the resolver
must skip that metadata commit and still return the ordinary transaction
commit.

## Dependency ledger

| dependency or mechanism | selected version or SHA | grounded source | relevant behavior |
|---|---|---|---|
| Datahike | `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike/src/datahike/{versioning,writing}.cljc` | ordinary writer commits persist one immutable commit node and advance `:max-tx`; `force-branch!` writes a new commit node while retaining the selected database value's `:max-tx`; parents are immutable commit ids |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/core.cljc` | the writer can read retained immutable commit maps by commit UUID without materializing historical secondary-index resources |
| Malli | `0.20.0` | `reference-code/malli`, `seon.schema` | the portable request is closed and restricts the frozen head to branch `:db` |
| Seon database protocol | protocol v2 | `src/seon/db/protocol.cljc`, `src/seon/db/writer.clj` | one typed writer request/response and canonical protocol failure envelope; no restore-only transport |
| Seon replica RPC | current branch | `src/seon/db/replica.cljs` | the one pod-to-writer UDS owner sends the request; `seon.db` does not open another socket path |
| Restore completion | current branch | `src/seon/db/restore.cljc` | equal identity retry already finds the identity datom's transaction and emits no write |

The checked-out Datahike directory is at test-only child `eb3e2239`, but its
only delta from the selected SHA is in test files. The grounded versioning and
writing source is byte-identical to the selected dependency.

## Source findings

`datahike.writing/commit!` resolves immutable parent commit ids, applies one
transaction-produced database value, stores a commit UUID record, and advances
the branch head. Its comments explicitly identify the immutable commit graph
and its one writer commit loop. `datahike.versioning/force-branch!` instead
starts from an existing database value, replaces branch/parent metadata, and
creates a new commit id without advancing that value's `:max-tx`.

Therefore an original ordinary transaction commit at transaction `T` is a
reachable main-lineage commit where:

- the commit node's stored branch is `:db`;
- its `:max-tx` equals `T`; and
- no direct parent has `:max-tx = T`.

A force/branch metadata commit repeats a direct parent's `T` and fails the last
test. Walking all reachable candidates and requiring exactly one keeps a future
merge or malformed history fail-closed. The walk uses raw immutable Konserve
commit maps. It does not call `commit-as-db` for every ancestor, which would
materialize historical secondary indices and can contend with the live writer.

Datahike branch heads are aliases: a branch keyword can point at an immutable
commit node whose stored `:config :branch` names the source branch. Restore
completion is deliberately narrower than a general branch-history lookup. Its
portable request schema accepts only a `:db` frozen head, and the writer also
requires its routed current attachment to be `:db`. Branch aliases fail before
commit-node equality is considered.

## Live REPL probe

The selected default writer reported head commit
`6a5793ed-d90d-5f88-8ae3-e6deafc8d300`, branch `:db`, and `t` `536871013`.
`datahike.versioning/branch-history` returned 102 reachable commit values; the
first three formed a strict main-line sequence with transaction ids
`536871013`, `536871012`, and `536871011`. A raw Konserve read of the head
confirmed the commit node retains `:max-tx`, parents, and primary roots without
requiring a historical database connection.

## Closed failure semantics

| observation | protocol result |
|---|---|
| request or routed attachment is not live `:db` | `attachment-mismatch` (or structural `protocol` rejection for a non-`:db` portable request) |
| frozen commit is missing, or a required ancestor commit was garbage-collected | `unsupported-history` |
| commit graph is disabled | `unsupported-history` |
| frozen head commit resolves to different database, branch, or `t` | `attachment-mismatch` |
| frozen head is retained but is not an ancestor of current main | `non-ancestor` |
| no original main commit has the requested transaction id | `not-found` |
| more than one eligible origin survives the exact walk | `ambiguous-history` |

The CLJS public value preserves `:seon.db.protocol/error-kind` inside the
structured `:seon.error` ex-data. Restore retry treats any error value as a
closed failure and never substitutes the current head.

## Implemented mechanism

- `seon.db.protocol` owns the closed request, response, main-head schema, and
  new exact error kinds.
- `seon.db.writer` walks the frozen main ancestry through raw immutable commit
  maps, proves the frozen head remains an ancestor, filters repeated-`t`
  metadata commits, and requires exactly one origin.
- `seon.db.replica` remains the one UDS RPC owner. `seon.db` exposes one
  non-agent-facing errors-as-values wrapper and validates the returned
  attachment and transaction id.
- `seon.db.restore/record!` uses the resolver only when the completion
  transaction is no longer current. The existing current-head fast path stays
  local and an equal retry remains zero-write.

Focused JVM proof covers later-head recovery, a repeated-`t` force commit,
wrong attachment, abandoned non-ancestor history, missing transaction, and a
real branch-head alias. The protocol request and response also round-trip
through the production Transit codec. CLJS proof injects the replica response
at the single RPC owner while exercising the real `seon.db` wrapper and restore
retry, including original-coordinate return, zero-write, and structured writer
error-kind preservation.

Observed gates: resolver plus replay, six tests/33 assertions; Transit, ten
tests/32 assertions; restore CLJS, six tests/34 assertions. The standard writer
runner later became temporarily unloadable while the concurrent cold-start lane
held an unreadable in-progress `seon.launch` schema; the isolated Transit gate
was therefore invoked directly on the already prepared writer classpath. No
files from that lane were changed.

## Remaining integration boundary

This closes only later-head coordinate recovery for the completion fact. The
restore-aware cold transition still must compose writer promotion, blob proof,
reconstruction, completion read-back, and exact prepared-generation admission
before the open issue can close.
