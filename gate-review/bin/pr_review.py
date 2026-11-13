#!/usr/bin/env python3
"""GATE gate-review: PR 리뷰 댓글과 Job Summary를 만든다 (표준 라이브러리만 사용).

    pr_review.py REVIEW_JSON --base SHA --head SHA [--repo owner/name --pr N]

- 지적이 PR에서 바뀐 줄에 있으면 그 줄에 댓글, 아니면 리뷰 본문에 목록으로 남긴다.
- --repo/--pr가 없거나 GITHUB_TOKEN이 없으면 댓글 없이 요약만 쓴다 (포크 PR 등).
- 검토 결과의 글(AI가 쓴 제목·근거 포함)은 신뢰하지 않는 입력으로 다룬다: 멘션·HTML을 무력화하고 길이를 자른다.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

MAX_TEXT = 600
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def safe(text, limit=MAX_TEXT):
    text = str(text or "").replace("\r", " ")
    text = text.replace("@", "@​").replace("<", "&lt;").replace(">", "&gt;")
    return text if len(text) <= limit else text[:limit] + "…"


def code(text):
    """한 줄 코드 표시. 코드 칸 안에서는 HTML·멘션이 동작하지 않으므로 줄바꿈·길이만 정리한다.
    백틱이 들어 있으면 감싸는 백틱을 늘린다."""
    text = str(text or "").replace("\r", " ").replace("\n", " ")
    text = text if len(text) <= 300 else text[:300] + "…"
    fence = "``" if "`" in text else "`"
    return f"{fence} {text} {fence}"


def changed_lines(base, head, path):
    """head 쪽에서 추가·변경된 줄 번호 (PR 리뷰 댓글은 diff 안의 줄에만 달 수 있다)."""
    out = subprocess.run(["git", "diff", "-U0", base, head, "--", path], capture_output=True, encoding="utf-8", errors="replace",
                         check=False).stdout
    lines = set()
    for row in out.splitlines():
        m = HUNK.match(row)
        if m:
            start, count = int(m.group(1)), int(m.group(2) or "1")
            lines.update(range(start, start + count))
    return lines


def finding_body(f):
    ev = f.get("evidence") or {}
    parts = [f"**GATE gate-review · {safe(f['decision'])} · {safe(f['source'])} · {safe(f['severity'])}**",
             "", safe(f["title"]), ""]
    if "rule" in ev:
        parts.append(f"- Rule: `{safe(ev['rule'], 120)}` ({safe(ev.get('engine', ''), 60)})"
                     + (f", {safe(ev['cwe'], 20)}" if ev.get("cwe") else ""))
        if ev.get("code"):
            parts.append(f"- Code: {code(ev['code'])}")
    ai = ev.get("ai") if isinstance(ev.get("ai"), dict) else (ev if f["source"] == "AI" else None)
    if ai:
        parts.append(f"- AI ({safe(ai.get('model', ''), 60)}, confidence {safe(ai.get('confidence', ''), 20)})"
                     + (f", {safe(ai['cwe'], 20)}" if ai.get("cwe") else "") + f": {safe(ai.get('evidence', ''))}")
        if ai.get("quotedCode"):
            parts.append(f"- Quoted code (verified): {code(ai['quotedCode'])}")
    if f["source"] == "AI":
        parts.append("")
        parts.append("_AI-only finding: shown as a warning. GATE never blocks on AI judgement alone._")
    return "\n".join(parts)


def summary(report, inline, listed):
    ai = report.get("ai") or {}
    status = ai.get("status")
    if status == "OK":
        ai_line = f"{safe(ai.get('model'), 60)} ({ai.get('inputTokens')} in / {ai.get('outputTokens')} out tokens)"
    else:
        ai_line = f"not used ({safe(status, 20)}: {safe(ai.get('detail'), 200)})"
    rows = [f"## GATE gate-review: {safe(report['decision'], 10)}", "",
            f"- Static analysis: {safe(report.get('staticEngine'), 60)}",
            f"- AI review: {ai_line}",
            f"- Findings: {len(report['findings'])} ({len(inline)} as line comments)"]
    if report.get("rejectedAiFindings"):
        rows.append(f"- Discarded AI findings (quoted code not found in the file): {len(report['rejectedAiFindings'])}")
    for e in report.get("staticErrors") or []:
        rows.append(f"- Static analysis error: {safe(e, 200)}")
    if report["findings"]:
        rows += ["", "| Decision | Source | Severity | Location | Finding |", "|---|---|---|---|---|"]
        for f in report["findings"]:
            title = safe(f["title"], 160).replace("|", "\\|")
            rows.append(f"| {safe(f['decision'])} | {safe(f['source'])} | {safe(f['severity'])} | "
                        f"`{safe(f['file'], 160)}:{f['line']}` | {title} |")
    if listed:
        rows += ["", "Findings outside the changed lines (no line comment possible):", ""]
        rows += [f"- `{safe(f['file'], 160)}:{f['line']}` {safe(f['title'], 160)}" for f in listed]
    rows += ["", "BLOCK requires a static rule (alone, or confirmed by the AI at the same place). "
             "AI-only findings are warnings."]
    return "\n".join(rows)


def post_review(repo, pr, head, body, comments, token):
    url = f"https://api.github.com/repos/{repo}/pulls/{pr}/reviews"
    payload = {"commit_id": head, "event": "COMMENT", "body": body, "comments": comments}
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "gate-review"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.status


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser()
    p.add_argument("report")
    p.add_argument("--base", required=True)
    p.add_argument("--head", required=True)
    p.add_argument("--repo")
    p.add_argument("--pr")
    a = p.parse_args()
    if a.repo and not re.fullmatch(r"[\w.-]+/[\w.-]+", a.repo) or a.pr and not a.pr.isdigit():
        sys.exit("invalid --repo or --pr")

    with open(a.report, encoding="utf-8") as fh:
        report = json.load(fh)
    inline, listed, cache = [], [], {}
    for f in report["findings"]:
        if f["file"] not in cache:
            cache[f["file"]] = changed_lines(a.base, a.head, f["file"])
        (inline if f["line"] in cache[f["file"]] else listed).append(f)
    body = summary(report, inline, listed)

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(body + "\n")
    print(body)

    token = os.environ.get("GITHUB_TOKEN")
    if not (a.repo and a.pr and token):
        print("no PR context or token: summary only")
        return
    if not report["findings"]:
        print("no findings: no review posted")
        return
    comments = [{"path": f["file"], "line": f["line"], "side": "RIGHT", "body": finding_body(f)} for f in inline]
    try:
        post_review(a.repo, a.pr, a.head, body, comments, token)
    except urllib.error.HTTPError as e:
        if e.code != 422 or not comments:
            raise
        # 줄 계산이 GitHub과 어긋나면 줄 댓글 없이 본문만 남긴다
        print("line comments rejected (422): posting the summary only")
        post_review(a.repo, a.pr, a.head, summary(report, [], inline + listed), [], token)
    print(f"review posted: {len(comments)} line comment(s)")


if __name__ == "__main__":
    main()
