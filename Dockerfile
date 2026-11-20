FROM python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 BILI_DATA_DIR=/var/lib/bili-cc
WORKDIR /app

COPY requirements.txt ./
RUN python -m pip install --no-cache-dir --require-hashes --only-binary=:all: -r requirements.txt \
    && install -d -o 10001 -g 10001 -m 0700 /var/lib/bili-cc
COPY --chown=10001:10001 bili_cc/ bili_cc/
COPY --chown=10001:10001 web/ web/

USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "bili_cc.app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log", "--no-proxy-headers", "--timeout-graceful-shutdown", "12"]
