"""Summarises results/outcomes.csv against the reliability contract.

The contract (README, paper §3) has six clauses. Each is checked per cell = scenario × client × mode × setup, over
all repetitions of the cell (a cell meets a clause only if every repetition does). A clause that does not apply to a
scenario is not counted. Writes results/summary.md and results/contract.csv.

    C1 retry exactly the transient failures      transient: attempts > 1; permanent, or data already delivered: attempts = 1
    C2 wait as long as the provider asks         S04, S05: every gap >= Retry-After (3 s, 10 ms tolerance)
    C3 never present an incomplete answer        the application receives the complete answer or an exception, never PARTIAL
    C4 bound silence, not length                 a recoverable fault ends in the answer (S02, S04, S08); slow but live streams complete (S13, S15)
    C5 record every outcome as it was            metrics record a failure iff the application got one; the guard also names the kind
    C6 make every attempt visible                one instrumentation record per request that reached the provider
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
import sys
SRC = sys.argv[1] if len(sys.argv) > 1 else "outcomes.csv"
rows = list(csv.DictReader(open(ROOT / "results" / SRC, encoding="utf-8")))

TRANSIENT = {"S02", "S03", "S04", "S05", "S08", "S14"}
PERMANENT = {"S06", "S07", "S09", "S10", "S11", "S12"}  # S11, S12: data already delivered
RETRY_AFTER = {"S04", "S05"}
RECOVERABLE = {"S02", "S04", "S08"}
LIVE_STREAMS = {"S13", "S15"}
KIND = {"S03": "server-error", "S05": "rate-limited", "S06": "client-error", "S07": "authentication", "S09": "malformed-response",
        "S10": "empty-response", "S11": "stream-truncated", "S12": "stream-error-event", "S14": "no-response"}
CLAUSES = ["C1", "C2", "C3", "C4", "C5", "C6"]
CLAUSE_TEXT = {
    "C1": "retry exactly the transient failures",
    "C2": "wait as long as the provider asks",
    "C3": "never present an incomplete answer as complete",
    "C4": "bound silence, not length; recover what is recoverable",
    "C5": "record every outcome as it was",
    "C6": "make every attempt visible",
}


def metric_failure(r):
    """Did a metric record this call as failed? Guard: its call observation. Spring AI: the gen_ai observation
    stopped with an error. LangChain4j: a meter with an error tag (the listener is not a metric)."""
    if r["setup"] == "guard":
        return r["guard_record"].startswith("failure/")
    inst = r["instrumentation"].split()
    if r["client"].startswith("spring"):
        return any(t.startswith("gen_ai.client.operation!") for t in inst)
    return r["meter_error_tag"] not in ("-", "none", "")


def attempts_recorded(r):
    inst = r["instrumentation"].split()
    if r["setup"] == "guard":
        return int(r["guard_attempts"])
    if r["client"].startswith("spring"):
        return sum(t.startswith("okhttp.requests") for t in inst)
    return inst.count("listener.request")


def check(sc, r):
    """clause -> True / False / None (not applicable) for one repetition."""
    a = int(r["attempts"])
    gaps = [int(x) for x in r["gaps_ms"].split()]
    res = r["result"]
    out = {}
    out["C1"] = (a > 1) if sc in TRANSIENT else (a == 1) if sc in PERMANENT else None
    out["C2"] = (len(gaps) > 0 and min(gaps) >= 2990) if sc in RETRY_AFTER else None
    out["C3"] = res in ("OK", "ERROR")
    out["C4"] = (res == "OK") if sc in RECOVERABLE | LIVE_STREAMS else None
    ok_record = metric_failure(r) == (res != "OK")
    if r["setup"] == "guard" and sc in KIND:
        ok_record = ok_record and r["guard_record"].split("/")[1] == KIND[sc]
    out["C5"] = ok_record
    out["C6"] = attempts_recorded(r) == a
    return out


groups = defaultdict(list)
for r in rows:
    groups[(r["scenario"], r["client"], r["mode"], r["setup"])].append(r)

cells = []
for (sc, client, mode, setup), g in sorted(groups.items()):
    per = [check(sc, r) for r in g]
    verdict = {}
    for c in CLAUSES:
        vals = [p[c] for p in per]
        verdict[c] = None if vals[0] is None else all(vals)
    res = {r["result"] for r in g}
    gaps = [int(x) for r in g for x in r["gaps_ms"].split()]
    cells.append({
        "scenario": sc, "client": client, "mode": mode, "setup": setup, "reps": len(g),
        "result": res.pop() if len(res) == 1 else "mixed:" + "/".join(sorted(res)),
        "error": "/".join(sorted({r["error"].split(" < ")[0] for r in g if r["error"]})) or "-",
        "guard_kind": "/".join(sorted({r["guard_record"].split("/")[1] for r in g if r["guard_record"] != "-"})) or "-",
        "attempts": statistics.median(int(r["attempts"]) for r in g),
        "gap_min_ms": min(gaps) if gaps else "",
        "millis_median": int(statistics.median(int(r["millis"]) for r in g)),
        **{c: ("" if verdict[c] is None else "yes" if verdict[c] else "NO") for c in CLAUSES},
    })

with open(ROOT / "results" / "contract.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(cells[0]))
    w.writeheader()
    w.writerows(cells)

# clause x (client, setup): cells meeting / cells applicable
cols = sorted({(c["client"], c["setup"]) for c in cells}, key=lambda k: (k[0], ["defaults", "explicit", "guard"].index(k[1])))
md = ["# Summary", "", "Generated by `scripts/summarize.py` from `results/outcomes.csv`. A cell is one scenario × client × mode × setup; "
      "it meets a clause only if every repetition does. Entries are *cells meeting the clause / cells to which it applies*.", "",
      "## Contract by client and setup", "",
      "| Clause | " + " | ".join(f"{cl.split('-')[0]} · {st}" for cl, st in cols) + " |",
      "|---|" + "---|" * len(cols)]
totals = {k: [0, 0] for k in cols}
for c in CLAUSES:
    line = [f"{c} {CLAUSE_TEXT[c]}"]
    for k in cols:
        app = [x for x in cells if (x["client"], x["setup"]) == k and x[c] != ""]
        met = sum(1 for x in app if x[c] == "yes")
        totals[k][0] += met
        totals[k][1] += len(app)
        line.append(f"{met}/{len(app)}" if app else "–")
    md.append("| " + " | ".join(line) + " |")
md.append("| **all clauses** | " + " | ".join(f"**{totals[k][0]}/{totals[k][1]}**" for k in cols) + " |")

md += ["", "## Cells", "", "| Scen. | Client | Mode | Setup | Result | Exception reaching the application | Guard kind | Attempts | Min gap (ms) | Time (ms) | "
       + " | ".join(CLAUSES) + " |", "|---|---|---|---|---|---|---|---|---|---|" + "---|" * len(CLAUSES)]
for x in cells:
    md.append(f"| {x['scenario']} | {x['client']} | {x['mode']} | {x['setup']} | {x['result']} | {x['error']} | {x['guard_kind']} | {x['attempts']:g} | "
              f"{x['gap_min_ms']} | {x['millis_median']} | " + " | ".join(x[c] for c in CLAUSES) + " |")
(ROOT / "results" / "summary.md").write_text("\n".join(md) + "\n", encoding="utf-8")
print("\n".join(md[:16]))
