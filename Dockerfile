FROM python:3.13-slim

WORKDIR /app

COPY bluetooth ./bluetooth

RUN mkdir -p /app/received

EXPOSE 8765

CMD ["python", "-m", "bluetooth.server"]