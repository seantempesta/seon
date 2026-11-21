(ns seon.render.canvas.live
  "General agent-perspective live Canvas & work-progress sections (zero `:agent/root` special-casing).
   Every agent — root (no parent, optional children) or specialist (optional parent, optional children) —
   composes the exact same data-driven sections for BOTH Browser UI and Agent Wire Context:
   1. `parent-request-section` — what this agent's parent (or user) requested + assigned `:seon.task/*`
   2. `self-work-section`      — live in-flight ack, expandable Thinking & actions, own merge-gate status
   3. `children-work-section`  — recursive child & delegated sub-agent work + merge-gate pills
   4. `system-pulse-section`   — live benchmark runs, coordinator living hero, Chronicle timeline
   5. `compose-live-canvas`    — lets any agent override or add individual sections without losing the rest."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.canvas.blocks :as cblocks]
            [seon.render.data :as rdata :refer [agent-label first-doc-line]]
            [seon.render.markdown :as rmd :refer [render-activity-fold render-markdown-blocks]]
            [seon.store :as store]))

(defn prompt-headline
  "Derives a crisp single-line headline from a user prompt for the in-flight acknowledgement card."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]
   :cost/ticks 50000}
  [prompt]
  (let [s (str/trim (str (or prompt "")))
        line1 (first (str/split-lines s))]
    (cond
      (str/blank? line1) "Working on your request"
      (> (count line1) 110) (str (subs line1 0 107) "…")
      :else line1)))

(defn- branch-test-summary
  "Reads the latest `:seon.test.run/*` verdict for `branch` from `main-db` via indexed `:avet` lookup."
  [main-db branch]
  (when (and main-db branch (contains? (:schema main-db) :seon.test.run/branch))
    (when-let [dt (first (d/datoms main-db :avet :seon.test.run/branch branch))]
      (let [r (d/pull main-db '[:seon.test.run/pass? :seon.test.run/ran :seon.test.run/cached
                                :seon.test.run/failed-digests] (:e dt))
            failed (count (:seon.test.run/failed-digests r))
            total (+ (long (or (:seon.test.run/ran r) 0))
                     (long (or (:seon.test.run/cached r) 0)))]
        {:status (cond
                   (:seon.test.run/pass? r) :pass
                   (pos? failed) :fail
                   :else :unrun)
         :total total
         :failed failed}))))

(defn agent-branch-progress
  "Derives universal merge-readiness facts (evals, latest human-readable intent, test pass/fail,
   ready/merge state) for ANY agent `aid` using indexed reads only."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/conn] [:maybe :keyword]] :map]}
  [main-db conn aid]
  (let [ag (when (and main-db aid (contains? (:schema main-db) :seon.agent/id))
             (d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/scope :seon.agent/parent
                               :seon.agent/ready? :seon.agent/tested-tx :seon.agent/merged-tx]
                     [:seon.agent/id aid]))
        br (:seon.agent/branch ag)
        bdb (when (and conn br (not= br :main))
              (try (store/head conn br) (catch Throwable _ nil)))
        eval-db (or bdb main-db)
        eval-dts (when (and eval-db aid (contains? (:schema eval-db) :seon.agent.eval/agent))
                   (vec (d/datoms eval-db :avet :seon.agent.eval/agent aid)))
        eval-count (count eval-dts)
        last-dt (when (seq eval-dts) (reduce #(if (>= (:tx %2) (:tx %1)) %2 %1) eval-dts))
        last-ev (when last-dt
                  (d/pull eval-db '[:seon.agent.eval/intent :seon.agent.eval/thought
                                    :seon.agent.eval/error :seon.agent.eval/tx] (:e last-dt)))
        last-intent (or (not-empty (str/trim (str (or (:seon.agent.eval/intent last-ev) ""))))
                        (not-empty (str/trim (str (or (:seon.agent.eval/thought last-ev) "")))))
        last-err (not-empty (str/trim (str (or (:seon.agent.eval/error last-ev) ""))))
        test-sum (when br (branch-test-summary main-db br))
        b-max-tx (long (or (:max-tx bdb) (:seon.agent.eval/tx last-ev) 0))
        merged-tx (long (or (:seon.agent/merged-tx ag) 0))
        ready? (boolean (:seon.agent/ready? ag))
        merged? (and (pos? merged-tx) (or (zero? b-max-tx) (>= merged-tx b-max-tx)))
        stage (cond
                merged? :merged
                (= :fail (:status test-sum)) :tests-red
                (and ready? (= :pass (:status test-sum))) :ready-to-merge
                ready? :testing
                (pos? eval-count) :working
                :else :starting)]
    {:agent-id aid
     :branch br
     :parent (:seon.agent/parent ag)
     :scope (:seon.agent/scope ag)
     :evals eval-count
     :latest-intent (when last-intent (first-doc-line last-intent 84))
     :last-error (when last-err (first-doc-line last-err 84))
     :tests (or test-sum {:status :unrun :total 0 :failed 0})
     :ready? ready?
     :merged? merged?
     :stage stage}))

(defn parent-request-data
  "Pulls what `agent-id`'s optional parent has requested: `:parent-id`, assigned `:task` map,
   and open/recent incoming `:seon.agent.call/*` entries addressed to `agent-id`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :map]]}
  [main-db agent-id]
  (when (and main-db agent-id (contains? (:schema main-db) :seon.agent/id))
    (let [ag (d/pull main-db '[:seon.agent/parent :seon.agent/task :seon.agent/goal]
                     [:seon.agent/id agent-id])
          parent-id (:seon.agent/parent ag)
          tid (:seon.agent/task ag)
          task-ent (when (and tid (contains? (:schema main-db) :seon.task/id))
                     (d/pull main-db '[:seon.task/id :seon.task/title :seon.task/plan :seon.task/scope :seon.task/done?]
                             [:seon.task/id tid]))
          incoming (when (contains? (:schema main-db) :seon.agent.call/callee)
                     (let [all (->> (d/datoms main-db :avet :seon.agent.call/callee agent-id)
                                    (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                            :seon.agent.call/status :seon.agent.call/prompt
                                                            :seon.agent.call/result-schema :seon.agent.call/created-tx] (:e %)))
                                    (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                    vec)
                           pend (filterv #(= :pending (:seon.agent.call/status %)) all)]
                       (if (seq pend) pend (vec (take-last 2 all)))))]
      (when (or parent-id (:seon.task/id task-ent) (seq incoming))
        {:parent-id (or parent-id (:seon.agent.call/caller (first incoming)))
         :task (when (:seon.task/id task-ent) task-ent)
         :incoming (vec incoming)}))))

(defn delegation-progress-tree
  "Builds a unified recursive work-progress tree for ANY agent `agent-id` from BOTH outgoing
   `:seon.agent.call/*` entries AND direct `:seon.agent/parent` child agents, enriching each node
   with live eval activity and universal merge-gate readiness (tests, errors, ready, merged).
   Single source of truth for BOTH Browser UI and Agent Wire Context."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/conn] [:maybe :keyword]] [:maybe [:vector :map]]]}
  [main-db conn agent-id]
  (when (and main-db agent-id)
    (let [has-calls? (contains? (:schema main-db) :seon.agent.call/caller)
          direct-calls (if has-calls?
                         (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                              (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                      :seon.agent.call/callee :seon.agent.call/parent-call
                                                      :seon.agent.call/status :seon.agent.call/prompt
                                                      :seon.agent.call/created-tx] (:e %)))
                              (sort-by #(or (:seon.agent.call/created-tx %) 0))
                              vec)
                         [])
          pending-calls (filterv #(= :pending (:seon.agent.call/status %)) direct-calls)
          call-roots (if (seq pending-calls)
                       pending-calls
                       (when (seq direct-calls)
                         (let [latest-tx (or (:seon.agent.call/created-tx (peek direct-calls)) 0)]
                           (filterv #(>= (or (:seon.agent.call/created-tx %) 0) (- latest-tx 8)) direct-calls))))
          seen-callees (into #{} (keep :seon.agent.call/callee) call-roots)
          parent-children (when (contains? (:schema main-db) :seon.agent/parent)
                            (->> (d/datoms main-db :avet :seon.agent/parent agent-id)
                                 (keep (fn [dt]
                                         (let [c (d/pull main-db '[:seon.agent/id :seon.agent/goal :seon.agent/archived?] (:e dt))
                                               cid (:seon.agent/id c)]
                                           (when (and cid (not (:seon.agent/archived? c)) (not (contains? seen-callees cid)))
                                             {:seon.agent.call/callee cid
                                              :seon.agent.call/status :child
                                              :seon.agent.call/prompt (:seon.agent/goal c "")}))))
                                 vec))
          has-parent-idx? (contains? (:schema main-db) :seon.agent.call/parent-call)
          enrich (fn enrich [c depth]
                   (let [callee (:seon.agent.call/callee c)
                         prog (agent-branch-progress main-db conn callee)
                         kids (when (and has-parent-idx? (:seon.agent.call/id c) (< depth 4))
                                (->> (d/datoms main-db :avet :seon.agent.call/parent-call (:seon.agent.call/id c))
                                     (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                             :seon.agent.call/callee :seon.agent.call/parent-call
                                                             :seon.agent.call/status :seon.agent.call/prompt
                                                             :seon.agent.call/created-tx] (:e %)))
                                     (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                     (mapv #(enrich % (inc depth)))))]
                     (cond-> (assoc c :progress prog)
                       (seq kids) (assoc :children kids))))
          all-roots (into (vec (take-last 8 call-roots)) (take 6 parent-children))]
      (when (seq all-roots)
        (mapv #(enrich % 0) all-roots)))))

(defn- merge-gate-pills
  "Renders universal merge-readiness pills (`[tests · merge/stage]`) for any agent progress map."
  [prog]
  (let [tinfo (:tests prog)
        stage (:stage prog :starting)
        evals (long (or (:evals prog) 0))
        test-badge (case (:status tinfo)
                     :pass (str "tests ✓ " (:total tinfo))
                     :fail (str "tests ✗ " (:failed tinfo) " red")
                     "tests · pending")
        merge-badge (cond
                      (:merged? prog) "merged ✓"
                      (= :ready-to-merge stage) "ready to merge"
                      (:ready? prog) "merge-queued"
                      :else (if (pos? evals) (str evals " eval" (when (not= 1 evals) "s")) "starting"))]
    [[:span {:class (str "pill mono tiny" (when (= :pass (:status tinfo)) " green-text") (when (= :fail (:status tinfo)) " red-text"))}
      test-badge]
     [:span {:class (str "pill mono tiny" (when (:merged? prog) " green-text"))}
      merge-badge]]))

(defn parent-request-section
  "Composable Canvas & Wire section #1: shows what this agent's optional parent requested
   (clickable `↑ parent` ref, incoming `:seon.agent.call/*` prompts, and assigned `:seon.task/*`).
   Returns `nil` for root agents with no parent or task."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (when-let [{:keys [parent-id task incoming]} (:seon.canvas/parent-request ctx)]
    (let [pend-in (filterv #(= :pending (:seon.agent.call/status %)) incoming)]
      [:section {:id "cv-parent-request" :class "card tile-card" :style "padding:12px 14px"}
       [:div {:class "pill-row" :style "justify-content:space-between"}
        [:div {:class "pill-row"}
         [:span {:class "root-kicker"} "Requested by parent"]
         (when parent-id
           [:button {:type "button" :class "link-muted mono tiny strong"
                     :on-click (list 'seon.web/view-agent! parent-id :canvas)}
            (str "↑ " parent-id)])]
        (when (seq pend-in)
          [:span {:class "badge amber"} (str (count pend-in) " open call" (when (not= 1 (count pend-in)) "s"))])]
       (when task
         [:div {:style "margin-top:6px"}
          [:div {:class "pill-row"}
           [:span {:class (str "badge " (if (:seon.task/done? task) "green" "blue"))}
            (if (:seon.task/done? task) "task done" "task")]
           [:strong {:class "tiny"} (:seon.task/title task)]
           [:span {:class "mono tiny muted"} (str (:seon.task/id task))]]
          (when-not (str/blank? (str (:seon.task/plan task)))
            [:p {:class "muted tiny" :style "margin:4px 0 0"} (first-doc-line (:seon.task/plan task) 180)])])
       (when (seq incoming)
         [:ul {:style "margin:6px 0 0 16px;padding:0"}
          (for [c incoming]
            [:li {:class "tiny"}
             [:span {:class (str "badge " (if (= :pending (:seon.agent.call/status c)) "amber" "green"))
                     :style "margin-right:6px"}
              (name (:seon.agent.call/status c :pending))]
             [:span (first-doc-line (:seon.agent.call/prompt c "") 140)]])])])))

(defn- render-work-node
  [node depth]
  (let [st (:seon.agent.call/status node :pending)
        callee (:seon.agent.call/callee node)
        prompt (:seon.agent.call/prompt node "")
        prog (:progress node)
        stage (:stage prog :starting)
        intent (:latest-intent prog)
        err (:last-error prog)
        badge-cls (case st
                    :resolved "green"
                    :failed "red"
                    :cancelled "muted"
                    :child "blue"
                    (if (= :tests-red stage) "red" "amber"))
        [t-pill m-pill] (merge-gate-pills prog)]
    [:div {:style (str "margin-left:" (* depth 16) "px;padding:5px 0;border-left:"
                       (if (pos? depth) "1px solid var(--border-subtle);padding-left:10px" "none"))}
     [:div {:class "pill-row"}
      [:span {:class (str "dot " (if (= :pending st) "pulsing" "live"))}]
      [:button {:type "button" :class "link-muted mono tiny strong"
                :on-click (list 'seon.web/view-agent! callee :canvas)}
       (str callee)]
      [:span {:class (str "badge " badge-cls)} (name st)]
      t-pill
      m-pill
      (when (and intent (or (= :pending st) (= :child st)))
        [:span {:class "tiny"} (str "— " intent)])
      (when (and (not intent) (not (str/blank? prompt)))
        [:span {:class "muted tiny truncate"} (first-doc-line prompt 84)])]
     (when err
       [:div {:class "tiny red-text mono" :style "margin:2px 0 0 18px"}
        (str "err: " err)])
     (for [ch (:children node)]
       (render-work-node ch (inc depth)))]))

(defn render-work-progress
  "Single-source Hiccup renderer for child & delegated subagent work + merge-gate readiness.
   Rendered as interactive HTML in Canvas AND projected via `seon.render/hiccup->text`
   into coordinating agents' `<neighborhood-activity>` context."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]}
  [work-tree]
  (when (seq work-tree)
    (let [flatten-nodes (fn fnodes [xs] (mapcat #(cons % (fnodes (:children %))) xs))
          all (vec (flatten-nodes work-tree))
          total (count all)
          pend (count (filter #(= :pending (:seon.agent.call/status %)) all))
          merged-n (count (filter #(get-in % [:progress :merged?]) all))
          tests-pass-n (count (filter #(= :pass (get-in % [:progress :tests :status])) all))
          tests-red-n (count (filter #(= :fail (get-in % [:progress :tests :status])) all))]
      [:details {:id "cv-delegations" :class "activity-fold" :open (pos? pend)}
       [:summary {:class "activity-summary"}
        [:span {:class "activity-label"}
         (str "Sub-agents · "
              (if (pos? pend)
                (str pend " running / " total " active")
                (str total " tracked"))
              (when (pos? tests-pass-n) (str " · " tests-pass-n " tests green"))
              (when (pos? tests-red-n) (str " · " tests-red-n " tests red"))
              (when (pos? merged-n) (str " · " merged-n " merged")))]]
       [:div {:class "stack-sm" :style "padding:6px 0 2px 4px"}
        (for [n work-tree]
          (render-work-node n 0))]])))

(defn children-work-section
  "Composable Canvas & Wire section #3: shows what this agent's children / delegated subagents
   are working on and their universal merge-gate readiness."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (render-work-progress (or (:seon.canvas/work-tree ctx) (:seon.canvas/outgoing-calls ctx))))

(defn render-in-flight-panel
  "Composable Canvas section #2a: immediate user/parent ack, live pulsing status + 1-click Live
   Thinking toggle, own branch merge-gate pills, live expandable `Thinking & actions` eval steps,
   and active children work while `(:seon.canvas/pending-prompt ctx)` is active."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (when-let [pending-prompt (:seon.canvas/pending-prompt ctx)]
    (let [aid (:seon.agent/id ctx)
          label (agent-label ctx aid)
          active? (boolean (:seon.canvas/active? ctx))
          actions (:seon.canvas/latest-actions ctx)
          self-prog (:seon.canvas/self-progress ctx)
          work-tree (or (:seon.canvas/work-tree ctx) (:seon.canvas/outgoing-calls ctx))
          pend-n (count (filter #(= :pending (:seon.agent.call/status %)) work-tree))
          headline (prompt-headline pending-prompt)
          long-prompt? (> (count (str/trim (str pending-prompt))) (+ (count headline) 8))
          [t-pill m-pill] (when self-prog (merge-gate-pills self-prog))]
      [:section {:id "cv-pending" :class "ch-pending-card canvas-pending"}
       [:div {:class "ch-pending-head"}
        [:div {:class "pill-row"}
         [:span {:class "root-kicker"} (str "In flight · " label)]
         t-pill
         m-pill]
        [:div {:class "pill-row"}
         [:button {:type "button" :class "ch-pending-status" :data-on:click "$showThinking = !$showThinking"}
          [:span {:class "dot pulsing" :aria-hidden "true"}]
          [:span (cond
                   (pos? pend-n) (str "Coordinating · " pend-n " sub-agent" (when (not= 1 pend-n) "s"))
                   (seq actions) (str "Working · " (count actions) " step" (when (not= 1 (count actions)) "s"))
                   active? "Thinking…"
                   :else "Acknowledged · waking agent…")]]
         [:button {:type "button" :class "link-muted tiny" :data-on:click "$statusOpen = !$statusOpen"}
          "Activity"]]]
       [:h1 {:class "ch-pending-title canvas-prompt-headline"} headline]
       (when long-prompt?
         [:details {:class "ch-prompt-more"}
          [:summary "Full request"]
          [:div {:class "ch-prompt-more-body ch-hero-body"} (render-markdown-blocks pending-prompt)]])
       (if (seq actions)
         (render-activity-fold actions "cv-live")
         [:p {:class "muted tiny" :style "margin:8px 0 0"}
          "Thinking — live eval steps appear here as they run (click Thinking to watch live tokens)."])
       (when (seq work-tree)
         (render-work-progress work-tree))])))

(defn self-work-section
  "Composable Canvas section #2: when in flight shows `render-in-flight-panel`; when settled on
   a non-`:main` branch shows the agent's own merge-gate status bar + expandable eval steps."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (if (:seon.canvas/pending-prompt ctx)
    (render-in-flight-panel ctx)
    (let [prog (:seon.canvas/self-progress ctx)
          br (:branch prog)]
      (when (and prog br (not= br :main) (or (pos? (long (or (:evals prog) 0))) (:ready? prog) (:merged? prog)))
        (let [[t-pill m-pill] (merge-gate-pills prog)]
          [:div {:id "cv-self-progress" :class "pill-row" :style "padding:6px 10px;border:1px solid var(--border-subtle);border-radius:10px"}
           [:span {:class "mono tiny muted"} (str br)]
           t-pill
           m-pill
           (when-let [intent (:latest-intent prog)]
             [:span {:class "tiny muted truncate"} (str "— " intent)])])))))

(defn system-pulse-section
  "Composable Canvas section #4: live benchmark runs, coordinator Living Hero (status/problems),
   and Chronicle timeline."
  {:malli/schema [:=> [:cat :map] :any]}
  [ctx]
  (let [living (:seon.canvas/living ctx)
        as-of-tx (:seon.canvas/as-of-tx ctx)]
    (when living
      [:div {:class "stack"}
       (when (seq (:bench-runs living))
         (cblocks/render-bench-runs (:bench-runs living) (:main-ms living)))
       (cblocks/render-living-hero living {:primary-heading? false :hero-title (:seon.agent/hero-title ctx)})
       (cblocks/render-chronicle-stream (:entries living) {:as-of-tx as-of-tx})])))

(defn compose-live-canvas
  "General composable Canvas builder for ANY agent overriding `:seon.agent/render-fn`.
   Pass either a Hiccup `custom` body OR a slot-override map `{:parent fn :self fn :visual fn-or-hiccup :children fn :pulse fn :extra [...]}`
   so agents can evolve individual sections cleanly without throwing away parent requests,
   live in-flight progress, children merge-gate status, or system health/Chronicle."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [ctx custom]
  (let [slots (if (and (map? custom) (some #(contains? custom %) [:parent :self :visual :children :pulse :extra]))
                custom
                {:visual custom})
        run-slot (fn [k default-fn]
                   (let [v (get slots k ::default)]
                     (cond
                       (nil? v) nil
                       (= v ::default) (when default-fn (default-fn ctx))
                       (fn? v) (v ctx)
                       :else v)))
        living (:seon.canvas/living ctx)
        actions (:seon.canvas/latest-actions ctx)
        pending? (boolean (:seon.canvas/pending-prompt ctx))]
    [:div {:class "stack"}
     (when living (cblocks/render-chronicle-style))
     (run-slot :parent parent-request-section)
     (run-slot :self (when-not pending? self-work-section))
     (run-slot :visual nil)
     (for [ex (:extra slots)] (if (fn? ex) (ex ctx) ex))
     (when-not pending? (run-slot :children children-work-section))
     (run-slot :pulse system-pulse-section)
     (when (and (not pending?) (seq actions))
       (render-activity-fold actions "cv"))]))
