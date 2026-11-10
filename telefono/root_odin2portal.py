#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Odin 2 Portal Root Helper  -  root automatico con Magisk su LineageOS
=====================================================================

Cosa fa, in ordine:
  1. Scarica gli strumenti ufficiali Google (adb/fastboot) se mancano
  2. Controlla che la console sia una Odin 2 Portal con LineageOS e bootloader sbloccato
  3. Prende l'immagine init_boot ESATTA della build di LineageOS installata
     (dal sito LineageOS, oppure estraendola dal pacchetto .zip ufficiale)
  4. Installa l'app Magisk ufficiale (GitHub di topjohnwu)
  5. Ti guida nella patch dentro l'app Magisk (3 tocchi) e recupera il file
  6. Flasha l'immagine patchata solo sullo slot attivo
  7. Verifica il root e, se presente, installa il modulo Odin2PortalTweaks

Sicurezza:
  - non sblocca MAI il bootloader (cancellerebbe i dati): se è bloccato si ferma
  - usa solo l'immagine della tua build, verificata prima e dopo la patch
  - conserva l'originale e offre il ripristino:  python root_odin2portal.py --ripristina

Uso:  python root_odin2portal.py            (procedura completa)
      python root_odin2portal.py --ripristina   (rimette l'init_boot originale)
      python root_odin2portal.py --zip FILE.zip (usa un pacchetto LineageOS già scaricato)
"""

import argparse
import bz2
import glob
import hashlib
import io
import json
import lzma
import os
import platform
import re
import shutil
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

VERSIONE = "1.1"
DEVICE = "odin2portal"
PARTIZIONE = "init_boot"
MAX_INIT_BOOT = 8 * 1024 * 1024          # dimensione partizione (BoardConfigCommon.mk)
MAGISK_PKG = "com.topjohnwu.magisk"
LINEAGE_API = "https://download.lineageos.org/api/v2/devices/%s/builds" % DEVICE
LINEAGE_PAGE = "https://download.lineageos.org/devices/%s/builds" % DEVICE
MAGISK_API = "https://api.github.com/repos/topjohnwu/Magisk/releases/latest"
PT_URL = "https://dl.google.com/android/repository/platform-tools-latest-%s.zip"

BASE = os.path.dirname(os.path.abspath(__file__))
LAVORO = os.path.join(BASE, "odin2portal_root")
TOOLS = os.path.join(LAVORO, "platform-tools")

ADB = "adb"
FASTBOOT = "fastboot"


# --------------------------------------------------------------------------
# Interfaccia
# --------------------------------------------------------------------------
class Stop(Exception):
    """Interruzione controllata con messaggio per l'utente."""


def titolo(t):
    print("\n" + "=" * 64 + "\n  " + t + "\n" + "=" * 64)


def info(t):
    print("  " + t)


def ok(t):
    print("  [OK] " + t)


def attenzione(t):
    print("  [!]  " + t)


def chiedi_si(domanda, default_no=True):
    suff = " [s/N] " if default_no else " [S/n] "
    while True:
        try:
            r = input("  " + domanda + suff).strip().lower()
        except EOFError:
            r = ""
        if r == "":
            return not default_no
        if r in ("s", "si", "sì", "y", "yes"):
            return True
        if r in ("n", "no"):
            return False


def invio(t="Premi INVIO per continuare..."):
    try:
        input("  " + t)
    except EOFError:
        pass


# --------------------------------------------------------------------------
# Comandi esterni
# --------------------------------------------------------------------------
def esegui(cmd, timeout=120, controlla=False):
    """Esegue un comando e restituisce (codice, output)."""
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=timeout)
        out = p.stdout.decode("utf-8", "replace")
    except FileNotFoundError:
        raise Stop("Comando non trovato: %s" % cmd[0])
    except subprocess.TimeoutExpired:
        if controlla:
            raise Stop("Il comando non ha risposto in tempo: %s" % " ".join(cmd))
        return 124, ""
    if controlla and p.returncode != 0:
        raise Stop("Comando fallito: %s\n%s" % (" ".join(cmd), out.strip()))
    return p.returncode, out


def adb(*a, timeout=120, controlla=False):
    return esegui([ADB] + list(a), timeout=timeout, controlla=controlla)


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)[1].strip()


def fastboot(*a, timeout=120, controlla=False):
    return esegui([FASTBOOT] + list(a), timeout=timeout, controlla=controlla)


# --- connessioni sicure -----------------------------------------------------
# La verifica dei certificati resta SEMPRE attiva. Se Python non riesce a verificarli
# (tipico su Windows con antivirus che controllano HTTPS, o certificati intermedi
# mancanti), si usano tutti i certificati di Windows e poi lo strumento curl del
# sistema, che si fida degli stessi certificati del browser.
_CTX = None
OID_SERVER = "1.3.6.1.5.5.7.3.1"


def _errore_certificato(e):
    r = getattr(e, "reason", e)
    return isinstance(r, ssl.SSLCertVerificationError) or "CERTIFICATE_VERIFY_FAILED" in str(e)


def _contesto_sistema():
    ctx = ssl.create_default_context()
    if hasattr(ssl, "enum_certificates"):              # solo Windows
        for store in ("ROOT", "CA"):
            try:
                certs = ssl.enum_certificates(store)
            except OSError:
                continue
            for der, codifica, fiducia in certs:
                if codifica != "x509_asn":
                    continue
                if fiducia is not True and OID_SERVER not in (fiducia or ()):
                    continue
                try:
                    ctx.load_verify_locations(cadata=der)
                except (ssl.SSLError, ValueError):
                    pass
    return ctx


def _apri(req, timeout):
    global _CTX
    try:
        return urllib.request.urlopen(req, timeout=timeout, context=_CTX)
    except urllib.error.URLError as e:
        if _CTX is not None or not _errore_certificato(e):
            raise
    _CTX = _contesto_sistema()
    return urllib.request.urlopen(req, timeout=timeout, context=_CTX)


def _curl():
    for nome in ("curl.exe", "curl"):
        c = shutil.which(nome)
        if c:
            return c
    return None


def _scarica_curl(url, tmp, descr):
    c = _curl()
    if not c:
        return False
    info("Uso lo strumento di download di sistema (curl) per %s..." % descr)
    cmd = [c, "-fL", "--retry", "3", "-#", "-A", "odin2portal-root/" + VERSIONE, "-C", "-", "-o", tmp, url]
    try:
        return subprocess.run(cmd, timeout=7200).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def scarica(url, dest, descr="", sha256=None):
    """Download con barra di avanzamento, ripresa e verifica sha256."""
    tmp = dest + ".part"
    inizio = os.path.getsize(tmp) if os.path.exists(tmp) else 0
    req = urllib.request.Request(url, headers={"User-Agent": "odin2portal-root/" + VERSIONE})
    if inizio:
        req.add_header("Range", "bytes=%d-" % inizio)
    r = None
    try:
        r = _apri(req, 60)
    except Exception as e:
        if not _scarica_curl(url, tmp, descr or url):
            raise Stop("Download non riuscito (%s): %s" % (descr or url, e))
    if r is not None:
        if inizio and r.status != 206:      # il server non supporta la ripresa
            inizio = 0
        totale = r.headers.get("Content-Length")
        totale = int(totale) + inizio if totale else None
        fatto = inizio
        with open(tmp, "ab" if inizio else "wb") as f:
            while True:
                blocco = r.read(1024 * 256)
                if not blocco:
                    break
                f.write(blocco)
                fatto += len(blocco)
                if totale:
                    pct = fatto * 100 // totale
                    sys.stdout.write("\r  %s %3d%%  (%d/%d MB)" % (descr, pct, fatto >> 20, totale >> 20))
                    sys.stdout.flush()
        print()
        if totale and fatto != totale:
            raise Stop("Download incompleto di %s: riavvia il programma per riprendere." % descr)
    if sha256 and file_sha256(tmp) != sha256.lower():
        os.remove(tmp)
        raise Stop("Il file scaricato (%s) è danneggiato: riprova." % descr)
    os.replace(tmp, dest)
    return dest


def scarica_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "odin2portal-root/" + VERSIONE,
                                               "Accept": "application/json"})
    try:
        with _apri(req, 30) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        c = _curl()
        if c:
            try:
                p = subprocess.run([c, "-fsSL", "-A", "odin2portal-root/" + VERSIONE,
                                    "-H", "Accept: application/json", url],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
                if p.returncode == 0:
                    return json.loads(p.stdout.decode("utf-8"))
            except (OSError, ValueError, subprocess.TimeoutExpired):
                pass
        raise Stop("Connessione non riuscita a %s:\n  %s" % (url.split("/")[2], e))


def file_sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


# --------------------------------------------------------------------------
# 1. Strumenti adb / fastboot
# --------------------------------------------------------------------------
def prepara_strumenti():
    global ADB, FASTBOOT
    titolo("1/7  Strumenti adb e fastboot")
    est = ".exe" if os.name == "nt" else ""
    locali = (os.path.join(TOOLS, "adb" + est), os.path.join(TOOLS, "fastboot" + est))
    if shutil.which("termux-adb") and shutil.which("termux-fastboot"):
        # sul telefono (Termux, vedi telefono.sh): il telefono fa da PC attraverso il cavo USB-C
        ADB, FASTBOOT = shutil.which("termux-adb"), shutil.which("termux-fastboot")
    elif all(os.path.exists(x) for x in locali):
        ADB, FASTBOOT = locali
    elif shutil.which("adb") and shutil.which("fastboot"):
        ADB, FASTBOOT = shutil.which("adb"), shutil.which("fastboot")
    else:
        sistema = {"Windows": "windows", "Darwin": "darwin", "Linux": "linux"}.get(platform.system())
        if not sistema:
            raise Stop("Sistema operativo non supportato: " + platform.system())
        info("Scarico gli strumenti ufficiali Google (platform-tools)...")
        os.makedirs(LAVORO, exist_ok=True)
        z = scarica(PT_URL % sistema, os.path.join(LAVORO, "platform-tools.zip"), "platform-tools")
        with zipfile.ZipFile(z) as zf:
            zf.extractall(LAVORO)
        for x in locali:
            if os.name != "nt":
                os.chmod(x, 0o755)
        ADB, FASTBOOT = locali
    _, v = esegui([ADB, "version"])
    ok("adb pronto: " + (v.splitlines()[0] if v else ADB))


# --------------------------------------------------------------------------
# 2. Controlli sul dispositivo
# --------------------------------------------------------------------------
def attendi_adb(secondi=180, messaggio=True):
    if messaggio:
        info("In attesa della console via USB...")
    fine = time.time() + secondi
    avvisato = False
    while time.time() < fine:
        _, out = adb("devices")
        righe = [l for l in out.splitlines()[1:] if l.strip()]
        if any(l.split()[-1] == "device" for l in righe):
            return
        if any("unauthorized" in l for l in righe) and not avvisato:
            attenzione("Sulla console tocca CONSENTI sulla richiesta 'Consentire il debug USB?'")
            avvisato = True
        time.sleep(2)
    raise Stop("Console non trovata. Controlla il cavo e che 'Debug USB' sia attivo\n"
               "  (Impostazioni > Sistema > Opzioni sviluppatore > Debug USB).")


def attendi_avvio(secondi=300):
    attendi_adb(secondi)
    fine = time.time() + secondi
    while time.time() < fine:
        if shell("getprop sys.boot_completed") == "1":
            return
        time.sleep(3)
    raise Stop("La console non ha completato l'avvio.")


def controlla_dispositivo():
    titolo("2/7  Controllo della console")
    info("Collega la Odin 2 Portal al PC con il cavo USB.")
    info("Deve essere attivo: Impostazioni > Sistema > Opzioni sviluppatore > Debug USB")
    attendi_adb()
    p = {k: shell("getprop " + k) for k in (
        "ro.product.device", "ro.lineage.device", "ro.lineage.version", "ro.build.version.release",
        "ro.boot.verifiedbootstate", "ro.boot.slot_suffix", "ro.boot.flash.locked")}
    dispositivo = p["ro.lineage.device"] or p["ro.product.device"]
    info("Dispositivo: %s  |  LineageOS %s  |  Android %s" % (
        dispositivo or "?", p["ro.lineage.version"] or "?", p["ro.build.version.release"] or "?"))
    if dispositivo != DEVICE:
        raise Stop("Questa non è una Odin 2 Portal (%s): mi fermo per sicurezza." % dispositivo)
    if not p["ro.lineage.version"]:
        raise Stop("LineageOS non rilevato: questo programma è solo per LineageOS.")
    # basta UN segnale di bootloader bloccato per fermarsi (doppio controllo poi in fastboot)
    bloccato = p["ro.boot.flash.locked"] == "1" or p["ro.boot.verifiedbootstate"] in ("green", "yellow")
    if bloccato or p["ro.boot.verifiedbootstate"] != "orange":
        raise Stop("Il bootloader risulta BLOCCATO. Sbloccarlo cancella tutti i dati:\n"
                   "  questo programma non lo fa. Con LineageOS installato dovrebbe essere già sbloccato.")
    ok("Bootloader sbloccato, slot attivo: %s" % (p["ro.boot.slot_suffix"] or "?"))
    if PARTIZIONE not in shell("ls /dev/block/by-name/ 2>/dev/null") and \
            PARTIZIONE not in shell("ls /dev/block/bootdevice/by-name/ 2>/dev/null"):
        attenzione("Non riesco a confermare la partizione init_boot (normale senza root): proseguo,\n"
                   "       la presenza è verificata nei sorgenti ufficiali LineageOS di odin2portal.")
    if "uid=0" in shell("su -c id 2>/dev/null", timeout=15):
        attenzione("Il root sembra già attivo: proseguendo lo reinstalli (va bene dopo un aggiornamento).")
    return p


def parse_versione(v):
    """'24.0-20261020-NIGHTLY-odin2portal' -> ('24.0', '20261020')"""
    m = re.match(r"^(\d+(?:\.\d+)?)-(\d{8})-", v or "")
    if not m:
        raise Stop("Versione LineageOS non riconosciuta: %r" % v)
    return m.group(1), m.group(2)


# --------------------------------------------------------------------------
# 3. Immagine init_boot della build installata
# --------------------------------------------------------------------------
def immagine_valida(path):
    try:
        if os.path.getsize(path) > MAX_INIT_BOOT or os.path.getsize(path) < 4096:
            return False
        with open(path, "rb") as f:
            return f.read(8) == b"ANDROID!"
    except OSError:
        return False


def trova_build(api_json, versione, data):
    """Cerca la build installata nell'elenco del sito LineageOS.
    Restituisce (file_zip, file_init_boot) come dict {filename,url,sha256} o None."""
    builds = api_json if isinstance(api_json, list) else api_json.get("response", [])
    for b in builds:
        files = b.get("files") or []
        zipf = None
        for f in files:
            nome = f.get("filename", "")
            if nome.endswith(".zip") and data in nome and ("lineage-%s-" % versione) in nome:
                zipf = f
        if not zipf:
            continue
        initb = next((f for f in files if f.get("filename") == PARTIZIONE + ".img"), None)
        return zipf, initb
    return None, None


# --- estrazione da payload.bin (formato A/B di Android: CrAU + protobuf) ----
def _varint(buf, i):
    r = s = 0
    while True:
        b = buf[i]
        i += 1
        r |= (b & 0x7F) << s
        if not b & 0x80:
            return r, i
        s += 7


def _campi(buf):
    """Decodifica minima protobuf: genera (numero_campo, tipo, valore)."""
    i, n = 0, len(buf)
    while i < n:
        chiave, i = _varint(buf, i)
        campo, tipo = chiave >> 3, chiave & 7
        if tipo == 0:
            v, i = _varint(buf, i)
        elif tipo == 2:
            l, i = _varint(buf, i)
            v = buf[i:i + l]
            i += l
        elif tipo == 1:
            v = buf[i:i + 8]
            i += 8
        elif tipo == 5:
            v = buf[i:i + 4]
            i += 4
        else:
            raise Stop("Pacchetto LineageOS non riconosciuto (protobuf tipo %d)." % tipo)
        yield campo, tipo, v


def estrai_da_payload(fp, base, nome_partizione, dest):
    """fp: file aperto sul payload.bin (seekable), base: offset del payload nel file."""
    fp.seek(base)
    testa = fp.read(24)
    if testa[:4] != b"CrAU":
        raise Stop("payload.bin non valido.")
    versione, man_size = struct.unpack(">QQ", testa[4:20])
    if versione != 2:
        raise Stop("Formato payload versione %d non supportato." % versione)
    sig_size = struct.unpack(">I", testa[20:24])[0]
    manifest = fp.read(man_size)
    dati = base + 24 + man_size + sig_size
    block = 4096
    parti = []
    for c, t, v in _campi(manifest):
        if c == 3 and t == 0:
            block = v
        elif c == 13 and t == 2:
            parti.append(v)
    for pu in parti:
        nome, ops, size = None, [], None
        for c, t, v in _campi(pu):
            if c == 1:
                nome = v.decode()
            elif c == 8:
                ops.append(v)
            elif c == 7:
                size = next((x for cc, tt, x in _campi(v) if cc == 1 and tt == 0), None)
        if nome != nome_partizione:
            continue
        out = bytearray(size or 0)
        for op in ops:
            tipo, off, ln, ext = 0, 0, 0, []
            for c, t, v in _campi(op):
                if c == 1:
                    tipo = v
                elif c == 2:
                    off = v
                elif c == 3:
                    ln = v
                elif c == 6:
                    e = dict((cc, x) for cc, tt, x in _campi(v))
                    ext.append((e.get(1, 0), e.get(2, 0)))
            if tipo in (6, 7):            # ZERO / DISCARD
                continue
            fp.seek(dati + off)
            blob = fp.read(ln)
            if tipo == 0:
                chiaro = blob
            elif tipo == 1:
                chiaro = bz2.decompress(blob)
            elif tipo == 8:
                chiaro = lzma.decompress(blob)
            else:
                raise Stop("Operazione %d non supportata (pacchetto incrementale?):\n"
                           "  serve il pacchetto COMPLETO di LineageOS." % tipo)
            pos = 0
            for start, num in ext:
                lung = num * block
                pezzo = chiaro[pos:pos + lung]
                fine = start * block + len(pezzo)
                if fine > len(out):
                    out.extend(b"\0" * (fine - len(out)))
                out[start * block:fine] = pezzo
                pos += lung
        if size:
            del out[size:]
        with open(dest, "wb") as f:
            f.write(out)
        return dest
    raise Stop("La partizione %s non è presente nel pacchetto." % nome_partizione)


def estrai_da_zip(zip_path, dest):
    info("Estraggo init_boot dal pacchetto LineageOS (nessuna modifica al file)...")
    with zipfile.ZipFile(zip_path) as zf:
        try:
            zi = zf.getinfo("payload.bin")
        except KeyError:
            raise Stop("Nel pacchetto non c'è payload.bin: non è un pacchetto LineageOS valido.")
        if zi.compress_type == zipfile.ZIP_STORED:
            # payload salvato senza compressione: si legge direttamente dal file zip
            with open(zip_path, "rb") as f:
                f.seek(zi.header_offset)
                h = f.read(30)
                nl, el = struct.unpack("<HH", h[26:30])
                base = zi.header_offset + 30 + nl + el
                return estrai_da_payload(f, base, PARTIZIONE, dest)
        with zf.open(zi) as src:
            dati = io.BytesIO(src.read())
        return estrai_da_payload(dati, 0, PARTIZIONE, dest)


def prendi_init_boot(prop, zip_utente=None):
    titolo("3/7  Immagine init_boot della tua build")
    versione, data = parse_versione(prop["ro.lineage.version"])
    cartella = os.path.join(LAVORO, "build-%s-%s" % (versione, data))
    os.makedirs(cartella, exist_ok=True)
    originale = os.path.join(cartella, "init_boot_originale.img")
    if immagine_valida(originale):
        ok("Già presente: " + originale)
        return originale, cartella

    zips = [zip_utente] if zip_utente else []
    zips += glob.glob(os.path.join(BASE, "lineage-%s-%s-*%s*.zip" % (versione, data, DEVICE)))
    zips += glob.glob(os.path.join(cartella, "lineage-*.zip"))
    zips = [z for z in zips if z and os.path.exists(z)]

    if not zips:
        info("Cerco la build %s del %s sul sito LineageOS..." % (versione, data))
        zipf = initb = None
        try:
            zipf, initb = trova_build(scarica_json(LINEAGE_API), versione, data)
        except Stop:
            raise
        except Exception as e:
            attenzione("Sito LineageOS non raggiungibile (%s)." % e)
        if initb and initb.get("url"):
            scarica(initb["url"], originale + ".tmp", "init_boot.img", initb.get("sha256"))
            os.replace(originale + ".tmp", originale)
        elif zipf and zipf.get("url"):
            info("Immagine singola non disponibile: scarico il pacchetto completo (~1,5 GB).")
            z = os.path.join(cartella, zipf["filename"])
            if not os.path.exists(z):
                scarica(zipf["url"], z, zipf["filename"], zipf.get("sha256"))
            zips = [z]
        else:
            raise Stop("Non trovo la build installata (%s, %s) sul sito LineageOS.\n"
                       "  Soluzioni:\n"
                       "   a) aggiorna LineageOS all'ultima versione (Impostazioni > Sistema > Aggiornamenti)\n"
                       "      e rilancia il programma;\n"
                       "   b) metti nella cartella del programma il file lineage-%s-%s-...-%s-signed.zip\n"
                       "      della tua build (pagina: %s)." % (versione, data, versione, data, DEVICE, LINEAGE_PAGE))
    if zips and not os.path.exists(originale):
        z = zips[0]
        nome = os.path.basename(z)
        if data not in nome:
            raise Stop("Il pacchetto %s non è della build installata (%s)." % (nome, data))
        estrai_da_zip(z, originale + ".tmp")
        os.replace(originale + ".tmp", originale)
    if not immagine_valida(originale):
        os.remove(originale)
        raise Stop("L'immagine ottenuta non è valida: mi fermo per sicurezza.")
    ok("init_boot originale salvato: " + originale)
    info("sha256: " + file_sha256(originale))
    return originale, cartella


# --------------------------------------------------------------------------
# 4. App Magisk
# --------------------------------------------------------------------------
def installa_magisk(cartella):
    titolo("4/7  App Magisk")
    if MAGISK_PKG in shell("pm list packages " + MAGISK_PKG):
        ok("Magisk è già installato.")
        return
    locali = sorted(glob.glob(os.path.join(BASE, "Magisk-v*.apk")) + glob.glob(os.path.join(cartella, "Magisk-v*.apk")))
    if locali:
        dest = locali[-1]
        ok("Uso l'APK già presente: " + os.path.basename(dest))
        rel = {"tag_name": os.path.basename(dest)[7:-4]}
    else:
        info("Scarico l'ultima versione stabile ufficiale di Magisk da GitHub...")
        manuale = ("\n  Puoi scaricarlo tu dal browser:\n"
                   "   1) apri https://github.com/topjohnwu/Magisk/releases/latest\n"
                   "   2) scarica il file Magisk-vXX.X.apk (sezione 'Assets')\n"
                   "   3) mettilo in questa cartella:\n      %s\n"
                   "   4) rilancia il programma: lo userà da solo." % BASE)
        try:
            rel = scarica_json(MAGISK_API)
        except Stop as e:
            raise Stop(str(e) + manuale)
        apk = next((a for a in rel.get("assets", [])
                    if re.match(r"^Magisk-v[\d.]+\.apk$", a.get("name", ""))), None)
        if not apk:
            raise Stop("APK di Magisk non trovato nell'ultima release." + manuale)
        dest = os.path.join(cartella, apk["name"])
        if not os.path.exists(dest):
            try:
                scarica(apk["browser_download_url"], dest, apk["name"])
            except Stop as e:
                raise Stop(str(e) + manuale)
    if os.path.getsize(dest) < 1024 * 1024 or not zipfile.is_zipfile(dest):
        os.remove(dest)
        raise Stop("Il file di Magisk non è valido (download interrotto?): rilancia il programma.")
    info("Installo %s sulla console..." % os.path.basename(dest))
    rc, out = adb("install", "-r", dest, timeout=300)
    if rc != 0 or "Success" not in out:
        raise Stop("Installazione di Magisk non riuscita:\n" + out)
    ok("Magisk %s installato." % rel.get("tag_name", ""))


# --------------------------------------------------------------------------
# 5. Patch nell'app Magisk
# --------------------------------------------------------------------------
def lista_patchate():
    out = shell("ls /sdcard/Download/magisk_patched-*.img 2>/dev/null")
    return set(l.strip() for l in out.splitlines() if l.strip().endswith(".img"))


def patch_con_magisk(originale, cartella):
    titolo("5/7  Patch con Magisk")
    patchata = os.path.join(cartella, "init_boot_magisk.img")
    if immagine_valida(patchata) and chiedi_si("Uso l'immagine già patchata in precedenza?", default_no=False):
        return patchata
    remoto = "/sdcard/Download/init_boot_odin2portal.img"
    adb("push", originale, remoto, controlla=True)
    prima = lista_patchate()
    shell("monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1" % MAGISK_PKG)
    info("Sulla console si è aperta l'app Magisk. Fai così:")
    info("  1) tocca  INSTALLA  (accanto a 'Magisk')")
    info("  2) scegli 'Seleziona e patcha un file'")
    info("  3) apri la cartella Download e scegli  init_boot_odin2portal.img")
    info("  4) tocca  INIZIA  (o 'Andiamo') e aspetta 'Fatto!'")
    info("Il programma si accorge da solo quando la patch è pronta (attesa max 10 minuti).")
    fine = time.time() + 600
    nuovo = None
    while time.time() < fine and not nuovo:
        time.sleep(3)
        diff = lista_patchate() - prima
        if diff:
            nuovo = sorted(diff)[-1]
    if not nuovo:
        raise Stop("Non ho trovato il file patchato da Magisk in Download.")
    time.sleep(3)                         # lascia finire la scrittura
    adb("pull", nuovo, patchata + ".tmp", controlla=True)
    os.replace(patchata + ".tmp", patchata)
    if not immagine_valida(patchata):
        raise Stop("L'immagine patchata non è valida: niente è stato flashato.")
    if file_sha256(patchata) == file_sha256(originale):
        raise Stop("L'immagine non risulta modificata da Magisk: niente è stato flashato.")
    shell("rm -f '%s' '%s'" % (remoto, nuovo))
    ok("Immagine patchata pronta: " + patchata)
    return patchata


# --------------------------------------------------------------------------
# 6. Flash
# --------------------------------------------------------------------------
def attendi_fastboot(secondi=120):
    fine = time.time() + secondi
    while time.time() < fine:
        _, out = fastboot("devices", timeout=15)
        if out.strip() and "fastboot" in out:
            return
        time.sleep(2)
    msg = "La console in modalità fastboot non viene vista dal PC."
    if os.name == "nt":
        msg += ("\n  Su Windows serve il driver USB: apri Gestione dispositivi, trova 'Android' con il\n"
                "  triangolo giallo > Aggiorna driver > 'Android Bootloader Interface'\n"
                "  (Google USB Driver: https://developer.android.com/studio/run/win-usb).\n"
                "  Poi rilancia il programma: riprenderà da qui.")
    raise Stop(msg)


def flash(immagine, descr, partizione=PARTIZIONE):
    info("Riavvio la console in modalità fastboot...")
    adb("reboot", "bootloader")
    attendi_fastboot()
    _, sbl = fastboot("getvar", "unlocked", timeout=15)
    if "unlocked: yes" not in sbl and "unlocked:yes" not in sbl.replace(" ", ""):
        fastboot("reboot")
        raise Stop("fastboot dice che il bootloader non è sbloccato: annullo, nessuna modifica fatta.")
    _, slot = fastboot("getvar", "current-slot", timeout=15)
    m = re.search(r"current-slot:\s*([ab])", slot)
    info("Slot attivo: %s" % (m.group(1) if m else "?"))
    info("Scrivo %s sulla partizione %s dello slot attivo..." % (descr, partizione))
    rc, out = fastboot("flash", partizione, immagine, timeout=600)   # mai interrompere una scrittura a metà
    if rc != 0:
        fastboot("reboot")
        raise Stop("Flash non riuscito:\n" + out)
    ok("Scrittura completata.")
    fastboot("reboot")


def flash_patch(patchata):
    titolo("6/7  Installazione del root")
    info("Ora la console si riavvierà due volte. Non scollegare il cavo.")
    if not chiedi_si("Procedo con il flash dell'immagine patchata?"):
        raise Stop("Annullato: nessuna modifica fatta alla console.")
    flash(patchata, "l'immagine con Magisk")


# --------------------------------------------------------------------------
# 7. Verifica e modulo
# --------------------------------------------------------------------------
def verifica_e_modulo():
    titolo("7/7  Verifica del root")
    info("Attendo il riavvio completo (fino a 5 minuti)...")
    try:
        attendi_avvio(300)
    except Stop:
        raise Stop("La console non si è avviata come previsto.\n"
                   "  Per tornare com'eri: rimettila in modalità fastboot (come quando hai installato\n"
                   "  LineageOS), collegala e lancia:  python root_odin2portal.py --ripristina")
    ok("Console avviata.")
    shell("monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1" % MAGISK_PKG)
    info("Se Magisk chiede 'Configurazione aggiuntiva', tocca OK: la console si riavvierà.")
    info("Ora sulla console comparirà la richiesta di accesso root per 'Shell': tocca CONSENTI.")
    rooted = False
    for _ in range(40):
        out = shell("su -c id", timeout=20)
        if "uid=0" in out:
            rooted = True
            break
        if not shell("getprop sys.boot_completed"):
            attendi_avvio(300)
        time.sleep(4)
    if not rooted:
        attenzione("Root non confermato da qui. Apri Magisk: se mostra la versione installata, il root c'è.")
        return
    ok("ROOT ATTIVO.")
    moduli = sorted(glob.glob(os.path.join(BASE, "Odin2PortalTweaks-*.zip")))
    if moduli and chiedi_si("Installo anche il modulo %s?" % os.path.basename(moduli[-1]), default_no=False):
        remoto = "/sdcard/Download/" + os.path.basename(moduli[-1])
        adb("push", moduli[-1], remoto, controlla=True)
        out = shell("su -c 'magisk --install-module \"%s\"'" % remoto, timeout=180)
        print("\n".join("    " + l for l in out.splitlines()[-8:]))
        if "Done" in out or "Fatto" in out or "Riavvia" in out:
            ok("Modulo installato.")
            if chiedi_si("Riavvio ora la console per attivarlo?", default_no=False):
                adb("reboot")
        else:
            attenzione("Installazione del modulo non confermata: installalo dall'app Magisk > Moduli.")


# --------------------------------------------------------------------------
# Kernel su misura: prima un avvio di prova che non scrive nulla, poi (solo se va) l'installazione.
# Le immagini le prepara sulla console il modulo v4.22 (comando firmato kernel_prova).
# --------------------------------------------------------------------------
KP_ODIN = "/sdcard/Download/Odin2_report"
KP = os.path.join(LAVORO, "kernel")
SPEGNI = ("tieni premuto il tasto di accensione finché la console si riavvia:\n"
          "  riparte con il kernel originale, perché non è stato scritto nulla.")


def leggi_prova(testo):
    """Dati di kernel_prova.txt scritto dal modulo; None se l'immagine di prova non è pronta."""
    imm = dict(re.findall(r"^(boot_\w+\.img): \d+ byte \| sha256 ([0-9a-f]{64})$", testo, re.M))
    m = re.search(r"^sistema: (\S+)", testo, re.M)
    if "IMMAGINE DI PROVA PRONTA" not in testo or set(imm) != {"boot_prova.img", "boot_originale.img"} or not m:
        return None
    imm["sistema"] = m.group(1)
    return imm


def rapporto_completo(testo, versione):
    """Il modulo scrive kernel_info.txt un pezzo alla volta: è finito quando c'è la sezione che segue i moduli rifiutati.
    (10/11/2026: letto a metà, risultavano "futex_waitv non c'è" e "3 moduli rifiutati" con un kernel che andava.)"""
    return versione in testo.split("\n", 1)[0] and "\n--- sistema" in testo


def esito_prova(prima, dopo):
    """Confronta kernel_info.txt del kernel originale e di quello nuovo: lista dei problemi (vuota = va bene)."""
    def moduli(t):
        m = re.search(r"^caricati: (\d+)", t, re.M)
        return int(m.group(1)) if m else None
    guai = []
    a, b = moduli(prima), moduli(dopo)
    if a is None or b is None:
        guai.append("numero dei moduli caricati non leggibile")
    elif b < a - 5:
        guai.append("moduli caricati: %d, con il kernel originale %d" % (b, a))
    rif = dopo.split("--- moduli rifiutati dal kernel", 1)[-1].split("\n---", 1)[0].splitlines()[1:]
    if [r for r in rif if r.strip()]:
        guai.append("moduli rifiutati dal kernel: %d" % len([r for r in rif if r.strip()]))
    return guai


def prova_kernel():
    prop = controlla_dispositivo()
    titolo("Kernel su misura: avvio di prova (non scrive nulla sulla console)")
    os.makedirs(KP, exist_ok=True)
    rapporto = os.path.join(KP, "kernel_prova.txt")
    if os.path.exists(rapporto):
        os.remove(rapporto)
    adb("pull", KP_ODIN + "/kernel_prova.txt", rapporto, timeout=60)
    dati = leggi_prova(open(rapporto, encoding="utf-8", errors="replace").read()) if os.path.exists(rapporto) else None
    if not dati:
        raise Stop("Sulla console l'immagine di prova non è pronta: chiedi a Claude di prepararla.")
    if dati["sistema"] != prop["ro.lineage.version"]:
        raise Stop("LineageOS è cambiato dopo la preparazione dell'immagine di prova (era %s, ora %s):\n"
                   "  va preparata di nuovo, chiedilo a Claude. Nessuna modifica fatta."
                   % (dati["sistema"], prop["ro.lineage.version"]))
    info("Copio le due immagini dalla console al PC (circa 200 MB)...")
    for n in ("boot_originale.img", "boot_prova.img"):
        adb("pull", "%s/kernel_prova/%s" % (KP_ODIN, n), os.path.join(KP, n), timeout=900, controlla=True)
        if file_sha256(os.path.join(KP, n)) != dati[n]:
            raise Stop("%s è arrivata diversa dall'originale: riprova. Nessuna modifica fatta." % n)
    ok("Immagini copiate e verificate in " + KP)
    v_prima, info_prima = shell("uname -v"), shell("cat %s/kernel_info.txt" % KP_ODIN)
    info("Ora la console si riavvia e parte UNA volta con il kernel nuovo, senza scriverlo.")
    info("Se resta ferma: " + SPEGNI)
    if not chiedi_si("Procedo con l'avvio di prova?"):
        raise Stop("Annullato: nessuna modifica fatta alla console.")
    adb("reboot", "bootloader")
    attendi_fastboot()
    rc, out = fastboot("boot", os.path.join(KP, "boot_prova.img"), timeout=300)
    if rc != 0:
        fastboot("reboot")
        raise Stop("La console non accetta l'avvio di prova: si riavvia normale, nessuna modifica fatta.\n"
                   "  Riferisci a Claude:\n" + out.strip())
    info("Attendo l'avvio con il kernel nuovo (fino a 5 minuti)...")
    try:
        attendi_avvio(300)
    except Stop:
        raise Stop("Dal cavo non vedo la console avviata con il kernel nuovo.\n"
                   "  Se sullo schermo è accesa e funziona, il kernel è partito e si è perso solo il collegamento USB:\n"
                   "  riavviala normalmente (torna il kernel originale) e dillo a Claude.\n"
                   "  Se è ferma: " + SPEGNI + "\n  Poi dillo a Claude.")
    v_dopo = shell("uname -v")
    if not v_dopo or v_dopo == v_prima:
        raise Stop("La console è ripartita con il kernel di prima: l'avvio di prova non ha avuto effetto.\n"
                   "  Nessuna modifica fatta. Dillo a Claude.")
    ok("Console avviata con il kernel nuovo (%s)" % v_dopo)
    info("Attendo il rapporto del modulo (fino a 3 minuti)...")
    info_dopo, fine = "", time.time() + 180
    while time.time() < fine and not rapporto_completo(info_dopo, v_dopo):
        time.sleep(5)
        info_dopo = shell("cat %s/kernel_info.txt" % KP_ODIN)
    with open(os.path.join(KP, "kernel_info_prova.txt"), "w", encoding="utf-8") as f:
        f.write(info_dopo)
    guai = esito_prova(info_prima, info_dopo) if rapporto_completo(info_dopo, v_dopo) else ["rapporto del modulo non arrivato o incompleto"]
    if guai:
        for g in guai:
            attenzione(g)
        adb("reboot")
        raise Stop("Il kernel nuovo non va bene: la console si riavvia con quello originale, nessuna modifica fatta.\n"
                   "  Riferisci a Claude le righe con [!] qui sopra.")
    ok("Moduli del sistema caricati, nessuno rifiutato.")
    # che futex_waitv sia nel kernel lo garantisce la compilazione (controllo su System.map): qui è solo un'informazione
    info("futex_waitv nel rapporto: " + ("presente" if "sys_futex_waitv" in info_dopo else "non visto (lo controlla Claude)"))
    info("Prova la console per un minuto: schermo, tocco, tasti, Wi-Fi, audio.")
    if not chiedi_si("Funziona tutto? Rendo definitivo il kernel nuovo?"):
        adb("reboot")
        raise Stop("Va bene: la console si riavvia con il kernel originale. Nessuna modifica fatta.")
    flash(os.path.join(KP, "boot_prova.img"), "il kernel nuovo", "boot")
    try:
        attendi_avvio(300)
    except Stop:
        raise Stop("Dopo l'installazione la console non si è avviata.\n  Mettila in modalità fastboot (come quando hai "
                   "installato LineageOS), collegala e lancia RIPRISTINA_KERNEL_WINDOWS.bat")
    ok("Kernel nuovo installato. Per tornare all'originale: RIPRISTINA_KERNEL_WINDOWS.bat")
    info("A ogni aggiornamento di LineageOS torna il kernel originale: va rifatto.")


def ripristina_kernel():
    titolo("Ripristino del kernel originale")
    img, rapporto = os.path.join(KP, "boot_originale.img"), os.path.join(KP, "kernel_prova.txt")
    dati = leggi_prova(open(rapporto, encoding="utf-8", errors="replace").read()) if os.path.exists(rapporto) else None
    if not dati or not os.path.isfile(img) or file_sha256(img) != dati["boot_originale.img"]:
        raise Stop("Non trovo una copia valida del kernel originale in " + KP)
    _, out = adb("devices")
    in_adb = any(l.split()[-1] == "device" for l in out.splitlines()[1:] if l.strip())
    if in_adb and shell("getprop ro.lineage.version") != dati["sistema"]:
        raise Stop("La copia è di LineageOS %s, sulla console ora c'è un'altra versione: non la scrivo." % dati["sistema"])
    attenzione("Questa copia è di LineageOS %s: va bene solo se sulla console c'è quella versione." % dati["sistema"])
    if not chiedi_si("Rimetto il kernel originale?"):
        raise Stop("Annullato.")
    if in_adb:
        flash(img, "il kernel originale", "boot")
    else:
        info("Metti la console in modalità fastboot (come quando hai installato LineageOS) e collegala.")
        attendi_fastboot()
        rc, o = fastboot("flash", "boot", img, timeout=600)
        if rc != 0:
            raise Stop("Scrittura non riuscita:\n" + o)
        fastboot("reboot")
    ok("Kernel originale ripristinato. La console si riavvia.")


# --------------------------------------------------------------------------
# Ripristino
# --------------------------------------------------------------------------
def ripristina():
    titolo("Ripristino dell'init_boot originale")
    candidati = sorted(glob.glob(os.path.join(LAVORO, "build-*", "init_boot_originale.img")))
    if not candidati:
        raise Stop("Nessun backup trovato in " + LAVORO)
    _, out = adb("devices")
    in_adb = any(l.split()[-1] == "device" for l in out.splitlines()[1:] if l.strip())
    img = None
    if in_adb:
        v, d = parse_versione(shell("getprop ro.lineage.version"))
        cerca = os.path.join(LAVORO, "build-%s-%s" % (v, d), "init_boot_originale.img")
        img = cerca if os.path.exists(cerca) else None
        if not img:
            raise Stop("Non ho l'originale della build installata (%s %s)." % (v, d))
    else:
        img = candidati[-1]
        attenzione("Console non in modalità normale: userò " + img)
        attenzione("Deve essere della stessa build di LineageOS installata sulla console!")
    if not immagine_valida(img):
        raise Stop("Backup non valido.")
    if not chiedi_si("Rimetto l'immagine originale (toglie il root)?"):
        raise Stop("Annullato.")
    if in_adb:
        flash(img, "l'immagine originale")
    else:
        attendi_fastboot()
        rc, o = fastboot("flash", PARTIZIONE, img, timeout=120)
        if rc != 0:
            raise Stop("Flash non riuscito:\n" + o)
        fastboot("reboot")
    ok("Originale ripristinato. La console si riavvia senza root.")


# --------------------------------------------------------------------------
def main():
    for flusso in (sys.stdout, sys.stderr):
        try:
            flusso.reconfigure(errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="Root automatico con Magisk per Odin 2 Portal (LineageOS)")
    ap.add_argument("--ripristina", action="store_true", help="rimette l'init_boot originale")
    ap.add_argument("--zip", help="pacchetto LineageOS già scaricato (stessa build installata)")
    ap.add_argument("--kernel", action="store_true", help="avvio di prova del kernel su misura, poi installazione")
    ap.add_argument("--ripristina-kernel", action="store_true", help="rimette il kernel originale")
    a = ap.parse_args()
    print("\nOdin 2 Portal Root Helper v%s  -  LineageOS + Magisk" % VERSIONE)
    try:
        prepara_strumenti()
        if a.ripristina:
            ripristina()
            return 0
        if a.kernel or a.ripristina_kernel:
            (prova_kernel if a.kernel else ripristina_kernel)()
            return 0
        info("")
        info("Questo programma installa il root (Magisk) sulla tua console.")
        info("Non cancella i tuoi dati e conserva l'originale per poter tornare indietro.")
        info("Dopo ogni aggiornamento di LineageOS il root va reinstallato: rilancia il programma.")
        if not chiedi_si("Vuoi continuare?", default_no=False):
            return 0
        prop = controlla_dispositivo()
        originale, cartella = prendi_init_boot(prop, a.zip)
        installa_magisk(cartella)
        patchata = patch_con_magisk(originale, cartella)
        flash_patch(patchata)
        verifica_e_modulo()
        titolo("FINITO")
        info("Backup dell'originale: " + originale)
        info("Per togliere il root:  python root_odin2portal.py --ripristina")
        return 0
    except Stop as e:
        print("\n  [STOP] " + str(e) + "\n")
        try:
            os.makedirs(LAVORO, exist_ok=True)
            with open(os.path.join(LAVORO, "registro.txt"), "a", encoding="utf-8") as f:
                f.write(time.strftime("\n[%Y-%m-%d %H:%M] STOP: ") + str(e) + "\n")
        except OSError:
            pass
        return 1
    except KeyboardInterrupt:
        print("\n  Interrotto. Se eri a metà del flash, rilancia il programma o usa --ripristina.\n")
        return 1
    except Exception:
        import traceback
        dettaglio = traceback.format_exc()
        try:
            os.makedirs(LAVORO, exist_ok=True)
            with open(os.path.join(LAVORO, "registro.txt"), "a", encoding="utf-8") as f:
                f.write(time.strftime("\n[%Y-%m-%d %H:%M]\n") + dettaglio)
        except OSError:
            pass
        print("\n  [ERRORE IMPREVISTO] Nessuna modifica è stata fatta alla console se non eri al passo 6.")
        print("  Mandami il contenuto del file odin2portal_root\\registro.txt oppure queste righe:\n")
        print("\n".join("    " + l for l in dettaglio.strip().splitlines()[-6:]))
        return 1


if __name__ == "__main__":
    sys.exit(main())
