(ns seon.learn
  "The learning loop (lane R4): turns-to-green per template version and step kind, read
  from the attempt facts that already exist; a template version whose recent window is
  below its floor or over its estimate is flagged and an improvement task is filed; a new
  version runs beside the old one on a split by task id until a verdict, and the loser is
  no longer picked.

  Extends, adds no mechanism:
  - task templates (`seon.template`): a template is a value with an estimate; the task
    writer stamps `:seon.task/template` and `:seon.task/template-version`;
  - the attempt facts root's judge already writes (turns, tokens, outcome, results), plus
    the judge's refusal classes (`:seon.attempt/refusals`);
  - the reconciler: `rows` and `flag` are pure functions of main's view, run for the
    template versions of attempts judged in a pass;
  - embeddings by schema property: `found` turns a kNN answer into references marked
    found, not named; on against off is two template versions, judged by `verdict`.
  Nothing is stored that the attempts already hold: a grade and a row are derived."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.walk] [datahike.api :as d] [jolt.crypto]))

(jolt.crypto/install!)

;; ---- content digests ----------------------------------------------------------------

(defn digest
  "The first 16 hex digits of the SHA-256 of s: a content identity (until M1's definition
  digest is a fact, then a template's version is its var's digest)."
  {:malli/schema [:=> [:cat :string] [:string {:min 16 :max 16}]]}
  [s]
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String s))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 8 b)))))

(defn- canonical
  "x printed with sorted maps and no print limits: equal values print equal."
  {:malli/schema [:=> [:cat :any] :string]}
  [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false]
    (pr-str (clojure.walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %) x))))

(defn version
  "A template's version: the digest of its content."
  {:malli/schema [:=> [:cat :seon.template/template] :seon.template/version]}
  [t] (digest (canonical t)))

(defn digits->n
  "s with every run of digits replaced by N (ids, counts and line numbers differ between
  occurrences of one cause): the one normalisation for error signatures."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (let [sb (StringBuilder.)]
    (loop [i 0 in-digits? false]
      (if (= i (count s)) (str sb)
          (let [c (.charAt ^String s i) d? (Character/isDigit c)]
            (when-not (and d? in-digits?) (.append sb (if d? \N c)))
            (recur (inc i) d?))))))

;; ---- picking a template version for a new task --------------------------------------

(defn- bucket
  "A task id's stable bucket among n arms (its digest, so every root picks the same)."
  {:malli/schema [:=> [:cat :seon.task/id pos-int?] nat-int?]}
  [id n] (mod (Long/parseLong (subs (digest id) 0 12) 16) n))

(defn pick
  "The template a new task id of a name gets: among the live versions of that name (the
  current one and its candidates), minus the versions a verdict lost, the one its bucket
  names. templates: the live templates of one name, current first."
  {:malli/schema [:=> [:cat [:vector :seon.template/template] [:set :seon.template/version] :seon.task/id]
                  :seon.template/template]}
  [templates lost id]
  (let [arms (vec (remove #(lost (version %)) templates))
        arms (if (seq arms) arms [(first templates)])]
    (nth arms (bucket id (count arms)))))

(defn stamp
  "Task declaration decl made from template t: its kind and instructions (unless decl
  sets them), and the template's name and version, written by the one task writer."
  {:malli/schema [:=> [:cat :map :seon.template/template] :map]}
  [decl t]
  (cond-> (assoc decl :seon.task/kind (:seon.task/kind t)
                 :seon.task/template (:seon.template/name t) :seon.task/template-version (version t))
    (not (:seon.task/instructions decl)) (assoc :seon.task/instructions (:seon.task/instructions t))))

;; ---- grades: one per task, from its attempts ----------------------------------------

(def ^:private done-status #{:merged :done})

(defn grade
  "Task t's grade from its attempts (any order): done when an attempt merged (a merge
  task) or was accepted (another); turns, tokens and ms summed over every attempt up to
  and including the first done one (every run counts, owner 2026-10-18); the outcome of
  the last attempt counted; the refusal classes the judge measured, by count."
  {:malli/schema [:=> [:cat [:map [:seon.task/id :seon.task/id] [:seon.task/kind :seon.task/kind]]
                        [:sequential [:map [:seon.attempt/id :seon.attempt/id] [:seon.attempt/status :seon.attempt/status]]]]
                  :seon.learn/grade]}
  [t attempts]
  (let [n-of (fn [a] (let [s (:seon.attempt/id a)] (Long/parseLong (subs s (inc (str/last-index-of s "#"))))))
        ordered (sort-by n-of attempts)
        [before after] (split-with #(not (done-status (:seon.attempt/status %))) ordered)
        counted (concat before (take 1 after))
        sum (fn [k] (reduce + 0 (map #(k % 0) counted)))]
    {:task (:seon.task/id t) :kind (:seon.task/kind t)
     :template (:seon.task/template t) :version (:seon.task/template-version t)
     :done? (boolean (seq after)) :attempts (count counted)
     :turns (sum :seon.attempt/turns) :tokens (sum :seon.attempt/tokens)
     :ms (reduce + 0 (for [a counted :when (:seon.attempt/end-ms a)] (- (:seon.attempt/end-ms a) (:seon.attempt/start-ms a))))
     :outcome (:seon.attempt/outcome (last counted))
     :refusals (frequencies (mapcat #(:seon.attempt/refusals % #{}) counted))}))

(defn- quantile
  "The q-quantile of a sorted vector (nearest rank), nil when empty."
  {:malli/schema [:=> [:cat [:vector number?] number?] [:maybe number?]]}
  [xs q] (when (seq xs) (nth xs (min (dec (count xs)) (long (Math/floor (* q (count xs))))))))

(defn row
  "One template version's row over grades gs (its recent window): tasks, done, success
  rate over ended tasks, turns-to-green p50/p90 and tokens p50 over done tasks, how many
  went over the estimate, outcomes and refusal classes, the three worst tasks."
  {:malli/schema [:=> [:cat [:sequential :seon.learn/grade] [:maybe :seon.template/estimate]] :seon.learn/row]}
  [gs estimate]
  (let [g0 (first gs)
        done (filter :done? gs)
        ended (filter #(or (:done? %) (:outcome %)) gs)
        ttg (vec (sort (map :turns done)))
        worst (->> gs (sort-by (juxt :done? (comp - :turns))) (take 3) (mapv :task))]
    {:kind (:kind g0 :work) :template (:template g0) :version (:version g0)
     :n (count gs) :done (count done) :open (- (count gs) (count ended))
     :rate (when (seq ended) (/ (double (count done)) (count ended)))
     :ttg-p50 (quantile ttg 0.5) :ttg-p90 (quantile ttg 0.9)
     :tokens-p50 (quantile (vec (sort (map :tokens done))) 0.5)
     :over (if estimate (count (filter #(> (:turns %) (:turns estimate)) done)) 0)
     :outcomes (frequencies (keep :outcome gs))
     :refusals (apply merge-with + {} (map :refusals gs))
     :worst worst}))

(defn rows
  "Rows per [kind template version] over grades gs, each over its most recent `window`
  tasks (gs in task creation order). estimates: version -> estimate."
  {:malli/schema [:=> [:cat [:sequential :seon.learn/grade] [:map-of :string :seon.template/estimate] pos-int?]
                  [:vector :seon.learn/row]]}
  [gs estimates window]
  (vec (for [[[_ _ v] g] (sort-by (comp str key) (group-by (juxt :kind :template :version) gs))]
         (row (vec (take-last window g)) (get estimates v)))))

;; ---- flags and the improvement task ----------------------------------------------------

(def dials
  "The learning loop's dials when the config sets none: a row over the last 200 tasks;
  judged from 10 ended tasks; flagged under 60% success or a median turns-to-green over
  1.5x the estimate; a trial decided from 30 tasks per arm at |z| >= 1.96."
  {:window 200 :min-n 10 :floor 0.6 :slack 1.5 :trial-n 30 :z 1.96})

(defn flag
  "Why template row r needs improving, or nil: judged only from min-n ended tasks."
  {:malli/schema [:=> [:cat :seon.learn/row [:maybe :seon.template/estimate] :seon.learn/dials] [:maybe :seon.learn/flag]]}
  [r estimate {:keys [min-n floor slack]}]
  (let [ended (- (:n r) (:open r))]
    (when (>= ended min-n)
      (cond
        (< (:rate r) floor)
        {:rule :success-below-floor
         :reason (format "%d of %d ended tasks green (%.0f%%) < floor %.0f%%" (:done r) ended (* 100 (:rate r)) (* 100 floor))}
        (and estimate (:ttg-p50 r) (> (:ttg-p50 r) (* slack (:turns estimate))))
        {:rule :turns-over-estimate
         :reason (format "median turns-to-green %s > %.1f x estimate %d" (:ttg-p50 r) slack (:turns estimate))}))))

(defn improve-decl
  "The task that improves template t, whose row r was flagged f: a design step (its proof
  may stay red: it writes a deftest asserting what the worst tasks' context lacked) whose
  plan names the template, the flag and the measured evidence; filed once per flagged
  version (its id derives from the version, so filing twice is one task)."
  {:malli/schema [:=> [:cat :seon.template/template :seon.learn/row :seon.learn/flag [:sequential :map]] :map]}
  [t r f evidence]
  (let [v (version t)]
    {:seon.task/ns 'seon.templates :seon.task/name (str "improve-" (name (:seon.template/name t)) "-" (subs v 0 8))
     :seon.task/kind :design :seon.task/about (str "template " v)
     :seon.task/plan
     (str "Template " (:seon.template/name t) " of `seon.template/templates` (version " v ", kind " (name (:kind r)) ") was flagged: "
          (:reason f) ".\nMeasured over its last " (:n r) " tasks: outcomes " (pr-str (:outcomes r))
          ", the judge's refusals " (pr-str (:refusals r)) ", turns-to-green p50 " (:ttg-p50 r) " p90 " (:ttg-p90 r)
          ".\nWorst tasks: " (str/join ", " (map #(str "`" % "`") (:worst r)))
          ".\nWrite a deftest asserting what their context lacked, then a candidate version that `:seon.template/replaces` "
          v " with an honest estimate. The candidate runs beside this version until a verdict (`seon.learn/verdict`).")
     :seon.task/evidence {:kind :template :version v :worst (vec evidence)}}))

;; ---- the trial: two versions of one template, judged by measured grades ----------------

(defn- two-proportion-z
  "z of the difference in success between arms a and b ({:done :ended})."
  {:malli/schema [:=> [:cat :map :map] number?]}
  [a b]
  (let [pa (/ (double (:done a)) (max 1 (:ended a))) pb (/ (double (:done b)) (max 1 (:ended b)))
        p (/ (double (+ (:done a) (:done b))) (max 1 (+ (:ended a) (:ended b))))
        se (Math/sqrt (* p (- 1 p) (+ (/ 1.0 (max 1 (:ended a))) (/ 1.0 (max 1 (:ended b))))))]
    (if (zero? se) 0.0 (/ (- pb pa) se))))

(defn- mann-whitney-z
  "z of Mann-Whitney U for turns xs (arm a) against ys (arm b): positive when b takes
  fewer turns. O((n+m) log(n+m)), ties at their mean rank."
  {:malli/schema [:=> [:cat [:sequential number?] [:sequential number?]] number?]}
  [xs ys]
  (let [n (count xs) m (count ys)
        all (sort-by first (concat (map #(vector % :a) xs) (map #(vector % :b) ys)))
        ranks (loop [[g & more] (partition-by first all) r 1 acc []]
                (if-not g acc
                        (let [k (count g) mean (+ r (/ (dec k) 2.0))]
                          (recur more (+ r k) (into acc (map #(vector (second %) mean) g))))))
        ra (reduce + 0.0 (keep #(when (= :a (first %)) (second %)) ranks))
        u (- ra (/ (* n (inc n)) 2.0))
        mu (/ (* n m) 2.0)
        sd (Math/sqrt (/ (* n m (+ n m 1)) 12.0))]
    (if (zero? sd) 0.0 (/ (- u mu) sd))))

(defn verdict
  "The verdict between the current version's grades ga and a candidate's gb: undecided
  until each arm has trial-n ended tasks; then the arm with significantly more green
  tasks wins; else the arm with significantly fewer turns-to-green; else no measured gain,
  and the candidate loses (a change without a measured gain is not kept)."
  {:malli/schema [:=> [:cat [:sequential :seon.learn/grade] [:sequential :seon.learn/grade] :seon.learn/dials]
                  :seon.learn/verdict]}
  [ga gb {:keys [trial-n z]}]
  (let [arm (fn [gs] (let [ended (filter #(or (:done? %) (:outcome %)) gs)]
                       {:ended (count ended) :done (count (filter :done? ended)) :ttg (map :turns (filter :done? ended))}))
        a (arm ga) b (arm gb)
        va (:version (first ga)) vb (:version (first gb))
        n [(:ended a) (:ended b)]]
    (if (< (min (:ended a) (:ended b)) trial-n)
      {:decided? false :n n}
      (let [zs (two-proportion-z a b)
            zt (mann-whitney-z (:ttg a) (:ttg b))
            win (fn [by zz better-b?] {:decided? true :by by :z zz :n n
                                       :winner (if better-b? vb va) :loser (if better-b? va vb)})]
        (cond (>= (Math/abs zs) z) (win :success zs (pos? zs))
              (>= (Math/abs zt) z) (win :turns zt (pos? zt))
              :else (win :no-gain zt false))))))

;; ---- embedding queries per step template, hits found and not named --------------------

(defn query-text
  "The text a template's embedding query embeds for task t (its :from parts joined):
  instructions, the accumulated plan, the red assertions, the conflicts, the error."
  {:malli/schema [:=> [:cat :seon.template/embed :map] :string]}
  [{:keys [from]} {:keys [instructions plan red conflicts error]}]
  (str/join "\n" (keep {:instructions instructions :plan plan :red (some-> red pr-str)
                        :conflicts (some-> conflicts pr-str) :error (some-> error pr-str)}
                       from)))

(defn found
  "kNN hits [[symbol score]] as references found, not named: those the plan does not
  already name, best first, at most k."
  {:malli/schema [:=> [:cat [:sequential [:tuple :qualified-symbol number?]] [:set :qualified-symbol] pos-int?]
                  [:vector :seon.learn/hit]]}
  [hits named k]
  (->> hits (remove (comp named first)) (sort-by (comp - second)) (take k)
       (mapv (fn [[s x]] {:symbol s :score x :found true}))))

;; ---- the view (W1: a render returns its HTML face; the AI face is the row data) --------

(defn rows-html
  "The templates view: one row per template version, flagged rows marked."
  {:malli/schema [:=> [:cat [:vector :seon.learn/row] [:map-of :string :seon.template/estimate] :seon.learn/dials]
                  [:map [:seon.render/html vector?]]]}
  [rs estimates dls]
  (let [pct #(if % (format "%.0f%%" (* 100.0 %)) "-")]
    {:seon.render/html
     [:table.templates
      [:tr [:th "kind"] [:th "template"] [:th "version"] [:th "tasks"] [:th "green"] [:th "turns p50/p90"]
       [:th "estimate"] [:th "tokens p50"] [:th "refusals"] [:th "flag"]]
      (for [r rs :let [e (get estimates (:version r)) f (flag r e dls)]]
        [:tr {:class (when f "flagged") :id (str "tpl-" (:version r))}
         [:td (some-> (:kind r) name)] [:td (some-> (:template r) name)] [:td [:code (some-> (:version r) (subs 0 8))]]
         [:td (:n r)] [:td (pct (:rate r))] [:td (str (or (:ttg-p50 r) "-") " / " (or (:ttg-p90 r) "-"))]
         [:td (or (:turns e) "-")] [:td (or (:tokens-p50 r) "-")]
         [:td (str/join " " (for [[k c] (sort-by (comp - val) (:refusals r))] (str (name k) "×" c)))]
         [:td (or (:reason f) "")]])]}))

;; ---- over main's view: grades, flags, the trial's losers ----------------------------------

(defn version-tasks
  "The tasks of view v made from template version ver, found through the
  :seon.task/template-version index of db (O(tasks of ver), never a scan of every task)."
  {:malli/schema [:=> [:cat :seon.host/db :map :seon.template/version] [:vector :map]]}
  [db v ver]
  (vec (for [[id nm] (d/q '[:find ?id ?nm :in $ ?v :where [?e :seon.task/template-version ?v] [?e :seon.task/id ?id]
                            [(get-else $ ?e :seon.task/template :none) ?nm]] db ver)
             :let [t (get-in v [:tasks id])] :when t]
         (cond-> (assoc t :seon.task/template-version ver) (not= :none nm) (assoc :seon.task/template nm)))))

(defn grades-in
  "The grades of the last w of tasks ts (one version's) on view v, in the order their
  first attempts started: O(|ts| log |ts|) to sort, O(w) to grade."
  {:malli/schema [:=> [:cat :map [:maybe [:vector :map]] pos-int?] [:vector :seon.learn/grade]]}
  [v ts w]
  (let [first-ms (fn [t] (reduce min Long/MAX_VALUE (map :seon.attempt/start-ms (get-in v [:attempts (:seon.task/id t)]))))]
    ;; each task's key computed once (a key function inside the comparator would read its
    ;; attempts O(log n) times)
    (vec (for [[_ _ t] (take-last w (sort (map (fn [t] [(first-ms t) (:seon.task/id t) t]) ts)))]
           (grade t (get-in v [:attempts (:seon.task/id t)] []))))))

(defn by-version
  "templates (name -> template, plus candidates under their own keys) by version."
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.template/template]] [:map-of :string :seon.template/template]]}
  [templates] (into {} (for [t (vals templates)] [(version t) t])))

(defn lost
  "The versions a decided trial lost on view v: each candidate (a template that
  :seon.template/replaces a version) against the version it replaces."
  {:malli/schema [:=> [:cat :seon.host/db :map [:map-of :keyword :seon.template/template] :seon.learn/dials] [:set :string]]}
  [db v templates dls]
  (let [bv #(version-tasks db v %)]
  (into #{} (for [t (vals templates) :let [old (:seon.template/replaces t)] :when old
                  :let [vd (verdict (grades-in v (bv old) (:window dls)) (grades-in v (bv (version t)) (:window dls)) dls)]
                  :when (:decided? vd)]
              (:loser vd)))))

(defn actions
  "The reconciler's learning actions on main db (its view v): for each template version
  with an attempt judged in this pass (status :ended), file its improvement task when its
  row is flagged, no improvement of it exists and no trial of its name is undecided.
  O(judged) lookups, then O(tasks of the judged versions)."
  {:malli/schema [:=> [:cat :seon.host/db :map [:map-of :keyword :seon.template/template] :seon.learn/dials]
                  [:vector [:map [:op [:= :improve]] [:version :string] [:reason :string]]]]}
  [db v templates dls]
  (let [bv (by-version templates)
        judged (into #{} (for [as (vals (:attempts v)) a as :when (= :ended (:seon.attempt/status a))
                               :let [ver (d/q '[:find ?v . :in $ ?id :where [?e :seon.task/id ?id] [?e :seon.task/template-version ?v]]
                                              db (:seon.attempt/task a))]
                               :when ver]
                           ver))
        grades (fn [ver] (grades-in v (version-tasks db v ver) (:window dls)))
        trial? (fn [nm] (some (fn [t] (and (= nm (:seon.template/name t)) (:seon.template/replaces t)
                                           (not (:decided? (verdict (grades (:seon.template/replaces t)) (grades (version t)) dls)))))
                              (vals templates)))]
    (vec (for [ver (sort judged)
               :let [t (bv ver)] :when t
               :let [e (:seon.template/estimate t)
                     r (row (grades ver) e)
                     f (flag r e dls)
                     d (when f (improve-decl t r f []))]
               :when (and f (nil? (get-in v [:tasks (str (:seon.task/ns d) "/" (:seon.task/name d))]))
                          (not (trial? (:seon.template/name t))))]
           {:op :improve :version ver :reason (:reason f)}))))

;; ---- waste, charged to the producer of what the agent was shown (census idea 14) --------

(def ^:private explore-ops
  "Heads of forms that look for code instead of changing it."
  '#{source doc dir ns-publics ns-interns ns-map meta resolve find-var all-ns apropos find-doc source-fn})

(defn- read-forms
  "The forms of source text s, read without evaluation; [] when it does not read."
  {:malli/schema [:=> [:cat :string] [:vector :any]]}
  [s]
  (try (binding [*read-eval* false] (vec (read-string (str "[" s "\n]"))))
       (catch Exception _ [])))

(defn form-reads
  "What one evaluated form reads, by its shape: :explore? when its head (or a nested
  head) looks for code, and the qualified symbols and namespaces it names (aliases
  resolved through as)."
  {:malli/schema [:=> [:cat :string [:map-of :symbol :symbol]] [:map [:explore? :boolean] [:targets [:set :symbol]]]]}
  [s as]
  (let [nodes (mapcat #(tree-seq coll? seq %) (read-forms s))
        expand (fn [sym] (if-let [a (and (namespace sym) (as (symbol (namespace sym))))] (symbol (str a) (name sym)) sym))]
    {:explore? (boolean (some #(and (seq? %) (symbol? (first %)) (explore-ops (symbol (name (first %))))) nodes))
     :targets (into #{} (comp (filter symbol?) (map expand)
                              (filter #(or (namespace %) (str/includes? (str %) ".")))
                              (remove #(#{"clojure.core" "clojure.repl" "clojure.string"} (or (namespace %) (str %)))))
                    nodes)}))

(defn aliases
  "The aliases the forms of an attempt set up with (require '[ns :as a]): a -> ns."
  {:malli/schema [:=> [:cat [:sequential :string]] [:map-of :symbol :symbol]]}
  [forms]
  (into {} (for [f forms n (mapcat #(tree-seq coll? seq %) (read-forms f))
                 :when (and (vector? n) (symbol? (first n)) (some #{:as} n))
                 :let [a (second (drop-while #(not= :as %) n))] :when (symbol? a)]
             [a (first n)])))

(defn waste
  "The waste of an attempt's turns, each charged to a producer: turns [{:n :shown {producer
  #{symbol}} :items [{:form :error}]}] and final, the qualified symbols the attempt's
  accepted definitions use. A form that looks for code shown already is :re-read (charged
  to the producer that showed it: its render did not satisfy); one that looks for code the
  final definitions use but nothing showed is :missing (charged to :template: the
  template's references or embedding query did not reach it); one that looks for anything
  else is :search (charged to :template). An error repeating an earlier turn's signature is
  :repeats-error (charged to the render that delivered it, the last evaluations)."
  {:malli/schema [:=> [:cat [:sequential :map] [:set :symbol]] [:vector [:map [:turn :int] [:rule :keyword] [:producer :any] [:form :string]]]]}
  [turns final]
  (let [as (aliases (for [t turns it (:items t)] (:form it)))
        final-ns (into #{} (keep #(some-> (namespace %) symbol)) final)]
    (loop [[t & more] turns seen-errors #{} acc []]
      (if-not t
        acc
        (let [shown-by (into {} (for [[p syms] (:shown t) s syms] [s p]))
              charged (for [{:keys [form error]} (:items t)
                            :let [{:keys [explore? targets]} (form-reads form as)
                                  sig (some-> error digits->n)]
                            r [(cond
                                 (and sig (seen-errors sig)) {:rule :repeats-error :producer 'seon.context/current-state}
                                 (not explore?) nil
                                 (and (seq targets) (every? shown-by targets)) {:rule :re-read :producer (shown-by (first targets))}
                                 (some #(or (final %) (final-ns %)) targets) {:rule :missing :producer :template}
                                 :else {:rule :search :producer :template})]
                            :when r]
                        (assoc r :turn (:n t) :form (subs form 0 (min 80 (count form)))))]
          (recur more (into seen-errors (keep #(some-> (:error %) digits->n) (:items t))) (into acc charged)))))))

(defn waste-summary
  "Waste by producer: {producer {:forms n :turns n}}; a turn counts for a producer when
  any of its forms was charged to it."
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of :any [:map [:forms :int] [:turns :int]]]]}
  [ws]
  (into {} (for [[p xs] (group-by :producer ws)] [p {:forms (count xs) :turns (count (distinct (map :turn xs)))}])))
