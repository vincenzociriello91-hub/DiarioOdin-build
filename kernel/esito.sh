#!/bin/bash
# Copia l'esito di una fase in kernel/esiti/ e lo salva nel repository (i commit del robot non fanno ripartire la compilazione).
# Uso: esito.sh <file di stato> [altri file da pubblicare accanto, se esistono]
f=$1; mkdir -p kernel/esiti
if [ -f "$f" ]; then cp "$f" kernel/esiti/; else echo "nessun esito: la fase non è arrivata a scriverlo ($(date -u '+%d/%m %H:%M') UTC)" > "kernel/esiti/$(basename "$f")"; fi
shift; for a in "$@"; do [ -f "$a" ] && cp "$a" kernel/esiti/; done
git config user.name "github-actions[bot]"; git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add kernel/esiti; git diff --cached --quiet && exit 0
git commit -q -m "Kernel: esito $(basename "$f" .txt) (compilazione $GITHUB_RUN_NUMBER)"
for i in 1 2 3 4 5 6; do git pull -q --rebase origin main && git push -q && exit 0; sleep $((i * 5)); done
echo "esito non salvato nel repository"; exit 1
