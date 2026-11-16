FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends dcm2niix libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /service
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir ".[segmentation]"
RUN python -c "import wfdb, pyedflib, scipy"

RUN useradd --create-home --uid 10001 worker \
    && mkdir -p /data /models \
    && chown -R worker:worker /data /models /service \
    && test "$(id -u worker)" = 10001
USER 10001:10001

EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]

