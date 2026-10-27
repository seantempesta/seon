---
name: push-forks-freely
description: "Pushing to the owner's public fork repos (seantempesta/*) needs no ask; no force, never overwrite main"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 21e07b71-4cb1-4e53-a41e-5be4db8f99e1
  modified: 2026-10-23T05:35:46.434Z
---

Push branches to the public forks at seantempesta/* without asking the owner first, and tell sessions they may too. Still: no force push, never overwrite a fork's main, no AI trailers on fork commits, verify with `gh api` after.

**Why:** the owner, 2026-10-23: "You don't have to ask me to push public repos anymore."

**How to apply:** when a pin bump's commit exists only in a local submodule clone, push it to its fork as part of the merge, then record the SHA. Related: [[verify-gitlink-pin-bumps]].
