from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SEGMENTATION_", case_sensitive=False)

    service_token: str = "med-node-segmentation-dev"
    engine: str = "totalsegmentator"
    data_dir: Path = Path("/data")
    device: str = "cpu"
    fast: bool = True
    run_pathology_models: bool = True
    keep_work_directory: bool = False
    command_timeout_seconds: int = Field(default=7200, ge=60)
    max_archive_bytes: int = Field(default=4_294_967_296, ge=1)

    llm_enabled: bool = False
    llm_base_url: str = "http://host.docker.internal:11434"
    llm_model: str = "qwen3.5:4b"
    llm_timeout_seconds: int = Field(default=120, ge=5)

    orthanc_base_url: str = "http://orthanc:8042"
    orthanc_username: str = "mednode"
    orthanc_password: str = "mednode-orthanc-dev"


settings = Settings()
