FROM python:3.12-slim

WORKDIR /app

COPY relais/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY relais/ .

# HTTPS n'est pas gere ici -- prevoir un reverse-proxy devant ce conteneur
# (Caddy, nginx, Traefik...) pour le certificat et la terminaison TLS.
# Toutes les variables DEVICE_AGENT_* se passent a l'execution, voir .env.example.
EXPOSE 8000

CMD ["python", "server.py"]
