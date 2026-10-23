;; The printer evaluation's variants (task: printer-eval, rows 37 and 38 of the shrink research):
;; the corpus (corpus.edn) rendered at the real budgets through the current printer and through
;; each alternative, side by side. Writes notes/printer-eval/renders.edn:
;; [{:id :candidate :site :value-id :variants {variant text}}], only entries whose variants differ.
;; Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/variants.clj
;;
;; Row 38 (one string cutter): :A today (a string inside a value: `"prefix"…+N (subs …)`, cut at a
;; line end inside its growth; prose: `prefix …+N chars in M more lines (subs src n)`, cut at a line
;; break in the room's second half else a space in its final quarter), :B select's cut everywhere
;; (prose too reads `…+N (subs src n)` and breaks at a line end else anywhere), :C prose's cut
;; everywhere (a string inside a value too breaks at a line or word and says `…+N chars in M more
;; lines (subs …)`).
;; Row 37 (:cuts): :A today (each cut inline, with its form; :cuts unread), :C inline plus a closing
;; "left out" section built from :cuts, :D inline markers «k» with the forms as footnotes.
(require '[seon.edn :as edn] '[seon.print :as print] '[seon.print.emit :as emit] '[seon.print.select :as select]
         '[seon.render :as render] '[seon.render.ai :as ai] '[seon.render.ui :as ui] '[seon.context :as context]
         '[seon.config :as config] '[clojure.string :as str] '[clojure.test :as t])

(def corpus (into {} (for [c (edn/decode (slurp "notes/printer-eval/corpus.edn"))] [(:id c) c])))
(defn v [id] (let [{:keys [value schema]} (corpus id)] (cond-> value schema (vary-meta assoc :seon.render/schema schema))))

;;; --- production-shaped evaluations, from the corpus ------------------------------------------

(t/deftest add-test (t/is (= 3 (- 1 2))) (t/is (= 0 (- -2 2))))
(t/deftest tasks-test (t/is (= #{:done} (set (map :seon.task/execution (vals (v "tasks-calc")))))))
(def test-out (with-out-str (binding [t/*test-out* *out*] (t/run-tests 'user))))

(def items
  {"slurp-agents" {:form "(slurp \"AGENTS.md\")" :value (v "doc-agents")}
   "tasks" {:form "(my.task/tasks)" :value (v "tasks-calc")}
   "log" {:form "(println (slurp \"runs/root.log\"))" :value nil :out (slurp "target/eval-calc.log")}
   "http-error" {:form "(seon.model/call provider request)" :tag :err :value (v "ex-info-http")}
   "datoms" {:form "(vec (d/datoms (my.db/db) :eavt))" :value (v "datoms-calc")}
   "attempts" {:form "(my.task/attempts)" :value (v "attempts-stuck")}
   "run-tests" {:form "(clojure.test/run-tests 'app.proofs)" :value {:test 2 :pass 0 :fail 3 :error 0 :type :summary} :out test-out}
   "config" {:form "(my.config/all)" :value (v "config")}
   "turns" {:form "(my.db/q '[:find [(pull ?e [*]) ...] :where [?e :seon.turn/id]])" :value (v "turns-stuck-r2")}
   "doc-render" {:form "(slurp \"docs/render.md\")" :value (v "doc-render")}})

;;; --- the paths cuts carry --------------------------------------------------------------------

(defn step [parent s]
  (let [tag (when (vector? s) (first s))]
    (cond (= tag :seon.render.data/entry) (let [e (nth (seq parent) (second s))] (if (= :seon.render.data/key (nth s 2)) (key e) (val e)))
          (= tag :seon.render.data/nth) (nth (seq parent) (second s))
          (map? parent) (get parent s)
          :else (nth (seq parent) s))))

(def break-at @#'seon.print/break-at)
(def lines-after @#'seon.print/lines-after)

;;; --- row 38 ------------------------------------------------------------------------------------

(defn c38-value
  "Variant :C of a printer result r of value: each string cut ends where prose's would (a line
  break, else a word), and says what prose's says."
  [r value]
  (reduce (fn [text c]
            (if (not= :characters (:seon.print/elision-unit c))
              text
              (let [s (reduce step value (:seon.render.data/path c))
                    s (if (string? s) s (str s))
                    room (count (:seon.print/prefix c))
                    at (break-at s room)
                    at (if (zero? at) room at)
                    cont (when (:seon.print/continuation c)
                           (emit/continuation (assoc c :seon.render.data/next-offset at)))
                    new (str (emit/string-literal (subs s 0 at)) (print/cut-words (- (count s) at) (lines-after s at) nil)
                             (when cont (str " " (emit/form-text cont))))
                    old (emit/elided-text c)]
                (assert (str/includes? text old) (str "no cut text " old))
                (str/replace-first text old new))))
          (:text r) (:cuts r)))

(defn b38-prose
  "Variant :B of seon.print/prose: select's string cut (a line end inside the room, else the
  room) and its words, `…+N` and the form."
  [s budget {:keys [source one-line?]}]
  (let [whole (or s "")
        ^String s (cond-> whole one-line? (-> (str/replace #"\s+" " ") str/trim))
        cap (long (Math/floor (* budget 3.2)))]
    (if (<= (count s) cap)
      {:text s :truncated? false}
      (let [form (fn [at] (when source (list 'subs source at)))
            room (max 0 (- cap 2 (count (str "…+" (count whole))) (count (str (form (count whole))))))
            nl (.lastIndexOf s "\n" (int (dec room)))
            at (if (pos? nl) (inc nl) room)
            shown (subs s 0 at)
            src-at (if one-line? (@#'seon.print/source-offset whole at) at)
            words (str "…+" (- (count whole) src-at) (when source (str " " (emit/form-text (form src-at)))))]
        {:text (if (str/ends-with? shown "\n") (str shown words) (str shown " " words)) :truncated? true}))))

;;; --- row 37 ------------------------------------------------------------------------------------

(defn- unit-words [c]
  (case (:seon.print/elision-unit c)
    :characters (str (:seon.print/omitted c) " characters")
    :children (str (:seon.print/omitted c) " members")
    "members not yet counted"))

(defn c37
  "Variant :C: the text, then a closing section listing every cut: where, what it left, the form."
  [r]
  (if (empty? (:cuts r))
    (:text r)
    (str (:text r) "\n;; left out, each read whole by its form:\n"
         (str/join "\n" (for [c (:cuts r)]
                          (str ";; at " (pr-str (:seon.render.data/path c)) ": " (unit-words c) " not shown"
                               (when-let [f (:seon.print/continuation c)] (str ": " (emit/form-text f)))))))))

(defn d37
  "Variant :D: each inline cut keeps its count and a marker «k»; the forms are footnotes."
  [r]
  (let [cs (filter :seon.print/continuation (:cuts r))]
    (if (empty? cs)
      (:text r)
      (let [[text notes] (reduce (fn [[text notes] [k c]]
                                   (let [f (str " " (emit/form-text (:seon.print/continuation c)))
                                         m (str " «" (inc k) "»")]
                                     (assert (str/includes? text f))
                                     [(str/replace-first text f m) (conj notes (str ";; «" (inc k) "» " (emit/form-text (:seon.print/continuation c))))]))
                                 [(:text r) []] (map-indexed vector cs))]
        (str text "\n;; to read what «k» left:\n" (str/join "\n" notes))))))

;;; --- the sites: where a value is read, at its real budget ---------------------------------------

(def handle 'result/h3)
(def reply-budget (:reply print/budgets))
(def part-budget (config/value :seon.config.render/part-budget))

(defn reply-site
  "The REPL's answer (seon.render/printed under the reply budget)."
  [value]
  (let [r (render/printed value handle)]
    (when (:cuts r) r)))

(defn context-site
  "One turn's evaluations as the model reads them (seon.context/shown-evals: each an equal share
  of the evaluations budget)."
  [its]
  (let [folded (context/fold-evals its)]
    (mapv (fn [{:keys [k item]} r] {:k k :item item :r r})
          folded
          (for [{:keys [k item]} folded
                :let [share (max 32 (quot context/last-evals-budget (max 1 (count folded))))]]
            (print/print-value (#'context/shown-eval item) {:handle (list 'nth '(my.turn/evals) k) :budget share})))))

(defn part-site [value] (print/print-value value {:handle handle :budget part-budget}))

(def entries (atom []))
(defn facts [r] (vec (for [c (:cuts r)] [(:seon.render.data/path c) (:seon.print/omitted c) (:seon.print/elision-unit c) (some-> (:seon.print/continuation c) emit/form-text)])))
(defn add! [m] (when (apply not= (vals (:variants m))) (swap! entries conj m)))

;; 37 and 38's :C on printed values: the reply, a turn's evaluations, a value inside another
(doseq [[id value] (concat (for [[k it] items :when (some? (:value it))] [(str "reply/" k) (:value it)])
                           (for [k ["tasks-calc" "attempts-calc" "config" "turns-square" "problems-stuck" "merge-conflict" "ex-info-http"]] [(str "part/" k) (v k)]))]
  (let [r (if (str/starts-with? id "reply/") (reply-site value) (part-site value))]
    (when (:text r)
      (add! {:id (str "37/" id) :candidate 37 :site (first (str/split id #"/")) :value-id id :facts (facts r) :variants {:A (:text r) :C (c37 r) :D (d37 r)}})
      (add! {:id (str "38/" id) :candidate 38 :site (first (str/split id #"/")) :value-id id :variants {:A (:text r) :C (c38-value r value)}}))))

(doseq [[tid ks] {"turn-1" ["tasks" "log" "http-error" "slurp-agents"]
                  "turn-2" ["run-tests" "attempts" "datoms" "config" "turns" "doc-render"]
                  "turn-3" ["log" "run-tests"]}]
  (let [its (mapv #(items %) ks)
        rs (context-site its)
        join (fn [f] (str/join "\n" (for [{:keys [r]} rs] (f r))))]
    (add! {:id (str "37/context/" tid) :candidate 37 :site "context" :value-id tid :facts (vec (mapcat (comp facts :r) rs)) :variants {:A (join :text) :C (join c37) :D (join d37)}})
    (add! {:id (str "38/context/" tid) :candidate 38 :site "context" :value-id tid
           :variants {:A (join :text) :C (str/join "\n" (for [{:keys [r item]} rs] (c38-value r (#'context/shown-eval item))))}})))

;; 38's prose: an error's message and fix, a plan, a form, an output, as the model and the page read them
(def prose-cases
  [["out/log" (:out (items "log")) part-budget '(:out (nth (my.turn/evals) 1)) false]
   ["out/run-tests" (:out (items "run-tests")) part-budget '(:out (nth (my.turn/evals) 0)) false]
   ["message/http" (get-in (v "ex-info-http") [:seon.error/data :body]) part-budget '(get-in result/h3 [:seon.error/data :body]) false]
   ["plan/agents" (v "doc-agents") 600 '(:seon.task/plan result/h3) false]
   ["context/stuck" (v "context-stuck-r2-0") 256 nil false]
   ["line/log" (:out (items "log")) 25 nil true]
   ["line/run-tests" (:out (items "run-tests")) 50 nil true]
   ["line/plan" (v "doc-render") 50 '(:seon.task/plan result/h3) true]])

(doseq [[id s budget source one-line?] prose-cases]
  (add! {:id (str "38/prose/" id) :candidate 38 :site (if one-line? "line" "prose") :value-id id
         :variants {:A (:text (print/prose s budget (cond-> {:one-line? one-line?} source (assoc :source source))))
                    :B (:text (b38-prose s budget {:source source :one-line? one-line?}))}}))

(spit "notes/printer-eval/renders.edn" (edn/encode @entries))
(doseq [e @entries] (println (:id e) (into {} (for [[k t] (:variants e)] [k (select/estimate t)])) (:facts e)))

;;; --- round 2: a one-line cell's cut without the line count (:E) --------------------------------
;; Round 1 split on one-line cells: select's shorter words showed more of the line. :E keeps prose's
;; break and its `chars` unit but, on one line, leaves out `in M more lines` (lines a cell does not show).

(defn e38-line [s budget source]
  (let [whole (or s "")
        ^String s (-> whole (str/replace #"\s+" " ") str/trim)
        cap (long (Math/floor (* budget 3.2)))
        words (fn [omitted at] (str "…+" omitted " chars" (when source (str " " (emit/form-text (list 'subs source at))))))]
    (if (<= (count s) cap)
      s
      (let [room (max 0 (- cap 1 (count (words (count whole) (count whole)))))
            at (break-at s (min room (count s)))
            src-at (@#'seon.print/source-offset whole at)
            shown (subs s 0 at)]
        (str shown (when-not (or (empty? shown) (str/ends-with? shown " ")) " ") (words (- (count whole) src-at) src-at))))))

(def line-cases
  (concat (filter #(nth % 4) prose-cases)
          [["line/work-form" (:form (v "eval-square-4")) 25 nil true]
           ["line/merge-form" (:form (v "eval-conflict-b-7")) 25 nil true]
           ["line/http-message" (:seon.error/message (v "ex-info-http")) 25 '(:seon.error/message result/h3) true]
           ["line/context" (v "context-stuck-r2-0") 50 nil true]
           ["line/agents" (v "doc-agents") 50 '(:seon.task/plan result/h3) true]]))

(def round2
  (vec (for [[id s budget source one-line?] line-cases]
         {:id (str "38/line2/" id) :candidate 38 :site "line" :value-id id
          :variants {:A (:text (print/prose s budget {:one-line? true :source source}))
                     :B (:text (b38-prose s budget {:source source :one-line? true}))
                     :E (e38-line s budget source)}})))
(spit "notes/printer-eval/renders2.edn" (edn/encode round2))
(doseq [e round2 [k t] (:variants e)] (println (:id e) k t))
