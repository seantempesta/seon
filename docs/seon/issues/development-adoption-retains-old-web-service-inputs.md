---
type: issue
status: open
severity: friction
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


## Fresh construction verified

Implementation commit **`b4d665f89`**. The isolated `custody` cluster used the
same source digest, a fresh database without either retired run attribute,
and Juniper's disabled-credential settings component. Its debug URL returned
HTTP 200, 55,189 bytes, with the plan and sample messages present. Provider
usage rows remained zero. The scratch JVM was downed and its worktree removed.
This confirms the failing default request is the captured pre-change input;
it does not justify weakening the current contract. Default's reset remains
pending with the owner.

## Turn namespace reset boundary, 2026-09-09

After the custody refork (default PID 38717), the turn namespace publication
reaches loaded definitions and refuses `seon.env/advance-projection!` with
`invalid-input: must hold one immutable replacement environment`. The old
HTTP service then returns HTTP 500, 200 bytes: `seon.cluster.loop/preview-sources`
reports missing renamed starting-ns/opened-at/closed-at output keys. Source
convergence cannot be claimed after this refusal. RESET NEEDED accompanies
the rename commit; default was not operated by the lane.

Fresh construction in `tmp/turn-rename-wt` verifies the new component
`:seon.turn/attempts` as cardinality-many/ref/isComponent=true and the old
turn namespace attributes plus `:seon.ai.attempt/run` absent. The ordinary
Juniper debug page returns HTTP 200, 52,752 bytes; history is a vector of one
evaluation and provider usage remains zero. The settings component disabled
the credential variable before fixture messages were installed.

## Loop/work schema and proc move, 2026-09-09

Default PID 77143 adopted and published
`6aa136b4-9bf0-5042-bed0-dda843e7ad25` after unused alias declarations were
removed from the merged namespace. `seon.turn/step` resolves, but the running
instance still carries the old `:seon.cluster.loop/cluster` handle key.
Debug HTTP returned **500 / 314 bytes**, naming `seon.turn/preview-sources`
and missing nested inputs under `:seon.turn.loop/cluster` (cluster name,
wake channel, completion, error recurrence limit, and two more).

Fresh construction on the same rename served **200 / 42,913 bytes**, with
four Juniper evaluations and zero provider attempts. **RESET NEEDED for
`7004818dc`** remains; successful source adoption does not reconstruct a
captured service/graph input. The lane did not operate default's lifecycle.

## Re-verified at HEAD (2026-09-15)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source).

OPEN, UNVERIFIABLE as a general adoption defect. HEAD `src/seon/render/web.clj:3423` still closes over the start-time `service` while resolving the current handler. Reload does not reconstruct that immutable map. The historical default HTTP failures are not current: `curl --max-time 15 -s -o /dev/null -w 'HTTP %{http_code}; bytes %{size_download}; seconds %{time_total}\n' http://127.0.0.1:7994/` returned HTTP 200, 49,291 bytes, 1.806230 seconds. This is HTTP evidence, not browser paint or HEAD adoption convergence. Reproducing the general defect requires an isolated before/after service-contract adoption with an added required key; no production/test edits or default lifecycle mutation were made. Severity is friction: the old reset incidents do not currently block the observed page or establish a blocked agent.

surface: adoption-publication
