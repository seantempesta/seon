---
title: "Card CO-4: Reach acquisition decodes nothing"
type: reference
status: active
created: 2026-10-08
---

# CO-4 Reach acquisition decodes nothing

Pattern P2. Lane: Sonnet, after lane datoms-decode (W-24) lands.

## What exists
`seon.test.runner/reach-attribute` reads each reach attribute through `seon.db/datoms`, which decodes every datom
(W-24: ~35× `d/datoms`). In check windows `seon.db/datoms->data` is 0.737 GB of the 0.77 GB reach bucket
(check-alloc-astra, depth-256 JFR). Reach attributes are natively typed (symbols, refs, digest strings).

## Change
First re-measure after W-24: if `seon.db/datoms` no longer decodes natively typed attributes, this card only adds
the proof. Otherwise `reach-attribute` reads through the `seon.db` raw-index function W-24 provides for natively
typed attributes (direct `datahike.api` stays in `seon.db`).

## Proof
`seon.test-reaching-test/reach-acquisition-decodes-no-datom` (new): a cold `reach-digests` on a fixture closure;
`(calls-of m 'seon.db/datoms->data)` is 0 and the digest equals the parent's.

## Cost
Expected: index iteration plus map building, ∝ attribute datoms (Datahike's own `d/datoms` cost). Parent 0.737 GB
decode per cold acquisition.

## Commit
`bin/lane commit <lane> -m "CO-4: reach acquisition decodes nothing" --test seon.test-reaching-test src/seon/test/runner.clj test/seon/test_reaching_test.clj`

## Dependencies
CO-0, W-24.
