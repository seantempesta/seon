---
type: issue
status: resolved
severity: blocker
tags: [issue, render, web, live-drive]
---

# Print faces have no stylesheet, so every value renders as a bare triangle

## Problem

Every collapsed value node in every web page renders as an **unlabeled
disclosure triangle**. The root page of the live `default` cluster has
**699** of them. A human cannot tell one collapsed value from another,
so the page is unreadable as a whole even though its content is
entirely correct and present in the DOM.

`src/seon/print.cljc:158-159` emits the summary with its label in an
**attribute only**, with no child text:

```clojure
[:summary {:class "seon-print-summary"
           :data-seon-summary (::summary node)}]
```

The obvious intent is that CSS surfaces the attribute via
`content: attr(data-seon-summary)`. **No such rule exists anywhere.**

## Evidence

Live browser, `http://127.0.0.1:7994/` (cluster `default`, pid 31570),
2026-08-10, current HEAD.

A representative node — the label is genuinely present and genuinely
invisible:

```html
<details class="seon-print-node seon-print-map" data-seon-path="[]">
  <summary class="seon-print-summary" data-seon-summary="{} 9 keys"></summary>
  <span class="seon-print-content">…</span>
</details>
```

Measured in the page:

```text
document.querySelectorAll('summary.seon-print-summary').length          => 699
… filter(s => !s.textContent).length                                    => 699   (all of them)
getComputedStyle(summary, '::before').content                           => "none"
getComputedStyle(summary, '::after').content                            => "none"
matched CSS rules containing "seon-print-summary"                       => []
document.styleSheets                                                    => ["/css/output.css"]
```

Zero of 699 summaries have any text. The stylesheet is the only one
loaded, and it matches nothing.

Confirmed in the tree — this is **not** a stale build artifact, the
rules were never written:

```text
rg -l "seon-print" resources/              => NONE
rg -l "seon-render-unavailable" resources/ => NONE
```

Both `resources/public/css/input.css` (the source) and
`resources/public/css/output.css` (the build output) contain zero
occurrences of any `seon-print` class.

The same gap affects the table face, which emits its content span with
`:hidden "hidden"` at `src/seon/print.cljc:147` expecting CSS that is
not present, and the `seon-render-unavailable` face
(`src/seon/render.clj:509`, `src/seon/render/walk.clj:436`,
`src/seon/render/web.clj:269,368`), which renders as unstyled body text.

## Class

This is the **declared-but-unrendered** class: a producer emits a
complete, correct DOM contract and no consumer implements it. The
failure is silent — the markup validates, the data is all there, the
page returns 200, and nothing anywhere reports that 699 labels were
dropped on the floor.

## Owner

`resources/public/css/input.css` (the missing rules) with
`src/seon/print.cljc:140-160` as the producer defining the contract.

Whoever fixes it should decide which side owns the label. Putting the
text in the element as a child (rather than an attribute plus a CSS
`attr()` rule) makes the class unrepresentable — a summary with no
visible label could no longer be constructed, and the page would degrade
correctly with no stylesheet at all.

## Acceptance

- Every `summary.seon-print-summary` has a visible label naming the
  collapsed value, with no stylesheet required to see it.
- `seon-render-unavailable` renders as a recognizable refusal face
  rather than unstyled body text.
- The table face's `hidden` content span is either styled or removed.
- One recurring proof asserts a rendered page has zero unlabeled
  summaries, so the class cannot silently return.

## Resolution

Resolved in this commit at the producer and presentation owners:

- `seon.print` emits the summary label as the `<summary>` element's real child
  text. The unused `data-seon-summary` copy was deleted, leaving one semantic
  source and readable native disclosure markup when CSS is absent.
- `resources/public/css/input.css` supplies quiet print-token, native marker,
  derived-table, and `seon-render-unavailable` rules. `bin/css` rebuilt the
  ignored `output.css` asset with Tailwind v4.1.18 in 95 ms.
- `seon.print-test/structural-summaries-carry-readable-child-text` proves that
  emitted print disclosures cannot have an empty label, while the static-route
  regression proves the tracked source stylesheet covers the print summary,
  table, and unavailable faces.

Live proof on 2026-08-10:

- the existing `default` cluster served the rebuilt semantic rules immediately
  from `http://127.0.0.1:7994/css/output.css`, proving CSS resources are
  live-served without a JVM restart;
- its retained root package honestly stayed on the sovereign cluster's older
  rendered markup rather than synchronizing application code behind its back;
  and
- fresh cluster `ui-print-css`, forked from the newly published `current-src`,
  served 124 `summary.seon-print-summary` elements at
  `http://127.0.0.1:7979/`: 124 labeled and zero empty. Its built stylesheet
  served the summary/marker, table, and unavailable selectors.
