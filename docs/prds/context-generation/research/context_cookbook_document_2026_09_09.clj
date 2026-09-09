(ns context-cookbook-document-2026-09-09
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

; This formats retained default-MCP evidence; it never evaluates a database form.
(let [root "docs/prds/context-generation/research/"
      path (str root "context-cookbook-2026-09-09.md")
      prior (slurp path)
      result (edn/read-string (slurp (str root "context_cookbook_rechecked_2026_09_09.edn")))
      records (concat (:reads result) (:writes result) (:proposed-reads result))
      _ (assert (every? :unchanged? records))
      prefix (subs prior 0 (or (str/index-of prior "\n## Agent-source recheck\n")
                              (str/index-of prior "\n## Identity\n")))
      prefix (str/replace prefix
                          "each read receives `database = @connection`. Basis 536871165."
                          (str "the connection and immutable read basis are supplied to bare reads. Basis "
                               (:basis result) "."))
      prefix (str/replace prefix
                          "../../../seon/issues/platform-blob-reachability-fails-at-3d13aa0f7.md"
                          "../../../seon/issues/archive/platform-blob-reachability-fails-at-3d13aa0f7.md")
      choices {"Identity" "Pull: one known entity and its nested refs."
               "Plan" "Pull: the agent's plan component and nested steps."
               "Settings" "Pull: the agent's override component."
               "Runtime target" "Pull: the intended component shape; this installed-schema refusal is not an empty runtime."
               "Runtime current" "Q plus inner pull: filter open turns and shape their refs; absence filtering and inner pull yield attribute-level evidence."
               "Messages" "Reverse-ref pull: all messages addressed to this known agent, with sender shape."
               "History" "Q: join this agent's turns to their evaluations. The prompt itself is the history block."
               "Faults root" "Q plus inner pull: filter by repair steward and shape the fault. Root gets this read at turn 0."
               "Faults juniper" "Q plus inner pull: an ordinary agent gets this block when a fault is routed to it as steward."
               "Notes" "Reverse-ref pull: notes attached to this known agent."
               "Namespace" "Pull: the namespace and its reverse declaration refs."
               "Namespace counts" "Q: count stored facts for the declared attributes."
               "Root agents" "Q plus inner pull: select all agents and shape their current work."
               "Orders" "Q: join order identities, customers, and amounts."
               "Customer totals" "Q: group by customer and sum amounts."}
      section
      (fn [mode {:keys [title thought form output evidence] n :bytes}]
        (str "\n## " title "\n\n"
             (when (= mode :read) (str (get choices title "Pull: verify the known entity's resulting shape.") "\n\n"))
             "```clojure\n;; " thought "\n" form "\n```\n\n"
             "Actual " (if (= mode :write) "speculative transaction result" "read result")
             ": **" n " UTF-8 bytes**"
             (when (seq evidence) (str "; evidence " (pr-str evidence)))
             ".\n\n```clojure\n" output "\n```\n"))]
  (spit path
        (str prefix
             "\n## Agent-source recheck\n\n"
             "All source forms below use reader quotes, explicit keyword keys, and supplied database custody. "
             "The production `seon.repl/source-text` uses Clojure's `pprint/code-dispatch`; the probe calls that same function. "
             "Each of the 23 bare reads was executed beside its explicit-database form at the same immutable basis; "
             "all returned equal values. Each of the nine printed transactions parsed to identical transaction data "
             "and then ran through `datahike.api/with`. Writes below are the agent's source, never committed to default. "
             "The new report timestamps come from those actual speculative transactions. "
             "[Complete recheck evidence](context_cookbook_rechecked_2026_09_09.edn).\n"
             (str/join (map #(section :read %) (:reads result)))
             "\n## Write examples\n\nThe proposed identity, transaction-time, message, and runtime attributes exist only in the speculative value. "
             "These are dependency-semantic probes, not a claim that the data lane's schema is installed.\n"
             (str/join (map #(section :write %) (:writes result)))
             "\n## Verify the speculative state\n"
             (str/join (map #(section :read %) (:proposed-reads result)))
             "\n## Positioned component proof\n\n"
             "The renderer derives component membership from the handed database schema, sorts counted members when all share a numeric position key, "
             "and preserves set syntax through fitting and emission. An uncounted tail is not traversed to discover positions. "
             "The first regression's lexical order matched its position order; the live opposite-order probe falsified it. "
             "The corrected regression puts those orders in opposition. Live default returned 118 bytes, `cookbook/b` at 1 before `cookbook/a` at 2; "
             "[both ordering and set-preservation checks are true](context_cookbook_set_2026_09_09.edn). "
             "The real plan's six pulled positions changed from 0,2,1,4,3,5 to 0,1,2,3,4,5 at 373 shown bytes. "
             "The corrected isolated print/value gate passed 42 tests / 202 assertions; platform passed 83 / 490. "
             "The print fixture now reads the actual node from `admit-value`, matching its existing cross-process test.\n"))
  (println {:records (count records) :basis (:basis result) :bytes (count (.getBytes (slurp path) "UTF-8"))}))
