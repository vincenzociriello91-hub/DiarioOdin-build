#!/data/data/com.termux/files/usr/bin/bash
# Odin 2 Portal senza PC: il telefono fa da PC, dentro Termux, con un cavo USB-C tra telefono e console.
# Uso (in Termux):  bash telefono.sh kernel            avvio di prova del kernel su misura, poi installazione se confermi
#                   bash telefono.sh ripristina-kernel rimette il kernel originale
# Servono le app Termux e Termux:API (tutte e due da F-Droid). Non serve il root sul telefono.
# Tutto quello che compare viene salvato e, a console accesa, copiato in Odin2_report/telefono_esito.txt (va su Drive).
set -u
case ${1:-} in kernel|ripristina-kernel) ;; *) echo "Uso: bash telefono.sh kernel | ripristina-kernel"; exit 1;; esac
D=$HOME/odin; L=$D/telefono_esito.txt; mkdir -p "$D"; cd "$D" || exit 1
U=https://raw.githubusercontent.com/vincenzociriello91-hub/DiarioOdin-build/main/telefono
{
  echo "=== telefono.sh $1 | $(date '+%d/%m/%Y %H:%M:%S') | $(getprop ro.product.model) Android $(getprop ro.build.version.release) ==="
  command -v python >/dev/null 2>&1 && command -v termux-usb >/dev/null 2>&1 || pkg install -y python termux-api
  # termux-adb / termux-fastboot (github.com/nohajc/termux-adb): adb e fastboot che usano la porta USB del telefono senza root
  # scaricato in un file e poi eseguito: così, se l'installazione fa una domanda, la tastiera resta collegata
  command -v termux-fastboot >/dev/null 2>&1 || { curl -fsSL -o installa-termux-adb.sh https://raw.githubusercontent.com/nohajc/termux-adb/master/install.sh && bash installa-termux-adb.sh; }
  command -v termux-fastboot >/dev/null 2>&1 || { echo "ERRORE: termux-adb non installato"; exit 1; }
  curl -fsSL -o root_odin2portal.py.nuovo "$U/root_odin2portal.py?$(date +%s)" && mv -f root_odin2portal.py.nuovo root_odin2portal.py
  [ -s root_odin2portal.py ] || { echo "ERRORE: programma non scaricato (controlla la connessione)"; exit 1; }
  echo "Quando il telefono chiede il permesso per il dispositivo USB (Termux:API), tocca OK ogni volta."
  echo "Se la console è accesa ma non viene trovata: sul telefono apri la notifica USB e scegli 'USB controllata da: Questo dispositivo'."
  python -u root_odin2portal.py "--$1"
  echo "=== fine: codice $? ==="
} 2>&1 | tee "$L"
# la console può essere in riavvio: si aspetta che torni, poi si copiano l'esito e il rapporto del kernel di prova
echo "Aspetto la console per copiare l'esito (fino a 5 minuti)..."
timeout 300 termux-adb wait-for-device >/dev/null 2>&1
for f in "$L" "$D/odin2portal_root/kernel/kernel_info_prova.txt"; do [ -f "$f" ] && termux-adb push "$f" /sdcard/Download/Odin2_report/ >/dev/null 2>&1 && echo "Copiato sulla console: $(basename "$f")"; done
