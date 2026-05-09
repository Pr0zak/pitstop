from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    postgres_user: str = "pitstop"
    postgres_password: str = "changeme"
    postgres_db: str = "pitstop"
    postgres_host: str = "db"
    postgres_port: int = 5432

    mqtt_host: str = "mosquitto"
    mqtt_port: int = 1883
    mqtt_user: str = "pitstop"
    mqtt_password: str = ""

    ingest_token: str = ""
    query_token: str = ""

    log_level: str = "INFO"
    tz: str = "UTC"

    pid_profiles_dir: str = "/app/pid_profiles"

    # Trip detector tuning.
    #
    # silence_close_s = 60 was tearing trips into shreds at every red
    # light: a typical 30-min drive produced 5-6 zero-distance "trips"
    # because the engine-off-at-light idle pushed the detector past the
    # silence threshold. 600 (10 min) matches industry-typical OBD
    # session sleeps and only forces a close on real engine-off events.
    #
    # min_distance_km filters the 0-km sliver-trips that survive even
    # the longer silence — a parked car still publishes battery + temp
    # readings every minute, which would otherwise auto-open + auto-
    # close a 0-km "trip" the user has no interest in seeing.
    trip_silence_open_s: int = 120
    trip_silence_close_s: int = 600
    trip_min_distance_km: float = 0.5
    trip_low_voltage_threshold: float = 12.0
    trip_low_voltage_consecutive: int = 3

    # MQTT ingest tuning.
    ingest_batch_max_rows: int = 100
    ingest_batch_max_ms: int = 200
    vehicle_cache_ttl_s: int = 300

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )

    @property
    def asyncpg_dsn(self) -> str:
        """Plain asyncpg DSN (no SQLAlchemy driver prefix)."""
        return (
            f"postgresql://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )


settings = Settings()
