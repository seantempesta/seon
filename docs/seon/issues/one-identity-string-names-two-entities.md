---
type: issue
status: open
severity: friction
tags: [issue, config, schema, wave/config-cluster-identity]
---

# Stop two identity attributes from naming one string

## Problem

An agent holds an ordinary string, never an entity id or an
attribute-specific lookup ref, so `seon.cluster.message/resolve-about`
resolves a `:my.message/about` against EVERY installed
`:db.unique/identity` attribute and refuses a tie
(`:seon.cluster.message/ambiguous-about`) rather than guessing.

That refusal is correct. What is not correct is that two entity families
may mint the SAME identity string, which turns the refusal from a rare
diagnosis into the ordinary outcome. The refusal is also silent to the
agent by design (`src/seon/cluster/message.clj:41-49`): it lands as a
durable error fact, the turn completes normally, and the message simply
never exists — so the whole class fails without anybody's assertion
seeing it.

One instance of the class was fixed on 2026-08-07 (see below). One
remains live, in a different owner.

## Evidence

Fixed instance — the frozen form and its receipt:

- `src/seon/cluster/run.clj` minted `(pr-str [run-id ordinal])` for BOTH
  `:seon.cluster.run.form/id` (the form freeze) and
  `:seon.cluster.eval/id` (the receipt), and both are declared
  `:db.unique/identity` (`resources/seon/schemas/seon.cluster.run.form.edn`,
  `resources/seon/schemas/seon.cluster.eval.edn`).
- Every problem identity an agent could be asked to repair therefore
  named two entities, so `my.message/decline` about a problem was refused
  for every problem that has ever existed, since 2026-07-29 (`c8d45d9ef`).
- Observed in `seon.gen.loop-test`: the durable error fact
  `[:seon.cluster.message/ambiguous-about "More than one identified fact
  is named \"[\\\"<run-id>\\\" 5]\" …]`, with beta's declination form
  evaluating cleanly and committing no message.
- Fixed by qualifying the internal identity:
  `seon.cluster.run/form-identity` mints
  `(pr-str [:seon.cluster.run.form/id run-id ordinal])`, while
  `seon.cluster.run/receipt-identity` keeps the bare pair because that
  string is the agent-facing problem name. `seon.cluster.work/problem-id`
  and `seon.problems` now call the one owner instead of repeating
  `pr-str`.

Remaining instance — a cluster's name:

- `:seon.cluster/name` (`resources/seon/schemas/seon.cluster.edn`) and
  `:seon.config/cluster` (`resources/seon/schemas/seon.config.edn`) are
  both `[:string {:min 1, :seon.db/identity true}]`, and both hold the
  cluster's name, on the cluster entity and on the config singleton.
- Reproduced by the class regression's own derivation over a real
  production drive:
  `{"generate-code-v0" #{:seon.config/cluster :seon.cluster/name}}`.
- Consequence: an agent that names its cluster as a message's `about`
  gets `:seon.cluster.message/ambiguous-about` and no message.
- Not fixed here: the config singleton's key is read through ~30 call
  sites including `src/seon/cluster.clj` (owned by a running refork lane),
  `src/seon/config.clj`, and `src/seon/sci/eval.clj`. The data-oriented
  answer is a ref to the cluster entity rather than a duplicated name
  string, which is a config-owner design decision, not a repair.

## Owner and acceptance

Owner: the config/cluster identity owner (`src/seon/config.clj` plus
`resources/seon/schemas/seon.config.edn`), coordinated with whoever holds
`src/seon/cluster.clj`.

Accept when this query returns `{}` over a fully driven cluster database,
i.e. no string is held as a `:db.unique/identity` value by two entities:

```clojure
(->> (db/q '[:find ?value ?entity ?attribute
             :where [?entity ?attribute ?value] [(string? ?value)]]
           db)
     (filter (fn [[_ _ attribute]]
               (= :db.unique/identity
                  (get-in db [:schema attribute :db/unique]))))
     (group-by first)
     (into {} (keep (fn [[value rows]]
                      (when (< 1 (count (into #{} (map second) rows)))
                        [value (into #{} (map #(nth % 2)) rows)])))))
```

The run-family half of that invariant is already a standing regression:
`seon.gen.loop-test/a-goal-is-a-message-and-the-attempt-routes-its-own-failures`,
"no identity this run minted names two entities", which runs the same
derivation and scopes it by query to one run's own minted strings — so a
new family colliding with a run identity fails automatically.

## Third instance, found 2026-08-08 by the wave audit — a source digest

The scoping that makes the run regression cheap is also what keeps it blind:
it only sees strings ONE RUN minted, so a collision between two families
that never involve a run identity is invisible to it. Running the
acceptance query above unscoped over the live `default` cluster found one:

```clojure
{:value "3e4395b400fb588cdb5d83d57f1c32afd40ab5e646c779019b739b88f3fb"
 :attributes [:seon.activation/source-digest :seon.source/digest]
 :entities [24028 24029]}
```

Unlike the two earlier instances this one is DECLARED, so it recurs in every
fresh cluster rather than being data rot:
`:seon.activation/source-digest` is
`[:and {:seon.db/identity true} :seon.source/digest]`
(`resources/seon/schemas/seon.activation.edn:1-2`) — an identity attribute
built by construction from another identity attribute's value. The activation
closure and the source it was derived from therefore always hold the same
digest string under two identity attributes.

A fourth family has the same declared shape and will collide the moment the
values coincide: `:seon.schema.shape/fingerprint`,
`[:and {:seon.db/identity true} :seon.source/digest]`
(`resources/seon/schemas/seon.schema.shape.edn:1-2`).

The same probe re-confirmed the cluster-name instance
(`"default"` under `:seon.cluster/name` and `:seon.config/cluster`) and
showed the run/form instance still present in that store's PRE-FIX data —
that JVM was started before `0f67e6003` (`seon.cluster.run/form-identity`
does not resolve in it), so those rows are history, not a regression.

Probe, read-only, 2026-08-08 (40 identity attributes, 10,773 identified
rows): derive every `:db.unique/identity` attribute by query, pull every
`[?e ?a ?v]` for each, group by value, keep the values held under more than
one attribute.

### Added acceptance criterion

The class regression must be UNSCOPED to be a class regression: one
recurring test derives the identity attributes by query and asserts no value
is held by two of them across the whole database, so a newly declared
derived identity attribute fails on the day it is declared. The run-scoped
assertion in `seon.gen.loop-test` stays as the fast in-drive check.
