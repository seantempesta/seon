---
type: issue
status: open
severity: friction
tags: [issue, schema, ai]
---

# Give provider JSON values a named recursive schema

## Problem

The provider-owned documents `:seon.ai/usage` and `:seon.ai/sent` are declared
as `[:map-of :string :any]`. Their variability is real, but `:any` promises
more than the external JSON boundary can supply: arbitrary JVM values validate
even though provider request and response documents are JSON values.

Opening the surrounding map declarations under owner ruling #48 did not cause
publication to reject these leaves: a fresh process published and booted with
the current declarations. The incident nevertheless exposed the declaration
while auditing publication, and closed maps had made this nested schema debt
less visible by rejecting accreted outer data before its declared leaves were
examined.

## Evidence

`resources/seon/schema.edn` declares both provider documents with `:any`
values. `:seon.ai/usage` flows into completion, partial, and attempt shapes;
`:seon.ai/sent` flows into the request body. A fresh isolated `bin/seon init`
published commit `6a6fe66c-b101-5457-8604-87930e33d13f`, and cluster
`ruling48-publication` reached READY with 447 instrumented vars, proving the
current admission path accepts them rather than identifying them as the
publication blocker.

## Owner

The AI provider document declarations in `resources/seon/schema.edn` and the
one provider JSON codec in `seon.ai`.

## Acceptance

Declare one named recursive JSON-value schema and use it for provider document
values. Generative round-trips cover null, booleans, numbers, strings, arrays,
and string-keyed objects, and refuse a non-JSON JVM object. No admission check
is weakened and no closed map is restored.
