#!/usr/bin/env python3
"""
Local ONNX embedding server — OpenAI-compatible /v1/embeddings endpoint.

Loads a quantized ONNX model and serves embeddings over HTTP so Java can
use OpenAiEmbeddingModel against localhost.

Usage:
    python embedding-server.py \
        --model-path /app/models/mxbai-embed-large.onnx \
        --models-dir /app/models \
        --port 11435
"""

import argparse
import sys
from typing import List, Optional

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from onnxruntime import InferenceSession
from transformers import AutoTokenizer


# --- Pydantic models for OpenAI-compatible API ---

class EmbeddingRequest(BaseModel):
    model: str = "mxbai-embed-large"
    input: Optional[list | str] = None  # accepts string or list of strings
    user: Optional[str] = None

    def get_texts(self) -> list:
        if self.input is None:
            return []
        if isinstance(self.input, str):
            return [self.input]
        return self.input


class EmbeddingDataItem(BaseModel):
    object: str = "embedding"
    index: int
    embedding: List[float]


class EmbeddingResponse(BaseModel):
    object: str = "list"
    model: str
    data: List[EmbeddingDataItem]


# --- Parse args before creating the app ---

def parse_args():
    parser = argparse.ArgumentParser(description="Local ONNX Embedding Server")
    parser.add_argument("--model-path", required=True, help="Path to the ONNX model file")
    parser.add_argument("--models-dir", required=True, help="Directory containing tokenizer files")
    parser.add_argument("--port", type=int, default=11435, help="Port to listen on")
    return parser.parse_args()


SERVER_ARGS = parse_args()

# --- Globals loaded at startup ---

app = FastAPI(title="Local ONNX Embedding Server")


@app.middleware("http")
async def log_requests(request, call_next):
    body = await request.body()
    headers = dict(request.headers)
    print(f"DEBUG {request.method} '{request.url}'", flush=True)
    print(f"  HEADERS: {headers}", flush=True)
    print(f"  BODY ({len(body)} bytes): {body.decode()[:300]}", flush=True)
    response = await call_next(request)
    return response
session: Optional[InferenceSession] = None
tokenizer: Optional[AutoTokenizer] = None


def normalize_l2(vectors: np.ndarray) -> np.ndarray:
    """L2-normalize embeddings along the embedding dimension."""
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    # Avoid division by zero
    norms = np.where(norms == 0, 1, norms)
    return vectors / norms


@app.on_event("startup")
def startup():
    global session, tokenizer

    print(f"Loading ONNX model from {SERVER_ARGS.model_path} ...", flush=True)
    session = InferenceSession(SERVER_ARGS.model_path, providers=["CPUExecutionProvider"])

    print(f"Loading tokenizer from {SERVER_ARGS.models_dir} ...", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(SERVER_ARGS.models_dir, local_files_only=True)

    print("Embedding server ready.", flush=True)


@app.post("/v1/embeddings")
@app.post("/embeddings")
def create_embeddings(request: EmbeddingRequest):
    if session is None or tokenizer is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet")

    texts = request.get_texts()
    if not texts:
        return EmbeddingResponse(model=request.model, data=[])

    # Tokenize with padding and truncation to max sequence length
    tokenized = tokenizer(
        texts,
        padding=True,
        truncation=True,
        max_length=512,
        return_tensors="np",
    )

    # Run ONNX inference — map all model inputs from tokenized output
    inputs = {}
    for inp in session.get_inputs():
        name = inp.name
        if name == "input_ids":
            inputs[name] = tokenized["input_ids"]
        elif name == "attention_mask":
            inputs[name] = tokenized["attention_mask"]
        elif name == "token_type_ids":
            inputs[name] = tokenized.get("token_type_ids", np.zeros_like(tokenized["input_ids"]))
    outputs = session.run(None, inputs)
    token_embeddings = outputs[0]  # shape: (batch, seq_len, hidden)

    # CLS-token pooling — mxbai-embed-large uses the [CLS] token (index 0)
    if token_embeddings.ndim == 3:
        embeddings = token_embeddings[:, 0, :]  # (batch, hidden)
    else:
        embeddings = token_embeddings

    # L2-normalize
    embeddings = normalize_l2(embeddings)

    # Build response — convert numpy floats to Python scalars
    data = []
    for i in range(embeddings.shape[0]):
        emb_list = [float(x) for x in embeddings[i].flatten()]
        data.append(EmbeddingDataItem(index=i, embedding=emb_list))

    return EmbeddingResponse(model=request.model, data=data)


class OllamaEmbeddingRequest(BaseModel):
    model: str = "mxbai-embed-large"
    input: Optional[list | str] = None

    def get_texts(self) -> list:
        if self.input is None:
            return []
        if isinstance(self.input, str):
            return [self.input]
        return self.input


@app.post("/api/embeddings")
@app.post("/api/embed")
def ollama_embeddings(request: OllamaEmbeddingRequest):
    """Ollama-compatible embeddings endpoint for LangChain4j OllamaEmbeddingModel."""
    if session is None or tokenizer is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet")

    texts = request.get_texts()
    if not texts:
        return {"embeddings": []}

    tokenized = tokenizer(
        texts, padding=True, truncation=True, max_length=512, return_tensors="np",
    )

    inputs = {}
    for inp in session.get_inputs():
        name = inp.name
        if name == "input_ids":
            inputs[name] = tokenized["input_ids"]
        elif name == "attention_mask":
            inputs[name] = tokenized["attention_mask"]
        elif name == "token_type_ids":
            inputs[name] = tokenized.get("token_type_ids", np.zeros_like(tokenized["input_ids"]))

    outputs = session.run(None, inputs)
    token_embeddings = outputs[0]

    # CLS-token pooling — mxbai-embed-large uses the [CLS] token (index 0)
    if token_embeddings.ndim == 3:
        embeddings = token_embeddings[:, 0, :]
    else:
        embeddings = token_embeddings

    embeddings = normalize_l2(embeddings)

    # Return Ollama format: single input → {"embedding": [...]}, multiple → {"embeddings": [[...],[...]]}
    if len(texts) == 1:
        return {"embedding": [float(x) for x in embeddings[0]]}
    return {"embeddings": [[float(x) for x in emb] for emb in embeddings]}


@app.get("/health")
def health():
    return {"status": "ok"} if session is not None else {"status": "loading"}


# --- Main entry point (for direct execution) ---

def main():
    import asyncio
    import hypercorn.asyncio
    from hypercorn.config import Config

    print(
        f"Starting embedding server on port {SERVER_ARGS.port} "
        f"(model={SERVER_ARGS.model_path}, models_dir={SERVER_ARGS.models_dir})",
        flush=True,
    )

    config = Config()
    config.bind = [f"0.0.0.0:{SERVER_ARGS.port}"]
    config.loglevel = "info"
    asyncio.run(hypercorn.asyncio.serve(app, config))


if __name__ == "__main__":
    main()
