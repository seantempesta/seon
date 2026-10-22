(ns seon.render-test
  "Renders (session W2): selection (override key, schema-declared, printer), root's AI face,
  and the render proc's step as a plain function: inputs before render, bytes before
  send, a new agent appended, a retired one removed, keyframes composed from what was
  retained. Run: bin/test seon.render-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.render :as r] [seon.render.ui :as ui] [seon.render.ai :as ai] [seon.render.proc :as p]
            [seon.web.html :as h] [seon.config] [seon.docs] [seon.print] [seon.db] [seon.reconcile] [seon.task] [seon.edn] [seon.context] [seon.contract] [seon.repl] [datahike.api :as d]
            [seon.fx] [seon.source] [seon.program]))

(def ^:private task
  {:seon.task/id "calc/add" :seon.task/ns 'calc :seon.task/name "add" :seon.task/plan "Make `app.calc/add` pass"
   :seon.task/execution :running :seon.task/branch :task-calc-add :seon.task/proofs #{'app.proofs/add-test}
   :seon.task/needs #{} :seon.task/kind :work
   :seon.task/accumulated {:plan "Make `app.calc/add` pass" :proofs #{'app.proofs/add-test}}})

(deftest selection
  (testing "the value's own override key wins: data as is"
    (is (= [:b "mine"] (r/html {:a 1 :seon.render/html [:b "mine"]} {:seon.render/schema :seon.task/task}))))
  (testing "the schema's declared render, named by the producer"
    (is (= 'seon.render.ui/task-box (r/declared :seon.task/task :html)))
    (is (str/includes? (h/html (r/html task {:seon.render/schema :seon.task/task})) "task · calc/add")))
  (testing "a pulled entity's schema is the one its identity attribute names"
    (is (= :seon.attempt/attempt (r/schema-of {:seon.attempt/id "x#1"} nil)))
    (is (str/includes? (h/html (r/html {:seon.attempt/id "calc/add#1" :seon.attempt/agent :a3 :seon.attempt/status :running} {}))
                       "href=\"/agent/a3\"")))
  (testing "no declaration: the one value printer's hiccup"
    (let [out (r/html {:k [1 2 3]} {})]
      (is (= "seon-data-panel" (get-in out [1 :class])))))
  (testing "the AI face of an undeclared value is the printer's text"
    (is (= "{:k 1}" (r/ai {:k 1} {})))
    (is (vector? (r/html {:k 1} {})))))

(def ^:private sys
  {:agents {:a1 {:status :idle :tests {:pass 1 :fail 1 :error 0 :reports [{:type :fail :var 'agent.a1/c}]}}
            :a2 {:status :dead}
            :a3 {:status :killed :tests {:pass 0 :fail 3 :error 0}}}
   :health {:api {:defects 0} :render {:defects 1 :newest "boom"}}
   :writer {:tx-count 4 :tx-per-s 2.0} :fork-server {:status :up :pid 7} :main {:t 9 :datoms 100}
   :tasks {} :attempts {} :events [] :root-errors [{:pid :render :message "boom"}] :now-ms 0
   ;; what main recorded (seon.problems/report): root records an agent's death and a red run
   :recorded {:classes {:agent {:n 3 :rows [{:signature "s1" :count 2 :message "tests red: agent.a1/c"}
                                            {:signature "s2" :count 1 :message "agent a2 died: signal 9"}]}
                        :red {:n 0 :rows []}}}})

(deftest root-context
  (let [text (r/ai sys {:seon.render/schema :seon.render/system :seon.render/budget 700})
        lines (str/split-lines text)]
    (testing "problems first: root's defect, then what main recorded (one producer); no proc is pinged"
      (is (= "# problems" (second lines)))
      (is (not (re-find #"\d\d:\d\d:\d\d" (first lines))) "no clock: an unchanged system gives the same text")
      (is (not-any? #(str/includes? % "UNKNOWN") lines) "no ping, so no unanswered ping")
      (is (some #(str/includes? % "defect in render: boom") lines))
      (is (some #(str/includes? % "recorded agent 3: tests red: agent.a1/c") lines))
      (testing "a class's other open rows are named, with where they all are"
        (is (some #(str/includes? % "tests red: agent.a1/c · +1 more: open, all in (get-in (my.root/problems sys) [:classes :agent :rows])") lines))))
    (testing "the live view is not a second producer: an agent's own entry makes no problem line"
      (is (not-any? #(re-find #"^- a\d" %) lines)))
    (testing "over the budget: later sections are named, not silently cut"
      (let [small (r/ai sys {:seon.render/schema :seon.render/system :seon.render/budget 40})]
        (is (str/includes? small "not shown, over the budget"))
        (is (<= (seon.print.select/estimate (first (str/split small #"\n\(not shown"))) 40))))))

(defn- answer
  "The render proc's step on an api :state answer for st (the request it had in flight)."
  [st view]
  (let [st (assoc st :pending {1 :state} :main-tx (get-in view [:writer :tx-count]))]
    (#'p/render-proc st :answers {:tag 1 :r view})))

(def ^:private view0
  {:agents {:a1 {:branch :agent-1 :status :idle :task "t1" :evals []}}
   :writer {:tx-count 1 :recent []} :fork-server {:status :up} :stats {} :store {} :boot {}})

(defn- fresh-state []
  {:rid 1 :pending {} :watch #{:root} :waiting {} :kf-wanted #{} :new-events [] :events [] :event-rows []
   :views {} :zoom {} :merges 0 :main {:t 1}})

(deftest render-step
  (let [[st1 out1] (answer (fresh-state) view0)
        patch1 (first (filter #(= :patch (:op %)) (:to-web out1)))]
    (testing "the first pass renders every unit and sends the agents' index"
      (is (some #(= "t-agents" (first %)) (:frags patch1)))
      (is (= "#grid" (ffirst (:appends patch1))))
      (is (str/includes? (second (first (:appends patch1))) "id=\"cb-a1\""))
      (is (= {:a1 {:branch :agent-1 :status :idle}} (:agents (first (filter #(= :index (:op %)) (:to-web out1)))))))
    (testing "nothing changed: no unit renders, no patch is sent"
      (let [[st2 out2] (answer st1 view0)]
        (is (empty? (filter #(= :patch (:op %)) (:to-web out2))))
        (is (= (get-in st1 [:counts :rendered]) (get-in st2 [:counts :rendered])))))
    (testing "one agent's evaluation: its card and the swarm re-render; the other tiles do not"
      (let [view1 (assoc-in view0 [:agents :a1 :evals] [{:id 0 :tag :ret :form "(+ 1 2)" :printed "3" :ms 0.1}])
            [_ out] (answer st1 view1)
            ids (set (map first (:frags (first (filter #(= :patch (:op %)) (:to-web out))))))]
        (is (contains? ids "cb-a1"))
        (is (contains? ids "t-agents"))
        (is (not (contains? ids "t-writer")))
        (is (not (contains? ids "t-fork-server")))))
    (testing "a new agent: its whole card appended to the grid, its body inside"
      (let [view2 (assoc-in view0 [:agents :a2] {:branch :agent-2 :status :spawning :task "t2" :evals []})
            [st3 out] (answer st1 view2)
            patch (first (filter #(= :patch (:op %)) (:to-web out)))
            [[sel html]] (:appends patch)]
        (is (= "#grid" sel))
        (is (str/includes? html "id=\"card-a2\""))
        (is (str/includes? html "id=\"cb-a2\""))
        (testing "an agent ended long ago leaves the grid: its card removed"
          (let [view3 (assoc-in view2 [:agents :a2] {:branch :agent-2 :status :killed :ended-ms 0 :evals []})
                [_ out] (answer st3 view3)
                patch (first (filter #(= :patch (:op %)) (:to-web out)))]
            (is (= ["card-a2"] (:removes patch)))))))
    (testing "a keyframe is composed from the retained fragments, rendering nothing"
      (let [[st out] (#'p/render-proc st1 :from-web {:op :keyframe :view :root})
            html (:html (first (:to-web out)))]
        (is (= (get-in st1 [:counts :rendered]) (get-in st [:counts :rendered])))
        (is (str/includes? html "id=\"cb-a1\""))
        (is (not (str/includes? html "<!--s:")))))))

(deftest compose-fills-slots-recursively
  (is (= "<a><b>x</b></a>" (p/compose "<a><!--s:b--></a>" {"b" {:html "<b><!--s:c--></b>"} "c" {:html "x"}}))))

(deftest stream-prepends-new-rows
  (let [[st1 _] (answer (fresh-state) view0)
        st1 (assoc st1 :new-events [{:type :ready :agent :a1 :at-ms 1} {:type :killed :agent :a1 :at-ms 2}])
        [st2 out] (answer st1 view0)
        patch (first (filter #(= :patch (:op %)) (:to-web out)))
        [[sel html]] (:prepends patch)]
    (testing "new events: their rows prepended, newest first, each with its own id; the list itself not re-sent"
      (is (= "#events" sel))
      (is (< (str/index-of html "id=\"ev-2\"") (str/index-of html "id=\"ev-1\"")))
      (is (not-any? #(= "events" (first %)) (:frags patch))))
    (testing "past the cap, the oldest rows are removed by id"
      (let [st (assoc st2 :new-events (vec (for [i (range (seon.config/value :seon.config.render.list/stream))] {:type :ready :agent :a1 :at-ms i})))
            [_ out] (answer st view0)
            patch (first (filter #(= :patch (:op %)) (:to-web out)))]
        (is (= #{"ev-1" "ev-2"} (set (filter #(str/starts-with? % "ev-") (:removes patch)))))))))

(deftest end-words
  (testing "an agent stopped after its attempt finished ended; a kill is a kill; a death with no finished attempt died"
    (is (= :ended (ui/shown-status (ui/ended-as {:status :killed} :a1 {:a1 {:seon.attempt/status :merged}}))))
    (is (= :killed (ui/shown-status (ui/ended-as {:status :killed} :a1 {:a1 {:seon.attempt/status :running}}))))
    (is (= :died (ui/shown-status (ui/ended-as {:status :dead} :a1 {}))))
    (is (= :idle (ui/shown-status (ui/ended-as {:status :idle} :a1 {:a1 {:seon.attempt/status :merged}})))))
  (testing "its card says how it ended, not KILLED"
    (let [html (h/html (:seon.render/html (ui/agent-card {:id :a1 :status :killed :ended-as :merged :evals []} {})))]
      (is (str/includes? html "ended · merged"))
      (is (not (str/includes? html ">killed<"))))))

(deftest red-labels
  (let [owners {'app.proofs/abs-test "calc/abs" 'app.proofs/add-test "calc/add"}]
    (is (str/includes? (h/html (ui/red-label "#'app.proofs/add-test" #{'app.proofs/add-test} owners)) "blocking"))
    (is (str/includes? (h/html (ui/red-label 'app.proofs/abs-test #{'app.proofs/add-test} owners)) "allowed: a proof of calc/abs"))))

(deftest problems-tile
  (is (str/includes? (h/html (:seon.render/html (ui/tile-problems [] {}))) "no problems"))
  (let [html (h/html (:seon.render/html (ui/tile-problems ["proc writer UNKNOWN"] {})))]
    (is (str/includes? html "1 problem"))
    (is (str/includes? html "proc writer UNKNOWN")))
  (testing "a dead agent whose attempt finished is not a problem"
    (is (empty? (ai/problems (assoc sys :agents {:a2 {:status :dead}} :health {} :root-errors [] :recorded {}
                                    :agent-attempt {:a2 {:seon.attempt/status :done}}))))))

(deftest observed-task-states
  (let [decl (fn [nm proofs & needs] (cond-> {:seon.task/ns 't :seon.task/name nm :seon.task/plan (str "Make " nm ".") :seon.task/proofs proofs}
                                       needs (assoc :seon.task/needs (set needs))))
        db (ts/test-db)
        db (-> db (d/db-with (seon.task/create-tx db [(decl "a" #{'p/x}) (decl "b" #{'p/z} "t/a") (decl "c" #{'p/w})]))
               (d/db-with (seon.task/execution-tx ["t/a" "t/b"] :running))
               (d/db-with (seon.task/execution-tx ["t/c"] :cancelled))
               (d/db-with (conj (seon.task/attempt-start-tx "t/a#1" "t/a" "boot-1" :a1 {:branch :task-t-a :model nil :max-turns 40} 1 "Make a.")
                                {:seon.attempt/id "t/a#1" :seon.attempt/tokens 7
                                 :seon.attempt/results [{:seon.proof/test 'p/x :seon.proof/status :pass} {:seon.proof/test 'p/y :seon.proof/status :fail}]})))
        rows (p/task-rows (seon.reconcile/index db))]
    (testing "what happened, not the desired execution: running, blocked by its need, cancelled"
      (is (= :running (get-in rows ["t/a" :state])))
      (is (= :blocked (get-in rows ["t/b" :state])))
      (is (= #{"t/a"} (get-in rows ["t/b" :blocked-by])))
      (is (= :cancelled (get-in rows ["t/c" :state]))))
    (testing "its own proofs on its newest attempt (another task's red is allowed), attempts so far, tokens"
      (is (= [1 0 1 1 7] ((juxt :green :red :allowed-red :attempts :tokens) (get rows "t/a")))))))

(deftest stop-row-says-how-it-ended-once-main-knows
  (let [[st1 _] (answer (fresh-state) view0)
        [st2 _] (answer (assoc st1 :new-events [{:type :killed :agent :a7 :at-ms 1}]) view0)
        row (second (first (:event-rows st2)))
        _ (is (str/includes? row "KILLED") "before main records the attempt's end, a stop reads as a kill")
        [_ out] (answer (assoc st2 :agent-attempt {:a7 {:seon.attempt/status :merged}}) view0)
        frag (some #(when (= "ev-1" (first %)) (second %)) (:frags (first (filter #(= :patch (:op %)) (:to-web out)))))]
    (is (str/includes? frag "stopped by root after its attempt ended merged"))
    (is (str/includes? frag "e-ended"))))

;; ---- no clipping: every cut on the page and in root's context says what it left -------------

(defn- long-text [n] (str/join " " (for [i (range n)] (str "word" i))))

(deftest no-cut-on-the-page-drops-silently
  (testing "the clipping helpers are gone"
    (is (nil? (resolve 'seon.render.ui/clip)))
    (is (nil? (resolve 'seon.render.ui/line))))
  (let [err (long-text 400)
        form (str "(defn f [x] " (long-text 200) ")")
        evals (vec (for [i (range 10)] {:id i :tag :ret :form "(+ 1 2)" :printed "3"}))
        html (h/html (:seon.render/html (ui/agent-card {:id :a1 :status :busy :error (seon.contract/error :seon.agent/failed (quote seon.root/spawn!) err {:agent :a1}) :running {:form form} :evals evals} {})))
        omitted (fn [s] (some-> (re-find #"…\+(\d+) chars" s) second parse-long))]
    (testing "a long error is elided: its beginning, then how many characters it left, the whole on hover"
      (is (str/includes? html "word0 word1"))
      (is (some? (omitted html)))
      (is (str/includes? html (str "title=\"" err "\"")) "the cut's title holds the whole message"))
    (testing "the running form's cut links to the agent's page, which shows it whole"
      (is (str/includes? html "href=\"/agent/a1\" title=\"the whole on this page\"")))
    (testing "ten evaluations, six shown: the card says four more are on its page"
      (is (str/includes? html "+4 more: earlier evaluations on its page") "open, the newest six")
      (is (str/includes? html "+9 more: earlier; ⤢ shows the newest 6, its page all 10") "closed, the newest one")
      (is (str/includes? html ">10<") "the evals count is all of them")))
  (testing "a cut's count is exact"
    (let [s (long-text 300)
          {:keys [shown omitted]} (seon.print/prose s 40)]
      (is (= (count s) (+ (count shown) omitted)))
      (is (str/includes? (h/html (ui/prose s 40)) (str "…+" omitted " chars")))))
  (testing "tasks are all listed; the attempts table says how many more main holds"
    (let [rows (into {} (for [i (range 50)] [(str "t/x" i) {:state :done :blocked-by #{} :green 1 :red 0 :proofs 1 :attempts 1 :tokens 0}]))
          attempts (into {} (for [i (range 12)] [(str "t/x" i "#1") {:seon.attempt/id (str "t/x" i "#1") :seon.attempt/status :done :seon.attempt/start-ms i}]))
          html (h/html (:seon.render/html (ui/tile-tasks {:rows rows :attempts attempts} {})))]
      (is (str/includes? html ">t/x49<") "the 50th task is listed")
      (is (str/includes? html "+4 more: older, on main: (seon.task/attempts (deref (:conn sys))) at root's REPL"))))
  (testing "root's defects: the count is every one recorded, not the ones the view keeps"
    (let [html (h/html (:seon.render/html (ui/tile-errors {:root-errors (vec (for [i (range 20)] {:pid :render :message (str "boom " i)}))
                                                           :root-errors-total 31 :boot {} :store {}} {})))]
      (is (str/includes? html "31 defects in root's own procs"))
      (is (str/includes? html "+25 more"))
      (is (< (str/index-of html "boom 19") (str/index-of html "boom 14")) "newest first"))))

(deftest root-context-names-what-each-list-left
  (let [attempts (into {} (for [i (range 12)] [(str "t/x" i "#1") {:seon.attempt/id (str "t/x" i "#1") :seon.attempt/status :done
                                                                     :seon.attempt/outcome :green :seon.attempt/end-ms i
                                                                     :seon.attempt/reason (if (= i 11) (long-text 200) "ok")}]))
        s (assoc sys :tasks {"t/x0" {}} :attempts attempts :task-rows {}
                 :root-errors [{:pid :render :message (long-text 300)}] :root-errors-total 4
                 :events (vec (for [i (range 30)] {:type :ready :agent :a1 :at-ms i})))
        text (r/ai s {:seon.render/schema :seon.render/system :seon.render/budget 2000})]
    (testing "five ended attempts shown, seven more named with the form that reads them"
      (is (str/includes? text "+7 more: ended, all in (seon.task/attempts (deref (:conn sys)))")))
    (testing "a long reason is elided with the form that reads its rest"
      (is (re-find #"…\+\d+ chars \(subs \(:seon.attempt/reason \(get \(seon.task/attempts" text)))
    (testing "root's defects: the shown one, its cut's form, and how many more"
      (is (re-find #"defect in render: word0 .*…\+\d+ chars \(subs \(:message \(nth \(:root-errors \(seon.root/state sys\)\) 0\)\) \d+\)" text))
      (is (str/includes? text "+3 more: (:root-errors (seon.root/state sys))")))
    (testing "the events: eight newest, and the rest named"
      (is (str/includes? text "+22 more: on the dashboard's event stream")))))

(deftest the-page-says-what-it-keeps
  (testing "the stream's header: how many events it keeps of all since boot"
    (is (str/includes? (h/html (:seon.render/html (ui/events-count {:kept 60 :seen 112} {})))
                       "60 kept of 112 since root's boot · 52 older not kept")))
  (testing "agents ended long ago leave the grid; the tile counts them"
    (is (str/includes? (h/html (:seon.render/html (ui/tile-agents {:cells [[:a1 :idle 0]] :off 12 :retire-ms 90000} {})))
                       "1 on the grid · +12 ended over 90 s ago, off it")))
  (testing "a held task's pill says what holding means and how to release it"
    (is (str/includes? (ui/state-title :held) "(my.root/run! sys [id])"))))

(deftest root-context-groups-blocked-tasks-by-what-blocks-them
  (let [rows {"t/a" {:state :blocked :blocked-by #{"t/root"}} "t/b" {:state :blocked :blocked-by #{"t/root" "t/other"}}
              "t/c" {:state :blocked :blocked-by #{"t/root"}} "t/d" {:state :done :blocked-by #{}}}
        text (r/ai (assoc sys :tasks {"t/a" {}} :task-rows rows) {:seon.render/schema :seon.render/system :seon.render/budget 2000})]
    (is (str/includes? text "3 blocked, waiting on: t/root (3), t/other (1)") "each blocker once, the one holding most first")
    (is (not (str/includes? text "t/a blocked by")) "not one line per blocked task")))

;; ---- the FENCES audit's names for the same law (seon.fences/no-clip-in-renders) -------------

(deftest no-render-calls-clip
  (is (nil? (resolve 'seon.render.ui/clip)))
  (is (nil? (resolve 'seon.render.ui/line)))
  (testing "root's context sizes tokens by the printer's own measure (seon.print.select/estimate), no alias of it"
    (is (nil? (resolve 'seon.render.ai/tokens)))))

(deftest a-capped-list-says-how-many-more
  (testing "the agent page's history: the newest rows, and how many older its branch records"
    (let [html (h/html (:seon.render/html (ui/history-list {:rows ["<li>r</li>"] :total 140} {})))]
      (is (str/includes? html "+139 more: older evaluations, recorded on its branch"))))
  (testing "nothing capped, nothing said"
    (is (not (str/includes? (h/html (:seon.render/html (ui/history-list {:rows ["<li>r</li>"] :total 1} {}))) "more")))))

(deftest a-context-read-is-shown-once
  (let [ctx (str "{:turn \"1 of 8\" :refused [{:refusal :contract-gap :vars [" (str/join " " (for [i (range 360)] (str "app.m/f" i))) "]}]}")
        read {:id 0 :tag :ret :form "(print (seon.turn/context-block \"t/x\" \"t/x#1\"))" :printed "nil" :out ctx :origin :context}
        card (h/html (:seon.render/html (ui/agent-card {:id :a14 :status :idle :evals [read]} {})))
        row (h/html (:seon.render/html (ui/history-row read {})))]
    (testing "a card and a history row refer to the block; they never print it again"
      (is (str/includes? card "read its current-state block"))
      (is (str/includes? card "href=\"/agent/a14#z-context\""))
      (is (not (str/includes? card "app.m/f359")))
      (is (str/includes? row "read its current-state block"))
      (is (not (str/includes? row "app.m/f359"))))
    (testing "the stream says what happened"
      (is (str/includes? (h/html (:seon.render/html (ui/event-row {:type :reply :agent :a14 :at-ms 0 :items [read]} {})))
                         "read its current-state block")))
    (testing "the agent page's panel is the text it read, as recorded (seon.context/recorded), and one copy of it"
      (let [ctxv (#'p/context-of nil {:seon.attempt/task "t/x"} {:evals [{:tag :ret :form "(+ 1 2)" :printed "3"} read]})]
        (is (= :why (first (keys ctxv))) "no db: why, not a rebuild"))
      (ts/test-branch! (fn [_ conn] (let [;; the wiki, as root's boot loads it: the help page every render reads
                                          _ (d/transact conn (seon.docs/load-tx @conn "docs" (seon.docs/pages (seon.config/checkout) "docs")))
                                          _ (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 't :seon.task/name "x" :seon.task/plan "p"}]))
            ;; an outside read whose done rule refused 360 contract gaps: its basis, never its text
            b {:commit (d/commit-id @conn) :task "t/x" :attempt "t/x#1" :turn 1 :max-turns 1 :origin :outside
               :observed {:results {} :reds [] :run-errors []
                          :refusals (vec (for [i (range 360)] {:refusal :contract-gap :symbol (symbol "app.m" (str "f" i))}))}}
            _ (d/transact conn [{:seon.turn/id "t/x#1/read-1" :seon.turn/attempt "t/x#1" :seon.turn/n 1
                                 :seon.turn/origin :outside :seon.turn/basis b}])
            db @conn
            ctxv (#'p/context-of db {:seon.attempt/id "t/x#1" :seon.attempt/task "t/x"} {:evals [read]})]
        (is (= {:text (seon.context/reader-text (:messages (seon.context/render db b))) :origin :outside} (select-keys ctxv [:text :origin]))
            "the text it read, made again from its basis")
        (is (str/includes? (:text ctxv) "app.m/f0") "what the read showed")
        (is (:why (#'p/context-of db {:seon.attempt/id "t/x#2" :seon.attempt/task "t/x"} {:evals []})) "an attempt that read nothing says why")))))))

(deftest a-hand-spawned-agent-says-why-it-has-no-context
  (let [{:keys [why]} (#'p/context-of nil nil {:task "index rows by id (#0)"})]
    (is (str/includes? why "spawned by hand with the description \"index rows by id (#0)\""))
    (is (str/includes? why "(my.root/run! sys [task-id])") "how an agent gets a context")))

(deftest the-tiles-explain-their-numbers
  (testing "an ended card's reds are said to be other tasks' proofs"
    (let [html (h/html (:seon.render/html (ui/agent-card {:id :a6 :status :killed :ended-as :merged :evals []
                                                          :tests {:pass 2 :fail 7 :error 0}} {})))]
      (is (str/includes? html "its attempt ended merged: its own proofs passed; these reds are other tasks' proofs, allowed"))))
  (testing "the count pills say what each state is; held says why"
    (let [html (h/html (:seon.render/html (ui/tile-tasks {:rows {"t/a" {:state :held :blocked-by #{} :green 0 :red 0 :proofs 1 :attempts 0 :tokens 0}}
                                                          :attempts {}} {})))]
      (is (re-find #"pill s-held\" title=\"held: declared in a task file" html))))
  (testing "boot's program count is definitions, and the agents tile names its retire window"
    (is (str/includes? (h/html (:seon.render/html (ui/tile-errors {:root-errors [] :boot {:program {:loaded 360}} :store {}} {}))) "360 defs"))
    (is (str/includes? (h/html (:seon.render/html (ui/tile-agents {:cells [] :off 3 :retire-ms 90000} {}))) "+3 ended over 90 s ago, off it"))))

(deftest an-ended-agent-reads-ended-and-offers-no-live-buttons
  (testing "root's own word for an agent it stopped because its attempt ended"
    (is (= :ended (ui/shown-status {:status :ended})))
    (is (str/includes? (h/html (:seon.render/html (ui/event-row {:type :ended :agent :a6 :at-ms 0 :ended-as :merged} {})))
                       "stopped by root: its attempt ended merged")))
  (testing "its live buttons and REPL box are marked live, which an ended card hides; history stays"
    (let [shell (h/html (ui/card-shell :a6))]
      (is (= 6 (count (re-seq #"class=\"act live" shell))) "tests, merge, main, clone, interrupt, kill")
      (is (str/includes? shell "class=\"repl live\""))
      (is (str/includes? shell "show history")))
    (is (str/includes? (slurp "resources/public/app.css") ".card:has(.cb.s-ended) .live"))))

(deftest a-cut-value-names-the-form-that-reads-it
  (let [txt (pr-str (apply str (repeat 300 "lorem ipsum ")))
        row (h/html (:seon.render/html (ui/history-row {:tag :ret :form "(apply str (repeat 300 \"lorem ipsum \"))" :printed txt :handle :h0} {})))]
    (is (re-find #"…\+\d+ chars · the whole value: result/h0 at its REPL" row))))

(deftest the-writer-tile-counts-per-commit-and-per-flush
  (let [html (h/html (:seon.render/html (ui/metric-tile (first (ui/load-tiles {:tx-per-commit 1.0 :commits 40 :tx-count 40 :flushes 10} {} {} {})) {})))]
    (is (str/includes? html ">tx/commit<"))
    (is (str/includes? html "<dd>1.00</dd>"))
    (is (str/includes? html ">commits/flush</dt><dd>4.0<") "40 commits over 10 flushes")
    (is (str/includes? html "a flush makes the commits since the last one durable on disk"))
    (is (not (str/includes? html "tx/batch")))))


;; ---- no beat: health is what flows (owner, 2026-10-21) --------------------------------------

(deftest render-proc-has-no-beat
  (let [d (p/render-proc)]
    (is (not (contains? (:ins d) :tick)) "no beat in-port")
    (is (= #{:answers :from-web} (set (keys (:ins d)))))
    (is (nil? (resolve 'seon.render.proc/SAMPLE-MS)))
    (is (nil? (resolve 'seon.render.proc/start-sampler!))))
  (testing "health is each declared proc's recorded defects: no ping, no steps/s"
    (let [html (h/html (:seon.render/html (ui/tile-health {:health {:api {:defects 0} :render {:defects 2 :newest "boom"}}} {})))]
      (is (str/includes? html "2 procs · 1 with a defect recorded"))
      (is (str/includes? html "2 defects"))
      (is (str/includes? html "no defect recorded"))
      (is (not (str/includes? html "steps/s")))
      (is (not (str/includes? html "pinged ·")))))
  (testing "a proc's health from root's view: its declared procs, its defects by pid"
    (let [sysp (doto (promise) (deliver {:procs {:api {} :render {} :fx/model {}}}))
          h (#'p/proc-health-of {:system-p sysp} [{:pid :render :message "a"} {:pid :render :message "b"}])]
      (is (= {:defects 2 :newest "b"} (get h :render)))
      (is (= {:defects 0} (get h :api)))
      (is (= 3 (count h))))))

;; ---- one bounded list: its bound is config, its cut says what it left -----------------------

(deftest a-bounded-list-is-one-call-with-its-bound-in-config
  (let [n (seon.config/value :seon.config.render.list/top)
        [shown more] (seon.print/bounded (range 10) :seon.config.render.list/top "in (range 10)")]
    (is (= (range n) shown) "the first n, n from the config key")
    (is (= (str "+" (- 10 n) " more: in (range 10)") more)))
  (testing "a total beyond the items (a view that keeps fewer than were recorded) counts the whole"
    (is (= "+17 more: older" (second (seon.print/bounded [1 2 3] :seon.config.render.list/top "older" 20)))))
  (testing "nothing left, nothing said"
    (is (nil? (second (seon.print/bounded [1] :seon.config.render.list/top "x"))))))

(deftest the-load-tiles-are-data-the-context-reads
  (let [tiles (ui/load-tiles {:tx-count 4} {:status :down :pid 7} {:datoms 100} {})
        text (r/ai sys {:seon.render/schema :seon.render/system :seon.render/budget 2000})]
    (is (= ["t-writer" "t-fork-server" "t-main"] (mapv :id tiles)))
    (is (str/includes? (h/html (:seon.render/html (ui/metric-tile (second tiles) {}))) "pill srv s-down"))
    (testing "root's context prints the page's own tiles, one line each"
      (is (some #(str/starts-with? % "- writer 4 txs, batch") (str/split-lines text)))
      (is (some #(str/starts-with? % "- fork server up – ms fork p50, forked –") (str/split-lines text))))))

(deftest a-recorded-error-shows-its-message
  ;; task seon.render/one-owner-per-view-path (A13): an evaluation's text is its printed text,
  ;; live (:printed) or recorded (:shown), for an error too: never its error map printed as text
  (let [e (seon.contract/error :seon.agent/eval-error 'seon.repl/eval-code "Divide by zero" {:class "ArithmeticException"})
        it {:id 1 :tag :err :form "(/ 1 0)" :err e :printed "ArithmeticException: Divide by zero" :ms 0.1}
        rec (some :seon.cluster.eval/record (seon.repl/record-tx :a1 "r-1" [it] nil))]
    (doseq [[what x] [["live" it] ["recorded" rec]]
            :let [html (h/html (:seon.render/html (ui/history-row x {})))]]
      (is (str/includes? html "Divide by zero") what)
      (is (not (str/includes? html "seon.error/type")) what))))

(deftest a-refused-merge-says-why-once
  ;; task seon.render/one-owner-per-view-path (B2, A14): a refusal is the one error map; the
  ;; card's pill and the stream's row read its message, once
  (let [r (seon.contract/error :seon.root/refused 'seon.root/merge! "2 functions have no contract" {:agent :a1})
        card (h/html (:seon.render/html (ui/agent-card {:id :a1 :status :idle :evals [] :merged r} {})))
        row (ui/event-detail {:type :merged :agent :a1 :result r})]
    (is (str/includes? card "2 functions have no contract") "the pill's title says why, not that its tests were red")
    (is (= 1 (count (re-seq #"no contract" row))) row)
    (is (str/includes? (ui/event-detail {:type :merged-main :result r}) "no contract"))))

;;; --- the values agents and root read: declared AI and HTML renders, one per value --------

(def ^:private behind
  ;; as seon.writer/merge-refusal makes it
  (seon.contract/error :seon.merge/behind 'seon.writer/merge-branch
                       "main changed 3 key(s) since the branch's merge base: merge main into the branch first"
                       {:tx 812 :behind 3
                        :seon.error/fix "A merge task merges main into the branch (my.branch/merge-main!), resolves any conflict, and its proofs run on the combined program."}))

(deftest an-error-reads-its-message-fix-and-variant
  (testing "an error map's schema is its variant: the base's render, whatever the variant"
    (is (= :seon.merge/behind (r/schema-of behind nil)))
    (is (= :seon.error/base (r/schema-of {:seon.error/type :no.such/variant :seon.error/operation 'a/b :seon.error/message "m"} nil))))
  (let [text (r/ai behind {:seon.render/handle 'result/h4})]
    (testing "the message first, as a REPL answers; the fix; then its variant, where, and its own keys"
      (is (str/starts-with? text "main changed 3 key(s)"))
      (is (str/includes? text "fix: A merge task merges main into the branch (my.branch/merge-main!)"))
      (is (str/includes? text "(seon.merge/behind in seon.writer/merge-branch {"))
      (is (str/includes? text ":behind 3"))
      (is (str/includes? text "the whole value as data: (with-meta result/h4 nil)"))))
  (testing "the page shows the same facts"
    (let [html (h/html (r/html behind {}))]
      (is (str/includes? html "main changed 3 key(s)"))
      (is (str/includes? html "fix: A merge task merges main"))
      (is (str/includes? html "seon.merge/behind in seon.writer/merge-branch")))))

(def ^:private failed-task
  (assoc task :seon.task/execution :held :seon.task/needs #{"calc/sub"}
         :seon.task/accumulated {:plan (str/join "\n" (cons "Make `app.calc/add` pass." (for [i (range 400)] (str "Step " i ": read `app.calc/f" i "` and its proof."))))
                                 :proofs (set (for [i (range 12)] (symbol "app.proofs" (str "add-" i "-test"))))}
         :seon.task/failure {:reason "add cannot be total over longs" :counterexample "(add Long/MAX_VALUE 1)" :attempt "calc/add#2"}
         :seon.task/evidence {:from "calc/add#2" :conflicts [] :unmerged [] :red ['app.proofs/add-0-test] :problem "red"}))

(deftest a-task-reads-its-state-plan-proofs-and-failure
  (testing "a key present in a map names no render at the REPL: it prints as the data it is"
    (is (str/starts-with? (:text (r/printed failed-task 'result/h2)) "#:seon.task{")))
  (testing "a task its producer names (seon.task/task's metadata) reads at the REPL by its declared render"
    (let [text (:text (r/printed (with-meta failed-task {:seon.render/schema :seon.task/task}) 'result/h2))]
      (is (str/starts-with? text "task calc/add · work · held · needs calc/sub"))
      (is (str/includes? text "failed: add cannot be total over longs · counterexample (add Long/MAX_VALUE 1)"))
      (testing "a long plan shows whole under the REPL's reply budget"
        (is (str/includes? text "Step 399")))
      (testing "(the part budget when the env names none)"
        (is (re-find #"…\+\d+ chars in \d+ more lines \(subs \(get-in result/h2 \[:seon.task/accumulated :plan\]\) \d+\)" (r/ai failed-task {:seon.render/handle 'result/h2}))))
      (testing "the proofs are a bounded list: how many more and where"
        (is (str/includes? text "12 proofs: app.proofs/add-0-test"))
        (is (str/includes? text "+4 more: (get-in result/h2 [:seon.task/accumulated :proofs])")))
      (is (str/includes? text "evidence: {:from \"calc/add#2\""))))
  (testing "the page's task box shows the failure too"
    (let [html (h/html (r/html failed-task {:seon.render/schema :seon.task/task}))]
      (is (str/includes? html "failed in calc/add#2: add cannot be total over longs")))))

(deftest an-attempt-reads-in-one-line
  (is (= "running calc/add#1 on a3, turn 2, 1840 tokens"
         (r/ai {:seon.attempt/id "calc/add#1" :seon.attempt/status :running :seon.attempt/agent :a3 :seon.attempt/turns 2 :seon.attempt/tokens 1840} {})))
  (let [line (r/ai {:seon.attempt/id "calc/add#2" :seon.attempt/status :failed :seon.attempt/outcome :merge-failed
                    :seon.attempt/reason (str "merge refused: " (:seon.error/message behind))
                    :seon.attempt/conflicts [{:key "var app.calc/add" :kind :var :base "a" :ours "b" :theirs "c"}]
                    :seon.attempt/unmerged ["var app.calc/add"]}
                   {:seon.render/handle '(get atts "calc/add#2")})]
    (is (str/starts-with? line "ended calc/add#2 merge-failed: merge refused: main changed 3 key(s)"))
    (is (str/includes? line " · 1 conflicts in (:seon.attempt/conflicts (get atts \"calc/add#2\")) · 1 unmerged in (:seon.attempt/unmerged (get atts \"calc/add#2\"))"))))

(defn- row
  "A problem row as seon.problems/report makes it: every row's members, then its class's."
  [m] (merge {:producer nil :owner-ns 'calc :evidence {} :proofs [] :answered-by [] :at-ms 1761170000000} m))

(deftest every-problem-class-reads-what-it-is-about-and-why
  ;; rows as seon.problems/report makes them, one per class that had no line of its own
  (let [line #(r/ai (row (assoc %2 :class %1)) {:seon.render/schema :seon.problems/row})]
    (is (= "task-conflict calc/add calc/add#3 plan: {:base \"Make add.\", :ours \"Make add total.\", :theirs \"Make add pass.\"}"
           (line :task-conflict {:task "calc/add" :attempt "calc/add#3" :attr :plan :open? true
                                 :evidence {:task "calc/add" :attr :plan :base "Make add." :ours "Make add total." :theirs "Make add pass." :from "calc/add#3"}})))
    (is (= "failed calc/add calc/add#2: attempt calc/add#2 ended cap: proof-red app.proofs/add-test"
           (line :failed {:task "calc/add" :attempt "calc/add#2" :reason "attempt calc/add#2 ended cap: proof-red app.proofs/add-test"}))
        "a refused attempt's hold: a reason and no counterexample")
    (is (= "failed calc/add calc/add#2: add cannot be total over longs"
           (line :failed {:task "calc/add" :attempt "calc/add#2" :reason "add cannot be total over longs" :counterexample "(add Long/MAX_VALUE 1)"})))
    (is (= "merge calc/add calc/add#2: main changed 3 key(s) since the branch's merge base: merge main into the branch first · 1 conflicts · unmerged var app.calc/add"
           (line :merge {:task "calc/add" :attempt "calc/add#2" :error behind :conflicts 1 :unmerged ["var app.calc/add"]})))
    (is (= "red calc/add calc/add#1 app.proofs/add-test: tests-red"
           (line :red {:task "calc/add" :attempt "calc/add#1" :test 'app.proofs/add-test :outcome :tests-red})))
    (is (= "platform ×4: Datahike transact refused (answered by calc/fix-store)"
           (line :platform {:signature "s" :message "Datahike transact refused" :count 4 :answered-by ["calc/fix-store"]}))))
  (testing "the whole report at root's REPL: by class, each row by its render, closed rows marked"
    (let [rep (with-meta {:t 90 :classes {:failed {:n 1 :rows [(row {:task "calc/add" :reason "not total" :open? true})]}
                                          :merge {:n 1 :rows [(row {:task "calc/sub" :attempt "calc/sub#1" :error behind :open? false})]}
                                          :red {:n 0 :rows []}}}
                {:seon.render/schema :seon.problems/report})
          text (:text (r/printed rep 'result/h1))]
      (is (= ["problems recorded on main at t 90" "# failed 1" "- failed calc/add: not total" "# merge 1" "- merge calc/sub calc/sub#1: main changed 3 key(s) since the branch's merge base: merge main into the branch first (closed)"
              "the whole value as data: (with-meta result/h1 nil)"]
             (str/split-lines text))))))


;;; --- the law, from the compiler's facts: one printer, and no cut before it -------------

(def ^:private printer-vars
  "The vars that turn a value into text beside the one printer."
  '#{clojure.core/pr-str clojure.core/prn-str clojure.core/print-str clojure.core/with-out-str
     clojure.core/pr clojure.core/prn clojure.core/printf clojure.pprint/pprint clojure.pprint/pp
     clojure.pprint/write clojure.pprint/cl-format})

(def ^:private budgeted-vars
  "The printer's calls that cut to a budget: a cut where a value is made, outside a render."
  '#{seon.print/text seon.print/line seon.print/prose})

(def ^:private cut-vars
  "The vars that shorten what is shown, where output is made for a person or a model."
  '#{clojure.core/subs clojure.core/take clojure.core/take-last clojure.core/drop-last})

(defn- src-program
  "Main's db with every file of src imported (each form analyzed, never run: the running
  generation's namespaces), as seon.source-test imports it."
  []
  (let [files (:value (seon.fx/read-source {:req {:root (System/getProperty "user.dir") :dirs ["src"]}}))]
    (doseq [n (keep #(seon.source/file-ns (val %)) files)] (require n))
    (let [db (ts/test-db)] (d/db-with db (seon.source/import-tx db files)))))

(defn- sites
  "The vars of src whose forms reference one of marks (the compiler's :form/refs), in
  namespaces ns? holds."
  [forms marks ns?]
  (sort (distinct (for [f forms :when (and (ns? (:ns f)) (some marks (:refs f)))
                        ;; a var's form, or a method's (named by its multi)
                        [k sym] (:defines f) :when (#{:var :method} k)]
                    sym))))

(deftest one-printer-and-no-cut-before-it
  ;; owner, 2026-10-22: "Do not create more than one value printer and no clipping happens
  ;; before then". Every var of src that prints beside seon.print, and every var of a
  ;; display namespace that cuts, is named in docs/render.md#one-printer with its reason
  ;; (the wire, storage, code text, or a cut of data, not of output); a new one is red.
  (let [forms (seon.program/live-forms (src-program))
        doc (let [t (slurp "docs/render.md")] (subs t (str/index-of t "## One printer") (or (str/index-of t "\n## " (+ 3 (str/index-of t "## One printer"))) (count t))))
        ;; a site is named by a list item, before its reason (`- `ns/f`, `ns/g`: why`)
        named (set (for [[_ head] (re-seq #"(?m)^- (.*?): " doc) [_ n] (re-seq #"`([a-z][\w.\-]*/[^`\s]+)`" head)] (symbol n)))
        printer? #(not (str/starts-with? (str %) "seon.print"))
        display? #(re-find #"^(my\.|seon\.(render|web|context|mcp|server|cost|repl|agent|problems)($|\.))" (str %))]
    (testing "src is imported: the check reads facts, not nothing"
      (is (< 1000 (count forms))))
    (testing "every var printing beside the one printer is named with its reason"
      (is (= [] (remove named (sites forms printer-vars printer?)))))
    (testing "every var cutting in a display namespace is named with its reason"
      (is (= [] (remove named (sites forms cut-vars display?)))))
    (testing "every var cutting to a budget where a value is made, outside a display namespace, is named"
      (is (= [] (remove named (sites forms budgeted-vars #(and (printer? %) (not (display? %))))))))
    (testing "a site named that no longer prints or cuts is gone from the page too"
      (is (= [] (remove (set (concat (sites forms printer-vars printer?) (sites forms cut-vars display?)
                                     (sites forms budgeted-vars #(and (printer? %) (not (display? %))))))
                        (sort named)))))))

(deftest an-outside-model-reads-an-evaluation-as-the-repl-says-it
  ;; seon_eval's tool text (seon.server/mcp-reply): reply items as a REPL shows them, never
  ;; the items' map printed with its strings escaped
  (let [text (ai/items [{:id 0 :tag :ret :form "(def outside-x 41)" :printed "#'user/outside-x" :handle :h0}
                        {:id 1 :tag :ret :form "(println \"hi\") (inc outside-x)" :out "hi\n" :printed "42" :handle :h1}
                        {:id 2 :tag :err :form "(/ 1 0)" :printed "Divide by zero\n(seon.agent/eval-error in seon.repl/eval-code)"}])]
    (is (= (str "(def outside-x 41)\n=> #'user/outside-x\n;; result/h0\n\n"
                "(println \"hi\") (inc outside-x)\nhi\n=> 42\n;; result/h1\n\n"
                "(/ 1 0)\nx Divide by zero\n(seon.agent/eval-error in seon.repl/eval-code)")
           text))))

(deftest the-model-reads-a-rendered-value-as-its-repl-did
  ;; the context's last evaluations (seon.context/shown-evals): a value whose producer named
  ;; its render shows that render's text, as the agent's REPL answered it; other values print
  ;; structurally from the live value
  (let [t (with-meta failed-task {:seon.render/schema :seon.task/task})
        [shown plain] (seon.context/shown-evals [{:k 0 :times 1 :item {:tag :ret :form "(my.task/current)" :value t :printed (:text (r/printed t '(nth (my.turn/evals) 0)))}}
                                                 {:k 1 :times 1 :item {:tag :ret :form "{:n 1}" :value {:n 1} :printed "{:n 1}"}}])]
    (is (str/includes? (:text shown) "task calc/add · work · held"))
    (testing "the render's text as text, its lines real lines, never an escaped string literal"
      (is (str/includes? (:text shown) "\nfailed: add cannot be total"))
      (is (not (str/includes? (:text shown) "\\n"))))
    (is (not (str/includes? (:text shown) ":seon.task/accumulated")))
    (is (str/includes? (:text plain) ":value {:n 1}"))))
