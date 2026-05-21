FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

WORKDIR /app

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH="/app/.venv/bin:$PATH"

# docker CLI for the in-app /admin/upgrade flow. The endpoint shells out
# to `docker run --rm -d docker:27-cli ...` which needs the client on
# PATH; the daemon socket is bind-mounted at runtime. We ship only the
# client (no engine).
RUN apt-get update \
    && apt-get install -y --no-install-recommends docker.io \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --no-install-project

COPY . .
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync

ARG GIT_SHA=unknown
ARG BUILD_TIME=unknown
ARG VERSION=dev
ENV GIT_SHA=${GIT_SHA} \
    BUILD_TIME=${BUILD_TIME} \
    PITSTOP_VERSION=${VERSION}

EXPOSE 8000

CMD ["sh", "-c", "alembic upgrade head && fastapi run src/pitstop/main.py --host 0.0.0.0 --port 8000"]
