(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)            what draws what in this agent's live walk, from its root: {:render :args :id :tokens :reads? :children}
    (my.render/render-ctx)      the whole render of your ctx, every component as the hiccup your request holds, whole
    (my.render/render-changes)  what moved since you last asked, as edits; nothing when nothing did
    (my.render/diff p0 p1)      what moved between two points of your branch, as edits: the page at p0 taken to the page at p1"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.render :as render] [seon.repl :as repl] [seon.ctx.wire :as wire] [seon.ctx] [seon.agent :as agent] [datahike.api :as d]))

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

(defn diff
  "What moved of your page between point p0 and point p1 of your branch ({:branch :commit :t}; p0 nil: nothing before it, so each part whole, [[id] :+ form]), as edits [[path op value] ...], each path starting with the part's id: the page drawn at each point with today's renders and the edits `seon.render/page-edits` makes of the two. Pure in the points: the same points and renders give the same edits, which the model reads for each turn between the points of its two requests (:seon.model-call/basis). The same edits your turn's page message prints (seon.ctx.wire/page-edits-between). See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat [:maybe [:map [:branch :any] [:commit :uuid] [:t :int]]] [:map [:branch :any] [:commit :uuid] [:t :int]]] [:or [:vector :any] :map]]}
  [p0 p1] (wire/page-edits-between (agent/db) (agent/branch-index) (:db/id @seon.ctx/ctx) p0 p1))
