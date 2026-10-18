"""Figures for the paper, as SVG, in the style of the author's other repositories (no plotting library).

    python scripts/figures.py            reads results/summary.md, writes results/figures/

fig1_layer.svg        where the layer sits: beneath Spring AI, LangChain4j or no framework, above the HTTP client
fig2_contract.svg     share of applicable cells meeting the whole contract, per client and setup (Table 1, last row)
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
R = ROOT / "results"
OUT = R / "figures"
OUT.mkdir(parents=True, exist_ok=True)

# Validated set (dataviz reference palette, slots 1-3, all-pairs CVD dE >= 9.2 on white);
# aqua is below 3:1 on white, so every bar carries a printed value label.
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, GRID, FRAME = "#111111", "#555555", "#eeeeee", "#999999"
FONT = "font-family='Helvetica,Arial,sans-serif' font-size='11'"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def svg(w, h, body):
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{w}' height='{h}' viewBox='0 0 {w} {h}' {FONT}>\n"
            f"<rect width='{w}' height='{h}' fill='white'/>\n" + "\n".join(body) + "\n</svg>\n")


def text(x, y, s, anchor="start", size=11, weight="normal", fill=INK, family=None):
    fam = f" font-family='{family}'" if family else ""
    return (f"<text x='{x:.1f}' y='{y:.1f}' text-anchor='{anchor}' font-size='{size}' font-weight='{weight}' "
            f"fill='{fill}'{fam}>{esc(s)}</text>")


def box(x, y, w, h, title, lines=(), stroke=FRAME, fill="white", mono=False):
    out = [f"<rect x='{x}' y='{y}' width='{w}' height='{h}' rx='6' fill='{fill}' stroke='{stroke}' stroke-width='1.2'/>",
           text(x + w / 2, y + 17, title, anchor="middle", weight="bold")]
    for k, l in enumerate(lines):
        out.append(text(x + w / 2, y + 33 + 13 * k, l, anchor="middle", size=10, fill=INK2,
                        family="Consolas,Menlo,monospace" if mono else None))
    return out


def arrow(x1, y1, x2, y2):
    return f"<line x1='{x1}' y1='{y1}' x2='{x2}' y2='{y2}' stroke='{INK}' stroke-width='1.2' marker-end='url(#a)'/>"


def fig1():
    w, h = 760, 400
    b = ["<defs><marker id='a' viewBox='0 0 10 10' refX='9' refY='5' markerWidth='7' markerHeight='7' orient='auto'>"
         f"<path d='M0 0 L10 5 L0 10 z' fill='{INK}'/></marker></defs>"]
    b += box(250, 10, 260, 40, "Application")
    cols = [(60, "Spring AI", ["OpenAiChatModel", "maxRetries = 0"]),
            (290, "LangChain4j", ["OpenAiChatModel", "maxRetries = 0"]),
            (520, "no framework", ["direct call"])]
    for x, t, ls in cols:
        b += box(x, 80, 180, 56, t, ls)
        b.append(arrow(380, 50, x + 90, 78))
    adapters = [(60, "OkHttp interceptor", ["210 lines"]), (290, "LangChain4j HttpClient", ["137 lines"]),
                (520, "java.net.http transport", ["290 lines"])]
    # the guard band
    b.append(f"<rect x='40' y='158' width='680' height='144' rx='8' fill='#f3f8fe' stroke='{BLUE}' stroke-width='1.4'/>")
    b.append(text(52, 297, "guard — one contract, C1–C6", weight="bold", fill=BLUE))
    for x, t, ls in adapters:
        b += box(x, 186, 180, 40, t, ls)
    for x, *_ in cols:
        b.append(arrow(x + 90, 136, x + 90, 184))
    core = [("RetryRules", "C1, C2"), ("WireCheck", "C3"), ("GuardedSseStream", "C3, C4"), ("CallObservations", "C5, C6")]
    cx = 60
    for name, cl in core:
        b += box(cx, 240, 152, 48, name, [cl], stroke=AQUA, mono=False)
        cx += 166
    b.append(text(380, 237, "", anchor="middle"))
    b.append(text(712, 297, "core: 725 lines, JDK + Micrometer Observation API", anchor="end", size=10, fill=INK2))
    b += box(250, 322, 260, 32, "HTTP: JSON body or server-sent events")
    for x, *_ in cols:
        b.append(f"<line x1='{x + 90}' y1='226' x2='{x + 90}' y2='236' stroke='{FRAME}' stroke-width='1' stroke-dasharray='3 2'/>")
    b.append(arrow(380, 302, 380, 320))
    b += box(250, 364, 260, 32, "Provider: OpenAI-compatible endpoint")
    b.append(arrow(380, 354, 380, 362))
    return svg(w, h, b)


def fig2():
    md = (R / "summary.md").read_text(encoding="utf-8")
    head = next(l for l in md.splitlines() if l.startswith("| Clause |"))
    allrow = next(l for l in md.splitlines() if l.startswith("| **all clauses**"))
    cols = [c.strip() for c in head.strip("|").split("|")][1:]
    vals = [c.strip().strip("*") for c in allrow.strip("|").split("|")][1:]
    cell = {c: tuple(int(x) for x in v.split("/")) for c, v in zip(cols, vals)}
    groups = [("LangChain4j 1.21.0", "langchain4j"), ("Spring AI 2.0.1", "spring"), ("java.net.http", "plain")]
    setups = [("defaults", "framework defaults", ORANGE), ("explicit", "explicit configuration", AQUA),
              ("guard", "with the guard", BLUE)]
    left, right, top = 160, 70, 40
    bar_h, gap_in, gap_out = 14, 3, 16
    rows = []
    for label, key in groups:
        bars = [(s, n, col) for s, n, col in setups if f"{key} · {s}" in cell]
        rows.append((label, key, bars))
    plot_h = sum(len(b) * bar_h + (len(b) - 1) * gap_in for _, _, b in rows) + gap_out * (len(rows) - 1) + 16
    w, h = 760, top + plot_h + 44
    pw = w - left - right
    sx = lambda v: left + pw * v / 100
    b = []
    lx = left
    for _, name, col in setups:
        b.append(f"<rect x='{lx}' y='{top - 28}' width='10' height='10' rx='2' fill='{col}'/>")
        b.append(text(lx + 14, top - 19, name, fill=INK2))
        lx += 24 + 6.2 * len(name)
    b.append(f"<rect x='{left}' y='{top}' width='{pw}' height='{plot_h}' fill='none' stroke='{FRAME}'/>")
    for k in range(6):
        v = 20 * k
        b.append(f"<line x1='{sx(v):.1f}' x2='{sx(v):.1f}' y1='{top}' y2='{top + plot_h}' stroke='{GRID}'/>")
        b.append(text(sx(v), top + plot_h + 14, f"{v}", anchor="middle", fill=INK2))
    b.append(text(left + pw / 2, top + plot_h + 32, "% of applicable cells meeting all six clauses", anchor="middle", fill=INK2))
    y = top + 8
    for label, key, bars in rows:
        gh = len(bars) * bar_h + (len(bars) - 1) * gap_in
        b.append(text(left - 8, y + gh / 2 + 4, label, anchor="end"))
        for s, _, col in bars:
            k, n = cell[f"{key} · {s}"]
            v = 100 * k / n
            b.append(f"<rect x='{sx(0):.1f}' y='{y:.1f}' width='{sx(v) - sx(0):.1f}' height='{bar_h}' rx='2' fill='{col}'/>")
            b.append(text(sx(v) + 5, y + bar_h - 3, f"{k}/{n} ({v:.0f} %)", size=10))
            y += bar_h + gap_in
        y += gap_out - gap_in
    return svg(w, h, b)


(OUT / "fig1_layer.svg").write_text(fig1(), encoding="utf-8")
(OUT / "fig2_contract.svg").write_text(fig2(), encoding="utf-8")
print("figures written:", ", ".join(sorted(f.name for f in OUT.glob("*.svg"))))
