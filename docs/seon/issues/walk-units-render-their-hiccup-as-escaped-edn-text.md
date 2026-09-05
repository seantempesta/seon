---
type: issue
status: open
severity: blocker
tags: [issue, render, web, class/n11, wave/live-drive-render, wave/visual-qa]
---

# Stop printing a block's Hiccup as escaped EDN text

## Problem

Whole walk units on the root and agent pages paint, as their complete visible
content, a printed EDN string of the Hiccup vector they were supposed to
become. The reader sees `"[:div {:id \"seon-value-…\", :class
\"seon-data-panel\"} nil nil [:details {:class \"seon-print-node …` for
several screens. An `/html` projection is reaching the value printer instead
of the DOM emitter.

This is the outward-values-bypass-the-render-contract class wearing a new
coat: the block's own markup is being treated as agent data.

## Evidence

Observed live 2026-08-14 on both targets. The offending node is a
`span.seon-print-elision` that is the ONLY child of an
`article.seon-walk-unit`:

```html
<article class="seon-walk-unit seon-rank-deep" data-rank="50"
         data-unit-id="seon-value-ede835df67e5e0378e061a63"
         data-walk-path="[:seon.render.walk/neighbours 31]"
         id="seon-value-ede835df67e5e0378e061a63" style="order:50">
  <span class="seon-print-elision">"[:div {:id \"seon-value-3d598ef754bfceb2e693aeb4\", :class \"seon-data-panel\"} nil nil [:details {:class \"seon-print-node seon-print-map\", :data-seon-path \"[]\"} [:summary {:class \"seon-print-summary\"} \"{} 16 keys\"] [:span {:class \"seon-print-content\"} [:span {:class \"seon-print-delimiter\"} \"{\"] [:span {:class \"seon-print-keyword\"} \":seon.maintenance.request/log-dir\"] …</span>
</article>
```

Counts on the pages walked:

| Page | Hiccup-as-EDN units | Total walk units |
|---|---|---|
| `http://127.0.0.1:7994/` (default root, HEAD) | 29 | 138 |
| `http://127.0.0.1:7994/agent/root` | 29 | 138 |
| `http://127.0.0.1:55156/` (drive root) | 17 | 138 |
| `http://127.0.0.1:55156/agent/drive-one-agent-attempt-5` | 0 | 38 |

Every instance carries class `seon-print-elision`, so the value reaching the
printer is an elision value whose omitted payload is Hiccup. Deep in the
default root page the escaping compounds to four levels (`\\\\\\\"`) inside a
single elision, with a trailing `… 5711 more characters of 7349; requery by
[:seon.render.call/id [:seon.render/html …] 1] at path [] offset 1638 with
:seon.render.profile/agent`.

Full walk with per-page verdicts:
[ui-verification-2026-08-14](../../prds/context-generation/research/ui-verification-2026-08-14.md).

### Design-lab observation, 2026-09-05

The print-owner fix in `e54240e9d` removes the quoted Hiccup payload from
oversized HTML elisions. A fresh `default` process (PID 1170) still delivered
only `rendered HTML…60637 more characters of 63913` in the debug page's
actual-output section for `seon.flow`. The output is no longer escaped EDN,
but it also supplies no useful preview of the selected renderer's result.
The stored-data section renders separately and remains visible. Keep this
issue open for the inspection experience until the existing fit/profile and
navigation mechanisms provide a useful bounded preview or an actionable way
to inspect the actual output; replacing unreadable output with a label alone
does not complete that experience.

## Owner

The `/html` projection seam between `seon.render.walk` / `seon.render.web`
and `seon.print`/`seon.render.value`: Hiccup produced for a block must never
be handed to the value printer as data.

## Acceptance

No page contains a `seon-print-elision` (or any node) whose text begins
`"[:div` or otherwise spells a Hiccup vector. A block that exceeds its budget
emits an elision VALUE describing the omission, rendered as markup. One
recurring proof asserts that no rendered page's text contains a printed
Hiccup vector, so the class cannot return through another producer.
