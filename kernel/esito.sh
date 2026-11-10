#!/bin/bash
# Copia l'esito di una fase in kernel/esiti/ e lo salva nel repository (i commit del robot non fanno ripartire la compilazione).
# Uso: esito.sh <file di stato>
# Ogni tentativo riparte dallo stato attuale del repository e ci mette il file intero di questa fase, senza fusioni con
# l'esito di un'altra compilazione: alla 4 una fusione in conflitto aveva impedito di salvare l'esito della verifica.
f=$1; n=kernel/esiti/$(basename "$f")
git config user.name "github-actions[bot]"; git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
for i in 1 2 3 4 5 6; do
  if git fetch -q origin main && git reset -q --hard FETCH_HEAD; then
    mkdir -p kernel/esiti
    if [ -f "$f" ]; then cp "$f" "$n"; else echo "nessun esito: la fase non è arrivata a scriverlo ($(date -u '+%d/%m %H:%M') UTC)" > "$n"; fi
    git add kernel/esiti; git diff --cached --quiet && exit 0
    git commit -q -m "Kernel: esito $(basename "$f" .txt) (compilazione ${GITHUB_RUN_NUMBER:-?})" && git push -q origin HEAD:main && exit 0
  fi
  sleep $((i * 5))
done
echo "esito non salvato nel repository"; exit 1
