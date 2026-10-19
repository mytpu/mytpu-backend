"""Урезает словарь multilingual-e5-small (ONNX int8) до русских и английских токенов.

Зачем: матрица эмбеддингов словаря XLM-R (250 037 × 384) — 96 МБ из 118 МБ модели, и вместе с токенизатором
на 250 тыс. токенов это сотни МБ памяти процесса rag (бюджет — 380 МБ). Для русского и английского
нужны ~60 тыс. токенов: список берётся из d0rj/e5-small-en-ru (тот же e5-small с урезанным словарём, MIT),
а веса — из исходной int8-модели, поэтому векторы совпадают с исходными.

Запуск (нужны onnx, numpy; для проверки — onnxruntime и tokenizers):
    python tools/rag/trim_e5_vocab.py --model D:/models/multilingual-e5-small-int8 \
        --pruned-tokenizer D:/models/src/d0rj-e5-small-en-ru/tokenizer.json --out D:/models/e5-small-en-ru-int8
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper

EMBEDDING = "embeddings.word_embeddings.weight_quantized"
CHECK_TEXTS = [
    "query: как получить справку об обучении",
    "passage: Номер группы и зачетной книжки размещены в Личном кабинете портала ТПУ.",
    "query: where is the library of Tomsk Polytechnic University",
]


def unigram_vocab(tokenizer_path: Path) -> list[str]:
    data = json.loads(tokenizer_path.read_text(encoding="utf-8"))
    if data["model"]["type"] != "Unigram":
        raise SystemExit(f"Ожидался токенизатор Unigram: {tokenizer_path}")
    return [piece for piece, _score in data["model"]["vocab"]]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def trim(model_dir: Path, pruned_tokenizer: Path, out: Path) -> dict:
    original = unigram_vocab(model_dir / "tokenizer.json")
    pruned = unigram_vocab(pruned_tokenizer)
    index = {piece: i for i, piece in enumerate(original)}
    missing = [p for p in pruned if p not in index]
    if missing:
        raise SystemExit(f"{len(missing)} токенов нового словаря нет в исходном, например: {missing[:5]}")
    old_ids = np.array([index[p] for p in pruned], dtype=np.int64)

    model = onnx.load(model_dir / "model.onnx")
    initializer = next(i for i in model.graph.initializer if i.name == EMBEDDING)
    weights = numpy_helper.to_array(initializer)
    trimmed = numpy_helper.from_array(weights[old_ids], EMBEDDING)
    initializer.CopyFrom(trimmed)

    out.mkdir(parents=True, exist_ok=True)
    onnx.save(model, out / "model.onnx")
    shutil.copyfile(pruned_tokenizer, out / "tokenizer.json")
    manifest = {
        "model_version": "e5-small-en-ru-int8-v1",
        "source_model": "Xenova/multilingual-e5-small onnx/model_quantized.onnx",
        "vocab_source": "d0rj/e5-small-en-ru tokenizer.json (MIT)",
        "vocab_size": len(pruned),
        "dimensions": int(weights.shape[1]),
        "pad_token_id": pruned.index("<pad>"),
        "model_sha256": sha256(out / "model.onnx"),
        "tokenizer_sha256": sha256(out / "tokenizer.json"),
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def embed(model_dir: Path, text: str) -> np.ndarray:
    import onnxruntime as ort
    from tokenizers import Tokenizer

    tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
    ids = np.array([tokenizer.encode(text).ids], dtype=np.int64)
    session = ort.InferenceSession(str(model_dir / "model.onnx"), providers=["CPUExecutionProvider"])
    hidden = session.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids), "token_type_ids": np.zeros_like(ids)})[0][0]
    vector = hidden.mean(axis=0)
    return vector / np.linalg.norm(vector)


def check(original_dir: Path, trimmed_dir: Path) -> None:
    for text in CHECK_TEXTS:
        similarity = float(embed(original_dir, text) @ embed(trimmed_dir, text))
        print(f"косинус исходный/урезанный = {similarity:.6f}: {text}")
        if similarity < 0.999:
            raise SystemExit("Векторы урезанной модели расходятся с исходной")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--pruned-tokenizer", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--no-check", action="store_true")
    args = parser.parse_args()
    manifest = trim(args.model, args.pruned_tokenizer, args.out)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    if not args.no_check:
        check(args.model, args.out)


if __name__ == "__main__":
    main()
