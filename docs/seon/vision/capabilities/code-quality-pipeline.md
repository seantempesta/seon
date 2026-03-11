---
type: capability
status: complete
tags: [vision, schema]
---
# Automated Code Quality Pipeline

Every code change triggers automatic validation: syntax checking, static analysis, code reload, affected test execution, convention compliance, and AI review. The pipeline blocks on test failure, ensuring agents cannot proceed with broken code. This is the verification layer that makes agent autonomy safe.

## What Exists

- Dev hook triggers on every Edit/Write
- Syntax validation and static analysis with auto-repair
- Automatic code reload via clj-reload
- Affected tests run automatically (not full suite)
- Generative tests for schema boundaries
- Convention compliance checking
- AI review for style and correctness
- Blocks on test failure

## Gaps

None.

## Related

- Components: [[components/dev-tools]]
- PRDs: [[prds/agent-repl-interface/prd]]
