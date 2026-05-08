import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api import health
from .config import settings

logging.basicConfig(level=getattr(logging, settings.log_level.upper(), logging.INFO))
log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("pitstop backend starting")
    yield
    log.info("pitstop backend stopping")


app = FastAPI(title="pitstop", lifespan=lifespan)
app.include_router(health.router)
