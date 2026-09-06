---
title: Selected run transcript in debug UI
status: research
---

The existing bounded transcript owner is `seon.render.transcript/history-entries` (`src/seon/render/transcript.clj:911-969`). A caller supplies a render unit containing `:seon.db/db`, `:seon.cluster.agent/id`, and render profile/caps; the smallest read-only probe is:

```clojure
(seon.render.transcript/history-entries
 {:seon.db/db db
  :seon.db/connection connection
  :seon.cluster.agent/id "lab-producing-source"
  :seon.cluster.run/id "054e12b7-8330-44e4-888f-f968dc803c61"
  :seon.render/profile profile
  :seon.sci.admit/caps caps})
```

Each returned entry carries a stable `:seon.render.history/call-id`, basis transaction, read form, bounded printed value, and final bytes (`transcript.clj:949-957`). For evaluation entries, `receipt-entry` retains form ordinal, run id, read basis, result EDN/blob/size, error, triage, output, and interruption (`transcript.clj:407-432`). `form-sources` joins a stored evaluation to its form by run and ordinal (`transcript.clj:279-293`), so source and result remain paired. `input-entry` preserves form id, ordinal, source, namespace, and run id (`transcript.clj:434-452`). A form with no evaluation is currently admitted to the transcript only when it is comment-only (`comment-form-rows`, `transcript.clj:135-154`); this is the pending-form boundary to verify before promising all pending source forms.

The bounded owner already renders both projections: `render-ai` uses the token-budgeted `projection` (`transcript.clj:869-874`), and `render-html` uses the same projection plus stable entry ids (`transcript.clj:971-982`). Results are rendered through the existing value floor and blob-aware path (`transcript.clj:537-664`), while missing terminal result, evaluation error, and interruption remain distinct entry data. No new history assembler is needed.

There is a selection gap. Although `history` and `projection` accept `:seon.cluster.run/id` (`transcript.clj:508-510`, `817-820`), `candidate-entity-ids` and its count queries are keyed by agent and active-run rules (`transcript.clj:116-231`, `241-277`); the supplied run id is passed only to message decoration. Thus the current owner is an agent transcript, not an independently selected submitted run. The debug request currently selects viewer namespace, agent, subject, output, and prompt (`src/seon/render/web.clj:2555-2568`), with no run selector.

Smallest design proposal: let the debug request carry a validated `:seon.cluster.run/id` selected from the agent's durable run facts, and pass that id into a run-scoped transcript query at the existing `history` seam. Constrain evaluation and form clauses by that run id; retain agent-directed messages only if the UI explicitly wants the surrounding conversation. Keep ordinal joins for source/result pairing, and derive pending state from a form's absence of a terminal evaluation rather than storing a new flag. Then pass the selected run id in the unit used by `render-session-ai`/`render-session-html`, preserving their one shared token budget and HTML entry ids.

This proposal must be falsified before implementation with the lab cluster's actual facts: query the selected run, pull its forms ordered by ordinal, pull evaluations ordered by ordinal, and call `history-entries` with the run id. Confirm nonempty source/result pairs, a comment-only form, and any pending or error entry. The current source is sufficient to prove bounded rendering, but not yet run isolation; a test that only stubs `db/q` would miss that boundary.
