package club.practicaintegral.ronquino;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Llega la hora de una alarma (o el móvil se ha reiniciado y hay que volver a programarlas). */
public class AlarmaReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if ("QUITAR_DND".equals(i.getAction()) || "SEGURIDAD".equals(i.getAction())) {
            // Botón «Quitarlo ya» del aviso, o la seguridad de las 13 horas: «No molestar» y wifi como estaban
            Modos.restaurar(c);
            return;
        }
        if (!"SONAR".equals(i.getAction())) { // reinicio, actualización de la app o cambio de hora
            Alarmas.programarTodas(c);
            return;
        }
        // Las alarmas diarias se vuelven a programar para mañana
        Alarmas.programarTodas(c);
        // Al sonar la alarma ya es de día: «No molestar» deja de silenciar las llamadas
        Modos.quitarDnd(c);
        Intent s = new Intent(c, AlarmaService.class)
                .putExtra("tono", i.getStringExtra("tono"))
                .putExtra("sub", i.getIntExtra("sub", 120))
                .putExtra("reto", i.getStringExtra("reto"))
                .putExtra("cod", i.getStringExtra("cod"))
                .putExtra("codigo", i.getIntExtra("codigo", 0)); // 5.4: para «Apagar y desactivar»
        try { c.startForegroundService(s); } catch (RuntimeException ignored) {}
    }
}
