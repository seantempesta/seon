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
