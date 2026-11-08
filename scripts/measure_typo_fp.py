#!/usr/bin/env python3
"""PKG-TYPO 오탐률 측정.

인기 목록 스냅샷(상위 5,000) 바로 바깥 순위(기본 5,001~7,000위)의 패키지는 널리 쓰이는 정상 패키지라고 보고,
그중 무작위 표본을 실제 gate-pkg check(전체 파이프라인, 실제 레지스트리)로 검사해 PKG-TYPO가
경고·차단한 비율을 잰다. 패키지를 설치하거나 실행하지 않는다 (메타데이터 조회만).

사용법:
  ./gradlew :gate-pkg:cliJar
  python3 scripts/measure_typo_fp.py --sample 300 --out docs/measurements/pkg-typo-fp.json
"""
import argparse
import json
import random
import re
import subprocess
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "gate-pkg/build/libs/gate-pkg.jar"
NPM_URL = "https://unpkg.com/npm-high-impact@1.13.0/lib/top-download.js"
PYPI_URL = "https://hugovk.github.io/top-pypi-packages/top-pypi-packages-30-days.min.json"
UA = {"User-Agent": "gate-pkg-measurement (+https://github.com/yeomyaloo/GATE)"}
NPM_NAME = re.compile(r"^(@[a-z0-9~-][a-z0-9._~-]*/)?[a-z0-9~-][a-z0-9._~-]*$")


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read().decode("utf-8")


def npm_ranked():
    names, seen = [], set()
    for m in re.finditer(r"'([^'\n]+)'|\"([^\"\n]+)\"", fetch(NPM_URL)):
        n = (m.group(1) or m.group(2)).lower()
        if NPM_NAME.match(n) and n not in seen:
            seen.add(n)
            names.append(n)
    return names


def pypi_ranked():
    return [re.sub(r"[-_.]+", "-", row["project"]).lower() for row in json.loads(fetch(PYPI_URL))["rows"]]


def check(ecosystem, names, batch):
    results = []
    for i in range(0, len(names), batch):
        chunk = names[i:i + batch]
        proc = subprocess.run(["java", "-jar", str(JAR), "check", ecosystem, *chunk, "--format", "json"],
                              capture_output=True, text=True, encoding="utf-8", timeout=600)
        report = json.loads(proc.stdout)
        results.extend(report["packages"])
        print(f"  {ecosystem}: {min(i + batch, len(names))}/{len(names)}", flush=True)
    return results


def summarize(ecosystem, ranks, packages):
    typo = Counter()
    other_block = Counter()
    unchecked = 0
    flagged = []
    for p in packages:
        rules = {f["ruleId"]: f for f in p["findings"]}
        if "PKG-UNCHECKED" in rules:
            unchecked += 1
        t = rules.get("PKG-TYPO")
        if t:
            typo[t["decision"]] += 1
            flagged.append({"name": p["name"], "decision": t["decision"],
                            "similarTo": t["evidence"].get("similarTo"),
                            "technique": t["evidence"].get("technique"),
                            "downloadShare": t["evidence"].get("downloadShare"),
                            "reason": t["evidence"].get("reason")})
        for f in p["findings"]:
            if f["decision"] == "BLOCK" and f["ruleId"] != "PKG-TYPO":
                other_block[f["ruleId"]] += 1
    n = len(packages)
    return {
        "ecosystem": ecosystem, "ranks": ranks, "sampled": n,
        "typoWarn": typo["WARN"], "typoBlock": typo["BLOCK"],
        "typoWarnRate": round(typo["WARN"] / n, 4) if n else None,
        "typoBlockRate": round(typo["BLOCK"] / n, 4) if n else None,
        "unchecked": unchecked, "otherBlocksByRule": dict(other_block),
        "flagged": sorted(flagged, key=lambda x: (x["decision"], x["name"])),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", type=int, default=5000, help="0-based first rank (5000 = rank 5,001)")
    ap.add_argument("--end", type=int, default=7000)
    ap.add_argument("--sample", type=int, default=300)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--batch", type=int, default=50)
    ap.add_argument("--out", default=str(ROOT / "docs/measurements/pkg-typo-fp.json"))
    args = ap.parse_args()

    rng = random.Random(args.seed)
    results = []
    for ecosystem, ranked in (("npm", npm_ranked()), ("pypi", pypi_ranked())):
        population = ranked[args.start:args.end]
        sample = sorted(rng.sample(population, min(args.sample, len(population))))
        print(f"{ecosystem}: {len(population)} names in ranks {args.start + 1}-{args.end}, sampling {len(sample)}")
        results.append(summarize(ecosystem, f"{args.start + 1}-{args.end}", check(ecosystem, sample, args.batch)))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"seed": args.seed, "results": results}, f, indent=2, ensure_ascii=False)
        f.write("\n")
    for r in results:
        print(f"{r['ecosystem']}: sampled {r['sampled']}, PKG-TYPO warn {r['typoWarn']} "
              f"({r['typoWarnRate']:.1%}), block {r['typoBlock']} ({r['typoBlockRate']:.1%}), "
              f"unchecked {r['unchecked']}")
    print(f"written to {out}")


if __name__ == "__main__":
    main()
