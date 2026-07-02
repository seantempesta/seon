import os, sys, json, time, urllib.request

EP = os.environ.get("DIFFGEMMA_EP", "66ofi51onfelby")

def api(path, method="GET", body=None):
    key = os.environ["RUNPOD_API_KEY"]
    req = urllib.request.Request(
        f"https://api.runpod.ai/v2/{EP}/{path}",
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method=method)
    return json.load(urllib.request.urlopen(req, timeout=90))

def main():
    # Async /run RETAINS results for polling (unlike /runsync). payload reaches
    # the @Endpoint function as **kwargs via {"input": ...}.
    payload = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {
        "mode": "generate",
        "prompt": ("Write an idiomatic Clojure function `mean` that returns the "
                   "average of a vector of numbers. Reply with ONLY the code in a "
                   "```clojure block."),
        "max_new_tokens": 256,
    }
    j = api("run", "POST", {"input": payload})
    jid = j["id"]
    print("submitted", jid, j.get("status"), flush=True)
    for i in range(100):
        s = api(f"status/{jid}")
        st = s.get("status")
        print(f"[{i}] {st}", flush=True)
        if st == "COMPLETED":
            print("=== OUTPUT ===")
            print(json.dumps(s.get("output"), indent=2))
            break
        if st in ("FAILED", "CANCELLED"):
            print("=== FAILED ===")
            print(json.dumps(s, indent=2)[:2000])
            break
        time.sleep(15)

if __name__ == "__main__":
    main()
