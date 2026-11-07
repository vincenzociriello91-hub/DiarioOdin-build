package com.vincenzo.diarioodin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Schermata di stato: cosa funziona, cosa manca, e un pulsante per chiudere la sessione adesso. */
public class MainActivity extends Activity {
    private TextView stato;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);
        TextView titolo = new TextView(this);
        titolo.setText("Diario Odin");
        titolo.setTextSize(26);
        l.addView(titolo);
        stato = new TextView(this);
        stato.setTextSize(16);
        stato.setPadding(0, p / 2, 0, p / 2);
        l.addView(stato);
        bottone(l, "Crea il report adesso", new View.OnClickListener() { public void onClick(View v) {
            Diario.get(MainActivity.this).reportOra();
            Toast.makeText(MainActivity.this, "Report in scrittura in Download/Odin2_report", Toast.LENGTH_LONG).show();
        } });
        bottone(l, "Accessibilità (lettura schermo)", new View.OnClickListener() { public void onClick(View v) { apri(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } });
        bottone(l, "Accesso alle notifiche", new View.OnClickListener() { public void onClick(View v) { apri(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } });
        bottone(l, "Accesso a tutti i file", new View.OnClickListener() { public void onClick(View v) { apri(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()))); } });
        ScrollView s = new ScrollView(this);
        s.setFitsSystemWindows(true);
        s.addView(l);
        setContentView(s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String acc = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean schermo = acc != null && acc.contains(getPackageName());
        String not = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean notifiche = not != null && not.contains(getPackageName());
        boolean file = Environment.isExternalStorageManager();
        Diario d = Diario.get(this);
        stato.setText("Versione " + d.versione() + "\n\n"
                + riga(schermo, "Lettura dello schermo") + "\n"
                + riga(notifiche, "Lettura delle notifiche") + "\n"
                + riga(file, "Report in Download/Odin2_report") + "\n"
                + riga(d.rootInput, "Root: chi ha fatto cosa (tu o la Odin)") + "\n"
                + riga(d.rootLog, "Root: crash e app chiuse dal sistema") + "\n\n"
                + "I report si creano da soli quando la Odin va in standby per più di 5 minuti, "
                + "quando si spegne, e un riepilogo del giorno si aggiorna a ogni report."
                + (file ? "" : "\n\nSenza accesso ai file i report vanno in Android/data/" + getPackageName() + "/files."));
    }

    private static String riga(boolean ok, String cosa) { return (ok ? "✅ " : "❌ ") + cosa; }

    private void bottone(LinearLayout l, String testo, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(testo);
        b.setOnClickListener(c);
        l.addView(b);
    }

    private void apri(Intent i) {
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "Impostazione non disponibile", Toast.LENGTH_SHORT).show(); }
    }
}
