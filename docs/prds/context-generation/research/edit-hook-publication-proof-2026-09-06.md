---
type: research
status: complete
tags: [research, source, runtime]
---

# Edit hook and fresh-fork proof — 2026-09-06

Configured Codex and Claude hooks run `bin/seon-hook` for `apply_patch`,
`Edit`, and `Write`. PostToolUse calls the existing
`bin/seon init --changed` owner. Shell writes do not trigger these hooks;
after a shell source edit, invoke that publication command and verify its result.
Publication failure is advisory feedback, not proof the branch updated.

## Live evidence

Using `apply_patch`, temporarily added `EDIT-HOOK-PROBE-0906` to the docstring
of `seon.render.transcript/render-run-ai`. No manual publication command ran.
MCP readback positively found the marker in indexed source and doc at
commit `6a9db72a-3e62-5e7a-a07c-2b18adaee0f6`.
Restoring the file through `apply_patch` published
`6a9db740-0660-59f3-a472-d3bb1a4b44eb`; readback found the original docstring
and no marker. The artifact at `data/clusters/build/current-src.edn` matched
the branch head and freshly calculated source snapshot digest
`bf958d333fc9e4576522728f522181fda0a13f68b4db4af69634a1dac016cc3e`.

`bin/seon init lab-hook-proof` created dormant `:cluster-lab-hook-proof`.
MCP compared both materialized database values: identical commit IDs and
identical function source/doc rows. The branch was not started and made no
provider request. Existing running clusters retain their own indexed program.

The readback forms are in `edit_hook_readback_2026_09_06.clj`. They use the
live `lab-run-inspection` instance's store and release materialized databases.
Dependency grounding: Datahike's `branch-as-db` and
`release-materialized-db`, also used by `script/seon/fresh_operator.clj`.

## Coverage correction

`seon.cluster/source-roots` includes `config/default.edn`, but the hook's
`source-index-path?` omitted it. Commit `eef2ac128` adds an exact canonical
path check. The focused probe admits default config, source, tests and schemas
while excluding unrelated `config/test.edn`. This corrects a publication
trigger, using the existing publication mechanism.

After the selected-value and inline-definition UI changes (`93cc6bab0`), the
same live readback found head `6a9dbb6f-928d-50b8-af34-a31819f283d5` and again
confirmed the artifact head and current file snapshot match. No manual source
publication was needed for those edits.
