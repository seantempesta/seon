---
type: research
status: complete
tags: [research, runtime, testing]
---

# Stage 1.5 child sampler and retirement proof (2026-07-21)

## Disposition

The sampler mechanism is substantially test-proven, but the frozen Stage 1.5
live matrix is not complete. No source repair is justified by this audit.
The missing evidence is one real retained-value drive that observes owner
retirement or tier replacement while sampling, proves exactly one unavailable
settlement and no fresh-runtime retry, and then exercises the authorized value
route and browser against the same admitted artifact.

The earlier U4/U1.5 drive is not that proof. It demonstrates invocation
retirement and definition replay, not a `value-sample` request in flight. Its
durable log at `tmp/sci-probe/exec/out/u4-proof-drive.log` contains
`:seon.execution/child-retired? true` for turn 3 and successful later turns,
but contains no value-sample frame or value-route observation. It must not be
promoted into sampler evidence by inference.

`u15` is already closed according to the current roadmap and Stage 2 refresh.
Do not recreate it. Do not reset `default`, and do not use or prune
`.shadow-cljs-b2/` or `out-b2/` for this proof.

## Dependency and source ledger

| Contract | Maintained owner | Grounded evidence |
|---|---|---|
| Pure bounded descent and paging | `src/seon/render/value.cljc` | `test/seon/render/value_test.cljs`; request rejection touches zero, sequence work is exactly `offset + page-size + 1`, the ceiling is 1,025, and million-entry maps touch one page plus a sentinel |
| Closed ordinary Transit frames | `src/seon/execution.cljs` | `test/seon/execution_test.cljs`; closed request/result/error round trips, million-segment paths are rejected before segment work, and the child repeats policy checks before lookup |
| Retained owner selection and lifecycle | `src/seon/execution/host.cljs` | `test/seon/execution/host_test.cljs`; owner is selected from recorded eval-id membership across child/host lanes, not from a fresh tier lookup |
| JVM retained-value parity | `src/seon/host.clj` | `test/seon/host_conformance_writer_test.clj`; live same-session sampling, widened/incomplete policy refusal before raw lookup, and replacement-session honest unavailability |
| HTTP authorization and status translation | `src/seon/web/serve.cljs` | `test/seon/web/serve_test.cljs`; missing and cross-agent evals are byte-uniform 404s with zero sampler calls; admitted available/unavailable, policy, and core outcomes map to 200/400/503 |
| Runtime and paging rulings | Bun `d8ecf098572e2b8265b23e40c04efb4067e516cc`, Transit CLJS `0.8.280`, Orchard `c462a25d9798` | `reference-code/bun/docs/runtime/child-process.mdx`, `reference-code/transit-cljs/`, and `reference-code/orchard/src/orchard/inspect.clj` as recorded in [[execution-child-value-sampling-boundary-2026-07-20]] |

## Evidence matrix

| Acceptance cell | Current proof | State |
|---|---|---|
| Work bound, including hostile or million-entry input | Instrumented pure tests assert touches, not output size; sequence paging is exact and excessive requests touch zero | Proven in focused tests; live drive need only show the production route uses this producer |
| Parent and serving-runtime policy refusal | Host and child tests widen each effective-limit field and assert zero raw lookups | Proven in focused tests |
| Same-owner sampling | Child conformance returns the retained live value; host owner selection uses recorded eval-id membership | Proven in focused tests |
| Ownership refusal before host work | Missing and cross-agent route requests return equal 404 bodies and make zero sampler calls | Proven in focused tests; browser/server observation still missing from the frozen matrix |
| Missing or replaced retained value | A fresh JVM session sampling the prior eval returns `:availability :unavailable` and `:recompute? true` | Proven at the direct host protocol boundary |
| Tier change does not redirect an old eval | `value-owner-selection-ignores-the-current-tier-selector` proves recorded ownership wins over current tier | Proven as a pure host-state test; no live tier-flip observation |
| Retirement while sample is active | Configuration retirement and timeout tests settle unavailable; timeout ignores a late terminal response and removes the retired entry | Proven with fake processes, not a real child/host |
| Exact retry behavior | `sample-once!` addresses only `sample-owner`; it never calls the invocation `invoke-once!`/reload retry path. Exit settles the active sample through `sample-host-unavailable`. The timeout test proves one kill and no second late-frame side effect | Source- and focused-test-proven; a real process-spawn/generation trace is still missing |
| Agent FIFO and no wedge | A retained sample blocks a later invocation until its terminal frame; timeout clears the lane | Proven with fake processes |
| Authorized route, `/data`, eval disclosure, SSE, browser | Source gates are recorded in the roadmap | Missing as one frozen live artifact matrix |

## Shortest safe live proof

This is an integration checkpoint, so first freeze every source path included
in the artifact and record `git rev-parse HEAD`, `git status --short`, and the
artifact digest. Abort if any build input changes before the last observation.

Use a unique retained branch, never `default` and never `u15`:

```bash
proof_branch=stage15-sampler-proof-20260721
bin/seon branch open "$proof_branch"
bin/seon branch status "$proof_branch" --edn

```

The status result supplies the branch-scoped URL and process coordinates. Keep
all captured requests, responses, process IDs/generations, and server logs
under a new `tmp/stage15-sampler-proof/` directory. The driver must use the
normal branch artifact; it must not point Shadow at `.shadow-cljs-b2/` or
`out-b2/`.

Drive these cells in order through one agent and one recorded eval whose value
is a lazy or instrumented large sequence containing a 100 MiB string beyond
the requested page. Retain the eval id from database facts rather than parsing
display prose.

1. Request page zero through `GET /agent/{id}/value?eval={eval-id}` and one
   admitted later page using the route's canonical encoded `path` and
   `offset`. Record 200, `no-store`, stable subtree id, honest `more?`, bounded
   response bytes, and the child process identity. The work counter must be
   read from the driver fixture and equal at most
   `offset + page-size + 1`; response length alone is not evidence.
2. Request the same eval through another agent id. Record 404 and prove the
   execution host's sample-send counter did not advance.
3. Start a deliberately held sample in the owning runtime. After its request
   frame is observed, retire that exact owner using the existing driver-owned
   child/host kill seam. Do not kill an operator child blindly and do not
   restart the whole branch.
4. Record exactly one terminal unavailable projection with
   `:seon.render.value/recompute? true`. Record the pre-retirement owner
   generation/process, the replacement or absence after settlement, and the
   total sample-send/spawn counts. Acceptance is one send to the old owner,
   zero sends of that request to a new owner, and no stale terminal frame that
   changes the settled result.
5. If testing a tier flip, transact the supported execution-tier fact only
   after the eval is recorded, then repeat the old eval request. Acceptance is
   honest unavailable from its recorded owner or the retained old owner's
   result; a fresh current-tier runtime must never claim it. Record both lane
   coordinates and the unchanged request count.
6. Re-run the recorded source through the normal agent path. The new eval id
   may become available in the new runtime; the old eval id must remain
   unavailable. This is recomputation, not an automatic sampler retry.
7. Observe the same sequence in a real browser: available page, next-page
   morph with stable target identity, cross-agent refusal, retired-value
   unavailable state, and recompute control. Pair it with a server-side
   identity-encoded gzip SSE capture because the browser bridge alone is not
   authoritative for the long-lived feed.

Close only the branch created by this run, in `finally`, after copying the
evidence into the owning dated report:

```bash
bin/seon branch close "$proof_branch"
bin/seon branch status "$proof_branch" --edn

```

The close proof must show no retained branch process record for this name.
Unchanged `default` basis/process records and untouched B2 cache mtimes are
part of the safety evidence.

## Abort and issue rules

Stop and open or update one note in `docs/seon/issues/` before any repair if:

- the large-value work counter exceeds the ruled budget;
- a cross-agent or missing eval sends a host request;
- retirement yields more than one settlement, retries on a replacement, or
  leaves the agent FIFO pending;
- a tier change lets the current tier claim an eval it did not produce;
- the route returns persisted display text as if it were a live value; or
- branch-scoped work mutates `default`, `u15`, or either B2 cache.

Absent one of those falsifiers, the next action is evidence collection and
Stage 1.5 ledger closure, not production editing.
