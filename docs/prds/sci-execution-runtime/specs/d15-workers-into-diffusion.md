---
type: prd
status: active
tags: [prd, architecture]
---

# D15 — the diffusion workers join the diffusion tree

Grounding preamble: read the actual source of every file you touch;
report better seams and the owners' exact terms; stopping early is
FREE; if source contradicts this spec, stop and report.

Owner-approved (org sheet D15, worker.parse/worker.eval shape):
`seon.worker-validator` → `seon.diffusion.worker.parse`
(`src/seon/diffusion/worker/parse.cljs`); `seon.worker-eval` →
`seon.diffusion.worker.eval` (`.../worker/eval.cljs`). Real moves,
all callers/keys/docstrings updated; JSON wire keys unchanged (the
validator deliberately flattens at the wire boundary — verify, cite).
Update the two shadow build `:main` symbols (`:worker-validator`,
`:worker-oracle-eval` — the ONLY shadow-cljs.edn edits), the fence
test's allowed-source-paths (the moved files now live inside the
fenced tree — the special-cased root paths DELETE), and
`src/seon/diffusion/AGENTS.md` membership. Ephemeral self-host user
namespaces follow if they reference the old names.

Owned: the two moved files, shadow-cljs.edn (`:main` lines only),
test/seon/diffusion_fence_test.cljs, src/seon/diffusion/AGENTS.md,
consumers/tests by rg (enumerate). No commits, no lifecycle ops.

Gates: both worker bundles compile clean; fence test green with the
shrunken path set; focused diffusion selectors; full bin/test-cljs
once (baseline 1523/7369). rg proof: zero old-name tokens.
