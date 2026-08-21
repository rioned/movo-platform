"""Minimal, dependency-light web-page RAG: scrape a URL, chunk its text,
and retrieve the most relevant chunks for a query via TF-IDF cosine
similarity (pure stdlib + no ML framework -- deliberately simple, since
this runs on a CPU-only box and just needs to be "good enough").

Shared by web_server.py (scraping, on the /scrape and /token endpoints)
and agent.py (retrieval, inside Agent.on_user_turn_completed).

Scraped pages are cached to disk under kb_cache/<kb_id>.json, keyed by a
hash of the URL, so re-connecting with the same URL doesn't re-scrape.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import math
import os
import re
import socket
import time
from collections import Counter
from html.parser import HTMLParser
from urllib.parse import urlparse

KB_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kb_cache")
_SKIP_TAGS = {"script", "style", "noscript", "svg", "nav", "footer", "header", "template"}


class SsrfBlocked(ValueError):
    pass


def _assert_public_host(url: str) -> None:
    """Reject URLs that resolve to loopback/private/link-local/reserved IPs
    (this includes 169.254.169.254, the cloud metadata address on AWS/GCP/
    Azure/etc) before we let `requests` touch them. Scraping is reachable
    from the web UI, so without this a user could point it at internal
    services or the VPS's own metadata endpoint (SSRF)."""
    host = urlparse(url).hostname
    if not host:
        raise SsrfBlocked("url has no host")
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as e:
        raise SsrfBlocked(f"could not resolve host: {e}") from e
    for family, _, _, _, sockaddr in infos:
        ip = ipaddress.ip_address(sockaddr[0])
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip.is_unspecified
        ):
            raise SsrfBlocked(f"url resolves to a non-public address ({ip})")


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.texts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in _SKIP_TAGS:
            self._skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in _SKIP_TAGS and self._skip_depth > 0:
            self._skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        t = data.strip()
        if t:
            self.texts.append(t)


def extract_text(html: str) -> str:
    parser = _TextExtractor()
    parser.feed(html)
    return "\n".join(parser.texts)


def chunk_text(text: str, chunk_size: int = 700, overlap: int = 80, max_chunks: int = 80) -> list[str]:
    text = re.sub(r"\n{2,}", "\n\n", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    chunks = []
    i, n = 0, len(text)
    while i < n and len(chunks) < max_chunks:
        chunk = text[i : i + chunk_size].strip()
        if len(chunk) > 40:
            chunks.append(chunk)
        i += chunk_size - overlap
    return chunks


def kb_id_for(url: str) -> str:
    return hashlib.sha1(url.encode("utf-8")).hexdigest()[:16]


def _kb_path(kb_id: str) -> str:
    return os.path.join(KB_CACHE_DIR, f"{kb_id}.json")


def scrape_url(url: str, force: bool = False) -> dict:
    """Fetch + chunk a URL's text content, caching the result to disk."""
    import requests

    kb_id = kb_id_for(url)
    path = _kb_path(kb_id)
    if not force and os.path.isfile(path):
        with open(path) as f:
            return json.load(f)

    _assert_public_host(url)
    resp = requests.get(
        url,
        timeout=15,
        headers={"User-Agent": "Mozilla/5.0 (voice-agent-rag/1.0)"},
        # No redirects: a redirect target isn't re-validated by
        # _assert_public_host, so following one would reopen the SSRF hole
        # this function exists to close.
        allow_redirects=False,
    )
    if resp.is_redirect or resp.is_permanent_redirect:
        raise SsrfBlocked("url redirects elsewhere; scrape the final URL directly instead")
    resp.raise_for_status()
    text = extract_text(resp.text)
    chunks = chunk_text(text)
    if not chunks:
        raise ValueError("no readable text content found at that URL")

    data = {"url": url, "kb_id": kb_id, "chunks": chunks, "scraped_at": time.time()}
    os.makedirs(KB_CACHE_DIR, exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f)
    return data


def load_kb(kb_id: str) -> dict | None:
    path = _kb_path(kb_id)
    if not os.path.isfile(path):
        return None
    with open(path) as f:
        return json.load(f)


def _tokenize(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]+", text.lower())


class SimpleTfidfIndex:
    """TF-IDF + cosine similarity over a small set of text chunks."""

    def __init__(self, chunks: list[str]) -> None:
        self.chunks = chunks
        doc_tokens = [_tokenize(c) for c in chunks]
        n = len(chunks)
        df: Counter[str] = Counter()
        for toks in doc_tokens:
            for t in set(toks):
                df[t] += 1
        self._idf = {t: math.log((n + 1) / (c + 1)) + 1 for t, c in df.items()}
        self._doc_vecs = [self._vectorize(toks) for toks in doc_tokens]

    def _vectorize(self, tokens: list[str]) -> dict[str, float]:
        if not tokens:
            return {}
        tf = Counter(tokens)
        vec = {t: (cnt / len(tokens)) * self._idf.get(t, 0.0) for t, cnt in tf.items()}
        norm = math.sqrt(sum(v * v for v in vec.values())) or 1.0
        return {t: v / norm for t, v in vec.items()}

    def query(self, text: str, k: int = 3, min_score: float = 0.05) -> list[str]:
        q_vec = self._vectorize(_tokenize(text))
        if not q_vec:
            return []
        scored = []
        for i, dvec in enumerate(self._doc_vecs):
            score = sum(q_vec.get(t, 0.0) * w for t, w in dvec.items())
            if score >= min_score:
                scored.append((score, i))
        scored.sort(reverse=True)
        return [self.chunks[i] for _, i in scored[:k]]
