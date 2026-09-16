---
type: issue
status: open
severity: cleanup
created: 2026-09-16
tags: [issue, docs, ai]
---

# The provider skill names a retired default model

## Problem and evidence

The shipped-provider paragraph in `.agents/skills/llm-providers/SKILL.md`
names deepseek-v4-flash as the default descriptor. `config/default.edn:358`
explicitly records the 2026-09-10 switch to deepseek-flash; the configured
value is at line 362 and its descriptor at line 543. Default's issue-family
attempt 3c0ce4212c40 also records deepseek-flash. The lane used the live
configuration, not the stale skill spelling.

## Acceptance

The skill's owner updates the shipped-model paragraph and source citations
from the current manifest. Preserve historical research as dated evidence;
do not replace the live database's selected model from a prose example.
