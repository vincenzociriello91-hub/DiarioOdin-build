package com.vincenzo.diarioodin;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/** Registra notifiche arrivate, aperte e scartate. */
public class NotificheService extends NotificationListenerService {
    private Diario d;
    private final Map<String, String> ultime = new HashMap<>();

    @Override
    public void onListenerConnected() {
        d = Diario.get(this);
        d.ascoltoNotifiche = true;
        d.evento("APP", "SERVIZIO", null, "lettura delle notifiche attiva");
    }

    @Override
    public void onListenerDisconnected() {
        if (d == null) return;
        d.ascoltoNotifiche = false;
        d.evento("APP", "SERVIZIO", null, "lettura delle notifiche fermata");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (d == null) return;
        try {
            String t = testo(sbn);
            // le notifiche in corso (musica, download) si aggiornano di continuo: solo i cambi di testo
            if (t.equals(ultime.get(sbn.getKey()))) return;
            boolean nuova = !ultime.containsKey(sbn.getKey());
            ultime.put(sbn.getKey(), t);
            if (ultime.size() > 500) ultime.clear();
            if (nuova) d.notifica(sbn.getPackageName());
            d.evento("ODIN", nuova ? "NOTIFICA" : "NOTIFICA+", sbn.getPackageName(), t);
        } catch (Exception e) { d.errore("lettura notifica", e); }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap r, int motivo) {
        if (d == null) return;
        String t = ultime.remove(sbn.getKey());
        String chi, cosa;
        switch (motivo) {
            case REASON_CLICK: chi = "TU"; cosa = "aperta"; break;
            case REASON_CANCEL: chi = "TU"; cosa = "scartata"; break;
            case REASON_CANCEL_ALL: chi = "TU"; cosa = "cancellata con \"Cancella tutto\""; break;
            case REASON_APP_CANCEL: case REASON_APP_CANCEL_ALL: chi = "ODIN"; cosa = "tolta dall'app"; break;
            case REASON_TIMEOUT: chi = "ODIN"; cosa = "scaduta"; break;
            default: chi = d.chi(); cosa = "rimossa (motivo " + motivo + ")";
        }
        d.evento(chi, "NOTIFICA-", sbn.getPackageName(), cosa + (t != null ? ": " + t : ""));
    }

    private static String testo(StatusBarNotification sbn) {
        Bundle x = sbn.getNotification().extras;
        StringBuilder b = new StringBuilder();
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT}) {
            CharSequence v = x == null ? null : x.getCharSequence(k);
            if (v == null || v.length() == 0) continue;
            String s = v.toString().replace('\n', ' ');
            if (b.indexOf(s) >= 0) continue; // il testo lungo spesso ripete quello breve
            if (b.length() > 0) b.append(" — ");
            b.append(s);
        }
        String s = b.toString();
        return s.length() > 1000 ? s.substring(0, 1000) + "…" : s;
    }
}
