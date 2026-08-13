FROM python:3.11-slim

WORKDIR /app

# 构建期代理（buildkit 容器网络直连 pypi 不通时走宿主代理；compose 传入）
ARG http_proxy
ARG https_proxy
ENV http_proxy=$http_proxy https_proxy=$https_proxy

# 依赖以 pyproject.toml 为单一真相源
COPY pyproject.toml ./
COPY app ./app
RUN pip install .

EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
