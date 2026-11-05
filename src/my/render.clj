(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)            what draws what in this agent's live walk, from its root: {:render :args :id :tokens :reads? :children}
    (my.render/render-ctx)      the whole render of your ctx, every part as the hiccup your request holds, whole
    (my.render/render-changes)  the parts that moved since you last asked, whole; nothing when none did"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.render :as render] [seon.repl :as repl] [seon.agent :as agent] [seon.context :as context]))

(defn tree
  "What draws what in this agent's live walk (the one its model's page and its person's page are drawn from, brought up to
  date now), from its root, as data: each component {:render :args :id :tokens :reads? :children}, :tokens its own output's by the
  one sizer (`seon.render/tree`). The lists drawn so far: the model's, and every other once a person's page has asked."
  {:malli/schema [:=> [:cat] [:maybe :seon.render/tree]]}
  [] (render/tree (repl/page-walk)))

(defn- parts-value
  "Parts m ({id form}) as the value an eval answers: each form ready to print (`seon.render/printable`), the map named a
  :seon.render/parts, which prints whole (:seon.print/full on its schema), the stored value too."
  {:malli/schema [:=> [:cat :map] :map]}
  [m] (with-meta (into (array-map) (map (fn [[id f]] [id (render/printable f)])) m) {:seon.render/schema :seon.render/parts :seon.print/full true}))

(defn render-ctx
  "Your ctx rendered: every part of your page, {id [render-name {:ctx path} hiccup...]}, the hiccup your request holds each part as
  (and the turn line, which a request ends with). A value like any eval's, kept whole in its record and printed whole. It
  brings the walk up to date and answers what is drawn; the same as `render-changes` on a first ask."
  {:malli/schema [:=> [:cat] :seon.render/parts]}
  [] (parts-value (render/placed-hiccup (repl/page-walk) context/page-lists)))

(defn render-changes
  "The parts of your page that moved since you last asked this (or all of them, the first time or after a restart),
  {id [render-name {:ctx path} hiccup...]}, each whole; a part that left is {id :gone}. Nothing moved: {}, and nothing
  was drawn to say so (the walk compares each part's output once, when it draws it)."
  {:malli/schema [:=> [:cat] :seon.render/parts]}
  []
  (let [{:keys [parts revs gone]} (render/parts-since (repl/page-walk) context/page-lists (:render-seen @agent/state))]
    (swap! agent/state assoc :render-seen revs)
    (parts-value (into parts (map (fn [id] [id :gone])) gone))))
