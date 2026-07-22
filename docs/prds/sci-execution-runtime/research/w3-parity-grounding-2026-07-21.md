---
type: research
status: active
tags: [research, architecture, agent]
---

# W3 host-parity grounding — sol read-only pass (2026-07-21 overnight)

Orchestrator-accepted, incl. the four premise corrections (W3a mostly
landed; m/-instrument for multi-arity; settle! is not the run fence —
W3c splits into W3c1 fence / W3c2 repair-preflight; authored invocation
never used the U2 registry) and the execution order W3a → W3c1 → W3b →
W3c2 → W3d with host/eval.clj hooks serialized. SCI checkout is
8fac6e88 (anchor note corrected). WP-D stays adjacent-open.

Read-only audit complete. No files changed and no live cluster writes or lifecycle operations were performed.

## Executive verdict

Your four-unit cut matches all six items in the anchor’s explicit W3 punch list: W3a covers typed interrupts and output; W3b instrumentation; W3c run fencing plus repair/preflight; W3d authored invocation ([program-synthesis:342](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:342)).

Four corrections matter:

1. **W3a typed classification is already landed.** Current host interrupt, resolution, refusal, arity, and Malli classification is structural, and the program ledger records all three host message regexes removed ([program-synthesis:554](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:554)). W3a is now verification plus output-policy convergence and one late-interrupt metadata fix.
2. **The accepted W3b wrapper sketch is wrong for multi-arity Malli `:function` schemas.** Their top-level `-function-info` is nil; Malli’s own instrumentation performs per-arity dispatch ([malli/core.cljc:2276](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2276)). Use `m/-instrument`, not one manual `m/-function-info` call ([malli/core.cljc:3110](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:3110)).
3. **`invoke/settle!` is not the run fence.** It is a process-local CAS for terminal-frame ownership ([host/invoke.clj:34](/Users/sean/src/seon/src/seon/host/invoke.clj:34)). The host accepts the database run-fence field but never consumes it ([host/session.clj:54](/Users/sean/src/seon/src/seon/host/session.clj:54)).
4. **W3c should have two internal units:** W3c1 run-fence safety and W3c2 repair/preflight. They share `host/eval.clj` but have no semantic dependency. W3c1—not W3c2—is a prerequisite for W3d.

The active SCI checkout is `8fac6e88…`, not the design document’s former `be4021d`; the host uses a local-root dependency ([deps.edn:53](/Users/sean/src/seon/deps.edn:53)), the checked-out `seon` ref records `8fac6e88…` ([SCI ref:1](/Users/sean/src/seon/.git/modules/reference-code/sci/refs/heads/seon:1)), and the structured resolution patch is present ([resolve.cljc:323](/Users/sean/src/seon/reference-code/sci/src/sci/impl/resolve.cljc:323)).

---

## W3a — typed interrupts and output capture

### Interface ledger

| Mechanism | Owner | W3a use |
|---|---|---|
| Deadline producer | [host/context.clj:1345](/Users/sean/src/seon/src/seon/host/context.clj:1345) | `build-base!` installs interrupt-aware core/string and throws `interrupt!` with `:seon.error/kind :timeout` ([host/context.clj:1358](/Users/sean/src/seon/src/seon/host/context.clj:1358)). |
| SCI marker | [interrupt.cljc:32](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:32) | `interrupt!` attaches SCI’s private marker ([interrupt.cljc:39](/Users/sean/src/seon/reference-code/sci/src/sci/interrupt.cljc:39)); SCI tests it by identity ([utils.cljc:51](/Users/sean/src/seon/reference-code/sci/src/sci/impl/utils.cljc:51)). |
| Host classifier | [error/sci.clj:125](/Users/sean/src/seon/src/seon/error/sci.clj:125) | Walks the cause chain, uses `identical?`, removes only the marker, and emits `:seon.error.sci/class :interrupt` ([error/sci.clj:197](/Users/sean/src/seon/src/seon/error/sci.clj:197)). |
| Classification call sites | [host/eval.clj:34](/Users/sean/src/seon/src/seon/host/eval.clj:34) | Per-form catch classifies at [host/eval.clj:135](/Users/sean/src/seon/src/seon/host/eval.clj:135); outer invocation catch uses the same path at [host/invoke.clj:115](/Users/sean/src/seon/src/seon/host/invoke.clj:115). |
| Cancel distinction | [host/invoke.clj:101](/Users/sean/src/seon/src/seon/host/invoke.clj:101) | A typed interrupted batch becomes cancellation only when `::cancel-requested?` is also true. |
| Host output capture | [host/eval.clj:63](/Users/sean/src/seon/src/seon/host/eval.clj:63) | Streaming bounded writer; both `sci/out` and `sci/err` bind to it ([host/eval.clj:121](/Users/sean/src/seon/src/seon/host/eval.clj:121)). |
| Persistence seam | [host/eval.clj:262](/Users/sean/src/seon/src/seon/host/eval.clj:262) | Internal `::output` is removed from the returned form envelope and passed into terminal recording; nonblank output becomes `:seon.eval/output` ([host/record.clj:345](/Users/sean/src/seon/src/seon/host/record.clj:345)). |
| Child output owner | [eval.cljs:348](/Users/sean/src/seon/src/seon/eval.cljs:348) | AsyncLocalStorage isolates a per-eval bucket across eval and Promise auto-await ([eval.cljs:4371](/Users/sean/src/seon/src/seon/eval.cljs:4371)). |

### Exact parity measurement

| Property | Child | JVM host |
|---|---|---|
| Streams | Both global print functions route to one bucket ([eval.cljs:398](/Users/sean/src/seon/src/seon/eval.cljs:398)). | Both `sci/out` and `sci/err` route to one Writer ([host/eval.clj:126](/Users/sean/src/seon/src/seon/host/eval.clj:126)). |
| Attribution | Per fiber and per form across awaits ([eval.cljs:370](/Users/sean/src/seon/src/seon/eval.cljs:370)). | Per synchronous form evaluation ([host/eval.clj:121](/Users/sean/src/seon/src/seon/host/eval.clj:121)). Cross-thread propagation is **NOT GROUNDED**. |
| Capture-time bound | Unbounded string accumulation via `swap! … str` ([eval.cljs:389](/Users/sean/src/seon/src/seon/eval.cljs:389)). | Streaming cap of 2,048 estimated tokens plus a truncation marker ([host/eval.clj:17](/Users/sean/src/seon/src/seon/host/eval.clj:17)). |
| Stored shape | Optional `:seon.eval/output`; absent when nothing printed ([eval.cljs:97](/Users/sean/src/seon/src/seon/eval.cljs:97)). | Same optional attribute ([host/record.clj:389](/Users/sean/src/seon/src/seon/host/record.clj:389)). |
| Stored cap | Configurable `database-edn-cap`, default 16,384 characters ([config.cljs:1153](/Users/sean/src/seon/src/seon/config.cljs:1153)). | Hard-coded 2,048-token cap, applied at capture and persistence ([host/eval.clj:17](/Users/sean/src/seon/src/seon/host/eval.clj:17), [host/record.clj:336](/Users/sean/src/seon/src/seon/host/record.clj:336)). |
| Invocation envelope | Batch returns ids/counts, not output ([eval.cljs:5380](/Users/sean/src/seon/src/seon/eval.cljs:5380)). | `:seon.host/results` contains form envelopes but `::output` is stripped first ([host/eval.clj:262](/Users/sean/src/seon/src/seon/host/eval.clj:262)). |

Functional shape parity is present. Policy parity is not: caps, units, configuration, and truncation markers differ. Existing host tests already prove per-form attribution, truncation, and no JVM-stdout leakage ([host_conformance_writer_test.clj:490](/Users/sean/src/seon/test/seon/host_conformance_writer_test.clj:490)).

### Regex survivors

No message-regex classification remains in the JVM host classification scope; current classification branches are structural ([error/sci.clj:197](/Users/sean/src/seon/src/seon/error/sci.clj:197)).

Outside that scope:

- The quarantined diffusion worker still accepts a timeout/termination message regex as a fallback ([worker_eval.cljs:187](/Users/sean/src/seon/src/seon/worker_eval.cljs:187), [worker_eval.cljs:203](/Users/sean/src/seon/src/seon/worker_eval.cljs:203)).
- Child read-error enrichment parses line/column and EOF from reader prose ([eval.cljs:2261](/Users/sean/src/seon/src/seon/eval.cljs:2261)). That is not interrupt classification.
- Embedding retry classification still uses message/status matching, outside W3 execution ([embed.clj:663](/Users/sean/src/seon/src/seon/embed.clj:663)).

### Honest gap

`finish-evaluation!`’s late-interrupt fallback synthesizes `:interrupt` without `:seon.error/kind :timeout` ([host/eval.clj:95](/Users/sean/src/seon/src/seon/host/eval.clj:95)); the ordinary marker path preserves timeout metadata ([error/sci.clj:125](/Users/sean/src/seon/src/seon/error/sci.clj:125)). Output work should preserve streaming truncation while moving the bound to the common configuration authority.

### Ranked risks and cheapest falsifiers

1. **Critical: regress print-flood containment.** Cheapest falsifier: retain the existing flood test and require a sibling session to succeed immediately afterward ([host_hostile_battery_writer_test.clj:384](/Users/sean/src/seon/test/seon/host_hostile_battery_writer_test.clj:384)).
2. **High: late interrupt loses timeout identity.** Extend the forced post-return interrupt test to assert both `:interrupt` and `:timeout` ([host_conformance_writer_test.clj:518](/Users/sean/src/seon/test/seon/host_conformance_writer_test.clj:518)).
3. **Medium: concurrent-session output bleed is unproven.** Run two sessions with distinct sentinels and query both eval rows. Equivalent concurrent JVM proof is **NOT GROUNDED**; current proof is sequential ([host_conformance_writer_test.clj:490](/Users/sean/src/seon/test/seon/host_conformance_writer_test.clj:490)).

---

## W3b — instrumentation over SCI vars

### Interface ledger

| Mechanism | Owner | W3b use |
|---|---|---|
| Canonical projection | [host/context.clj:1491](/Users/sean/src/seon/src/seon/host/context.clj:1491) | Host already queries committed schema/function contracts and builds `schema/projection-from-rows` ([host/context.clj:1505](/Users/sean/src/seon/src/seon/host/context.clj:1505)). No second query or registry is needed. |
| Contract recording | [host/record.clj:122](/Users/sean/src/seon/src/seon/host/record.clj:122) | Valid `:malli/schema` becomes `:seon.fn/spec`; parse failure records `:seon.fn/schema-error` and omits the contract ([host/record.clj:140](/Users/sean/src/seon/src/seon/host/record.clj:140)). |
| Pod semantics | [instrument.cljc:295](/Users/sean/src/seon/src/seon/instrument.cljc:295) | Preserve input/output/guard/arity semantics and exact projection reconciliation. The implementation itself is CLJS-only ([instrument.cljc:27](/Users/sean/src/seon/src/seon/instrument.cljc:27)). |
| Correct Malli seam | [malli/core.cljc:2203](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2203) | Wrap roots with `m/-instrument`, supplying the contract, projection registry, and decorated reporter. This supports both `:=>` and multi-arity `:function` ([malli/core.cljc:2280](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2280)). |
| SCI var | [sci/lang.cljc:71](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:71) | A distinct mutable root/meta/watch object implementing `IDeref`, `IRef`, and `IFn`, not a native Clojure Var ([sci/lang.cljc:194](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:194), [sci/lang.cljc:213](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:213)). |
| Root replacement | [sci/core.cljc:249](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:249) | `sci/alter-var-root` is the public privileged installation seam, including stamped built-ins. |
| Redefinition watch | [sci/lang.cljc:97](/Users/sean/src/seon/reference-code/sci/src/sci/lang.cljc:97) | `bindRoot` synchronously notifies watches; a guarded watch can rewrap after `defn`, registry upgrade, or graduation. |
| Registry vars | [host/context.clj:848](/Users/sean/src/seon/src/seon/host/context.clj:848) | Registration upgrades cached shared SCI vars with `alter-var-root` ([host/context.clj:860](/Users/sean/src/seon/src/seon/host/context.clj:860)); already-linked contexts see the same var. |
| Error envelope | [error/instrument.cljc:197](/Users/sean/src/seon/src/seon/error/instrument.cljc:197) | `report-fn` works on JVM and throws the existing envelope ([error/instrument.cljc:271](/Users/sean/src/seon/src/seon/error/instrument.cljc:271)); WP-A classifies it structurally ([error/sci.clj:143](/Users/sean/src/seon/src/seon/error/sci.clj:143)). |
| Cold hook | [host.clj:210](/Users/sean/src/seon/src/seon/host.clj:210) | Apply after `graduate/rebuild!`, because projection acquisition precedes creation of corpus registry vars ([host.clj:223](/Users/sean/src/seon/src/seon/host.clj:223)). |
| Hot hook | [host/eval.clj:284](/Users/sean/src/seon/src/seon/host/eval.clj:284) | Reconcile synchronously after successful committed-projection refresh and before advancing the batch. |

### Honest gap

There is no `seon.host.instrument` namespace and no host instrumentation apply/reconcile call. Projection refresh currently only publishes the new projection ([host/context.clj:1593](/Users/sean/src/seon/src/seon/host/context.clj:1593)).

Recommended boundary:

- New `seon.host.instrument`: projection target preparation, `m/-instrument`, original/fingerprint metadata, SCI watches, and an apply ledger.
- `seon.host/start!`: cold application after graduation rebuild.
- `seon.host.eval`: hot application after projection refresh.
- `host.context` remains projection/registry owner; `host.record` remains corpus owner.

The accepted design’s assumption that every corpus var can be found through the shared base is not grounded. `sci/fork` copies the environment atom ([sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318)); agent defs replay into private forks ([host.clj:81](/Users/sean/src/seon/src/seon/host.clj:81)); only registry vars are explicitly linked afterward ([host.clj:85](/Users/sean/src/seon/src/seon/host.clj:85)). W3b must resolve both registry vars and live context-private vars until W3d makes newly authored functions shared.

The Malli envelope works unchanged in-process; the existing test proves exact preservation plus the SCI class ([host_error_sci_writer_test.clj:116](/Users/sean/src/seon/test/seon/host_error_sci_writer_test.clj:116)). Full Transit round-trip is **NOT GROUNDED** because leaf error maps can contain live Malli schema objects while the UDS codec has no default unknown-object handler ([error/instrument.cljc:247](/Users/sean/src/seon/src/seon/error/instrument.cljc:247), [uds.cljc:210](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:210)).

The envelope’s coercion hints are also still JS-specific (`js/parseInt`, `js/Date`) and therefore not host-quality unchanged ([error/instrument.cljc:158](/Users/sean/src/seon/src/seon/error/instrument.cljc:158)).

### Ranked risks and cheapest falsifiers

1. **Critical: multi-arity wrapper failure.** Instrument a two-arity SCI function and prove valid 0/1 calls plus structured invalid-2 arity behavior ([malli/core.cljc:2276](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2276)).
2. **Critical: new private corpus var remains bare.** Define a new specced function through a host batch, then make an invalid call in the next form and next batch ([host/eval.clj:239](/Users/sean/src/seon/src/seon/host/eval.clj:239)).
3. **High: structured envelope cannot cross Transit.** Encode and decode a classified bad-input envelope through `uds/encode`; current wire compatibility is **NOT GROUNDED** ([uds.cljc:210](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:210)).
4. **High: projection publishes before wrappers become current.** Barrier two sessions between projection publication and wrapper application; today refresh publishes internally before returning ([host/context.clj:1615](/Users/sean/src/seon/src/seon/host/context.clj:1615)).
5. **Medium: removed contract resurrects via watch.** Specced function → remove schema → redefine → bad old-contract call must be uninstrumented; omitted contract retractions originate in [host/record.clj:163](/Users/sean/src/seon/src/seon/host/record.clj:163).

---

## W3c — run fence and repair/preflight

### Interface ledger

| Mechanism | Owner | W3c use |
|---|---|---|
| Wire fence | [host/session.clj:54](/Users/sean/src/seon/src/seon/host/session.clj:54) | Optional `:seon.execution/run-fence` already crosses the host protocol. |
| Child batch fence | [eval.cljs:5172](/Users/sean/src/seon/src/seon/eval.cljs:5172) | At batch start, transact exactly one `db/cas-assert` against the invocation database ([eval.cljs:5187](/Users/sean/src/seon/src/seon/eval.cljs:5187)); failure skips every entry and returns `:seon.eval/fenced?` ([eval.cljs:5240](/Users/sean/src/seon/src/seon/eval.cljs:5240), [eval.cljs:5380](/Users/sean/src/seon/src/seon/eval.cljs:5380)). |
| Fence meaning | [agent/run.cljs:381](/Users/sean/src/seon/src/seon/agent/run.cljs:381) | Assert the agent still points at the named open run ([agent/run.cljs:389](/Users/sean/src/seon/src/seon/agent/run.cljs:389)). |
| Host settlement | [host/invoke.clj:34](/Users/sean/src/seon/src/seon/host/invoke.clj:34) | Process-local terminal-frame ownership only; not the database fence. |
| Host pinned read seam | [host/context.clj:1473](/Users/sean/src/seon/src/seon/host/context.clj:1473) | Demonstrates operations against an explicit immutable database value. |
| Current host transact seam | [host/context.clj:1440](/Users/sean/src/seon/src/seon/host/context.clj:1440) | Resolves current head, so it cannot reproduce the child’s invocation-database fence unchanged. |
| Symbol repair | [repair/candidates.cljc:106](/Users/sean/src/seon/src/seon/repair/candidates.cljc:106) | Reuse threshold, ranking, nearest tier, and unique-winner contract ([repair/candidates.cljc:143](/Users/sean/src/seon/src/seon/repair/candidates.cljc:143)). |
| Child preflight | [eval.cljs:3962](/Users/sean/src/seon/src/seon/eval.cljs:3962) | Budgeted detect→candidate→compile-only trial→fix/hint loop; skip rules at [eval.cljs:4078](/Users/sean/src/seon/src/seon/eval.cljs:4078). |
| Delimiter repair | [repair.cljc:149](/Users/sean/src/seon/src/seon/repair.cljc:149) | Pure best-effort repair using an injected read predicate; child reparses and redispatches repaired entries through the ordinary path ([eval.cljs:5289](/Users/sean/src/seon/src/seon/eval.cljs:5289)). |
| SCI disposable analysis | [sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318) | Fork the retained agent context, analyze there, and discard it; ordinary evaluation analyzes then executes ([interpreter.cljc:29](/Users/sean/src/seon/reference-code/sci/src/sci/impl/interpreter.cljc:29)). |

### Honest gap

`run-invocation!` does not destructure or consume the run fence, and `eval-batch-result` proceeds directly to receipts/evaluation ([host/invoke.clj:57](/Users/sean/src/seon/src/seon/host/invoke.clj:57), [host/eval.clj:196](/Users/sean/src/seon/src/seon/host/eval.clj:196)). The parent’s `result-current?` check happens only after effects have occurred ([execution/host.cljs:406](/Users/sean/src/seon/src/seon/execution/host.cljs:406)).

W3c1 therefore needs a transaction-at-explicit-database seam. On CAS loss it must return empty counts plus `:seon.eval/fenced? true`, creating no receipt.

The host has neither delimiter repair nor symbol preflight: read failures become error envelopes immediately, while forms go receipt→eval directly ([host/eval.clj:148](/Users/sean/src/seon/src/seon/host/eval.clj:148), [host/eval.clj:220](/Users/sean/src/seon/src/seon/host/eval.clj:220)). Only `rank-candidates` is exposed through the host registry today ([host/context.clj:1025](/Users/sean/src/seon/src/seon/host/context.clj:1025)).

The accepted host design intentionally differs from the child:

- Child preflight happens before receipt ([eval.cljs:4328](/Users/sean/src/seon/src/seon/eval.cljs:4328)); host design requires receipt first ([error-quality design:365](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/error-quality-u6-w3-design-2026-07-21.md:365)).
- Child ambiguity normally lets the original source run and annotates its eventual error ([eval.cljs:4431](/Users/sean/src/seon/src/seon/eval.cljs:4431)); the accepted host design makes unresolved preflight terminal and skips evaluation ([error-quality design:396](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/error-quality-u6-w3-design-2026-07-21.md:396)).

Calling that exact child parity would be incorrect; it is an accepted semantic change.

The repair accessors currently live only in `.cljs` ([config.cljs:1395](/Users/sean/src/seon/src/seon/config.cljs:1395)). Claim that the JVM can reuse them unchanged: **NOT GROUNDED**.

### Ranked risks and cheapest falsifiers

1. **Critical: fence at the wrong database value.** Capture the writer request and assert it uses the invocation database, contains exactly one run-pointer CAS, and a failed CAS creates zero receipts.
2. **High: implement child semantics instead of accepted host semantics.** Assert receipt-before-preflight and zero `eval-form!` calls on ambiguous/fatal resolution ([error-quality design:369](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/error-quality-u6-w3-design-2026-07-21.md:369)).
3. **High: analysis mutates the retained context.** Analyze a `defn` on a fork, then prove the symbol remains unresolved in the retained context ([sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318)).
4. **Medium: repaired read path bypasses normal recording or namespace transitions.** Compare a repaired malformed form against its already-correct source through receipts, tee, namespace, and output ([eval.cljs:5289](/Users/sean/src/seon/src/seon/eval.cljs:5289)).
5. **Medium: repair policy drifts across tiers.** One table test should cover absent config, `:off`, class-disabled, max-fixes, and budget; current JVM accessor parity is **NOT GROUNDED**.

---

## W3d — authored-function invocation

### Interface ledger

| Mechanism | Owner | W3d use |
|---|---|---|
| Authored identity preparation | [execution.cljs:571](/Users/sean/src/seon/src/seon/execution.cljs:571) | Query source at one immutable database value and pin its digest ([execution.cljs:585](/Users/sean/src/seon/src/seon/execution.cljs:585)). |
| Child verification | [execution.cljs:638](/Users/sean/src/seon/src/seon/execution.cljs:638) | Reject absent or digest-mismatched source before invocation. |
| Child corpus acquisition | [execution.cljs:608](/Users/sean/src/seon/src/seon/execution.cljs:608) | Acquire namespaces, require edges, functions, tests, schemas, and contracts as one program. |
| Child loading/invocation | [execution.cljs:664](/Users/sean/src/seon/src/seon/execution.cljs:664) | Install selected authored functions; resolve and apply them at [execution.cljs:781](/Users/sean/src/seon/src/seon/execution.cljs:781). |
| Current pod routing | [execution/host.cljs:858](/Users/sean/src/seon/src/seon/execution/host.cljs:858) | Only eval-batch consults the host coordinate; all authored calls are forced to the child lane ([execution/host.cljs:868](/Users/sean/src/seon/src/seon/execution/host.cljs:868)). |
| Host refusal | [host/invoke.clj:84](/Users/sean/src/seon/src/seon/host/invoke.clj:84) | Every source-digest identity returns the explicit `:core-bug` refusal. |
| Retained SCI context | [host.clj:65](/Users/sean/src/seon/src/seon/host.clj:65) | Per-agent context is a base fork; startup restores only that agent’s home namespace defs ([host/context.clj:1400](/Users/sean/src/seon/src/seon/host/context.clj:1400)). |
| U2 registry | [host/context.clj:876](/Users/sean/src/seon/src/seon/host/context.clj:876) | Late registration, shared vars, root upgrades, and lazy require for already-registered namespaces ([host/context.clj:905](/Users/sean/src/seon/src/seon/host/context.clj:905)). |
| Nursery/graduated installation | [host/graduate.clj:225](/Users/sean/src/seon/src/seon/host/graduate.clj:225) | Nursery source becomes an interpreted root; graduated source becomes a JVM root; both install through the same registry var ([host/graduate.clj:241](/Users/sean/src/seon/src/seon/host/graduate.clj:241)). |
| Startup reconstruction | [host.clj:223](/Users/sean/src/seon/src/seon/host.clj:223) | `graduate/rebuild!` reconstructs tiered registry roots before serving sessions ([host/graduate.clj:303](/Users/sean/src/seon/src/seon/host/graduate.clj:303)). |
| SCI resolution/call context | [sci/core.cljc:684](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:684) | Resolve the live SCI var and invoke interpreted code under its originating `sci.ctx-store/with-ctx`, as the nursery test path already does ([host/graduate.clj:190](/Users/sean/src/seon/src/seon/host/graduate.clj:190)). |

### Correction to the premise

The child does **not** invoke authored functions through the JVM U2 registry. It prepares a source-digest identity, acquires the complete database program, loads it into the self-host compiler, resolves the function, and applies it ([execution.cljs:571](/Users/sean/src/seon/src/seon/execution.cljs:571), [execution.cljs:629](/Users/sean/src/seon/src/seon/execution.cljs:629), [execution.cljs:1046](/Users/sean/src/seon/src/seon/execution.cljs:1046)).

The U2 registry is already the JVM host’s nursery/graduated implementation substrate, but production invocation is missing.

### Honest gap

W3d needs to compose:

1. pinned source lookup at the invocation database;
2. source-digest verification;
3. version-correct namespace/dependency materialization;
4. SCI var resolution and invocation under `with-ctx`;
5. W3b instrumentation;
6. W3c1 run-fence context;
7. existing result bounding and settlement.

Simply resolving the retained/shared var is insufficient: registry roots mutate in place ([host/context.clj:848](/Users/sean/src/seon/src/seon/host/context.clj:848)), while the request’s source identity is pinned to an older immutable database value ([execution.cljs:571](/Users/sean/src/seon/src/seon/execution.cljs:571)). Claim that `sci/resolve` alone preserves source identity: **NOT GROUNDED**.

The cross-agent live-require gate remains open. `registry-load-fn` only serves namespaces already registered in its process atom and returns no corpus source ([host/context.clj:905](/Users/sean/src/seon/src/seon/host/context.clj:905)). Successful host eval currently refreshes projection but never calls `install-nursery!` or `register-wrappers!` ([host/eval.clj:268](/Users/sean/src/seon/src/seon/host/eval.clj:268)). Registry reconstruction happens only at host start ([host.clj:223](/Users/sean/src/seon/src/seon/host.clj:223)).

Thus a namespace authored after startup is not production-grounded as requireable by another existing agent context. The anchor explicitly requires corpus-backed `:load-fn` plus cross-agent live require without restart ([program-synthesis:458](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:458)).

### Ranked risks and cheapest falsifiers

1. **Critical: pinned request executes a newer mutable root.** Prepare at database A, redefine at B, then invoke the A request; require the A result while retained/shared roots remain B afterward.
2. **Critical: pinned replay mutates a shared registry var.** Fork a linked context, redefine a linked symbol in the fork, and prove another context plus the registry remain unchanged; `sci/fork` copies the environment atom but preserves referenced objects ([sci/core.cljc:318](/Users/sean/src/seon/reference-code/sci/src/sci/core.cljc:318)).
3. **High: cross-agent require is restart-only.** Create contexts A and B, let A author `my.shared/f`, then require/call it from B without restart.
4. **High: namespace dependency closure is incomplete.** Cold-invoke namespace B requiring authored namespace A; current host restore reads only one namespace’s function sources ([host/context.clj:1400](/Users/sean/src/seon/src/seon/host/context.clj:1400)).
5. **High: dispatch still allocates the child.** A host-tier authored call must prove JVM socket ownership and no Bun child allocation; current routing explicitly selects the child ([execution/host.cljs:858](/Users/sean/src/seon/src/seon/execution/host.cljs:858)).
6. **Medium: nursery and graduated roots need different dynamic-context handling.** Exercise both through one invocation path, including an interpreted function that resolves another SCI var ([host/graduate.clj:190](/Users/sean/src/seon/src/seon/host/graduate.clj:190)).
7. **Medium: private authored invocation policy is unspecified.** `defn-` is recorded ([host/record.clj:148](/Users/sean/src/seon/src/seon/host/record.clj:148)), but whether qualified private functions are an allowed invocation surface is **NOT GROUNDED**.

---

## Recommended dependency order and safe parallelism

The partial order is:

```text
WP-A — already landed
 ├─ W3a closure
 ├─ W3b host instrumentation
 ├─ W3c1 run-fence CAS
 └─ W3c2 repair/preflight

W3b + W3c1 ──> W3d authored invocation
W3b ─────────> WP-D error detail/render
all above ───> W5/U11 deletion gate
```

Recommended execution order:

1. **W3a first**: small closure, establishes current interrupt/output baseline, and avoids concurrent edits to `host/eval.clj`.
2. **W3c1 run fence next on the safety spine.**
3. **W3b instrumentation integration.** Its new `host/instrument.clj` core may be developed in parallel with W3c1’s pinned transaction seam, but their `host/eval.clj` hooks must serialize.
4. **W3c2 preflight/repair** may develop in a new `host/preflight.clj` while W3b’s new namespace is built, but its batch hook must serialize with W3a/W3b.
5. **W3d final integration** after W3b and W3c1. Its preliminary pod-dispatch work in `execution/host.cljs` can parallelize, but `host/invoke.clj` and `host/context.clj` integration must serialize with W3c1 and the registry portion of W3b.

W3a, W3b, and W3c are not safe as whole parallel units because all require `host/eval.clj` ([host/eval.clj:181](/Users/sean/src/seon/src/seon/host/eval.clj:181)). W3c1 and W3d both need `host/invoke.clj`/`host.context.clj`, so they also must not be whole-unit parallel edits ([host/invoke.clj:50](/Users/sean/src/seon/src/seon/host/invoke.clj:50)).

## Coverage omissions

No explicit item from the anchor’s six-item W3 bullet is missing from your four units ([program-synthesis:342](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:342)).

Two adjacent gates are not explicit in the cut:

- **WP-D abridged-first/addressable full detail remains open.** `error.sci/detail` exists ([error/sci.clj:301](/Users/sean/src/seon/src/seon/error/sci.clj:301)), but the host retains live values only on successful evals ([host/eval.clj:302](/Users/sean/src/seon/src/seon/host/eval.clj:302)); the accepted design assigns the remaining retention/render/config work to WP-D ([error-quality design:574](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/error-quality-u6-w3-design-2026-07-21.md:574)).
- **All authored consumers must use W3d**, not only the public web call path. The anchor requires authored renderer, AI-twin, and button-handler calls to route through the same mechanism ([program-synthesis:494](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:494)).