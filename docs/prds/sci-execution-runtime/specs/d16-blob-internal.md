---
type: prd
status: active
tags: [prd, architecture]
---

# D16 — blob machinery behind the teaching surface

Grounding preamble: read the actual source of every file you touch;
report better seams and the owners' exact terms; stopping early is
FREE; if source contradicts this spec, stop and report.

Owner-approved (org sheet D16): `src/my/blob.cljs` spends ~700 lines
on storage-view normalization, fsync publication, retained-hash
observation, and restore materialization BEFORE its agent API (~:713).
Extract that machinery to `my.blob.internal` (parent-only require, per
the gated law — the conformance gate will enforce it automatically).
`my.blob` keeps schemas and the agent-facing verbs (put!/get/concat!/
text/stat) plus thin non-agent wrappers where other namespaces
genuinely consume storage operations (enumerate those consumers).
Replace any direct `!storage-view` mutation from outside with one
narrow parent-owned configuration fn. Docstrings stay true
current-state (they render to agents). Behavior-preserving: no key
renames, no reset.

Owned: src/my/blob.cljs, new src/my/blob/internal.cljs, blob tests +
any consumer needing the narrow config fn (enumerate). No commits, no
lifecycle ops.

Gates: focused blob selectors + the internal-require conformance gate
(it computes the new internal automatically — must pass with zero
allowlist) + full bin/test-cljs once (baseline 1523/7369).
