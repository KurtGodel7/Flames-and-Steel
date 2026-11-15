"""Request 69: compares the games of one AI test run (run-ai-test output folder).

Usage: py -I ai-test-compare.py <run folder, e.g. ../ai-test/run1>
"""
import csv, sys, re, collections
from pathlib import Path

run = Path(sys.argv[1])
games = sorted(run.glob("game-*"), key=lambda p: int(p.name.split("-")[1]))
for g in games:
    print("==", g.name)
    inc = list(csv.DictReader(open(g / "income.csv", encoding="utf-8")))
    # round each nation's territory count first reached 0
    gone = {}
    for r in inc:
        if int(r["territories"]) == 0 and r["nation"] not in gone and r["nation"] != "Vichy":
            gone[r["nation"]] = int(r["round"])
    print("  lost all land (round):", gone)
    ref = list(csv.DictReader(open(g / "refused-moves.csv", encoding="utf-8")))
    cats = collections.Counter()
    for r in ref:
        reason = r["reason"]
        if reason.startswith("Aircraft move only"):
            cats["aircraft outside air stages"] += 1
        elif "at peace" in reason:
            cats["nation at peace"] += 1
        else:
            cats[re.sub(r"\d+", "N", reason)[:90]] += 1
    print("  refused:", len(ref), dict(cats.most_common(8)))
    print("  cooperation-related refusals:", sum(1 for r in ref if r["cooperation_territory"]))
    pur = list(csv.DictReader(open(g / "purchases.csv", encoding="utf-8")))
    air_pu = sum(int(r["count"]) * int(r["pu_each"]) for r in pur if r["air"])
    all_pu = sum(int(r["count"]) * int(r["pu_each"]) for r in pur)
    spc = sum(int(r["count"]) for r in pur if r["unit"] == "apc")
    print(f"  PUs spent {all_pu}, on aircraft {air_pu} ({100*air_pu//max(all_pu,1)}%), SPCs bought {spc}")
    air = [r for r in csv.DictReader(open(g / "air.csv", encoding="utf-8")) if r["round"] == "15"]
    tot = sum(int(r["aircraft"]) for r in air); park = sum(int(r["parked_3plus_rounds"]) for r in air)
    print(f"  aircraft at end {tot}, parked 3+ rounds {park}")
    bat = list(csv.DictReader(open(g / "battles.csv", encoding="utf-8")))
    airlost = [r["result"] for r in bat if r["territory"] == "(air phase)" and r["result"] != "no aircraft lost"]
    print("  air-phase rounds with losses:", len(airlost))
    res = list(csv.DictReader(open(g / "research.csv", encoding="utf-8")))
    techs = collections.Counter(r["nation"] for r in res for t in r["techs_gained"].split("; ") if t)
    print("  techs gained by nation:", dict(techs))
    pl = list(csv.DictReader(open(g / "placements.csv", encoding="utf-8")))
    print("  SPC output placements:", sum(1 for r in pl if r["step"].endswith("ApcOutput")))
    steps = list(csv.DictReader(open(g / "steps.csv", encoding="utf-8")))
    slow = sorted(steps, key=lambda r: -float(r["seconds"]))[:3]
    print("  slowest steps:", [(r["round"], r["step"], r["seconds"]) for r in slow])
    print("  errors/warnings:", sum(1 for _ in open(g / "errors.csv", encoding="utf-8")) - 1)
