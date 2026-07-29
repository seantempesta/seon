---
name: browser-automation
description: "Verify Seon's current cluster JVM web UI in a real browser. Load this for /, /agent/{id}, /data, /feed/{id}, message submission, Datastar block morphs, layout, or console errors. Discover the advertised cluster URL first—ports are dynamic—and use a server-side client when the browser bridge cannot hold SSE."
---

# Browser automation for Seon

Verify the current JVM UI, not the deleted pod UI. The actual URL comes from
the cluster advertisement:

```text
bin/seon-fresh status
bin/seon-fresh open NAME
```

The advertisement gains the bound web URL and port only after the server is
ready (`src/seon/cluster.clj:900-922`). Never assume port 7890.

## Own the tab

Browser tabs are shared. Create a new tab for this task, remember its ID, and
do not navigate or close a tab you did not create. Re-read tab state after
navigation because element references become stale.

Use the configured Browser or Chrome control skill rather than relying on old
MCP tool names. Inspect:

- the screenshot and visible text;
- stable block IDs and layout;
- browser console errors;
- the network request around an action; and
- focus/value retention while a render update arrives.

## Current paths

Only test routes that current source serves:

| path | current purpose |
|---|---|
| `/` | selected cluster agent page |
| `/agent/{id}` | agent page |
| `/data` | database/schema drill |
| `/feed/{id}` | Datastar SSE feed |
| `POST /agent/{id}/message` | inbound message |

The dispatcher is `src/seon/render/web.clj:734-840`. Debug pages, debug feeds,
canvas controls, agent creation, and `/call` are tabled design, not current
browser targets.

## Handle SSE honestly

Some browser-control bridges fail or return 503 for long-lived
`text/event-stream` requests. A loaded shell with no morph does not by itself
prove either a server defect or server health.

When the bridge is ambiguous:

1. use a server-side HTTP client against the advertised `/feed/{id}`;
2. inspect the selected cluster log with `bin/seon-fresh logs NAME`;
3. make a database change through an existing supported path; and
4. confirm a Datastar patch arrives.

Use the browser again for the user-visible effect. Server-side frames prove
delivery; the browser proves morph/layout/focus behavior.

The current feed gives each tab a sliding-1 tap and an initial full paint at
`src/seon/render/web.clj:530-608`. Socket writes park on the fork's drain state
at `src/seon/render/web.clj:502-528`.

## Minimal verification pass

1. Discover the selected cluster's advertised URL.
2. Create your own browser tab.
3. Open `/`, `/agent/{id}`, or `/data`.
4. Capture a screenshot and console messages.
5. Start network observation before submitting a message.
6. Verify the POST status and unchanged input affordance.
7. If SSE is not observable, use the server-side check above.
8. Record the exact cluster, URL, route, and evidence.

Load `datastar-web-ui` for current render mechanics. Read
`docs/seon/architecture/ui.md` and ruling 12 at
`docs/prds/sci-execution-runtime/plan/README.md:1087-1097` before testing or
proposing any broader target UI.
