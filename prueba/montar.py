# Une las escenas de prueba/video.sh en un solo vídeo, con un rótulo en inglés y español en cada una.
#   python3 prueba/montar.py clips video-declaracion-permisos.mp4
import os, subprocess, sys, textwrap

CLIPS, OUT = sys.argv[1], sys.argv[2]
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
W, H = 720, 1600
TITULO = ('Amable Sleep: how it uses its permissions', 'Cómo usa Amable Sleep sus permisos')
ESCENAS = [
    ('1. The user taps «Start sleep monitoring»', '1. El usuario pulsa «Empezar a monitorear el sueño»'),
    ('2. Microphone foreground service: visible notification «Amable Sleep is listening»',
     '2. Servicio en primer plano con micrófono: aviso visible «está escuchando»'),
    ('3. Screen off: it keeps listening for snoring until the user taps «End»',
     '3. Con la pantalla apagada sigue escuchando hasta que el usuario pulsa «Fin»'),
    ('4. In the morning: snoring chart and recordings (sample night)',
     '4. Por la mañana: gráfico de ronquidos y grabaciones (noche de ejemplo)'),
    ('5. Sleep music keeps playing with the screen off (media playback foreground service)',
     '5. La música para dormir sigue con la pantalla apagada (servicio de reproducción)'),
    ('6. Alarm clock set by the user rings at the exact time, full screen',
     '6. La alarma que pone el usuario suena a su hora, a pantalla completa'),
]


DURA = [9, 8, 12, 12, 13, 9]  # segundos de cada escena en el vídeo (unos 60 s en total)


def rotulo(en, es, f):
    t = textwrap.fill(en, 38) + '\n\n' + textwrap.fill(es, 40)
    open(f, 'w', encoding='utf-8').write(t)
    return f


def ff(*a):
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', *a], check=True)


TXT = f"drawtext=fontfile={FONT}:textfile={{}}:fontsize=28:fontcolor=white:line_spacing=6:box=1:boxcolor=0x312E81@0.88:boxborderw=20:x=(w-text_w)/2:y={{}}"
COD = ['-an', '-c:v', 'libx264', '-preset', 'medium', '-crf', '22', '-pix_fmt', 'yuv420p', '-r', '30']
partes = []
os.makedirs('montaje', exist_ok=True)
# portada (3 s)
f = rotulo(*TITULO, 'montaje/t0.txt')
ff('-f', 'lavfi', '-i', f'color=c=0x0F0C29:s={W}x{H}:d=3', '-vf', TXT.format(f, '(h-text_h)/2'), *COD, 'montaje/p0.mp4')
partes.append('montaje/p0.mp4')
for i, (en, es) in enumerate(ESCENAS, 1):
    clip = f'{CLIPS}/v{i}.mp4'
    if not os.path.exists(clip):
        print('falta', clip); continue
    f = rotulo(en, es, f'montaje/t{i}.txt')
    d = float(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', clip], capture_output=True, text=True).stdout or 0) + 1.5
    t = DURA[i - 1]; k = min(2.0, d / t) if d > t else 1.0   # más largo de lo previsto: un poco más rápido, y se corta
    vf = f'tpad=stop_mode=clone:stop_duration=1.5,setpts=PTS/{k:.3f},fps=30,scale={W}:{H}:force_original_aspect_ratio=decrease,pad={W}:{H}:(ow-iw)/2:(oh-ih)/2:color=black,' + TXT.format(f, 'h-text_h-150')
    ff('-i', clip, '-vf', vf, '-t', str(t), *COD, f'montaje/p{i}.mp4')
    partes.append(f'montaje/p{i}.mp4')
open('montaje/lista.txt', 'w').write(''.join(f"file '{os.path.basename(p)}'\n" for p in partes))
ff('-f', 'concat', '-safe', '0', '-i', 'montaje/lista.txt', '-c', 'copy', '-movflags', '+faststart', OUT)
d = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout
print('vídeo', OUT, 'dura', d.strip(), 's')
