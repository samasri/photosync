FROM python:3.13-alpine
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY server.py healthcheck.py index_archive.py ./
EXPOSE 8787
CMD ["python", "server.py"]
