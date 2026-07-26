(ns seon.agent.lifecycle
  "Return lifecycle dispositions for the run driver to interpret."
  (:require [clojure.string :as str]
            [seon.schema :as schema]))

(defn- register-schema! [key definition]
  (schema/register! key definition))

(register-schema! ::disposition
  [:enum :wait :completed :pause :resume :terminate])
(register-schema! ::note :string)
(register-schema! ::result :string)
(register-schema! ::target-request
  [:map {:closed true}
   [:seon.agent/id {:optional true} :seon.agent/id]])
(register-schema! ::wait-disposition
  [:map {:closed true}
   [::disposition [:= :wait]]
   [::note ::note]])
(register-schema! ::complete-disposition
  [:map {:closed true}
   [::disposition [:= :completed]]
   [::result ::result]])
(register-schema! ::pause-disposition
  [:map {:closed true}
   [::disposition [:= :pause]]
   [:seon.agent/id {:optional true} :seon.agent/id]])
(register-schema! ::resume-disposition
  [:map {:closed true}
   [::disposition [:= :resume]]
   [:seon.agent/id {:optional true} :seon.agent/id]])
(register-schema! ::terminate-disposition
  [:map {:closed true}
   [::disposition [:= :terminate]]
   [:seon.agent/id :seon.agent/id]])
(register-schema! ::error
  [:map {:closed true}
   [:seon.error/message :string]])
(register-schema! ::complete-response
  [:or ::complete-disposition ::error])

(defn ^{:seon.capability/effect :pure} wait
  "Return a disposition that leaves the current run open and unclaimed."
  {:malli/schema [:=> [:catn [::note ::note]] ::wait-disposition]}
  [note]
  {::disposition :wait
   ::note note})

(defn ^{:seon.capability/effect :pure} complete
  "Return the terminal synthesis for the run driver to commit."
  {:malli/schema [:=> [:catn [::result ::result]] ::complete-response]}
  [result]
  (if (str/blank? result)
    {:seon.error/message "complete requires non-blank synthesis text."}
    {::disposition :completed
     ::result result}))

(defn ^{:seon.capability/effect :pure} pause
  "Return a request to pause the calling or named agent's current run."
  {:malli/schema
   [:function
    [:=> [:catn] ::pause-disposition]
    [:=> [:catn [::request ::target-request]] ::pause-disposition]]}
  ([] {::disposition :pause})
  ([{:seon.agent/keys [id]}]
   (cond-> {::disposition :pause}
     id (assoc :seon.agent/id id))))

(defn ^{:seon.capability/effect :pure} resume
  "Return a request to resume the calling or named agent's current run."
  {:malli/schema
   [:function
    [:=> [:catn] ::resume-disposition]
    [:=> [:catn [::request ::target-request]] ::resume-disposition]]}
  ([] {::disposition :resume})
  ([{:seon.agent/keys [id]}]
   (cond-> {::disposition :resume}
     id (assoc :seon.agent/id id))))

(defn ^{:seon.capability/effect :pure} terminate
  "Return a request to terminate a named agent."
  {:malli/schema
   [:=> [:catn [::agent-id :seon.agent/id]] ::terminate-disposition]}
  [agent-id]
  {::disposition :terminate
   :seon.agent/id agent-id})
