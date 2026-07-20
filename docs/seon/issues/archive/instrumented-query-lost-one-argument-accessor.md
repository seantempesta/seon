---
type: issue
status: resolved
tags:
  - database
  - instrumentation
  - web
severity: friction
tags: [issue]
---

# Instrumented query lost its one-argument accessor

## Failure

The canonical Bun pod passed readiness, but opening the root Datastar feed
failed with `seon.db.query.cljs$core$IFn$_invoke$arity$1 is not a function`.
`query` declared both a fixed one-argument body and a variadic body whose
minimum was also one. Complete wrapper verification accepted the matching
profiles, but Malli's in-place CLJS surgery left the overlapping fixed accessor
unavailable to compiled direct callers.

## Resolution

`seon.db/query` now has one implementation and one function schema: a first
request-or-query argument followed by zero or more ordinary inputs. The body
still distinguishes the established one-map request from positional query
forms. This removes the overlapping callable shapes and lets Seon's existing
variadic maximum-arity bridge provide the direct one-argument accessor after
instrumentation.

## Acceptance

- Focused query transport tests pass under Bun.
- A restarted instrumented pod opens the root and data feeds without an
  unhandled rejection.
