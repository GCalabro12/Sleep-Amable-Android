package club.practicaintegral.ronquino;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Servicio en primer plano mientras Amable Sleep escucha la noche o suena el ruido para dormir.
 * Con él, Android no corta el micrófono ni el sonido al apagar la pantalla.
 */
public class NocheService extends Service {
    static volatile boolean escuchando, ruido;
    private static final String CANAL = "noche";
    private PowerManager.WakeLock cpu;

    static boolean activo() { return escuchando || ruido; }

    /** Arranca, actualiza o para el servicio según lo que esté pasando. */
    static void actualizar(Context c) {
        Intent i = new Intent(c, NocheService.class);
        try {
            if (activo()) c.startForegroundService(i);
            else c.stopService(i);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Noche en marcha", NotificationManager.IMPORTANCE_LOW));
        Intent abrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_stat_luna)
                .setContentTitle(escuchando ? "Amable Sleep está escuchando" : "Amable Sleep: sonido para dormir")
                .setContentText(escuchando ? "Solo graba cuando suena algo. Toca para volver."
                        : "El sonido sigue con la pantalla apagada. Toca para volver.")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int tipo = 0;
                if (escuchando && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                if (ruido || tipo == 0) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
                startForeground(1, n, tipo);
            } else {
                startForeground(1, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!activo()) { stopSelf(); return START_NOT_STICKY; }
        if (cpu == null) {
            // El teléfono no se duerme del todo: el detector y el temporizador del ruido siguen funcionando
            cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ronquino:noche");
            cpu.acquire(14 * 60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    /** Si se cierra la app deslizándola, la noche se acaba: «No molestar» y wifi vuelven a como estaban. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        escuchando = false;
        ruido = false;
        Modos.restaurar(this);
        Tono.quitar();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (cpu != null && cpu.isHeld()) cpu.release();
        cpu = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
