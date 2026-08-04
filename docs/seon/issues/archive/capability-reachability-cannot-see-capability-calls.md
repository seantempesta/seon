---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, effect, program-graph, curation]
---

RESOLVED 2026-08-04 by `bcee99a74` (Fail closed on host Vars in form
capability walks). The shared Var classifier now recognizes both SCI and host
Vars, projects qualified symbols from the metadata contract used by
`sci.core/var->symbol`, and retains the call-site symbol when a Var lacks
classifying metadata. Both form walks therefore see host-bound capability
entries, while an unclassifiable Var enters the unproven set and fails closed.

The regression derives a host-bound entry from the database capability
inventory, evaluates a definition that calls it through a real cluster ctx,
and proves that the entry appears in both referenced and unproven sets and
that `capability-free-references?` returns false. Its general class check binds
an anonymous `clojure.lang.Var` and proves the call-site symbol remains
unproven. Before the fix those four assertions failed; after it, the owning
`seon.sci.session-image-test` plus `seon.cluster.loop-test` gate passed 31
tests / 147 assertions with zero failures or errors.

Live proof used scratch cluster `capvarwalk0804`. Before reload,
`my.fs/read` resolved as `clojure.lang.Var`, both sets were empty, and the
capability predicate returned true. After reloading the changed
`seon.sci.eval` Vars, both sets contained `my.fs/read` and the predicate
returned false. Committing a real definition whose body calls `my.fs/read`
then produced `:seon.code.def/unrestorable` with no source, proving the
session-image consumer now refuses replay.

# See capability calls in the form-Var walk instead of failing open

## Problem

The static capability-reachability derivation cannot see a direct call to a
capability leaf. `seon.sci.eval/resolved-form-vars` and
`seon.sci.eval/unproven-called-vars` keep a resolved symbol only when
`sci.impl.utils/var?` is true, but the cluster's SCI context binds the
agent-facing capability functions as host `clojure.lang.Var` objects. Those
symbols are therefore dropped from BOTH the referenced set and the unproven
set, and `seon.cluster.loop/capability-free-references?` answers `true` for a
form that calls the filesystem, shell, edit, or web door directly.

This fails OPEN at exactly the boundary the walk exists to guard. The
docstring at `src/seon/sci/eval.clj:382-385` states the intended fail-closed
contract ("a missing program row for one is not silently pure"); the predicate
never gets the chance to apply it, because the symbol is discarded before the
program-graph lookup.

The consumer today is the session image: `session-image-tx`
(`src/seon/cluster/loop.clj:386-463`) marks a definition
`:seon.code.def/unrestorable` when its form reaches a capability leaf, and can
therefore mark a capability-calling definition restorable. Session curation is
a second consumer that would inherit the same wrong answer.

## Evidence

Probed 2026-08-04 on scratch cluster `opuseffect0804` (HEAD of
`codex/runtime-reliability-refactor`), against the cluster's live SCI context
and branch database.

Resolution classes:

```clojure
(sci/binding [sci/ns (sci/create-ns 'my.agents.root)]
  (into {} (map (fn [s] [s {:class (.getName (class (sci/resolve ctx s)))
                            :sci-var? (sci.impl.utils/var? (sci/resolve ctx s))}]))
        '[my.fs/read my.message/send seon.db/transact! +]))
;; => {my.fs/read        {:class "clojure.lang.Var" :sci-var? false}
;;     my.message/send   {:class "clojure.lang.Var" :sci-var? false}
;;     seon.db/transact! {:class "sci.lang.Var"     :sci-var? true}
;;     +                 {:class "sci.lang.Var"     :sci-var? true}}
```

The resulting classification, calling the real private Vars with no evaluation
of the candidate form:

```clojure
;; (my.fs/read {:my.fs/path "deps.edn"})
{:referenced [] :unproven [] :capability-free? true}
;; (+ 1 2)
{:referenced [clojure.core/+] :unproven [] :capability-free? true}
;; (seon.db/transact! [{:seon.cluster.agent/id "z"}])
{:referenced [seon.db/transact!] :unproven [seon.db/transact!]
 :capability-free? true}
```

The third line shows the machinery is not uniformly blind: a `sci.lang.Var`
IS observed and IS reported unproven. Only the capability leaves, which resolve
to host Vars, vanish.

For calibration, the graph side is correct where it is reached:
`(seon.effect/capabilities db 'my.fs/read)` returns `#{"my.fs/read"}` and
`my.fs/read`'s row carries `:seon.fn/calls #{seon.effect/request!}` plus its
`:seon.effect/capability`.

Sites: `src/seon/sci/eval.clj:364-380` (`resolved-form-vars`, var? filter at
377), `src/seon/sci/eval.clj:382-400` (`unproven-called-vars`, var? filter at
396), `src/seon/cluster/loop.clj:343-369` (the consumer walk),
`src/seon/cluster/loop.clj:386-463` (the session-image consumer).

Full context and the adjacent gaps:
[Per-form effect visibility for session curation](../../prds/sci-execution-runtime/research/session-curation-effect-visibility-opus-2026-08-04.md).

## Owner

`src/seon/sci/eval.clj` — the two form-Var walks are the one choke point.
Repairing them repairs `capability-free-references?`,
`:seon.code.def/unrestorable`, and any future curation consumer at once, with
no second mechanism.

## Acceptance

- `resolved-form-vars` and `unproven-called-vars` map a resolved host
  `clojure.lang.Var` to its qualified symbol exactly as they map a
  `sci.lang.Var`.
- A regression asserts the class, not the instance: for a form calling any
  declared `:seon.effect/capability` owner, `capability-free-references?`
  returns `false`; for `(+ 1 2)` it returns `true`. Driving the assertion from
  the capability inventory query rather than a literal symbol list keeps a
  newly declared capability covered automatically.
- A live proof that a session definition whose body calls a capability leaf is
  recorded `:seon.code.def/unrestorable` rather than restorable.
