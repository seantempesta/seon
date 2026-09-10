(ns context-cookbook-document-2026-09-09
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

; Format executed evidence; never fabricate or evaluate a database result here.
(let [directory "docs/prds/context-generation/research/"
      record (edn/read-string (slurp (str directory "context_cookbook_landed_2026_09_09.edn")))
      capture (edn/read-string (slurp (str directory "context_page_capture_2026_09_09.edn")))
      titles ["Help" "Identity" "Plan" "Messages" "Settings" "Notes" "Namespace"
              "Namespace counts" "Runtime" "History" "Faults at root" "Root's agents" "Customer totals"]
      choices ["The declared help pair returns bare lines."
               "Pull: one known entity and its nested refs."
               "Pull: the plan component and its nested steps; the recursive selector has attribute-level evidence."
               "Reverse-ref pull: the known agent's inbox and sender shapes."
               "Effective settings: query the cluster configuration and pull the agent overlay; derive turns-left from turn facts."
               "Reverse-ref pull: this agent's notes, including an empty result."
               "Dir: namespace declarations as data; inspect unknown attribute candidates before choosing keys."
               "Q: aggregate the facts under the declared attributes."
               "Pull: the runtime component, turn transaction refs, trigger, and listens; the wildcard trigger makes evidence attribute-level."
               "Q joins the runtime's turns to evaluations; map selects stored source and ordinal. The prompt itself is the history block, whose AI concern emits nothing."
               "Q with inner pull: filter by repair steward and shape matching faults. Root receives this at turn 0; an ordinary agent receives it only when a fault is routed to it."
               "Q with inner pull: select agents and shape their current work."
               "Q: group customer values and aggregate amounts."]
      section (fn [title choice {:keys [comment source shown source-bytes bytes evidence]}]
                (str "\n## " title "\n\n" choice "\n\n```clojure\n"
                     (when (seq comment) (str comment "\n")) source "\n```\n\n"
                     source-bytes " form bytes; **" bytes " output UTF-8 bytes**."
                     (when (seq evidence)
                       (str " Complete evaluation evidence: " (pr-str (frequencies evidence)) "."))
                     "\n\n```clojure\n" shown "\n```\n"))]
  (assert (:fixture-unchanged? record))
  (assert (= (count titles) (count (:reads record))))
  (spit (str directory "context-cookbook-2026-09-09.md")
        (str "---\ntype: research\nstatus: verified\ntags: [agent-context, repl, render]\n---\n\n"
             "# Context cookbook — landed data shapes, 2026-09-09\n\n"
             "Read AGENTS.md, the chart r2 and roadmap, its raw-data probe note, and turn PRD §18–§18d end to end. "
             "This replaces the earlier proposed-schema examples with executed canonical-fixture evidence. "
             "[Landing and verification](context-page-review-2026-09-09.md).\n\n"
             "MCP JVM mode, cluster `" (:cluster record) "`, explicit `(seon.operator/connection \"" (:cluster record)
             "\")`, immutable basis **" (:basis record) "**. Reads execute through the real agent SCI evaluation point with that database supplied. "
             "Every write below executes with `datahike.api/with` on a succession of immutable database values; no fixture or default write is committed. "
             "The source shown is exactly what an agent types. Read outputs are the production renderer's shown text; write outputs are the compact transaction report's exact `pr-str`. "
             "The nine writes use fixed message event ids for reproducibility.\n\n"
             "The [whole reseeded prompt](context_cookbook_final_prompt_2026_09_09.txt), read end to end, is **"
             (:seon.page/prompt-bytes capture) " bytes**. Help is **" (:seon.page/help-bytes capture)
             " bare bytes**; its complete entry is **2554 bytes**. The exact multiline regression is **128 bytes**. "
             "Paid trial: **`:unavailable`**, provider credits unavailable; no paid retry.\n\n"
             "Pull describes one known entity or known set, nested refs, and reverse refs. Q handles filters, joins, and aggregates; q with inner pull combines filtering and shaping. "
             "Finite pull selectors and positive datom patterns record index constraints; recursive/wildcard pulls and inner-pull/absence joins retain attribute-level evidence. "
             "The counts below include acquisition and rendering reads as well as the form: index-pattern presence does not make the entire evaluation exact. "
             "The dependency capture owner is `src/seon/db.clj:335–423`.\n\n"
             "Dependency ledger: Datahike `transaction.cljc:640` identity upsert, `:738` nested maps, `:785` cardinality-one replacement, `:997/:1059` retractEntity/retract; "
             "`pull_api.cljc:304` reverse refs and component collections. First-party owners: `seon.db/transaction-result`, `seon.repl/source-text`, `seon.render.value/prepare`, and the schema-declared block pairs. "
             "The next read is db-after; tx-data already says what changed, so the report omits db-before/db-after.\n"
             (str/join (map section titles choices (:reads record)))
             (str/join (map #(section (:title %) "Speculative write on the landed schema; the following read receives its db-after." %) (:writes record)))
             "\n## Reproduction\n\n"
             "`context_page_probe_2026_09_09.clj` captures the prompt and executes the plan examples. "
             "`context_cookbook_landed_2026_09_09.clj` re-executes every read and the nine speculative writes. "
             "[Exact retained results](context_cookbook_landed_2026_09_09.edn) include source/output counts and read evidence. "
             "The earlier default-only observations remain in `context_cookbook_rechecked_2026_09_09.edn`; their proposed schema is historical.\n")))
