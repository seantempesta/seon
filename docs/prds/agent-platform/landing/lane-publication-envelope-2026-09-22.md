---
type: landing
status: partial landing; rows 2 and 7 and row 6 aggregates held
created: 2026-09-22
---

# Publication envelope — wave A file half

Hook publication is paused. No change in this lane is live on the repository's
`default`; no operation resets or adopts that cluster. Scratch roots have their
own JVMs and are not evidence of default adoption or browser paint.

## Scope and held seams

- **Row 2 held:** `build-manifest`, `database-manifest`, `manifest-data`,
  `artifact-by-path`, `manifest-function-symbols`, `replace-manifest-artifacts`
  and `published-index-rows` are in `src/seon/fn.clj`, expressly excluded from
  this assignment. That file has concurrent edits. Manifest retirement cannot
  leave those readers behind. An ownership clarification was requested; no
  additional permission is inferred from silence.
- **Row 6 aggregate retirement held:** `src/seon/test.clj` still finds its
  publication row by `:seon.source/digest` (884), pulls both aggregate members
  (890), and reads input digests (906, 1372, 2160). `test/runner.clj` reads the
  aggregate source digest (2379) and artifact input digests (3615).
  `test/fast.clj:31` reads the snapshot digest. Those files are excluded.
  Consequently the seal and both aggregate producers remain. The independent
  scalar-upsert path can leave with its callers and schema keys.
- **Row 7 held:** its complete input-reader move requires excluded
  `src/seon/test/cache.clj`; classification must also replace manifest analysis
  selection in held `fn.clj`. No parallel copy of these readers was created.
- **Row 8 independent:** retain Clojure reload, remove cyclic name-order
  fallback and blind retry, compute one closure across both database values,
  verify reloaded file digests before recording adoption, produce arming
  identities. The existing `instrument/apply!` still ignores the new member
  and uses broad arming. Bounded arming is NOT claimed.

## Dependency and work boundary

Clojure gitlink `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`,
`reference-code/clojure/src/clj/clojure/core.clj:6064–6087`, selects `load-one`
for `:reload`; it receives an already-selected namespace, compiles that file,
retains existing namespace/Var identities, and does not select dependents.
Seon's `load-development-definitions!` is the caller. A changed declaration
selects roots; stored `:seon.ns/requires` selects their reverse closure;
`reload-order` orders that set. Each reload pays that namespace's compilation.
The comparison source is clj-reload `61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa`,
`src/clj_reload/parse.clj:163–176`: its topological sorter refuses a cycle.
No library or second dependency graph was added.

## Initial evidence and foreign boundaries

`bin/seon status` and MCP `runtime_status` at the repository root both returned
unknown/degraded default readiness, missing source commit, branch, connection,
configuration, SCI context, work launcher, graph and web serving. MCP itself
was available. Live probes therefore used scratch roots.

The original measurement script failed before adoption on its first scratch
boot: `:seon.db/process` was absent from installed schema. The second snapshot
included `6ec971b07`'s repair and progressed to the deleted
`seon.search/supplied-handle` config reference. A third attempt at `4343e804a`
still found the remaining reference; `62487dbc3` removes it. None of those
failed starts supplies an adoption timing. Their exact logs are retained in
`tmp/publication-envelope-evidence/baseline-*-refusal.log`.

The shared-tree load subsequently failed in the other lane's untracked
`resources/seon/schemas/seon.sci.execution.edn`: `:seon.fn/affected` belonged in
`seon.fn.edn`. No foreign file was edited. The user-authorized snapshot
`tmp/publication-envelope-wt`, HEAD `62487dbc3` plus only this lane's paths,
loads `seon.cluster`, `seon.cluster.source`, and `seon.fn` successfully.

Focused invocation:
`bin/test-fast --paths src/seon/cluster.clj test/seon/cluster_test.clj -- seon.cluster.source-test seon.cluster-test seon.cluster.publication-delta-test`.
It acquired the projection and armed 1,687 contracts, then refused admission:
`Test recording requires a published current-src.` Zero tests executed; this
is not green. The installed fast runner uses `seon.test.source-root` for its
recording authority and provides no independent scratch recording-root member.
No cold gate or suite was launched.

## REPL forms

Before, on `tmp/publication-envelope-before-root`, cluster `head`, JVM,
read-only:

```clojure
(let [v (resolve 'seon.cluster/reload-order)]
  {:available? (some? v)
   :order (when v (v '#{sample.a sample.b}
                    '{sample.a #{sample.b} sample.b #{sample.a}}))})
; 1 ms, {:available? true :order [sample.a sample.b]}
```

After, on `tmp/publication-envelope-root`, cluster `default` (the SCRATCH
root's cluster), JVM, read-only:

```clojure
(try
  (seon.cluster/reload-order '#{sample.a sample.b}
                            '{sample.a #{sample.b} sample.b #{sample.a}})
  (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
; 1 ms, {:seon.boot/refused true
;        :seon.error/message "Development reload requires contain a cycle."
;        :seon.boot/offense {:seon.ns/requires #{sample.a sample.b}}}
```

These are host algorithm observations during scratch boot, not successful
publication, arming, SCI execution or browser observations.

## Measurements, scratch boot, commits

The measurement script was attempted before the reductions and the required
scratch reset exercised the combined reduced source. Both reached a published
`current-src`, then failed creating a cluster namespace without its required
`:seon.program/definition-digest`. The CLI additionally reported an unreadable
printed exception (`No dispatch macro for: '\'`). This is beyond the owned
publication/adoption span. Healthy scratch boot is NOT claimed.

| Requested clock | Before rows 6/8 | After rows 6/8 | Reloads / re-armed Vars |
|---|---|---|---|
| explicit no-change adoption | unavailable: boot refused | unavailable: boot refused | unknown / unknown |
| leaf docstring adoption | unavailable: boot refused | unavailable: boot refused | unknown / unknown |
| `seon.id` adoption | unavailable: boot refused | unavailable: boot refused | unknown / unknown |
| from-zero start/reset | baseline CLI 168,900 ms, failed | scratch process start to refusal 168,184 ms, failed | no successful adoption |
| raw `seon.id` closure compilation | 3,042.041917 ms | reload primitive retained | 90 namespaces; unarmed compile only |

These are failed end-to-end measurement rows, NOT target passes or evidence that
the envelope is gone. No-change and leaf/core acceptance remain outstanding.
Row 2/7 have no after measurement because their code has not changed.

The 90-namespace figure is now from the published stored graph, not the data
pack's static estimate. All 90 source namespaces were first loaded, then timed
individually through `require :reload`; no publication or contract arming is
inside that clock. Heap in use afterward: 762,877,088 bytes (not a peak/RSS
measurement). This is already above one second before envelope work. Exact
per-namespace rows and commit identity are in
[the clock artifact](publication-envelope-seon-id-compile-2026-09-22.edn).
The companion script supports `compile-offline` against the persisted scratch
publication when boot cannot acquire a cluster. Invocation from the baseline
snapshot:

```sh
clojure -Sdeps '{:paths ["src" "resources" "script"]}' -M \
  /Users/sean/src/seon/docs/prds/agent-platform/research/measure-publication-reloads-2026-09-22.clj \
  /Users/sean/src/seon/tmp/publication-envelope-baseline-root compile-offline
```

The original measurement script now uses its companion around successful
adoptions to count changed armed roots, including newly armed Vars, and writes
the closure compile table before stopping the scratch JVM. Those adoption
counter paths are authored but unexercised here because boot refused.

Scratch schema proof command (HEAD plus owned paths):
`bin/seon --root /Users/sean/src/seon/tmp/publication-envelope-root reset --force`.
Raw log: `tmp/publication-envelope-evidence/scratch-reset.log`.


## Accepted independent changes and proof limits

Row 6 deletes `seon.cluster.source/assert-scalar-rows!`, `populate-upserts!`,
`upsert!`, and `:seon.source/upsert-row`, `/upsert-rows`, `/upsert-request`.
The three tests protecting the removed scalar path and the obsolete predicate
rename/tombstone test leave with it. The surviving branch-isolation regression
now calls `publish!` directly through its fixture population function.
Source −54 lines; schema −17 net; tests −294 net. The seal,
`publication-input-digest!`, `test-input-digest`, and aggregate source digest
remain because their live B4 consumers remain. This is a partial row 6, not
aggregate retirement.

The required from-zero scratch attempt **did** publish the canonical schema and
program before the foreign boot refusal. A later read of that exact scratch
publication returned `[]` for schema keys `/upsert-row`, `/upsert-rows`, and
`/upsert-request`. This proves their resource removal has no missing-reader
failure at publication; it does not prove healthy cluster boot.

Row 8 deletes `source-change-phase` and `retrying-source-change`; the obsolete
retry test leaves. It retains `require :reload`, commit-id equality on every
request, and broad arming. The producer supplies only function identities from
reloaded interns and schema referrers, excluding retired identities. The digest
check reads the hosting checkout's files, not a candidate directory that
`require` did not load. `development-namespaces` keeps its public two-argument
arity and adds a before/after arity; adoption calls it once.

A diagnostic armed probe on the persisted canonical scratch publication
registered/instrumented 1,518 Vars. Direct-referrer selection included
`my.web/fetch` for `:my.web/fetch-request`; the transitive selection for
`:seon.source/commit-id` included `development-source-refresh!` through
`:seon.source/published` (28 function identities). A reflexive rule with repeated
head variables initially selected only direct referrers; binding distinct rule
columns repaired that probe. This is measured behavior, not a new dependency
mechanism or an inferred recursive-query guarantee.

The same armed probe accepted matching digests and refused a mismatching source
root with `:seon.cluster.source/phase :adoption`, naming
`src/seon/cluster.clj`. A three-namespace program analyzed by `seon.fn/rows` and
written through the canonical writer on a temporary branch proved the combined
graph: after retracting caller→leaf, after-only selected `#{leaf}`, whereas
before+after selected `#{leaf caller outer}`. The connection was released and
the branch unlinked in `finally`. Exact forms/results:
`tmp/publication-envelope-evidence/probe.clj` and `probe-accepted.log`.
A forced failure through a fully booted development adoption remains unproved.

Publication-only probes (no development cluster, explicitly **not adoption**)
republished the changed owned file in 10,486.75525 ms and returned an unchanged
commit in a fresh host at 872.032291 ms. No leaf/sub-second claim follows.
The first armed probe correctly rejected a set against an older published
vector contract; publication of the changed file updated the program contract
before the successful probe. A hot source edit alone was not treated as adoption.

## Focused recorded tests

The isolated checkout used the existing published fixture cache read-only and
its own scratch store as the recording authority (local symlinks, no change to
the runner and no repository-default write). The same `bin/test-fast --paths`
request then **recorded 22 executed, 0 reused, 41 assertions, 0 failures,
17 errors**, run `d086058add44`. The armed cycle regression passed. Every setup
error named the stale fixture base's deleted `seon.search/ping-map-fn?`; the
base was 49 commits behind the tested snapshot. This is wanted fixture
acquisition behavior at B4's held boundary, not a reason to restore search or
change the new regression's expectation. Raw log:
`tmp/publication-envelope-evidence/focused-test.log`.
The additional transitive-schema regression is authored; its recorded-fixture
execution is pending that repair, with its producer exercised by the armed
scratch probe above. No suite or cold gate was run.
