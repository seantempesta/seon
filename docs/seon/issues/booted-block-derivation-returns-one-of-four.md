---
type: issue
status: open
severity: blocker
tags: [issue, render, database]
---

# A booted cluster's block derivation returns one block where the facts have four

## Problem

Boot seeds root's four blocks, and a booted cluster then reports EITHER four
child blocks or one, varying across otherwise identical boots of a fresh root
directory. When it reports one, the root page serves with only its header
surface and no problems, agents, or messages — and gives no sign that three are
missing.

No fixture reproduces it. Every suite exercising this path builds a fresh
in-memory Datahike inside the test and consistently sees four; the divergence
appears only against a cluster booted through `seon.cluster/start!` on a file
store. That is the fixture-vs-live-boot class again, in a new place.

## Evidence

Fresh root directory each run, `clojure -M:dev`, Seon at `21c3bd50f` plus the
boot wiring.

One boot reports the facts as correct:

```clojure
(d/q '[:find (count ?b) . :where
       [?a :seon.cluster.agent/id "root"]
       [?a :seon.cluster.agent/blocks ?b]] db)
;; => 4
(d/q '[:find ?b ?n :where
       [?a :seon.cluster.agent/id "root"]
       [?a :seon.cluster.agent/blocks ?b]
       [?b :seon.block/name ?n]] db)
;; => #{[599 :agents] [600 :messages] [597 :header] [598 :problems]}
```

The next boot, same code, same command, fresh root:

```clojure
(d/q '[:find ?block :in $ ?agent-id :where
       [?agent :seon.cluster.agent/id ?agent-id]
       [?agent :seon.cluster.agent/blocks ?block]] db "root")
;; => #{[597]}
```

**Correction to a wrong first reading, recorded because it cost an hour and the
next person will make it too.** The divergence first looked like "the same query
returns four inline and one inside the function", and that framing is FALSE: it
came from comparing results across two different boots. Running both forms in
one process against one database value always agrees. Whatever is wrong happens
at or before boot's seeding transaction, not in the derivation.

Ruled out along the way, so nobody repeats it: the loaded var is not stale
(`io/resource` resolves to the single file in `src/`, no `block*.class` on the
classpath); it is not instrumented (no marker in the var's metadata, nothing in
`src/` calls `apply!` or `alter-var-root` on it); and it is not the pipeline (a
local copy of the exact body behaves identically to the var).

Also observed, and possibly the same underlying fault: against the booted store
the collection-find forms `:find [?block ...]` and
`:find [(pull ?block [*]) ...]` returned one where the relation form returned
four, and an earlier identical run of the collection-pull form returned four.
`blocks` now uses the relation form, which is more robust but did not fix this.

## Impact

The root page — the thing a person opens in a browser — renders one of its four
blocks and gives no indication that three are missing. A surface that silently
disappears is indistinguishable from a surface that legitimately rendered
nothing, which is precisely the absence-read-as-health class.

It also puts every live block-set derivation in doubt: the prompt's ai renders
come from the same function.

## Owner

Boot's seeding transaction — `seon.cluster/seed-root-agent!` and
`seon.render.block/install-tx` — against a file-store connection.

`install-tx` commits ONE map carrying a cardinality-many component ref with four
identity-less child maps:

```clojure
{:seon.cluster.agent/id "root"
 :seon.cluster.agent/blocks [{...} {...} {...} {...}]}
```

The parent resolves by upsert on a `:db.unique/identity` attribute while the
children have no identity and rely on generated tempids. That is the shape to
suspect first — and it was TRIED and RULED OUT: transacting the four children
as their own maps with explicit `:db/id` tempids and a reverse
`:seon.cluster.agent/_blocks` ref produced the same one-block result on three
consecutive boots. That change was reverted, so the committed code is the
simpler nested form.

The next thing to try, and the one that would settle it in a single run, is the
transaction REPORT at boot: count added datoms from the seeding transact and
compare against the four blocks' worth. That distinguishes "the transaction
committed one child" from "the transaction committed four and a later read sees
one", and those two have completely different owners.

## Acceptance

- A booted cluster's root page renders every seeded block, proven by a test
  that boots a real cluster rather than by a fixture.
- The cause is named — not worked around by, for example, re-querying until the
  count stops changing.
- One regression at the choke point: a live-boot test asserting that
  `blocks` agrees with a direct relation query over the same database value,
  because that equality is the invariant that broke.
- If the cause is Datahike's, the finding is recorded against the vendored
  source with the reproducing query.

## Notes

Found 2026-07-28 wiring root's seeded block set into boot (N4 package 2, final
slice). The boot wiring itself is sound: the tower stands, the page serves, CSS
and `/data` work, and `stop!` tears the view down. Probes:
`tmp/n4_boot_page.clj`, `tmp/n4_q_probe.clj`, `tmp/n4_boot_schema.clj`.
