---
type: issue
status: resolved
severity: blocker
tags: [issue, web, render, debug-page, ugly-output, hand-maintained-list]
---

# The debug page's blocks are hand-labelled and the AI/HTML previews are squeezed

Observed by the owner 2026-09-08 15:50 on
`http://127.0.0.1:7994/ns/my.agents.juniper/debug`: "It used to show the
attribute and then the two boxes for AI and HTML and they looked reasonable.
This looks like shit and I only see Identity and Plan. This looks hardcoded."

It is hardcoded. `src/seon/render/web.clj` (commit `38d2fc91b`, record-render
lane) labels blocks with a literal `case`:

```clojure
[:h2 (case attribute
       :seon.render/value "Identity"
       :seon.agent/plan "Plan"
       :seon.agent/settings "Settings"
       [:code (str attribute)])]
```

and prints "No Malli :description is declared for this attribute." under
"Identity" because `:seon.render/value` is a synthetic key, not an attribute.
The AI and HTML previews sit in a ~180 px column at the left of a three-column
layout, so the AI source wraps at 12 characters. "Not yet available:
seon.eval/of-agent" is printed three times per block.

## What the ruling actually asked for (turn PRD §13)

One block per CONCERN: the record's own scalars in one block, each
component (plan, settings) in its own, derived queries in theirs — each
block rendered through the entity schema's ONE AI/HTML pair, full width,
AI and HTML side by side as before. The block's title and description come
from the schema (its `:description` / the component attribute's docstring),
never a `case`. Nothing on this page may be a hand-maintained list.

## Fix

- Derive block title/description from the schema of the value being
  rendered (`seon.schema` projection), delete the `case`.
- Restore the previous layout: blocks stacked full width, AI | HTML side
  by side at readable width; the reference graph and diagnostics below or
  collapsible, not a third column stealing the page.
- A missing target function renders ONE line naming it.
- Proof: screenshot before/after in the landing note; the owner reads it.

## Resolution, 2026-09-08

The later owner ruling supersedes the scalar grouping above: EVERY declared
attribute is shown in schema order, including absent values, with its own
AI/HTML pair. Components select their entity schema pair. Reverse concerns
are independent of graph pagination and use their declared render inputs.
The hard-coded labels are deleted; headings and descriptions derive from
schema metadata. A missing function is shown once. The instrumented page
gate passes 6 tests / 25 assertions, including absent fields and reverse
relationship selection. Playwright measured 1,568 px blocks and 762.609 px
columns at 1,600 px viewport width.

Screenshots and remaining performance limitations are in
[the landing note](../../prds/context-generation/research/page-feed-landing-2026-09-08.md).

## Reference-header follow-up, 2026-09-10

The generic block header now links installed identity values and omits
identity-less entities. It no longer prints a numeric entity-id dump or
reference count. Schema-derived titles and the existing preview layout are
preserved. See
`docs/prds/context-generation/research/debug-refs-landing-2026-09-10.md`
for the canonical-fixture regression and live verification boundary.
