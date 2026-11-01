package club.practicaintegral.ronquino;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * 📡 El pitido del sonar: sale siempre por el altavoz del móvil, también con un auricular conectado.
 * Si Android lo manda a otro sitio se queda en silencio: ese pitido nunca debe sonar dentro del oído.
 */
final class Tono {
    private static final int HZ = 48000, MUESTRAS = 4800; // 0,1 s: 1.900 ciclos justos de 19 kHz, el bucle empalma sin «clic»
    private static final float VOL = .3f; // el mismo volumen que el pitido de la web
    private static final Handler hilo = new Handler(Looper.getMainLooper());
    private static final Runnable vigia = Tono::vigila;
    private static AudioTrack pista;
    private static float vol;

    private Tono() {}

    /** Empieza en silencio; solo sube cuando Android confirma que va por el altavoz. Devuelve false si no se puede. */
    static synchronized boolean poner(Context c) {
        if (pista != null) return true;
        try {
            AudioDeviceInfo altavoz = null;
            for (AudioDeviceInfo d : c.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) altavoz = d;
            if (altavoz == null) return false;
            short[] onda = new short[MUESTRAS];
            for (int i = 0; i < MUESTRAS; i++) onda[i] = (short) (Math.sin(2 * Math.PI * 19000 * i / HZ) * Short.MAX_VALUE);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(HZ).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(MUESTRAS * 2).build();
            t.setVolume(0f);
            t.setPreferredDevice(altavoz);
            t.write(onda, 0, MUESTRAS);
            t.setLoopPoints(0, MUESTRAS, -1);
            t.addOnRoutingChangedListener((AudioTrack.OnRoutingChangedListener) r -> vigila(), hilo);
            t.play();
            pista = t;
            vol = 0;
            hilo.postDelayed(vigia, 300);
            return true;
        } catch (RuntimeException e) { return false; }
    }

    static synchronized void quitar() {
        hilo.removeCallbacks(vigia);
        if (pista == null) return;
        try { pista.setVolume(0f); pista.stop(); } catch (RuntimeException ignored) {}
        pista.release();
        pista = null;
    }

    /** ¿Está sonando de verdad por el altavoz del móvil? */
    static synchronized boolean enAltavoz() {
        try {
            AudioDeviceInfo d = pista == null ? null : pista.getRoutedDevice();
            return d != null && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } catch (RuntimeException e) { return false; }
    }

    /** Sube despacio (sin «clic») y, si deja de ir por el altavoz, silencio al momento hasta que vuelva. */
    private static synchronized void vigila() {
        if (pista == null) return;
        vol = enAltavoz() ? Math.min(VOL, vol + .02f) : 0;
        try { pista.setVolume(vol); } catch (RuntimeException ignored) {}
        hilo.removeCallbacks(vigia);
        hilo.postDelayed(vigia, vol > 0 && vol < VOL ? 50 : 1000);
    }
}
