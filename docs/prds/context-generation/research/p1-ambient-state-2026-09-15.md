---
type: research
status: active
tags: [research, schema, database, architecture]
---

# P1: verified residuals and the owner design gate

## Outcome

Design stop before production edits, as explicitly required by the assignment
and AGENTS.md §2.5. This is not a class closure or a failed test gate.

The class guarantee is: **a running operation receives its declaration
projection with its inputs; missing input refuses visibly and never triggers
a database or packaged-resource rebuild.**

The full assigned membership cannot acquire that guarantee at one schema
consumer: its producers include cluster boot/adoption, execution custody,
operator source acquisition, and lifecycle generators. Several are protected.
Removing only `fallback-read-projection` leaves a global acquisition lookup,
dynamic custody, bootstrap wrapper fallback, and mutable projection-state
selection. Completing those owners is hours of cross-owner work.

## Exactly three options

Estimates are engineering effort, excluding waiting for protected owners and
machine test slots; they are planning estimates, not measured durations.

1. **Require carried projections at running read/admission boundaries first
   (recommended), 1–2 days.** Retain explicit boot/publication construction.
   Change the existing fallback diagnostic into a counted missing-input
   refusal shared by the consumers; pass immutable projections from the
   existing environment at acquisition. Migrate the canonical fixture and
   actual callers before removing their fallback. Guarantee: these running
   paths never rebuild declarations when input is absent. Give up immediate
   closure of all P1 notes; custody, lifecycle and adoption remain separately
   named obligations. Requires a coordinated release of cluster, evaluation,
   and fixture producer seams, not permission to edit their current work.
2. **Complete projection generation and custody carriage together, 3–5 days.**
   Extend the existing environment acquisition/publication mechanism to hand
   immutable, basis-correct projections and explicit writing custody; remove
   the operator-table lookup and dynamic consumer fallbacks together. Include
   host instrumentation's declared boot-versus-running behavior in that
   contract. Guarantee: running schema consumers and write decisions select
   their world exclusively from admitted input values. Give up a bounded
   single-lane change; this spans protected cluster/evaluation/fixture owners
   and still does not establish atomic adoption for concurrent host calls.
3. **Close every assigned P1 member as one coordinated program, 10–20 days.**
   Include option 2 plus coherent adoption, immutable source-analysis input,
   isolated operator source authority, and lifecycle-scoped opaque generators.
   Guarantee: each listed operation uses its owned input generation throughout
   its lifetime, with a specific regression for each distinct observable.
   Give up immediate delivery and unrestricted concurrent live adoption while
   designing its completion boundary. The cost range matches the dated mining
   row; it is not a claim that all historical defects remain live.

## Authority and inherited state

Read AGENTS.md, docs/seon/issues/README.md, the named class record and every
explicitly assigned member note end to end, including the archived admission
and instrumentation records. Read the complete issue-class-mining report;
its P1 structural-kill column says the immutable environment/projection rides
the work or database value and APIs expose no dynamic/process fallback.
Skills used: data-oriented-clojure, repl, datahike.

Source basis: `22893b71383cec23c8763df7da841524259a1774`, branch
`steward-platform`. `bin/seon status`: default PID 69622 alive, prepl 55914,
no orphan Seon JVMs. MCP runtime status answered; all three observed plumbing
procs replied, and the existing error count was 10. This is connectivity and
bounded observation, not proof of every agent's health.

`bin/issues-index --class class/p1` exited 1 before printing membership:
eight unrelated notes lack schedule rows. The index is owner-managed and was
not edited. Exact-tag search supplied the dated open membership, including
the extra work-launcher and database-read notes. The requested class record
is already in `docs/seon/issues/archive/`; its resolved status covers its
three original demonstrated mechanisms, not the entire P1 tag population.

Inherited changes: resources/seon/schemas/seon.sci.admit.edn,
src/seon/cluster.clj, src/seon/render/value.clj, and the four named bisect
test files. During this audit src/seon/sci/admit.clj also acquired an unrelated
8-line diff. All were preserved. No other lane was contacted or operated.

## Dependency ledger and current owning seams

- Datahike gitlink `cdcb5792db8bd599487f099437265d18a31164a5`:
  reference-code/datahike/src/datahike/versioning.cljc:75 derives cache
  ownership from the supplied attached value. Seon's temporal schema origin
  traversal is src/seon/db.clj:901; read state comes from that origin at :950.
- reference-code/malli gitlink `3517a3cd9271b2083780ac7be1725493905bca2e`:
  the existing `m/-instrument` call receives explicit compile options in
  src/seon/instrument.clj:465–478 and :524–543. The wrapper cache rides the
  projection, but :550–575 retains a bootstrap wrapper for calls lacking a
  projection. :503–521 also accepts the dynamic projection carrier.
- reference-code/sci gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`:
  src/seon/sci/eval.clj:158–163 still owns a delayed shared generator context.
  Its protected acquisition owner and forwarded host Vars are separate from
  schema compilation.
- src/seon/db.clj:126–139 searches operator `running-instances` by connection
  identity. :141–155 carries a mutable state reference as database metadata;
  :955 dereferences it when the read asks for its projection. This is not yet
  an immutable projection snapshot. No stale-basis failure was reproduced.
- src/seon/db.clj:939–948 rebuilds on missing input and prints the existing
  `projection-fallback` warning. It has no explicit occurrence counter there.
  :252–279 still bases foreign-write custody on `*conn*`.
- src/seon/schema.clj:2474–2568 already performs dependency-based incremental
  declaration admission; `git log -S` attributes it to `fba6bc4c1`.
- src/seon/schema/edn.clj:410–435 no longer reads `!source-files`, but reads
  the packaged population again to find refusal provenance. This is a
  remaining sideways read, not the historical accumulating atom.

## Per-member verdicts at this boundary

These are dated verification verdicts, not new lifecycle statuses. No member
is falsely closed on source existence or a missing signal.

| Member note under docs/seon/issues/ | Verdict and evidence |
|---|---|
| archive/schema-environment-is-ambient-not-explicit.md | Keep its narrow resolved status. Its archived evidence names `16c6c7bc5`; current projection caches remain owned by projections. Dynamic carriers and the raw database fallback remain outside that closure. |
| schema-declaration-rebuilds-four-gigabytes-per-form.md | Original full-rebuild cause removed by `fba6bc4c1`; current pure candidate admission measured 907,104 bytes / 0.624375 ms. The exact two historical SCI registration probes and scaling regression were not rerun, so full acceptance closure is pending. |
| archive/value-admission-resolves-the-declaration-population-per-node.md | Keep narrow resolved status. HEAD admit-walk carries one projection, and open-node uses it; canonical declaration_population_test.clj clears runner carriers. Current supplied admissions measured below. Missing projection still uses an optional path, so this does not prove the new strict-input guarantee. |
| archive/instrumentation-compiles-under-one-clusters-projection.md | Keep narrow resolved status under its explicit shared-host-program ruling. Projection-local wrapper compilation exists; bootstrap fallback remains a deliberate separate contract decision. No cohost test rerun. |
| foreign-write-fence-reads-only-the-dynamic-var.md | Confirmed remaining shape at db.clj:252. Read-only live private guard with `*conn*` nil returned nil. No foreign transaction performed; this is not an ordinary SCI custody-loss demonstration. |
| schema-source-provenance-accumulates-in-a-global-atom.md | Historical atom removed (`656cea270`, git log -S); live ns-resolve confirms absence. Residual: refusal! reacquires packaged resource provenance rather than receiving the candidate population's map. Keep open for the full immutable-provenance acceptance. |
| opaque-contract-generators-share-live-process-objects.md | Remaining shared delayed SCI context and web server/mult are present at eval.clj:158–163 and web.clj:100–126. No server generator forced. Protected web owner; no lifecycle proof. |
| history-policy-refusal-test-is-load-flaky.md | root-store-holder still determines refusal at cluster.clj:767–805. The historical timing hypothesis is not confirmed by that presence. Required 20 loaded repetitions not run; protected cluster owner. |
| the-database-identity-face-cannot-satisfy-its-own-declared-input.md | Correct named input exists at render/value.clj:74. Direct live producer returned a database identity string, not refusal. Nested renderer regression remains unrun; protected renderer and its in-flight diff are not claimed as this lane's repair. |
| isolated-operator-init-requires-a-source-checkout.md | Read historical record completely; no isolated-root init performed before the design stop. Current behavior remains unverified. It cannot be closed from the projection-consumer seam. |
| source-analysis-can-slice-changing-files-with-stale-offsets.md | exact-source still uses unchecked offsets over separately captured text (fn.clj:125–143); stable-manifest checks changed snapshots after build (cluster.clj:1637–1665). Race not reproduced; fix must pass the same source bytes to analysis and extraction in the protected publication path. |
| a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md | agent.clj:641–644 still requires context-state; current boot constructs it at cluster.clj:2563. No old-handle incompatible-adoption drill. `5ffc491ae` does not establish this handle-compatibility gate. Keep open. |
| development-adoption-can-mix-host-and-sci-generations.md | `5ffc491ae` repairs authored wrapper freshness and retries once; its note explicitly retains concurrent-call atomicity. That bounded retry cannot close the broader generation guarantee. No concurrent adoption drill performed. |
| flow-work-launcher-graph-omits-its-root-io-executor.md | Extra current tagged member: HEAD flow.clj:602–640 still ends graph definition with only compute-exec. No placement proof or flow edit. |
| seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md | Confirmed live after the earlier carriage repair; exact measured raw/carried comparison below. This is the first narrow implementation target in option 1. |

## Measured numbers

Read-only MCP JVM session `p1-ambient-state`, default, timeout 20,000 ms.
Thread allocation uses `com.sun.management.ThreadMXBean` on the evaluating
thread immediately before/after each owner call; it excludes other threads
and MCP serialization. This is existing loaded behavior, not this lane's
hot-adoption proof. No production edits were made.

| Operation | Allocated bytes | Milliseconds |
|---|---:|---:|
| raw @connection pull [:seon.agent/id] for root | 4,506,504,040 | 982.175166 |
| same pull on seon.db/db connection, first | 583,712 | 0.407208 |
| same carried pull, repeat | 583,248 | 0.285417 |
| pure projection-with-schema candidate | 907,104 | 0.624375 |
| explicit-projection admission, 20 maps | 1,850,520 | 0.926666 |
| same admission, repeat | 1,850,088 | 0.728750 |
| explicit-projection admission, 100 maps | 8,680,280 | 3.171833 |

The schema population contained 2,561 forms. Raw database metadata had no
projection state; seon.db/db metadata did. Both reads returned root's id.
Exactly one warning was returned by the first probe:

```text
WARN seon.db/projection-fallback caller= seon.db/pull elapsed-ms= 981 Supply a database value carrying its projection state.
```

The candidate definition was `[:int {:min 0 :max 100}]` and was never
registered. Admission returned print-node and value keys in all three cases.
The direct identity producer returned:

```text
database :cluster-default at basis transaction 536871456 commit 6aa99da3-a457-5bce-8013-22d448397f75
```

Historical measurements in member notes are not newly reproduced before/after
results. There is no new implementation whose allocation improvement can be
claimed. Exact executed forms are retained as data in
[p1-ambient-state-probes-2026-09-15.edn](p1-ambient-state-probes-2026-09-15.edn).

## Gates and exact boundary

No test invocation: the assignment's design stop fired before implementation
or test edits. Neither the named-path gate nor --platform is claimed green.
Needed for option 1: one canonical armed regression proving missing carried
input causes one counted refusal and zero projection construction/resource
reads, plus the existing database/admission/instrumentation suites, then
serial named-path and platform gates. A current-only read test cannot prove
historical projection correctness; include a retained database value across
schema advancement at the producer seam.

No default lifecycle change, no source reload/adoption, no test JVM, background
shell, scratch root, or worktree was created. The index's missing schedule
rows are a recorded documentation boundary, not the reason for this stop.
