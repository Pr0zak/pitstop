import os

# CI passes the git tag (e.g. "v0.1.79") as PITSTOP_VERSION. Local
# builds without that env var fall back to "dev". The /version
# endpoint surfaces this so the web UI can show the running release.
VERSION = os.environ.get("PITSTOP_VERSION", "dev")
GIT_SHA = os.environ.get("GIT_SHA", "unknown")
BUILD_TIME = os.environ.get("BUILD_TIME", "unknown")
