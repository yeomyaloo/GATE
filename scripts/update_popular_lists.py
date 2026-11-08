#!/usr/bin/env python3
"""인기 패키지 목록 스냅샷 갱신 (PKG-TYPO 비교 기준).

데이터 파일만 내려받아 이름만 뽑는다. 패키지를 설치하거나 내려받은 코드를 실행하지 않는다.
  - npm : npm-high-impact (MIT, wooorm) 의 lib/top-download.js. 버전을 고정해서 받는다.
          JS 파일이지만 실행하지 않고 따옴표 안의 이름만 정규식으로 읽는다.
  - PyPI: hugovk/top-pypi-packages 30일 다운로드 상위 목록 (JSON)

사용법: python3 scripts/update_popular_lists.py [--limit 5000]
"""
import argparse
import datetime
import json
import pathlib
import re
import urllib.request

NPM_HIGH_IMPACT_VERSION = "1.13.0"
NPM_URL = f"https://unpkg.com/npm-high-impact@{NPM_HIGH_IMPACT_VERSION}/lib/top-download.js"
PYPI_URL = "https://hugovk.github.io/top-pypi-packages/top-pypi-packages-30-days.min.json"
OUT = pathlib.Path(__file__).resolve().parent.parent / "gate-pkg/src/main/resources/popular"
UA = {"User-Agent": "gate-pkg-popular-list-updater (+https://github.com/yeomyaloo/GATE)"}

NPM_NAME = re.compile(r"^(@[a-z0-9~-][a-z0-9._~-]*/)?[a-z0-9~-][a-z0-9._~-]*$")
PYPI_NAME = re.compile(r"^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")


def fetch(url: str) -> str:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read().decode("utf-8")


def write(name: str, source: str, names: list[str]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    today = datetime.date.today().isoformat()
    header = [
        f"# Popular {name} packages (top {len(names)}), used as the PKG-TYPO reference list.",
        f"# Source: {source}",
        f"# Generated: {today} by scripts/update_popular_lists.py",
    ]
    # Windows에서도 LF로 쓴다 (저장소 규칙: .gitattributes eol=lf)
    with open(OUT / f"{name}.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(header + names) + "\n")
    print(f"{name}: {len(names)} names -> {OUT / (name + '.txt')}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=5000)
    args = parser.parse_args()

    npm_names, seen = [], set()
    for match in re.finditer(r"'([^'\n]+)'|\"([^\"\n]+)\"", fetch(NPM_URL)):
        candidate = (match.group(1) or match.group(2)).lower()
        if NPM_NAME.match(candidate) and candidate not in seen:
            seen.add(candidate)
            npm_names.append(candidate)
        if len(npm_names) >= args.limit:
            break
    write("npm", f"npm-high-impact@{NPM_HIGH_IMPACT_VERSION} lib/top-download.js (MIT)", npm_names)

    data = json.loads(fetch(PYPI_URL))
    pypi_names = []
    for row in data["rows"][: args.limit]:
        normalized = re.sub(r"[-_.]+", "-", row["project"]).lower()
        if PYPI_NAME.match(normalized):
            pypi_names.append(normalized)
    write("pypi", f"hugovk/top-pypi-packages 30-day downloads (last_update {data.get('last_update')})", pypi_names)


if __name__ == "__main__":
    main()
