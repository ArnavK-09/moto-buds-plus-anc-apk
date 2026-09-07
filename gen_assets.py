import os
import re
import subprocess

SRC = "/home/dopamide/Projects/moto-buds-plus-gnome-extension/icons/hicolor/scalable/actions"
OUT = "/home/dopamide/Projects/moto-buds-plus-apk/app/src/main/res/drawable-nodpi"
os.makedirs(OUT, exist_ok=True)

def path_data(svg_path):
    s = open(svg_path).read()
    ms = re.findall(r'd=\"([^\"]+)\"', s)
    return " ".join(ms)

def save_svg(path, content):
    open(path, "w").write(content)

def convert(src_svg, out_png, w, h):
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), "-b", "transparent", src_svg, "-o", out_png], check=True)

icons = {
    "ic_earbuds": "bbm-earbuds-stem-symbolic.svg",
    "ic_anc_off": "bbm-anc-off-symbolic.svg",
    "ic_transparency": "bbm-transperancy-symbolic.svg",
    "ic_anc_on": "bbm-anc-on-symbolic.svg",
    "ic_adaptive": "bbm-adaptive-symbolic.svg",
}

for name, src in icons.items():
    d = path_data(os.path.join(SRC, src))
    svg = f'<svg width="96" height="96" viewBox="0 0 96 96" xmlns="http://www.w3.org/2000/svg"><g fill="#FFFFFF"><g transform="scale(6)"><path d="{d}"/></g></g></svg>'
    tmp = os.path.join(OUT, name + ".svg")
    save_svg(tmp, svg)
    convert(tmp, os.path.join(OUT, name + ".png"), 96, 96)
    os.remove(tmp)

battery = '<svg width="48" height="48" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg"><path fill="#FFFFFF" d="M15,6 h18 v6 h6 v30 H9 V12 h6 z M18,12 v24 h12 V12 z"/></svg>'
tmp = os.path.join(OUT, "ic_battery.svg")
save_svg(tmp, battery)
convert(tmp, os.path.join(OUT, "ic_battery.png"), 48, 48)
os.remove(tmp)

sound = '<svg width="48" height="48" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg"><path fill="#FFFFFF" d="M9,18 h6 v18 H9 z M21,12 h6 v24 h-6 z M33,6 h6 v30 h-6 z"/></svg>'
tmp = os.path.join(OUT, "ic_sound.svg")
save_svg(tmp, sound)
convert(tmp, os.path.join(OUT, "ic_sound.png"), 48, 48)
os.remove(tmp)

gestures = '<svg width="48" height="48" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg"><path fill="#FFFFFF" d="M24,6 a6,6 0 1,0 0.01,0 M15,18 c0,-4.97 4.03,-9 9,-9 s9,4.03 9,9 v15 h3 v6 H15 v-6 h3V18 z"/></svg>'
tmp = os.path.join(OUT, "ic_gestures.svg")
save_svg(tmp, gestures)
convert(tmp, os.path.join(OUT, "ic_gestures.png"), 48, 48)
os.remove(tmp)

more = '<svg width="48" height="48" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg"><path fill="#FFFFFF" d="M6,6 h9 v9 H6 z M19,6 h9 v9 h-9 z M32,6 h9 v9 h-9 z M6,19 h9 v9 H6 z M19,19 h9 v9 h-9 z M32,19 h9 v9 h-9 z M6,32 h9 v9 H6 z M19,32 h9 v9 h-9 z M32,32 h9 v9 h-9 z"/></svg>'
tmp = os.path.join(OUT, "ic_more.svg")
save_svg(tmp, more)
convert(tmp, os.path.join(OUT, "ic_more.png"), 48, 48)
os.remove(tmp)

# launcher foreground
d = path_data(os.path.join(SRC, "bbm-earbuds-stem-symbolic.svg"))
launcher = f'<svg width="108" height="108" viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg"><g fill="#FFFFFF"><g transform="translate(24,24) scale(3.75)"><path d="{d}"/></g></g></svg>'
tmp = os.path.join(OUT, "ic_launcher_foreground.svg")
save_svg(tmp, launcher)
convert(tmp, os.path.join(OUT, "ic_launcher_foreground.png"), 108, 108)
os.remove(tmp)

# hero
d = path_data(os.path.join(SRC, "bbm-earbuds-stem-symbolic.svg"))
hero = f'<svg width="800" height="800" viewBox="0 0 800 800" xmlns="http://www.w3.org/2000/svg"><circle cx="400" cy="400" r="360" fill="#1E2330"/><circle cx="400" cy="420" r="320" fill="none" stroke="#3A4050" stroke-width="4"/><g fill="#FFFFFF"><g transform="translate(280,280) scale(15)"><path d="{d}"/></g></g></svg>'
tmp = os.path.join(OUT, "hero_earbuds.svg")
save_svg(tmp, hero)
convert(tmp, os.path.join(OUT, "hero_earbuds.png"), 800, 800)
os.remove(tmp)

print("assets generated")
for f in sorted(os.listdir(OUT)):
    print(f, os.path.getsize(os.path.join(OUT, f)))
