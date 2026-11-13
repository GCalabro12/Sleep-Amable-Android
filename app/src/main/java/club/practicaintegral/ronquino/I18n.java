package club.practicaintegral.ronquino;

import android.content.Context;

import java.util.Locale;

/** 5.4: el idioma que eligió la persona en la web (es, en o pt): la voz de los avisos y la pantalla de la alarma. */
final class I18n {
    private I18n() {}

    static String lang(Context c) {
        String l = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getString("idioma", "es");
        return "en".equals(l) || "pt".equals(l) ? l : "es";
    }

    static void set(Context c, String l) {
        c.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putString("idioma", "en".equals(l) || "pt".equals(l) ? l : "es").apply();
    }

    static String t(Context c, String es, String en, String pt) {
        String l = lang(c);
        return "en".equals(l) ? en : "pt".equals(l) ? pt : es;
    }

    static Locale locale(String l) {
        return "en".equals(l) ? new Locale("en", "US") : "pt".equals(l) ? new Locale("pt", "BR") : new Locale("es", "ES");
    }
}
