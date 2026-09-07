"""Edge-case tests for generate-replies and the health route.

Reuses the same offline fakes as test_gating.py. Env is stubbed before import.
"""

import json
import os
import tempfile

import httpx
import pytest
from openai import RateLimitError

os.environ["GROQ_API_KEY"] = "test-groq-key"
os.environ["APP_API_KEY"] = "test-app-api-key"
os.environ["FREE_GENERATION_LIMIT"] = "3"
os.environ["ALLOWANCE_DB_PATH"] = os.path.join(
    tempfile.mkdtemp(prefix="hook-generate-"), "allowance.db"
)
os.environ.pop("SUPABASE_URL", None)
os.environ.pop("SUPABASE_SERVICE_ROLE_KEY", None)
os.environ.pop("ALLOWED_ORIGINS", None)
os.environ.pop("GEMINI_API_KEY", None)

from fastapi.testclient import TestClient  # noqa: E402

import entitlements  # noqa: E402
import main  # noqa: E402
from store import SqliteAllowanceStore  # noqa: E402

API_KEY = "test-app-api-key"
USER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
FREE_LIMIT = 3


class _FakeMessage:
    def __init__(self, content):
        self.content = content


class _FakeChoice:
    def __init__(self, content):
        self.message = _FakeMessage(content)


class _FakeCompletion:
    def __init__(self, content):
        self.choices = [_FakeChoice(content)]


GOOD_PAYLOAD = json.dumps({
    "suggestion_1": "bold of you to assume i'd say no",
    "suggestion_2": "you had me at hello",
    "suggestion_3": "prove it",
    "updated_context_summary": "Playful banter with the match.",
})


def _mock_groq(monkeypatch, *, content=GOOD_PAYLOAD, error=None):
    calls = []

    async def fake_create(*args, **kwargs):
        calls.append(kwargs)
        if error is not None:
            raise error
        return _FakeCompletion(content)

    monkeypatch.setattr(main._client.chat.completions, "create", fake_create)
    return calls


def _mock_is_pro(monkeypatch, value):
    async def fake_is_pro(app_user_id):
        return value

    monkeypatch.setattr(main, "is_pro", fake_is_pro)


def _headers(api_key=API_KEY, user_id=USER):
    return {"X-Api-Key": api_key, "X-App-User-Id": user_id}


def _body(**overrides):
    body = {
        "screenshot_base64": "ZmFrZS1qcGVn",
        "preferences": {
            "style": "LOWERCASE",
            "tone": "FUNNY",
            "flirt_level": "MEDIUM",
            "reply_length": "SHORT",
            "emoji_use": "MINIMAL",
        },
    }
    body.update(overrides)
    return body


@pytest.fixture
def store(monkeypatch):
    fresh = SqliteAllowanceStore(":memory:")
    monkeypatch.setattr(main, "store", fresh)
    return fresh


@pytest.fixture
def client(store):
    entitlements.clear_cache()
    main.provider_queue._primary = main.ProviderQueue.GROQ
    main._gemini = None
    with TestClient(main.app) as test_client:
        yield test_client


def _enable_gemini(monkeypatch, *, error=None):
    """Wire a fake gemini_complete that returns GOOD_PAYLOAD suggestions."""
    calls = []

    async def fake_gemini(jpeg_bytes, prompt):
        calls.append({"jpeg_len": len(jpeg_bytes), "prompt": prompt})
        if error is not None:
            raise error
        data = json.loads(GOOD_PAYLOAD)
        return (
            [data["suggestion_1"], data["suggestion_2"], data["suggestion_3"]],
            data.get("updated_context_summary", ""),
        )

    monkeypatch.setattr(main, "_gemini", object())
    monkeypatch.setattr(main, "gemini_complete", fake_gemini)
    return calls


# --- Health --------------------------------------------------------------

def test_root_reports_online(client):
    response = client.get("/")
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "online"
    assert payload["service"] == "Hook"
    assert payload["version"] == main.APP_VERSION


# --- Screenshot encoding -------------------------------------------------

def test_data_url_prefix_is_stripped_before_groq(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    calls = _mock_groq(monkeypatch)

    body = _body(screenshot_base64="data:image/jpeg;base64,ZmFrZS1qcGVn")
    response = client.post("/generate-replies", json=body, headers=_headers())
    assert response.status_code == 200

    content = calls[0]["messages"][0]["content"]
    image_url = content[0]["image_url"]["url"]
    assert image_url == "data:image/jpeg;base64,ZmFrZS1qcGVn"
    # Prefix must not be double-wrapped.
    assert image_url.count("data:image/jpeg;base64,") == 1


def test_raw_base64_is_wrapped_as_data_url(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    calls = _mock_groq(monkeypatch)

    response = client.post(
        "/generate-replies", json=_body(), headers=_headers()
    )
    assert response.status_code == 200
    image_url = calls[0]["messages"][0]["content"][0]["image_url"]["url"]
    assert image_url == "data:image/jpeg;base64,ZmFrZS1qcGVn"


# --- Conversation context ------------------------------------------------

def test_context_round_trip_passthrough_sent_replies(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    _mock_groq(
        monkeypatch,
        content=json.dumps({
            "suggestion_1": "a",
            "suggestion_2": "b",
            "suggestion_3": "c",
            "updated_context_summary": "Now talking about brunch.",
        }),
    )

    body = _body(context={
        "summary": "Earlier they mentioned dogs.",
        "sent_replies": ["i love dogs too"],
    })
    response = client.post("/generate-replies", json=body, headers=_headers())
    assert response.status_code == 200
    ctx = response.json()["context"]
    assert ctx["summary"] == "Now talking about brunch."
    # Client-owned list is passed through unchanged.
    assert ctx["sent_replies"] == ["i love dogs too"]


def test_context_keeps_prior_summary_when_model_omits_update(
    client, monkeypatch
):
    _mock_is_pro(monkeypatch, False)
    # json_object-style payload without updated_context_summary.
    _mock_groq(
        monkeypatch,
        content=json.dumps({
            "suggestion_1": "a",
            "suggestion_2": "b",
            "suggestion_3": "c",
        }),
    )

    body = _body(context={
        "summary": "Prior summary stays",
        "sent_replies": [],
    })
    response = client.post("/generate-replies", json=body, headers=_headers())
    assert response.status_code == 200
    assert response.json()["context"]["summary"] == "Prior summary stays"


# --- Rate limiting / provider queue --------------------------------------

def test_groq_rate_limit_returns_429_when_gemini_unavailable(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    response = httpx.Response(
        429,
        headers={"retry-after": "17"},
        request=request,
    )
    err = RateLimitError(
        "Please try again in 17s.",
        response=response,
        body={"error": {"message": "rate limited"}},
    )
    _mock_groq(monkeypatch, error=err)

    result = client.post("/generate-replies", json=_body(), headers=_headers())
    assert result.status_code == 429
    assert result.headers.get("retry-after") == "17"
    assert "Rate limit" in result.json()["detail"]
    # Failed before any replies were delivered — do not spend allowance.
    assert client.get("/me", headers=_headers()).json()["free_used"] == 0


def test_groq_rate_limit_falls_back_to_gemini(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    response = httpx.Response(
        429,
        headers={"retry-after": "17"},
        request=request,
    )
    err = RateLimitError(
        "Please try again in 17s.",
        response=response,
        body={"error": {"message": "rate limited"}},
    )
    groq_calls = _mock_groq(monkeypatch, error=err)
    gemini_calls = _enable_gemini(monkeypatch)

    result = client.post("/generate-replies", json=_body(), headers=_headers())
    assert result.status_code == 200
    assert result.json()["suggestions"] == [
        "bold of you to assume i'd say no",
        "you had me at hello",
        "prove it",
    ]
    assert len(groq_calls) == 1
    assert len(gemini_calls) == 1
    assert client.get("/me", headers=_headers()).json()["free_used"] == 1
    assert main.provider_queue._primary == main.ProviderQueue.GEMINI


def test_after_demotion_gemini_is_tried_first(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    main.provider_queue._primary = main.ProviderQueue.GEMINI
    gemini_calls = _enable_gemini(monkeypatch)
    groq_calls = _mock_groq(monkeypatch)

    result = client.post("/generate-replies", json=_body(), headers=_headers())
    assert result.status_code == 200
    assert len(gemini_calls) == 1
    assert groq_calls == []


def test_groq_success_does_not_call_gemini(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    _mock_groq(monkeypatch)
    gemini_calls = _enable_gemini(monkeypatch)

    result = client.post("/generate-replies", json=_body(), headers=_headers())
    assert result.status_code == 200
    assert gemini_calls == []


def test_both_providers_rate_limited_returns_429(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    response = httpx.Response(
        429,
        headers={"retry-after": "9"},
        request=request,
    )
    err = RateLimitError(
        "Please try again in 9s.",
        response=response,
        body={"error": {"message": "rate limited"}},
    )
    _mock_groq(monkeypatch, error=err)

    class _FakeGemini429(Exception):
        code = 429

    _enable_gemini(monkeypatch, error=_FakeGemini429())

    result = client.post("/generate-replies", json=_body(), headers=_headers())
    assert result.status_code == 429
    assert client.get("/me", headers=_headers()).json()["free_used"] == 0


def test_groq_error_falls_back_to_gemini(client, monkeypatch):
    _mock_is_pro(monkeypatch, False)
    groq_calls = _mock_groq(monkeypatch, error=RuntimeError("groq exploded"))
    gemini_calls = _enable_gemini(monkeypatch)

    result = client.post("/generate-replies", json=_body(), headers=_headers())
    assert result.status_code == 200
    assert groq_calls
    assert len(gemini_calls) == 1
    assert client.get("/me", headers=_headers()).json()["free_used"] == 1


# --- Allowance increment resilience --------------------------------------

def test_successful_generation_survives_increment_failure(
    client, store, monkeypatch
):
    _mock_is_pro(monkeypatch, False)
    _mock_groq(monkeypatch)

    async def boom(_app_user_id):
        raise RuntimeError("store is down")

    monkeypatch.setattr(store, "increment", boom)

    response = client.post(
        "/generate-replies", json=_body(), headers=_headers()
    )
    assert response.status_code == 200
    payload = response.json()
    assert len(payload["suggestions"]) == 3
    # Best-effort local bump when the durable store fails after delivery.
    assert payload["free_used"] == 1
    assert payload["remaining"] == FREE_LIMIT - 1
