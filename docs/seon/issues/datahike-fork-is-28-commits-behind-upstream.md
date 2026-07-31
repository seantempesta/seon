---
type: issue
status: open
severity: friction
tags: [issue, dependency, datahike, database]
---

# Merge the 28 upstream Datahike commits our fork is missing

## Problem

Our maintained Datahike fork is 95 commits ahead of and **28 commits behind**
`replikativ/datahike` `main`. The missing 28 are overwhelmingly query-engine
correctness fixes in families where we have no equivalent repair, so Seon is
running a query engine with known-fixed defects still live in it.

## Evidence

`reference-code/datahike` HEAD `9b3be9d5`; merge base with `upstream/main` is
`85c40aee8`; `git rev-list --count HEAD..upstream/main` = 28.

Correctness fixes we lack:

- `cf8b75df` (#912) variable occurrences are equality constraints, not just
  projection sources, and `60f2f0a7` (#913) the rest of that family — nested
  scopes, rule bodies, repeated attributes, history replay;
- `3342c643` (#883) scope leaks, fail-loud parity, schema state validation,
  search cache;
- recursive-rule fixes `5f859c00` (#915), `6d5f602d` (#918), `b5ef35e2` (#899);
- anti-join and planner fixes `e4e26c68` (#905), `d95785fa` (#904),
  `c4d19929` (#903), `61f436d8` (#887);
- `437d6401` (#923) `get-else` left-outer semantics.

Features on surfaces we use: `11426b97` (#881) `:db.type/store-ref` — blobs
and out-of-line values tracked by GC, which is the transport law's
bulky-payloads-as-blobs seam; `fabf4b41` (#862) secondary indices owning
their external-engine query-spec (the Proximum seam); `ac70ef3a` (#861)
attribute-value constraints and a value-size resource model.

The existing `sync-upstream` branch is stale — one commit, `eb3e2239`, a
test-coverage addition.

## Owner

`reference-code/datahike` as a maintained fork, and whoever owns the next
database wave. This is a real merge across a 95/28 divergence, not a
fast-forward; it is not safe to automate.

## Acceptance

- `git rev-list --count HEAD..upstream/main` is 0, or every commit not taken
  is named with the reason it was rejected.
- Datahike's own suite passes at the merged revision.
- `bin/test` green, plus one live proof on a freshly forked cluster — the
  query engine changed, so a fixture-only proof is not sufficient.
- The merged revision is published on a branch of our own remote and the
  gitlink records it.
