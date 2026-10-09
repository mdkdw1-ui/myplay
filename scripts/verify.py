#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MyPlayer 프로젝트 사전 검증 스크립트 (v3)"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RED = "\033[91m"; GREEN = "\033[92m"; YELLOW = "\033[93m"
CYAN = "\033[96m"; RESET = "\033[0m"

errors = []
warnings = []


def log_ok(msg):   print(f"{GREEN}✅ {msg}{RESET}")
def log_warn(msg): print(f"{YELLOW}⚠️  {msg}{RESET}"); warnings.append(msg)
def log_err(msg):  print(f"{RED}❌ {msg}{RESET}");    errors.append(msg)
def log_info(msg): print(f"{CYAN}ℹ️  {msg}{RESET}")


def check_required_files(root):
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


def check_kotlin_syntax(root):
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


def check_xml(root):
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
            if r.find("application") is None:
                log_err("AndroidManifest: <application> 태그 없음")
            else:
                log_ok("AndroidManifest 구조 정상")
        except Exception as e:
            log_err(f"AndroidManifest 분석 실패: {e}")


def check_gradle(root):
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
        (r'androidx\.media:media', "androidx.media:media"),
    ]
    for pattern, label in checks:
        if re.search(pattern, text):
            log_ok(f"Gradle: {label} 확인")
        else:
            log_warn(f"Gradle: {label} 누락?")


def check_workflow(root):
    print(f"\n{CYAN}[5/6] GitHub Actions 확인{RESET}")
    wf = root / ".github/workflows/build.yml"
    if not wf.exists():
        log_warn(".github/workflows/build.yml 없음")
        return
    log_ok(".github/workflows/build.yml 존재")


def check_secrets(root):
    print(f"\n{CYAN}[6/6] 시크릿 유출 검사{RESET}")

    PUBLIC_KEYS = {
        "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
    }

    patterns = [
        (r'AIza[0-9A-Za-z_\-]{35}', "Google API Key"),
        (r'gsk_[0-9A-Za-z]{40,}',   "Groq API Key"),
        (r'sk-[0-9A-Za-z]{40,}',    "OpenAI Key"),
    ]
    hits = 0
    src = root / "app/src"
    if not src.exists():
        return

    for f in src.rglob("*"):
        if not f.is_file() or f.suffix not in {".kt", ".xml", ".properties"}:
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for pat, name in patterns:
            for m in re.finditer(pat, text):
                key = m.group(0)
                if key in PUBLIC_KEYS:
                    continue
                log_err(f"{f.relative_to(root)}: {name} 하드코딩 의심! (…{key[-8:]})")
                hits += 1

    if hits == 0:
        log_ok("실제 시크릿 노출 없음 (YouTube 공개 키 제외)")


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    print(f"{CYAN}═══ MyPlayer 검증 시작 ═══{RESET}")
    print(f"루트: {root}")
    if not (root / "app").exists():
        log_err("'app' 디렉터리 없음")
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
        for e in errors:
            print(f"  • {e}")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
