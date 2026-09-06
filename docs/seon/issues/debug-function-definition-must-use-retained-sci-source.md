---
type: issue
status: resolved
severity: friction
tags: [issue, render]
---

# Inspect a renderer definition without leaving its input

Candidate function links navigate away from the selected entity. Display the
definition already retained with the render invocation in an inline disclosure,
so the input, result and viewing namespace remain visible. This is function
definition evidence, not an executed producing form.

## Live correction — 2026-09-06

The first implementation (`809e726d9`) incorrectly expected `:seon.fn/source`
inside the retained declaration. Its helper test supplied that invented shape
and passed, while the real browser showed no definition.

MCP `seon.sci.kernel/program-function` on `lab-browser-0906` returned
`:seon.sci.eval/function-source`, with source beginning `(defn render-item-html`.
The declaration is the normalized SCI program record, not the raw database row.
Read that exact field; do not query the filesystem or add another database read.
Render it as escaped code and show an explicit message when source is absent.

The browser control probe must open this disclosure and verify the definition,
unchanged URL/subject and unchanged rendered output. It currently fails on the
missing disclosure and therefore catches the shape mismatch missed by the
initial helper test.

## Resolution

`93cc6bab0` reads the normalized SCI source key. Two focused tests passed five
assertions, including a real acquired program row from the canonical database
fixture and explicit escaping/missing-source checks. After hot reload and the
ordinary SCI render wake, the controls browser probe passed: actual definition
shown inline, subject/URL retained, paired output unchanged, and zero JavaScript
errors. MCP independently confirmed the retained definition equals the indexed
database source for this fixture. This adds no parser, query or evaluation to
the disclosure.
