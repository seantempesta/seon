---
type: issue
status: open
severity: cleanup
tags: [issue, documentation, contract, class-kill]
---

# Derive callable shape documentation from executable contracts

## Problem

Docstrings, bootstrap examples, and proof notes restate request keys or
lifecycle semantics already owned by schemas and code. The prose remains valid
text after the executable contract changes, so it teaches deleted or incomplete
behavior.

## Evidence

Four open issues recur from 2026-08-01 through 2026-08-07:
[[ai-retry-proof-still-cites-the-deleted-run-lease]],
[[bootstrap-teaches-bare-map-keys]],
[[my-fs-write-docstring-hides-its-own-request-shape]], and
[[production-docstrings-teach-deleted-semantics]].

## Owner

The declared function/schema documentation renderer and the bootstrap/help
surfaces that consume it.

## Acceptance

- Callable argument and result shapes, keys, and examples render from the
  installed schemas and function contracts.
- Docstrings describe semantics and intent only; they do not duplicate a
  request map, lifecycle, or deleted mechanism.
- A contract-shape change updates rendered help without a prose edit.
