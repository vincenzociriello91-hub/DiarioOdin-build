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
  command -v termux-fastboot >/dev/null 2>&1 || curl -fsSL https://raw.githubusercontent.com/nohajc/termux-adb/master/install.sh | bash
  command -v termux-fastboot >/dev/null 2>&1 || { echo "ERRORE: termux-adb non installato"; exit 1; }
  curl -fsSL -o root_odin2portal.py.nuovo "$U/root_odin2portal.py" && mv -f root_odin2portal.py.nuovo root_odin2portal.py
  [ -s root_odin2portal.py ] || { echo "ERRORE: programma non scaricato (controlla la connessione)"; exit 1; }
  echo "Quando il telefono chiede il permesso per il dispositivo USB (Termux:API), tocca OK ogni volta."
  python -u root_odin2portal.py "--$1"
  echo "=== fine: codice $? ==="
} 2>&1 | tee "$L"
termux-adb push "$L" /sdcard/Download/Odin2_report/telefono_esito.txt >/dev/null 2>&1 && echo "Esito copiato sulla console (Odin2_report/telefono_esito.txt)."
