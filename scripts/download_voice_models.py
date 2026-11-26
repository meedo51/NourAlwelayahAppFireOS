#!/usr/bin/env python3
"""
Nour Alwelayah Offline Voice Assistant — sherpa-onnx Model Provisioning Script

Self-contained, zero-dependency Python 3 script that downloads the official
k2-fsa/sherpa-onnx release models and stages them into:
  app/src/main/assets/voice_models/

Target Directory Layout (matched to VoiceModelManager.kt):
  app/src/main/assets/voice_models/
    ├── silero_vad.onnx
    ├── encoder.int8.onnx
    ├── decoder.int8.onnx
    ├── tokens.txt
    ├── tts_ar/
    │   ├── model.onnx
    │   ├── tokens.txt
    │   └── espeak-ng-data/
    └── tts_en/
        ├── model.onnx
        ├── tokens.txt
        └── espeak-ng-data/
"""

import argparse
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

SILERO_VAD_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
)
WHISPER_TINY_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
)
TTS_AR_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ar_JO-kareem-medium.tar.bz2"
)
TTS_EN_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2"
)


def format_size(num_bytes: int) -> str:
    if num_bytes >= 1024 * 1024:
        return f"{num_bytes / (1024 * 1024):.2f} MB ({num_bytes:,} bytes)"
    if num_bytes >= 1024:
        return f"{num_bytes / 1024:.1f} KB ({num_bytes:,} bytes)"
    return f"{num_bytes} bytes"


def download_file(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = dest.with_suffix(dest.suffix + ".part")
    print(f"[DOWNLOAD] {url}")
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "NourAlwelayah-ModelProvisioner/1.0"},
    )
    with urllib.request.urlopen(req, timeout=180) as resp, open(tmp_path, "wb") as out:
        shutil.copyfileobj(resp, out, length=1024 * 256)
    tmp_path.replace(dest)
    print(f"       -> Saved {dest.name} ({format_size(dest.stat().st_size)})")


def provision_silero_vad(target_root: Path, force: bool) -> None:
    vad_dest = target_root / "silero_vad.onnx"
    if vad_dest.is_file() and vad_dest.stat().st_size > 100_000 and not force:
        print(f"[SKIP] {vad_dest} already exists ({format_size(vad_dest.stat().st_size)})")
        return
    download_file(SILERO_VAD_URL, vad_dest)


def provision_whisper_tiny_int8(target_root: Path, force: bool) -> None:
    enc_dest = target_root / "encoder.int8.onnx"
    dec_dest = target_root / "decoder.int8.onnx"
    tok_dest = target_root / "tokens.txt"

    if (
        not force
        and enc_dest.is_file()
        and enc_dest.stat().st_size > 1_000_000
        and dec_dest.is_file()
        and dec_dest.stat().st_size > 1_000_000
        and tok_dest.is_file()
        and tok_dest.stat().st_size > 1_000
    ):
        print("[SKIP] Whisper tiny INT8 models already exist")
        return

    with tempfile.TemporaryDirectory(prefix="nour_whisper_") as tmp_dir:
        tmp_dir_path = Path(tmp_dir)
        archive_path = tmp_dir_path / "sherpa-onnx-whisper-tiny.tar.bz2"
        download_file(WHISPER_TINY_URL, archive_path)

        print("[EXTRACT] Extracting Whisper tiny INT8 files...")
        with tarfile.open(archive_path, "r:bz2") as tar:
            for member in tar.getmembers():
                if not member.isfile():
                    continue
                base_name = Path(member.name).name
                if base_name == "tiny-encoder.int8.onnx":
                    with tar.extractfile(member) as src, open(enc_dest, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                elif base_name == "tiny-decoder.int8.onnx":
                    with tar.extractfile(member) as src, open(dec_dest, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                elif base_name == "tiny-tokens.txt":
                    with tar.extractfile(member) as src, open(tok_dest, "wb") as dst:
                        shutil.copyfileobj(src, dst)

    for f in (enc_dest, dec_dest, tok_dest):
        if not f.is_file() or f.stat().st_size == 0:
            raise RuntimeError(f"Failed to provision required Whisper file: {f}")
        print(f"       -> Staged {f.relative_to(target_root)} ({format_size(f.stat().st_size)})")


def provision_piper_tts(url: str, lang: str, target_root: Path, force: bool) -> None:
    tts_dir = target_root / f"tts_{lang}"
    model_dest = tts_dir / "model.onnx"
    tokens_dest = tts_dir / "tokens.txt"
    espeak_dest = tts_dir / "espeak-ng-data"

    if (
        not force
        and model_dest.is_file()
        and model_dest.stat().st_size > 1_000_000
        and tokens_dest.is_file()
        and tokens_dest.stat().st_size > 10
        and espeak_dest.is_dir()
        and any(espeak_dest.iterdir())
    ):
        print(f"[SKIP] Piper VITS TTS ({lang}) already exists in {tts_dir}")
        return

    tts_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"nour_tts_{lang}_") as tmp_dir:
        tmp_dir_path = Path(tmp_dir)
        archive_path = tmp_dir_path / f"tts_{lang}.tar.bz2"
        download_file(url, archive_path)

        print(f"[EXTRACT] Extracting Piper VITS TTS ({lang}) archive...")
        extract_root = tmp_dir_path / "extracted"
        extract_root.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive_path, "r:bz2") as tar:
            tar.extractall(path=extract_root)

        # Locate the extracted .onnx model, tokens.txt, and espeak-ng-data/
        onnx_candidates = [
            p for p in extract_root.rglob("*.onnx")
            if p.is_file() and not p.name.startswith("._")
        ]
        if not onnx_candidates:
            raise RuntimeError(f"No .onnx model found in {url}")
        onnx_src = max(onnx_candidates, key=lambda p: p.stat().st_size)
        shutil.copy2(onnx_src, model_dest)

        tokens_candidates = [
            p for p in extract_root.rglob("tokens.txt")
            if p.is_file() and not p.name.startswith("._")
        ]
        if not tokens_candidates:
            raise RuntimeError(f"No tokens.txt found in {url}")
        shutil.copy2(tokens_candidates[0], tokens_dest)

        espeak_candidates = [
            p for p in extract_root.rglob("espeak-ng-data")
            if p.is_dir()
        ]
        if not espeak_candidates:
            raise RuntimeError(f"No espeak-ng-data directory found in {url}")
        if espeak_dest.exists():
            shutil.rmtree(espeak_dest)
        shutil.copytree(espeak_candidates[0], espeak_dest)

    print(f"       -> Staged tts_{lang}/model.onnx ({format_size(model_dest.stat().st_size)})")
    print(f"       -> Staged tts_{lang}/tokens.txt ({format_size(tokens_dest.stat().st_size)})")
    espeak_files = sum(1 for _ in espeak_dest.rglob("*") if _.is_file())
    print(f"       -> Staged tts_{lang}/espeak-ng-data/ ({espeak_files} files)")


def verify_layout(target_root: Path) -> None:
    required_files = [
        target_root / "silero_vad.onnx",
        target_root / "encoder.int8.onnx",
        target_root / "decoder.int8.onnx",
        target_root / "tokens.txt",
        target_root / "tts_ar" / "model.onnx",
        target_root / "tts_ar" / "tokens.txt",
        target_root / "tts_en" / "model.onnx",
        target_root / "tts_en" / "tokens.txt",
    ]
    required_dirs = [
        target_root / "tts_ar" / "espeak-ng-data",
        target_root / "tts_en" / "espeak-ng-data",
    ]

    print("\n=== Staged sherpa-onnx Voice Models Summary ===")
    total_bytes = 0
    for f in required_files:
        if not f.is_file() or f.stat().st_size == 0:
            raise RuntimeError(f"Verification failed — missing or empty file: {f}")
        sz = f.stat().st_size
        total_bytes += sz
        print(f"  [OK] {f.relative_to(target_root)!s:<28} : {format_size(sz)}")

    for d in required_dirs:
        if not d.is_dir() or not any(d.iterdir()):
            raise RuntimeError(f"Verification failed — missing or empty directory: {d}")
        dir_bytes = sum(p.stat().st_size for p in d.rglob("*") if p.is_file())
        dir_count = sum(1 for p in d.rglob("*") if p.is_file())
        total_bytes += dir_bytes
        print(f"  [OK] {d.relative_to(target_root)!s:<28} : {dir_count} files, {format_size(dir_bytes)}")

    print(f"Total Voice Models Footprint: {format_size(total_bytes)}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Download and stage sherpa-onnx models for Nour Alwelayah Appliance."
    )
    parser.add_argument(
        "--target-dir",
        default="app/src/main/assets/voice_models",
        help="Target directory for staged voice models (default: app/src/main/assets/voice_models)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-download and overwrite existing model files",
    )
    args = parser.parse_args()

    target_root = Path(args.target_dir).resolve()
    target_root.mkdir(parents=True, exist_ok=True)

    provision_silero_vad(target_root, args.force)
    provision_whisper_tiny_int8(target_root, args.force)
    provision_piper_tts(TTS_AR_URL, "ar", target_root, args.force)
    provision_piper_tts(TTS_EN_URL, "en", target_root, args.force)
    verify_layout(target_root)


if __name__ == "__main__":
    main()
