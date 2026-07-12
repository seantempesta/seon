#!/usr/bin/env bb
;; LoRA curation helper — per-form mechanical analysis of drafted Clojure.
;;
;; stdin:  JSON array of {"sid": <str>, "text": <str>}
;; stdout: JSON array of {"sid", "parsed", "error", "forms": [<form-analysis>]}
;;
;; Same reader discipline as kt3_score.clj (edamame :all, auto-resolve),
;; same symbol normalization ({name, last-dot-segment ns}), same call-kind
;; taxonomy. Adds what the curation gates need and the scorer doesn't:
;; per-top-level-form source slices (byte-exact, for target assembly),
;; every map key with its namespace (bare-key detection), nested call heads
;; (hallucinated-fn detection), id-shaped strings (ingredient check), and
;; the first argument of each call (register! attr position).

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[edamame.core :as e])

(def id-re #"[A-Za-z0-9]{3}-26\d{8}")

(defn parse-forms [s]
  (try {:ok true
        :forms (e/parse-string-all s {:all true
                                      :auto-resolve (fn [k] (if (= :current k) "CURNS" (str k)))})}
       (catch Exception ex
         {:ok false :error (.getMessage ex)})))

(defn norm-sym [sym]
  (let [ns' (namespace sym)]
    {:name (name sym)
     :ns (when ns' (last (str/split ns' #"\.")))
     :full (str sym)}))

(defn call-kind [{n :name ns' :ns}]
  (cond
    (#{"in-ns" "ns" "require" "use" "refer" "load-file"} n) :ns-move
    (#{"defn" "defn-" "def" "defonce" "defmacro" "defmethod" "defmulti"} n) :defn
    (= n "register!") :register
    (= n "transact!") :transact
    (and (#{"query" "q" "pull" "pull-by-name" "pull-many" "entity" "datoms" "history" "as-of"} n)
         (or (nil? ns') (= ns' "db"))) :query
    (= ns' "plan") :plan
    :else :other))

(defn all-map-keys
  "Every keyword key of every map anywhere in the form (skips quoted)."
  [form]
  (cond
    (and (seq? form) (= 'quote (first form))) []
    (map? form) (concat (for [k (keys form) :when (keyword? k)]
                          {:kw (str k) :ns (namespace k) :name (name k)})
                        (mapcat all-map-keys (vals form))
                        (mapcat all-map-keys (filter coll? (keys form))))
    (coll? form) (mapcat all-map-keys form)
    :else []))

(defn nested-heads
  "All call-site head symbols in reading order (quoted subtrees skipped)."
  [form]
  (cond
    (seq? form)
    (if (= 'quote (first form))
      []
      (let [self (when (symbol? (first form)) [(norm-sym (first form))])]
        (concat self (mapcat nested-heads (rest form)))))
    (coll? form) (mapcat nested-heads form)
    :else []))

(defn id-strings [form]
  (cond
    (string? form) (re-seq id-re form)
    (coll? form) (mapcat id-strings form)
    :else []))

(defn line-slice
  "Byte-exact source slice for a form's edamame location metadata."
  [lines {:keys [row col end-row end-col]}]
  (if (= row end-row)
    (subs (nth lines (dec row)) (dec col) (dec end-col))
    (str/join "\n"
              (concat [(subs (nth lines (dec row)) (dec col))]
                      (subvec (vec lines) row (dec end-row))
                      [(subs (nth lines (dec end-row)) 0 (dec end-col))]))))

(defn analyze-form [lines form]
  (let [loc (meta form)
        call? (and (seq? form) (symbol? (first form)))
        head (when call? (norm-sym (first form)))
        args (when call? (rest form))
        first-map (when call? (first (filter map? args)))
        top-keys (when first-map
                   (for [k (keys first-map) :when (keyword? k)]
                     {:kw (str k) :ns (namespace k) :name (name k)}))
        farg (when call? (first args))]
    {:src (if (and loc (:row loc)) (line-slice lines loc) (pr-str form))
     :call (boolean call?)
     :head head
     :kind (when head (name (call-kind head)))
     :top-arg-map-keys (vec top-keys)
     :all-map-keys (vec (all-map-keys form))
     :nested-heads (vec (rest (nested-heads form)))  ; rest = drop self head
     :ids (vec (distinct (id-strings form)))
     :first-arg (cond
                  (keyword? farg) {:type "keyword" :kw (str farg)
                                   :ns (namespace farg) :name (name farg)}
                  (map? farg) {:type "map"}
                  (vector? farg) {:type "vector"}
                  (string? farg) {:type "string"}
                  (seq? farg) {:type "list"}
                  (symbol? farg) {:type "symbol" :sym (str farg)}
                  (nil? farg) nil
                  :else {:type (str (type farg))})}))

(defn analyze [{:keys [sid text]}]
  (let [{:keys [ok error forms]} (parse-forms text)
        lines (str/split (str text) #"\n" -1)]
    {:sid sid
     :parsed ok
     :error error
     :forms (when ok (mapv #(analyze-form lines %) forms))}))

(let [rows (json/parse-stream *in* true)]
  (json/generate-stream (mapv analyze rows) *out*)
  (flush))
