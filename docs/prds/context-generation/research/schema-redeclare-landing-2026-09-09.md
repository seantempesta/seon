---
type: research
status: active
tags: [research, schema, adoption, test]
---

# Schema re-declaration after development adoption — 2026-09-09

Read the issue and Juniper fixture end to end, the environment owner end to
end, schema population/admission, and development source reconciliation.
AGENTS.md has no section 10; its opening copies the turn PRD's §10, which
was also read in full.

## Dependency ledger and reproduction

- Clojure `defrecord`, `reference-code/clojure/src/clj/clojure/core_deftype.clj:398`:
  re-evaluation emits and imports a new JVM class and replaces the factories.
- Clojure `defonce`, `reference-code/clojure/src/clj/clojure/core.clj:5964`:
  an already bound Var retains its value during reload.
- SCI persistent contexts, `src/seon/sci/eval.clj:1673`: each agent retains
  its own environment reference across turns. `receive-base!` updates roots.
- Adoption, `src/seon/cluster.clj:1872`: reloads changed namespaces and
  advances the base environment; older agent environments still exist.
- Admission, `src/seon/schema/edn.clj:469`: resolves declarations against
  the projection in hand. Juniper's shared declaration source is
  `test/seon/context_blocks_fixture.clj:14`.

At HEAD `17d845b55`, initialized `tmp/schema-redeclare-root`, started
`schema-redeclare`, and loaded the dated Juniper fixture through MCP JVM
evaluation. Fresh seed succeeded (turn `395006879631`, 9,987 ms).
Changed only `advance-projection!`'s docstring, then ran:

```
bin/seon --root tmp/schema-redeclare-root init --dev schema-redeclare --changed src/seon/env.clj
```

Adoption converged to `6aa22199-1ad6-5ffa-8545-a03c263b0266`. The first
reseed failed in 383 ms with both exact errors:

```
Schema population refused :example/order-row (unresolved-reference).
seon.env/advance-projection! violated its contract (invalid-input): must hold one immutable replacement environment
```

All three attribute evaluations failed with the environment contract error;
the subsequent entity evaluation failed to resolve those attributes. The
base environment was valid after adoption, while its older carried record
failed `environment?`. Disarming and re-arming created a new valid agent
environment and reseeding succeeded. The root cause is JVM record-class
identity across reload, not declaration ordering. A separate scratch JVM
probe also falsified wrapping `defrecord` in `defonce`: the compiler still
emits a new class, while the skipped factories construct the old one.

The fix retains one immutable empty Environment record with `defonce` and
derives construction, type checking, and printing from that record. Reload
can replace behavior without changing the type of live environments.

## Verification

The path-limited fast loop passed: 1 test, 2 outer assertions, zero failures
or errors, 153.605 seconds. Its child JVM uses the canonical published
population, real cluster boot and contracts, the shared Juniper installer,
real source adoption, a second seed, and two new schema declarations. The
outer assertions require successful child exit and the success marker printed
only after the child checks persisted schema rows, evaluation outcomes, and
four seeded orders. A further assertion requires the record declaration's
JVM class to have changed, proving that reload actually occurred.

The scratch adoption with the fix converged to
`6aa223b6-20cd-5240-8fea-bb0aa4f35e37`; reseed returned turn `395006879631`
in 15,864 ms. Before installing the new `defonce` anchor into an already
running pre-fix JVM, the JVM REPL interned `seon.env/empty-environment` using
that JVM's current `map->Environment` factory. This one-time live installation
preserves its current class without restarting it. The regression starts
from the patched source and requires no such preparation.

No foreign source edits were present at entry. Later, concurrent edits
appeared in `src/seon/cluster/agent.clj`, `src/seon/cluster/registry.clj`,
`src/seon/render/walk.clj`, and a new cluster-status schema/owner/test. They
are preserved. Two scratch publications refused changing source digests;
the first plain fast iteration also failed inside `seon.fn/exact-source`
(`StringIndexOutOfBoundsException`, range 8905–9304 over 9220 Java character positions) while
building a manifest from changing files. The passing fast iteration used
HEAD plus only `src/seon/env.clj` and `test/seon/schema_redeclare_test.clj`.
It needed no worktree or foreign session operation.

Default's first pre-fix seed independently reproduced the same two errors.
The following seed passed schema declaration but its opening encountered
`seon.render/ambiguous`, candidates `seon.cluster.agent/render-identity-ai`
and `seon.cluster.status/render-ai`, at the concurrent cluster-status boundary.
The initial default publication attempts encountered changing source; the
successful complete proof is recorded below.

Both isolated gates passed, workers capped at three:

```
SEON_TEST_WORKERS=3 bin/test --paths src/seon/env.clj test/seon/schema_redeclare_test.clj -- seon.schema-redeclare-test
SEON_TEST_WORKERS=3 bin/test --paths src/seon/env.clj test/seon/schema_redeclare_test.clj --platform
```

The subject gate passed 1 test / 2 outer assertions, child duration 130,798 ms,
coordinator phase 149 seconds. Platform passed 84 tests / 505 assertions,
coordinator phase 67 seconds. Both reported zero failures and errors and
removed their successful isolated roots. No full-suite gate was run.

Untracked `build/`, `workers/`, and `config/virtual-turns.edn` are preserved.

## Default proof and cleanup

Implementation committed path-limited as `4552f52ff`. After that commit,
the fixed default fixture seeded successfully: turn `aa071259cfd8`, 7,774 ms.
Restored only the original `advance-projection!` docstring ("Advance" →
"Replace") as the required trivial source edit, then ran:

```
bin/seon init --dev default --changed src/seon/env.clj
```

It exited zero and converged to `6aa226e2-731d-55ef-9574-b1f1bf142fba`,
source digest `b4bc238fd468c9f6e3a0590375b0772b50ab3e79d3f19928ef69f423ef42a0ea`.
Reseed plus independent fact checks returned in 17,693 ms:

```clojure
{:probe/seed {:seon.turn/id "aa071259cfd8"}
 :probe/pid 23557
 :probe/adopted #uuid "6aa226e2-731d-55ef-9574-b1f1bf142fba"
 :probe/published #uuid "6aa226e2-731d-55ef-9574-b1f1bf142fba"
 :probe/orders 4
 :probe/schema-count 4
 :probe/environment-valid true}
```

The exact verification form is retained below; the dated fixture was already
loaded. It exercises the real agent graph through `install!`, then requires
both non-absent convergence and persisted declarations/orders:

```clojure
(let [seed (juniper-fixture-2026-09-06/install! "default")
      instance (get @seon.operator.runtime/running-instances "default")
      connection (:seon.boot/cluster-connection instance)
      database @connection
      state (get-in instance [:seon.sci.eval/ctx :seon.sci.eval/projection-state])
      adopted (:seon.source/commit-id
               (seon.db/pull database [:seon.source/commit-id]
                             [:seon.cluster/name "default"]))
      published (:seon.source/commit-id
                 (seon.cluster.source/current (:seon.store/store instance)))
      declarations
      (mapv #(seon.db/pull database [:seon.schema/key :seon.schema/form]
                          [:seon.schema/key %])
            [:example/order :example/amount :example/customer :example/order-row])
      orders (seon.db/q '[:find (count ?e) . :where [?e :example/order]] database)]
  (assert (and adopted (= adopted published)))
  (assert (= 4 orders))
  (assert (every? :seon.schema/form declarations))
  {:probe/seed seed
   :probe/pid (get-in instance [:seon.boot/advertisement :seon.boot/pid])
   :probe/adopted adopted :probe/published published
   :probe/orders orders :probe/schema-count (count declarations)
   :probe/environment-valid (seon.env/environment-state? state)})
```

Default stayed on PID `23557`, the PID observed at entry. It was never
stopped, reforked, or restarted. The only post-gate production-file edit was
the docstring restoration used in this live proof; executable behavior is
identical to the gated version. The scratch operator was downed, its free
store lock confirmed, and `tmp/schema-redeclare-root` removed without following
symlinks. Both successful gate roots cleaned themselves up. All owned shell
commands finished; unrelated working-tree edits remain preserved.
