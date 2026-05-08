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

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )


settings = Settings()
