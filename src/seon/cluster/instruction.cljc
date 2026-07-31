(ns seon.cluster.instruction
  "Cluster-owned instruction facts and their verbatim family renders."
  (:require [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(def instruction-ids
  "The four cluster-owned instruction identities seeded at initialization."
  [:reply-grammar :messaging :declining :global])

(def ^:private reply-grammar
  (str "Reply with Clojure forms to run, in order. "
       "Finish with (my.run/complete \"your reply\") when you are "
       "done, or (my.run/wait \"why\") to pause this run — pause "
       "when you are waiting on another agent, and put everything "
       "you will need to finish into the note, because your next "
       "run starts fresh and that note is what it reads."))

(def ^:private messaging
  (str "To ask another agent for something, return "
       "(my.message/send \"agent-id\" \"what you want to say\") from a "
       "form — that delivers it and wakes them. Use the bare agent id; "
       "it is not a namespace. Their answer comes back to you later as a "
       "new request, so pause with my.run/wait after asking. Return a "
       "vector of sends to message several."))

(def ^:private declining
  (str "Repair an assigned problem in your own namespace and say what you "
       "did. If you cannot — the code is not yours to change, or nothing "
       "in your namespace could satisfy it — return "
       "(my.message/decline \"assigner-agent-id\" \"problem-id\" "
       "\"why you cannot\"), naming the assigning agent and problem "
       "identity exactly as rendered. Declining settles the problem as "
       "answered; saying nothing leaves it open forever."))

(defn seed-rows
  "Instruction rows seeded into the published source database."
  {:malli/schema
   [:=> [:cat :seon.cluster.instruction/seed-request]
    :seon.cluster.instruction/seed-rows]}
  [{global-text :seon.cluster.instruction/global-text}]
  [{:seon.cluster.instruction/id :reply-grammar
    :seon.cluster.instruction/text reply-grammar}
   {:seon.cluster.instruction/id :messaging
    :seon.cluster.instruction/text messaging}
   {:seon.cluster.instruction/id :declining
    :seon.cluster.instruction/text declining}
   {:seon.cluster.instruction/id :global
    :seon.cluster.instruction/text global-text}])

(defn instruction-ai
  "The instruction's text, verbatim."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (:seon.cluster.instruction/text unit))

(defn instruction-html
  "The instruction's text in a minimal HTML family render."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  [:article {:class "seon-family-entry seon-instruction-entry"}
   [:p (instruction-ai unit)]])
