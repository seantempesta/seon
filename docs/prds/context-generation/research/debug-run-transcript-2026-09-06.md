---
title: Selected run transcript in debug UI
type: research
status: complete
tags: [research, render, web, context]
---

The existing bounded transcript owner is `seon.render.transcript/history-entries` (`src/seon/render/transcript.clj:911-969`). A caller supplies a render unit containing `:seon.db/db`, `:seon.cluster.agent/id`, and render profile/caps; the smallest read-only probe is:

The live scratch cluster is `lab-browser-0906`; the agent is `root` and the
full run identity is
`lab-producing-source:054e12b7-8330-44e4-888f-f968dc803c61`. The exact
bounded read-only selection probe was:

```clojure
(let [database (seon.db/db (seon.operator/connection "lab-browser-0906"))
      run-id "lab-producing-source:054e12b7-8330-44e4-888f-f968dc803c61"]
  {:forms (seon.db/q {:query '[:find ?ordinal ?source
                               :in $ ?id
                               :where
                               [?run :seon.cluster.run/id ?id]
                               [?form :seon.cluster.run.form/run ?run]
                               [?form :seon.cluster.run.form/ordinal ?ordinal]
                               [?form :seon.cluster.run.form/source ?source]]
                      :args [database run-id]
                      :order-by '[?ordinal :asc]
                      :limit 8})
   :evaluations (seon.db/q {:query '[:find ?eval ?ordinal
                                    :in $ ?id
                                    :where
                                    [?run :seon.cluster.run/id ?id]
                                    [?eval :seon.cluster.eval/run ?run]
                                    [?eval :seon.cluster.eval/ordinal ?ordinal]]
                            :args [database run-id]
                            :order-by '[?ordinal :asc]
                            :limit 8})})
```

The live result selected exactly two forms, ordinals `0` and `1`, with source
lengths `73` and `74`, and two evaluations at ordinals `0` and `1`. The first
probe accidentally used `get-else` with a nil default, which Datahike rejects;
the successful probe selected entity IDs first and pulled optional terminal
attributes afterward. The returned envelope was bounded/windowed by MCP and
retrievable as a blob, rather than dumping the run or transcript.

Each returned entry carries a stable `:seon.render.history/call-id`, basis transaction, read form, bounded printed value, and final bytes (`transcript.clj:949-957`). For evaluation entries, `receipt-entry` retains form ordinal, run id, read basis, result EDN/blob/size, error, triage, output, and interruption (`transcript.clj:407-432`). `form-sources` joins a stored evaluation to its form by run and ordinal (`transcript.clj:279-293`), so source and result remain paired. `input-entry` preserves form id, ordinal, source, namespace, and run id (`transcript.clj:434-452`). A form with no evaluation is currently admitted to the transcript only when it is comment-only (`comment-form-rows`, `transcript.clj:135-154`); this is the pending-form boundary to verify before promising all pending source forms.

The bounded owner already renders both projections: `render-ai` uses the token-budgeted `projection` (`transcript.clj:869-874`), and `render-html` uses the same projection plus stable entry ids (`transcript.clj:971-982`). Results are rendered through the existing value floor and blob-aware path (`transcript.clj:537-664`), while missing terminal result, evaluation error, and interruption remain distinct entry data. No new history assembler is needed.

There is a selection gap. Although `history` and `projection` accept `:seon.cluster.run/id` (`transcript.clj:508-510`, `817-820`), `candidate-entity-ids` and its count queries are keyed by agent and active-run rules (`transcript.clj:116-231`, `241-277`); the supplied run id is passed only to message decoration. Thus the current owner is an agent transcript, not an independently selected submitted run. The debug request currently selects viewer namespace, agent, subject, output, and prompt (`src/seon/render/web.clj:2555-2568`), with no run selector.

Smallest design proposal: let the debug request carry a validated `:seon.cluster.run/id` selected from the agent's durable run facts, and pass that id into a run-scoped transcript query at the existing `history` seam. Constrain evaluation and form clauses by that run id; retain agent-directed messages only if the UI explicitly wants the surrounding conversation. Keep ordinal joins for source/result pairing, and derive pending state from a form's absence of a terminal evaluation rather than storing a new flag. Then pass the selected run id in the unit used by `render-session-ai`/`render-session-html`, preserving their one shared token budget and HTML entry ids.

This proposal is grounded in the live run selection above, but the probe did not
establish a comment-only, pending, or error form; those remain falsifiers for
the proposed UI contract. A focused follow-up should call
`history-entries` with a fully populated render unit and inspect only the
selected run's entries. A test that only stubs `db/q` would miss the run
isolation boundary.
