#!/usr/bin/env python3
"""gate-pkg 탐지율 측정: 실제 악성 패키지를 얼마나 잡는가.

자료: OSV의 악성 패키지 항목(MAL-, 원자료 OpenSSF malicious-packages, Apache-2.0).
OSV 전체 묶음(npm 약 222MB, PyPI 약 38MB)을 --data-dir(저장소 밖)에 받아 MAL 항목만 고른다.
저장소에는 표본의 이름·OSV ID·결과만 남긴다.

두 가지를 따로 잰다.
  1. 이름만으로: 표본 이름을 철자 흉내 탐지기에만 넣는다(네트워크 없음). OSV에 올라가기 전(0일)에도 잡을 수 있는 비율
  2. 전체 검사: 작은 표본을 실제 gate-pkg check로 (레지스트리·OSV 조회). OSV에 반영된 뒤의 비율. 확인용

사용법:
  ./gradlew :gate-pkg:cliJar
  python3 scripts/measure_detection.py --data-dir /tmp/osv --sample 300 --full-sample 50 \\
      --out docs/measurements/pkg-detection.json
패키지를 설치하거나 실행하지 않는다 (이름·메타데이터만 다룬다).
"""
import argparse
import datetime
import io
import json
import random
import subprocess
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "gate-pkg/build/libs/gate-pkg.jar"
OSV = "https://osv-vulnerabilities.storage.googleapis.com/{eco}/all.zip"
ECOS = {"npm": "npm", "pypi": "PyPI"}
UA = {"User-Agent": "gate-pkg-measurement (+https://github.com/yeomyaloo/GATE)"}


def download(eco, data_dir):
    path = data_dir / f"osv-{eco}-all.zip"
    if not path.exists():
        print(f"downloading OSV {ECOS[eco]} data -> {path}", flush=True)
        req = urllib.request.Request(OSV.format(eco=ECOS[eco]), headers=UA)
        with urllib.request.urlopen(req, timeout=600) as r, open(path, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
    return path


def malicious(zip_path, eco):
    """MAL- 항목에서 패키지 이름 -> OSV ID 목록."""
    names = {}
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            if not info.filename.startswith("MAL-"):
                continue
            entry = json.loads(z.read(info))
            for a in entry.get("affected", []):
                pkg = a.get("package", {})
                if pkg.get("ecosystem", "").lower() == ECOS[eco].lower() and pkg.get("name"):
                    names.setdefault(pkg["name"], []).append(entry["id"])
    return names


def name_only(eco, names):
    proc = subprocess.run(["java", "-cp", str(JAR), str(ROOT / "scripts/TypoNames.java"), eco],
                          input="\n".join(names), capture_output=True, text=True, encoding="utf-8", timeout=600,
                          check=True)
    out = {}
    for line in proc.stdout.splitlines():
        parts = line.split("\t")
        out[parts[0]] = None if parts[1] == "-" else {"target": parts[1], "technique": parts[2]}
    return out


def full_check(eco, names, batch=10):
    results = {}
    for i in range(0, len(names), batch):
        chunk = names[i:i + batch]
        proc = subprocess.run(["java", "-jar", str(JAR), "check", eco, *chunk, "--format", "json"],
                              capture_output=True, text=True, encoding="utf-8", timeout=900,
                              env={**__import__("os").environ, "GATE_USAGE": "off"})
        for p in json.loads(proc.stdout)["packages"]:
            results[p["name"]] = {"decision": p["decision"],
                                  "rules": sorted({f["ruleId"] for f in p.get("findings", [])})}
        print(f"  full check {eco}: {min(i + batch, len(names))}/{len(names)}", flush=True)
    return results


def shape(name):
    if name.startswith("@"):
        return "scoped"
    return "unscoped"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True, type=Path)
    ap.add_argument("--sample", type=int, default=300)
    ap.add_argument("--full-sample", type=int, default=50)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    a.data_dir.mkdir(parents=True, exist_ok=True)

    report = {"measuredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
              "source": "OSV MAL- entries (OpenSSF malicious-packages, Apache-2.0)", "seed": a.seed,
              "ecosystems": {}}
    for eco in ECOS:
        mal = malicious(download(eco, a.data_dir), eco)
        rng = random.Random(a.seed)
        sample = sorted(rng.sample(sorted(mal), min(a.sample, len(mal))))
        typo = name_only(eco, sample)
        caught = [n for n in sample if typo.get(n)]
        full_names = sample[:a.full_sample]
        full = full_check(eco, full_names) if a.full_sample > 0 else {}
        blocked = [n for n in full_names if full.get(n, {}).get("decision") == "BLOCK"]
        warned = [n for n in full_names if full.get(n, {}).get("decision") == "WARN"]
        report["ecosystems"][eco] = {
            "maliciousPackages": len(mal),
            "sample": len(sample),
            "nameOnlyCaught": len(caught),
            "nameOnlyRate": round(len(caught) / len(sample), 4) if sample else 0,
            "nameOnlyByTechnique": dict(Counter(typo[n]["technique"] for n in caught)),
            "missedByShape": dict(Counter(shape(n) for n in sample if not typo.get(n))),
            "fullCheckSample": len(full_names),
            "fullCheckBlocked": len(blocked),
            "fullCheckWarned": len(warned),
            "fullCheckRules": dict(Counter(r for n in full_names for r in full.get(n, {}).get("rules", []))),
            "packages": [{"name": n, "osv": mal[n][:3], "nameOnly": typo.get(n), "full": full.get(n)}
                         for n in sample],
        }
        e = report["ecosystems"][eco]
        print(f"{eco}: {e['maliciousPackages']} malicious packages, name-only {e['nameOnlyCaught']}/{e['sample']}, "
              f"full check BLOCK {e['fullCheckBlocked']} WARN {e['fullCheckWarned']} of {e['fullCheckSample']}",
              flush=True)
    a.out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"raw results: {a.out}")


if __name__ == "__main__":
    main()
