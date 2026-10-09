#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MyPlayer 프로젝트 사전 검증 스크립트"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RED = "\033[91m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
RESET = "\033[0m"

errors = []
warnings = []


def log_ok(msg):   print(f"{GREEN}✅ {msg}{RESET}")
def log_warn(msg): print(f"{YELLOW}⚠️  {msg}{RESET}"); warnings.append(msg)
def log_err(msg):  print(f"{RED}❌ {msg}{RESET}");    errors.append(msg)
def log_info(msg): print(f"{CYAN}ℹ️  {msg}{RESET}")


def check_required_files(root: Path):
    print(f"\n{CYAN}[1/6] 필수 파일 확인{RESET}")
    required = [
        "app/build.gradle.kts",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "app/src/main/AndroidManifest.xml",
    ]
    for f in required:
        if (root / f).exists():
            log_ok(f)
        else:
            log_err(f"누락: {f}")


def check_kotlin_syntax(root: Path):
    print(f"\n{CYAN}[2/6] Kotlin 문법 검사{RESET}")
    kt_dir = root / "app/src/main/java"
    if not kt_dir.exists():
        log_err(f"Kotlin 폴더 없음: {kt_dir}")
        return
    kt_files = list(kt_dir.rglob("*.kt"))
    log_info(f"Kotlin 파일 {len(kt_files)}개 발견")

    for f in kt_files:
        try:
            text = f.read_text(encoding="utf-8")
        except Exception as e:
            log_err(f"{f.name} 읽기 실패: {e}")
            continue

        for open_c, close_c, name in [("{", "}", "중괄호"),
                                       ("(", ")", "괄호"),
                                       ("[", "]", "대괄호")]:
            cleaned = re.sub(r'"[^"\\]*(?:\\.[^"\\]*)*"', '""', text)
            cleaned = re.sub(r'//.*', '', cleaned)
            cleaned = re.sub(r'/\*.*?\*/', '', cleaned, flags=re.DOTALL)
            if cleaned.count(open_c) != cleaned.count(close_c):
                log_err(f"{f.name}: {name} 불균형 "
                        f"({open_c}={cleaned.count(open_c)}, {close_c}={cleaned.count(close_c)})")

        if not re.search(r'^\s*package\s+[\w.]+', text, re.MULTILINE):
            log_warn(f"{f.name}: package 선언 없음")

        if len(text.strip()) < 50:
            log_warn(f"{f.name}: 파일이 너무 짧음 ({len(text)} bytes)")


def check_xml(root: Path):
    print(f"\n{CYAN}[3/6] XML 파싱 검증{RESET}")
    res_dir = root / "app/src/main/res"
    xml_files = list(res_dir.rglob("*.xml")) if res_dir.exists() else []
    manifest = root / "app/src/main/AndroidManifest.xml"
    if manifest.exists():
        xml_files.append(manifest)
    log_info(f"XML 파일 {len(xml_files)}개 발견")

    for f in xml_files:
        try:
            ET.parse(str(f))
        except ET.ParseError as e:
            log_err(f"{f.name}: XML 파싱 실패 - {e}")

    if manifest.exists():
        try:
            tree = ET.parse(str(manifest))
            r = tree.getroot()
            ns = "{http://schemas.android.com/apk/res/android}"
            perms = [p.get(f"{ns}name", "") for p in r.findall("uses-permission")]
            if not any("INTERNET" in p for p in perms):
                log_err("AndroidManifest: INTERNET 권한 누락")
            app = r.find("application")
            if app is None:
                log_err("AndroidManifest: <application> 태그 없음")
            else:
                log_ok("AndroidManifest 구조 정상")
        except Exception as e:
            log_err(f"AndroidManifest 분석 실패: {e}")


def check_gradle(root: Path):
    print(f"\n{CYAN}[4/6] Gradle 설정 검사{RESET}")
    app_gradle = root / "app/build.gradle.kts"
    if not app_gradle.exists():
        log_err("app/build.gradle.kts 없음")
        return

    text = app_gradle.read_text(encoding="utf-8")
    checks = [
        (r'namespace\s*=',      "namespace 선언"),
        (r'compileSdk\s*=',     "compileSdk"),
        (r'minSdk\s*=',         "minSdk"),
        (r'targetSdk\s*=',      "targetSdk"),
        (r'buildConfigField',   "buildConfigField"),
        (r'buildFeatures\s*\{', "buildFeatures"),
    ]
    for pattern, label in checks:
        if re.search(pattern, text):
            log_ok(f"Gradle: {label} 확인")
        else:
            log_warn(f"Gradle: {label} 누락?")

    m = re.search(r'minSdk\s*=\s*(\d+)', text)
    if m and int(m.group(1)) < 21:
        log_err(f"minSdk {m.group(1)}은 너무 낮음 (21 이상 권장)")


def check_workflow(root: Path):
    print(f"\n{CYAN}[5/6] GitHub Actions 확인{RESET}")
    wf = root / ".github/workflows/build.yml"
    if not wf.exists():
        log_warn(".github/workflows/build.yml 없음 (빌드 자동화 불가)")
        return
    text = wf.read_text(encoding="utf-8")
    for key in ["on:", "jobs:", "runs-on:", "actions/checkout"]:
        if key in text:
            log_ok(f"Workflow: {key} 포함")
        else:
            log_warn(f"Workflow: {key} 누락?")


def check_secrets(root: Path):
    print(f"\n{CYAN}[6/6] 시크릿 유출 검사{RESET}")
    patterns = [
        (r'AIza[0-9A-Za-z_\-]{35}', "Google API Key"),
        (r'gsk_[0-9A-Za-z]{40,}',   "Groq API Key"),
        (r'sk-[0-9A-Za-z]{40,}',    "OpenAI Key"),
    ]
    hits = 0
    src = root / "app/src"
    if not src.exists():
        log_warn("app/src 없음")
        return
    for f in src.rglob("*"):
        if not f.is_file() or f.suffix not in {".kt", ".xml", ".properties"}:
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for pat, name in patterns:
            if re.search(pat, text):
                log_err(f"{f.relative_to(root)}: {name} 하드코딩 의심!")
                hits += 1
    if hits == 0:
        log_ok("하드코딩된 API 키 없음")


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    print(f"{CYAN}═══ MyPlayer 검증 시작 ═══{RESET}")
    print(f"루트: {root}")

    if not (root / "app").exists():
        log_err(f"'app' 디렉터리 없음. 잘못된 경로? ({root})")
        sys.exit(1)

    check_required_files(root)
    check_kotlin_syntax(root)
    check_xml(root)
    check_gradle(root)
    check_workflow(root)
    check_secrets(root)

    print(f"\n{CYAN}═══ 결과 ═══{RESET}")
    print(f"{GREEN}통과: {len(errors) == 0}{RESET}")
    print(f"{YELLOW}경고: {len(warnings)}개{RESET}")
    print(f"{RED}오류: {len(errors)}개{RESET}")

    if errors:
        print(f"\n{RED}── 오류 목록 ──{RESET}")
        for e in errors:
            print(f"  • {e}")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
