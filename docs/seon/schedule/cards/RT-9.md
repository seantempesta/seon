---
title: "Card RT-9 Opening a merge request assigns it to root"
type: reference
status: active
created: 2026-10-08
---

# RT-9 Opening a merge request assigns it to root

Ledger §3.28 RT-9 (group M; amends F5). Lane: Sonnet.

## What becomes true
The transaction that opens a merge request (F5's request path, GV-5's data) also names the request's proofs
(`:seon.task/proofs`, RT-4: the source task's proof symbols; `:seon.merge/proofs` keeps their digests) and assigns the
request to root through `assign-tx` (branch: the target branch). The proofs are judged on the target, where they are
red until the merge commits (the source task was assigned only while they were red there). If they are already green
on the target, `assign-tx` refuses `:proofs-green` and F5 treats the task as done with no merge request (its
no-change rule). An update with a newer source commit changes no assignment.

## Functions
- CHANGE F5's request writer in `seon.task` (today `propose!`) to add `:seon.task/proofs` and
  `[:db.fn/call #'assign-tx …]` in its one transaction (TX-6b).
- CHANGE `my.program/request-merge!` (today `request-merge!`): delete the `message/send!` to root and the
  `:seon.task/undelivered` branch.

## Deleted
The "Please merge branch …" message and its undelivered handling (≈ −8 lines).

## Proof
`my.program-test/a-merge-request-is-assigned-to-root` (new): an agent with green proofs on its branch requests a merge;
the home holds the request with `:seon.task/proofs` equal to the source task's proofs, root holds it
(`seon.task/held`), root has no new message, and `unanswered-wakes` for root names the assignment.

## Cost
Inside the request's one transaction: `assign-tx`'s reads (one `verified?` per proof on the home).

## Dependencies
F5, GV-5, TX-6b, RT-1, RT-2, RT-4. Commit with `--test my.program-test --test seon.task-ingestion-test`.
