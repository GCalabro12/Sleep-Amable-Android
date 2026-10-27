package club.practicaintegral.ronquino;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Alarmas de verdad: las programa el reloj de Android (AlarmManager.setAlarmClock), así suenan
 * aunque Amable Sleep esté cerrada o el móvil bloqueado. La web manda la lista con setAlarmas().
 */
public class Alarmas {
    static final String PREFS = "alarmas";
    static final int ID_SIESTA = 900001, ID_POSPUESTA = 900002;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Guarda la lista que manda la web: [{id, on, hora:"07:30", tono, sub, reto, cod}] y la vuelve a programar. */
    static void guardar(Context c, String json) {
        // Antes de cambiar la lista, quita las que había programadas
        cancelarLista(c);
        prefs(c).edit().putString("lista", json).apply();
        programarTodas(c);
    }

    static void cancelarLista(Context c) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("lista", "[]"));
            for (int i = 0; i < a.length(); i++) cancelar(c, codigo(a.getJSONObject(i)));
        } catch (Exception ignored) {}
    }

    /** Programa la próxima vez de cada alarma encendida (se llama también al reiniciar el móvil). */
    static void programarTodas(Context c) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("lista", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject al = a.getJSONObject(i);
                if (!al.optBoolean("on")) { cancelar(c, codigo(al)); continue; }
                String[] hm = al.optString("hora", "07:30").split(":");
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                cal.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_MONTH, 1);
                programar(c, codigo(al), cal.getTimeInMillis(), al.optString("tono", "amanecer"), al.optInt("sub", 120),
                        al.optString("reto", ""), al.optString("cod", ""));
            }
        } catch (Exception ignored) {}
    }

    static int codigo(JSONObject al) { return (int) (al.optLong("id") % 800000); }

    /** reto: "" (se apaga con el botón), "pasos", "cuentas" o "codigo" (alarma reforzada); cod: el código de barras guardado. */
    static void programar(Context c, int codigo, long cuando, String tono, int sub, String reto, String cod) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, AlarmaReceiver.class).setAction("SONAR").putExtra("tono", tono).putExtra("sub", sub).putExtra("codigo", codigo)
                .putExtra("reto", reto).putExtra("cod", cod);
        PendingIntent pi = PendingIntent.getBroadcast(c, codigo, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ver = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(cuando, ver), pi);
        } catch (SecurityException e) { // si Android no deja alarmas exactas, la más precisa posible
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    static void cancelar(Context c, int codigo) {
        Intent i = new Intent(c, AlarmaReceiver.class).setAction("SONAR");
        PendingIntent pi = PendingIntent.getBroadcast(c, codigo, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pi != null) { c.getSystemService(AlarmManager.class).cancel(pi); pi.cancel(); }
    }

    /** Siesta: suena dentro de «min» minutos. Devuelve la hora (ms) a la que sonará. */
    static long siesta(Context c, int min, String tono, int sub) {
        long cuando = System.currentTimeMillis() + min * 60_000L;
        programar(c, ID_SIESTA, cuando, tono, sub, "", "");
        return cuando;
    }

    static void cancelarSiesta(Context c) { cancelar(c, ID_SIESTA); }

    /** «Posponer»: la misma alarma vuelve a sonar dentro de «min» minutos (1, 3, 5 o 9). */
    static void posponer(Context c, String tono, int sub, int min, String reto, String cod) {
        int[] ops = opcionesPosponer(c); min = Math.min(Math.max(1, min), ops[ops.length - 1]); // nunca más de lo que toca
        contarPospuesta(c);
        programar(c, ID_POSPUESTA, System.currentTimeMillis() + min * 60_000L, tono, sub, reto, cod);
    }

    // Cada vez que se pospone, menos tiempo: la 1.ª vez 1, 3, 5 o 9 min; la 2.ª, 1 o 3; después, solo 1
    static int pospuestas(Context c) {
        SharedPreferences p = c.getSharedPreferences("pospuestas", Context.MODE_PRIVATE);
        return System.currentTimeMillis() - p.getLong("t", 0) < 2 * 3600_000L ? p.getInt("n", 0) : 0; // (de otra mañana no cuenta)
    }
    static void contarPospuesta(Context c) {
        c.getSharedPreferences("pospuestas", Context.MODE_PRIVATE).edit().putInt("n", pospuestas(c) + 1).putLong("t", System.currentTimeMillis()).apply();
    }
    static void olvidarPospuestas(Context c) { c.getSharedPreferences("pospuestas", Context.MODE_PRIVATE).edit().clear().apply(); }
    static int[] opcionesPosponer(Context c) {
        int n = pospuestas(c);
        return n == 0 ? new int[]{1, 3, 5, 9} : n == 1 ? new int[]{1, 3} : new int[]{1};
    }
}
