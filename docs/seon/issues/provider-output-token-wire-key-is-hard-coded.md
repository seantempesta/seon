---
type: issue
status: open
severity: friction
tags: [issue, ai, config, wave/ai-provider-protocol]
---

# Make the provider descriptor own its output-token wire key

## Problem

The provider descriptor names an abstract output budget, but its schema always
projects that fact as `max_tokens`. A Kimi K3 row therefore cannot use Kimi's
current preferred `max_completion_tokens` field without also sending the
deprecated field.

## Evidence

- `resources/seon/schema.edn:607-613` attaches one literal
  `[:seon.ai/wire [["max_tokens" ...]]]` projection to
  `:seon.config.ai/max-tokens` for every provider row.
- `src/seon/ai.clj:397-418` merges schema-derived fields before
  `extra-body-edn`. An extra body can add `max_completion_tokens`, but it cannot
  suppress the builder's `max_tokens`.
- Kimi's current Chat API, verified 2026-08-03 and cited in
  `docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`,
  marks `max_tokens` deprecated and directs callers to
  `max_completion_tokens`.

## Owner

The schema-derived provider descriptor wire facts in `resources/seon/schema.edn`
and their one projection in `seon.ai`. This belongs in the existing descriptor,
not a Kimi-specific request builder.

## Acceptance

- A provider descriptor explicitly and queryably determines the wire field for
  its one output budget without model-name branching.
- The DeepSeek row still emits only `max_tokens`.
- A Kimi K3 row emits only `max_completion_tokens`.
- Extra-body conflict protection remains intact, and no second config map,
  provider registry, or HTTP owner is introduced.
- Pure request-body tests assert the exact DeepSeek and Kimi JSON documents
  before any paid call.
