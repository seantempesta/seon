---
type: research
status: active
tags: [research, render, mcp, class/n1]
---

# N1 MCP bypass — 2026-09-15

## Result and boundaries

Option A is implemented. **MCP decides evaluation/exception handling from caller
data, never arbitrary result keys; preparation rejects a missing root before
visiting or emitting a value.** Failed preparation and serialization produce
one semantic error through a producer-independent constructor.

The final live sorted-map probe succeeds. The canonical class regression passed
25 assertions twice during development, but its final post-adoption run stopped
before its assertions on the cached fixture's old `mcp-valf` contract.
**The isolated gate and platform tier are pending the orchestrator.** This is
not a whole-N1 closure or a claim that the final gate is green.

Read end to end: `AGENTS.md`, `docs/seon/issues/README.md`, the N1 class
note, all 21 member notes enumerated by the approved plan, and
`docs/prds/context-generation/research/n1-total-render-plan-2026-09-15.md`.
Read the mining report, including N1's structural-kill column:
“One total rendered-value/omission/error construction; required producers,
bounded terminal output, counted fallback.”
Skills read: data-oriented-clojure, repl, clojure-testing, datahike.
The current roadmap entry and working-edge checkpoint were also inspected;
the complete historical working-edge file was not an end-to-end read.

## Dependency and owner ledger

- Clojure gitlink `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`:
  `reference-code/clojure/src/clj/clojure/core/server.clj:194–301`.
  `prepl` supplies the exception flag on its event; `io-prepl` discards it
  before calling `valf` and retries a thrown printer with a
  `:print-eval-result` exception map. The existing MCP server now consumes
  those real PREPL events directly and keeps the flag.
- Clojure's `PersistentTreeMap.doCompare`,
  `reference-code/clojure/src/jvm/clojure/lang/PersistentTreeMap.java:328`,
  delegates lookup keys to the map comparator. Keyword lookup against string
  keys is an operation that may throw, not a shape predicate.
- Malli gitlink `3517a3cd9271b2083780ac7be1725493905bca2e`; existing armed
  calls are owned by `src/seon/instrument.clj:548–600`. Its wrapper uses the
  supplied projection's function contract, explaining the stale-fixture
  refusal without blaming the implementation.
- SCI gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`; real fixture
  acquisition remains `seon.test-support/fork-cluster-ctx`, and selected
  render invocations remain `seon.sci.kernel/invoke`.
- Existing owners: `seon.cluster/mcp-project`, `mcp-valf`,
  `mcp-io-prepl`, `seon.render.value/prepare`, `value-node`.
  Historical slice `ee8d54dca` already joined ordinary MCP admission to the
  shared renderer. This change removes the recognition bypass before it,
  preserving its blob/requery mechanism.

## Changes

1. The bridge's SCI caller marks evaluation recognition explicitly through
   the existing per-PREPL projection marker. Ordinary result maps containing
   evaluation-like keys remain ordinary values.
2. The PREPL event supplies exception status to `mcp-valf`; ordinary maps
   containing `:via`, `:trace`, and `:cause` are not exceptions.
   The old `prepl-exception-envelope?` classifier is deleted.
3. `mcp-project` contains preparation failures. `mcp-valf` contains
   serialization failures. Both use `mcp-projection-error`, which neither
   admits nor invokes a renderer. Its fallback printer clears print-length
   and print-level. The original three-argument `mcp-project` entry remains
   for already-open PREPL connections that captured the earlier `mcp-valf`.
4. `prepare` derives the path/root id once at entry, returns its refusal
   immediately, and carries the accepted id into the output.
5. `value-node` constructs terminal semantic error nodes after a producer
   throws. It preserves the failure message without selecting that producer
   again. The shared emitter and AI profile still own presentation.
6. One new regression:
   `seon.mcp-test/outward-values-use-total-projection`. It uses the canonical
   database fixture, real SCI context, and real MCP PREPL with finite readers.
   It proves successful terminal cardinality and map equality, ordinary
   evaluation-/exception-shaped maps, genuine exception handling, zero work
   for a missing root, valid preparation, one failed producer invocation and
   one semantic fallback, and preparation failure containment.

Owned source/test paths:
`src/seon/cluster.clj` (MCP functions only),
`src/seon/render/value.clj`, `test/seon/mcp_test.clj`.
Necessary unprotected caller/test updates:
`script/seon/dev/mcp.clj` (one SCI marker argument),
`test/seon/cluster/mcp_test.clj` (explicit recognition arguments).
No schema or protected owner was changed.

## Live before/after

Same observed default process: PID 69622, start 2026-09-15T19:25:43Z.
The lane never stopped, reforked, restarted, or changed its lifecycle.

Exact sorted-map form, MCP JVM mode:

```clojure
(let [c (seon.operator/connection "default")]
  (sorted-map "a" 1 "b" 2))
```

Before: one exceptional terminal, `ClassCastException`,
`:phase :print-eval-result`, trace through `mcp-project:337`.
PREPL evaluation time: 3 ms (excludes output projection).

After, including a new MCP connection after the session-loss report:

```clojure
{:tag :ret
 :val {:seon.dev.mcp/value {"a" 1 "b" 2}
       :seon.dev.mcp/windowed? false}
 :ns "user"
 :ms 3}
```

Exactly one terminal value; zero escaped projection exceptions; zero raw
stack frames. No total-latency or allocation improvement is claimed.

Final direct live preparation probe (new session `n1-mcp-proof`, 11 ms):

```clojure
{:probe/invalid-kind :seon.render.value/missing-root-identity
 :probe/visits 0
 :probe/emits 0
 :probe/producer-calls 1
 :probe/fallback-text
 "#:seon.error{:kind :seon.render.value/projection-failed, :message \"declared producer failure\"}"}
```

The regression carries the same counter/injection forms as recurring evidence.
The injected failure is at the real selected-producer invocation seam;
the database, projection, SCI context, and PREPL are not mocked.

The updated bridge-generated SCI form was evaluated in the development JVM:
`(eval (read-string (#'seon.dev.mcp/sci-evaluation-form "42" "default" 'user)))`.
It returned `:seon.dev.mcp/text "42"`, outcome `:ok`, duration 7 ms,
and PREPL time 18 ms. This verifies the updated caller; the separate
long-lived MCP bridge process was not restarted by this lane and must load
its updated script on its normal reconnect.

## Exact in-process runs

Every row used:
`(seon.test/run #'<namespace>/<test> (seon.operator/connection "default"))`.
No `bin/test`, `bin/test-fast`, or new test JVM was launched.
New/revised forms were evaluated and probed through MCP before file edits;
the table includes the refused runs rather than treating them as green.

Abbreviations:
**C** = `seon.mcp-test/outward-values-use-total-projection`;
**R** = `seon.render.value-test/anonymous-roots-refuse-instead-of-colliding`;
**B** = `seon.dev.mcp-bridge-test/namespace-coordinate-shapes-both-evaluation-modes`;
**S** = `seon.cluster.mcp-test/sci-evaluations-project-the-repl-text-face`;
**F** = `seon.render.value-test/realization-failure-is-visible-data`.

| UTC time | Test | Pass / fail / error | Run entity |
|---|---|---:|---:|
| 23:23:46 | R, root prototype before edit | 3 / 0 / 0 | 65008 |
| 23:28:18 | C, prototypes before MCP/node edits | 0 / 0 / 1 | 65304 |
| 23:29:36 | B, first caller attempt | 2 / 0 / 2 | 65329 |
| 23:29:52 | B, caller prototype before edit | 2 / 0 / 2 | 65331 |
| 23:30:38 | S, explicit caller test before edit | 5 / 0 / 0 | 65454 |
| 23:30:45 | C, after initial edits | 0 / 0 / 1 | 65455 |
| 23:31:15 | C | 25 / 0 / 0 | 65473 |
| 23:31:45 | R, after edit | 3 / 0 / 0 | 65474 |
| 23:31:47 | B, after bridge reload | 4 / 0 / 0 | 65475 |
| 23:32:14 | C, semantic message prototype | 0 / 0 / 1 | 65476 |
| 23:33:42 | C | 0 / 0 / 1 | 65992 |
| 23:34:09 | C, final containment prototype before edit | 25 / 0 / 0 | 66240 |
| 23:34:59 | R, formatted root prototype | 3 / 0 / 0 | 66742 |
| 23:35:03 | F, after message-preserving edit | 5 / 0 / 0 | 66759 |
| 23:36:05 | C | 0 / 0 / 1 | 66980 |
| 23:37:09 | C, diagnostic-key prototype | 0 / 0 / 1 | 67053 |
| 23:38:46 | C, after observed adoption convergence | 0 / 0 / 1 | 67055 |

The C refusals name the old three-argument `mcp-valf` contract when PREPL
calls the new four-argument form. The early B refusals name the old
zero-argument projection-marker contract. The final C refusal is therefore
a verification boundary, not a final green proof:
[canonical fixture retains old contracts](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).
The same stale fixture reproduces after adoption; no fixture global or
instrumentation contract was weakened/reset to make it pass.

Adoption was observed converged at
`6aa9d679-f42c-5e97-b898-234c33d4bbc8` on both the cluster and
`seon.cluster.source/current`. Earlier observations explicitly disagreed;
file edits alone were not counted as adopted evidence.
Subsequent named-session loss claimed a restart although status retained the
same PID/generation. A new session worked:
[MCP session-loss diagnostic](../../../seon/issues/mcp-session-loss-claims-unobserved-restart.md).
This lane did not attribute the transport cause.

## Per-member disposition

This is the approved Option A scope, not the broader class assignment.
The MCP member is resolved by this source slice and the live before/after.
The other 20 records were read as required; this lane did not re-fix or close
them. Other concurrent N1 verification commits may independently close them.

| Member stem (under docs/seon/issues, or its archive) | This lane's verdict / residual owner |
|---|---|
| mcp-projection-crashes-on-non-keyword-map-keys | Resolved; before/after and regression above. |
| time-limit-face-exposes-interpreter-interrupt-marker | Outside A; SCI failure-value. |
| instrumentation-headline-unbounded-when-caps-absent | Outside A; instrumentation headline. |
| expected-refusal-logs-raw-datom-error-twice | Outside A; dependency transaction logging. |
| boot-refusal-has-no-render-producer | Outside A; boot/operator output. |
| run-renderer-narrates-forms-and-receipts | Outside A; evaluation/history rendering. |
| the-debug-ai-pane-never-wraps | Outside A; browser/CSS observation. |
| operator-status-dumps-every-absent-test-result | Outside A; operator status face. |
| database-values-render-as-opaque-host-objects-in-html | Outside A; database identity HTML. |
| changed-test-report-is-one-enormous-line | Outside A; test CLI output. |
| debug-left-pane-is-not-the-exact-prompt | Outside A; saved prompt/debug page. |
| transcript-renderer-encodes-entries-as-comment-forms | Outside A; saved evaluation error face. |
| effect-receipts-have-no-render-producers | Outside A; effect schema pair. |
| an-entity-pull-returns-a-sentence-instead-of-its-attributes | Outside A; protected declared-render selection. |
| agent-pages-overflow-a-phone-viewport | Outside A; browser/CSS geometry. |
| init-failure-dumps-entire-prepl-event-history | Outside A; operator init output. |
| the-value-floors-map-face-is-not-readable-edn | Outside A; historical map grammar verification. |
| my-background-poll-costs-290-tokens-per-polled-result | Outside A; background descriptor rendering. |
| one-elision-has-two-representations-in-one-context | Outside A; elision grammar. |
| pre-rename-root-claims-are-unreadable-noise-on-every-status | Outside A; claim reclamation/diagnostic. |
| debug-pages-receive-block-patches-for-elements-they-do-not-have | Outside A; page interests/SSE targets. |

## Gate handoff and hygiene

Gate request: `tmp/orchestrator/gate-requests/n1-mcp-bypass.txt`.
Run the named path-limited gate, then the platform tier, serially.
The admission file says “orchestrator batches gates; set 2026-09-15 21:05Z”;
this lane did not override it.

`git diff --check` passed. Hook lint reported existing shadowed bindings
and an unrelated `process-identity` docstring warning, no blocking finding.
`bin/issues-index --check` reported concurrent archive/schedule drift and
the new fixture issue's missing schedule row; the index is owner-maintained.

No scratch root, worktree, shell session, test JVM, or background process was
created. All shell calls returned. Unrelated edits, `build/`, and
`workers/` were preserved.
