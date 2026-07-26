(ns seon.ai.attempt
  "Define durable model-attempt evidence schemas."
  (:require
    [seon.ai.provider]
    [seon.db.id :as db.id]
    [seon.schema :as schema]))

(schema/register!
  ::id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! ::ordinal [:int {:min 0}])
(schema/register! ::config-digest [:string {:min 64 :max 64}])
(schema/register! ::deadline-at :inst)
(schema/register! ::provider :seon.ai/provider)
(schema/register!
  ::adapter
  [:enum :openai-compat :anthropic :diffusiongemma :typeahead :stub])
(schema/register! ::requested-model [:string {:min 1}])
(schema/register! ::temperature :double)
(schema/register! ::max-tokens [:int {:min 1}])
(schema/register! ::thinking [:string {:min 1}])
(schema/register! ::endpoint [:string {:min 1}])
(schema/register! ::adapter-timeout-ms [:int {:min 1}])
(schema/register! ::outer-timeout-ms [:int {:min 1}])
(schema/register! ::stream? :boolean)
(schema/register! ::reply-evaluation [:enum :first-form :batch])
(schema/register! ::partial-text
                  [:string {:seon.db/no-history? true}])
(schema/register! ::extra-body-digest [:string {:min 64 :max 64}])
(schema/register! ::dg-backend [:enum :vllm :control])
(schema/register! ::api-key-env [:string {:min 1}])
(schema/register!
  ::credential-class
  [:enum :configured-env :provider-default-env :conventional-env])
(schema/register!
  ::outcome
  [:enum :open :success :provider-error :adapter-timeout
   :outer-timeout :crashed])
(schema/register! ::error-status :int)
(schema/register! ::response-model [:string {:min 1}])
(schema/register! ::system-fingerprint [:string {:min 1}])
(schema/register! ::request-id [:string {:min 1}])
(schema/register! ::evidence-error [:string {:min 1}])

(schema/register!
  ::entity
  [:map {:seon.db/entity true}
   [::id ::id]
   [::ordinal ::ordinal]
   [::config-digest ::config-digest]
   [::deadline-at ::deadline-at]
   [::provider ::provider]
   [::adapter ::adapter]
   [::requested-model {:optional true} ::requested-model]
   [::temperature {:optional true} ::temperature]
   [::max-tokens {:optional true} ::max-tokens]
   [::thinking {:optional true} ::thinking]
   [::endpoint {:optional true} ::endpoint]
   [::adapter-timeout-ms {:optional true} ::adapter-timeout-ms]
   [::outer-timeout-ms ::outer-timeout-ms]
   [::stream? ::stream?]
   [::reply-evaluation ::reply-evaluation]
   [::partial-text {:optional true} ::partial-text]
   [::extra-body-digest {:optional true} ::extra-body-digest]
   [::dg-backend {:optional true} ::dg-backend]
   [::api-key-env {:optional true} ::api-key-env]
   [::credential-class {:optional true} ::credential-class]
   [::outcome ::outcome]
   [::error-status {:optional true} ::error-status]
   [::response-model {:optional true} ::response-model]
   [::system-fingerprint {:optional true} ::system-fingerprint]
   [::request-id {:optional true} ::request-id]
   [::evidence-error {:optional true} ::evidence-error]])
