# Toca de verdad (adb input tap) un elemento de la web de la app, como haría un dedo.
#   python3 prueba/toca.py "#startBtn"        -> lo pone a la vista y lo toca
#   python3 prueba/toca.py "#startBtn" donde  -> solo dice «x y» en la pantalla
# Si no encuentra dónde está la web en la pantalla, lo pulsa por JavaScript.
import json, re, subprocess, sys

sel = sys.argv[1]
js = """(() => { const e = document.querySelector(%s); if (!e) return null;
  e.scrollIntoView({block: 'center'}); const r = e.getBoundingClientRect();
  return [r.x + r.width / 2, r.y + r.height / 2, devicePixelRatio]; })()""" % json.dumps(sel)
r = json.loads(subprocess.run(['python3', 'prueba/cdp.py', js], capture_output=True, text=True).stdout or 'null')
if not r:
    sys.exit('No encuentro ' + sel)
xml = subprocess.run(['adb', 'exec-out', 'uiautomator', 'dump', '/dev/tty'], capture_output=True, text=True).stdout
m = re.search(r'class="android\.webkit\.WebView"[^>]*?bounds="\[(\d+),(\d+)\]', xml)
if not m:
    subprocess.run(['python3', 'prueba/cdp.py', 'document.querySelector(%s).click(); "js"' % json.dumps(sel)])
    sys.exit(0)
x, y = int(m[1]) + round(r[0] * r[2]), int(m[2]) + round(r[1] * r[2])
if len(sys.argv) > 2:
    print(x, y)
else:
    subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
