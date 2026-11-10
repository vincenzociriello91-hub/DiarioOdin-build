#!/bin/bash
# Compila il kernel della Odin 2 Portal su GitHub Actions.
#   originale = identico a quello di LineageOS: è il riferimento per i simboli che i moduli della ROM si aspettano
#   misura    = originale + futex_waitv + BBR, senza MODULE_SIG_PROTECT (vedi sotto)
# Uso: compila.sh originale|misura prepara|costruisci      Esito, anche in caso di errore, in $RIS/stato-<variante>.txt
set -euo pipefail
V=$1; FASE=$2; QUI=$(cd "$(dirname "$0")" && pwd); . "$QUI/versione.conf"
W=${W:-$HOME/kernel}; SRC=$W/src; OUT=$W/out; RIS=$W/risultati; STATO=$RIS/stato-$V.txt
mkdir -p "$RIS"
nota() { echo "$*" | tee -a "$STATO"; }
errore() { nota "ERRORE: $*"; exit 1; }
export PATH=$W/clang/bin:$PATH
# HOSTCC=gcc: gli strumenti di compilazione girano sulla macchina di GitHub, non entrano nel kernel
MK=(make -C "$SRC" O="$OUT" ARCH=arm64 LLVM=1 LLVM_IAS=1 HOSTCC=gcc HOSTCXX=g++ -j"$(nproc)")
impronta() { grep '^CONFIG_' "$1" | LC_ALL=C sort | sha256sum | cut -c1-64; }

prepara() {
  nota "=== kernel $V | $KERNEL_RELEASE | $(date -u '+%d/%m/%Y %H:%M') UTC | fase prepara ==="
  # --- compilatore: lo stesso di LineageOS, altrimenti configurazione e controlli CFI non coincidono
  mkdir -p "$W/clang"
  # La pagina del repository AOSP indica clang-r596125 nel ramo mirror-goog-main-llvm-toolchain-source. La cartella
  # viene ricompilata nel tempo con un numero di build diverso: si cerca nella sua storia la build usata da LineageOS.
  G=https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86
  ref=refs/heads/mirror-goog-main-llvm-toolchain-source; punto=$ref
  storia=$(curl -sSL --retry 3 --retry-delay 20 "$G/+log/$ref/$CLANG?format=JSON&n=40" | tail -n +2 | python3 -c 'import json,sys; print(" ".join(x["commit"] for x in json.load(sys.stdin)["log"]))' 2>/dev/null || true)
  nota "storia della cartella $CLANG: $(echo $storia | wc -w) commit"
  for c in $storia; do
    nomi=$(curl -sSL --retry 3 --retry-delay 20 "$G/+/$c/$CLANG/?format=JSON" | tail -n +2 | python3 -c 'import json,sys; print(" ".join(e["name"] for e in json.load(sys.stdin)["entries"]))' 2>/dev/null || true)
    nota "  ${c:0:12}: $(echo "$nomi" | tr ' ' '\n' | grep -i -E 'manifest|version' | tr '\n' ' ')"
    case " $nomi " in *" manifest_$CLANG_BUILD.xml "*) punto=$c; nota "  build $CLANG_BUILD trovata in ${c:0:12}"; break;; esac
  done
  for da in "$punto" "$ref"; do
    u="$G/+archive/$da/$CLANG.tar.gz"
    cod=$(curl -sSL --retry 3 --retry-delay 30 -o "$W/clang.tgz" -w '%{http_code}' "$u" || true)
    if [ "$cod" = 200 ] && tar -xzf "$W/clang.tgz" -C "$W/clang" 2>/dev/null && [ -x "$W/clang/bin/clang" ]; then nota "compilatore da ${da:0:60}"; break; fi
    nota "compilatore non scaricato da ${da:0:60} (risposta $cod, $(stat -c %s "$W/clang.tgz" 2>/dev/null || echo 0) byte)"
    [ "$punto" = "$ref" ] && break
  done
  rm -f "$W/clang.tgz"
  [ -x "$W/clang/bin/clang" ] || errore "compilatore $CLANG non scaricabile"
  cv=$(clang --version | head -n 1); nota "compilatore: $cv"
  case $cv in *"$CLANG_SEGNO"*) ;; *) errore "compilatore diverso da quello della Odin ($CLANG_SEGNO)";; esac
  case $cv in *"($CLANG_BUILD,"*) nota "stessa build del compilatore della Odin ($CLANG_BUILD)";; *) nota "ATTENZIONE: build del compilatore diversa da quella della Odin ($CLANG_BUILD): stessi sorgenti LLVM, cambia la riga CC_VERSION_TEXT";; esac

  # --- sorgenti: solo il commit del kernel installato
  git init -q "$SRC"; git -C "$SRC" remote add origin "$KERNEL_REPO"
  git -C "$SRC" fetch -q --depth 1 origin "$KERNEL_COMMIT"; git -C "$SRC" checkout -q FETCH_HEAD
  nota "sorgenti: $(git -C "$SRC" log -1 --format='%H %s')"
  # stesso nome di versione dell'originale anche con i sorgenti modificati (senza, diventerebbe "-dirty")
  echo "-g${KERNEL_COMMIT:0:12}" > "$SRC/.scmversion"

  # --- configurazione originale: deve avere la stessa impronta di quella letta sulla Odin
  "${MK[@]}" $DEFCONFIG > "$RIS/config.log" 2>&1 || { tail -n 30 "$RIS/config.log" >> "$STATO"; errore "configurazione non generata"; }
  cp "$OUT/.config" "$RIS/config-originale"
  grep -E '^(# )?CONFIG_(CC_VERSION_TEXT|CLANG_VERSION|LLD_VERSION|MODULE_SIG_PROTECT|MODULE_ALLOW_BTF_MISMATCH|DEBUG_INFO_BTF|DEBUG_INFO_BTF_MODULES|LTO_CLANG_FULL|CFI_CLANG|TRIM_UNUSED_KSYMS|TCP_CONG_ADVANCED|COMPAT_VDSO|PAHOLE_[A-Z0-9_]*)[= ]' "$OUT/.config" >> "$STATO" || true
  imp=$(impronta "$OUT/.config"); nota "impronta della configurazione: $imp"
  # Impronta diversa = non fermarsi: la configurazione generata qui viene pubblicata in kernel/esiti/config-originale e la
  # Odin la confronta riga per riga con la sua (modulo, kernel_confronta). Si installa solo dopo aver letto quel confronto.
  if [ "$imp" = "$CONFIG_IMPRONTA" ]; then nota "configurazione identica a quella della Odin"
  else nota "ATTENZIONE: impronta diversa da quella della Odin ($CONFIG_IMPRONTA): serve il confronto riga per riga fatto dalla Odin"; echo "$imp" > "$RIS/da-confrontare"; fi
  # come si comporta il kernel se le informazioni BTF di un modulo non combaciano (i moduli della ROM le hanno)
  nota "--- BTF dei moduli (kernel/bpf/btf.c)"; grep -n -A7 'failed to validate module' "$SRC/kernel/bpf/btf.c" | head -n 14 >> "$STATO" || nota "(riga non trovata)"

  [ "$V" = misura ] && su_misura
  # --- versione: deve essere quella dell'originale
  "${MK[@]}" prepare > "$RIS/prepare.log" 2>&1 || { tail -n 40 "$RIS/prepare.log" >> "$STATO"; errore "preparazione"; }
  rel=$(cat "$OUT/include/config/kernel.release"); nota "versione: $rel"
  [ "$rel" = "$KERNEL_RELEASE" ] || errore "versione diversa da $KERNEL_RELEASE"
  nota "fase prepara: OK"
}

su_misura() {
  # --- futex_waitv: patch per 5.15 dell'autore originale (André Almeida)
  git clone -q --depth 1 https://github.com/andrealmeid/futex_waitv_patches "$W/fw"
  nota "--- futex_waitv: patch $(git -C "$W/fw" rev-parse --short HEAD): $(ls "$W/fw/5.15" | tr '\n' ' ')"
  for p in $(grep -l "^diff --git" "$W"/fw/5.15/* | LC_ALL=C sort); do
    if patch -d "$SRC" -p1 -N --no-backup-if-mismatch < "$p" >> "$RIS/patch.log" 2>&1; then nota "  applicata: $(basename "$p")"
    else
      tail -n 25 "$RIS/patch.log" >> "$STATO"
      find "$SRC" -name '*.rej' | head -n 4 | while read -r r; do nota "--- $r"; head -n 50 "$r" >> "$STATO"; done
      errore "patch non applicabile: $(basename "$p")"
    fi
  done
  # arm64 usa la tabella generica delle chiamate di sistema: se la serie non la tocca, si aggiunge il numero 449 (come nel 5.16)
  U=$SRC/include/uapi/asm-generic/unistd.h
  if ! grep -q futex_waitv "$U"; then
    grep -q '^#define __NR_syscalls 449$' "$U" || errore "tabella delle chiamate di sistema diversa dal previsto: $(grep -n '__NR_syscalls' "$U" | tr '\n' ' ')"
    sed -i 's/^#define __NR_syscalls 449$/#define __NR_futex_waitv 449\n__SYSCALL(__NR_futex_waitv, sys_futex_waitv)\n\n#undef __NR_syscalls\n#define __NR_syscalls 450/' "$U"
    nota "  aggiunta la chiamata 449 futex_waitv nella tabella generica (arm64)"
  fi
  nota "  futex_waitv nei sorgenti: $(grep -l futex_waitv "$U" "$SRC/arch/arm64/include/asm/unistd32.h" "$SRC/include/linux/syscalls.h" "$SRC/kernel/sys_ni.c" "$SRC"/kernel/futex.c "$SRC"/kernel/futex/*.c 2>/dev/null | sed "s#$SRC/##" | tr '\n' ' ')"
  grep -rq 'SYSCALL_DEFINE5(futex_waitv' "$SRC/kernel" || errore "futex_waitv non risulta definita dopo le patch"
  nota "  file cambiati: $(git -C "$SRC" status --short | grep -v scmversion | awk '{print $2}' | tr '\n' ' ')"

  # --- configurazione
  # MODULE_SIG_PROTECT via: zram.ko e zsmalloc.ko di system_dlkm sono firmati con la chiave (usa e getta) della
  # compilazione di LineageOS; con la nostra chiave risultano non firmati e con la protezione attiva verrebbero rifiutati.
  C=("$SRC/scripts/config" --file "$OUT/.config")
  "${C[@]}" -d MODULE_SIG_PROTECT -e TCP_CONG_ADVANCED -e TCP_CONG_BBR -d TCP_CONG_BIC -d TCP_CONG_WESTWOOD -d TCP_CONG_HTCP
  grep -q 'config MODULE_ALLOW_BTF_MISMATCH' "$SRC/lib/Kconfig.debug" && "${C[@]}" -e MODULE_ALLOW_BTF_MISMATCH
  "${MK[@]}" olddefconfig >> "$RIS/config.log" 2>&1 || errore "configurazione su misura"
  cp "$OUT/.config" "$RIS/config-misura"
  diff <(grep '^CONFIG_' "$RIS/config-originale" | LC_ALL=C sort) <(grep '^CONFIG_' "$OUT/.config" | LC_ALL=C sort) | grep '^[<>]' > "$RIS/config.diff" || true
  nota "--- configurazione cambiata rispetto all'originale"; cat "$RIS/config.diff" >> "$STATO"
  if grep -vE '^< CONFIG_MODULE_SIG_PROTECT=y$|^> CONFIG_(TCP_CONG_[A-Z0-9_]+|DEFAULT_[A-Z0-9_]+|MODULE_ALLOW_BTF_MISMATCH)=' "$RIS/config.diff" | grep -q .; then
    errore "la configurazione è cambiata in punti non previsti"
  fi
  grep -q '^CONFIG_TCP_CONG_BBR=y$' "$OUT/.config" || errore "BBR non attivo"
  # senza questa opzione un modulo con BTF che non combacia non si carica: kernel da non installare
  grep -q '^CONFIG_MODULE_ALLOW_BTF_MISMATCH=y$' "$OUT/.config" || echo "il kernel rifiuta i moduli con BTF che non combacia (manca MODULE_ALLOW_BTF_MISMATCH): serve una correzione prima di installarlo" > "$RIS/blocco"
  [ -f "$RIS/blocco" ] && nota "ATTENZIONE: $(cat "$RIS/blocco")"
  return 0
}

costruisci() {
  nota "=== fase costruisci | $(date -u '+%H:%M') UTC ==="
  SECONDS=0
  "${MK[@]}" Image > "$RIS/build.log" 2>&1 || { grep -n -m 12 -B2 -A6 -E 'error:|Error [0-9]|FAILED|Killed' "$RIS/build.log" >> "$STATO" || true; tail -n 25 "$RIS/build.log" >> "$STATO"; errore "compilazione fallita dopo $((SECONDS / 60)) min"; }
  nota "compilato in $((SECONDS / 60)) min"
  cp "$OUT/arch/arm64/boot/Image" "$OUT/vmlinux.symvers" "$RIS/"
  nota "Image: $(stat -c %s "$RIS/Image") byte | $(strings -n 30 "$RIS/Image" | grep -m 1 'Linux version' | cut -c1-90)"
  nota "simboli esportati: $(wc -l < "$RIS/vmlinux.symvers") | futex_waitv: $(grep -c ' [Tt] __arm64_sys_futex_waitv$' "$OUT/System.map" || true) | BBR: $(grep -c ' bbr_init$' "$OUT/System.map" || true)"
  if [ "$V" = misura ]; then
    grep -q ' __arm64_sys_futex_waitv$' "$OUT/System.map" || errore "futex_waitv non è nel kernel compilato"
    # pacchetto AnyKernel3: sostituisce solo il kernel dentro la partizione boot dello slot attivo
    git clone -q --depth 1 https://github.com/osm0sis/AnyKernel3 "$W/ak3"
    nota "AnyKernel3: $(git -C "$W/ak3" rev-parse --short HEAD)"
    rm -rf "$W/ak3/.git" "$W/ak3/.github" "$W/ak3/README.md" "$W/ak3/ramdisk" "$W/ak3/patch" "$W/ak3/modules"
    sed "s/@KERNEL_RELEASE@/$KERNEL_RELEASE/" "$QUI/anykernel.sh" > "$W/ak3/anykernel.sh"
    cp "$RIS/Image" "$W/ak3/Image"
    Z=$RIS/Odin2Portal-kernel-$KERNEL_RELEASE-misura.zip
    (cd "$W/ak3" && zip -qr9 "$Z" .)
    nota "pacchetto: $(basename "$Z") | $(stat -c %s "$Z") byte | sha256 $(sha256sum "$Z" | cut -c1-64)"
    rm -f "$RIS/Image"   # è già dentro lo zip
  else
    rm -f "$RIS/Image"   # dell'originale servono solo i simboli
  fi
  nota "fase costruisci: OK"
}

case $FASE in prepara) prepara;; costruisci) costruisci;; *) echo "uso: compila.sh originale|misura prepara|costruisci"; exit 2;; esac
