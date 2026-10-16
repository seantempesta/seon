"""Load test for server.py:  python -I loadtest.py URL FORMS.jsonl
Sequential single-text latency, concurrent single-text clients (request merging), and one bulk request."""
import sys, json, time, threading, base64, urllib.request, numpy as np
url, corpus = sys.argv[1], sys.argv[2]
rows = [json.loads(l)["text"] for l in open(corpus)]
def post(texts, **kw):
    b = json.dumps({"texts": texts, "task": "code", **kw}).encode()
    with urllib.request.urlopen(urllib.request.Request(url + "/v1/embed", b, {"Content-Type": "application/json"})) as r: return json.loads(r.read())
short = [t for t in rows if 200 < len(t) < 500]
for s in short[:5]: post([s])
lat = []
for s in short[:100]:
    t = time.perf_counter(); post([s], encoding="f16", dim=256); lat.append((time.perf_counter() - t) * 1000)
print(f"sequential 1 text (~{np.mean([len(s) for s in short[:100]])/3.3:.0f} tok), dim 256 f16: p50 {np.percentile(lat,50):.1f} ms p99 {np.percentile(lat,99):.1f} ms")
for C in (8, 32, 64):
    lat, lock = [], threading.Lock()
    def client(k):
        for j in range(20):
            t = time.perf_counter(); post([short[(k * 20 + j) % len(short)]], encoding="f16"); d = (time.perf_counter() - t) * 1000
            with lock: lat.append(d)
    ts = [threading.Thread(target=client, args=(k,)) for k in range(C)]; t0 = time.perf_counter()
    [t.start() for t in ts]; [t.join() for t in ts]; dt = time.perf_counter() - t0
    print(f"{C} concurrent clients x 20 single-text requests: {len(lat)/dt:.0f} emb/s, p50 {np.percentile(lat,50):.1f} ms p99 {np.percentile(lat,99):.1f} ms")
t = time.perf_counter(); r = post(rows[:2000], encoding="f16", dim=256); dt = time.perf_counter() - t
v = np.frombuffer(base64.b64decode(r["vectors"]), "<f2").reshape(r["n"], r["dim"])
print(f"bulk 2000 forms ({r['input_tokens']} tokens) dim 256 f16: {dt:.2f}s, {r['n']/dt:.0f} emb/s, {r['input_tokens']/dt/1000:.0f}k tok/s, norms {np.linalg.norm(v.astype(np.float32),axis=1).min():.3f}")
