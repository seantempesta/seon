---
type: issue
status: open
severity: cleanup
tags: [issue, agent, runtime]
---

# Remove surviving requires of deleted pod Group 2 namespaces

## Problem

Final-surviving source and configuration still require namespaces deleted by
pod cut Group 2.

## Evidence

The post-cut require scan finds ten seams:

- `src/my/blob.cljc:13`, `src/seon/agent/message.cljc:12`, and
  `src/seon/agent/web.cljc:14` require deleted CLJS leaves.
- `src/seon/client.cljs:56`, `:175`, `:180`, and `:186` require deleted
  schedule, filesystem, search, and shell namespaces.
- `config/system.edn:515-517` retains deleted search, filesystem, and shell
  namespaces in `:seon.eval/home-requires`.
- Retained tests under `test/my/blob_test.cljc` and
  `test/seon/agent/{fs,fs_portable,schedule,search,shell,shell_portable,web_search}_test.*`
  still require the deleted CLJS leaves and are seam failures, not translated
  async tests.

## Owner

The pod cut's Step 6 source/config cleanup owns these seams; Group 5 owns the
remaining `seon.client` removal. Deleted namespaces receive no replacement.

## Acceptance

A source/config require scan has zero references to the Group 2 namespaces,
and the surviving JVM capability namespaces still load through their existing
owners.
