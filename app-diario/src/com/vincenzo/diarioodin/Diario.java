package com.vincenzo.diarioodin;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Registro centrale. Tutto lo stato vive sul thread "diario": i metodi pubblici
 * accodano soltanto, quindi possono essere chiamati da qualsiasi thread.
 *
 * File prodotti in Download/Odin2_report:
 *   schermo_AAAAMMGG_HHMM.txt   un report per sessione (dall'accensione allo standby lungo)
 *   schermo_giorno_AAAAMMGG.txt riepilogo del giorno, riscritto a ogni fine sessione
 * Il nome inizia con "schermo_" così la pulizia dei report di partita del modulo non li tocca.
 */
public final class Diario {
    static final String CARTELLA = "Odin2_report";
    static final long STANDBY_LUNGO_MS = 5 * 60 * 1000L;
    static final long FINESTRA_INPUT_MS = 2000L;
    static final int GIORNI_TENUTI = 30;

    private static Diario io;

    final Context ctx;
    private final Handler h;
    private final File live, meta;
    private Writer out;

    // stato di sessione (solo thread diario)
    private long inizio, schermoAccesoDa, schermoMs;
    private int battInizio = -1, battUltima = -1, battLog = -1;
    private float tempMax;
    private boolean inCarica, schermoAcceso = true;
    private String appCorrente, appUltima;
    private long appDa;
    private final Map<String, Long> tempoApp = new HashMap<>();
    private final Map<String, Integer> notifiche = new HashMap<>();
    private final List<String> morti = new ArrayList<>();
    private final List<String> erroriApp = new ArrayList<>();
    private int azioniTu, azioniOdin;
    // aggiornati dai thread root: solo valori semplici
    volatile long ultimoInput;
    volatile int tocchi, tasti;
    volatile boolean rootInput, rootLog, ascoltoNotifiche;

    private final Runnable chiusuraStandby = new Runnable() {
        public void run() { if (inizio != 0) fine("standby lungo (schermo spento da 5 minuti)"); }
    };
    private final Runnable svuota = new Runnable() {
        public void run() { flush(); h.postDelayed(this, 5000); }
    };

    private Diario(Context c) {
        ctx = c.getApplicationContext();
        HandlerThread t = new HandlerThread("diario");
        t.start();
        h = new Handler(t.getLooper());
        live = new File(ctx.getFilesDir(), "sessione.log");
        meta = new File(ctx.getFilesDir(), "sessione.meta");
        h.post(new Runnable() { public void run() { avvio(); } });
        h.postDelayed(svuota, 5000);
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable e) {
                // scrittura diretta: il thread diario potrebbe non girare più.
                // Al prossimo avvio la sessione rimasta aperta diventa un report "interrotta".
                flush();
                try (Writer w = new FileWriter(live, true)) {
                    w.write(ora() + " [APP ] CRASH     dell'app Diario: " + traccia(e) + "\n");
                } catch (Exception ignored) { }
                if (def != null) def.uncaughtException(th, e);
            }
        });
    }

    static synchronized Diario get(Context c) {
        if (io == null) io = new Diario(c);
        return io;
    }

    // ---------- API pubblica (thread qualsiasi) ----------

    /** Chi ha causato l'evento: TU se c'è stato un tuo input negli ultimi 2 secondi. */
    String chi() {
        return System.currentTimeMillis() - ultimoInput < FINESTRA_INPUT_MS ? "TU" : "ODIN";
    }

    void input() { ultimoInput = System.currentTimeMillis(); }

    void evento(final String chi, final String tipo, final String app, final String testo) {
        final long t = System.currentTimeMillis();
        h.post(new Runnable() { public void run() { scrivi(t, chi, tipo, app, testo); } });
    }

    void appInPrimoPiano(final String pkg, final String classe) {
        final String c = chi();
        final long t = System.currentTimeMillis();
        h.post(new Runnable() {
            public void run() {
                if (pkg.equals(appCorrente)) return;
                accumulaApp();
                appCorrente = pkg; appUltima = pkg; appDa = t;
                scrivi(t, c, "APP", pkg, nome(pkg) + (classe != null ? " (" + classe + ")" : ""));
            }
        });
    }

    void notifica(final String pkg) {
        h.post(new Runnable() { public void run() { piuI(notifiche, pkg, 1); } });
    }

    /** Crash, ANR o processo chiuso dal sistema. */
    void morte(final String testo) {
        final String s = ora() + " " + testo;
        h.post(new Runnable() { public void run() { if (morti.size() < 500) morti.add(s); } });
    }

    void errore(final String dove, final Throwable e) {
        final String s = dove + ": " + traccia(e);
        h.post(new Runnable() {
            public void run() {
                if (erroriApp.size() < 100) erroriApp.add(ora() + " " + s);
                scrivi(System.currentTimeMillis(), "APP", "ERRORE", null, s);
            }
        });
    }

    void schermo(final boolean acceso) {
        final String c = chi();
        final long t = System.currentTimeMillis();
        h.post(new Runnable() {
            public void run() {
                if (acceso) {
                    h.removeCallbacks(chiusuraStandby);
                    if (inizio == 0) nuovaSessione();
                    if (!schermoAcceso) { schermoAcceso = true; schermoAccesoDa = t; }
                    if (appCorrente == null && appUltima != null) { appCorrente = appUltima; appDa = t; }
                    scrivi(t, c, "SCHERMO", null, "acceso");
                } else {
                    if (inizio == 0) return;
                    accumulaApp(); appCorrente = null;
                    if (schermoAcceso) { schermoMs += t - schermoAccesoDa; schermoAcceso = false; }
                    scrivi(t, c, "SCHERMO", null, "spento");
                    h.removeCallbacks(chiusuraStandby);
                    h.postDelayed(chiusuraStandby, STANDBY_LUNGO_MS);
                }
            }
        });
    }

    void batteria(final int livello, final float temp, final boolean carica) {
        h.post(new Runnable() {
            public void run() {
                long t = System.currentTimeMillis();
                if (battInizio < 0) battInizio = livello;
                battUltima = livello;
                if (temp > tempMax) tempMax = temp;
                if (carica != inCarica) {
                    inCarica = carica;
                    scrivi(t, "ODIN", "BATTERIA", null, (carica ? "in carica" : "scollegata dalla carica") + ", " + livello + "%");
                }
                if (battLog < 0 || Math.abs(livello - battLog) >= 5) {
                    battLog = livello;
                    scrivi(t, "ODIN", "BATTERIA", null, livello + "%, " + temp + " °C");
                }
            }
        });
    }

    void chiudiSessione(final String motivo) {
        h.post(new Runnable() { public void run() { if (inizio != 0) fine(motivo); } });
    }

    /** Chiude la sessione, scrive il report e ne apre subito una nuova (pulsante nell'app). */
    void reportOra() {
        h.post(new Runnable() {
            public void run() {
                if (inizio != 0) fine("chiusa a mano dall'app Diario");
                nuovaSessione();
            }
        });
    }

    // ---------- thread diario ----------

    private void avvio() {
        if (live.length() > 0) {
            // sessione rimasta aperta: app chiusa dal sistema, crash o riavvio
            Properties p = leggi(meta);
            inizio = num(p.getProperty("inizio"), live.lastModified());
            battInizio = (int) num(p.getProperty("batt"), -1);
            fine("interrotta: l'app o la Odin si sono chiuse senza avviso (riavvio, crash o batteria scarica)");
        }
        nuovaSessione();
    }

    private void nuovaSessione() {
        long t = System.currentTimeMillis();
        inizio = t; schermoAccesoDa = t; schermoMs = 0; schermoAcceso = true;
        battInizio = battUltima; tempMax = 0; battLog = -1;
        tempoApp.clear(); notifiche.clear(); morti.clear(); erroriApp.clear();
        azioniTu = 0; azioniOdin = 0; tocchi = 0; tasti = 0;
        if (appUltima != null) { appCorrente = appUltima; appDa = t; }
        Properties p = new Properties();
        p.setProperty("inizio", "" + t);
        p.setProperty("batt", "" + battInizio);
        salva(p, meta);
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(live, true), "UTF-8"));
        } catch (Exception e) { out = null; }
        scrivi(t, "ODIN", "SESSIONE", null, "inizio");
    }

    private void scrivi(long t, String chi, String tipo, String app, String testo) {
        if ("TU".equals(chi)) azioniTu++; else if ("ODIN".equals(chi)) azioniOdin++;
        if (out == null) return;
        StringBuilder b = new StringBuilder(64 + (testo == null ? 0 : testo.length()));
        b.append(fmt("HH:mm:ss.SSS", t)).append(" [").append(pad(chi, 4)).append("] ").append(pad(tipo, 9));
        if (app != null) b.append(' ').append(app).append(" |");
        if (testo != null) b.append(' ').append(testo.replace('\n', ' '));
        b.append('\n');
        try { out.write(b.toString()); } catch (Exception e) { out = null; }
    }

    private void flush() { try { if (out != null) out.flush(); } catch (Exception ignored) { } }

    private void accumulaApp() {
        long t = System.currentTimeMillis();
        if (appCorrente != null && schermoAcceso) piuL(tempoApp, appCorrente, t - appDa);
        appDa = t;
    }

    private void fine(String motivo) {
        long t = System.currentTimeMillis();
        accumulaApp();
        long schermoTot = schermoMs + (schermoAcceso ? t - schermoAccesoDa : 0);
        scrivi(t, "ODIN", "SESSIONE", null, "fine: " + motivo);
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        out = null;

        File dir = cartella();
        File rep = new File(dir, "schermo_" + fmt("yyyyMMdd_HHmm", inizio) + ".txt");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(rep), "UTF-8"))) {
            w.write("=== DIARIO ODIN — REPORT DI SESSIONE ===\n");
            w.write("Inizio: " + fmt("dd/MM/yyyy HH:mm:ss", inizio) + "\n");
            w.write("Fine:   " + fmt("dd/MM/yyyy HH:mm:ss", t) + "\n");
            w.write("Durata: " + durata(t - inizio) + "   Schermo acceso: " + durata(schermoTot) + "\n");
            w.write("Motivo della chiusura: " + motivo + "\n");
            if (battInizio >= 0 && battUltima >= 0) {
                int d = battInizio - battUltima;
                w.write("Batteria: " + battInizio + "% -> " + battUltima + "% ("
                        + (d >= 0 ? "-" + d : "+" + (-d)) + "%), temperatura max " + tempMax + " °C\n");
            }
            w.write("Azioni fatte da te: " + azioniTu + "   Azioni fatte dalla Odin: " + azioniOdin + "\n");
            w.write("Input letti: tocchi " + tocchi + ", tasti e pulsanti " + tasti + "\n");
            w.write("\n--- App a schermo (tempo) ---\n");
            for (Map.Entry<String, Long> e : ordinaL(tempoApp))
                w.write(String.format(Locale.ITALY, "%10s  %s (%s)%n", durata(e.getValue()), nome(e.getKey()), e.getKey()));
            w.write("\n--- Notifiche ricevute ---\n");
            for (Map.Entry<String, Integer> e : ordinaI(notifiche))
                w.write(String.format(Locale.ITALY, "%5d  %s (%s)%n", e.getValue(), nome(e.getKey()), e.getKey()));
            w.write("\n--- Crash, blocchi (ANR) e app chiuse dal sistema: " + morti.size() + " ---\n");
            for (String s : morti) w.write(s + "\n");
            w.write("\n--- Stato dell'app Diario ---\n");
            w.write("Versione: " + versione() + "\n");
            w.write("Root per input / log di sistema: " + (rootInput ? "sì" : "NO") + " / " + (rootLog ? "sì" : "NO") + "\n");
            w.write("Accesso alle notifiche: " + (ascoltoNotifiche ? "sì" : "NO") + "\n");
            w.write("Errori interni: " + erroriApp.size() + "\n");
            for (String s : erroriApp) w.write("  " + s + "\n");
            w.write("\n=== CRONOLOGIA COMPLETA ===\n");
            w.write("[TU  ] = causato da te (tocco, tasto o pulsante nei 2 secondi prima)\n");
            w.write("[ODIN] = fatto dalla console o da un'app da sola\n");
            w.write("[APP ] = messaggi dell'app Diario stessa\n\n");
            copia(live, w);
        } catch (Exception e) {
            erroriApp.add(ora() + " report non scritto: " + traccia(e));
        }
        aggiornaGiorno(t, schermoTot);
        pulisci(dir);
        live.delete();
        meta.delete();
        inizio = 0;
    }

    private void aggiornaGiorno(long t, long schermoTot) {
        // shortcut: una sessione a cavallo della mezzanotte va tutta al giorno d'inizio
        String g = fmt("yyyyMMdd", inizio);
        File f = new File(ctx.getFilesDir(), "giorno_" + g + ".properties");
        Properties p = leggi(f);
        add(p, "sessioni", 1);
        add(p, "schermo", schermoTot);
        add(p, "tu", azioniTu);
        add(p, "odin", azioniOdin);
        add(p, "tocchi", tocchi);
        add(p, "tasti", tasti);
        add(p, "morti", morti.size());
        add(p, "errori", erroriApp.size());
        if (battInizio >= 0 && battUltima >= 0 && battInizio > battUltima) add(p, "batteria", battInizio - battUltima);
        for (Map.Entry<String, Long> e : tempoApp.entrySet()) add(p, "app." + e.getKey(), e.getValue());
        for (Map.Entry<String, Integer> e : notifiche.entrySet()) add(p, "notif." + e.getKey(), e.getValue());
        p.setProperty("rep." + inizio, fmt("HH:mm", inizio) + "-" + fmt("HH:mm", t) + "  schermo_" + fmt("yyyyMMdd_HHmm", inizio) + ".txt");
        salva(p, f);

        Map<String, Long> app = new HashMap<>();
        Map<String, Integer> not = new HashMap<>();
        List<String> sess = new ArrayList<>();
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("app.")) app.put(k.substring(4), num(p.getProperty(k), 0));
            else if (k.startsWith("notif.")) not.put(k.substring(6), (int) num(p.getProperty(k), 0));
            else if (k.startsWith("rep.")) sess.add(p.getProperty(k));
        }
        Collections.sort(sess);
        File r = new File(cartella(), "schermo_giorno_" + g + ".txt");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(r), "UTF-8"))) {
            w.write("=== DIARIO ODIN — RIEPILOGO DEL " + fmt("dd/MM/yyyy", inizio) + " ===\n");
            w.write("Aggiornato alle " + fmt("HH:mm", t) + "\n");
            w.write("Sessioni: " + p.getProperty("sessioni") + "   Schermo acceso: " + durata(num(p.getProperty("schermo"), 0)) + "\n");
            w.write("Batteria consumata: " + p.getProperty("batteria", "0") + "%\n");
            w.write("Azioni tue: " + p.getProperty("tu") + "   Azioni della Odin: " + p.getProperty("odin") + "\n");
            w.write("Tocchi: " + p.getProperty("tocchi") + "   Tasti e pulsanti: " + p.getProperty("tasti") + "\n");
            w.write("Crash/ANR/chiusure del sistema: " + p.getProperty("morti") + "   Errori dell'app Diario: " + p.getProperty("errori") + "\n");
            w.write("\n--- App a schermo (tempo) ---\n");
            for (Map.Entry<String, Long> e : ordinaL(app))
                w.write(String.format(Locale.ITALY, "%10s  %s (%s)%n", durata(e.getValue()), nome(e.getKey()), e.getKey()));
            w.write("\n--- Notifiche ---\n");
            for (Map.Entry<String, Integer> e : ordinaI(not))
                w.write(String.format(Locale.ITALY, "%5d  %s (%s)%n", e.getValue(), nome(e.getKey()), e.getKey()));
            w.write("\n--- Sessioni (report completi) ---\n");
            for (String s : sess) w.write(s + "\n");
        } catch (Exception e) {
            erroriApp.add(ora() + " riepilogo non scritto: " + traccia(e));
        }
    }

    private void pulisci(File dir) {
        String limite = fmt("yyyyMMdd", System.currentTimeMillis() - GIORNI_TENUTI * 86400000L);
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            String d = n.startsWith("schermo_giorno_") ? n.substring(15) : n.startsWith("schermo_") ? n.substring(8) : null;
            if (d != null && d.length() >= 8 && d.substring(0, 8).compareTo(limite) < 0) f.delete();
        }
        File[] ps = ctx.getFilesDir().listFiles();
        if (ps != null) for (File f : ps) {
            String n = f.getName();
            if (n.startsWith("giorno_") && n.length() >= 15 && n.substring(7, 15).compareTo(limite) < 0) f.delete();
        }
    }

    File cartella() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CARTELLA);
        if (d.isDirectory() || d.mkdirs()) {
            File prova = new File(d, ".diario_prova");
            try { new FileOutputStream(prova).close(); prova.delete(); return d; } catch (Exception ignored) { }
        }
        // senza "accesso a tutti i file": cartella privata dell'app (Android/data/...)
        File p = ctx.getExternalFilesDir(null);
        return p != null ? p : ctx.getFilesDir();
    }

    // ---------- utilità ----------

    String versione() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    String nome(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return pkg; }
    }

    static String ora() { return fmt("HH:mm:ss", System.currentTimeMillis()); }

    static String fmt(String f, long t) { return new SimpleDateFormat(f, Locale.ITALY).format(new Date(t)); }

    static String durata(long ms) {
        long m = ms / 60000;
        if (m < 1) return (ms / 1000) + " s";
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    static String traccia(Throwable e) {
        StringWriter s = new StringWriter();
        e.printStackTrace(new PrintWriter(s));
        String r = s.toString().replace('\n', ' ').replace('\t', ' ');
        return r.length() > 600 ? r.substring(0, 600) : r;
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static void piuL(Map<String, Long> m, String k, long v) { Long o = m.get(k); m.put(k, (o == null ? 0 : o) + v); }

    private static void piuI(Map<String, Integer> m, String k, int v) { Integer o = m.get(k); m.put(k, (o == null ? 0 : o) + v); }

    private static List<Map.Entry<String, Long>> ordinaL(Map<String, Long> m) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, new java.util.Comparator<Map.Entry<String, Long>>() {
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) { return Long.compare(b.getValue(), a.getValue()); }
        });
        return l;
    }

    private static List<Map.Entry<String, Integer>> ordinaI(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, new java.util.Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return Integer.compare(b.getValue(), a.getValue()); }
        });
        return l;
    }

    private static long num(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static void add(Properties p, String k, long v) { p.setProperty(k, "" + (num(p.getProperty(k), 0) + v)); }

    private static Properties leggi(File f) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception ignored) { }
        return p;
    }

    private static void salva(Properties p, File f) {
        try (FileOutputStream o = new FileOutputStream(f)) { p.store(o, null); } catch (Exception ignored) { }
    }

    private static void copia(File f, Writer w) throws Exception {
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) w.write(buf, 0, n);
        }
    }
}
