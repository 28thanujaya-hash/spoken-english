"""Builds the voice pack (files the app downloads OUTSIDE the APK) and writes voice.json.
Run by .github/workflows/voice-pack.yml. Every input is pinned by sha256; the build stops if anything differs.
Pack voice-0 = run 0: stock Paradee-8M v1.3 (before Sam and Maya are trained)."""
import gzip, hashlib, io, json, os, subprocess, sys, urllib.request, zipfile

PACK = "voice-0"
REPO = "28thanujaya-hash/spoken-english-updates"
OUT = sys.argv[1] if len(sys.argv) > 1 else "pack"
HERE = os.path.dirname(os.path.abspath(__file__))
HF = "https://huggingface.co/sahilmahendrakar/Paradee-8M-v1.3/resolve/main/"
ORT = "1.31.1"
PINS = {  # uncompressed sha256 of every file in the pack (checked 13 Nov 2026)
    "libonnxruntime.so": "46e0c16cd515c62a1dd6c5994448b5413705478354bc20a756a5a82634c3ca03",
    "paradee_int8.onnx": "f68ed87562d040693dd0dd34e99c11b35ca24294e2149b840503bd1f25a2199f",
    "misaki-en-us.lex": "fbf09090cff0a41f3f579bf03ab9fc42710b04d7284767b10467a5f182c23925",
    "config.json": "021428dbdf35d2cc1d25f66e012ca2fef0439326eb3d60bea79955e223fdd713",
}

def get(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()

os.makedirs(OUT, exist_ok=True)
data = {}
aar = get(f"https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/{ORT}/onnxruntime-android-{ORT}.aar")
data["libonnxruntime.so"] = zipfile.ZipFile(io.BytesIO(aar)).read("jni/arm64-v8a/libonnxruntime.so")
data["paradee_int8.onnx"] = get(HF + "onnx/paradee_int8.onnx")
data["config.json"] = get(HF + "config.json")
lex = os.path.join(OUT, "misaki-en-us.lex.raw")
subprocess.check_call([sys.executable, os.path.join(HERE, "convert_misaki_lexicon.py"), "--fetch", os.path.join(OUT, "cache"), "-o", lex])
data["misaki-en-us.lex"] = open(lex, "rb").read()

files = []
for name, b in data.items():
    sha = hashlib.sha256(b).hexdigest()
    if sha != PINS[name]:
        sys.exit(f"{name}: sha256 {sha} differs from the pinned {PINS[name]}")
    gz = gzip.compress(b, 9, mtime=0)
    open(os.path.join(OUT, name + ".gz"), "wb").write(gz)
    files.append(dict(name=name, url=f"https://github.com/{REPO}/releases/download/{PACK}/{name}.gz",
                      sha256=sha, size=len(b), gzsize=len(gz)))
manifest = dict(version=PACK, note="Run 0: stock Paradee-8M v1.3 int8 (Apache-2.0), misaki 0.9.4 dictionary (Apache-2.0), ONNX Runtime %s arm64 (MIT)." % ORT, files=files)
json.dump(manifest, open(os.path.join(OUT, "voice.json"), "w"), indent=1)
print(json.dumps(manifest, indent=1))
