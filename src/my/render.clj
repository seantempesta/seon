(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)            what draws what in this agent's live walk, from its root: {:render :args :id :tokens :reads? :children}
    (my.render/render-ctx)      the whole render of your ctx, every component as the hiccup your request holds, whole
    (my.render/render-changes)  what moved since you last asked, as edits; nothing when nothing did
    (my.render/redo-changes)    your page's evals made true again after you removed or re-ran one"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.render :as render] [seon.repl :as repl] [seon.agent :as agent] [seon.context :as context] [seon.ctx :as ctx] [datahike.api :as d]))

(defn tree
  "What draws what in this agent's live walk (the one its model's page and its person's page are drawn from, brought up to
  date now), from its root, as data: each component {:render :args :id :tokens :reads? :children}, :tokens its own output's by the
  one sizer (`seon.render/tree`). The lists drawn so far: the model's, and every other once a person's page has asked."
  {:malli/schema [:=> [:cat] [:maybe :seon.render/tree]]}
  [] (render/tree (repl/page-walk)))

(defn- page
  "The page's value as of now (`seon.render/page-value`), whole or what moved since it was last shown; nothing moved, which eval showed it, by the handle the agent reads."
  {:malli/schema [:=> [:cat :boolean] :seon.render/parts]}
  [whole?]
  (let [w (repl/page-walk) db (agent/db)
        v (render/page-value w (render/page-lists w) (:max-tx db) whole? (:eval @agent/state))]
    (cond-> v
      (:shown-by v) (assoc :shown-by (get-in (d/pull db [:seon.eval/response] [:seon.eval/id (:shown-by v)]) [:seon.eval/response :handle])))))

(defn render-ctx
  "Your ctx rendered, whole: {:components [[id [render-name {:ctx path} hiccup...]] ...]}, every component of your page in placed order, the hiccup your request holds each as. A value like any eval's, kept whole in its record and printed whole. Seon runs it as your first eval, and you can
  run it any time to read your page whole; what it showed is what `render-changes` compares against. See docs/render.md#the-render-is-an-eval."
  {:malli/schema [:=> [:cat] :seon.render/parts]}
  [] (page true))

(defn render-changes
  "What moved on your page since you were last shown it (by `render-ctx` or by this), {:edits [[path op value] ...]}: each path starting with the component's id and going on in names, or the page whole if this process has shown you none. Nothing moved since you were shown it: {:edits [] :shown-by handle}, the eval that showed it. Seon runs it as an eval of its own when something did. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat] :seon.render/parts]}
  [] (page false))

(defn redo-changes
  "Your page's evals made true again after you removed or re-ran one: each whose :since is no longer what the eval before it showed answers anew, in place, what moved between them, drawn from the page as of each t. An explicit bust of your model's cache from the first it changes. Answers the ids it changed. See docs/render.md#a-redo-is-an-explicit-bust."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (let [db (agent/db) tx (context/redo-tx db (:db/id @ctx/ctx) @ctx/ctx)]
    (when (seq tx) (agent/transact! tx) (agent/commit!))
    (mapv :seon.eval/id tx)))
