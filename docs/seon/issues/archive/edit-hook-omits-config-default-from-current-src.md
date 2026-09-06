---
type: issue
status: resolved
severity: friction
tags: [issue, config, operator]
---

# Edit hook omitted `config/default.edn` from current-src publication

## Problem

The source publication digest includes `config/default.edn`, but the edit hook
did not classify that file as a source-index input. Editing it through the
recognized `apply_patch`, `Edit`, or `Write` tools therefore skipped the
PostToolUse `bin/seon init --changed` publication call, leaving `current-src`
stale until another publication.

## Evidence

`src/seon/cluster.clj:1377-1381` declares `config/default.edn` in
`source-roots`. Before the fix, `bin/seon-hook:1145-1153` admitted only
`src`, `test`, and schema-resource paths. The reproducible probe
[`edit_hook_source_index_probe_2026_09_06.bb`](../../prds/context-generation/research/edit_hook_source_index_probe_2026_09_06.bb)
now returns:

```clojure
{:config-default true, :config-other false, :src true, :test true, :schema true}
```

## Resolution

`eef2ac128` adds an exact canonical-path admission for `config/default.edn`.
Shell edits remain outside the configured tool-hook surface and are not
automatically published.
