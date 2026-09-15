---
type: research
status: active
tags: [research, class/n1, render, verification]
---

# N1 member verification at HEAD — 2026-09-16

Twenty of the twenty-one open `class/n1` members that the
[bounded N1 research note](n1-total-render-plan-2026-09-15.md) marked **U**
were re-probed at HEAD with a CORRECT render request. The twenty-first,
`mcp-projection-crashes-on-non-keyword-map-keys`, was skipped: a lane owns
it.

**Counts: 13 resolved · 7 confirmed · 0 unverifiable.**

## What the earlier probes were missing

The 2026-09-15 probes supplied database, projection and profile and got
`:seon.render.value/missing-root-identity` back, so no shape was ever
verified. A render request needs SEVEN carried inputs, not four
(`src/seon/render/value.clj:81`, `src/seon/render.clj:130-145`). The
harness used throughout this pass, on the live `default` cluster
(pid 69622, `http://127.0.0.1:7994`, source commit observation
`6aa9d310-8baa-51ec-ac1d-77626e26eeb7`), read-only, JVM mode:

```clojure
(def conn      (seon.operator/connection "default"))
(def database  (seon.db/db conn))
(def ctx       (:seon.sci.eval/ctx (#'seon.cluster/mcp-instance "default")))
(def projection (:seon.schema/projection (seon.env/of ctx)))
(def eff       (#'seon.cluster/mcp-effective "default" {}))
(def profile   (seon.render/agent-render-profile eff))
(def base {:seon.db/db database
           :seon.schema/projection projection
           :seon.render/profile profile
           :seon.sci.eval/ctx ctx
           :seon.db/connection conn
           :seon.sci.admit/caps (seon.config/result-caps eff)
           :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms eff)
           :seon.config/on-core-error :record
           :seon.render.value/root [:seon.agent/id "probe"]})
```

Omitting `:seon.sci.admit/caps`, `:seon.sci.eval/time-limit-ms` or
`:seon.config/on-core-error` does not refuse the render — it returns the
value with `#object[clojure.lang.ExceptionInfo "projection failed: …"]`
substituted at the node the producer would have rendered. That is itself
the N1 shape (a producer failure leaking a raw host object into an
otherwise total projection) and it is why three earlier probes read as
"renderer is fine".

## Member verdicts

| Member | Verdict | Evidence pointer |
|---|---|---|
| agent-pages-overflow-a-phone-viewport | resolved | `archive/agent-pages-overflow-a-phone-viewport.md` — 375 px, three pages, 0 overflowing elements |
| an-entity-pull-returns-a-sentence-instead-of-its-attributes | **confirmed** | 9,655 chars → 100; 3,460 → 179; a turn entity → `""` |
| boot-refusal-has-no-render-producer | **confirmed** | producer declared (`seon.boot.edn:156`); operator face still `(prn ex-data)` at `fresh_operator.clj:3151-3156` |
| changed-test-report-is-one-enormous-line | resolved | `changed_test.clj:329-378`, findings bounded to 20 with `…` |
| database-values-render-as-opaque-host-objects-in-html | resolved | identity in AI and HTML; no `datahike.db`, no hash |
| debug-left-pane-is-not-the-exact-prompt | resolved | two-pane layout gone; `repl.clj:291-311` is byte-exact |
| debug-pages-receive-block-patches-for-elements-they-do-not-have | resolved | 0 console messages on two page loads, feed live |
| effect-receipts-have-no-render-producers | resolved | `seon.effect.edn:22-26`; both faces read verbatim |
| expected-refusal-logs-raw-datom-error-twice | **confirmed** | `transaction.cljc:29,533` still `log/raise` (logs then throws) before the writer's bounded face |
| init-failure-dumps-entire-prepl-event-history | **confirmed** | `fresh_operator.clj:2446-2452` carries the whole output into `(prn data)` |
| instrumentation-headline-unbounded-when-caps-absent | resolved | `instrument.clj:279,373-380`; live headline 106 chars |
| my-background-poll-costs-290-tokens-per-polled-result | **confirmed** | `effect.clj:48-50` inlines the payload; an 8 KB result renders as 210 chars of pure elision with no identity |
| one-elision-has-two-representations-in-one-context | **confirmed** | prose twin at `render/value.clj:456-457,464-469`, third spelling at `db.clj:2350-2354` |
| operator-status-dumps-every-absent-test-result | resolved | 9 lines / 653 bytes; typed UNKNOWN naming `--verbose` |
| pre-rename-root-claims-are-unreadable-noise-on-every-status | **confirmed** | 149 of 288 claims name absent roots; largest record 787 KB |
| run-renderer-narrates-forms-and-receipts | resolved | owner deleted in `7296d173b`; no narration seam in `src/` |
| the-value-floors-map-face-is-not-readable-edn | resolved | pulled fire entity is readable EDN, attributes qualified |
| the-debug-ai-pane-never-wraps | resolved | every `pre`/`code` `pre-wrap`, overflow 0 |
| time-limit-face-exposes-interpreter-interrupt-marker | resolved | `sci/kernel.clj:481` dissocs the marker; `b5665971d` |
| transcript-renderer-encodes-entries-as-comment-forms | resolved | real stored failed evaluation renders source + execution error |
| mcp-projection-crashes-on-non-keyword-map-keys | not probed | owned by a running lane |

Each verdict's probe output is pasted in that member's own
`## Verified at HEAD (2026-09-16, N1 verification)` section. Resolved notes
carry `status: resolved` and live under `docs/seon/issues/archive/`; the
seven confirmed notes stay open and now carry a `surface:` line and a
one-line fix sketch.

## What the confirmed seven say about the class

Three are one defect: **a declared family producer replaces a queried
value**. The entity-pull member is the class note; the background-poll
member is the same substitution in its cost form, where the producer's
inline payload is either payload-proportional or cut down to a 210-character
elision that names neither the effect nor its disposition; the elision
member is the second representation the same construction emits.

The new evidence that should change the design's priority is the pulled
TURN entity rendering to the empty string. The selection currently accepts
a producer's `nil`/`""` as a complete render, so a 100% loss looks like a
successful total render — absence of signal read as health, which AGENTS.md
names as this project's recurring failure class.

Two are operator-terminal faces (boot refusal, init failure) that construct
a correct value and then print raw `ex-data` instead of rendering it. One
is the fork's transaction logging seam; one is operator claim reclamation.
None of the four is fixed by the value renderer, so the umbrella cannot be
closed by a render-only change — the 2026-09-15 note's option (A) scope is
confirmed as partial, not as class closure.

## Boundaries of this pass

No source or test edits, no test JVMs, no SCI evaluations, no transactions,
no cluster restarts, no lanes launched. Live reads were JVM-mode MCP
evaluations, `curl`, a read-only browser observation of the local pages,
`bin/seon status`, and source/git reads. Two verdicts (the datahike double
log, the init failure face) are source-exact rather than induced, because
inducing them requires a refused transaction and a failed publication
against the live development cluster; both notes say so.

One adjacent observation, recorded here rather than on the lane-owned
member: on this JVM a `seon.db/pull` contract violation returned through
MCP crashed the outward projection with
`ArityException: Wrong number of args (3) passed to: seon.cluster/mcp-project`
at `seon.cluster$mcp_valf` (`cluster.clj:444`), escaping as a 50-frame raw
trace under `:phase :print-eval-result`. It reproduced twice on that value
and never on ordinary values. Whether that is the live lane's in-flight
edit or a second bypass belongs to the lane that owns `src/seon/cluster.clj`.
