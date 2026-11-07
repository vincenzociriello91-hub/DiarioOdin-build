package com.vincenzo.diarioodin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Letture via root, senza toccare nulla:
 *  - getevent: ogni tocco/tasto/pulsante reale segna "input tuo" (serve a separare TU da ODIN)
 *  - logcat events: crash, blocchi (ANR) e app chiuse dal sistema
 * Se il root manca o si interrompe, riprova dopo 60 secondi.
 */
final class Radice {
    private final Diario d;
    private volatile boolean attivo = true;
    private Process pInput, pLog;

    Radice(Diario d) { this.d = d; }

    void avvia() {
        new Thread(new Runnable() { public void run() { ciclo(true); } }, "diario-input").start();
        new Thread(new Runnable() { public void run() { ciclo(false); } }, "diario-log").start();
    }

    void ferma() {
        attivo = false;
        if (pInput != null) pInput.destroy();
        if (pLog != null) pLog.destroy();
    }

    private void ciclo(boolean input) {
        while (attivo) {
            try {
                if (input) leggiInput(); else leggiLog();
            } catch (Exception e) {
                d.errore(input ? "lettura input (root)" : "lettura log di sistema (root)", e);
            }
            if (input) d.rootInput = false; else d.rootLog = false;
            try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
        }
    }

    private Process su(String comando) throws Exception {
        Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
        OutputStream o = p.getOutputStream();
        o.write(("exec " + comando + "\n").getBytes("UTF-8"));
        o.flush();
        return p;
    }

    private void leggiInput() throws Exception {
        pInput = su("getevent -l");
        BufferedReader r = new BufferedReader(new InputStreamReader(pInput.getInputStream(), "UTF-8"));
        String l;
        boolean primo = true;
        while (attivo && (l = r.readLine()) != null) {
            if (primo) { primo = false; d.rootInput = true; }
            l = l.trim(); // getevent -l allinea i valori con spazi finali
            // shortcut: le levette analogiche (ABS_X/Y/RX/RY) non contano come input, perché alcuni
            // sensori usano gli stessi codici; tasti, touch e croce direzionale bastano a capire chi agisce
            if (l.contains("EV_KEY") && l.endsWith("DOWN")) {
                if (l.contains("BTN_TOUCH")) { d.tocchi++; d.input(); continue; }
                d.tasti++; d.input();
                String k = tasto(l);
                if (k != null) d.evento("TU", "TASTO", null, k);
            } else if (l.contains("ABS_MT_TRACKING_ID") && !l.endsWith("ffffffff")) {
                d.input();
            } else if (l.contains("ABS_HAT0") && !l.endsWith(" 00000000")) {
                d.input();
            }
        }
        pInput.destroy();
    }

    /** Solo i tasti di sistema finiscono nella cronologia; i pulsanti di gioco si contano soltanto. */
    private static String tasto(String l) {
        String[] nomi = {"KEY_POWER", "KEY_VOLUMEUP", "KEY_VOLUMEDOWN", "KEY_BACK", "KEY_HOMEPAGE", "KEY_HOME",
                "KEY_MENU", "KEY_APPSELECT", "BTN_MODE", "BTN_SELECT", "BTN_START"};
        for (String n : nomi) if (l.contains(n + " ") || l.contains(n + "\t")) return n;
        return null;
    }

    private void leggiLog() throws Exception {
        pLog = su("logcat -b events -v time -T 1 am_crash:I am_anr:I am_kill:I am_proc_died:I am_low_memory:I *:S");
        BufferedReader r = new BufferedReader(new InputStreamReader(pLog.getInputStream(), "UTF-8"));
        String l;
        boolean primo = true;
        while (attivo && (l = r.readLine()) != null) {
            if (primo) { primo = false; d.rootLog = true; }
            String tipo = l.contains("am_crash") ? "CRASH" : l.contains("am_anr") ? "BLOCCO" : l.contains("am_kill") ? "CHIUSA"
                    : l.contains("am_low_memory") ? "MEMORIA" : l.contains("am_proc_died") ? "MORTA" : null;
            if (tipo == null) continue;
            String testo = l.length() > 400 ? l.substring(0, 400) : l;
            int i = testo.indexOf(": [");
            if (i > 0) testo = testo.substring(i + 2);
            // campi: [utente,pid,processo,...]; am_kill ha il motivo al 5° posto
            String[] c = testo.replace("[", "").replace("]", "").split(",");
            String chi = "ODIN";
            if (c.length >= 3) {
                String pkg = c[2].split(":")[0];
                String perche = "";
                if ("CHIUSA".equals(tipo) && c.length >= 5) {
                    String m = c[4].trim();
                    if (m.equals("remove task")) { perche = "tolta dalle app recenti"; chi = d.chi(); }
                    else if (m.startsWith("empty") || m.startsWith("cached")) perche = "chiusa per liberare memoria (era in sottofondo)";
                    else if (m.contains("lmk") || m.contains("low")) perche = "chiusa per memoria scarsa";
                    else if (m.contains("stop") || m.contains("force")) { perche = "forzata la chiusura"; chi = d.chi(); }
                    else perche = m;
                } else if ("CRASH".equals(tipo) && c.length >= 6) perche = c[4].trim() + ": " + c[5].trim();
                else if ("BLOCCO".equals(tipo)) perche = "non risponde (ANR)";
                else if ("MORTA".equals(tipo)) perche = "processo terminato";
                testo = d.nome(pkg) + " (" + c[2] + ")" + (perche.isEmpty() ? "" : ": " + perche);
            }
            // am_proc_died è solo cronologia (succede di continuo ai processi in cache); il resto va anche nel riepilogo
            if (!"MORTA".equals(tipo)) d.morte(tipo + " " + testo);
            d.evento(chi, tipo, null, testo);
        }
        pLog.destroy();
    }
}
