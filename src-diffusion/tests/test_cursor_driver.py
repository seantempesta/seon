"""CursorDriver FSM against a SCRIPTED stub model + the REAL bb oracle.

The test_control_loop pattern exactly: the stub is a perfect denoiser
(near-one-hot logits, one forward per round via stability_threshold=0),
targets are scripted per round, bb parse/lint is real. Every INTERPRET
arm is forced by a scripted output; glyph handling, masks, overlap-trim,
calibration arithmetic, and the CAL length probe are pinned offline.
"""

import shutil
from types import SimpleNamespace

import pytest

mx = pytest.importorskip("mlx.core")

from seon_diffusion.cursor import (CursorDriver, Policy, allow_vector,   # noqa: E402
                                   ban_vector, calibrate, glyph_ids,
                                   overlap_trim, posterior_margin)
from seon_diffusion.generate import GenConfig                            # noqa: E402

pytestmark = pytest.mark.skipif(
    shutil.which("bb") is None or shutil.which("node") is None,
    reason="bb/node not on PATH")

EOS, PAD, SPACE, CL, VOCAB = 3, 0, 32, 96, 512

# single-token glyph ids in the stub vocab (derived via the tokenizer,
# same contract as the real one — never hardcoded in the driver)
GLYPH_ID = {g: 300 + i for i, g in enumerate("①②③④⑤⑥⑦⑧⑨⑩")}
GLYPH_ID["⤵"] = 320
ID_GLYPH = {v: k for k, v in GLYPH_ID.items()}


class GlyphTok:
    def __call__(self, text, add_special_tokens=False):
        return {"input_ids": [GLYPH_ID.get(c, ord(c)) for c in text]}

    def decode(self, ids, skip_special_tokens=False):
        out = []
        for i in ids:
            if i in ID_GLYPH:
                out.append(ID_GLYPH[i])
            elif i == 10 or 31 < i < 256:
                out.append(chr(i))
        return "".join(out)


class StubModel:
    """Perfect denoiser: holds clamps, writes the next scripted target
    into free positions (space-filled past the target; EOS appended when
    the target's flag says so)."""

    def __init__(self, tok, targets):
        self.cfg = SimpleNamespace(canvas_length=CL, vocab_size=VOCAB)
        self.tok = tok
        self.targets = [(t, True) if isinstance(t, str) else t for t in targets]
        self.encoded = []
        self._mask = [False] * CL

    def new_cache(self):
        return [{"k": mx.zeros(1)}]

    def encode(self, ids, cache, past_len):
        self.encoded.append(self.tok.decode([int(t) for t in ids[0]]))

    def _emit(self, canvas_ids, stream):
        out, ti = [], 0
        for p in range(CL):
            if self._mask[p]:
                out.append(int(canvas_ids[0][p]))
            elif ti < len(stream):
                out.append(stream[ti])
                ti += 1
            else:
                out.append(SPACE)
        safe = [i if i < VOCAB else 1 for i in out]
        logits = mx.full((1, CL, VOCAB), -20.0)
        onehot = mx.zeros((1, CL, VOCAB))
        onehot[0, mx.arange(CL), mx.array(safe)] = 50.0
        return logits + onehot

    def decode(self, canvas_ids, cache, canvas_start, self_conditioning_logits=None):
        target, want_eos = self.targets.pop(0) if self.targets else ("", False)
        stream = self.tok(target)["input_ids"] + ([EOS] if want_eos else [])
        return self._emit(canvas_ids, stream)


@pytest.fixture(autouse=True)
def spy_clamp_mask(monkeypatch):
    """Hand the stub the clamp mask a real model implicitly respects."""
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


class SpyOracle:
    """Records every code string refine ever sees (glyph-leak assertions)."""

    def __init__(self, o):
        self.o = o
        self.seen = []

    def refine(self, code, phase=None):
        self.seen.append(code)
        return self.o.refine(code, phase=phase)


def driver(targets, oracle, policy=None):
    tok = GlyphTok()
    model = StubModel(tok, targets)
    spy = SpyOracle(oracle)
    d = CursorDriver(model, tok, spy,
                     policy=policy or Policy(probe_lengths=1), gen=gen_cfg())
    return d, model, spy


OFFERS = [{"glyph": "①", "label": "add todo",
           "template": [["clamp", '(todo/add! "'], ["free", 8], ["clamp", '")']]},
          {"glyph": "②", "label": "query",
           "template": [["clamp", "(db/query q)"]]}]


# ---------------------------------------------------------------------------
# pure helpers
# ---------------------------------------------------------------------------

def test_policy_defaults():
    p = Policy()
    assert p.auto_offer_margin == 6.0
    assert p.worst_entropy_gate == 1.0
    assert p.probe_lengths == 3 and p.probe_delta == 4
    assert p.ban_special and p.ban_eos_in_holes and p.candidate_masks
    assert p.min_overlap == 2 and p.settle_steps == 3
    assert p.max_rounds == 8 and p.max_attempts == 3
    assert p.glyph_page_size == 10


def test_overlap_trim():
    # the measured suffix echo: hole tail regenerates the following clamp
    t, k = overlap_trim('buy milk" :my', '" :my.plan/status :open})')
    assert t == "buy milk" and k == 5
    t, k = overlap_trim("no echo here", '" :status')
    assert t == "no echo here" and k == 0
    # below min_overlap: a single-char coincidence is not an echo
    t, k = overlap_trim("word)", ") tail", min_overlap=2)
    assert t == "word)" and k == 0
    t, k = overlap_trim("word))", ")) tail", min_overlap=2)
    assert t == "word" and k == 2


def test_calibration_subtraction():
    raw = {"①": -0.1, "②": -8.0}
    base = {"①": -3.0, "②": -9.0}
    cal = calibrate(raw, base)
    assert cal == {"①": 2.9, "②": 1.0}
    assert posterior_margin(cal) == pytest.approx(1.9)
    assert posterior_margin({"①": 4.2}) == pytest.approx(4.2)  # single = lift
    assert posterior_margin({}) is None


def test_glyph_ids_single_token_only():
    ids = glyph_ids(GlyphTok())
    assert len(ids) == 11 and ids["①"] == GLYPH_ID["①"] and ids["⤵"] == GLYPH_ID["⤵"]

    class SplitGrow(GlyphTok):
        def __call__(self, text, add_special_tokens=False):
            if text == "⤵":
                return {"input_ids": [1, 2]}       # multi-token: unusable
            return super().__call__(text)
    ids = glyph_ids(SplitGrow())
    assert "⤵" not in ids and len(ids) == 10


def test_open_tail_keeps_eos_legal(oracle):
    """Live-found bug: the real tokenizer declares EOS special, so the
    step-mode special ban was killing the done-ness meter. Holes ban
    EOS/pad/specials; the open tail bans specials MINUS EOS/pad."""
    class SpecialTok(GlyphTok):
        all_special_ids = [EOS, PAD, 400]      # 400 = a scaffold token

    tok = SpecialTok()
    d = CursorDriver(StubModel(tok, []), tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    assert d._open_ban_ids() == [400]
    assert set(d._hole_ban_ids()) == {EOS, PAD, 400}


def test_mask_construction():
    v = ban_vector(VOCAB, [EOS, PAD])
    assert float(v[EOS]) == -1e9 and float(v[PAD]) == -1e9
    assert float(v[ord("a")]) == 0.0
    assert ban_vector(VOCAB, []) is None
    a = allow_vector(VOCAB, GlyphTok(), [":open", ":done"])
    for ch in ":opendn":
        assert float(a[ord(ch)]) == 0.0
    assert float(a[ord("x")]) == -1e9 and float(a[EOS]) == -1e9


# ---------------------------------------------------------------------------
# INTERPRET arms — each forced by a scripted stub output
# ---------------------------------------------------------------------------

def test_arm_eos_complete_locks_and_done(oracle):
    d, _, _ = driver(["(defn f2a [x] x)"], oracle)
    r = d.step("ctx")
    assert r["arm"] == "eos-complete" and r["transition"] == "done"
    assert r["locked"] == ["(defn f2a [x] x)"] and r["new_draft"] == ""


def test_arm_text_progress_default(oracle):
    # clean form + trailing comment + EOS: lock the prefix, keep the rest —
    # exactly the guided loop's default move
    d, _, _ = driver(["(defn t1 [x] x)\n; still thinking"], oracle)
    r = d.step("ctx")
    assert r["arm"] == "text-progress" and r["transition"] == "progress"
    assert r["locked"] == ["(defn t1 [x] x)"]
    assert r["new_draft"] == "; still thinking"


def test_arm_broken_scramble_with_hints(oracle):
    d, _, _ = driver(["(def f2b [x] x)"], oracle)
    r = d.step("ctx")
    assert r["arm"] == "broken" and r["transition"] == "repair"
    assert not r["locked"]
    assert any("def takes no arg vector" in h for h in r["hints"])
    assert any(e["event"] == "scramble" and "def-vs-defn" in e["kinds"]
               for e in r["events"])


def test_arm_clean_unfinished_grows(oracle):
    d, _, _ = driver([("(defn part [x]", False)], oracle)
    r = d.step("ctx")
    assert r["arm"] == "clean-unfinished" and r["transition"] == "grow"
    assert r["new_draft"].startswith("(defn part [x]")


def test_arm_stuck_no_progress(oracle):
    d, _, _ = driver([("", False)], oracle)
    r = d.step("ctx", draft="(def f [x] x)")
    assert r["arm"] == "stuck" and r["transition"] == "stuck"
    assert r["new_draft"] == "(def f [x] x)"


def test_arm_glyph_select_expands_and_strips(oracle):
    d, model, spy = driver(["①", ("buy milk", False)], oracle)
    r = d.step("ctx", offers=OFFERS)
    assert r["arm"] == "glyph-select" and r["transition"] == "expand"
    assert r["glyph"] == "①"
    assert r["new_draft"] == '(todo/add! "buy milk")'
    # the glyph exists only between driver and model — never in text
    assert all("①" not in code for code in spy.seen)
    assert "①" not in r["new_draft"] and not r["locked"]


def test_grow_glyph_saves_a_round(oracle):
    d, _, _ = driver([("(+ 1 2) ⤵", False)], oracle)
    r = d.step("ctx")
    assert r["transition"] == "grow" and r["arm"] == "clean-unfinished"
    assert "⤵" not in r["new_draft"] and "(+ 1 2)" in r["new_draft"]
    assert any(e["event"] == "glyph" for e in r["events"])


# ---------------------------------------------------------------------------
# calibrated auto-offer
# ---------------------------------------------------------------------------

class AutoOfferStub(StubModel):
    """Call 1 (the null-render baseline): flat logits. Call 2 (the main
    denoise): free text is noise (spaces) but the ① glyph carries high
    mass at the cursor WITHOUT being argmax — the auto-offer shape.
    Calls 3+: scripted fill targets (the expansion)."""

    def __init__(self, tok, targets):
        super().__init__(tok, targets)
        self.calls = 0

    def decode(self, canvas_ids, cache, canvas_start, self_conditioning_logits=None):
        self.calls += 1
        if self.calls == 1:
            return mx.zeros((1, CL, VOCAB))
        if self.calls == 2:
            logits = self._emit(canvas_ids, [])          # all spaces
            bump = mx.zeros((1, CL, VOCAB))
            bump[0, 0, GLYPH_ID["①"]] = 40.0             # -20+40=20 < the space's 30
            return logits + bump
        return super().decode(canvas_ids, cache, canvas_start,
                              self_conditioning_logits)


def test_auto_offer_fires_on_calibrated_margin(oracle):
    tok = GlyphTok()
    model = AutoOfferStub(tok, [("milk", False)])
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    r = d.step("ctx", offers=OFFERS, null_render="null intent")
    assert r["transition"] == "expand" and r["glyph"] == "①"
    assert any(e["event"] == "auto-offer" for e in r["events"])
    cal = r["readouts"]["glyph_posteriors_calibrated"]
    assert cal["①"] > cal["②"]
    assert r["readouts"]["glyph_margin"] > Policy().auto_offer_margin
    assert "milk" in r["new_draft"]


def test_no_auto_offer_when_model_typed_code(oracle):
    """Never override typing: a parse-clean free-typed form wins even if
    the glyph posterior margin clears the threshold."""
    class TypedStub(AutoOfferStub):
        def decode(self, canvas_ids, cache, canvas_start,
                   self_conditioning_logits=None):
            self.calls += 1
            if self.calls == 1:
                return mx.zeros((1, CL, VOCAB))
            logits = self._emit(canvas_ids,
                                self.tok("(db/q 1)")["input_ids"] + [EOS])
            bump = mx.zeros((1, CL, VOCAB))
            bump[0, 0, GLYPH_ID["①"]] = 40.0
            return logits + bump

    tok = GlyphTok()
    model = TypedStub(tok, [])
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    r = d.step("ctx", offers=OFFERS, null_render="null intent")
    assert r["transition"] != "expand"
    assert not any(e["event"] == "auto-offer" for e in r["events"])
    assert "(db/q 1)" in (r["new_draft"] + " ".join(r["locked"]))


# ---------------------------------------------------------------------------
# fill: overlap-trim + readouts + CAL probe
# ---------------------------------------------------------------------------

def test_fill_overlap_trim_and_confidence(oracle):
    d, _, _ = driver([('buy milk" :my', False)], oracle)
    r = d.fill("ctx", [("clamp", '(todo/add! "'), ("free", 14),
                       ("clamp", '" :my.plan/status :open})')])
    assert r["holes"] == ["buy milk"] and r["trims"] == [5]
    assert r["text"] == '(todo/add! "buy milk" :my.plan/status :open})'
    st = r["hole_confidence"][0]
    assert st["worst"] < 1.0 and st["accepted"] is True
    assert r["tok_per_s"] > 0


def test_fill_candidate_hole_sized_and_masked(oracle):
    """A hole with a candidate set is CLOSED: sized to the longest
    candidate (no slack → no echo/repeat junk) and skipped by CAL."""
    d, _, _ = driver([(":op", False)], oracle,
                     policy=Policy(probe_lengths=3))
    r = d.fill("ctx", [("clamp", "(s "), ("free", 9), ("clamp", ")")],
               candidates={"0": [":op", ":no"]})
    assert r["holes"] == [":op"] and r["text"] == "(s :op)"
    assert r["probes"] == []                   # candidate hole: no CAL probe
    assert r["hole_confidence"][0]["snapped"] is True


def test_rank_orders_candidates(oracle):
    d, _, _ = driver([(":open", False)] * 3, oracle)
    r = d.rank("ctx", '(todo/add! {:my.plan/status ',
               [":open", ":done"], "})")
    assert [c["candidate"] for c in r["ranked"]] == [":open", ":done"]
    assert r["ranked"][0]["score"] > r["ranked"][1]["score"]
    assert r["calibrated"] is False and r["forwards"] == 3


class ProbeStub(StubModel):
    """Fixed belief: 'abc' at the hole start, FLAT logits past it — the
    CAL Φ(L) signal peaks when the hole length matches the content."""

    def __init__(self, tok, hole_start):
        super().__init__(tok, [])
        self.hole_start = hole_start

    def decode(self, canvas_ids, cache, canvas_start, self_conditioning_logits=None):
        logits = mx.zeros((1, CL, VOCAB))
        for i, t in enumerate(self.tok("abc")["input_ids"]):
            logits[0, self.hole_start + i, t] = 50.0
        return logits


def test_cal_hole_length_probe_picks_peak(oracle):
    tok = GlyphTok()
    model = ProbeStub(tok, hole_start=len(tok("(x ")["input_ids"]))
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=3, probe_delta=4),
                     gen=gen_cfg())
    r = d.fill("ctx", [("clamp", "(x "), ("free", 7), ("clamp", ")")])
    assert r["probes"][0]["chosen"] == 3
    assert set(r["probes"][0]["scores"]) == {3, 7, 11}
    assert r["probes"][0]["scores"][3] == max(r["probes"][0]["scores"].values())
    assert r["holes"] == ["abc"] and r["text"] == "(x abc)"


# ---------------------------------------------------------------------------
# eval-gated lock (real node session)
# ---------------------------------------------------------------------------

@pytest.fixture()
def session():
    from seon_diffusion.oracle import EvalSession
    s = EvalSession()
    yield s
    s.close()


def test_eval_gated_lock(oracle, session):
    d, _, _ = driver(["(defn ev2 [x] (+ x 1))"], oracle)
    r = d.step("ctx", eval_session=session)
    assert r["transition"] == "done" and r["locked"] == ["(defn ev2 [x] (+ x 1))"]

    d2, _, _ = driver(["(defn ev3 [xs] (filter even xs))"], oracle)
    r2 = d2.step("ctx", eval_session=session)
    assert r2["transition"] == "repair" and not r2["locked"]
    assert any(e["event"] == "eval-failed" for e in r2["events"])


# ---------------------------------------------------------------------------
# orientation line (round 7: 0/3->3/3 slot correctness)
# ---------------------------------------------------------------------------

def test_orient_for_renders_label_and_candidates():
    from seon_diffusion.repair import orient_for, strip_hints
    line = orient_for("(todo/add! …)", {1: [":open", ":done"]})
    assert line.startswith("; slot:")
    assert "(todo/add! …)" in line and ":open :done" in line
    assert strip_hints("(+ 1 2)\n" + line) == "(+ 1 2)\n"
    assert orient_for(None, {}) == ""


def test_expand_clamps_orientation_and_strips_from_draft(oracle):
    d, model, spy = driver(["①", (":open", False)], oracle)
    offers = [{"glyph": "①", "label": "(todo/add! <status>)",
               "candidates": {"0": [":open", ":done"]},
               "template": [["clamp", "(todo/add! "], ["free", 3],
                            ["clamp", ")"]]}]
    r = d.step("ctx", offers=offers)
    assert r["transition"] == "expand"
    # orientation line never leaks into the assembled draft
    assert "; slot:" not in r["new_draft"]
    # offer candidates flowed through to the closed-hole SNAP
    exp = r["expansion"]
    assert exp["hole_confidence"][0].get("snapped") is True
    assert exp["holes"][0] in (":open", ":done")
    assert r["new_draft"] == f"(todo/add! {exp['holes'][0]})"
