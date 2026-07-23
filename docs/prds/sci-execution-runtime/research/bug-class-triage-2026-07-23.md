---
type: research
status: active
tags: [research, runtime, architecture]
---

# Bug-class triage — dissolve classes by design (2026-07-23 AM)

Owner-directed recurring triage: group every open finding by the CLASS
it belongs to, name the design evolution that dissolves the class, and
surface the genuinely owner-level decisions. Individual bugs are queue
rows; classes are design work. Sources: the overnight lane returns, the
U12 drill findings, the CLJC test-parity audit, the docs-reconciliation
mismatch ledger, and the 119 open issues (top-10 triage of 2026-07-23).

## The class map

| # | Class (the invariant that keeps breaking) | Instances so far | Dissolving design move | State |
|---|---|---|---|---|
| C1 | Boundary totality: a value that cannot cross a boundary must become the ruled steering error, never a raw throw | drill codec leak (clojure.core$* — via a response path that BYPASSES the existing wire-safe-value guard); host session errors (triage #6); db result/error-shape ambiguity (triage #8) | Malli research VERDICT (§0.1/§0.4): one compiled m/encoder per envelope schema with {:encode/wire} properties projecting non-wire leaves into R15 result-symbol refs — representation decided by schema BEFORE the codec; replaces the try-encode-and-catch fallback pair (host/eval.clj:58-92); plus the generative encode→decode round-trip as the standing totality property. First probe: enumerate write-frame! callers to find the bypassing path | grounded (malli-root-enforcement §0.1/§0.4, b0898e861); bug-chase implements |
| C2 | Global attribute references: every statement — transact AND read/pull — names only registered attributes, on every tier | drill current-turn rejection (CORRECTED anatomy: a manual JVM db/pull used a DERIVED projection key as a stored attribute — read-side admission is the hole); claim/turn schemas registered in .cljs while builders are .cljc (adjacent class, docs mismatch #4); agent-bootstrap-attrs hand list (client.cljs:741) | Investigation VERDICT: mechanism A — committed :seon.schema facts as the one global authority — is mostly BUILT (canonical rows, fail-closed writer, lazy install, transact admission with teaching errors at db/internal.cljc:332). Remaining: (i) extend admission to PULL patterns (validate pattern keys vs the committed projection; steering distinguishes derived projection keys from stored attributes); (ii) delete the claimant leaf's validation defeat (= C4); (iii) replace agent-bootstrap-attrs with the computed transactable population | grounded (attr-registration-investigation); bug-chase implements; owner blesses A |
| C3 | Computed completeness over hand-enumerated tooling lists | JVM test-discovery hole (~20 orphans); the wiki's false discovery claim | Every root/list in tooling becomes a computed rule PLUS a completeness assertion (the orphan gate pattern). Generalize: discovery, schema-usage, census (census already computed) | test-integrity lane implementing now |
| C4 | Validation is structural, never per-tier opt-in | claimant leaf built with schema-validation? (constantly false) → JVM can persist Malli-invalid values | Delete the opt-out: the bound-committed-projection mechanism becomes the ONLY way to build a database context; a validation-free context is unrepresentable (test/bootstrap contexts get the explicit empty-projection form that already exists) | bug-chase lane (audit rank #2) |
| C5 | Limits are config facts (R27) | literal residue: turn/core retry bounds, shell/web defaults, driver/host invocation caps (docs mismatch #3) | The R27 sweep + (candidate) a boot-time computed check that guarded namespaces contain no numeric limit literals — decide if the check is worth its noise after the sweep | bug-chase lane sweeps; check = owner call |
| C6 | Type discipline at bb/JVM operator seams | UnixPath slurp (fixed 2a2844d7e) | Idiom already established in-file; low recurrence; NOT worth a design move beyond the wiki entry | closed |
| C7 | Registered-shape expressiveness in the schema bridge | alias form rejection (fixed 0e1954cb6, recursive dereference + cycle guard) | Fixed at the bridge choke point; the generative schema-install round-trip (C1's property test) also covers this class going forward | closed |
| C8 | Gates vs shared in-flight tree | full writer gate red during lane windows (issue full-writer-gate-fails-during-runtime-lane-integration) | Process design, now ruled: localized tests per lane; full suites only at frozen-tree orchestrator checkpoints | ruled (owner AM); issue closes at next green checkpoint |
| C9 | Crash-recovery proof debt | U2's unproven falsifiers: remaining kill points, two-driver race variants | Drills PAUSED (owner AM #4); organic crashes under the relaxed-parallelism regime are the evidence stream; every crash → queue row; drills resume as the graduation gate after core hardening | standing posture |
| C10 | Stale render / reactive invalidation | datastar stale render (triage #10); nested-authored-render-hides-child-reload; pod-remains-ready-after-web-listener-loss | Likely dissolves into the web-render process port (renders pure over a replica value; invalidation from the committed feed) — verify against each issue rather than spot-fixing in the dying pod web tier | fold into web tier slice 2; verify per issue |

## Owner-level decisions (the hands-on set)

1. **C2 mechanism** — registration authority: database-facts (A), boot
   reconciliation (B), transact-time steering only (C), static check
   (D), or a composition. The investigation returns a ranked
   recommendation; the call is yours because it decides whether
   `register!` becomes a WRITE to the cluster (agents' registrations
   visible to each other — matches "all agents hooked to one live
   database") or stays process-local with reconciliation.
2. **C1 representation** — when a value can't cross the wire, is the
   schema-declared projection ALWAYS a result-symbol reference, or may
   interfaces declare custom projections (e.g. a function value
   renders as its name + arity)? Default recommendation: result-symbol
   only (one representation), custom projections need a proven case.
3. **C5 enforcement** — after the literal sweep, do we want the
   no-literals boot check (structural but potentially noisy), or is
   review + R27 discipline enough?
4. **C4 test/bootstrap posture** — confirming the empty-projection
   escape stays legal ONLY for explicitly-constructed test hosts
   (never reachable from operator wiring).

## Standing mechanics for this triage

Re-run this triage whenever the bug queue grows materially (the owner's
recurring trigger); new findings join an existing class row or open a
new one; a class with ≥2 instances and no design move is a flag, not a
backlog.
