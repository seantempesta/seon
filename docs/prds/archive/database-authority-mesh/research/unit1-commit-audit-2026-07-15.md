---
type: research
status: active
tags: [research, prd, database]
---

# Unit 1 implementation commit audit — 2026-07-15

## Scope

This is a source-level review of Datahike commit
`0b65221586a20182639f2dd7984ca203238ea9f7` against
[[unit1-retained-test-design-2026-07-15]]. It changes no Datahike source or
tests.

## Decision summary

The commit establishes the right central mechanism: exact committed identity
is `[connection-id generation commit-id]`; speculative values clear their
context; generation admission and the weighted LRU share one atom; final close
atomically fences puts and evicts the exact generation. Opening publication
also preserves the generation that reservation minted.

It is not ready to graduate Unit 1. One writer-batching path is unsound, and
the retained tests omit the lifecycle, nonstreaming, branch/store, mutation,
and CLJS adversaries that could falsify the contract.

## Correctness blocker: an unknown report is lost inside a mixed writer batch

The writer reduces every report's modified attributes into one set
(`writer.cljc:225-230`) and passes that set to propagation. Propagation skips
only when the aggregate user-attribute set is empty (`query.cljc:2637-2642`).

That is unsafe when a greedy batch contains both:

- a real database change whose report has empty or otherwise incomplete
  `:tx-data` (secondary-index installation and purge-like operations are the
  documented examples); and
- an ordinary transaction with one known user attribute.

The known attribute makes the aggregate nonempty, so propagation copies every
parent entry not dependent on that attribute even though the unknown report
may have changed its result. The final committed child can therefore receive a
stale cached result.

The batch reduction must carry certainty as well as a set. If any report
represents a database change whose affected attributes cannot be certified,
skip propagation for the entire committed batch. A plain union is correct only
when every report's invalidation set is known complete.

Required adversarial proof: place an unknown/empty-change report and a known
attribute transaction in the same drained `txs` batch, seed a parent cache
entry independent of the known attribute, and prove the final commit does not
inherit it.

## Lifecycle ordering tradeoff requiring an explicit decision

Final release moves the connection registry to zero, then waits for writer
shutdown, and only afterwards closes cache-generation admission
(`connector.cljc:482-504`). The atomic close still prevents resurrection after
it runs: an in-flight put that loses the race is rejected, and an earlier put
is evicted. This is therefore not the same correctness defect as the mixed
batch.

It does, however, leave a released generation admissible throughout an
unbounded writer drain. Holders of an already-issued immutable DB can add
retained results during that interval, increasing peak memory and making
"reference count zero closes admission" false. The retained design specified
closing admission immediately after the final-reference transition.

Decision: either preserve that stronger, simpler lifecycle law by closing
before drain, or explicitly accept temporary admission during drain and add a
bounded-drain/peak-memory requirement. The former provides clearer ownership
and earlier reclamation without cancelling queries.

## Missing adversarial proofs

### Identity and isolation

- No proof that two branches in one physical store cannot share a bucket.
- No proof that equivalent commits in two physical stores cannot share a
  bucket.
- No reconnect proof that generation B cannot read or be evicted by a late
  close from generation A.
- No storage round-trip/hash proof that process cache context is excluded from
  durable identity and stored values.

### Mutation and committed publication

- The new cacheability test covers `with`, `as-of`, and `history`, but not
  `since`, filtered DBs, detached/restored values, `load-entities-with`, merge,
  or secondary-index installation.
- No writer test proves that a multi-report batch unions all known modified
  attributes.
- No test covers the mixed known/unknown batch blocker above.
- No proof establishes that callbacks for every report in a batch receive the
  same final committed identity while intermediate speculative values remain
  uncacheable.

### Open, release, and nonstreaming reads

- No injected failure after generation admission proves opening cleanup closes
  and evicts the generation before publishing failure to waiters.
- The late-put release test invokes the private put after release; it does not
  coordinate a query between lookup, computation, final-reference transition,
  and publication.
- No release-failure test proves the generation stays fenced and empty when
  writer, secondary-index, or store cleanup fails.
- No nonstreaming `deref-conn` test proves each store-head reload receives the
  exact current commit ID while retaining only the connection's generation.
- No nonstreaming test covers a head advance during dereference or final
  release.

### CLJS and LRU invariants

- The new committed-identity/query-cache tests are CLJ-only despite the owning
  namespaces being `.cljc`; there is no retained CLJS proof for record context,
  generation admission, cache puts, propagation, or nonstreaming dereference.
- The weighted removal test is one example. The existing independent
  property/reference model was not extended to prove that arbitrary removals
  preserve key/value, generation maps, weights, total weight, capacity, and
  subsequent eviction behavior.

## What the implementation gets right

- Reservation mints one generation and completed publication preserves it.
- `with`, `load-entities-with`, and writer completion explicitly clear
  speculative context.
- Connector attaches context only after durable restoration and opens cache
  admission before publishing the connection.
- Opening failure closes the exact generation; a stale close cannot remove a
  newer generation because close compares generations atomically.
- Cache admission and LRU mutation are one atomic state transition, so a late
  put cannot resurrect an already-closed generation.
- Parent-to-child propagation requires matching connection and generation and
  uses the durable child commit ID.
- Process context remains a dedicated DB field rather than metadata/config.

## Recommended graduation sequence

1. Fix mixed known/unknown writer-batch propagation.
2. Decide whether final-reference zero closes cache admission before or after
   writer drain; encode the chosen law in the roadmap and test it.
3. Add branch/store/reconnect and open/release-failure barriers.
4. Add nonstreaming and all mutation-path identity tests.
5. Run the same retained contract in CLJS, then extend the LRU property model.
