#!/bin/bash
# Confronta i simboli esportati dal kernel su misura con quelli dell'originale: ogni simbolo dell'originale deve
# esserci ancora con la stessa impronta (CRC), altrimenti i moduli della ROM non si caricano. Solo allora pubblica lo zip.
# Uso: verifica.sh <cartella con kernel-originale/ e kernel-misura/>
set -euo pipefail
R=$1; QUI=$(cd "$(dirname "$0")" && pwd); . "$QUI/versione.conf"
STATO=$R/stato-verifica.txt
nota() { echo "$*" | tee -a "$STATO"; }
errore() { nota "ERRORE: $*"; exit 1; }
nota "=== verifica | $KERNEL_RELEASE | $(date -u '+%d/%m/%Y %H:%M') UTC ==="
o=$R/kernel-originale/vmlinux.symvers; m=$R/kernel-misura/vmlinux.symvers
[ -s "$o" ] && [ -s "$m" ] || errore "manca una delle due compilazioni (vedi stato-originale.txt e stato-misura.txt)"
cut -f1,2 "$o" | LC_ALL=C sort > "$R/a"; cut -f1,2 "$m" | LC_ALL=C sort > "$R/b"
persi=$(LC_ALL=C comm -23 "$R/a" "$R/b" | wc -l)
nota "simboli esportati: originale $(wc -l < "$R/a"), su misura $(wc -l < "$R/b"); cambiati o spariti: $persi"
if [ "$persi" != 0 ]; then LC_ALL=C comm -23 "$R/a" "$R/b" | head -n 20 >> "$STATO"; errore "il kernel su misura non è compatibile con i moduli della ROM"; fi
[ ! -f "$R/kernel-misura/blocco" ] || errore "$(cat "$R/kernel-misura/blocco")"
cfg="Configurazione identica a quella della Odin."
[ -f "$R/kernel-misura/da-confrontare" ] && { cfg="Configurazione NON ancora confrontata riga per riga con quella della Odin: non installare prima del confronto."; nota "ATTENZIONE: $cfg"; }
z=$(ls "$R"/kernel-misura/*.zip); t=kernel-$KERNEL_RELEASE-$GITHUB_RUN_NUMBER
gh release create "$t" "$z" "$R/kernel-misura/config-misura" --prerelease --title "Kernel Odin 2 Portal $KERNEL_RELEASE (compilazione $GITHUB_RUN_NUMBER)" \
  --notes "Kernel di LineageOS 24.0 ($KERNEL_RELEASE) con futex_waitv e BBR. Vale solo per questa versione esatta del kernel. Compatibilità dei simboli con i moduli della ROM verificata. $cfg NON ancora provato sulla Odin."
nota "pubblicato: $t | $(basename "$z") | sha256 $(sha256sum "$z" | cut -c1-64)"
nota "verifica: OK"
