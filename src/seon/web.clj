(ns seon.web
  "The `:web` Flow proc (`:io` workload):
   - Zero atoms! Owns SSE browser tabs, `::flow/report` & `::flow/error` history, and `ring-chez` HTTP listener
     purely inside its Flow proc state map.
   - Serves:
     1. `GET /` & `GET /events` — Live Native Jolt Flow Monitor + Agent/Task Tree + Reach Test Cache + Branch REPL
     2. `POST /op` (`application/edn`) — Fast ~4 ms EDN RPC endpoint for `bin/seon` & MCP sessions!"
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.datafy :as datafy]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [ring-chez.adapter :as adapter]
            [seon.reconciler :as reconciler]))

;; --- Pure Topological Layout (from clojure.core.async.flow-static) ---

(defn- flow-relationships [procs conns]
  (let [base (into {}
                   (map (fn [[pid pdef]]
                          (let [desc (:desc pdef)]
                            [pid {:from []
                                  :to []
                                  :ins (vec (sort (keys (:ins desc))))
                                  :outs (vec (sort (keys (:outs desc))))
                                  :workload (or (:workload desc) :io)
                                  :doc (:doc desc)}])))
                   procs)]
    (reduce (fn [res [[fp fport] [tp tport]]]
              (-> res
                  (update-in [tp :from] (fnil conj []) fp)
                  (update-in [fp :to] (fnil conj []) tp)
                  (update-in [tp :ins] #(if (some #{tport} %) % (conj (vec %) tport)))
                  (update-in [fp :outs] #(if (some #{fport} %) % (conj (vec %) fport)))))
            base conns)))

(defn- flow-levels [rels roots]
  (let [rset (set roots)
        seed (filter (fn [[k _]] (contains? rset k)) rels)]
    (loop [levels []
           cur (into (array-map) (if (seq seed) seed (take 1 rels)))
           rem (apply dissoc rels (keys cur))]
      (if (empty? cur)
        (if (empty? rem)
          levels
          (let [[k v] (first rem)]
            (recur levels {k v} (dissoc rem k))))
        (let [nkeys (distinct (mapcat (fn [[_ v]] (:to v)) cur))
              nmap (into (array-map) (keep (fn [k] (when-let [v (get rem k)] [k v])) nkeys))]
          (recur (conj levels (vec (keys cur)))
                 nmap
                 (apply dissoc rem (keys nmap))))))))

(defn- json-clean [x]
  (cond
    (nil? x) nil
    (or (boolean? x) (number? x) (string? x)) x
    (keyword? x) (if-let [ns (namespace x)] (str ":" ns "/" (name x)) (str ":" (name x)))
    (or (symbol? x) (uuid? x)) (str x)
    (map? x) (into {} (map (fn [[k v]] [(if (string? k) k (str (json-clean k))) (json-clean v)])) x)
    (or (sequential? x) (set? x)) (mapv json-clean x)
    :else (pr-str x)))

(defn- build-ui-snapshot [st]
  (let [g (when-let [gf (:flow-fn st)] (gf))
        conn (:conn st)]
    (if-not g
      {:bootSeq (:boot-seq st 1) :rebooting true}
      (let [df (datafy/datafy g)
            rels (flow-relationships (:procs df) (:conns df))
            levels (flow-levels rels [:server])
            pings (try (flow/ping g :timeout-ms 15) (catch Throwable _ {}))
            live-agents (get-in pings [:agents ::flow/state :live] {})
            procs-json
            (into {}
                  (map (fn [[pid r]]
                         (let [p (get pings pid)
                               self? (= pid :web)
                               status (cond self? "running"
                                            p (name (or (::flow/status p) :running))
                                            :else "stepping")
                               pstate (if self?
                                        {:tabs (count (:tabs st)) :port (:bound-port st) :frames (:frames st)}
                                        (::flow/state p))]
                           [(name pid)
                            {:pid (name pid)
                             :workload (name (:workload r))
                             :doc (or (:doc r) "")
                             :ins (mapv name (:ins r))
                             :outs (mapv name (:outs r))
                             :status status
                             :count (if self? (:frames st 0) (or (::flow/count p) 0))
                             :site (json-clean (if self? {:stepping :sse} (::flow/site p)))
                             :inFill (into {}
                                           (map (fn [[port fill]]
                                                  [(name port)
                                                   {:count (:count fill 0)
                                                    :capacity (:capacity fill 0)
                                                    :parkedPuts (:parked-puts fill 0)}]))
                                           (::flow/in-fill p))
                             :stateStr (pr-str pstate)}])))
                  rels)
            conns-json (mapv (fn [[[fp fport] [tp tport]]]
                               {:fromProc (name fp) :fromPort (name fport)
                                :toProc (name tp) :toPort (name tport)})
                             (:conns df))
            db @conn
            store (:store db)
            live-pids (into {} (map (fn [[k v]] [k (:pid v)])) live-agents)
            agents-vec (->> (d/datoms db :aevt :agent/id)
                            (keep (fn [dt]
                                    (when-let [v (reconciler/derive-agent-status db store live-pids #{} #{} (:v dt))]
                                      (let [li (get live-agents (:id v))]
                                        {:id (str (:id v))
                                         :parent (some-> (:parent v) str)
                                         :role (name (or (:role v) :worker))
                                         :branch (str (:branch v))
                                         :branchTx (:branch-tx v)
                                         :goal (:goal v)
                                         :paused (:paused? v)
                                         :turns (:evals v)
                                         :unreadCount (:unread-count v)
                                         :status (name (:status v))
                                         :livePid (when (number? (:pid li)) (:pid li))
                                         :forkMs (or (:fork-ms li) 0)
                                         :memMb (or (:mem-mb li) 0)}))))
                            (sort-by :id)
                            vec)
            mail-vec (->> (d/datoms db :aevt :message/id)
                          (map #(d/pull db '[:message/seq :message/from :message/to :message/type :message/body] (:e %)))
                          (sort-by #(unchecked-negate (or (:message/seq %) 0)))
                          (take 20)
                          (mapv (fn [m]
                                  {:from (str (:message/from m))
                                   :to (str (:message/to m))
                                   :type (name (:message/type m :chat))
                                   :body (:message/body m "")})))
            tests-vec (->> (d/datoms db :aevt :test/digest)
                           (map #(d/pull db '[:test/digest :test/var :test/pass? :test/ms] (:e %)))
                           (sort-by #(str (:test/var %)))
                           (mapv (fn [t]
                                   {:var (str (:test/var t))
                                    :digest (:test/digest t)
                                    :pass (boolean (:test/pass? t))
                                    :ms (:test/ms t 0.0)})))]
        {:bootSeq (:boot-seq st 1)
         :throttled (:throttled? st false)
         :levels (mapv #(mapv name %) levels)
         :procs procs-json
         :conns conns-json
         :reports (vec (:reports st))
         :errors (vec (:errors st))
         :domain {:mainTx (:max-tx db)
                  :formsCount (count (d/datoms db :aevt :form/key))
                  :branchCount (count (d/branches conn))
                  :forkParentPid (:fork-parent-pid st)
                  :livePidCount (count (filter #(number? (:pid (val %))) live-agents))
                  :storePath (:store-path st)
                  :gitCommit (:git/commit (d/pull db '[:git/commit] [:git/repo :main]))
                  :agents agents-vec
                  :mail mail-vec
                  :tests tests-vec}}))))

;; --- Clean Single-Page HTML (Flow Monitor + Agent/Branch Inspector + Interactive REPL) ---

(def index-html
  "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\"/>
<title>Seon — Flow Monitor &amp; Live Datahike System</title>
<style>
  :root { --bg:#0b101b; --panel:#131b2e; --card:#19233c; --border:#2c3c63; --text:#e2e8f0; --muted:#94a3b8; --accent:#38bdf8; --green:#22c55e; --amber:#f59e0b; --red:#ef4444; }
  * { box-sizing:border-box; }
  body { margin:0; font-family:ui-sans-serif,-apple-system,sans-serif; background:var(--bg); color:var(--text); height:100vh; display:flex; flex-direction:column; overflow:hidden; }
  header { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:10px 16px; background:#0f172a; border-bottom:1px solid #1e293b; }
  .brand { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  .brand h1 { font-size:15px; margin:0; font-weight:700; }
  .pill { font-size:11px; padding:2px 8px; border-radius:999px; font-weight:600; background:#1e293b; color:var(--accent); border:1px solid #334155; font-family:ui-monospace,monospace; }
  .controls { display:flex; flex-wrap:wrap; gap:7px; }
  button { background:#1e293b; color:var(--text); border:1px solid #334155; border-radius:6px; padding:6px 10px; font-size:11.5px; font-weight:600; cursor:pointer; }
  button:hover { background:#28354f; }
  button.primary { background:#0284c7; border-color:#0ea5e9; color:#fff; }
  button.warn { background:#78350f; border-color:#d97706; color:#fef3c7; }
  button.warn.active { background:#d97706; color:#000; }
  button.danger { background:#7f1d1d; border-color:#ef4444; color:#fee2e2; }
  main { flex:1; display:grid; grid-template-columns:1.3fr 1fr; overflow:hidden; }
  #graph-pane { position:relative; overflow:auto; padding:24px 32px 60px; border-right:1px solid #1e293b; background:radial-gradient(circle at 20% 20%,#111a2e 0%,#0b101b 70%); }
  #wires-svg { position:absolute; top:0; left:0; pointer-events:none; z-index:1; overflow:visible; }
  #levels-root { position:relative; z-index:2; display:flex; flex-direction:column; gap:48px; align-items:center; min-width:640px; }
  .flow-row { display:flex; gap:30px; justify-content:center; align-items:flex-start; width:100%; }
  .proc-card { background:var(--card); border:1.5px solid var(--border); border-radius:10px; min-width:210px; max-width:265px; box-shadow:0 8px 24px rgba(0,0,0,0.35); cursor:pointer; }
  .proc-card.selected { border-color:var(--accent); box-shadow:0 0 0 2px rgba(56,189,248,0.35); }
  .proc-card.paused { border-color:var(--amber); opacity:0.86; }
  .ports-row { display:flex; flex-wrap:wrap; gap:5px; justify-content:center; padding:5px 7px; background:rgba(15,23,42,0.55); }
  .ports-row.ins { border-bottom:1px solid #253354; border-radius:9px 9px 0 0; }
  .ports-row.outs { border-top:1px solid #253354; border-radius:0 0 9px 9px; }
  .port-chip { font-size:10px; font-family:ui-monospace,monospace; padding:2px 6px; border-radius:4px; background:#0f172a; border:1px solid #334155; display:flex; align-items:center; gap:4px; color:#cbd5e1; }
  .port-chip.queued { border-color:var(--amber); color:#fde68a; background:rgba(120,53,15,0.45); }
  .port-chip.parked { border-color:var(--red); color:#fecaca; background:rgba(127,29,29,0.65); }
  .fill-bar { width:22px; height:4px; background:#1e293b; border-radius:2px; overflow:hidden; display:inline-block; }
  .fill-bar > i { display:block; height:100%; background:var(--accent); }
  .proc-body { padding:9px 11px; }
  .proc-head { display:flex; justify-content:space-between; align-items:center; margin-bottom:5px; }
  .proc-title { font-weight:700; font-size:13.5px; font-family:ui-monospace,monospace; color:#fff; }
  .workload-tag { font-size:9.5px; text-transform:uppercase; padding:1px 5px; border-radius:4px; background:#263454; color:#93c5fd; font-weight:700; }
  .workload-tag.compute { background:#3b1d5e; color:#d8b4fe; }
  .workload-tag.mixed { background:#1e3a3a; color:#5eead4; }
  .proc-metrics { display:flex; justify-content:space-between; font-size:11px; color:var(--muted); margin-bottom:5px; }
  .site-line { font-size:10px; font-family:ui-monospace,monospace; color:#94a3b8; background:#0f172a; padding:3px 6px; border-radius:4px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .proc-actions { display:flex; justify-content:flex-end; margin-top:6px; }
  .proc-actions button { padding:2px 7px; font-size:10px; }
  #side-pane { display:flex; flex-direction:column; overflow:hidden; background:var(--panel); }
  .tabs { display:flex; border-bottom:1px solid #1e293b; background:#0f172a; }
  .tab { padding:9px 13px; font-size:12px; font-weight:600; color:var(--muted); cursor:pointer; border-bottom:2px solid transparent; }
  .tab.active { color:var(--accent); border-bottom-color:var(--accent); }
  .tab-body { flex:1; overflow:auto; padding:13px; display:none; }
  .tab-body.active { display:block; }
  .section-box { background:#0f172a; border:1px solid #1e293b; border-radius:8px; padding:10px 12px; margin-bottom:11px; }
  .section-title { font-size:11px; font-weight:700; text-transform:uppercase; color:var(--muted); margin-bottom:8px; display:flex; justify-content:space-between; }
  .agent-node { border-left:2px solid #334155; margin-left:10px; padding-left:10px; margin-top:7px; }
  .agent-node.root { border-left:none; margin-left:0; padding-left:0; }
  .agent-card { background:#162036; border:1px solid #293859; border-radius:7px; padding:8px 10px; font-size:12px; }
  .badge { font-size:10px; font-weight:700; padding:1px 6px; border-radius:99px; font-family:ui-monospace,monospace; background:#1e293b; color:#cbd5e1; }
  .badge.running { background:rgba(34,197,94,0.2); color:#86efac; border:1px solid #22c55e; }
  .badge.merged { background:rgba(56,189,248,0.18); color:#7dd3fc; border:1px solid #0284c7; }
  .badge.testing { background:rgba(168,85,247,0.2); color:#e9d5ff; border:1px solid #a855f7; }
  pre { margin:0; font-family:ui-monospace,monospace; font-size:11.5px; white-space:pre-wrap; word-break:break-word; color:#cbd5e1; }
  .row-item { font-family:ui-monospace,monospace; font-size:11px; padding:4px 0; border-bottom:1px solid #162036; }
  textarea, input, select { background:#162036; border:1px solid #334155; color:#fff; border-radius:5px; padding:6px 8px; font-family:ui-monospace,monospace; font-size:11.5px; }
</style>
</head>
<body>
<header>
  <div class=\"brand\">
    <h1>Seon <span style=\"color:#64748b;font-weight:400\">| Static Flow + Datahike LMDB + COW Fork</span></h1>
    <span class=\"pill\" id=\"boot-pill\">Boot #1</span>
    <span class=\"pill\" id=\"db-pill\">:main tx 0</span>
    <span class=\"pill\" id=\"forms-pill\">0 forms</span>
    <span class=\"pill\" id=\"pids-pill\">0 Live Agent PIDs</span>
  </div>
  <div class=\"controls\">
    <button class=\"primary\" onclick=\"spawnGoal()\">+ Spawn Coordinator Tree</button>
    <button onclick=\"runSuite()\">🧪 Run Reach Tests (:main)</button>
    <button class=\"warn\" id=\"throttle-btn\" onclick=\"uiAct('/ui/toggle-throttle')\">🐢 Throttle :model</button>
    <button class=\"danger\" onclick=\"uiAct('/ui/power-loss')\">⚡ Power Loss (SIGKILL &amp; Cold Reboot)</button>
  </div>
</header>
<main>
  <div id=\"graph-pane\">
    <svg id=\"wires-svg\"></svg>
    <div id=\"levels-root\"></div>
  </div>
  <div id=\"side-pane\">
    <div class=\"tabs\">
      <div class=\"tab active\" data-tab=\"agents\" onclick=\"switchTab('agents')\">Agents &amp; clojure.test</div>
      <div class=\"tab\" data-tab=\"repl\" onclick=\"switchTab('repl')\">Branch REPL</div>
      <div class=\"tab\" data-tab=\"inspector\" onclick=\"switchTab('inspector')\">Proc State &amp; ::flow/report</div>
    </div>
    <div class=\"tab-body active\" id=\"tab-agents\">
      <div class=\"section-box\">
        <div class=\"section-title\"><span>Derived Agent Tree (Zero Stored Status)</span><span id=\"git-sha\"></span></div>
        <div id=\"agent-tree\"></div>
      </div>
      <div class=\"section-box\">
        <div class=\"section-title\"><span>clojure.test Reach-Digest Cache in Datahike</span></div>
        <div id=\"test-cache\"></div>
      </div>
      <div class=\"section-box\">
        <div class=\"section-title\"><span>Messages on :main</span></div>
        <div id=\"mail-log\"></div>
      </div>
    </div>
    <div class=\"tab-body\" id=\"tab-repl\">
      <div class=\"section-box\">
        <div class=\"section-title\"><span>Evaluate Inside Forked COW Branch REPL</span></div>
        <div style=\"display:flex;gap:6px;margin-bottom:7px\">
          <input id=\"repl-branch\" value=\":branch/scratch\" style=\"width:180px\" placeholder=\":branch/name\"/>
          <button class=\"primary\" onclick=\"evalBranch()\">Eval on Branch</button>
          <button onclick=\"testBranch()\">Test Branch</button>
          <button onclick=\"mergeBranch()\">3-Way Merge → :main</button>
        </div>
        <textarea id=\"repl-code\" rows=\"6\" style=\"width:100%\">(ns demo.quick)\n(defn square [x] (* x x))</textarea>
      </div>
      <div class=\"section-box\">
        <div class=\"section-title\"><span>Result</span></div>
        <pre id=\"repl-out\">Ready.</pre>
      </div>
    </div>
    <div class=\"tab-body\" id=\"tab-inspector\">
      <div class=\"section-box\">
        <div class=\"section-title\"><span>Selected Proc: <b id=\"sel-proc\" style=\"color:#38bdf8\">:reconciler</b></span><span id=\"sel-wl\"></span></div>
        <div id=\"sel-doc\" style=\"font-size:12px;color:#94a3b8;margin-bottom:8px\"></div>
        <pre id=\"sel-state\">{}</pre>
      </div>
      <div class=\"section-box\">
        <div class=\"section-title\"><span>::flow/report Stream (Built-in Flow Channel, 0 Extra Wires!)</span></div>
        <div id=\"reports-log\"></div>
      </div>
      <div class=\"section-box\">
        <div class=\"section-title\"><span>::flow/error Stream</span></div>
        <pre id=\"errors-log\">0 errors</pre>
      </div>
    </div>
  </div>
</main>
<script>
let snap = null;
let selectedProc = 'reconciler';
function switchTab(n) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === n));
  document.querySelectorAll('.tab-body').forEach(b => b.classList.toggle('active', b.id === 'tab-' + n));
}
async function uiAct(path, obj) {
  const r = await fetch(path, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(obj || {})});
  return r.status === 204 ? null : r.text();
}
async function callOp(ednStr) {
  const r = await fetch('/op', {method:'POST', headers:{'Content-Type':'application/edn'}, body:ednStr});
  return r.text();
}
async function spawnGoal() {
  const n = Math.floor(Math.random() * 900 + 100);
  await callOp(`{:op :create-task :id :goal/g${n} :title \"Autonomous tree #${n}\" :spawn-agent? true :role :coordinator}`);
}
async function runSuite() {
  const res = await callOp('{:op :test :branch :main}');
  document.getElementById('repl-out').textContent = res;
}
async function evalBranch() {
  const b = document.getElementById('repl-branch').value.trim();
  const c = document.getElementById('repl-code').value;
  document.getElementById('repl-out').textContent = await callOp(`{:op :eval :branch ${b} :code ${JSON.stringify(c)}}`);
}
async function testBranch() {
  const b = document.getElementById('repl-branch').value.trim();
  document.getElementById('repl-out').textContent = await callOp(`{:op :test :branch ${b}}`);
}
async function mergeBranch() {
  const b = document.getElementById('repl-branch').value.trim();
  document.getElementById('repl-out').textContent = await callOp(`{:op :merge :branch ${b}}`);
}
function formatSite(s) {
  if (!s) return 'parked on channel';
  if (s[':stepping']) return '⚡ stepping ' + s[':stepping'];
  if (s[':writing']) return '⏳ backpressure ' + JSON.stringify(s[':writing']);
  if (s[':reading']) return 'reading ' + JSON.stringify(s[':reading']);
  return JSON.stringify(s);
}
function renderGraph(s) {
  const root = document.getElementById('levels-root');
  if (!s.levels) return;
  root.innerHTML = '';
  s.levels.forEach(row => {
    const rEl = document.createElement('div');
    rEl.className = 'flow-row';
    row.forEach(pid => {
      const p = s.procs[pid];
      if (!p) return;
      const card = document.createElement('div');
      card.className = 'proc-card' + (pid === selectedProc ? ' selected' : '') + (p.status === 'paused' ? ' paused' : '');
      card.onclick = (e) => { if (e.target.tagName !== 'BUTTON') { selectedProc = pid; renderInspector(); renderGraph(snap); } };
      const ins = p.ins.map(port => {
        const f = (p.inFill && p.inFill[port]) || {count:0, capacity:0, parkedPuts:0};
        const pct = f.capacity > 0 ? Math.min(100, Math.round(f.count * 100 / f.capacity)) : 0;
        const cls = f.parkedPuts > 0 ? 'parked' : (f.count > 0 ? 'queued' : '');
        const b = f.capacity > 0 ? `<span class=\"fill-bar\"><i style=\"width:${pct}%\"></i></span><span>${f.count}/${f.capacity}</span>` : '';
        return `<div class=\"port-chip ${cls}\" id=\"in-${pid}-${port}\">:${port} ${b}</div>`;
      }).join('');
      const outs = p.outs.map(port => `<div class=\"port-chip\" id=\"out-${pid}-${port}\">:${port}</div>`).join('');
      const btn = p.status === 'paused'
        ? `<button onclick=\"uiAct('/ui/proc',{action:'resume',proc:'${pid}'})\">▶ Resume</button>`
        : `<button onclick=\"uiAct('/ui/proc',{action:'pause',proc:'${pid}'})\">⏸ Pause</button>`;
      card.innerHTML = `${ins ? `<div class=\"ports-row ins\">${ins}</div>` : ''}
        <div class=\"proc-body\">
          <div class=\"proc-head\"><span class=\"proc-title\">:${pid}</span><span class=\"workload-tag ${p.workload}\">${p.workload}</span></div>
          <div class=\"proc-metrics\"><span>${p.status}</span><span>steps: <b style=\"color:#fff\">${p.count}</b></span></div>
          <div class=\"site-line\">${formatSite(p.site)}</div>
          <div class=\"proc-actions\">${btn}</div>
        </div>
        ${outs ? `<div class=\"ports-row outs\">${outs}</div>` : ''}`;
      rEl.appendChild(card);
    });
    root.appendChild(rEl);
  });
  requestAnimationFrame(() => renderWires(s));
}
function renderWires(s) {
  const pane = document.getElementById('graph-pane');
  const svg = document.getElementById('wires-svg');
  if (!s || !s.conns) return;
  const pr = pane.getBoundingClientRect();
  svg.setAttribute('width', pane.scrollWidth);
  svg.setAttribute('height', pane.scrollHeight);
  let html = `<defs>
    <marker id=\"arr\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto-start-reverse\"><path d=\"M 0 1 L 10 5 L 0 9 z\" fill=\"#475569\"/></marker>
    <marker id=\"arr-q\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto-start-reverse\"><path d=\"M 0 1 L 10 5 L 0 9 z\" fill=\"#f59e0b\"/></marker>
  </defs>`;
  s.conns.forEach((c, idx) => {
    const o = document.getElementById(`out-${c.fromProc}-${c.fromPort}`);
    const i = document.getElementById(`in-${c.toProc}-${c.toPort}`);
    if (!o || !i) return;
    const r1 = o.getBoundingClientRect(), r2 = i.getBoundingClientRect();
    const x1 = r1.left - pr.left + pane.scrollLeft + r1.width/2, y1 = r1.bottom - pr.top + pane.scrollTop;
    const x2 = r2.left - pr.left + pane.scrollLeft + r2.width/2, y2 = r2.top - pr.top + pane.scrollTop;
    const fill = (s.procs[c.toProc] && s.procs[c.toProc].inFill && s.procs[c.toProc].inFill[c.toPort]) || {count:0};
    const hot = fill.count > 0;
    let d;
    if (y2 >= y1 - 6) {
      const dy = Math.max(26, (y2 - y1) * 0.5);
      d = `M ${x1} ${y1} C ${x1} ${y1+dy}, ${x2} ${y2-dy}, ${x2} ${y2}`;
    } else {
      const side = (x1 <= pane.scrollWidth/2) ? -105 - (idx%3)*18 : 105 + (idx%3)*18;
      const mx = Math.max(20, Math.min(pane.scrollWidth-20, (x1+x2)/2 + side));
      d = `M ${x1} ${y1} C ${x1} ${y1+34}, ${mx} ${y1+18}, ${mx} ${(y1+y2)/2} S ${x2} ${y2-34}, ${x2} ${y2}`;
    }
    html += `<path d=\"${d}\" fill=\"none\" stroke=\"${hot ? '#f59e0b' : '#475569'}\" stroke-width=\"${hot ? 2.3 : 1.7}\" marker-end=\"${hot ? 'url(#arr-q)' : 'url(#arr)'}\"/>`;
  });
  svg.innerHTML = html;
}
function renderInspector() {
  if (!snap || !snap.procs) return;
  const p = snap.procs[selectedProc] || Object.values(snap.procs)[0];
  if (!p) return;
  document.getElementById('sel-proc').textContent = ':' + p.pid;
  document.getElementById('sel-wl').textContent = ':' + p.workload;
  document.getElementById('sel-doc').textContent = p.doc;
  document.getElementById('sel-state').textContent = p.stateStr;
  document.getElementById('reports-log').innerHTML = (snap.reports || []).map(r => `<div class=\"row-item\">${r}</div>`).join('');
  document.getElementById('errors-log').textContent = (snap.errors && snap.errors.length) ? snap.errors.join('\\n') : '0 errors';
}
function renderDomain(d) {
  if (!d) return;
  document.getElementById('db-pill').textContent = `:main tx ${d.mainTx} (${d.branchCount} branches)`;
  document.getElementById('forms-pill').textContent = `${d.formsCount} forms in DB`;
  document.getElementById('pids-pill').textContent = `${d.livePidCount} Live Agent PIDs (ForkParent ${d.forkParentPid || '?'})`;
  document.getElementById('git-sha').textContent = d.gitCommit ? 'git ' + d.gitCommit.slice(0,8) : '';
  const byP = {};
  (d.agents || []).forEach(a => { const k = a.parent || '__root__'; (byP[k] = byP[k] || []).push(a); });
  function renderAgent(a, root) {
    const kids = (byP[a.id] || []).map(c => renderAgent(c, false)).join('');
    const pidTxt = a.livePid ? `<span style=\"color:#86efac\">PID ${a.livePid} (${a.forkMs}ms)</span>` : '0 PIDs';
    return `<div class=\"agent-node ${root ? 'root' : ''}\">
      <div class=\"agent-card\">
        <div style=\"display:flex;justify-content:space-between\"><b>${a.id}</b><span class=\"badge ${a.status}\">${a.status}</span></div>
        <div style=\"font-size:11px;color:#94a3b8;margin:3px 0\">${a.goal}</div>
        <div style=\"font-size:10.5px;font-family:monospace;color:#cbd5e1\">${a.branch}@${a.branchTx} | turns:${a.turns} | unread:${a.unreadCount} | ${pidTxt}</div>
      </div>${kids}</div>`;
  }
  const roots = byP['__root__'] || [];
  document.getElementById('agent-tree').innerHTML = roots.length ? roots.map(r => renderAgent(r, true)).join('') : '<div style=\"color:#64748b;font-size:12px\">0 active agents. Click <b>+ Spawn Coordinator Tree</b> or use <code>./bin/seon</code>!</div>';
  document.getElementById('test-cache').innerHTML = (d.tests || []).length
    ? d.tests.map(t => `<div class=\"row-item\"><span style=\"color:${t.pass ? '#22c55e' : '#ef4444'}\">${t.pass ? 'PASS' : 'FAIL'}</span> <b>${t.var}</b> <span style=\"color:#64748b\">[${t.digest}] ${t.ms}ms</span></div>`).join('')
    : '<div style=\"color:#64748b;font-size:12px\">Click <b>🧪 Run Reach Tests</b> or run <code>./bin/seon test</code>!</div>';
  document.getElementById('mail-log').innerHTML = (d.mail || []).map(m => `<div class=\"row-item\"><b style=\"color:#38bdf8\">${m.from} → ${m.to}</b> [${m.type}] ${m.body}</div>`).join('');
}
const es = new EventSource('/events');
es.onmessage = (ev) => {
  snap = JSON.parse(ev.data);
  document.getElementById('boot-pill').textContent = 'Boot #' + (snap.bootSeq || 1);
  const tb = document.getElementById('throttle-btn');
  tb.classList.toggle('active', !!snap.throttled);
  if (snap.rebooting) return;
  renderGraph(snap);
  renderInspector();
  renderDomain(snap.domain);
};
window.addEventListener('resize', () => snap && renderWires(snap));
</script>
</body>
</html>")

;; --- Ring HTTP Handler (Fiber-Safe, Zero Atoms) ---

(defn- make-ring-handler [http-ch server-in-ch]
  (fn [req]
    (let [m (:request-method req)
          uri (:uri req)]
      (cond
        (and (= m :get) (= uri "/"))
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
         :body index-html}

        (and (= m :get) (= uri "/events"))
        (let [sse-ch (a/chan (a/sliding-buffer 8))]
          (a/put! http-ch {:op :open-tab :ch sse-ch})
          {:status 200
           :headers {"Content-Type" "text/event-stream"
                     "Cache-Control" "no-cache"
                     "Connection" "keep-alive"}
           :body sse-ch})

        ;; Primary CLI / MCP / Programmatic EDN Endpoint (~4 ms roundtrip!)
        (and (= m :post) (= uri "/op"))
        (let [raw (if-let [b (:body req)] (if (string? b) b (slurp b)) "")
              edn-req (edn/read-string raw)
              reply-ch (a/chan 1)]
          (a/>!! server-in-ch {:req edn-req :reply-ch reply-ch})
          (let [[ans _] (a/alts!! [reply-ch (a/timeout 60000)])]
            {:status 200
             :headers {"Content-Type" "application/edn; charset=utf-8"}
             :body (pr-str (or ans {:ok? false :error "Timeout waiting for Flow op"}))}))

        (and (= m :post) (str/starts-with? uri "/ui/"))
        (let [raw (if-let [b (:body req)] (if (string? b) b (slurp b)) "")
              body (when-not (str/blank? raw) (json/read-str raw :key-fn keyword))]
          (a/put! http-ch {:op :ui-act :uri uri :body body})
          {:status 204 :headers {}})

        :else
        {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))))

(defn- broadcast-tabs [st]
  (if (empty? (:tabs st))
    st
    (let [payload (str "data: " (json/write-str (build-ui-snapshot st)) "\n\n")
          live-tabs (into #{}
                          (filter (fn [ch] (not (false? (a/offer! ch payload)))))
                          (:tabs st))]
      (-> st (assoc :tabs live-tabs) (update :frames inc)))))

(defn web-proc
  ([] {:params {:port "HTTP/SSE port on 127.0.0.1 (0: disabled)"
                :conn "Read access to store via :main conn"
                :store-path "Store directory path"
                :fork-parent-pid "OS PID of 1-thread Fork Parent"
                :server-in-ch "Channel into :server proc"
                :model-ctrl-ch "Throttle control channel into :model"
                :report-chan "Flow report-chan"
                :error-chan "Flow error-chan"
                :flow-fn "0-arg fn returning current Flow instance"
                :on-power-loss "0-arg fn triggering cold reboot simulation"
                :boot-seq "Boot sequence counter"}
       :ins {:db "Commit notices from :transactor"
             :stream "In-flight streaming status from :model"}
       :outs {}
       :workload :io
       :doc "Serves HTTP `/op` EDN API + SSE Flow Monitor UI with zero atoms."
       :ping-map-fn (fn [st]
                      {:port (:bound-port st)
                       :open-tabs (count (:tabs st))
                       :sse-frames (:frames st)})})
  ([{:keys [port conn store-path fork-parent-pid server-in-ch model-ctrl-ch
            report-chan error-chan flow-fn on-power-loss boot-seq]}]
   (let [http-ch (a/chan 128)
         srv (when (and port (pos? port))
               (adapter/run-server (make-ring-handler http-ch server-in-ch)
                                   {:host "127.0.0.1"
                                    :port port
                                    :strategy :fibers
                                    :keep-alive-timeout-ms 600000
                                    :write-timeout-ms 60000}))]
     {:conn conn
      :store-path store-path
      :fork-parent-pid fork-parent-pid
      :model-ctrl-ch model-ctrl-ch
      :flow-fn flow-fn
      :on-power-loss on-power-loss
      :boot-seq (or boot-seq 1)
      :throttled? false
      :server srv
      :bound-port (when srv (:port srv))
      :tabs #{}
      :reports []
      :errors []
      :frames 0
      ::flow/in-ports (cond-> {:http http-ch}
                        report-chan (assoc :report report-chan)
                        error-chan  (assoc :error error-chan))}))
  ([st transition]
   (when (= transition ::flow/stop)
     (doseq [ch (:tabs st)] (a/close! ch))
     (when-let [s (:server st)]
       (try (adapter/stop-server s {:drain-timeout-ms 0}) (catch Throwable _))))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :db
       [(broadcast-tabs st) nil]

       :stream
       [(broadcast-tabs st) nil]

       :report
       (let [st' (-> st
                     (update :reports #(vec (take 60 (cons (str msg) %))))
                     broadcast-tabs)]
         [st' nil])

       :error
       (let [st' (-> st
                     (update :errors #(vec (take 25 (cons (pr-str msg) %))))
                     broadcast-tabs)]
         [st' nil])

       :http
       (case (:op msg)
         :open-tab
         (let [ch (:ch msg)
               st' (update st :tabs conj ch)]
           (a/offer! ch (str "data: " (json/write-str (build-ui-snapshot st')) "\n\n"))
           [st' nil])

         :ui-act
         (case (:uri msg)
           "/ui/toggle-throttle"
           (let [next-th (not (:throttled? st))]
             (when-let [mc (:model-ctrl-ch st)]
               (a/put! mc {:max-slots (if next-th 1 4)
                           :latency-ms (if next-th 1400 120)}))
             [(broadcast-tabs (assoc st :throttled? next-th)) nil])

           "/ui/proc"
           (let [{:keys [action proc]} (:body msg)
                 g (when-let [gf (:flow-fn st)] (gf))
                 target (keyword proc)]
             (when g
               (if (= action "pause")
                 (flow/pause-proc g target)
                 (flow/resume-proc g target)))
             [(broadcast-tabs st) nil])

           "/ui/power-loss"
           (do
             (when-let [f (:on-power-loss st)]
               ;; Run cold-reboot sequence asynchronously so this proc can stop cleanly!
               (a/go (f)))
             [st nil])

           [st nil])

         [st nil])

       [st nil]))))
