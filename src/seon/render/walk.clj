(ns seon.render.walk
  "Incremental `step-walk` `#seon-view-unit` shell composition and SSE patch framing."
  (:require [clojure.string :as str]
            [seon.render :as render]
            [seon.render.data :as rdata]))

(def shell-unit-ids
  "Mounted non-view shell unit IDs whose DOM roots live outside `#seon-view-unit`."
  #{"seon-css" "seon-header" "seon-sidebar" "seon-stream-unit" "seon-composer-unit"})

(defn discrete-view-transition?
  "True when a Datastar SSE broadcast should enable `useViewTransition true`:
   fires ONLY on discrete UI transitions (`:view-mode`, `:viewing-id`, `:as-of-tx`, `:viewport-w`,
   `:inspect-ref`, `:lab-prefs`, or `:action-done`), NEVER on `:stream` token ticks."
  {:malli/schema [:=> [:cat [:maybe [:vector :any]] [:maybe [:vector :any]] :any] :boolean]
   :cost/ticks 50000
   :cost/bytes 4096}
  [prev-sig cur-sig reason]
  (boolean
   (and (not= reason :stream)
        (or (= reason :action-done)
            (and (some? prev-sig) (not= prev-sig cur-sig))))))

(defn compose-view-html
  "Assembles `#seon-view-unit` HTML from mounted `walk` units for initial SSR and structural transitions."
  {:malli/schema [:function
                  [:=> [:cat :map] :string]
                  [:=> [:cat :map :any] :string]
                  [:=> [:cat :map [:maybe :keyword] [:maybe :keyword]] :string]]}
  ([walk] (compose-view-html walk nil nil))
  ([walk db]
   (let [vs (when db (rdata/user-view-state db))]
     (compose-view-html walk (:viewing-id vs) (:view-mode vs))))
  ([walk _viewing-id _view-mode]
   (get-in walk [:units "seon-view-unit" :html] "")))

(defn effective-patches
  "Returns SSE patches for `step-walk`, morphing `#seon-view-unit` cleanly whenever mounted unit order changes."
  {:malli/schema [:function
                  [:=> [:cat :map :map [:vector :string] :any] [:vector :string]]
                  [:=> [:cat :map :map [:vector :string] :any :boolean] [:vector :string]]]}
  ([prev-walk walk patches db]
   (effective-patches prev-walk walk patches db false))
  ([prev-walk walk patches db use-vt?]
   (let [prev-ord (:order prev-walk)
         new-ord (:order walk)]
     (if (and (seq prev-ord) (not= prev-ord new-ord))
       (let [shell-patches (filterv (fn [p]
                                      (let [head (subs p 0 (min (count p) 220))]
                                        (some #(str/includes? head (str "id=\"" % "\"")) shell-unit-ids)))
                                    patches)
             view-html (compose-view-html walk db)]
         (if (str/blank? view-html)
           shell-patches
           (conj shell-patches (render/datastar-patch-event view-html nil nil (boolean use-vt?)))))
       patches))))

(defn compose-keyframe-html
  "Assembles full page unit HTML sequence (wrapping `#seon-view-unit`) for keyframe/reconnect broadcasts."
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map :any] :string]]}
  ([walk] (compose-keyframe-html walk nil))
  ([walk db]
   (let [uhtml (fn [id] (get-in walk [:units id :html] ""))]
     (str (uhtml "seon-css")
          (uhtml "seon-header")
          (uhtml "seon-sidebar")
          (compose-view-html walk db)
          (uhtml "seon-stream-unit")
          (uhtml "seon-composer-unit")))))
