"""Root's web latency at the current agent count (ported from proto/web/bench/latency.py, lane L2).
usage: python3 -I latency.py PORT [GETS] [EVALS] [TABS]
- page GET: sequential keep-alive GETs of / (ms p50/p95, bytes)
- patch latency: POST /call?fn=seon.root/submit! (a UI call: an eval recorded with origin :ui)
  with a unique marker form to a random live agent, timed until the marker's printed value
  arrives in EVERY open SSE tab of root's view (p50/p95 of the first tab, p95 of the last)
- the render proc's own numbers after the run (GET /render.edn)
"""
import asyncio, json, random, re, sys, time, http.client, urllib.parse

port = int(sys.argv[1]); gets = int(sys.argv[2]) if len(sys.argv) > 2 else 100
evals = int(sys.argv[3]) if len(sys.argv) > 3 else 40; tabs = int(sys.argv[4]) if len(sys.argv) > 4 else 3

def pct(xs, p): xs = sorted(xs); return round(xs[min(len(xs)-1, int(p*len(xs)))], 2) if xs else None

def page_gets():
    c = http.client.HTTPConnection("127.0.0.1", port); ts = []; size = 0
    for i in range(gets + 10):
        t = time.perf_counter(); c.request("GET", "/"); r = c.getresponse(); body = r.read(); dt = (time.perf_counter() - t) * 1000
        if i >= 10: ts.append(dt); size = len(body)
    html = body.decode()
    alive = [i for i, s in re.findall(r'id="cb-([A-Za-z0-9_-]+)" class="cb s-([a-z]+)', html) if s in ("idle", "busy")]
    alive = alive or [i for s, i in re.findall(r'class="cb s-([a-z]+)[^"]*" id="cb-([A-Za-z0-9_-]+)"', html) if s in ("idle", "busy")]
    return {"get_p50_ms": pct(ts, .5), "get_p95_ms": pct(ts, .95), "page_bytes": size}, alive

async def tab(seen, k, ready, nbytes):
    r, w = await asyncio.open_connection("127.0.0.1", port)
    w.write(b"GET /feed?view=root HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n"); await w.drain()
    ready.set_result(True) if not ready.done() else None
    buf = b""
    while True:
        chunk = await r.read(65536)
        if not chunk: return
        nbytes[k] = nbytes.get(k, 0) + len(chunk)
        buf += chunk
        for m in re.finditer(rb"mk(\d+)x", buf):
            seen.setdefault(int(m.group(1)), {}).setdefault(k, time.perf_counter())
        buf = buf[-200:]

async def patches(alive):
    seen = {}; nbytes = {}; readies = [asyncio.get_event_loop().create_future() for _ in range(tabs)]
    tasks = [asyncio.create_task(tab(seen, k, readies[k], nbytes)) for k in range(tabs)]
    await asyncio.gather(*readies); await asyncio.sleep(1.5)
    b0 = dict(nbytes); sent = {}; loop = asyncio.get_event_loop()
    def post(aid, n):
        c = http.client.HTTPConnection("127.0.0.1", port)
        body = "forms=" + urllib.parse.quote(f'(str "mk{n}x")')
        c.request("POST", "/call?fn=seon.root%2Fsubmit%21&agent=" + aid, body, {"Content-Type": "application/x-www-form-urlencoded"}); c.getresponse().read()
    for n in range(evals):
        aid = random.choice(alive); sent[n] = time.perf_counter()
        await loop.run_in_executor(None, post, aid, n)
        await asyncio.sleep(0.15)
    await asyncio.sleep(1.5)
    first = [(min(seen[n].values()) - sent[n]) * 1000 for n in sent if n in seen]
    last = [(max(seen[n].values()) - sent[n]) * 1000 for n in sent if n in seen and len(seen[n]) == tabs]
    per_tab = (nbytes.get(0, 0) - b0.get(0, 0)) / max(1, evals)
    for t in tasks: t.cancel()
    return {"patch_p50_ms": pct(first, .5), "patch_p95_ms": pct(first, .95), "fan_p95_ms": pct(last, .95),
            "bytes_per_eval_per_tab": int(per_tab), "delivered": f"{len(last)}/{len(sent)} to all {tabs} tabs"}

g, alive = page_gets()
p = asyncio.run(patches(alive))
c = http.client.HTTPConnection("127.0.0.1", port); c.request("GET", "/render.edn"); rs = c.getresponse().read().decode()
print(json.dumps({**g, **p, "alive": len(alive)}))
print(rs)
