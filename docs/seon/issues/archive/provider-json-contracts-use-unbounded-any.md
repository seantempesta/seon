---
type: issue
status: resolved
severity: friction
tags: [issue, schema, ai]
---

# Give provider JSON values a named recursive schema

## Problem

The provider-owned documents `:seon.ai/usage` and `:seon.ai/sent` were declared
as `[:map-of :string :any]`. Their variability is real, but `:any` promised
more than the external JSON boundary can supply: arbitrary JVM values validated
even though provider request and response documents are JSON values.

Opening the surrounding map declarations under owner ruling #48 did not cause
publication to reject these leaves: a fresh process published and booted with
the declarations. The incident nevertheless exposed the declaration while
auditing publication, and closed maps had made this nested schema debt less
visible by rejecting accreted outer data before its declared leaves were
examined.

## Evidence

The original declarations for both provider documents used `:any` values.
`:seon.ai/usage` flows into completion, partial, and attempt shapes;
`:seon.ai/sent` flows into the request body. The fail-first probe showed that a
representative provider payload and a function value both validated.

## Resolution

`resources/seon/schema.edn` now declares one named recursive
`:seon.ai/json-value` schema using Malli's local registry and `:ref`. Its values
are exactly JSON null, boolean, integer/double, string, recursively nested
arrays, or string-keyed objects. Both `:seon.ai/usage` and `:seon.ai/sent` use
that schema.

The focused regression validates a representative provider usage payload,
schema-generated samples, and seeded round-trips for every JSON partition. A
function value is refused. The focused AI, admission, and program gate passed
48 tests / 207 assertions. No admission rule was weakened and no closed map was
restored.
