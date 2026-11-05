(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)            what draws what in this agent's live walk, from its root: {:fn :args :id :tokens :reads? :children}
    (my.render/render-ctx)      the whole render of your ctx, every part as the hiccup your request holds, whole
    (my.render/render-changes)  the parts that moved since you last asked, whole; nothing when none did"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.render :as render] [seon.repl :as repl] [seon.agent :as agent] [seon.context :as context]))

(defn tree
  "What draws what in this agent's live walk (the one its model's page and its person's page are drawn from, brought up to
  date now), from its root, as data: each component {:fn :args :id :tokens :reads? :children}, :tokens its own output's by the
  one sizer (`seon.render/tree`). The lists drawn so far: the model's, and every other once a person's page has asked."
  {:malli/schema [:=> [:cat] [:maybe :seon.render/tree]]}
  [] (render/tree (repl/page-walk)))

(defn- since
  "The parts of your page that moved since seen ({id revision} of what you were last shown; nil: nothing, so every part), as the
  value an eval answers (`seon.render/parts-value`): {id [render-name {:ctx path} hiccup...]}, each whole, a part that left {id :gone}.
  What you are shown is now what you were last shown. See docs/render.md#the-render-is-an-eval."
  {:malli/schema [:=> [:cat [:maybe :map]] :seon.render/parts]}
  [seen]
  (let [{:keys [parts revs gone]} (render/parts-since (repl/page-walk) context/page-lists seen)]
    (swap! agent/state assoc :render-seen revs)
    (render/parts-value (into parts (map (fn [id] [id :gone])) gone))))

(defn render-ctx
  "Your ctx rendered: every part of your page, {id [render-name {:ctx path} hiccup...]}, the hiccup your request holds each part as
  (and the turn line). A value like any eval's, kept whole in its record and printed whole. Seon runs it as your first eval, and you can
  run it any time to read your page whole; what it showed is what `render-changes` compares against."
  {:malli/schema [:=> [:cat] :seon.render/parts]}
  [] (since nil))

(defn render-changes
  "The parts of your page that moved since you were last shown it (by `render-ctx` or by this), {id [render-name {:ctx path} hiccup...]},
  each whole; a part that left is {id :gone}. Nothing moved: {}. Seon runs it as an eval of its own when something did."
  {:malli/schema [:=> [:cat] :seon.render/parts]}
  [] (since (:render-seen @agent/state)))
