#!/bin/bash
# Robot del kernel: guarda se LineageOS ha pubblicato per la Odin 2 Portal una versione con un kernel diverso
# dall'ultimo compilato qui. Se sì aggiorna kernel/versione.conf (commit nuovo, versione e impronta "auto": le calcola
# la compilazione) e dice al workflow di compilare, scrivendo "compila=true|false" in $GITHUB_OUTPUT. Esito in $1.
# Elenco delle versioni e manifesto arrivano da fuori: sono solo dati, si accettano una data e un commit ben formati.
set -uo pipefail
STATO=$1; QUI=$(cd "$(dirname "$0")" && pwd); CONF=$QUI/versione.conf; . "$CONF"
nota() { echo "$*" | tee -a "$STATO"; }
esce() { echo "compila=$1" >> "${GITHUB_OUTPUT:-/dev/null}"; nota "compila: $1"; exit 0; }
EV=${GITHUB_EVENT_NAME:-push}
nota "=== robot | $(date -u '+%d/%m/%Y %H:%M') UTC | evento $EV | ultimo kernel compilato ${KERNEL_COMMIT:0:12} ==="
# una modifica fatta a mano (push) si compila così com'è: il robot decide solo quando parte da solo o su richiesta
[ "$EV" = push ] && esce true
T=$(mktemp -d); data=""; url=""; rev=""
if curl -fsSL --retry 3 --retry-delay 20 -o "$T/b.json" https://download.lineageos.org/api/v2/devices/odin2portal/builds 2>/dev/null; then
  read -r data url < <(python3 -I -c '
import json, sys
b = max(json.load(open(sys.argv[1])), key=lambda x: x.get("datetime", 0))
u = [f.get("url", "") for f in b.get("files", []) if f.get("filename") == "build-manifest.xml"]
print(b.get("date", "?"), u[0] if u else "?")' "$T/b.json" 2>/dev/null) || true
  case $url in https://mirrorbits.lineageos.org/*)
    curl -fsSL --retry 3 --retry-delay 20 -o "$T/m.xml" "$url" 2>/dev/null &&
      rev=$(grep 'android_kernel_ayn_qcs8550"' "$T/m.xml" | grep -o 'revision="[0-9a-f]\{40\}"' | head -n 1 | cut -d'"' -f2);; esac
fi
[[ $data =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || data=""
if [[ $rev =~ ^[0-9a-f]{40}$ ]]; then nota "ultima LineageOS pubblicata: ${data:-?}, kernel ${rev:0:12} (dal manifesto della versione)"
else
  # elenco o manifesto non leggibili: si guarda la punta del ramo dei sorgenti (può essere più avanti dell'ultima versione pubblicata)
  rev=$(git ls-remote "$KERNEL_REPO" refs/heads/lineage-24.0 2>/dev/null | cut -c1-40); data=""
  nota "manifesto di LineageOS non leggibile: uso la punta del ramo lineage-24.0, kernel ${rev:0:12}"
fi
if ! [[ $rev =~ ^[0-9a-f]{40}$ ]]; then
  nota "ERRORE: non riesco a sapere quale kernel usa LineageOS"; [ "$EV" = schedule ] && esce false; esce true
fi
if [ "$rev" = "$KERNEL_COMMIT" ]; then
  nota "questo kernel è già stato compilato"; [ "$EV" = schedule ] && esce false; esce true
fi
sed -i -e "s/^KERNEL_COMMIT=.*/KERNEL_COMMIT=$rev/" -e "s/^KERNEL_RELEASE=.*/KERNEL_RELEASE=auto/" \
  -e "s/^CONFIG_IMPRONTA=.*/CONFIG_IMPRONTA=auto/" -e '/^LINEAGE_BUILD=/d' "$CONF"
echo "LINEAGE_BUILD=${data:-sconosciuta}" >> "$CONF"
git config user.name "github-actions[bot]"; git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add "$CONF"; git commit -q -m "Kernel: robot, kernel nuovo ${rev:0:12} (LineageOS ${data:-?})"
for i in 1 2 3; do
  git pull -q --rebase origin main && git push -q origin HEAD:main && { nota "versione.conf aggiornato: si compila il kernel nuovo"; esce true; }
  sleep $((i * 5))
done
nota "ERRORE: versione.conf non salvato nel repository"; esce false
