---
type: issue
status: resolved
severity: friction
tags: [issue, agent]
---

# Make Namespace Docstrings Usable as Compact Catalog Entries

## Problem

Namespace docstrings existed across the compiled program, but many first lines
wrapped mid-sentence or described individual functions instead of the
namespace's responsibility. A planner therefore could not use the first line
as a reliable, bounded namespace-candidate catalog.

## Evidence

The 2026-07-19 compiled-source audit found 125 first-party namespaces with no
missing docstrings, but 56 first lines were incomplete and 13 exceeded the
intended 72-character summary convention.

Commit `9bfee359` rewrote the affected production namespace docstrings. A
CLJS-capable reader parsed every declaration and exact comparison with `HEAD`
after removing only the docstring proved that all non-docstring namespace
clauses remained unchanged. The compiled 125-namespace surface then reported
zero missing, over-length, incomplete, or body-less docstrings.

## Owner

Namespace declarations own their summaries. The program index and namespace
context renderer own the future derived compact projection.

## Acceptance

- Every production namespace has a complete, punctuated first-line summary of
  at most 72 characters.
- Remaining namespace documentation describes responsibility, principal
  surfaces, and boundaries without duplicating function documentation.
- Structural comparison proves no non-docstring namespace clause changed.

All acceptance criteria are satisfied by commit `9bfee359` and the recorded
reader and catalog audits.
