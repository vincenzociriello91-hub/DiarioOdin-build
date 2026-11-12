#!/bin/bash
# Prova di kernel/robot.sh con curl finto e repository locali. Uso: prova_robot.sh <cartella kernel/ vera>
K=$1; T=$(mktemp -d); cd "$T"; P=0; F=0
ok() { if eval "$2"; then P=$((P+1)); echo "  OK   $1"; else F=$((F+1)); echo "  FAIL $1"; fi; }
git init -q --bare origine.git; git init -q --bare kern.git
git clone -q kern.git ks 2>/dev/null; (cd ks && git checkout -q -b lineage-24.0 && echo a > f && git add f && git -c user.name=t -c user.email=t@t commit -q -m a && git push -q origin lineage-24.0)
PUNTA=$(git -C ks rev-parse HEAD)
git clone -q origine.git lav 2>/dev/null; cd lav; git checkout -q -b main; mkdir kernel
sed "s#^KERNEL_REPO=.*#KERNEL_REPO=$T/kern.git#" "$K/versione.conf" > kernel/versione.conf; cp "$K/robot.sh" kernel/
git add -A; git -c user.name=t -c user.email=t@t commit -q -m inizio; git push -q origin main; cd ..
VECCHIO=$(. lav/kernel/versione.conf; echo $KERNEL_COMMIT); NUOVO=5646ff2f4a18aaaaaaaaaaaaaaaaaaaaaaaaaaaa
mkdir bin finto
cat > bin/curl <<'X'
#!/bin/bash
for a; do [ "$p" = -o ] && o=$a; p=$a; u=$a; done
case $u in
  *api/v2/devices/odin2portal/builds) [ -f "$FINTO/api_ko" ] && exit 22; cp "$FINTO/b.json" "$o";;
  https://mirrorbits.lineageos.org/*) cp "$FINTO/m.xml" "$o";;
  *) exit 6;;
esac
X
chmod +x bin/curl; export FINTO=$T/finto PATH=$T/bin:$PATH
api() { printf '[{"date":"2026-11-02","datetime":100,"files":[{"filename":"build-manifest.xml","url":"https://mirrorbits.lineageos.org/full/odin2portal/20261102/build-manifest.xml"}]},{"date":"2026-11-09","datetime":200,"files":[{"filename":"boot.img","url":"x"},{"filename":"build-manifest.xml","url":"%s"}]}]' "$1" > finto/b.json; }
man() { printf '<manifest>\n  <project path="kernel/altro" name="LineageOS/android_kernel_altro" revision="%s" />\n  <project path="kernel/ayn/qcs8550" name="LineageOS/android_kernel_ayn_qcs8550" revision="%s" upstream="refs/heads/lineage-24.0" />\n</manifest>\n' ffffffffffffffffffffffffffffffffffffffff "$1" > finto/m.xml; }
gira() { rm -f out stato; (cd lav && GITHUB_EVENT_NAME=$1 GITHUB_OUTPUT=$T/out bash kernel/robot.sh "$T/stato" >/dev/null 2>&1); }
conf() { (. lav/kernel/versione.conf; eval echo \$$1); }
api https://mirrorbits.lineageos.org/full/odin2portal/20261109/build-manifest.xml; man $NUOVO
gira push;     ok "push: si compila, versione.conf non toccato" 'grep -qx compila=true out && [ "$(conf KERNEL_COMMIT)" = "$VECCHIO" ]'
man $VECCHIO
gira schedule; ok "notte, kernel già compilato: niente" 'grep -qx compila=false out && grep -q "già stato compilato" stato'
gira workflow_dispatch; ok "su richiesta, kernel già compilato: si ricompila" 'grep -qx compila=true out && [ "$(conf KERNEL_COMMIT)" = "$VECCHIO" ]'
man $NUOVO
gira schedule; ok "notte, kernel nuovo: versione.conf aggiornato e salvato, si compila" 'grep -qx compila=true out && [ "$(conf KERNEL_COMMIT)" = "$NUOVO" ] && [ "$(conf KERNEL_RELEASE)" = auto ] && [ "$(conf CONFIG_IMPRONTA)" = auto ] && [ "$(conf LINEAGE_BUILD)" = 2026-11-09 ] && git -C origine.git log -1 --format=%s main | grep -q "robot, kernel nuovo 5646ff2f4a18 (LineageOS 2026-11-09)"'
ok "il resto di versione.conf è rimasto" '[ "$(conf CLANG)" = clang-r596125 ] && [ "$(conf CLANG_BUILD)" = 15071444 ] && [ -n "$(conf DEFCONFIG)" ]'
gira schedule; ok "la notte dopo: niente da fare" 'grep -qx compila=false out'
touch finto/api_ko
gira schedule; ok "elenco non raggiungibile: punta del ramo dei sorgenti" 'grep -qx compila=true out && [ "$(conf KERNEL_COMMIT)" = "$PUNTA" ] && [ "$(conf LINEAGE_BUILD)" = sconosciuta ] && grep -q "punta del ramo" stato'
rm finto/api_ko; man 'x"; rm -rf /; echo "'; api https://mirrorbits.lineageos.org/m.xml
gira schedule; ok "manifesto con un commit non valido: ignorato, vale la punta del ramo (già compilata)" 'grep -qx compila=false out && [ "$(conf KERNEL_COMMIT)" = "$PUNTA" ]'
api 'https://altrove.example/m.xml'; man $NUOVO
gira schedule; ok "manifesto su un sito diverso da quello di LineageOS: non scaricato" 'grep -qx compila=false out && [ "$(conf KERNEL_COMMIT)" = "$PUNTA" ]'
printf 'non json' > finto/b.json
gira schedule; ok "elenco illeggibile: nessun errore, punta del ramo" 'grep -qx compila=false out'
echo "RISULTATO: $P superati, $F falliti"
