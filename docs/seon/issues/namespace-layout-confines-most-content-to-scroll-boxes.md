---
type: issue
status: open
severity: friction
tags: [issue, render, wave/agent-context]
---

# Give namespace and debug content a usable layout

## Problem

One primary unit gets most page width; all other content is placed in a narrow
column of separately scrolling boxes. Debug embeds those scroll constraints
inside another scrolling pane. This prevents reading connected data and output.

## Evidence

Browser inspection of default root and /agent/root/debug on 2026-09-05.
Fleet occupied the broad area; maintenance records filled the narrow column.
Debug had nested vertical and horizontal scrollbars and very long schema dumps.
src/seon/render/web.clj:244-249 and :395-403 assign rank;
resources/public/css/input.css:1115-1148 puts non-primary units in column 2
with max-height 10rem. The owner explicitly rejected this UI as design guidance.

## Owner

seon.render.web layout and resources/public/css/input.css, with content choices
belonging to the selected render functions.

## Acceptance

A representative wide and narrow browser view keeps data, candidate functions
and output readable. Exact content remains reachable; per-value scroll boxes
do not dominate. Subject/viewer selection remains stable across feed updates.
