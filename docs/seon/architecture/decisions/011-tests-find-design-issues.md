---
type: decision
status: active
date: 2026-07-23
tags: [decision, architecture, testing, runtime]
---

# ADR-011: Tests find design issues; structure retains them

## Context

A recurring failure class invites two responses. The reflexive one fences the
symptom: one point test per observed instance, accumulating suites that pin
call-site discipline without removing the way the mistake is expressed. The
structural one treats the failing test as a design finding: the invariant the
tests keep re-asserting belongs in one mechanism, after which most of those
tests are redundant. Wire values that could not serialize, attributes used
without registration, test files invisible to every runner, and validation
switched off per tier were each a class, not a bug list.

## Decision

A test's primary product is a design issue. When a class of failure appears,
the response is to move its invariant to one choke point — codec totality at
the frame writer, registration and pull admission against the committed
projection, computed discovery with a completeness assertion, provenance
derived from the asserting transaction — and then retain exactly one
regression per dissolved class. The design question a new test must answer
first: which class is this, and what choke point dissolves it?

Supporting rules:

- Schemas are the edge-case engine. Registered shapes drive generative
  round-trip properties (encode→decode, derive→install→pull→validate) as
  standing totality checks; hand-enumerated edge lists are not written.
- Every proof must be claimed by a recurring surface. A test exists only if a
  computed discovery gate runs it; a live proof exists only if a checkpoint
  list re-runs it. "Green once" never counts. The orphan gate — every test
  file claimed by at least one runner surface, asserted structurally — is the
  standing enforcement of this rule.
- Fixture load paths are not the live boot path. A mechanism that passes
  through seeded fixtures still owes a separate live-boundary regression of
  its real boot/acquisition path.
- Localized tests belong to lanes; full suites run only at frozen-tree
  integration checkpoints, where the suite tests the integrated system rather
  than an in-flight shared tree.
- Assertions target facts, transitions, envelopes, and Datahike
  `:db.fn/cas` outcomes — never exact prose renderings. `:db.fn/cas` is
  reserved for facts two processes race to win exactly once: plan freeze from
  absent to digest, and run claim from no process to the process record
  together with a claim-epoch increment.
- Tests for a mechanism scheduled for deletion are not written, including
  regressions that would "protect" behavior the architecture has already
  replaced.

## Consequences

- Suites stay small relative to the invariant surface: one regression per
  dissolved class plus the generative properties, instead of a point test per
  historical incident.
- A growing bug queue triggers class triage, not test triage: instances group
  into classes, and a class with repeat instances and no design move is a
  flag.
- Deleting tests is normal. When consumers move to a choke point, the point
  tests it obsoletes are removed with justification in the same change.
- Coverage claims are structural: the discovery gate and checkpoint list are
  the authorities on what is proven, not a lane's remembered green run.

## Related

- [[laws]] — the compact testing law register.
- [[architecture]] — the dependency-aware test selection principle.
