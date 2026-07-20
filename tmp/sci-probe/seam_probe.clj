;; sci routing-seam probe (research harness, not production).
;; Demonstrates: (1) :load-fn provisioning a REMOTE-hosted namespace as
;; generated stub source whose public fns are remote-call wrappers
;; (remote simulated by a local host fn); (2) laziness + caching;
;; (3) :namespaces injection variant; (4) fork semantics: registry-backed
;; load-fn propagates NEW capability namespaces to already-forked live
;; contexts lazily, while direct env injection does not; (5) unresolved
;; symbol behavior at analysis time (the missing :resolve-fn hook).
;; Run: clj -Sdeps '{:deps {org.babashka/sci {:local/root "reference-code/sci"}}}' -M tmp/sci-probe/seam_probe.clj
(ns seam-probe
  (:require [sci.core :as sci]))

(def remote-calls (atom []))

(defn remote-call
  "The one host-side boundary fn: pure-data request in, pure-data value out.
  Simulates the UDS/transit hop."
  [req]
  (swap! remote-calls conj req)
  (case (:fn req)
    seon.db/query   {:rows [[:a 1] [:b 2]] :basis-t 42}
    seon.db/pull    {:entity {:db/id (first (:args req))}}
    my.net/fetch    {:status 200 :body (str "fetched:" (first (:args req)))}
    {:seon/error {:code :unknown-remote-fn :fn (:fn req)}}))

(defn stub-source
  "Generated stub namespace: each public fn is a remote-call wrapper.
  This is what the placement layer synthesizes from :seon.ns/require-edges."
  [lib fns]
  (str "(ns " lib ")\n"
       (apply str
              (for [f fns]
                (str "(defn " f " [& args] "
                     "(seon.host/remote-call {:fn '" lib "/" f
                     " :args (vec args)}))\n")))))

;; the provisionable-namespace registry: lib -> seq of public fn names.
;; An atom so a namespace can be added AFTER contexts already exist.
(def remote-namespaces (atom {'seon.db ['query 'pull]}))

(def load-count (atom 0))

(defn load-fn [{:keys [libname]}]
  (when-let [fns (get @remote-namespaces libname)]
    (swap! load-count inc)
    {:file (str libname ".stub") :source (stub-source libname fns)}))

(def base
  (sci/init
   {:namespaces {'seon.host {'remote-call remote-call}}
    :load-fn load-fn}))

(defn ev [ctx s] (sci/eval-string* ctx s))

(println "== 1. :load-fn generated-stub require, lazy")
(println "load-count before:" @load-count)
(println "require+call:"
         (ev base "(require '[seon.db :as db]) (db/query '[:find ?e])"))
(println "load-count after first require:" @load-count)
(ev base "(require '[seon.db :as db2]) (db2/pull 7)")
(println "load-count after second require (cached):" @load-count)
(println "remote calls seen by host:" @remote-calls)

(println "\n== 2. :namespaces injection variant (no source, wrapper fns as values)")
(def inj (sci/init
          {:namespaces {'seon.kv {'get-val (fn [k] (remote-call {:fn 'seon.kv/get-val :args [k]}))}}}))
(println (ev inj "(require '[seon.kv :as kv]) (kv/get-val :x)"))

(println "\n== 3. fork + registry-backed lazy provisioning")
(def agent-a (sci/fork base))
(def agent-b (sci/fork base))
;; provision a NEW capability namespace after the forks exist:
(swap! remote-namespaces assoc 'my.net ['fetch])
(println "agent-a requires my.net (provisioned post-fork):"
         (ev agent-a "(require '[my.net :as net]) (net/fetch \"http://x\")"))
(println "agent-b too:" (ev agent-b "(require '[my.net :as net]) (net/fetch \"http://y\")"))
;; fork isolation: defs in a fork stay private
(ev agent-a "(def secret 1)")
(println "agent-b sees agent-a's def?"
         (try (ev agent-b "secret") (catch Exception e (str "NO: " (.getMessage e)))))
;; direct injection into base env post-fork does NOT reach forks:
(sci/add-namespace! base 'late.ns {'f (fn [] :late)})
(println "base sees late.ns:" (ev base "(require 'late.ns) (late.ns/f)"))
(println "fork sees late.ns?"
         (try (ev agent-a "(require 'late.ns) (late.ns/f)")
              (catch Exception e (str "NO: " (.getMessage e)))))

(println "\n== 4. unresolved symbol at analysis time (no hook today)")
(println (try (ev agent-a "(seon.db2/query 1)")
              (catch Exception e (str (type e) ": " (.getMessage e)))))
(println (try (ev agent-a "(undefined-fn 1)")
              (catch Exception e (str (type e) ": " (.getMessage e)))))

(println "\n== 5. merge-opts on a LIVE ctx mutates its env in place")
(sci/merge-opts agent-a {:namespaces {'cap.new {'g (fn [] :provisioned)}}})
(println "agent-a after merge-opts:" (ev agent-a "(require 'cap.new) (cap.new/g)"))
(println "agent-b unaffected:"
         (try (ev agent-b "(require 'cap.new) (cap.new/g)")
              (catch Exception e (str "NO: " (.getMessage e)))))

(println "\n== 6. what is in a ctx / env (park-restore inventory)")
(println "ctx keys:" (sort (keys agent-a)))
(let [env @(:env agent-a)]
  (println "env keys:" (sort (keys env)))
  (println "namespace count:" (count (:namespaces env)))
  (println "user ns var names:" (keys (dissoc (get-in env [:namespaces 'user]) :aliases :obj :refers :imports :refer))))
