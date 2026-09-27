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


def test_auth_config_reports_enforced_scopes(monkeypatch) -> None:
    from pitstop.config import settings

    monkeypatch.setattr(settings, "query_token", "")
    monkeypatch.setattr(settings, "ingest_token", "abc")
    r = client.get("/auth/config")
    assert r.status_code == 200
    assert r.json() == {"query_required": False, "ingest_required": True}
