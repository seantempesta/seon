"""generate_guided logic against a SCRIPTED stub model + the REAL oracles.

The stub emits one scripted canvas per round (near-one-hot logits, so a
round is exactly one forward: stability_threshold=0 and the entropy is
~0). bb parse/lint and the node eval session are the real ones — this
pins the loop's decisions (lock, harvest, scramble, hint, repair,
attempt-restart) without loading the 26GB model.
"""

import shutil
from types import SimpleNamespace

import pytest

mx = pytest.importorskip("mlx.core")

from seon_diffusion.control import generate_guided          # noqa: E402
from seon_diffusion.generate import GenConfig               # noqa: E402

pytestmark = pytest.mark.skipif(
    shutil.which("bb") is None or shutil.which("node") is None,
    reason="bb/node not on PATH")

EOS, PAD, CL, VOCAB = 3, 0, 96, 512


class CharTok:
    def decode(self, ids, skip_special_tokens=False):
        return "".join(chr(i) for i in ids if i == 10 or 31 < i < 256)

    def __call__(self, text, add_special_tokens=False):
        return {"input_ids": [ord(c) for c in text]}


class StubModel:
    """A PERFECT DENOISER: keeps clamped positions verbatim and writes the
    next scripted target into the FREE positions (one target per round —
    one decode call per round because stability_threshold=0 and the
    near-one-hot logits pass the confidence gate immediately)."""

    def __init__(self, targets):
        self.cfg = SimpleNamespace(canvas_length=CL, vocab_size=VOCAB)
        self.targets = list(targets)
        self.encoded = []                    # harvested texts, for assertions
        self.seen_canvases = []              # decoded canvas ins, for assertions
        self._mask = [False] * CL            # set by the _denoise_round spy

    def new_cache(self):
        return [{"k": mx.zeros(1)}]

    def encode(self, ids, cache, past_len):
        self.encoded.append(CharTok().decode([int(t) for t in ids[0]]))

    def decode(self, canvas_ids, cache, canvas_start, self_conditioning_logits=None):
        self.seen_canvases.append(CharTok().decode([int(t) for t in canvas_ids[0]]))
        target = self.targets.pop(0) if self.targets else ""
        stream = [ord(c) for c in target] + [EOS]
        out, ti = [], 0
        for p in range(CL):
            if self._mask[p]:
                out.append(int(canvas_ids[0][p]))     # hold the clamp
            elif ti < len(stream):
                out.append(stream[ti])
                ti += 1
            else:
                out.append(PAD)
        # clamped positions may hold ids outside the stub vocab (e.g. an
        # em-dash in a hint) — their logits are irrelevant (belief overrides
        # with the clamp ids), so scatter any in-range id there
        safe = [i if i < VOCAB else 1 for i in out]
        logits = mx.full((1, CL, VOCAB), -20.0)
        onehot = mx.zeros((1, CL, VOCAB))
        onehot[0, mx.arange(CL), mx.array(safe)] = 50.0
        return logits + onehot


@pytest.fixture(autouse=True)
def spy_clamp_mask(monkeypatch):
    """Hand the stub the clamp mask a real model implicitly respects
    (clamped logits are forced) — the stub is a perfect denoiser."""
    import seon_diffusion.control as C
    real = C._denoise_round
    def wrapper(model, canvas, clamp_mask, clamp_ids, cache, cur_len, gen, **kw):
        model._mask = [bool(b) for b in clamp_mask[0]]
        return real(model, canvas, clamp_mask, clamp_ids, cache, cur_len, gen, **kw)
    monkeypatch.setattr(C, "_denoise_round", wrapper)


def gen_cfg():
    return GenConfig(seed=1, entropy_bound=0.1, stability_threshold=0,
                     confidence_threshold=0.05, max_denoising_steps=4,
                     eos_token_ids=(EOS,), pad_token_id=PAD)


@pytest.fixture(scope="module")
def oracle():
    from seon_diffusion.oracle import Oracle
    o = Oracle()
    yield o
    o.close()


@pytest.fixture()
def session():
    from seon_diffusion.oracle import EvalSession
    s = EvalSession()
    yield s
    s.close()


def run(targets, oracle, session, **kw):
    model = StubModel(targets)
    tok = CharTok()
    r = generate_guided(model, tok, [ord(c) for c in "prompt"], oracle,
                        eval_session=session, gen=gen_cfg(), **kw)
    return r, model


def test_def_typo_scrambled_with_hint_then_locked(oracle, session):
    r, model = run(["(def f1 [x] x)", "(defn f1 [x] x)"], oracle, session)
    assert r["done"] and r["locked_forms"] == 1
    assert "(defn f1 [x] x)" in r["text"]
    scrambles = [e for e in r["events"] if e["event"] == "scramble"]
    assert scrambles and "def-vs-defn" in scrambles[0]["kinds"]
    # the hint comment was CLAMPED into the round-2 canvas the model saw
    assert any("; fix:" in c for c in model.seen_canvases[1:])


def test_undeclared_var_repaired_not_scrambled(oracle, session):
    r, _ = run(["(defn g1 [xs] (filter even xs))"], oracle, session)
    assert r["done"] and r["repairs"] == 1
    assert "(filter even? xs)" in r["text"]
    assert any(e["event"] == "repair" for e in r["events"])
    assert not any(e["event"] == "scramble" for e in r["events"])


def test_failing_check_restarts_attempt(oracle, session):
    r, model = run(["(defn h1 [x] (+ x 1))", "(defn h1 [x] (+ x 2))"],
                   oracle, session,
                   checks=[{"call": "(h1 1)", "expect": "3"}])
    assert r["done"] and r["attempts"] == 2 and r["checks_passed"]
    assert "(+ x 2)" in r["text"]
    # the behavioral failure rode the content channel into attempt 2
    assert any("must return 3" in c for c in model.seen_canvases[1:])


def test_harvest_reaches_encoder_cache(oracle, session):
    r, model = run(["(defn k1 [x] x)"], oracle, session)
    assert r["done"]
    assert any("(defn k1 [x] x)" in t for t in model.encoded)


def test_phase_gate_rejects_wrong_head(oracle, session):
    r, _ = run(["(defn p1 [x] x)"], oracle, session,
               phase="schemas", max_rounds=2, max_attempts=1)
    assert not r["done"]
    assert any("phase-violation" in e.get("kinds", []) for e in r["events"]
               if e["event"] == "scramble")


# ---- checkpoint-policy fixtures (ported from the retired seon.diffusion.loop) ----

def test_policy_no_progress_gives_up(oracle, session):
    """Identical error signature two rounds running → stuck, not an infinite loop."""
    r, _ = run(["(def z1 [x] x)", "(def z1 [x] x)", "(def z1 [x] x)"],
               oracle, session, max_attempts=1)
    assert not r["done"]
    assert any(e["event"] == "stuck" for e in r["events"])


def test_policy_budget_exhausted_gives_up(oracle, session):
    """Rotating (never-identical) errors still terminate at max_rounds."""
    targets = [f"(def q{i} [x] x)" for i in range(6)]
    r, _ = run(targets, oracle, session, max_rounds=3, max_attempts=1)
    assert not r["done"]
    assert r["rounds"] == 3
