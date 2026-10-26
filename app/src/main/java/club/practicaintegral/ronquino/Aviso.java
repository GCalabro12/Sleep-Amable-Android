package club.practicaintegral.ronquino;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * «Estás roncando, cambia de posición»: una voz que solo suena por los auriculares (pinganillos),
 * nunca por el altavoz, para no despertar a quien duerme al lado.
 */
final class Aviso {
    private static TextToSpeech tts;
    private static boolean listo;
    private static String pendiente;
    private static Context app;
    private static int volAntes = -1; // volumen que había antes de subirlo para el aviso

    private Aviso() {}

    /** Prepara la voz (tarda un momento la primera vez). */
    static void preparar(Context c) {
        if (tts != null) return;
        app = c.getApplicationContext();
        tts = new TextToSpeech(app, estado -> {
            listo = estado == TextToSpeech.SUCCESS;
            if (!listo) return;
            tts.setLanguage(new Locale("es", "ES"));
            mejorVoz();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { if ("aviso".equals(id)) bajarVolumen(); }
                @Override public void onError(String id) { if ("aviso".equals(id)) bajarVolumen(); }
            });
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            if (pendiente != null) { tts.speak(pendiente, TextToSpeech.QUEUE_FLUSH, null, "aviso"); pendiente = null; }
        });
    }

    /** De las voces en español del móvil, la de más calidad que no necesite internet (si no, la de más calidad). */
    private static void mejorVoz() {
        try {
            android.speech.tts.Voice mejor = null;
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (v.getLocale() == null || !"es".equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                int puntos = v.getQuality() + (v.isNetworkConnectionRequired() ? -50 : 0) + ("ES".equals(v.getLocale().getCountry()) ? 20 : 0);
                int puntosMejor = mejor == null ? Integer.MIN_VALUE : mejor.getQuality() + (mejor.isNetworkConnectionRequired() ? -50 : 0) + ("ES".equals(mejor.getLocale().getCountry()) ? 20 : 0);
                if (puntos > puntosMejor) mejor = v;
            }
            if (mejor != null) tts.setVoice(mejor);
        } catch (RuntimeException ignored) {}
    }

    /** ¿Hay auriculares conectados (con cable, bluetooth o USB)? */
    static boolean auriculares(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    return true;
            }
        }
        return false;
    }

    /** Lo dice por donde suene el móvil (altavoz o auriculares): la voz de Luna cuando la de Gemini no llega. */
    static void hablar(Context c, String texto) {
        preparar(c);
        if (listo) { tts.setSpeechRate(1f); tts.setPitch(1f); tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "luna"); }
        else pendiente = texto;
    }

    static void callar() { if (tts != null) tts.stop(); pendiente = null; }

    /** Para que el aviso se oiga: el volumen multimedia al menos al 60 % mientras habla; luego vuelve a como estaba.
     *  (Antes 3/4: con auriculares pasaba del «nivel seguro» y Android preguntaba «¿Subir el volumen por encima del nivel seguro?».) */
    private static void subirVolumen(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int min = (int) Math.floor(max * 0.6);
            if (cur < min) { if (volAntes < 0) volAntes = cur; am.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0); }
        } catch (RuntimeException ignored) {}
    }

    private static void bajarVolumen() {
        if (volAntes < 0 || app == null) return;
        try { app.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, volAntes, 0); } catch (RuntimeException ignored) {}
        volAntes = -1;
    }

    /** Lo dice solo si hay auriculares; sin ellos no suena nada y devuelve false. */
    static boolean decir(Context c, String texto) { return decir(c, texto, 1f, 1f); }

    /** Igual, más despacio y un poco más grave (más tranquilo): «Prueba de audífonos». */
    static boolean decirDespacio(Context c, String texto) { return decir(c, texto, 0.8f, 0.95f); }

    private static boolean decir(Context c, String texto, float velocidad, float tono) {
        if (!auriculares(c)) return false;
        preparar(c);
        subirVolumen(c);
        if (listo) { tts.setSpeechRate(velocidad); tts.setPitch(tono); tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "aviso"); }
        else pendiente = texto;
        return true;
    }
}
