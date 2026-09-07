"""Unit tests for pure helpers in main.py.

Nothing here touches the network. Env is stubbed before importing main so a
developer .env cannot leak into assertions.
"""

import json
import os
import tempfile

import pytest

os.environ["GROQ_API_KEY"] = "test-groq-key"
os.environ.setdefault("APP_API_KEY", "test-app-api-key")
os.environ.setdefault("FREE_GENERATION_LIMIT", "3")
os.environ.setdefault(
    "ALLOWANCE_DB_PATH",
    os.path.join(tempfile.mkdtemp(prefix="hook-helpers-"), "allowance.db"),
)
os.environ.pop("SUPABASE_URL", None)
os.environ.pop("SUPABASE_SERVICE_ROLE_KEY", None)
os.environ.pop("GEMINI_API_KEY", None)

import main  # noqa: E402
from main import (  # noqa: E402
    ConversationContext,
    UserPreferences,
    build_context_block,
    build_prompt,
    parse_suggestions,
    retry_after_seconds,
    strip_reasoning,
)


# --- strip_reasoning -----------------------------------------------------

def test_strip_reasoning_removes_think_block():
    raw = "<think>planning the joke</think>\n{\"suggestion_1\": \"hi\"}"
    assert strip_reasoning(raw) == '{"suggestion_1": "hi"}'


def test_strip_reasoning_unwraps_code_fence():
    raw = "```json\n{\"suggestion_1\": \"hi\"}\n```"
    assert strip_reasoning(raw) == '{"suggestion_1": "hi"}'


def test_strip_reasoning_extracts_outermost_json_from_prose():
    raw = 'Sure! Here you go: {"suggestion_1": "hi"} hope that helps'
    assert strip_reasoning(raw) == '{"suggestion_1": "hi"}'


def test_strip_reasoning_leaves_clean_json_alone():
    raw = '{"suggestion_1": "hi", "suggestion_2": "yo"}'
    assert strip_reasoning(raw) == raw


def test_strip_reasoning_combines_think_block_and_fence():
    raw = (
        "<think>reason</think>\n"
        "```json\n"
        '{"suggestion_1": "a", "suggestion_2": "b"}\n'
        "```"
    )
    assert strip_reasoning(raw) == '{"suggestion_1": "a", "suggestion_2": "b"}'


# --- retry_after_seconds -------------------------------------------------

class _FakeHeaders(dict):
    def get(self, key, default=None):
        return super().get(key, default)


class _FakeResponse:
    def __init__(self, headers):
        self.headers = headers


class _FakeRateLimitError(Exception):
    def __init__(self, message, headers=None):
        super().__init__(message)
        self.response = _FakeResponse(headers or {})


def test_retry_after_seconds_uses_header():
    err = _FakeRateLimitError("rate limited", headers={"retry-after": "12"})
    assert retry_after_seconds(err) == 12


def test_retry_after_seconds_parses_fractional_header():
    err = _FakeRateLimitError("rate limited", headers={"retry-after": "7.8"})
    assert retry_after_seconds(err) == 7


def test_retry_after_seconds_falls_back_to_message_text():
    err = _FakeRateLimitError("Please try again in 21.225s.")
    # Parsed seconds floored, then +1.
    assert retry_after_seconds(err) == 22


def test_retry_after_seconds_ignores_non_numeric_header_and_uses_body():
    err = _FakeRateLimitError(
        "Please try again in 5.1s.",
        headers={"retry-after": "soon"},
    )
    assert retry_after_seconds(err) == 6


def test_retry_after_seconds_uses_default_when_nothing_matches():
    err = _FakeRateLimitError("quota exceeded")
    assert retry_after_seconds(err, default=45) == 45


def test_retry_after_seconds_header_of_zero_becomes_one():
    err = _FakeRateLimitError("rate limited", headers={"retry-after": "0"})
    assert retry_after_seconds(err) == 1


# --- build_context_block / build_prompt ----------------------------------

def _prefs(**overrides):
    base = dict(
        style="LOWERCASE",
        tone="FUNNY",
        flirt_level="MEDIUM",
        reply_length="SHORT",
        emoji_use="MINIMAL",
    )
    base.update(overrides)
    return UserPreferences(**base)


def test_build_context_block_empty_when_none():
    assert build_context_block(None) == ""


def test_build_context_block_empty_when_blank():
    ctx = ConversationContext(summary="  ", sent_replies=["", "  "])
    assert build_context_block(ctx) == ""


def test_build_context_block_summary_only():
    ctx = ConversationContext(summary="They asked about coffee.")
    block = build_context_block(ctx)
    assert "sum: They asked about coffee." in block
    assert "sent:" not in block


def test_build_context_block_sent_replies_only():
    ctx = ConversationContext(summary="", sent_replies=["coffee sounds good"])
    block = build_context_block(ctx)
    assert "sum:" not in block
    assert "- coffee sounds good" in block


def test_build_context_block_includes_summary_and_replies():
    ctx = ConversationContext(
        summary="Flirty banter",
        sent_replies=["hey there", "what are you up to"],
    )
    block = build_context_block(ctx)
    assert "sum: Flirty banter" in block
    assert "- hey there" in block
    assert "- what are you up to" in block


def test_build_prompt_includes_preferences_and_profile():
    prompt = build_prompt(
        _prefs(profile_name="Alex", profile_gender="Male"),
        context=None,
    )
    assert "LOWERCASE" in prompt
    assert "FUNNY" in prompt
    assert "flirt=MEDIUM" in prompt
    assert "len=SHORT" in prompt
    assert "emoji=MINIMAL" in prompt
    assert "name=Alex" in prompt
    assert "gender=Male" in prompt
    assert "ctx" not in prompt


def test_build_prompt_embeds_context_block():
    ctx = ConversationContext(summary="Talking about weekend plans")
    prompt = build_prompt(_prefs(), context=ctx)
    assert "sum: Talking about weekend plans" in prompt
    assert "JSON" in prompt


def test_parse_suggestions_happy_path():
    text = json.dumps({
        "suggestion_1": "a",
        "suggestion_2": "b",
        "suggestion_3": "c",
        "updated_context_summary": "hi",
    })
    suggestions, summary = parse_suggestions(text)
    assert suggestions == ["a", "b", "c"]
    assert summary == "hi"


def test_parse_suggestions_rejects_partial():
    text = json.dumps({
        "suggestion_1": "a",
        "suggestion_2": "",
        "suggestion_3": "c",
        "updated_context_summary": "hi",
    })
    assert parse_suggestions(text) == ([], "")
