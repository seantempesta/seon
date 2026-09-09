---
type: issue
status: open
severity: blocker
tags: [issue, runtime, wave/render-arm]
---

# Development adoption retains old web service inputs

Observed 2026-09-09 during the custody removal slice. Default remains PID
87173. Its adopted and published source both reached
`6aa10a14-9ed8-5ea9-bfb3-376b62023e7f`, but the debug route returned HTTP 500,
110 bytes:

```text
seon.render.web/handler violated its contract (invalid-input): missing required key at [[:seon.db.process/id]]
```

The new service contract requires process provenance on the request. The
existing HTTP handler captured the earlier immutable service map at start;
reloading its Var and contract does not rebuild that captured input.
Fresh construction uses the new key in `src/seon/cluster.clj` and
`src/seon/render/web.clj`. The lane did not weaken the contract or reintroduce
the deleted custody attribute to make the old input pass.

RESET NEEDED with the custody removal commit. The owner must reconstruct
default once alongside the pending schema removals. The lane is explicitly
forbidden to stop, restart, or refork default. The landing note records the
fresh-construction check and exact implementation commit.

Visual verification also remains unavailable: CUA reports no browser
surfaces; native Chrome and Brave access return `cgWindowNotFound` (-10005).
HTTP observations do not constitute a browser-paint proof.
