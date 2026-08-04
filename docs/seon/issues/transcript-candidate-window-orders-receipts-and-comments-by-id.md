---
type: issue
status: open
severity: friction
tags: [issue, render, ordering]
---

# Order transcript receipt and comment candidates by numeric facts

## Problem

The transcript's bounded candidate queries still break same-instant receipt
and comment-form ties with their string identities. The final receipt ordering
uses the numeric run/form facts, but the earlier limited candidate window can
select the wrong newest rows before that correct seam sees them.

## Evidence

`src/seon/render/transcript.clj` orders `comment-form-rows` and
`recent-receipt-rows` by `[?at :desc ?id :desc]`, then
`candidate-entity-ids` carries those ids through another string comparison.
Run opening time and transaction, form/eval ordinal, and numeric entity id are
already queryable. This was found by the class sweep accompanying commit
`7cfb2435f`; message ordering was fixed there, while receipt/comment ownership
was deliberately left untouched.

## Owner

The transcript candidate-window query and its budget-derived limit.

## Acceptance

- Same-instant receipts and comment-only forms enter the bounded candidate set
  in numeric run/form order beyond ordinal nine.
- The candidate query uses only numeric or temporal facts, never an identity
  string as an ordering key.
- A tight-budget regression proves both candidate selection and final order.
