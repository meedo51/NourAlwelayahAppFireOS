#!/usr/bin/env python3
"""
Nour Alwelayah Knowledge Base Compiler (CSV -> SQLite FTS5 + Embeddings)

Compiles one or more bilingual (Arabic/English) CSV files into a single,
read-only, OTA-ready SQLite database (`nour_kb.db`) for Android Room/SQLite.

Usage:
  # Full build with multilingual ONNX embeddings (recommended for production):
  python compile_kb.py nour_alwelayah_appliance_commands_kb.csv -o nour_kb.db

  # Merge an entire folder of CSVs (e.g., commands + fiqh + aqeedah):
  python compile_kb.py ./kb_csvs/ -o nour_kb.db --version 1.0.0

  # Fast lexical-only build (FTS5 only, skips embedding model download):
  python compile_kb.py nour_alwelayah_appliance_commands_kb.csv -o nour_kb.db --no-embeddings

Dependencies for full build:
  pip install fastembed numpy
"""

import argparse
import csv
import datetime
import hashlib
import os
import re
import sqlite3
import struct
import sys
from pathlib import Path
from typing import Dict, List, Tuple

REQUIRED_COLUMNS = [
    "intent_id", "status", "category", "subcategory", "marja_scope",
    "source_ref_en", "source_ref_ar", "patterns_en", "keywords_en",
    "response_en", "patterns_ar", "keywords_ar", "response_ar",
    "action_type", "audio_asset_ar", "audio_asset_en", "command_payload",
    "priority", "last_verified"
]

VALID_STATUSES = {"published", "draft", "review"}
VALID_ACTIONS = {"tts_only", "play_audio", "app_command"}

# Arabic diacritics (tashkeel) and tatweel
ARABIC_DIACRITICS_RE = re.compile(r"[\u0617-\u061A\u064B-\u0652\u0670\u0640]")
NON_WORD_AR_RE = re.compile(r"[^\u0600-\u06FF0-9a-zA-Z\s]")
NON_WORD_EN_RE = re.compile(r"[^a-z0-9\s]")
MULTI_SPACE_RE = re.compile(r"\s+")

# Common Shia & appliance English transliteration normalizations
EN_TRANSLITERATIONS = {
    "hussein": "husayn",
    "hussain": "husayn",
    "hossein": "husayn",
    "adhan": "azan",
    "athaan": "azan",
    "azaan": "azan",
    "kumail": "kumayl",
    "ziyarah": "ziyarat",
    "ashoora": "ashura",
    "sajdah": "sujud",
    "salat": "salah",
    "marjaa": "marja",
    "wudhu": "wudu",
    "wudoo": "wudu",
}


def normalize_ar(text: str) -> str:
    if not text:
        return ""
    text = ARABIC_DIACRITICS_RE.sub("", text)
    text = text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    text = text.replace("ة", "ه").replace("ى", "ي")
    text = NON_WORD_AR_RE.sub(" ", text)
    return MULTI_SPACE_RE.sub(" ", text).strip()


def normalize_en(text: str) -> str:
    if not text:
        return ""
    text = text.lower()
    text = NON_WORD_EN_RE.sub(" ", text)
    tokens = [EN_TRANSLITERATIONS.get(tok, tok) for tok in text.split()]
    return " ".join(tokens).strip()


def collect_csv_files(inputs: List[str]) -> List[Path]:
    files: List[Path] = []
    for item in inputs:
        p = Path(item)
        if p.is_dir():
            files.extend(sorted(p.glob("*.csv")))
        elif p.is_file() and p.suffix.lower() == ".csv":
            files.append(p)
        else:
            raise FileNotFoundError(f"Invalid CSV input path: {item}")
    if not files:
        raise ValueError("No CSV files found to compile.")
    return files


def load_and_validate_csvs(files: List[Path]) -> Tuple[List[Dict[str, str]], List[str]]:
    published_rows: List[Dict[str, str]] = []
    errors: List[str] = []
    seen_ids = set()

    for csv_path in files:
        with open(csv_path, mode="r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            if not reader.fieldnames:
                errors.append(f"[{csv_path.name}] Empty CSV file.")
                continue

            missing_cols = [c for c in REQUIRED_COLUMNS if c not in reader.fieldnames]
            if missing_cols:
                errors.append(f"[{csv_path.name}] Missing columns: {missing_cols}")
                continue

            for row_idx, row in enumerate(reader, start=2):
                # Clean whitespace on all fields
                row = {k: (v.strip() if v is not None else "") for k, v in row.items()}
                intent_id = row["intent_id"]

                if not intent_id:
                    errors.append(f"[{csv_path.name}:{row_idx}] Missing intent_id.")
                    continue
                if intent_id in seen_ids:
                    errors.append(f"[{csv_path.name}:{row_idx}] Duplicate intent_id: '{intent_id}'.")
                    continue
                seen_ids.add(intent_id)

                status = row["status"].lower()
                if status not in VALID_STATUSES:
                    errors.append(f"[{csv_path.name}:{row_idx}] Invalid status '{status}' for '{intent_id}'.")
                    continue
                if status != "published":
                    continue  # Skip draft/review rows

                action = row["action_type"].lower()
                if action not in VALID_ACTIONS:
                    errors.append(f"[{csv_path.name}:{row_idx}] Invalid action_type '{action}' for '{intent_id}'.")
                    continue

                if action == "app_command" and not row["command_payload"]:
                    errors.append(f"[{csv_path.name}:{row_idx}] 'app_command' requires command_payload for '{intent_id}'.")
                if action == "play_audio" and not (row["audio_asset_ar"] or row["audio_asset_en"]):
                    errors.append(f"[{csv_path.name}:{row_idx}] 'play_audio' requires audio_asset for '{intent_id}'.")

                if not row["patterns_en"] and not row["patterns_ar"]:
                    errors.append(f"[{csv_path.name}:{row_idx}] At least one pattern (EN or AR) required for '{intent_id}'.")
                if not row["response_en"] or not row["response_ar"]:
                    errors.append(f"[{csv_path.name}:{row_idx}] Both response_en and response_ar are required for '{intent_id}'.")

                try:
                    int(row["priority"] or "2")
                except ValueError:
                    errors.append(f"[{csv_path.name}:{row_idx}] Priority must be an integer for '{intent_id}'.")

                published_rows.append(row)

    return published_rows, errors


def split_pipe(value: str) -> List[str]:
    return [part.strip() for part in value.split("|") if part.strip()]


def pack_float16(vec) -> bytes:
    """Packs a 1D float vector into compact IEEE-754 float16 bytes (2 bytes/dim)."""
    return struct.pack(f"<{len(vec)}e", *[float(x) for x in vec])


def generate_embeddings(texts: List[str], model_name: str) -> Tuple[List[bytes], int]:
    try:
        from fastembed import TextEmbedding
    except ImportError:
        print(
            "ERROR: 'fastembed' is not installed.\n"
            "Install it with:  pip install fastembed numpy\n"
            "Or run with --no-embeddings for a lexical FTS5-only build.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"Loading ONNX embedding model: {model_name} ...")
    model = TextEmbedding(model_name=model_name)
    # E5 models expect 'passage: ' prefix for indexed documents (and 'query: ' on Android)
    prefixed = [f"passage: {t}" for t in texts]
    vectors = list(model.embed(prefixed))
    dim = len(vectors[0]) if vectors else 0
    packed = [pack_float16(v) for v in vectors]
    return packed, dim


def build_sqlite_db(
    rows: List[Dict[str, str]],
    output_path: Path,
    kb_version: str,
    use_embeddings: bool,
    model_name: str,
) -> None:
    if output_path.exists():
        output_path.unlink()

    # Prepare pattern records
    pattern_records = []  # (intent_id, lang, raw_text, normalized_text, is_keyword)
    for r in rows:
        iid = r["intent_id"]
        for p in split_pipe(r["patterns_en"]):
            norm = normalize_en(p)
            if norm:
                pattern_records.append((iid, "en", p, norm, 0))
        for p in split_pipe(r["patterns_ar"]):
            norm = normalize_ar(p)
            if norm:
                pattern_records.append((iid, "ar", p, norm, 0))

        # Store keywords as a single boosted lexical string per language
        kw_en = normalize_en(" ".join(split_pipe(r["keywords_en"])))
        if kw_en:
            pattern_records.append((iid, "en", r["keywords_en"], kw_en, 1))
        kw_ar = normalize_ar(" ".join(split_pipe(r["keywords_ar"])))
        if kw_ar:
            pattern_records.append((iid, "ar", r["keywords_ar"], kw_ar, 1))

    # Generate embeddings for natural patterns (skip keyword-only rows for embeddings)
    embeddings_map: Dict[int, bytes] = {}
    emb_dim = 0
    if use_embeddings and pattern_records:
        pattern_indices = [i for i, rec in enumerate(pattern_records) if rec[4] == 0]
        texts_to_embed = [pattern_records[i][3] for i in pattern_indices]
        packed_vecs, emb_dim = generate_embeddings(texts_to_embed, model_name)
        for idx, blob in zip(pattern_indices, packed_vecs):
            embeddings_map[idx] = blob

    conn = sqlite3.connect(str(output_path))
    cur = conn.cursor()

    cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;

        CREATE TABLE kb_meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE intents (
            intent_id TEXT PRIMARY KEY,
            category TEXT NOT NULL,
            subcategory TEXT NOT NULL,
            marja_scope TEXT NOT NULL,
            source_ref_en TEXT NOT NULL,
            source_ref_ar TEXT NOT NULL,
            response_en TEXT NOT NULL,
            response_ar TEXT NOT NULL,
            action_type TEXT NOT NULL,
            audio_asset_ar TEXT,
            audio_asset_en TEXT,
            command_payload TEXT,
            priority INTEGER NOT NULL DEFAULT 2,
            last_verified TEXT NOT NULL
        );

        CREATE TABLE intent_patterns (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            intent_id TEXT NOT NULL REFERENCES intents(intent_id),
            lang TEXT NOT NULL,
            raw_text TEXT NOT NULL,
            normalized_text TEXT NOT NULL,
            is_keyword INTEGER NOT NULL DEFAULT 0,
            embedding BLOB
        );

        CREATE INDEX idx_patterns_intent ON intent_patterns(intent_id);
        CREATE INDEX idx_patterns_lang ON intent_patterns(lang);

        CREATE VIRTUAL TABLE intent_patterns_fts USING fts5(
            normalized_text,
            intent_id UNINDEXED,
            lang UNINDEXED,
            is_keyword UNINDEXED,
            content='intent_patterns',
            content_rowid='id',
            tokenize='unicode61'
        );
    """)

    for r in rows:
        cur.execute(
            """
            INSERT INTO intents (
                intent_id, category, subcategory, marja_scope,
                source_ref_en, source_ref_ar, response_en, response_ar,
                action_type, audio_asset_ar, audio_asset_en, command_payload,
                priority, last_verified
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                r["intent_id"], r["category"], r["subcategory"], r["marja_scope"],
                r["source_ref_en"], r["source_ref_ar"], r["response_en"], r["response_ar"],
                r["action_type"].lower(), r["audio_asset_ar"] or None,
                r["audio_asset_en"] or None, r["command_payload"] or None,
                int(r["priority"] or "2"), r["last_verified"],
            ),
        )

    for idx, (iid, lang, raw_text, norm_text, is_kw) in enumerate(pattern_records):
        cur.execute(
            """
            INSERT INTO intent_patterns (
                intent_id, lang, raw_text, normalized_text, is_keyword, embedding
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (iid, lang, raw_text, norm_text, is_kw, embeddings_map.get(idx)),
        )

    # Populate FTS5 index from content table
    cur.execute("INSERT INTO intent_patterns_fts(intent_patterns_fts) VALUES('rebuild');")

    # Write OTA & runtime metadata
    meta = {
        "kb_version": kb_version,
        "built_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "intent_count": str(len(rows)),
        "pattern_count": str(len(pattern_records)),
        "embedding_model": model_name if use_embeddings else "none",
        "embedding_dim": str(emb_dim),
        "embedding_dtype": "float16" if use_embeddings else "none",
    }
    cur.executemany("INSERT INTO kb_meta (key, value) VALUES (?, ?)", meta.items())

    conn.commit()
    cur.execute("VACUUM;")
    conn.close()

    # Print build summary + SHA-256 for OTA manifest
    sha256 = hashlib.sha256(output_path.read_bytes()).hexdigest()
    size_kb = output_path.stat().st_size / 1024.0
    print("\n=== Nour Alwelayah KB Build Complete ===")
    print(f"Output DB      : {output_path} ({size_kb:.1f} KB)")
    print(f"KB Version     : {kb_version}")
    print(f"Intents        : {len(rows)}")
    print(f"Patterns/KW    : {len(pattern_records)}")
    print(f"Embeddings     : {'ON (' + model_name + ', dim=' + str(emb_dim) + ')' if use_embeddings else 'OFF (FTS5 lexical only)'}")
    print(f"SHA-256 (OTA)  : {sha256}")


def main():
    parser = argparse.ArgumentParser(description="Compile Nour Alwelayah KB CSVs to SQLite FTS5 DB.")
    parser.add_argument("inputs", nargs="+", help="One or more CSV files or directories containing CSVs.")
    parser.add_argument("-o", "--output", default="nour_kb.db", help="Output SQLite DB path (default: nour_kb.db)")
    parser.add_argument("--version", default="1.0.0", help="KB semantic version stored in kb_meta")
    parser.add_argument("--model", default="intfloat/multilingual-e5-small", help="FastEmbed ONNX model name")
    parser.add_argument("--no-embeddings", action="store_true", help="Skip ONNX embedding generation (FTS5 only)")
    args = parser.parse_args()

    files = collect_csv_files(args.inputs)
    rows, errors = load_and_validate_csvs(files)

    if errors:
        print("Schema validation failed with the following errors:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)

    build_sqlite_db(
        rows=rows,
        output_path=Path(args.output),
        kb_version=args.version,
        use_embeddings=not args.no_embeddings,
        model_name=args.model,
    )


if __name__ == "__main__":
    main()
