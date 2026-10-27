#!/bin/bash
# Vídeo para Google Play (declaración de permisos): graba la pantalla del emulador, escena a escena.
#  1 Empezar la noche · 2 aviso «está escuchando» (servicio con micrófono) · 3 pantalla apagada
#  4 la mañana: gráfico y grabaciones (noche de ejemplo) · 5 música para dormir (servicio de reproducción)
#  6 alarma a pantalla completa (alarma exacta + pantalla completa)
# Deja las escenas en clips/ y luego prueba/montar.py las une con los rótulos.
set -uo pipefail
PKG=club.practicaintegral.ronquino
J() { python3 prueba/cdp.py "$1"; }
T() { python3 prueba/toca.py "$1"; }
mkdir -p clips
grabar() { adb shell screenrecord --bit-rate 8000000 --time-limit 170 /sdcard/v$1.mp4 & REC=$!; sleep 1.5; }
parar() { adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 1.5; adb pull /sdcard/v$1.mp4 clips/v$1.mp4 >/dev/null; echo "🎬 escena $1"; }
conectar() {
  for i in $(seq 1 30); do
    sleep 4
    PID=$(adb shell pidof $PKG | tr -d '\r')
    [ -n "$PID" ] && adb forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null 2>&1
    OK=$(J "window.__EN && window.__EN.DB ? 'si' : 'no'" 2>/dev/null || true)
    echo "intento $i: $OK"; [ "$OK" = '"si"' ] || [ "$OK" = "si" ] && break
  done
}

adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in RECORD_AUDIO POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
adb shell settings put system show_touches 1          # se ve dónde toca el dedo
adb shell settings put system screen_off_timeout 600000
adb shell settings put secure immersive_mode_confirmations confirmed   # sin el aviso «Viewing full screen»
adb shell svc power stayon true
adb shell cmd uimode night yes || true
adb shell am start -n $PKG/.MainActivity
conectar
# Primera vez ya vista (bienvenida, permisos) y las noches de ejemplo de «Lucía»
J "['ronquino-tour-visto','ronquino-permisos-vistos','ronquino-mic-ok'].forEach(k => localStorage.setItem(k, '1')); localStorage.setItem('ronquino-pais', 'España'); localStorage.setItem('ronquino-compartir', '0'); 'ok'"
echo "Noche de ejemplo: $(J "$(cat prueba/demo.js)")"
J "location.reload(); 'ok'"; sleep 3; conectar
LIMPIA="(() => { document.getElementById('splash')?.remove(); for (const id of ['cuentaPanel', 'apkBanner']) { const x = document.getElementById(id); if (x) x.hidden = true; } window.scrollTo(0, 0); return 'ok'; })()"
DESPIERTA="document.getElementById('listen').dispatchEvent(new PointerEvent('pointerdown', {bubbles: true})); 'ok'"   # la pantalla de monitoreo se oscurece a los 15 s
PERSIANA() { adb shell input swipe 540 5 540 1300 450; sleep 1.2; adb shell input swipe 540 600 540 1800 450; }   # baja la persiana de avisos con el dedo
sleep 6; J "$LIMPIA"; sleep 2
J "window.alert = m => { window.__alertas = (window.__alertas || []).concat(m); }; 'ok'"

# ---------- 1. Empezar la noche ----------
grabar 1
sleep 2.5; T '#goSnore'; sleep 2.5
T '#startBtn'; sleep 6
parar 1
echo "Escuchando: $(J "document.getElementById('listen').classList.contains('on')") · avisos: $(J 'window.__alertas || []')"
adb shell dumpsys activity services $PKG | grep -i "isForeground\|foregroundServiceType\|types=" | head -5

# ---------- 2. El aviso del servicio en primer plano (micrófono) ----------
J "$DESPIERTA"
grabar 2
sleep 1; PERSIANA; sleep 5
adb shell cmd statusbar collapse; sleep 1.5
parar 2

# ---------- 3. Pantalla apagada: sigue escuchando ----------
J "$DESPIERTA"
grabar 3
sleep 1.5; adb shell input keyevent KEYCODE_SLEEP; sleep 4
adb shell input keyevent KEYCODE_WAKEUP; sleep 1; J "$DESPIERTA"; sleep 2.5
PERSIANA; sleep 3.5
adb shell cmd statusbar collapse; adb shell wm dismiss-keyguard; sleep 2.5
parar 3
echo "Sigue escuchando tras apagar: $(J "document.getElementById('listen').classList.contains('on')")"

# (fuera de cámara) fin de la noche de prueba: se borra, es de 1 minuto
J "$DESPIERTA"; J "document.getElementById('stopBtn').click(); 'ok'"
# Pueden salir dos ventanas: «lo que queda por deshacer» y «¿Guardar esta grabación?» (se borra: era de prueba)
for i in $(seq 1 16); do
  sleep 1.5
  T_ASK=$(J "document.getElementById('ask').hidden ? '' : document.getElementById('askT').textContent")
  echo "ventana: $T_ASK"
  case "$T_ASK" in
    *Guardar*) J "document.getElementById('askA').click(); 'borrada'"; sleep 3; break ;;
    '""'|'') ;;
    *) J "document.getElementById('askA').click(); 'cerrada'" ;;
  esac
done
J "$LIMPIA"

# ---------- 4. Por la mañana: el gráfico y las grabaciones ----------
grabar 4
sleep 1.5; T '#histDet > summary'; sleep 2   # abre el historial de grabaciones
J "(() => { const id = +localStorage.getItem('demo-id'); document.querySelectorAll('#nights .night').forEach(x => { if (x._id === id) x.setAttribute('data-demo', '1'); }); return !!document.querySelector('#nights [data-demo]'); })()"
T '#nights [data-demo]'; sleep 4
T '#tlTit'; sleep 3
adb shell input swipe 540 1700 540 1000 600; sleep 4
parar 4

# ---------- 5. Música para dormir (servicio de reproducción) ----------
J "document.getElementById('backBtn').click(); 'ok'"; sleep 1.5
J "document.querySelectorAll('section').forEach(s => s.hidden = s.id !== 'menu'); window.scrollTo(0, 0); 'ok'"; sleep 1
grabar 5
sleep 1.5; T '#menu [data-go=alarma]'; sleep 2
T '#musDet > summary'; sleep 2
T '#musBody [data-c]'; sleep 3
PERSIANA; sleep 4
adb shell cmd statusbar collapse; adb shell wm dismiss-keyguard; sleep 0.5; J "$LIMPIA"; sleep 1.5
parar 5
adb shell dumpsys activity services $PKG | grep -i "isForeground\|types=" | head -5
J "document.querySelector('#musBody .cuna.on')?.click(); 'parada'"

# ---------- 6. La alarma suena a pantalla completa ----------
J "window.RonquiNoAndroid.siesta(1, 'amanecer', 0)"
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell input keyevent KEYCODE_SLEEP
sleep 50
grabar 6
for i in $(seq 1 40); do sleep 1; [ "$(adb shell dumpsys activity activities | grep -c 'AlarmaActivity')" != 0 ] && break; done
if ! kill -0 $REC 2>/dev/null; then grabar 6; fi   # (si la pantalla apagada cortó la grabación)
sleep 4
XY=$(python3 prueba/ui.py tap "Apagar"); [ -n "$XY" ] && adb shell input tap $XY
sleep 3
parar 6
adb exec-out screencap -p > captura-final.png
ls -la clips
