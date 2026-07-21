(ns seon.ai.dispatch
  "Effective-provider dispatch for the agent LLM boundary.

   Provider selection comes from the immutable
   `:seon.ai/config-resolution` captured at the prompt database value. Missing
   credentials select the deterministic stub; missing resolution is an
   explicit error value."
  (:require
    [seon.ai :as ai]
    [seon.ai.provider :as provider]
    [seon.ai.tokens :as tokens]
    [seon.schema :as schema]))

(schema/register! ::llm-fn 'fn?)
(schema/register! ::configured? 'fn?)
(schema/register! ::agent-adapter 'fn?)
(schema/register! ::provider-descriptor
  [:map
   [::configured? ::configured?]
   [::agent-adapter ::agent-adapter]])
(schema/register! ::provider-descriptors
  [:map-of :seon.ai/provider ::provider-descriptor])
(schema/register! ::provider-registration
  [:map-of :keyword ::provider-descriptor])
(schema/register! ::registration-error
  [:map
   [:seon/error
    [:map
     [:seon.error/kind [:= :user-input]]
     [:seon.error/message :string]
     [:seon.error/data :map]]]])
(schema/register! ::registration-result
  [:or ::provider-descriptors ::registration-error])

(defonce ^:private !providers (atom {}))

(defn registered-providers
  "The descriptors registered by namespaces loaded in this process."
  {:malli/schema [:=> [:cat] ::provider-descriptors]}
  []
  @!providers)

(defn register-providers!
  "Register loaded provider descriptors, rejecting unknown provider ids."
  {:malli/schema
   [:=> [:catn [::descriptors ::provider-registration]] ::registration-result]}
  [descriptors]
  (let [unknown (into #{} (remove #(contains? provider/provider-locality %))
                      (keys descriptors))]
    (if (seq unknown)
      {:seon/error
       {:seon.error/kind :user-input
        :seon.error/message
        (str "Provider registration rejected; declare provider locality first: "
             (pr-str unknown))
        :seon.error/data {::unknown-providers unknown}}}
      (swap! !providers merge descriptors))))

;; `:text` is the established turn-loop adapter result key. Provider adapters
;; may add raw/error fields; the deterministic stub returns only this minimum.
(schema/register! ::stub-response
  [:map [:text :string] [:seon.ai/adapter :seon.ai/adapter]])

(defn stub
  "Return the deterministic no-credentials LLM reply."
  {:malli/schema [:=> [:catn [::request :seon.ai/request]] ::stub-response]}
  [request]
  (let [ctx      (:seon.ai/ctx request)
        selected (get-in request [:seon.ai/config-resolution
                                  :seon.ai/resolved-config
                                  :seon.ai/provider])
        missing? (and selected (nil? (get (registered-providers) selected)))
        text (str
               (if missing?
                 (str ";; stub LLM here — provider " (pr-str selected)
                      " is not registered in this build\n")
                 ";; stub LLM here — the real one needs DEEPSEEK_API_KEY\n")
               ";; say hello to your human via the message/user function\n"
               "(message/user\n"
               "  "
               (pr-str (str "hello from the stub LLM — saw "
                            (tokens/estimate ctx) " tokens of ctx"))
               ")\n")]
    (.then (.resolve js/Promise nil)
           (fn [_] {:text text :seon.ai/adapter :stub}))))

(defn adapter
  "The agent adapter selected by one authority config resolution."
  {:malli/schema [:=> [:cat :seon.ai/config-resolution] ::llm-fn]}
  [resolution]
  (let [provider (get-in resolution [:seon.ai/resolved-config
                                     :seon.ai/provider])
        descriptor (get @!providers provider)]
    (if (and descriptor ((::configured? descriptor) resolution))
      ((::agent-adapter descriptor))
      stub)))

(defn- invalid-request
  []
  (.resolve js/Promise
            {:text ""
             :seon.ai/error
             {:seon.ai/msg
              "invalid LLM request — pass one closed :seon.ai/request map with :seon.ai/ctx and :seon.ai/config-resolution"}}))

(defn llm-fn
  "Build a per-call dispatching agent LLM function."
  {:malli/schema [:=> [:cat] ::llm-fn]}
  []
  (fn [request]
    (if (schema/valid-candidate-value? :seon.ai/request request)
      ((adapter (:seon.ai/config-resolution request)) request)
      (invalid-request))))
