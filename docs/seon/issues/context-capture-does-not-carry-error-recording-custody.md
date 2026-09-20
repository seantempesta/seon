---
type: issue
status: open
severity: friction
tags: [issue, context, contracts, error-model]
---

# Context capture receives raw errors without occurrence recording inputs

Observed at `1b5d19c0e` during ops-effects-2. This is a durable projection
decision, separate from the deferred handler-contract recognition decision.

`seon.context/contribution-row` (`src/seon/context.clj:468`, projection at
487) and `capture-tx` (490, projection at 533) copy a kind and message onto
capture-owned rows. Their input contracts accept `:seon.error/value`
(`resources/seon/schemas/seon.context.edn:11`, `:26`): a raw base observation,
with no guaranteed occurrence identity or prepared recording transaction.
Copying arbitrary facet members into these rows cannot preserve the ruled
ownership of raw offending evidence on an error occurrence; discarding the
raw members would silently lose evidence at this projection.

The existing owner is `seon.error/recording` (`src/seon/error.clj:1643`).
Its request requires process identity, admission caps and evidence-byte
bound in addition to the observation (`seon.error.edn:164`). Capture has
only a database value and turn identity on its refusal arm. Inferring the
missing process custody or inventing bounds would violate values-carry-their-
world; adding a per-capture or per-facet EDN field would violate the explicit
occurrence ruling.

The held `src/seon/turn.clj:4335` caller passes the raw error into capture,
commits that transaction, then `:4364` calls `fail!` with the original error.
The local `fail!` at `:4225` hands the cluster, agent and turn to `settle!`.
Thus the recording inputs exist in the turn owner but are not part of the
capture contract. The earlier resolved pre-provider-refusal issue owns
terminal recording and closure; it does not specify an occurrence relation
for capture/contribution rows.

This is the conversion PRD §6 consumer boundary: the declared base members
do not express the raw-versus-recorded evidence distinction needed to move
this durable projection onto the canonical occurrence. No context source,
schema or tests have been changed pending that representation decision.

Three options (engineering estimates):

1. **Recommended:** the existing turn/error owner prepares recording and
   passes the occurrence reference/transaction into capture; compose the
   writes once. Cost 2–4 hours across context and held turn plus canonical
   tests. Guarantee: one occurrence owns the full evidence and capture names
   it. Give up: a context-only mechanical sweep; contribution failures need
   the same explicit recording handoff from their producer.
2. Supply the existing recorder inputs to capture and call `error/recording`
   there. Cost 3–5 hours including callers and settlement deduplication.
   Guarantee: capture can commit its occurrence atomically. Give up: the
   current small capture contract; the existing later recording must be
   coordinated so counts do not change accidentally.
3. Defer the two durable kind projections explicitly and continue the other
   sites/families. Cost about 15 minutes to document; option 1 or 2 remains
   owed. Guarantee: no unreviewed change to evidence ownership. Give up:
   kind-free capture storage at this checkpoint.

Acceptance: the chosen representation records arbitrary offending objects
through the existing occurrence projection/data-edn, capture and contribution
facts retain the intended relation, and pre-provider refusal still records
and closes exactly once under the canonical armed fixture.
