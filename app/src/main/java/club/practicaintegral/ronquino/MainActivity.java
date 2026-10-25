package club.practicaintegral.ronquino;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * App Android de Amable Sleep: muestra la web de siempre (https://gcalabro12.github.io/Sleep-Amable/) y añade
 * lo que una web no puede hacer: poner y quitar «No molestar», apagar el wifi (Android 9 o anterior),
 * seguir escuchando / sonando con la pantalla apagada y que la musiquita de bienvenida suene sola.
 * La web sabe que está dentro de la app porque existe window.RonquiNoAndroid.
 */
public class MainActivity extends Activity {
    private static final String WEB = "https://gcalabro12.github.io/Sleep-Amable/";
    private static final String HOST = "gcalabro12.github.io";
    private static final int REQ_MIC = 1, REQ_ARCHIVO = 2, REQ_VOZ = 3;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView cargando;
    private boolean cargada;
    private PermissionRequest micPendiente;
    private ValueCallback<Uri[]> archivoPendiente;
    private SpeechRecognizer reconocedor;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Si la app se cerró a media noche, «No molestar» y el wifi vuelven a como estaban
        if (saved == null && !NocheService.activo() && Modos.pendiente(this)) Modos.restaurar(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1020);

        // Solo en la versión de pruebas (debug): deja que la prueba automática pulse los botones de la web
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1020);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        cargando = new TextView(this);
        cargando.setText("Amable Sleep");
        cargando.setTextColor(0xFFE8ECFA);
        cargando.setTextSize(30);
        cargando.setGravity(Gravity.CENTER);
        cargando.setBackgroundColor(0xFF0B1020);
        root.addView(cargando, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // La musiquita de bienvenida puede sonar sin que la persona toque la pantalla
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Con la pantalla apagada la web sigue a pleno rendimiento (detector y ruido)
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.addJavascriptInterface(new Puente(), "RonquiNoAndroid");
        // Descargas (p. ej. la app nueva): se abren en el navegador, que es quien sabe instalarlas
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException ignored) {}
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost()) || "file".equals(u.getScheme())) return false;
                if ("blob".equals(u.getScheme()) || "data".equals(u.getScheme())) return true;
                // El resto de enlaces (YouTube, practicaintegral.club…) se abren fuera
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (RuntimeException ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                cargada = true;
                cargando.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                int c = err.getErrorCode();
                if (c == ERROR_HOST_LOOKUP || c == ERROR_CONNECT || c == ERROR_TIMEOUT)
                    view.loadUrl("file:///android_asset/sin-conexion.html");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError e) {
                h.cancel();
                view.loadUrl("file:///android_asset/sin-conexion.html");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            /** La web pide el micrófono: se da si Android ya dio el permiso; si no, se pide. */
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean audio = Arrays.asList(req.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                    if (!audio || !HOST.equals(req.getOrigin().getHost())) { req.deny(); return; }
                    if (micOk()) { req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); return; }
                    if (micPendiente != null) micPendiente.deny();
                    micPendiente = req;
                    List<String> p = new ArrayList<>();
                    p.add(Manifest.permission.RECORD_AUDIO);
                    if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
                    requestPermissions(p.toArray(new String[0]), REQ_MIC);
                });
            }

            /** «Analizar una grabación»: abre el selector de archivos del teléfono. */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (archivoPendiente != null) archivoPendiente.onReceiveValue(null);
                archivoPendiente = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
                try {
                    startActivityForResult(Intent.createChooser(i, "Elige la grabación"), REQ_ARCHIVO);
                } catch (RuntimeException e) {
                    archivoPendiente = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });

        web.loadUrl(WEB);
        handler.postDelayed(() -> { if (!cargada) cargando.setVisibility(View.GONE); }, 15000);
    }

    private boolean micOk() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC || micPendiente == null) return;
        if (micOk()) micPendiente.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else micPendiente.deny();
        micPendiente = null;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOZ) { // dictado para «Habla con Luna»
            ArrayList<String> r = data != null ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
            dictado(r != null && !r.isEmpty() ? r.get(0) : "", res == RESULT_OK ? "" : "cancelado");
            return;
        }
        if (req != REQ_ARCHIVO || archivoPendiente == null) return;
        archivoPendiente.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
        archivoPendiente = null;
    }

    private void dictado(String texto, String error) {
        runOnUiThread(() -> web.evaluateJavascript("window.dictado && window.dictado("
                + JSONObject.quote(texto == null ? "" : texto) + "," + JSONObject.quote(error) + ")", null));
    }

    private void codigoEscaneado(String valor, String error) {
        runOnUiThread(() -> web.evaluateJavascript("window.codigoEscaneado && window.codigoEscaneado("
                + JSONObject.quote(valor == null ? "" : valor) + "," + JSONObject.quote(error) + ")", null));
    }

    /** Al volver de los ajustes de Android (permiso de «No molestar», wifi…), la web se pone al día. */
    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.nativeVuelta && window.nativeVuelta()", null);
    }

    @Override
    public void onBackPressed() {
        // La web decide: cerrar la hoja o volver al menú. Si ya está en el menú, la app se va al fondo.
        web.evaluateJavascript("(function(){try{return window.atrasNativo ? atrasNativo() : false}catch(e){return false}})()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && NocheService.activo()) {
            NocheService.escuchando = false;
            NocheService.ruido = false;
            NocheService.actualizar(this);
            Modos.restaurar(this);
        }
        super.onDestroy();
    }

    private void abrir(Intent i) {
        runOnUiThread(() -> { try { startActivity(i); } catch (RuntimeException ignored) {} });
    }

    private void pantalla(boolean encendida) {
        runOnUiThread(() -> {
            if (encendida) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // Mientras se monitorea, la pantalla de la app se ve encima del bloqueo: un toque y vuelve «Monitoreando»
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(encendida);
            else if (encendida) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        });
    }

    /** Funciones que la web puede llamar con window.RonquiNoAndroid.xxx() */
    class Puente {
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        @JavascriptInterface public int android() { return Build.VERSION.SDK_INT; }

        /** La pantalla de la noche se puede girar en horizontal (para ver el gráfico en grande); las demás, siempre en vertical.
         *  Respeta el bloqueo de giro del móvil: si está puesto, Android enseña su botoncito para girar. */
        @JavascriptInterface public void girar(boolean libre) {
            runOnUiThread(() -> setRequestedOrientation(libre
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        }

        // ---- Alarmas que suenan con la app cerrada (las programa el reloj de Android) ----
        /** La web manda su lista de alarmas: [{id, on, hora, tono, sub}] */
        @JavascriptInterface public void setAlarmas(String json) { Alarmas.guardar(MainActivity.this, json); }
        /** Entrar con Google con el selector de cuentas de Android; el resultado llega a window.googleNativo(token, error). */
        @JavascriptInterface public void entrarGoogle(String clientId) {
            runOnUiThread(() -> Google.entrar(MainActivity.this, clientId, (token, error) ->
                runOnUiThread(() -> web.evaluateJavascript("window.googleNativo && window.googleNativo("
                    + JSONObject.quote(token) + "," + JSONObject.quote(error) + ")", null))));
        }
        /** Dictar la pregunta a Luna con el reconocimiento de voz de Android; el texto llega a window.dictado(texto, error). */
        @JavascriptInterface public void dictar() {
            runOnUiThread(() -> {
                // Dentro de la app, sin la ventana de Google: el texto va apareciendo en la caja (window.dictadoParcial)
                if (SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                    try {
                        if (reconocedor != null) reconocedor.destroy();
                        reconocedor = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                        reconocedor.setRecognitionListener(new RecognitionListener() {
                            @Override public void onReadyForSpeech(Bundle b) {}
                            @Override public void onBeginningOfSpeech() {}
                            @Override public void onRmsChanged(float v) {}
                            @Override public void onBufferReceived(byte[] b) {}
                            @Override public void onEndOfSpeech() {}
                            @Override public void onEvent(int t, Bundle b) {}
                            @Override public void onError(int e) { dictado("", e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "cancelado" : "error " + e); }
                            @Override public void onPartialResults(Bundle b) {
                                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                                if (r != null && !r.isEmpty()) web.evaluateJavascript("window.dictadoParcial && window.dictadoParcial(" + JSONObject.quote(r.get(0)) + ")", null);
                            }
                            @Override public void onResults(Bundle b) {
                                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                                dictado(r != null && !r.isEmpty() ? r.get(0) : "", r != null && !r.isEmpty() ? "" : "cancelado");
                            }
                        });
                        reconocedor.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
                        return;
                    } catch (RuntimeException ignored) {}
                }
                try {
                    startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Pregúntale a Luna"), REQ_VOZ);
                } catch (RuntimeException e) { dictado("", "sin-dictado"); }
            });
        }
        @JavascriptInterface public void pararDictado() { runOnUiThread(() -> { if (reconocedor != null) reconocedor.stopListening(); }); }
        /** Alarma reforzada: abre el lector de códigos de barras; la respuesta llega a window.codigoEscaneado(valor, error). */
        @JavascriptInterface public void escanearCodigo() {
            runOnUiThread(() -> {
                try {
                    GmsBarcodeScanning.getClient(MainActivity.this).startScan()
                            .addOnSuccessListener(b -> codigoEscaneado(b.getRawValue(), ""))
                            .addOnCanceledListener(() -> codigoEscaneado("", "cancelado"))
                            .addOnFailureListener(e -> codigoEscaneado("", String.valueOf(e.getMessage())));
                } catch (RuntimeException e) { codigoEscaneado("", String.valueOf(e.getMessage())); }
            });
        }
        /** Siesta de «min» minutos; devuelve la hora (ms) a la que sonará. */
        @JavascriptInterface public double siesta(int min, String tono, int sub) { return Alarmas.siesta(MainActivity.this, min, tono, sub); }
        @JavascriptInterface public void cancelarSiesta() { Alarmas.cancelarSiesta(MainActivity.this); }
        @JavascriptInterface public boolean alarmaSonando() { return AlarmaService.sonando; }
        /** ¿Puede la alarma salir a pantalla completa aunque se esté usando el móvil? */
        @JavascriptInterface public boolean puedePantallaCompleta() {
            // Con «Mostrar encima de otras apps» la pantalla de la alarma ya se abre siempre (también con el móvil en uso);
            // el permiso de «notificaciones a pantalla completa» de Android 14 no hace falta además.
            return android.provider.Settings.canDrawOverlays(MainActivity.this);
        }
        /** Abre el ajuste de Android que hace falta («Mostrar sobre otras apps» o «Notificaciones a pantalla completa»). */
        @JavascriptInterface public void pedirPantallaCompleta() {
            runOnUiThread(() -> {
                Intent i;
                i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                try { startActivity(i); } catch (RuntimeException e) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (RuntimeException ignored) {}
                }
            });
        }

        // ---------- No molestar ----------
        @JavascriptInterface public boolean hasDndAccess() { return Modos.dndAcceso(MainActivity.this); }

        @JavascriptInterface public boolean dndActivo() { return Modos.dndActivo(MainActivity.this); }

        /** true si lo puso Amable Sleep (y lo quitará al terminar la noche). */
        @JavascriptInterface public boolean dndMio() { return Modos.dndMio(MainActivity.this); }

        /** Pone (true) o quita (false) «No molestar». Devuelve "ok", "sin-permiso" o "error". */
        @JavascriptInterface public String setDnd(boolean on) {
            if (on) return Modos.ponerDnd(MainActivity.this);
            Modos.quitarDnd(MainActivity.this);
            return "ok";
        }

        /** Abre la pantalla de Android «Acceso a No molestar» (solo hace falta la primera vez). */
        @JavascriptInterface public void pedirDnd() {
            abrir(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        }

        // ---------- Wifi y modo avión ----------
        @JavascriptInterface public boolean wifiEncendido() { return Modos.wifiEncendido(MainActivity.this); }

        /** ¿Puede Amable Sleep apagar el wifi él solo? Solo en Android 9 o anterior. */
        @JavascriptInterface public boolean wifiDirecto() { return Modos.wifiDirecto(); }

        @JavascriptInterface public boolean wifiMio() { return Modos.wifiMio(MainActivity.this); }

        /** Apaga el wifi (Android 9 o anterior) o abre el panel del wifi (Android 10 o más). Devuelve "ok", "panel" o "error". */
        @JavascriptInterface public String apagarWifi() {
            if (Modos.wifiDirecto()) return Modos.apagarWifi(MainActivity.this) ? "ok" : "error";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) abrir(new Intent(Settings.Panel.ACTION_WIFI));
            return "panel";
        }

        @JavascriptInterface public void encenderWifi() { Modos.encenderWifi(MainActivity.this); }
        /** ¿Está puesto el modo avión? (leerlo sí se puede; cambiarlo, no: Android no lo deja a ninguna app) */
        @JavascriptInterface public boolean modoAvion() {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        }
        /** Abre el panel del wifi para encenderlo (desde Android 10 una app no puede encenderlo sola). */
        @JavascriptInterface public void abrirPanelWifi() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) abrir(new Intent(Settings.Panel.ACTION_WIFI));
            else Modos.encenderWifi(MainActivity.this);
        }

        /** Android no deja que ninguna app ponga el modo avión: solo se puede abrir su pantalla de ajustes. */
        @JavascriptInterface public void abrirModoAvion() {
            abrir(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        }

        /** Deja «No molestar» y el wifi como estaban antes de la noche. */
        @JavascriptInterface public void restaurar() { Modos.restaurar(MainActivity.this); }
        /** Luz de la habitación (lux) durante la noche; -1 si el móvil no tiene sensor de luz. */
        @JavascriptInterface public double luz() { return Luz.lux; }
        @JavascriptInterface public boolean micBluetooth() { return Modos.micBluetooth(MainActivity.this); }
        @JavascriptInterface public void micMovil() { Modos.micMovil(MainActivity.this); }
        /** Batería de los pinganillos (%), -1 si no se sabe, -2 si falta el permiso de bluetooth. */
        @JavascriptInterface public int bateriaAuriculares() { return Bateria.auriculares(MainActivity.this); }
        @JavascriptInterface public void pedirBluetooth() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Bateria.permiso(MainActivity.this))
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 4));
        }
        @JavascriptInterface public void audioNormal() { Modos.audioNormal(MainActivity.this); }

        // ---------- Aviso de ronquido por los auriculares ----------
        @JavascriptInterface public boolean auriculares() { return Aviso.auriculares(MainActivity.this); }
        @JavascriptInterface public void prepararVoz() { runOnUiThread(() -> Aviso.preparar(MainActivity.this)); }
        @JavascriptInterface public boolean decir(String texto) { return Aviso.decir(MainActivity.this, texto); }
        /** Voz del móvil para Luna (si la voz de Gemini no llega a tiempo). */
        @JavascriptInterface public void hablar(String texto) { runOnUiThread(() -> Aviso.hablar(MainActivity.this, texto)); }
        @JavascriptInterface public void callar() { Aviso.callar(); }
        /** Abre los ajustes de voz de Android (motor y voz con que lee Luna). */
        @JavascriptInterface public void ajustesVoz() { abrir(new Intent("com.android.settings.TTS_SETTINGS")); }

        // ---------- La noche ----------
        /** Empieza (true) o termina (false) la escucha: pantalla encendida + servicio con el micrófono. Al terminar, todo como estaba. */
        @JavascriptInterface public void noche(boolean on) {
            if (on) Luz.empezar(MainActivity.this); else Luz.parar(MainActivity.this);
            NocheService.escuchando = on;
            NocheService.actualizar(MainActivity.this);
            pantalla(on);
            if (!on) {
                atenuar(false);
                Modos.restaurar(MainActivity.this);
            }
        }

        /** El ruido para dormir empieza o se para: con él sonando, el servicio mantiene el sonido con la pantalla apagada. */
        @JavascriptInterface public void ruido(boolean on) {
            NocheService.ruido = on;
            NocheService.actualizar(MainActivity.this);
        }

        /** Mientras escucha, la pantalla se queda encendida pero al mínimo de brillo. */
        @JavascriptInterface public void atenuar(boolean poco) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = poco ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            });
        }

        /** Compartir una grabación (WhatsApp, correo, Drive…). La web la manda en base64. */
        @JavascriptInterface public boolean compartir(String nombre, String base64) {
            try {
                String limpio = nombre.replaceAll("[^A-Za-z0-9_.-]", "_");
                File dir = ArchivoProvider.carpeta(MainActivity.this);
                File[] viejos = dir.listFiles();
                if (viejos != null) for (File f : viejos) f.delete();
                File f = new File(dir, limpio);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(base64, Base64.DEFAULT)); }
                Uri u = Uri.parse("content://" + ArchivoProvider.AUTORIDAD + "/" + limpio);
                Intent i = new Intent(Intent.ACTION_SEND).setType("audio/wav")
                        .putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setClipData(ClipData.newRawUri(limpio, u));
                abrir(Intent.createChooser(i, "Compartir la grabación"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
