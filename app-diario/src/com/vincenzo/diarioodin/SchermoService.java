package com.vincenzo.diarioodin;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Legge tutto ciò che compare a schermo e gli eventi di sistema, e li passa al Diario. */
public class SchermoService extends AccessibilityService {
    private static final int MAX_RIGA = 200, MAX_TESTO = 4000;

    private Diario d;
    private Radice radice;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Set<String> ultimeRighe = new LinkedHashSet<>();
    private String ultimaApp = "";
    private boolean completa;
    private CharSequence testoInScrittura;
    private String appInScrittura;
    private ConnectivityManager.NetworkCallback rete;

    private final Runnable leggiSchermo = new Runnable() {
        public void run() { istantanea(); }
    };
    private final Runnable fineScrittura = new Runnable() {
        public void run() {
            if (testoInScrittura != null) d.evento(d.chi(), "SCRITTO", appInScrittura, taglia(testoInScrittura.toString(), 1000));
            testoInScrittura = null;
        }
    };

    private final BroadcastReceiver sistema = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try { ricevi(i); } catch (Exception e) { d.errore("evento di sistema " + i.getAction(), e); }
        }
    };

    @Override
    protected void onServiceConnected() {
        d = Diario.get(this);
        d.evento("APP", "SERVIZIO", null, "lettura dello schermo attiva (Diario " + d.versione() + ")");
        IntentFilter f = new IntentFilter();
        for (String a : new String[]{Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT,
                Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_SHUTDOWN, Intent.ACTION_AIRPLANE_MODE_CHANGED,
                Intent.ACTION_HEADSET_PLUG, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED,
                "android.bluetooth.device.action.ACL_CONNECTED", "android.bluetooth.device.action.ACL_DISCONNECTED",
                "android.media.VOLUME_CHANGED_ACTION", "android.intent.action.DOCK_EVENT"})
            f.addAction(a);
        registra(sistema, f);
        IntentFilter p = new IntentFilter();
        p.addAction(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_REPLACED);
        p.addDataScheme("package");
        registra(sistema, p);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            rete = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    String t = c == null ? "rete" : c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                            : c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "rete mobile"
                            : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "rete";
                    d.evento(d.chi(), "RETE", null, "connessa: " + t);
                }
                @Override public void onLost(Network n) { d.evento(d.chi(), "RETE", null, "connessione persa"); }
            };
            cm.registerDefaultNetworkCallback(rete);
        } catch (Exception e) { d.errore("controllo della rete", e); }
        radice = new Radice(d);
        radice.avvia();
        d.schermo(true);
    }

    private void registra(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    private void ricevi(Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case Intent.ACTION_SCREEN_ON: d.schermo(true); break;
            case Intent.ACTION_SCREEN_OFF: d.schermo(false); break;
            case Intent.ACTION_USER_PRESENT: d.evento("TU", "SBLOCCO", null, "schermo sbloccato"); break;
            case Intent.ACTION_SHUTDOWN: d.evento(d.chi(), "SPEGNI", null, "la Odin si sta spegnendo o riavviando");
                d.chiudiSessione("spegnimento o riavvio della Odin"); break;
            case Intent.ACTION_BATTERY_CHANGED: {
                int liv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (liv >= 0 && scala > 0) d.batteria(liv * 100 / scala, i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
                        st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                break;
            }
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                d.evento(d.chi(), "AEREO", null, i.getBooleanExtra("state", false) ? "modalità aereo attiva" : "modalità aereo spenta"); break;
            case Intent.ACTION_HEADSET_PLUG:
                d.evento(d.chi(), "AUDIO", null, i.getIntExtra("state", 0) == 1 ? "cuffie collegate" : "cuffie scollegate"); break;
            case "android.media.VOLUME_CHANGED_ACTION":
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == 3) // solo il volume multimediale
                    d.evento(d.chi(), "VOLUME", null, "volume multimediale " + i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1));
                break;
            case "android.bluetooth.device.action.ACL_CONNECTED": d.evento(d.chi(), "BLUETOOTH", null, "dispositivo collegato"); break;
            case "android.bluetooth.device.action.ACL_DISCONNECTED": d.evento(d.chi(), "BLUETOOTH", null, "dispositivo scollegato"); break;
            case Intent.ACTION_PACKAGE_ADDED: case Intent.ACTION_PACKAGE_REMOVED: case Intent.ACTION_PACKAGE_REPLACED: {
                String pkg = i.getData() == null ? "?" : i.getData().getSchemeSpecificPart();
                boolean agg = i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (agg && !Intent.ACTION_PACKAGE_REPLACED.equals(a)) break; // un aggiornamento manda anche ADDED/REMOVED
                String cosa = Intent.ACTION_PACKAGE_ADDED.equals(a) ? "installata" : Intent.ACTION_PACKAGE_REMOVED.equals(a) ? "disinstallata" : "aggiornata";
                d.evento(d.chi(), "PACCHETTO", pkg, "app " + cosa);
                break;
            }
            default: d.evento(d.chi(), "SISTEMA", null, a);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (d != null) d.evento(d.chi(), "SCHERMO", null, c.orientation == Configuration.ORIENTATION_PORTRAIT ? "verticale" : "orizzontale");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (d == null) return;
        try { evento(e); } catch (Exception x) { d.errore("lettura evento " + AccessibilityEvent.eventTypeToString(e.getEventType()), x); }
    }

    private void evento(AccessibilityEvent e) {
        String pkg = e.getPackageName() == null ? null : e.getPackageName().toString();
        switch (e.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                if (pkg == null) break;
                CharSequence cls = e.getClassName();
                if (isToast(cls)) { d.evento("ODIN", "TOAST", pkg, testo(e)); break; }
                if (!pkg.equals(ultimaApp)) {
                    ultimaApp = pkg;
                    d.appInPrimoPiano(pkg, cls == null ? null : cls.toString());
                } else {
                    String t = testo(e);
                    d.evento(d.chi(), "FINESTRA", pkg, (cls == null ? "" : cls + " ") + (t.isEmpty() ? "" : "« " + t + " »"));
                }
                completa = true;
                rileggi(400);
                break;
            }
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                completa = true;
                rileggi(400);
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                rileggi(1500);
                break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                d.evento(chiTocco(), "TOCCO", pkg, descrivi(e)); break;
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED:
                d.evento(chiTocco(), "TOCCO LUNGO", pkg, descrivi(e)); break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                d.evento(d.chi(), "SELEZIONE", pkg, descrivi(e)); break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                // si registra il testo finale, dopo 1,5 s senza digitare
                testoInScrittura = (e.isPassword() ? "(campo password) " : "") + testo(e);
                appInScrittura = pkg;
                h.removeCallbacks(fineScrittura);
                h.postDelayed(fineScrittura, 1500);
                break;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                if (isToast(e.getClassName())) d.evento("ODIN", "TOAST", pkg, testo(e));
                break;
            case AccessibilityEvent.TYPE_ANNOUNCEMENT:
                d.evento("ODIN", "ANNUNCIO", pkg, testo(e)); break;
            default: break; // scorrimenti, selezioni di testo ecc.: troppo frequenti, il testo nuovo arriva comunque dall'istantanea
        }
    }

    /** Senza root non si leggono gli input reali: un tocco su un pulsante è quasi sempre tuo. */
    private String chiTocco() { return d.rootInput ? d.chi() : "TU"; }

    private static boolean isToast(CharSequence cls) { return cls != null && cls.toString().contains("Toast"); }

    private void rileggi(long ritardo) {
        h.removeCallbacks(leggiSchermo);
        h.postDelayed(leggiSchermo, ritardo);
    }

    /** Legge il testo di tutte le finestre visibili. Alla nuova schermata scrive tutto, poi solo le righe nuove. */
    private void istantanea() {
        Set<String> righe = new LinkedHashSet<>();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) raccogli(r, righe, 0);
            }
        } catch (Exception e) {
            d.errore("lettura dello schermo", e);
            return;
        }
        Set<String> nuove = new LinkedHashSet<>(righe);
        if (!completa) nuove.removeAll(ultimeRighe);
        ultimeRighe = righe;
        boolean tutta = completa;
        completa = false;
        if (nuove.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        for (String s : nuove) {
            if (b.length() > 0) b.append(" · ");
            b.append(s);
            if (b.length() > MAX_TESTO) { b.setLength(MAX_TESTO); b.append(" …"); break; }
        }
        d.evento(d.chi(), tutta ? "SCHERMATA" : "TESTO", ultimaApp, b.toString());
    }

    private void raccogli(AccessibilityNodeInfo n, Set<String> righe, int prof) {
        if (prof > 60 || righe.size() > 400) return;
        if (n.isVisibleToUser()) {
            CharSequence t = n.getText(), c = n.getContentDescription();
            if (t != null && t.length() > 0) righe.add(taglia(t.toString(), MAX_RIGA));
            else if (c != null && c.length() > 0) righe.add("[" + taglia(c.toString(), MAX_RIGA) + "]");
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = n.getChild(i);
            if (f != null) raccogli(f, righe, prof + 1);
        }
    }

    private static String testo(AccessibilityEvent e) {
        StringBuilder b = new StringBuilder();
        for (CharSequence s : e.getText()) if (s != null) { if (b.length() > 0) b.append(' '); b.append(s); }
        if (b.length() == 0 && e.getContentDescription() != null) b.append(e.getContentDescription());
        return taglia(b.toString(), 1000);
    }

    private static String descrivi(AccessibilityEvent e) {
        String t = testo(e);
        AccessibilityNodeInfo n = e.getSource();
        String id = n == null ? null : n.getViewIdResourceName();
        if (t.isEmpty() && id == null) t = e.getClassName() == null ? "?" : e.getClassName().toString();
        return t + (id != null ? "  #" + id.substring(id.indexOf('/') + 1) : "");
    }

    private static String taglia(String s, int max) {
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (d != null) {
            d.evento("APP", "SERVIZIO", null, "lettura dello schermo fermata");
            d.chiudiSessione("lettura dello schermo disattivata (Accessibilità spenta o app aggiornata)");
            try { unregisterReceiver(sistema); } catch (Exception ignored) { }
            try { if (rete != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(rete); } catch (Exception ignored) { }
            if (radice != null) radice.ferma();
        }
        super.onDestroy();
    }
}
