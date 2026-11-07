#!/bin/sh
# Compila l'APK di Diario Odin con gli strumenti dell'SDK Android, senza Gradle.
# Uso: ANDROID_HOME=/percorso/sdk sh app-diario/build.sh
# Risultato: modulo/app/DiarioOdin.apk + modulo/app/versione (versionCode, letto dal modulo)
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
[ -d "$SDK/build-tools" ] || { echo "SDK Android non trovato (ANDROID_HOME)"; exit 1; }
BT=$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)
JAR=$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar
VC=$(sed -n 's/^versionCode=//p' versione.prop)
VN=$(sed -n 's/^versionName=//p' versione.prop)
O=build
rm -rf $O && mkdir -p $O/classes

"$BT/aapt2" compile --dir res -o $O/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o $O/base.apk \
  --min-sdk-version 30 --target-sdk-version 35 --version-code "$VC" --version-name "$VN" $O/res.zip
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d $O/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$JAR" --output $O $(find $O/classes -name '*.class')
cp $O/base.apk $O/senza_firma.apk
(cd $O && zip -q senza_firma.apk classes.dex)
"$BT/zipalign" -f -p 4 $O/senza_firma.apk $O/allineato.apk
# chiave usa e getta, creata a ogni compilazione: nessun segreto nel repository.
# Firma diversa a ogni versione: il modulo, se l'aggiornamento viene rifiutato, disinstalla e reinstalla.
P=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
keytool -genkeypair -keystore $O/k.p12 -storetype PKCS12 -storepass "$P" -keypass "$P" -alias diario \
  -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Diario Odin" >/dev/null 2>&1
"$BT/apksigner" sign --ks $O/k.p12 --ks-pass "pass:$P" --out $O/DiarioOdin.apk $O/allineato.apk
rm -f $O/k.p12
"$BT/apksigner" verify $O/DiarioOdin.apk

mkdir -p ../modulo/app
cp $O/DiarioOdin.apk ../modulo/app/DiarioOdin.apk
echo "$VC" > ../modulo/app/versione
echo "OK: DiarioOdin $VN ($VC)"
