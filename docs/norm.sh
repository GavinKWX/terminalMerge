#!/bin/bash
# Package-normalised divergence between ShareCommerce.Terminal (com.sc.mf919)
# and ShareCommerceTerminalMF919Pro (com.sc.mf919pro).
# Rewrites the package token on both sides, strips whitespace, then compares.
# Usage: bash norm.sh   ->  writes ident/near/oldonly/proonly .txt beside itself
OLD=/c/Work/Code/ShareCommerce.Terminal/app/src/main/java/com/sc/mf919
PRO=/c/Work/Code/ShareCommerceTerminalMF919Pro/app/src/main/java/com/sc/mf919pro
cd "$(dirname "$0")" || exit 1
norm(){ sed -e 's/mf919pro/MFPKG/g' -e 's/mf919/MFPKG/g' "$1" | tr -d '[:space:]'; }
ident=0; oldonly=0; proonly=0
: > ident.txt; : > near.txt; : > oldonly.txt; : > proonly.txt
while IFS= read -r f; do
  rel=${f#"$OLD"/}
  if [ -f "$PRO/$rel" ]; then
    a=$(norm "$f" | md5sum | cut -d' ' -f1); b=$(norm "$PRO/$rel" | md5sum | cut -d' ' -f1)
    if [ "$a" = "$b" ]; then ident=$((ident+1)); echo "$rel" >> ident.txt
    else
      d=$(diff <(sed -e 's/mf919pro/MFPKG/g' -e 's/mf919/MFPKG/g' "$f") \
               <(sed -e 's/mf919pro/MFPKG/g' -e 's/mf919/MFPKG/g' "$PRO/$rel") | grep -c '^[<>]')
      echo "$d/$(wc -l < "$f") $rel" >> near.txt
    fi
  else oldonly=$((oldonly+1)); echo "$rel" >> oldonly.txt; fi
done < <(find "$OLD" -name '*.kt' -o -name '*.java')
while IFS= read -r f; do
  rel=${f#"$PRO"/}
  [ -f "$OLD/$rel" ] || { proonly=$((proonly+1)); echo "$rel" >> proonly.txt; }
done < <(find "$PRO" -name '*.kt' -o -name '*.java')
echo "identical after package rename : $ident"
echo "same name, drifted             : $(wc -l < near.txt)"
echo "old-only                       : $oldonly"
echo "pro-only                       : $proonly"
