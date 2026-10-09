#!/data/data/com.termux/files/usr/bin/bash
# MyPlayer 버그 스캔
cd ~/myplayer

G="\033[92m"; R="\033[91m"; Y="\033[93m"; C="\033[96m"; N="\033[0m"
SRC="app/src/main/java/com/example/myplayer"

echo -e "${C}═══════════════════════════════════════════${N}"
echo -e "${C}   MyPlayer  ─  코드 버그 스캔${N}"
echo -e "${C}═══════════════════════════════════════════${N}"

# ─────────────────────────────────────────
# 1. GlobalScope 사용 (메모리 누수 위험)
# ─────────────────────────────────────────
echo -e "\n${C}[1] GlobalScope 사용 (deprecated/누수)${N}"
GS=$(grep -rn "GlobalScope" $SRC 2>/dev/null)
if [ -z "$GS" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$GS" | while IFS=: read f line rest; do
        echo -e "  ${Y}⚠️${N}  ${f##*/}:$line"
    done
fi

# ─────────────────────────────────────────
# 2. !! (not-null assertion)
# ─────────────────────────────────────────
echo -e "\n${C}[2] !! (NPE 위험)${N}"
BANG=$(grep -rn '!!' $SRC 2>/dev/null | grep -v '!=' | grep -v '//' | head -20)
CNT=$(echo "$BANG" | grep -c '!!' 2>/dev/null || echo 0)
if [ "$CNT" -eq 0 ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo -e "  ${Y}⚠️${N}  ${CNT}건"
    echo "$BANG" | head -8 | while IFS=: read f line rest; do
        echo -e "     ${f##*/}:$line"
    done
fi

# ─────────────────────────────────────────
# 3. runBlocking (ANR 위험)
# ─────────────────────────────────────────
echo -e "\n${C}[3] runBlocking 사용 (ANR 위험)${N}"
RB=$(grep -rn "runBlocking" $SRC 2>/dev/null)
if [ -z "$RB" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$RB" | while IFS=: read f line rest; do
        echo -e "  ${R}❌${N} ${f##*/}:$line"
    done
fi

# ─────────────────────────────────────────
# 4. Thread.sleep (UI 블로킹)
# ─────────────────────────────────────────
echo -e "\n${C}[4] Thread.sleep${N}"
TS=$(grep -rn "Thread.sleep" $SRC 2>/dev/null)
if [ -z "$TS" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$TS" | while IFS=: read f line rest; do
        echo -e "  ${Y}⚠️${N}  ${f##*/}:$line"
    done
fi

# ─────────────────────────────────────────
# 5. Log import 누락 (빌드 실패 원인)
# ─────────────────────────────────────────
echo -e "\n${C}[5] Log import 누락${N}"
NOLOG=""
for f in $(find $SRC -name "*.kt" 2>/dev/null); do
    if grep -q "Log\.\(d\|e\|w\|i\|v\)(" "$f" 2>/dev/null; then
        if ! grep -q "^import android.util.Log" "$f"; then
            NOLOG="$NOLOG\n  ❌ ${f##*/}"
        fi
    fi
done
if [ -z "$NOLOG" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo -e "$NOLOG"
fi

# ─────────────────────────────────────────
# 6. while(true) 무한 루프
# ─────────────────────────────────────────
echo -e "\n${C}[6] while(true) 무한 루프${N}"
WT=$(grep -rn "while\s*(\s*true\s*)" $SRC 2>/dev/null)
if [ -z "$WT" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$WT" | while IFS=: read f line rest; do
        echo -e "  ${Y}⚠️${N}  ${f##*/}:$line"
    done
fi

# ─────────────────────────────────────────
# 7. 하드코딩 IP
# ─────────────────────────────────────────
echo -e "\n${C}[7] 하드코딩 IP 주소${N}"
IP=$(grep -rnE '"[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}"' $SRC 2>/dev/null | grep -v "127.0.0.1")
if [ -z "$IP" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$IP" | head -5
fi

# ─────────────────────────────────────────
# 8. TODO/FIXME
# ─────────────────────────────────────────
echo -e "\n${C}[8] TODO/FIXME${N}"
TODO=$(grep -rn "TODO\|FIXME\|XXX" $SRC 2>/dev/null)
if [ -z "$TODO" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$TODO" | while IFS=: read f line rest; do
        echo -e "  ${Y}📝${N} ${f##*/}:$line"
    done
fi

# ─────────────────────────────────────────
# 9. 빈 catch 블록 (예외 삼킴)
# ─────────────────────────────────────────
echo -e "\n${C}[9] 빈 catch 블록 (예외 삼킴)${N}"
EMPTY=$(grep -rn "catch\s*(\s*[a-zA-Z_][a-zA-Z0-9_]*:\s*Exception\s*)\s*{\s*}" $SRC 2>/dev/null)
CNT2=$(echo "$EMPTY" | grep -c "catch" 2>/dev/null || echo 0)
if [ "$CNT2" -eq 0 ]; then
    echo -e "  ${G}✅${N} 없음 (모두 로그 또는 주석 처리)"
else
    echo -e "  ${Y}⚠️${N}  ${CNT2}건 (의도된 것은 무시)"
    echo "$EMPTY" | head -5
fi

# ─────────────────────────────────────────
# 10. 너무 큰 파일 (800줄 초과)
# ─────────────────────────────────────────
echo -e "\n${C}[10] 큰 파일 (800줄 초과)${N}"
LARGE=$(find $SRC -name "*.kt" -exec wc -l {} \; 2>/dev/null | awk '$1 > 800' | sort -rn)
if [ -z "$LARGE" ]; then
    echo -e "  ${G}✅${N} 없음"
else
    echo "$LARGE" | while read lines file; do
        echo -e "  ${Y}📦${N} $lines 줄: ${file##*/}"
    done
fi

# ─────────────────────────────────────────
# 11. 사용 안 하는 의심 함수 (휴리스틱)
# ─────────────────────────────────────────
echo -e "\n${C}[11] 사용 안 하는 private 함수 (휴리스틱)${N}"
UNUSED=""
for f in $SRC/*.kt; do
    grep -oE "private fun [a-zA-Z_][a-zA-Z0-9_]*" "$f" 2>/dev/null | \
        sed 's/private fun //' | while read fn; do
        cnt=$(grep -rn "\.$fn\b\|  $fn(" $SRC 2>/dev/null | grep -v "private fun $fn" | wc -l)
        if [ "$cnt" -eq 0 ]; then
            echo "  ${f##*/}::$fn"
        fi
    done
done | head -20

# ─────────────────────────────────────────
# 12. 하드코딩 API 키 (재확인)
# ─────────────────────────────────────────
echo -e "\n${C}[12] 실제 시크릿 노출${N}"
GROQ=$(grep -rl 'gsk_[0-9A-Za-z]\{40,\}' $SRC 2>/dev/null)
if [ -z "$GROQ" ]; then
    echo -e "  ${G}✅${N} Groq 키 없음"
else
    echo -e "  ${R}❌${N} $GROQ"
fi

echo -e "\n${C}═══════════════════════════════════════════${N}"
echo -e "${C}   스캔 완료${N}"
echo -e "${C}═══════════════════════════════════════════${N}"