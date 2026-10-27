package club.practicaintegral.ronquino;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Hace sonar la alarma: tono propio en bucle, con el volumen subiendo poco a poco («sub» segundos),
 * y abre la pantalla «¡Buenos días!» aunque el móvil esté bloqueado.
 */
public class AlarmaService extends Service {
    private static final String CANAL = "alarma";
    static volatile boolean sonando;
    static String tonoActual = "amanecer";
    static int subActual = 120;
    /** Alarma reforzada: la prueba para apagarla ("" = basta el botón) y el código de barras guardado. */
    static String retoActual = "", codActual = "";
    /** Mientras se hace la prueba el volumen baja (hasta esta hora); si se deja, vuelve a sonar fuerte. */
    static volatile long bajoHasta;
    static void bajar(int seg) { bajoHasta = System.currentTimeMillis() + seg * 1000L; }
    private MediaPlayer mp;
    private PowerManager.WakeLock cpu;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long t0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : null;
        if ("APAGAR".equals(accion)) { Alarmas.olvidarPospuestas(this); parar(); return START_NOT_STICKY; }
        if ("DISCRETO".equals(accion)) { discreto(); return START_NOT_STICKY; }
        if ("POSPONER".equals(accion)) { Alarmas.posponer(this, tonoActual, subActual, intent.getIntExtra("min", 9), retoActual, codActual); parar(); return START_NOT_STICKY; }

        if (intent != null) {
            tonoActual = intent.getStringExtra("tono") != null ? intent.getStringExtra("tono") : "amanecer";
            subActual = intent.getIntExtra("sub", 120);
            retoActual = intent.getStringExtra("reto") != null ? intent.getStringExtra("reto") : "";
            codActual = intent.getStringExtra("cod") != null ? intent.getStringExtra("cod") : "";
            if ("codigo".equals(retoActual) && codActual.isEmpty()) retoActual = "cuentas";
            bajoHasta = 0;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CANAL, "Alarmas", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null); // el sonido lo pone la app, con su subida de volumen
        nm.createNotificationChannel(ch);
        PendingIntent pantalla = PendingIntent.getActivity(this, 2, new Intent(this, AlarmaActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_stat_luna)
                .setContentTitle("☀️ ¡Buenos días!")
                .setContentText("Amable Sleep · alarma")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pantalla, true)
                .setContentIntent(pantalla)
                .setOngoing(true);
        acciones(nb);
        Notification n = nb.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(7, n);
        } catch (RuntimeException e) { stopSelf(); return START_NOT_STICKY; }

        if (cpu == null) {
            cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ronquino:alarma");
            cpu.acquire(30 * 60 * 1000L);
        }
        sonar();
        try { startActivity(new Intent(this, AlarmaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) {}
        return START_NOT_STICKY;
    }

    /** Mismo aviso, pero en un canal sin ventanita emergente (la pantalla completa ya está a la vista). */
    private void discreto() {
        if (!sonando) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("alarma_baja", "Alarma sonando", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pantalla = PendingIntent.getActivity(this, 2, new Intent(this, AlarmaActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(this, "alarma_baja")
                .setSmallIcon(R.drawable.ic_stat_luna)
                .setContentTitle("☀️ ¡Buenos días!")
                .setContentText("Toca para ver la alarma")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pantalla)
                .setOngoing(true);
        acciones(nb);
        nm.notify(7, nb.build());
    }

    /** Posponer siempre; «Apagar» desde el aviso solo si la alarma no es reforzada (si no, se apaga haciendo la prueba). */
    private void acciones(Notification.Builder nb) {
        int[] ops = Alarmas.opcionesPosponer(this); int max = ops[ops.length - 1]; // 9, luego 3, luego 1
        nb.addAction(new Notification.Action.Builder(null, "Posponer " + max + " min", accionPI("POSPONER", 3, max)).build());
        if (retoActual.isEmpty()) nb.addAction(new Notification.Action.Builder(null, "Apagar", accionPI("APAGAR", 4, 0)).build());
    }

    private PendingIntent accionPI(String accion, int code, int min) {
        return PendingIntent.getService(this, code, new Intent(this, AlarmaService.class).setAction(accion).putExtra("min", min),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void sonar() {
        if (mp != null) return;
        int res = getResources().getIdentifier(tonoActual, "raw", getPackageName());
        if (res == 0) res = getResources().getIdentifier("amanecer", "raw", getPackageName());
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(this, Uri.parse("android.resource://" + getPackageName() + "/" + res));
            mp.setLooping(true);
            mp.prepare();
            float v0 = subActual > 0 ? ganancia(0) : 1f;
            mp.setVolume(v0, v0);
            mp.start();
            sonando = true;
            t0 = System.currentTimeMillis();
            h.post(subir);
        } catch (Exception e) { parar(); }
    }

    /**
     * Subida que el oído nota pareja: en decibelios, de −50 dB (casi en silencio) a 0 dB (volumen completo).
     * Subirla en línea recta (de 3 % a 100 %) se oía fuerte casi desde el principio.
     */
    static float ganancia(float k) { return k >= 1f ? 1f : (float) Math.pow(10, (-50 + 50 * k) / 20.0); }

    /** Despertar suave: el volumen sube poco a poco durante «sub» segundos. */
    private final Runnable subir = new Runnable() {
        @Override public void run() {
            if (mp == null) return;
            float k = subActual > 0 ? Math.min(1f, (System.currentTimeMillis() - t0) / (subActual * 1000f)) : 1f;
            float v = ganancia(k);
            if (System.currentTimeMillis() < bajoHasta) v = Math.min(v, 0.08f); // haciendo la prueba: bajito
            try { mp.setVolume(v, v); } catch (IllegalStateException ignored) {}
            h.postDelayed(this, 200);
        }
    };

    void parar() {
        h.removeCallbacks(subir);
        if (mp != null) { try { mp.stop(); } catch (IllegalStateException ignored) {} mp.release(); mp = null; }
        sonando = false;
        if (cpu != null && cpu.isHeld()) cpu.release();
        cpu = null;
        AlarmaActivity.cerrar();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { if (mp != null) parar(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
