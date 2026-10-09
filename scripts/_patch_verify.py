
#!/usr/bin/env python3
# verify.py의 check_secrets 함수만 교체하는 패치 스크립트
import re
from pathlib import Path

vf = Path.home() / "myplayer/scripts/verify.py"
text = vf.read_text(encoding="utf-8")

# 기존 check_secrets 함수 전체를 찾음
pattern = re.compile(
    r'def check_secrets\(root\):.*?(?=\ndef |\Z)',
    re.DOTALL
)

new_func = '''def check_secrets(root):
    print(f"\\n{CYAN}[6/6] 시크릿 유출 검사{RESET}")

    # ★ YouTube 공개 InnerTube 키 (허용)
    PUBLIC_KEYS = {
        "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
    }

    patterns = [
        (r'AIza[0-9A-Za-z_\\-]{35}', "Google API Key"),
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
'''

if pattern.search(text):
    text = pattern.sub(new_func, text, count=1)
    vf.write_text(text, encoding="utf-8")
    print("✅ verify.py check_secrets 함수 교체 완료")
else:
    print("❌ check_secrets 함수를 찾지 못했습니다")
    exit(1)