---
type: issue
status: open
severity: blocker
tags: [issue, architecture, render, class-kill]
---

# Make outward values unable to bypass one total render contract

## Problem

Outward values can reach agents, operators, MCP, and the web UI through
unrelated partial faces. A missing producer can silently select an empty or
opaque fallback; a face can throw, disclose host representation, or expand
without a bound; and several renderers encode computed values as source
comments or narration. Each instance is locally fixable, but the class remains
writable because no construction owns totality, boundedness, representation,
and declared producer selection together.

## Evidence

The open corpus contains 34 members spanning 2026-07-31 through 2026-08-11:
[[a-six-word-eval-error-renders-as-two-thousand-characters]],
[[agent-pages-overflow-a-phone-viewport]],
[[an-unmatched-print-face-throws-no-matching-clause-and-names-nothing]],
[[boot-refusal-has-no-render-producer]],
[[changed-test-report-is-one-enormous-line]],
[[cluster-config-and-bootstrap-plan-render-as-raw-maps]],
[[collection-render-drops-209-of-210-results-without-an-elision-value]],
[[contract-violation-serializes-print-tree-inside-error-data]],
[[database-values-render-as-opaque-host-objects-in-html]],
[[debug-left-pane-is-not-the-exact-prompt]],
[[debug-pages-receive-block-patches-for-elements-they-do-not-have]],
[[effect-context-suffix-returns-comment-notices]],
[[effect-receipts-have-no-render-producers]],
[[every-agent-prompt-is-a-neighborhood-render-walk-contract-violation]],
[[expected-refusal-logs-raw-datom-error-twice]],
[[init-failure-dumps-entire-prepl-event-history]],
[[instrumentation-headline-unbounded-when-caps-absent]],
[[mcp-projection-crashes-on-non-keyword-map-keys]],
[[my-background-poll-costs-290-tokens-per-polled-result]],
[[my-web-fetch-returns-plain-html-as-a-vector-of-integers]],
[[namespace-renderer-encodes-results-as-comments]],
[[namespace-units-render-error-schema-boilerplate]],
[[nested-map-sequences-render-as-tables-inside-structural-values]],
[[object-identity-addresses-break-prompt-prefix-stability]],
[[pre-rename-root-claims-are-unreadable-noise-on-every-status]],
[[render-value-floor-refuses-any-map-with-unqualified-keys]],
[[render-walk-frames-values-as-comments]],
[[render-walk-wrapper-returns-comment-notices]],
[[run-renderer-narrates-forms-and-receipts]],
[[schema-exact-reuse-warnings-are-unreadable-at-volume]],
[[status-floods-unreadable-external-claim-warnings]],
[[time-limit-face-exposes-interpreter-interrupt-marker]],
[[transcript-renderer-encodes-entries-as-comment-forms]], and
[[unindexed-namespaces-render-as-empty]].

Recent closures recur on 2026-08-07, 2026-08-08, 2026-08-10, and
2026-08-11: [[archive/render-proc-never-delivers-its-stop-completion-after-a-settled-stream]],
[[archive/reasoning-attribute-perturbs-the-agent-ai-walk-projection]],
[[archive/transcript-floor-renders-a-missing-root-identity-refusal]],
[[archive/a-fault-notice-says-it-interrupted-run-with-no-run]],
[[archive/print-faces-have-no-stylesheet-so-values-render-as-bare-triangles]],
[[archive/render-profile-activation-elides-the-session-transcript]], and
[[archive/contract-fit-render-selection-never-reaches-a-nested-value]].

## Owner

The one `seon.render` selection/call construction, the one `seon.print/fit`
owner, and the external projection leaves as one class-kill wave.

## Acceptance

- Every outward projection is constructed through one total result shape that
  distinguishes rendered value, explicit omission, and flat error.
- Important reachable schemas cannot publish without their declared
  projections; generic fallback use is counted and fails the graduation gate.
- The selected producer's output is terminal data, bounded by the selected
  profile, and no outward constructor accepts comment-prefixed narration as a
  computed result.
- Properties cover ordinary keys, host-wrapper values, deep/nested values,
  missing producers, and failure faces without a throw or silent empty result.

## N1 class-kill wave — 2026-08-12

The class remains open: this lane closed nine members, partially advanced two,
and recorded the exact remaining owner/edit on every other open note. Two
coherent source slices landed:

- `4bc8104d8` makes the existing render/print crossing structurally total: an
  arbitrary value never becomes render-unit keys, terminal AI and HTML
  producer output is fitted, every fit cut is a complete elision value,
  unknown terminal faces become flat errors, and retained call evidence
  records `:seon.render/would-fall-to-floor?`.
- `5e449b275` removes namespace comment framing, makes empty and omitted states
  ordinary values, uses honest “no indexed members” language, and reduces
  compact error schemas to their declared `:error/message`.

Dependency grounding for this slice: Malli `3517a3cd` keeps maps open by
default and validates the qualified render-unit declaration; SCI `fcbd886`
supplies the live-Var invocation/fork boundary; the selected first-party seams
are `src/seon/render.clj`, `src/seon/print.cljc`, and
`src/seon/render/value.clj`. Producer selection remains one mechanism in
`seon.render`; fitting/emission remains one mechanism in `seon.print`.

| Member | Disposition | Remaining boundary |
|---|---|---|
| `a-six-word-eval-error-renders-as-two-thousand-characters` | Open | Error/instrument evidence must be retained by identity and summarized. |
| `agent-pages-overflow-a-phone-viewport` | Open | CSS viewport owner. |
| `an-unmatched-print-face-throws-no-matching-clause-and-names-nothing` | Open | `seon.sci.admit/semantic-value` still needs a total default. |
| `boot-refusal-has-no-render-producer` | Open | Cluster/operator producers. |
| `changed-test-report-is-one-enormous-line` | Open | Changed-test CLI report producer. |
| `cluster-config-and-bootstrap-plan-render-as-raw-maps` | Open | Cluster/config and protected bootstrap producers. |
| `collection-render-drops-209-of-210-results-without-an-elision-value` | Resolved | Pre-existing complete collection elision. |
| `contract-violation-serializes-print-tree-inside-error-data` | Open | Instrumentation stores intermediate print syntax. |
| `database-values-render-as-opaque-host-objects-in-html` | Open | Identity-only admission projection; focused test is red. |
| `debug-left-pane-is-not-the-exact-prompt` | Open | Protected web owner. |
| `debug-pages-receive-block-patches-for-elements-they-do-not-have` | Skipped | Protected N5 web owner. |
| `effect-context-suffix-returns-comment-notices` | Open | Effect-state value and producers. |
| `effect-receipts-have-no-render-producers` | Open | Effect receipt declarations/producers. |
| `every-agent-prompt-is-a-neighborhood-render-walk-contract-violation` | Resolved | Pre-existing selector/rendered-field split (`80ae69ad1`). |
| `expected-refusal-logs-raw-datom-error-twice` | Open | Protected database refusal owner. |
| `init-failure-dumps-entire-prepl-event-history` | Open | Operator publication face. |
| `instrumentation-headline-unbounded-when-caps-absent` | Open | Instrumentation no-caps request remains. |
| `mcp-projection-crashes-on-non-keyword-map-keys` | Open | MCP map-key recognition/projection. |
| `my-background-poll-costs-290-tokens-per-polled-result` | Open | Background receipt descriptor producer. |
| `my-web-fetch-returns-plain-html-as-a-vector-of-integers` | Open | Web capability body decoding. |
| `namespace-renderer-encodes-results-as-comments` | Resolved | `5e449b275`. |
| `namespace-units-render-error-schema-boilerplate` | Partial | Error sentence fixed; protected walk-level closure hoist remains. |
| `nested-map-sequences-render-as-tables-inside-structural-values` | Resolved | `4bc8104d8`. |
| `object-identity-addresses-break-prompt-prefix-stability` | Resolved | Pre-existing stable object faces. |
| `pre-rename-root-claims-are-unreadable-noise-on-every-status` | Open | Operator claim reconciliation. |
| `render-value-floor-refuses-any-map-with-unqualified-keys` | Resolved | `4bc8104d8`. |
| `render-walk-frames-values-as-comments` | Resolved | Public crossing fixed by `4bc8104d8`; protected prose is no longer called there. |
| `render-walk-wrapper-returns-comment-notices` | Resolved | `4bc8104d8`. |
| `run-renderer-narrates-forms-and-receipts` | Skipped | Protected wedge-lane run owner. |
| `schema-exact-reuse-warnings-are-unreadable-at-volume` | Open | Schema-admission finding construction. |
| `status-floods-unreadable-external-claim-warnings` | Open | Operator claim/status summary. |
| `time-limit-face-exposes-interpreter-interrupt-marker` | Open | SCI kernel failure value. |
| `transcript-renderer-encodes-entries-as-comment-forms` | Partial | Comment subclass is fixed; live proof remains, and an unrelated ordering test is red. |
| `unindexed-namespaces-render-as-empty` | Resolved | `5e449b275`. |

Focused evidence:

- `seon.render.ns-test`: 5 tests, 134 assertions, green.
- `seon.print-test` plus `seon.render-simplification-test`: all new N1
  regressions green; the gate is red only in
  `nested-values-render-their-declared-faces` (five assertions), where full
  Datahike internals bypass identity-only projection.
- `seon.render.transcript-test`: the execution-error/comment regression is
  green; the namespace is red only in the independently existing generated
  bootstrap-prefix ordering regression.

No protected Phase-1, N5, wedge-lane, database, or public-contract path was
edited, and no issue note was moved to `archive/`.
