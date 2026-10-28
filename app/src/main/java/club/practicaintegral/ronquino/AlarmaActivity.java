package club.practicaintegral.ronquino;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Pantalla completa «¡Buenos días!» cuando suena una alarma (también encima del bloqueo):
 * reloj grande arriba, botones para posponer 1, 3, 5 o 9 minutos o apagar, y la luna abajo
 * saludando y dando saltitos.
 * Prueba para apagar (Ajustes): «Apagar» abre una prueba (caminar 30 pasos, 3 cuentas o escanear el código guardado);
 * mientras se hace, el volumen baja, y la alarma solo se apaga al terminarla.
 */
public class AlarmaActivity extends Activity {
    private static final int PASOS = 30, CUENTAS = 3; // 4.7: 30 pasos (antes 20)
    private static WeakReference<AlarmaActivity> actual = new WeakReference<>(null);
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private View principal;
    private TextView reloj;
    private ImageView luna;
    private boolean aplaude;
    // prueba de pasos
    private SensorManager sm;
    private SensorEventListener oyente;

    static void cerrar() { AlarmaActivity a = actual.get(); if (a != null) a.finish(); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        actual = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true); }
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { /* se apaga o pospone con sus botones */ });
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        LinearLayout col = columna();

        reloj = new TextView(this);
        reloj.setTextColor(0xFFF8FAFC); reloj.setTextSize(84); reloj.setGravity(Gravity.CENTER);
        col.addView(reloj);

        TextView hola = texto("☀️ ¡Buenos días!", 0xFFFDE68A, 28);
        hola.setPadding(0, 0, 0, dp(24));
        col.addView(hola);

        int[] ops = Alarmas.opcionesPosponer(this);
        if (ops.length == 0) col.addView(texto("☀️ Ya has pospuesto 2 veces: ¡a levantarse!", 0xFFC7D2FE, 17)); // 4.7: sin «Posponer»
        else {
            col.addView(texto("😴 Posponer", 0xFFC7D2FE, 17));
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setGravity(Gravity.CENTER);
            for (int m : ops) fila.addView(boton(m + " min", 0xFF312E81, 0xFFE0E7FF, 17, () -> pulsar("POSPONER", m), true)); // cada vez, menos
            col.addView(fila, new LinearLayout.LayoutParams(-1, -2));
        }

        boolean reforzada = !AlarmaService.retoActual.isEmpty();
        Button apagar = boton("☀️ Apagar", 0xFFFDE68A, 0xFF1E1B4B, 24,
                () -> { if (reforzada) prueba(); else pulsar("APAGAR", 0); }, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(76));
        lp.setMargins(dp(8), dp(18), dp(8), 0);
        col.addView(apagar, lp);
        if (reforzada) {
            TextView nota = texto("Para apagarla, " + queHacer(AlarmaService.retoActual), 0xFFC7D2FE, 15);
            nota.setPadding(0, dp(8), 0, 0);
            col.addView(nota);
        }

        // La luna, abajo del todo, ocupando el espacio que sobra
        luna = new ImageView(this);
        luna.setImageResource(R.drawable.luna_saluda);
        luna.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lpl = new LinearLayout.LayoutParams(-1, 0, 1f);
        lpl.topMargin = dp(16);
        col.addView(luna, lpl);

        principal = col;
        setContentView(col);
        // Con la pantalla completa a la vista, el aviso de arriba sobra (tapaba el reloj): pasa a ser discreto
        try { startService(new Intent(this, AlarmaService.class).setAction("DISCRETO")); } catch (RuntimeException ignored) {}
        animarLuna();
        tic.run();
    }

    private static String queHacer(String reto) {
        switch (reto) {
            case "pasos": return "camina " + PASOS + " pasos.";
            case "codigo": return "escanea el código que guardaste.";
            default: return "haz " + CUENTAS + " cuentas.";
        }
    }

    // ---------- Alarma reforzada ----------
    private void prueba() {
        String reto = AlarmaService.retoActual;
        if ("pasos".equals(reto) && acelerometro() == null) reto = "cuentas"; // móvil sin sensor de movimiento
        AlarmaService.bajar(30);
        switch (reto) {
            case "pasos": pruebaPasos(); break;
            case "codigo": pruebaCodigo(); break;
            default: pruebaCuentas(); break;
        }
    }

    /** Pantalla de la prueba: título arriba, el contenido y «Volver» abajo (para posponer). */
    private LinearLayout pantallaPrueba(String titulo) {
        LinearLayout col = columna();
        TextView t = texto(titulo, 0xFFFDE68A, 26);
        t.setPadding(0, 0, 0, dp(6));
        col.addView(t);
        col.addView(texto("🔉 El volumen baja mientras la haces", 0xFFC7D2FE, 15));
        return col;
    }

    private void volverAbajo(LinearLayout col) {
        View hueco = new View(this);
        col.addView(hueco, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button volver = boton("← Volver", 0xFF312E81, 0xFFE0E7FF, 18, () -> {
            pararPasos();
            setContentView(principal);
        }, false);
        col.addView(volver, new LinearLayout.LayoutParams(-1, dp(60)));
        setContentView(col);
    }

    private Sensor acelerometro() {
        if (sm == null) sm = getSystemService(SensorManager.class);
        return sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    /** 🚶 Caminar: cada paso es un pico de la aceleración (por encima de la gravedad) separado del anterior. */
    private void pruebaPasos() {
        LinearLayout col = pantallaPrueba("🚶 Camina " + PASOS + " pasos");
        col.addView(texto("Con el móvil en la mano, levántate y anda.", 0xFFE2E8F0, 17));
        TextView cuenta = texto("0 / " + PASOS, 0xFFF8FAFC, 72);
        cuenta.setPadding(0, dp(40), 0, 0);
        col.addView(cuenta);
        volverAbajo(col);
        final int[] pasos = {0};
        oyente = new SensorEventListener() {
            float g = SensorManager.GRAVITY_EARTH;
            boolean arriba;
            long ultimo;
            @Override public void onSensorChanged(SensorEvent e) {
                float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
                g = g * 0.9f + m * 0.1f;
                float d = m - g;
                long ahora = System.currentTimeMillis();
                if (!arriba && d > 1.8f && ahora - ultimo > 300) {
                    arriba = true; ultimo = ahora; pasos[0]++;
                    cuenta.setText(pasos[0] + " / " + PASOS);
                    AlarmaService.bajar(20);
                    if (pasos[0] >= PASOS) { pararPasos(); hecho(); }
                } else if (arriba && d < 0.3f) arriba = false;
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        sm.registerListener(oyente, acelerometro(), SensorManager.SENSOR_DELAY_GAME);
    }

    private void pararPasos() { if (sm != null && oyente != null) sm.unregisterListener(oyente); oyente = null; }

    /** 🧮 Cuentas: sumas, restas y multiplicaciones sencillas, con un teclado grande. */
    private void pruebaCuentas() {
        LinearLayout col = pantallaPrueba("🧮 Haz " + CUENTAS + " cuentas");
        TextView num = texto("", 0xFFC7D2FE, 17);
        TextView pregunta = texto("", 0xFFF8FAFC, 44);
        pregunta.setPadding(0, dp(18), 0, dp(4));
        TextView respuesta = texto("", 0xFFFDE68A, 44);
        TextView aviso = texto("", 0xFFFCA5A5, 17);
        col.addView(num); col.addView(pregunta); col.addView(respuesta); col.addView(aviso);
        final int[] estado = {0, 0}; // {cuenta hecha, resultado}
        final StringBuilder sb = new StringBuilder();
        Runnable nueva = () -> {
            int a, b; String op;
            switch (rnd.nextInt(3)) {
                case 0: a = 12 + rnd.nextInt(48); b = 11 + rnd.nextInt(39); op = " + "; estado[1] = a + b; break;
                case 1: a = 40 + rnd.nextInt(59); b = 11 + rnd.nextInt(a - 20); op = " − "; estado[1] = a - b; break;
                default: a = 3 + rnd.nextInt(7); b = 3 + rnd.nextInt(7); op = " × "; estado[1] = a * b; break;
            }
            num.setText("Cuenta " + (estado[0] + 1) + " de " + CUENTAS);
            pregunta.setText(a + op + b + " =");
            sb.setLength(0); respuesta.setText("?");
        };
        nueva.run();

        GridLayout teclado = new GridLayout(this);
        teclado.setColumnCount(3);
        String[] teclas = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓"};
        for (String k : teclas) {
            Button bt = boton(k, "✓".equals(k) ? 0xFFFDE68A : 0xFF312E81, "✓".equals(k) ? 0xFF1E1B4B : 0xFFE0E7FF, 26, () -> {
                AlarmaService.bajar(30);
                aviso.setText("");
                if ("⌫".equals(k)) { if (sb.length() > 0) sb.setLength(sb.length() - 1); }
                else if ("✓".equals(k)) {
                    if (sb.length() == 0) return;
                    if (Integer.parseInt(sb.toString()) == estado[1]) {
                        estado[0]++;
                        if (estado[0] >= CUENTAS) { hecho(); return; }
                        nueva.run(); return;
                    }
                    aviso.setText("❌ No es correcto, prueba otra vez");
                    sb.setLength(0);
                } else if (sb.length() < 4) sb.append(k);
                respuesta.setText(sb.length() == 0 ? "?" : sb.toString());
            }, false);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            p.width = 0; p.height = dp(64);
            p.setMargins(dp(5), dp(5), dp(5), dp(5));
            teclado.addView(bt, p);
        }
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-1, -2);
        lt.topMargin = dp(12);
        col.addView(teclado, lt);
        volverAbajo(col);
    }

    /** 📷 Código: hay que ir hasta el objeto guardado (la pasta de dientes, el café…) y escanearlo. */
    private void pruebaCodigo() {
        LinearLayout col = pantallaPrueba("📷 Escanea tu código");
        TextView info = texto("Ve hasta el objeto cuyo código guardaste y escanéalo.", 0xFFE2E8F0, 17);
        info.setPadding(0, dp(12), 0, dp(20));
        col.addView(info);
        TextView aviso = texto("", 0xFFFCA5A5, 17);
        Button escanear = boton("📷 Escanear", 0xFFFDE68A, 0xFF1E1B4B, 24, () -> desbloquearYEscanear(aviso, col), false);
        col.addView(escanear, new LinearLayout.LayoutParams(-1, dp(76)));
        aviso.setPadding(0, dp(14), 0, 0);
        col.addView(aviso);
        volverAbajo(col);
    }

    private void desbloquearYEscanear(TextView aviso, LinearLayout col) {
        AlarmaService.bajar(60);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            // la cámara no se abre encima del bloqueo: primero hay que desbloquear el móvil
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { escanear(aviso, col); }
                @Override public void onDismissCancelled() { aviso.setText("Desbloquea el móvil para poder escanear."); }
            });
        } else escanear(aviso, col);
    }

    private void escanear(TextView aviso, LinearLayout col) {
        AlarmaService.bajar(60);
        try {
            GmsBarcodeScanning.getClient(this).startScan()
                    .addOnSuccessListener(b -> {
                        if (AlarmaService.codActual.equals(b.getRawValue())) hecho();
                        else { AlarmaService.bajar(20); aviso.setText("❌ Ese no es el código que guardaste. Prueba otra vez."); }
                    })
                    .addOnCanceledListener(() -> AlarmaService.bajar(15))
                    .addOnFailureListener(e -> sinLector(aviso, col));
        } catch (RuntimeException e) { sinLector(aviso, col); }
    }

    /** Si el lector de códigos no funciona en este móvil, la alarma no se queda sin poder apagar: cuentas. */
    private void sinLector(TextView aviso, LinearLayout col) {
        aviso.setText("No se ha podido abrir el lector de códigos.");
        Button cuentas = boton("🧮 Hacer las cuentas en su lugar", 0xFF312E81, 0xFFE0E7FF, 18, this::pruebaCuentas, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
        lp.topMargin = dp(10);
        col.addView(cuentas, col.getChildCount() - 2, lp);
    }

    private void hecho() {
        Toast.makeText(this, "✅ ¡Muy bien! Alarma apagada", Toast.LENGTH_SHORT).show();
        pulsar("APAGAR", 0);
    }

    // ---------- Piezas ----------
    /** Saltitos y balanceo, y cada 1,6 s cambia de saludar a aplaudir. */
    private void animarLuna() {
        luna.setPivotY(dp(220));
        ObjectAnimator salto = ObjectAnimator.ofFloat(luna, "translationY", 0, -dp(18));
        salto.setDuration(450); salto.setRepeatMode(ValueAnimator.REVERSE); salto.setRepeatCount(ValueAnimator.INFINITE);
        salto.setInterpolator(new AccelerateDecelerateInterpolator()); salto.start();
        ObjectAnimator vaiven = ObjectAnimator.ofFloat(luna, "rotation", -7, 7);
        vaiven.setDuration(800); vaiven.setRepeatMode(ValueAnimator.REVERSE); vaiven.setRepeatCount(ValueAnimator.INFINITE);
        vaiven.start();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                aplaude = !aplaude;
                luna.setImageResource(aplaude ? R.drawable.luna_aplaude : R.drawable.luna_saluda);
                h.postDelayed(this, 1600);
            }
        }, 1600);
    }

    private void pulsar(String accion, int min) {
        pararPasos();
        startService(new Intent(this, AlarmaService.class).setAction(accion).putExtra("min", min));
        finish();
    }

    private LinearLayout columna() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(48), dp(24), dp(24));
        col.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF7C2D12, 0xFF1E1B4B, 0xFF0B1020}));
        return col;
    }

    private TextView texto(String t, int color, int sp) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(color); v.setTextSize(sp); v.setGravity(Gravity.CENTER);
        return v;
    }

    private Button boton(String txt, int fondo, int color, int sp, Runnable r, boolean enFila) {
        Button bt = new Button(this);
        bt.setText(txt); bt.setTextColor(color); bt.setTextSize(sp); bt.setAllCaps(false);
        bt.setSingleLine(true); bt.setPadding(0, 0, 0, 0); bt.setMinWidth(0); bt.setMinimumWidth(0);
        GradientDrawable g = new GradientDrawable(); g.setColor(fondo); g.setCornerRadius(dp(18));
        bt.setBackground(g);
        if (enFila) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(72), 1f);
            lp.setMargins(dp(6), dp(8), dp(6), 0);
            bt.setLayoutParams(lp);
        }
        bt.setOnClickListener(v -> r.run());
        return bt;
    }

    private final Runnable tic = new Runnable() {
        @Override public void run() {
            reloj.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            h.postDelayed(this, 1000);
        }
    };

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() { /* se apaga o pospone con sus botones */ }

    @Override public void setContentView(View v) {
        super.setContentView(v);
        if (Build.VERSION.SDK_INT >= 35) {
            final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), bo = v.getPaddingBottom();
            v.setOnApplyWindowInsetsListener((vista, ins) -> {
                android.graphics.Insets b = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                vista.setPadding(l + b.left, t + b.top, r + b.right, bo + b.bottom);
                return android.view.WindowInsets.CONSUMED;
            });
            v.requestApplyInsets();
        }
    }
    @Override protected void onDestroy() { pararPasos(); h.removeCallbacksAndMessages(null); super.onDestroy(); }
}
