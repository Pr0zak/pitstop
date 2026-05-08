from fastapi.testclient import TestClient

from pitstop.main import app

client = TestClient(app)


def test_health() -> None:
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_version() -> None:
    r = client.get("/version")
    assert r.status_code == 200
    body = r.json()
    assert "version" in body
    assert "git_sha" in body


def test_disk() -> None:
    r = client.get("/health/disk")
    assert r.status_code == 200
    body = r.json()
    assert "used_pct" in body
    assert 0 <= body["used_pct"] <= 100
