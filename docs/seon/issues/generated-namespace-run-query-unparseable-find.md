---
type: issue
status: open
tags: [issue, agent, database]
severity: friction
---

# Generated-namespace run query has an unparseable :find clause

## Problem

`src/my/plan.cljs:411` (`generated-namespace-for-run-query`, present at
4f38818f) declares:

```clojure
[:find [?root-id ?step-id ?namespace] .
 :in $ % ?run-id
 ...]
```

`[?a ?b ?c]` is find-tuple; the trailing `.` belongs only to find-scalar.
Datahike's parser rejects the combination, so every caller records a
`:core` fault instead of a result.

## Evidence

Live default cluster fault datoms 3989 and 3995 (2026-07-20 17:51:50Z):
`publish-generated-program!: Cannot parse :find, expected: (find-rel |
find-coll | find-tuple | find-scalar) {:error :parser/find, :fragment
[[?root-id ?step-id ?namespace] .]}` — recorded during the
warnings-block lane's live run, after the "Budget generated cause scalar
queries correctly" commit.

## Expected owner

The generated-program lane owning `src/my/plan.cljs` (recent commits
4f38818f, f457232a, 119c47dd). Fix is dropping the `.` (single tuple
result) and adding the regression the existing scalar-query commit
pattern uses.

## Acceptance

- `generated-namespace-for-run-query` parses and returns the tuple for a
  live generated run; no new `:parser/find` fault datoms.
