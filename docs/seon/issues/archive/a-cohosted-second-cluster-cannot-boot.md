---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, boot, schema, concurrency]
---

# A second cluster in one JVM cannot boot: its stored activation closure never satisfied its own contract

## Resolution (2026-08-07, repair lane)

**Fixed at cause in `src/seon/cluster.clj`
(`activation-closure-set-attributes` / `pulled-closure` / `stored-activation`,
`:968-1020`), with the class regression at
`test/seon/cluster/cohost_boot_test.clj`.**

**The filed mechanism hypothesis is REFUTED with evidence.** This was not one
cluster's projection compiling a validator that another cluster read. The
stored activation closure has ALWAYS violated `:seon.activation/closure`, in
every cluster, in every JVM. The co-hosted second boot is simply the only boot
in the system's life that runs with its own contracts checked.

### What the probes showed

Reproduced on isolated root `tmp/cohost-operator` exactly as filed. Then, on
cluster **A** — the first and only cluster, its OWN database, its OWN
projection state, `require-activation!` not instrumented:

```clojure
(m/explain :seon.activation/closure
           (:seon.activation/closure (#'seon.cluster/stored-activation db)))
;; #:seon.activation{:schema-keys         ["missing required key"]
;;                   :required-attributes ["missing required key"]
;;                   :config-defaults     ["invalid type"]
;;                   :config-required     ["invalid type"]
;;                   :executable-symbols  ["invalid type"]}
```

Five problems, on exactly the five keys the shape declares as `[:set …]`
(`resources/seon/schemas/seon.activation.edn:3-11,24-40`). A raw pull of the
closure entity confirms the two halves of the cause:

- **type** — Datahike projects a cardinality-many attribute as a
  `PersistentVector`; the shape declares a set. `derive-activation`
  (`src/seon/cluster.clj:936-958`) builds the closure from real sets, so the
  WRITE side is honest and only the READ boundary lost the declared type;
- **presence** — `:seon.activation/schema-keys` and
  `:seon.activation/required-attributes` hold no datoms at all, so pull omits
  the keys entirely and the required-key check fails.

Nothing here varies by cluster, by projection, or by thread.

### Why only the second cluster refuses

Instrumentation ordering in the operator, one line apart in effect:

- `launch-form` — a NEW JVM — applies instrumentation AFTER `start!` returns
  (`script/seon/fresh_operator.clj:1389`), so the boot that just ran was
  unchecked;
- `add-form` — a cluster added to a RUNNING JVM — runs
  `refresh-instrument-form` BEFORE `start!`
  (`script/seon/fresh_operator.clj:1430`), so `require-activation!` is
  wrapped while the second cluster boots through it.

Cluster B was therefore the first caller ever to have this contract enforced.
The "different projection generation" reading of the `:config-defaults`
`"invalid type"` was wrong: it is a plain vector-where-a-set-is-declared, and
cluster A produces the identical explain.

### The fix

`stored-activation` restores the declared value at the read boundary. The five
set-valued attributes are named ONCE
(`activation-closure-set-attributes`), and both the pull pattern and the
restoration are built from that one declaration so they cannot drift.
`(set nil)` is the faithful reading of an absent cardinality-many attribute —
absent IS the empty set in Datahike — not a substituted default.

### Evidence

- Refutation probe above, run through `eval_clj` on the live co-hosted JVM.
- After the fix, the same probe returns `nil` from `m/explain` and all five
  attributes are `PersistentHashSet`.
- End-to-end: the filed reproduction re-run on a clean `tmp/cohost-operator`
  now carries cluster B past `require-activation!`; both clusters record
  `:seon.boot/ready-ms` (a 5809 ms, b 63600 ms) in ONE JVM with
  instrumentation live, and `require-activation!` returns each cluster's own
  source digest.
- Class regression `test/seon/cluster/cohost-boot-test` — 16 assertions,
  green, repeated. Proven non-vacuous: with `pulled-closure` reduced to
  `identity` the regression errors at cluster B's `start!` with the filed
  contract violation.

## Still open, discovered while repairing (each needs its own owner)

1. **The activation closure records ZERO schema keys and ZERO required
   attributes.** `activation-requirements` (`src/seon/cluster.clj:759-793`)
   derives both from the projection catalog's `:seon.schema/entity?` shapes,
   and both come out empty at publication time. `require-activation!`'s
   `closure-fact-missing` (`:1002-1041`) then computes
   `(set/difference #{} database-schemas)` — empty — so **two of the five
   activation fact categories are verified vacuously at every boot**. The
   contract fix makes the emptiness legal but not correct.
   Filed: [activation-closure-records-no-schema-keys](activation-closure-records-no-schema-keys.md).
2. **The operator instruments the whole process under the FIRST cluster's
   projection state.** `refresh-instrument-form`
   (`script/seon/fresh_operator.clj:1298-1316`) picks `anchor` = the first
   running instance with a cluster connection and calls
   `seon.instrument/apply!` inside that cluster's
   `call-with-projection-state`. Malli instrumentation alters Var roots
   process-wide, so N co-hosted clusters share ONE set of wrappers compiled
   against ONE cluster's projection. It did not cause this failure (the
   projections agree today, both forked from one published commit) but it is
   a genuine Defect II instance at the boot boundary and belongs to the
   seon.env PRD's Phase 3 "move the compiled caches onto the projection".
   Filed: [instrumentation-compiles-under-one-clusters-projection](instrumentation-compiles-under-one-clusters-projection.md).
3. **A co-hosted second boot takes ~11× the first (63.6 s vs 5.8 s) and the
   operator's 30 s silence backstop abandons the wrapper mid-boot** — the
   cluster comes up healthy and reachable, but `bin/seon start` reports
   failure and prints no URL. A clock firing on a live, progressing boot is
   the "tuned constant standing in for an observable event" smell, and the
   underlying slowness taxes the four-worker target directly.
   Filed: [cohosted-second-boot-is-slow-and-trips-the-silence-backstop](cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md).

## Acceptance criteria

- [x] Two clusters start into one operator JVM, both reaching a completed boot
      sequence, each holding its own projection state.
- [x] A test proves it as a class: `test/seon/cluster/cohost_boot_test.clj`
      applies instrumentation under cluster A's projection state and then
      boots cluster B, asserting B's own contracts, B's own projection state,
      and ordinary evaluation in each cluster's own sci ctx.
- [ ] The failure face is readable. Still true and still ugly: this refusal
      prints one unbroken ~9,000-character line repeating the same message
      four times around a full stack trace and the whole boot instance. A boot
      refusal needs a declared `:seon.render/ai` producer. Carried forward to
      [boot-refusal-has-no-render-producer](boot-refusal-has-no-render-producer.md).

## Original report

Starting a second cluster into an ALREADY-RUNNING operator JVM fails at
`seon.cluster/require-activation!` with a Malli contract violation. The
identical cluster, from the identical published commit, boots cleanly through
every layer when it gets its own operator root — that is, its own JVM.

Observed 2026-08-07 by seon.env Phase 1 lane W1, at commit `ee00c6dd3`, on
isolated operator roots (never the shared default cluster).

```
bin/seon --root tmp/w1-operator start w1     # -> web, healthy
bin/seon --root tmp/w1-operator init w1b     # publishes cleanly
bin/seon --root tmp/w1-operator start w1b
● w1b boot: repl / store / branch
✗ seon.cluster/require-activation! violated its contract (invalid-output):
  #:seon.activation{:schema-keys       [{:value nil, :message "missing required key"}]
                    :required-attributes [{:value nil, :message "missing required key"}]
                    :config-defaults   [{:value [:seon.config.ai/extra-body-edn …],
                                         :message "invalid type"}]
                    :config-required   [{…}]}
```
