(ns seon.host.session
  "Define portable values carried over one guarded host session."
  (:require [seon.db.protocol :as db.protocol]
            [seon.schema :as schema]))

(def protocol-version 3)
(def maximum-result-bytes (- db.protocol/maximum-frame-bytes (* 64 1024)))
(def invoke-message :seon.execution.message/invoke)
(def cancel-message :seon.execution.message/cancel)
(def ready-message :seon.execution.message/ready)
(def result-message :seon.execution.message/result)
(def error-message :seon.execution.message/error)

(schema/register! ::protocol-version [:= protocol-version])
(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::invocation-id [:string {:min 1}])
(schema/register! ::function-symbol :qualified-symbol)
(schema/register! ::digest [:re "^[0-9a-f]{64}$"])
(schema/register! ::function-identity
                  [:or
                   [:map {:closed true}
                    [:seon.execution/function-symbol ::function-symbol]
                    [:seon.execution/source-digest ::digest]]
                   [:map {:closed true}
                    [:seon.execution/function-symbol ::function-symbol]
                    [:seon.execution/artifact-digest ::digest]]])
(schema/register! ::database-selection
                  [:map
                   [:seon.db/socket-path [:string {:min 1}]]
                   [:seon.db/database-name [:string {:min 1}]]])
(schema/register! ::startup
                  [:map {:closed true}
                   [:seon.execution/protocol-version ::protocol-version]
                   [:seon.execution/agent-id ::agent-id]
                   [:seon.launch/execution-digest ::digest]
                   [:seon.launch/application-digest ::digest]
                   [:seon.execution/database-selection ::database-selection]])
(schema/register! ::invoke
                  [:map
                   [:seon.execution/message [:= invoke-message]]
                   [:seon.execution/protocol-version ::protocol-version]
                   [:seon.execution/agent-id ::agent-id]
                   [:seon.execution/invocation-id ::invocation-id]
                   [:seon.db/db :seon.db/db]
                   [:seon.execution/function-identity ::function-identity]
                   [:seon.execution/arguments [:vector :any]]
                   [:seon.execution/deadline-ms [:int {:min 0}]]
                   [:seon.execution/result-limit-bytes [:int {:min 1
                                                              :max maximum-result-bytes}]]
                   [:seon.execution/run-fence {:optional true}
                    [:map-of :qualified-keyword :any]]])
(schema/register!
 ::ready
 [:map {:closed true}
  [:seon.execution/message [:= ready-message]]
  [:seon.execution/protocol-version ::protocol-version]
  [:seon.execution/agent-id ::agent-id]
  [:seon.launch/execution-digest ::digest]
  [:seon.launch/application-digest ::digest]
  [:seon.db/db :seon.db/db]])
(schema/register!
 ::result
 [:map {:closed true}
  [:seon.execution/message [:= result-message]]
  [:seon.execution/protocol-version ::protocol-version]
  [:seon.execution/invocation-id ::invocation-id]
  [:seon.db/db :seon.db/db]
  [:seon.execution/result :any]
  [:seon.execution/result-bytes
   [:int {:min 1 :max maximum-result-bytes}]]])
(schema/register!
 ::error
 [:map {:closed true}
  [:seon.execution/message [:= error-message]]
  [:seon.execution/protocol-version ::protocol-version]
  [:seon.execution/invocation-id ::invocation-id]
  [:seon.db/db {:optional true} :seon.db/db]
  [:seon.execution/error
   [:map {:closed true}
    [:seon.error/message [:string {:min 1}]]
    [:seon.error/kind :keyword]
    [:seon.error/data {:optional true} :map]]]])
