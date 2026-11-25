(ns seon.render.entities.graph
  "Pure Hiccup renderers for collapsed chain-boundary witnesses, contract blame cuts,
   and exact doc-atom cards with zero-cost Known-Set back-references."
  (:require [clojure.string :as str]
            [seon.render.text :refer [clip]]))

(defn format-known-backref
  "Formats a zero-repetition inline back-reference `(see Turn k / result/e<n>)` for an entity already in Known Set `K_t`."
  {:malli/schema [:=> {:examples [[[{:label "seon.ctx/project-entity" :turn 2 :handle 'result/e4}]
                                   "seon.ctx/project-entity (see Turn 2 / result/e4)"]]}
                  [:cat [:map [:label :string] [:turn {:optional true} [:maybe :int]] [:handle {:optional true} [:maybe :any]]]]
                  :string]
   :cost/ticks 100000}
  [{:keys [label turn handle]}]
  (let [parts (remove nil? [(when (and (integer? turn) (pos? (long turn))) (str "Turn " turn))
                            (when handle (str handle))])]
    (if (seq parts)
      (str label " (see " (str/join " / " parts) ")")
      label)))

(defn render-chain-boundary
  "Pure renderer for `:seon.graph/chain-boundary`: collapses `[f1 -> ... -> fk]` into entry->exit contract `A0 -> Ak`,
   1 concrete `seed=42` witness `(sample-in -> sample-out)`, unseen doc atoms, and Known-Set back-references."
  {:malli/schema [:=> [:cat [:map [:seon.graph/chain-boundary :seon.graph/chain-boundary]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:keys [steps input-schema output-schema input-type output-type sample-in sample-out known-refs doc-atoms]} :seon.graph/chain-boundary}]
  (let [chain-str (if (seq steps) (str/join " -> " (map str steps)) "(empty chain)")
        sig-str (str (or input-schema input-type :any) " -> " (or output-schema output-type :any))]
    {:seon.ui/hiccup
     [:div {:class "card tile-card" :data-min-width 360}
      [:div {:class "card-head"}
       [:span {:class "badge blue"} "chain-boundary"]
       [:strong {:class "mono"} sig-str]]
      [:p {:class "tiny mono muted"} chain-str]
      (when (or (not (str/blank? (str sample-in))) (not (str/blank? (str sample-out))))
        [:pre {:class "code-box tiny"}
         [:code (str "witness: " (clip (or sample-in "?") 160) " => " (clip (or sample-out "?") 160))]])
      (when (seq known-refs)
        [:p {:class "tiny muted"}
         (str "Already known: " (str/join ", " (map format-known-backref known-refs)))])
      (when (seq doc-atoms)
        (into [:ul {:class "tiny"}]
              (for [{:keys [id heading]} (take 6 doc-atoms)]
                [:li [:code (str id)] (when (not (str/blank? (str heading))) (str " — " (clip heading 80)))])))]}))

(defn render-blame-cut
  "Pure renderer for localized contract/exception blame: shows `[f1 -> ... -> FAILED AT fi]`, blame badge,
   minimal witness (valid example on `:input-contract`, failing args on `:output-contract`/`:exception`),
   and zooms into cut-point source ONLY when blame is not `:input-contract`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]
                             [:seon.code.var/entity {:optional true} [:maybe :map]]
                             [:seon.schema/entity {:optional true} [:maybe :map]]
                             [:seon.fix/entity {:optional true} [:maybe :map]]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{err :seon.error/entity var-ent :seon.code.var/entity sch-ent :seon.schema/entity fix-ent :seon.fix/entity}]
  (let [{:seon.error/keys [kind message blame var call-chain input-sample]} err
        eff-blame (or blame :exception)
        input-blame? (= :input-contract eff-blame)
        chain-txt (when (seq call-chain)
                    (if (> (count call-chain) 1)
                      (str (str/join " -> " (map str (butlast call-chain))) " -> FAILED AT " (last call-chain))
                      (str "FAILED AT " (first call-chain))))]
    {:seon.ui/hiccup
     [:div {:class "card tile-card" :data-min-width 420}
      [:div {:class "card-head"}
       [:span {:class (if input-blame? "badge amber" "badge red")} (name eff-blame)]
       [:strong {:class "mono"} (str (or var kind :error/unknown))]]
      (when chain-txt [:p {:class "tiny mono muted"} chain-txt])
      (when-not (str/blank? (str message))
        [:div {:class "notice red sm-copy"} (clip message 320)])
      (when-not (str/blank? (str input-sample))
        [:pre {:class "code-box tiny"}
         [:code (str (if input-blame? "valid example args: " "failing witness: ") (clip input-sample 240))]])
      (when (and sch-ent (:seon.schema/key sch-ent))
        [:pre {:class "code-box tiny"}
         [:code (str (:seon.schema/key sch-ent) " = " (clip (pr-str (:seon.schema/form sch-ent)) 280))]])
      (when (and (not input-blame?) var-ent (:seon.code.var/name var-ent))
        [:p {:class "tiny mono"}
         (str "cut-point: " (:seon.code.var/name var-ent)
              (when (:seon.code.var/schema var-ent) (str " " (clip (pr-str (:seon.code.var/schema var-ent)) 160))))])
      (when (and fix-ent (:seon.fix/change-summary fix-ent))
        [:p {:class "tiny muted"} (str "Known fix: " (clip (:seon.fix/change-summary fix-ent) 200))])]}))