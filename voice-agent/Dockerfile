FROM kalilinux/kali-rolling

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1

# caddy and llama.cpp-tools come straight from the kali-rolling repo (same
# packages/versions this project was built against). livekit-server has no
# apt package -- it's installed via the official script below.
RUN apt-get update && apt-get install -y --no-install-recommends \
        caddy \
        llama.cpp-tools \
        python3 \
        python3-venv \
        python3-pip \
        libportaudio2 \
        libgomp1 \
        curl \
        ca-certificates \
    && curl -sSL https://get.livekit.io | bash \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/voice-agent

COPY requirements.txt .
RUN python3 -m venv venv && venv/bin/pip install --no-cache-dir -r requirements.txt

COPY agent.py custom_stt.py custom_tts.py hermes_llm.py rag.py web_server.py Caddyfile ./
COPY web ./web
COPY piper ./piper
COPY models ./models

RUN chmod +x piper/piper/piper && mkdir -p kb_cache

COPY entrypoint.sh ./
RUN chmod +x entrypoint.sh

# 8443: Caddy (HTTPS/WSS UI + LiveKit signaling), password-gated via
#       basic_auth -- the only port meant to be reached from the internet.
#       Actual port is `PUBLIC_PORT` in .env (default 8443); with
#       network_mode: host this EXPOSE is documentation only.
# 7880/7881: livekit-server signaling/RTC-over-TCP. 7880 is also proxied
#       through Caddy, but livekit-server binds it directly too (needed
#       alongside 7881+UDP for WebRTC media); it's protected by a real
#       generated LIVEKIT_API_KEY/SECRET (see entrypoint.sh), not password
#       auth, so the VPS firewall should still only forward what WebRTC
#       actually needs (7881 + the UDP media range) -- not 7880 itself.
# 8000/8082: llama-server / web_server.py -- these bind 127.0.0.1 only
#       (see entrypoint.sh / web_server.py), not reachable from outside the
#       container at all regardless of firewall rules.
EXPOSE 8443 7880 7881

ENTRYPOINT ["/opt/voice-agent/entrypoint.sh"]
