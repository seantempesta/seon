(ns seon.browser.journey
  "Headless WKWebView user-journey gate."
  (:require [clojure.core.async :as a] [clojure.data.json :as json] [clojure.string :as str]
            [jolt.fs :as fs] [jolt.http-client :as http]
            [seon.browser :as browser] [seon.browser.measure :as measure] [seon.store :as store])
  (:import [java.io InputStream] [java.net URLEncoder]))

(def default-viewports [[375 812] [1280 800] [1440 900]])
(def default-themes [:dark :light])
(def default-ttfb-budget-ms 80.0)
(def default-journeys [:landing :chat :navigation :explorer :dev-mode :time-travel])
(defn- fnum [x] (when (number? x) (let [d (double x)] (when (and (== d d) (not (Double/isInfinite d))) d))))

(defn- nb [x] (when (and (string? x) (not (str/blank? x))) (str/trim x)))

(defn- r1 [x] (/ (Math/round (* (double x) 10.0)) 10.0))

(defn- snb [xs] (vec (keep nb (when (sequential? xs) xs))))

(defn- kn [x] (if (or (keyword? x) (string? x) (symbol? x)) (name x) (str x)))

(defn- maps [x] (when (sequential? x) (filter map? x)))

(defn- mget [x] (if (map? x) x {}))

(defn- now [] (System/currentTimeMillis))

(defn normalize-url-base
  {:malli/schema [:=> [:cat [:maybe :string]] :string] :cost/ticks 100000}
  [u]
  (let [s (str/trim (str (or u "")))]
    (if (str/blank? s) "http://127.0.0.1:9998" (str/replace (if (str/includes? s "://") s (str "http://" s)) #"/+$" ""))))

(defn install-error-capture-js
  {:malli/schema [:=> [:cat] :string] :cost/ticks 50000}
  []
  (str "(()=>{let d=document,w=window,g=i=>d.getElementById(i),q=s=>d.querySelector(s),E=w.__sjE||=[],W=w.__sjW||=[];"
       "w.__sj={g,q,qa:s=>[...d.querySelectorAll(s)],r:e=>e.getBoundingClientRect(),h:k=>g('seon-header')?.dataset?.[k]||'',ev:(e,t)=>e.dispatchEvent(new Event(t,{bubbles:1})),clk:s=>{let b=q(s);return b&&!b.disabled?(b.click(),1):0;}};"
       "if(w.__sjH)return 1;w.__sjH=1;let P=(k,m)=>{let s=(''+m).slice(0,140);k==='rej'&&d.hidden&&/View transition was skipped/i.test(s)?W.length<20&&W.push(s):E.length<50&&E.push(k+':'+s);};"
       "onerror=(m,s,l)=>(P('onerror',m+(s?`@${s}:${l}`:'')),false);onunhandledrejection=e=>P('rej',e?.reason?.message||e?.reason);"
       "let c=console.error.bind(console);console.error=(...a)=>(P('console.error',a.join(' ')),c(...a));return 1;})()"))

(defn apply-theme-js
  {:malli/schema [:=> [:cat [:maybe [:or :keyword :string]]] :string] :cost/ticks 50000}
  [theme]
  (let [t (if (= "light" (str/lower-case (kn (or theme :dark)))) "light" "dark")]
    (str "(()=>{let d=document,t='" t "',s=d.head.appendChild(d.createElement('style'));s.textContent='*{transition:none!important}';[d.documentElement,d.getElementById('seon-app'),d.getElementById('seon-header')].forEach(e=>e?.setAttribute('data-theme',t));void d.documentElement.offsetHeight;s.remove();return t;})()")))

(defn read-page-telemetry-js
  {:malli/schema [:=> [:cat] :string] :cost/ticks 100000}
  []
  (str "(()=>{let d=document,M=Math,ov=[],B='#seon-header,#seon-composer-unit,.canvas-timeline-bar,.entity-actions',"
       "A=[...d.querySelectorAll('a,button,input,textarea,select')].slice(0,80).map(e=>[e,e.getBoundingClientRect()]).filter(([e,r])=>r.width>3&&r.height>3&&!e.closest('#seon-hovercard,details:not([open])')&&getComputedStyle(e).visibility!='hidden');"
       "for(let i=0;i<A.length&&ov.length<6;i++)for(let j=i+1;j<A.length&&ov.length<6;j++){let[p,x]=A[i],[q,y]=A[j],b=p.closest(B),w=M.min(x.right,y.right)-M.max(x.left,y.left),h=M.min(x.bottom,y.bottom)-M.max(x.top,y.top);"
       "if((p.parentElement==q.parentElement||(b&&b==q.closest(B)))&&!p.contains(q)&&!q.contains(p)&&w>2&&h>2&&w*h>M.min(x.width*x.height,y.width*y.height)*.15)ov.push('Ov:'+(p.id||p.tagName)+'<>'+(q.id||q.tagName));}"
       "let n=performance.getEntriesByType('navigation')[0];return{errors:window.__sjE||[],warnings:window.__sjW||[],overlaps:ov,ttfb:n?M.round((n.responseStart-n.requestStart)*10)/10:null};})()"))

(defn evaluate-page-audit
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :map] :cost/ticks 2000000}
  [audit th]
  (let [{:keys [pixel-fit telemetry http-ttfb-ms checks]} (mget audit) t (mget th)
        pf (mget pixel-fit) tl (mget telemetry) vp (mget (:viewport pf))
        b (fnum (:ttfb-budget-ms t)) bgt (if (and b (pos? b)) b default-ttfb-budget-ms) ms (fnum http-ttfb-ms)
        vis (snb (:visual-defects pf)) ovs (snb (:overlaps tl)) ov? (or (:doc-overflow-x pf) (:overflow-x? vp))
        te (nb (:error tl)) pe (nb (:error pf))
        dj (cond-> (mapv #(str "JS:" %) (snb (:errors tl))) te (conj (str "Tel:" te)) pe (conj (str "PF:" pe)))
        do- (when (and ov? (not-any? #(re-find #"(?i)overflow" %) vis)) [(str "Overflow:" (:doc-scroll-w vp 0) ">" (:w vp 0))])
        dc (when-not (:allow-clipped? t) (mapv #(str "Clip:" (:selector % "el")) (take 8 (maps (:clipped-items pf)))))
        dt (when (and ms (> ms bgt)) [(str "TTFB:" (r1 ms) ">" bgt "ms")])
        dk (mapv #(str "Chk[" (kn (:name % :c)) "]:" (or (nb (:detail %)) "fail")) (remove #(true? (:ok? %)) (maps checks)))
        defs (vec (distinct (concat dj do- dc vis ovs dt dk)))]
    {:ok? (empty? defs) :defects defs :summary (if (empty? defs) "PASS" (str/join "; " (take 4 defs)))}))

(defn summarize-gate-results
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:maybe [:sequential :string]]] :map] :cost/ticks 2000000}
  [cells arts]
  (let [cs (vec (maps cells)) sk (count (filter :skipped? cs)) fs (filterv #(not (or (:skipped? %) (true? (:ok? %)))) cs) ts (keep #(fnum (:http-ttfb-ms %)) cs)]
    {:ok? (empty? fs) :cells cs :failures fs :artifacts (vec (distinct (snb arts)))
     :summary {:total (count cs) :passed (- (count cs) (count fs) sk) :failed (count fs) :skipped sk
               :defect-count (reduce + 0 (map #(count (when (sequential? (:defects %)) (:defects %))) fs)) :max-http-ttfb-ms (when (seq ts) (reduce max ts))}}))

(defn ^:seon.flow/edge measure-http-ttfb!
  {:malli/schema [:function [:=> [:cat :string] :map] [:=> [:cat :string [:maybe :map]] :map]]}
  ([url] (measure-http-ttfb! url nil))
  ([url opts]
   (let [p #(let [t0 (System/nanoTime) r (http/get url {:throw false :as :stream}) b (:body r)]
              (when (instance? InputStream b) (with-open [^InputStream c b] (.read c)))
              [(long (or (:status r) 0)) (/ (- (System/nanoTime) t0) 1e6)])]
     (try (when-not (false? (:warmup? opts)) (p)) (let [[st ms] (p)] {:ok? (= 200 st) :status st :ttfb-ms (r1 ms) :url url})
          (catch Throwable ex {:ok? false :status 0 :ttfb-ms nil :url url :error (.getMessage ex)})))))
(defn- ^:seon.flow/edge ipc! [ch p tmo]
  (let [dl (+ (now) (long tmo))]
    (loop [] (let [r (- dl (now)) [v c] (when (pos? r) (a/alts!! [ch (a/timeout r)]))]
               (cond (or (<= r 0) (not= c ch)) {:ok false :error :timeout} (nil? v) {:ok false :error :closed}
                     (= "exited" (:event v)) {:ok false :error :exited} (p v) v :else (recur))))))

(defn ^:seon.flow/edge open-session!
  {:malli/schema [:=> [:cat :string [:maybe :map]] :map]}
  [url opts]
  (let [{:keys [binary-path width height timeout-ms]} (mget opts) bin (or binary-path (browser/default-binary-path))]
    (if-not (and (string? bin) (fs/executable? bin)) {:ok? false :error :browser-unavailable}
      (let [ch (a/chan 64) h (browser/start-browser-process! bin url {:width (long (or width 1280)) :height (long (or height 800))} ch)
            stop #(do (try ((:stop! h)) (catch Throwable _)) (try (a/close! ch) (catch Throwable _)))]
        (if (:error (ipc! ch #(= "ready" (:event %)) (or timeout-ms 8000))) (do (stop) {:ok? false :error :ready-timeout})
          {:ok? true :handle h :ipc-ch ch :seq (volatile! 0) :stop! stop})))))
(defn- ^:seon.flow/edge cmd! [s m t]
  (let [id (vswap! (:seq s) inc) f (get-in s [:handle :send!])]
    (if (and f (f (assoc m :id id))) (ipc! (:ipc-ch s) #(= id (:id %)) t) {:ok false :error :send-failed})))
(defn- ^:seon.flow/edge ev! [sess code] (cmd! sess {:op "eval_js" :code code} 6000))

(defn- ^:seon.flow/edge js! [s b] (:value (ev! s (str "(()=>{" b "})()"))))

(defn- ^:seon.flow/edge clk! [s q] (js! s (str "return __sj.clk(" (json/write-str q) ");")))

(defn- ^:seon.flow/edge call! [s f] (js! s (str "fetch('/call?form='+encodeURIComponent(" (json/write-str f) "),{method:'POST'}).catch(()=>{});")))
(defn- ^:seon.flow/edge wt! [s expr t]
  (let [dl (+ (now) (long t)) c (str "(()=>{return !!(" expr ");})()")]
    (loop [] (let [r (cmd! s {:op "eval_js" :code c} (min 2500 (max 500 t)))]
               (cond (and (:ok r) (true? (:value r))) true (>= (now) dl) false :else (do (Thread/sleep 35) (recur)))))))
(defn- ^:seon.flow/edge nav! [s c p]
  (cmd! s {:op "navigate" :url "about:blank"} 2000)
  (when (:ok (cmd! s {:op "navigate" :url (let [u (str (:u c) p)] (if (:hh? c) (browser/headless-url u) u))} 8000))
    (let [ok (wt! s "['seon-app','seon-header','seon-view-unit'].every(i=>document.getElementById(i))" 5000)]
      (ev! s (install-error-capture-js))
      (when (:sv? c)
        (call! s (str "(seon.web/set-viewport! " (:width c) " " (:height c) ")"))
        (wt! s (str "__sj.h('viewportW')==='" (:width c) "'") 1500))
      (Thread/sleep (long (or (:settle-ms c) 70))) (ev! s (apply-theme-js (:theme c))) ok)))

(defn ^:seon.flow/edge audit-page!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [s {:keys [journey step width height theme http-url checks thresholds artifact-dir cap?] :as o}]
  (ev! s (apply-theme-js theme))
  (let [w (long width) h (long height) br (ev! s (measure/measure-boxes-js)) tr (ev! s (read-page-telemetry-js))
        pf (measure/analyze-pixel-fit (if (:ok br) (:value br) {:error (str (:error br))}))
        tl (if (and (:ok tr) (map? (:value tr))) (:value tr) {:error (str (:error tr))})
        hr (when (nb http-url) (measure-http-ttfb! http-url {:warmup? true}))
        cs (cond-> (vec checks) (and hr (not (:ok? hr))) (conj [:http false (str "HTTP " (:status hr))]))
        ks (mapv #(if (vector? %) {:name (% 0) :ok? (boolean (% 1)) :detail (str (get % 2 (% 1)))} %) cs)
        v (evaluate-page-audit {:pixel-fit pf :telemetry tl :http-ttfb-ms (:ttfb-ms hr) :checks ks} (or thresholds o))
        st (or step "main") sp (when (and (nb artifact-dir) (or cap? (not (:ok? v)))) (str artifact-dir "/" (name journey) "-" st "-" w "x" h "-" (name theme) ".jpg"))]
    (when sp (some-> (fs/parent sp) fs/create-dirs) (cmd! s {:op "snapshot" :width w :height h :max_width (min 1440 w) :quality 0.78 :save_path sp} 6000))
    (assoc v :journey journey :step st :width w :height h :theme theme :http-ttfb-ms (:ttfb-ms hr) :nav-ttfb-ms (:ttfb tl) :warnings (vec (:warnings tl)) :artifact (when (and sp (fs/regular-file? sp)) sp))))
(defn- ^:seon.flow/edge j-core! [s c j]
  (let [aud #(audit-page! s (assoc c :journey j :step %1 :checks %2 :http-url %3))]
    (case j
      :landing
      (let [n (nav! s c "/") m (js! s "return 'seon-app seon-header seon-main seon-view-unit seon-composer-unit'.split(' ').filter(i=>!__sj.g(i));")]
        [(aud "home" [[:nav n] [:landmarks (= [] m) m]] (str (:u c) "/"))])
      :chat
      (let [n (nav! s c "/chat") ro? (:read-only? c)
            g (js! s (str "if(document.hidden&&!__sj.R){__sj.R=1;requestAnimationFrame=f=>setTimeout(f,16);let x=[...document.scripts].find(s=>s.textContent.includes('ws-timeline'));x&&(0,eval)(x.textContent);}"
                          "__sj.sc=()=>{let a=__sj.q('.ws-timeline'),m=__sj.g('seon-main'),e=a&&a.scrollHeight>a.clientHeight+24?a:m,mx=e?Math.max(0,e.scrollHeight-e.clientHeight):0;return[e,mx,!!e&&(mx<=20||mx-e.scrollTop<=48)];};"
                          "let t=__sj.q('.composer-inp'),f=v=>(t.value=v,__sj.ev(t,'input'),__sj.r(t).height);if(!t)return{};let ok=f('')+10<f('MD:\\n2\\n3\\n4\\n5');"
                          (if ro? "return{ok:ok&&f('')<=48};" "return{ok,sub:t.form?(t.form.requestSubmit(),1):0};")))
            md (when-not ro? (wt! s "!__sj.q('.composer-inp')?.value&&!__sj.g('cv-pending')&&['code','pre','ul'].every(k=>__sj.q('#seon-view-unit '+k))" 5000))
            _ (when-not ro? (wt! s "__sj.sc()[2]" 900))
            p (js! s "let[e,mx,ok]=__sj.sc(),t=__sj.q('.composer-inp'),rs=!!(t&&!t.value&&__sj.r(t).height<=48);if(e){e.dispatchEvent(new WheelEvent('wheel',{deltaY:-180,bubbles:1}));e.scrollTop=0;__sj.ev(e,'scroll');__sj.ev(window,'resize');document.dispatchEvent(new CustomEvent('datastar-fetch',{detail:{type:'datastar-patch-elements',argsRaw:{elements:'seon-view-unit'}}}));}return{rs,ok,mx};")
            _ (Thread/sleep 280) up (js! s "let[e,mx]=__sj.sc();return{ok:!!e&&(mx<=20||e.scrollTop<=24),mx};")]
        [(aud (if ro? "ro" "live") (cond-> [[:nav n] [:grow (:ok g) g] [:unpin (:ok up) up]] (not ro?) (into [[:reset (and (= 1 (:sub g)) (:rs p))] [:md md] [:pin (:ok p) p]])) nil)])
      :navigation
      (let [n0 (nav! s c "/") _ (Thread/sleep 70) un "/ns/seon.web" uv "/var/seon.web/web-proc"
            go #(call! s (str "(seon.web/navigate-url! \"" % "\")"))
            at? #(wt! s (str "__sj.h('canonicalUrl')==='" %1 "'&&location.pathname==='" %1 "'" (when %2 (str "&&__sj.g('seon-view-unit')?.textContent.includes('" %2 "')"))) 4000)
            _ (go un) h1 (at? un nil) _ (go uv) h2 (at? uv nil)
            _ (clk! s "#hdr-nav-back") bk (at? un "seon.web") _ (clk! s "#hdr-nav-fwd") fw (at? uv "web-proc")]
        [(aud "back-fwd" [[:nav0 n0] [:ns h1] [:var h2] [:back bk] [:fwd fw]] nil)]))))
(defn- ^:seon.flow/edge j-ui! [s c j]
  (let [aud #(audit-page! s (assoc c :journey j :step %1 :checks %2))]
    (case j
      :explorer
      (let [iq "'seon-inspector-unit insp-focal-unit insp-neighborhood-unit insp-variations-unit'.split(' ').filter(i=>!__sj.g(i))"
            nv (nav! s c "/var/seon.web/web-proc")
            vr (js! s (str "let e=__sj.qa('[data-preview-ref]').find(x=>__sj.r(x).width>3);if(e){e.scrollIntoView({block:'center'});e.dispatchEvent(new FocusEvent('focusin',{bubbles:1}));}return{m:" iq ",ref:e?.dataset.previewRef||''};"))
            ref (:ref vr) ph (when (nb ref) (:ok? (measure-http-ttfb! (str (:u c) "/preview?ref=" (URLEncoder/encode ^String ref "UTF-8")) {:warmup? false})))
            hc (when (nb ref) (wt! s "(()=>{let p=__sj.g('seon-hovercard'),r=p&&__sj.r(p);return p?.innerHTML.trim()&&r.width>20&&r.height>10&&r.left>=-2&&r.top>=-2&&r.right<=innerWidth+2&&r.bottom<=innerHeight+2;})()" 2500))
            _ (js! s "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:1}));")
            av (aud "var" [[:nav nv] [:units (= [] (:m vr)) (:m vr)] [:preview ph ref] [:hover hc]])
            nn (nav! s c "/ns/seon.web") mn (js! s (str "return " iq ";"))]
        [av (aud "ns" [[:nav nn] [:units (= [] mn) mn]])])
      :dev-mode
      (let [n0 (nav! s c "/") tg "#expert-mode-toggle" exp #(wt! s (str "__sj.h('expertMode')==='" % "'") 3500)
            _ (when (= "true" (js! s "return __sj.h('expertMode');")) (clk! s tg) (exp "false"))
            _ (clk! s tg) on (exp "true")
            pl (js! s "let ps=__sj.qa('.mode-group .nav-pill'),ts=ps.map(p=>p.textContent.trim()),ss=__sj.q('.sig-strip');return ts.includes('Explain')&&ts.includes('System')&&ps.every(p=>__sj.r(p).width>10&&__sj.r(p).right<=innerWidth+1)&&(!ss||__sj.r(ss).right<=innerWidth+1);")
            ad (aud "on" [[:nav0 n0] [:on on] [:pills pl]]) ns (nav! s c "/system") as (aud "system" [[:sys ns]])
            _ (clk! s tg) off (exp "false")]
        [(if off ad (-> ad (assoc :ok? false) (update :defects conj "Dev off"))) as])
      :time-travel
      (let [n0 (nav! s c "/") tb ".canvas-timeline-bar" pb ".tl-btn[aria-label^=Prev]"
            tl (js! s (str "let p=__sj.q('" tb " " pb "');return!!(p&&!p.disabled);"))]
        [(if (and (not tl) (not (:require-timeline? c)))
           (assoc (select-keys c [:width :height :theme]) :journey j :step "skipped" :ok? true :skipped? true :defects [])
           (let [_ (clk! s pb)
                 hr (wt! s (str "__sj.q('" tb ".historical')&&__sj.q('button.step-live')&&(location.search.includes('tx=')||__sj.h('canonicalUrl').includes('tx='))") 4000)
                 ah (aud "rewind" [[:nav0 n0] [:tl tl] [:rewind hr]]) _ (clk! s "button.step-live")
                 lr (wt! s (str "!__sj.q('" tb ".historical')&&!__sj.h('canonicalUrl').includes('tx=')") 4000)]
             (if lr ah (-> ah (assoc :ok? false) (update :defects conj "Live fail")))))])
      [{:journey j :ok? false :defects [(str "Unknown:" j)]}])))

(defn ^:seon.flow/edge run-journey-gate!
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe :map]] :map]]}
  ([] (run-journey-gate! nil))
  ([opts]
   (let [o (merge {:viewports default-viewports :themes default-themes :journeys default-journeys} (mget opts))
         ro? (boolean (:read-only? o)) rd (nb (:artifact-dir o)) keep? (boolean (get o :keep-artifacts? rd))
         adir (if (and rd (not (str/includes? rd "seon-next"))) rd (str "/tmp/seon-journey-" (now)))
         [w0 h0] (first (:viewports o)) s (open-session! "about:blank" (assoc o :width (or w0 1280) :height (or h0 800)))]
     (if-not (:ok? s)
       (assoc (summarize-gate-results [{:journey :boot :ok? false :defects [(str "Browser:" (:error s))]}] nil) :error (:error s))
       (let [b (assoc o :u (normalize-url-base (:url o)) :hh? (get o :headless-hash? ro?)
                      :sv? (get o :sync-viewport? (not ro?)) :read-only? ro? :artifact-dir adir :cap? (or rd keep?))]
         (try
           (let [rs (vec (for [[w h] (:viewports o) :let [w (long w) h (long h) _ (cmd! s {:op "resize" :width w :height h} 4000)]
                               t (:themes o) j (:journeys o)
                               r ((if (#{:landing :chat :navigation} j) j-core! j-ui!) s (assoc b :width w :height h :theme t) j)] r))
                 _ (when ro? (try (nav! s (assoc b :theme :dark :settle-ms 20) "/") (catch Throwable _)))
                 res (summarize-gate-results rs (keep :artifact rs))]
             (if (and (:ok? res) (not keep?)) (do (when (fs/exists? adir) (store/rm-rf! adir)) (assoc res :artifacts [])) res))
           (finally ((:stop! s)))))))))
