import os
import json
import hmac
import re
from typing import List, Literal, Optional
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from openai import AsyncOpenAI, RateLimitError
import httpx
from dotenv import load_dotenv
import time

from entitlements import invalidate, is_pro
from store import get_store

load_dotenv()

APP_VERSION = "1.4.0"

GROQ_API_KEY = os.getenv("GROQ_API_KEY")
if not GROQ_API_KEY:
    raise ValueError("GROQ_API_KEY environment variable is required")

# Global async client at startup — reuses the connection pool across requests
_client = AsyncOpenAI(
    api_key=GROQ_API_KEY,
    base_url=os.getenv("GROQ_BASE_URL", "https://api.groq.com/openai/v1"),
    max_retries=0,  # we do our own model fallback; retries only add latency
    timeout=httpx.Timeout(connect=3.0, read=20.0, write=10.0, pool=3.0),
    http_client=httpx.AsyncClient(
        limits=httpx.Limits(max_connections=100, max_keepalive_connections=50,
                            keepalive_expiry=300.0),
        timeout=httpx.Timeout(connect=3.0, read=20.0, write=10.0, pool=3.0),
    ),
)

app = FastAPI(
    title="Hook",
    description="Backend service",
    version=APP_VERSION
)

# --- CORS ---
# The Android app is not a browser and needs no CORS at all; only the marketing
# site would. Default to allowing nothing rather than the old allow_origins=["*"].
ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.getenv("ALLOWED_ORIGINS", "").split(",")
    if origin.strip()
]
if ALLOWED_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=ALLOWED_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    print(f"[INFO] CORS enabled for: {', '.join(ALLOWED_ORIGINS)}")
else:
    print("[INFO] CORS disabled (no ALLOWED_ORIGINS set) — browser origins are "
          "blocked. Native app clients are unaffected.")


# --- Auth & allowance config ---

APP_API_KEY = os.getenv("APP_API_KEY")
if not APP_API_KEY:
    print("[WARN] " + "!" * 66)
    print("[WARN] APP_API_KEY is not set — the API is UNAUTHENTICATED and anyone "
          "who finds this URL can burn the Groq quota.")
    print("[WARN] Fine for local dev. NEVER deploy like this: set APP_API_KEY and "
          "ship the same value in the app.")
    print("[WARN] " + "!" * 66)

try:
    FREE_GENERATION_LIMIT = int(os.getenv("FREE_GENERATION_LIMIT", "5"))
except ValueError:
    print("[WARN] FREE_GENERATION_LIMIT is not an integer — using 5")
    FREE_GENERATION_LIMIT = 5

# Stable per-install UUID from the client; also the RevenueCat app user id.
APP_USER_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,128}$")

# One counter row per install. Screenshots and replies are never persisted.
store = get_store()


def require_api_key(x_api_key: Optional[str]) -> None:
    """Constant-time shared-key check. Skipped entirely when unconfigured."""
    if not APP_API_KEY:
        return
    supplied = (x_api_key or "").encode("utf-8")
    expected = APP_API_KEY.encode("utf-8")
    if not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


def require_app_user_id(x_app_user_id: Optional[str]) -> str:
    user_id = (x_app_user_id or "").strip()
    if not APP_USER_ID_PATTERN.match(user_id):
        raise HTTPException(
            status_code=400,
            detail="X-App-User-Id header is required: 1-128 characters of "
                   "letters, digits, '-' or '_'",
        )
    return user_id


async def authenticate(
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
    x_app_user_id: Optional[str] = Header(default=None, alias="X-App-User-Id"),
) -> str:
    """Shared gate for every metered route. Returns the caller's app user id."""
    require_api_key(x_api_key)
    return require_app_user_id(x_app_user_id)


# --- Request/Response Models ---

class UserPreferences(BaseModel):
    style: Literal["LOWERCASE", "SENTENCE_CASE"]
    tone: Literal["GEN_Z_SLANG", "RESPECTFUL", "FUNNY", "SMOOTH"]
    flirt_level: Literal["LESS", "MEDIUM", "BOLD"]
    reply_length: Literal["SHORT", "NORMAL", "EXTENDED"]
    emoji_use: Literal["NEVER", "MINIMAL", "EXPRESSIVE"]
    profile_name: Optional[str] = ""
    profile_gender: Optional[str] = "Male"
    profile_pronouns: Optional[str] = "he/him"
    profile_bio: Optional[str] = ""

class ConversationContext(BaseModel):
    """Session-only, hidden understanding of the whole conversation.

    Held by the client between screenshots; the server stays stateless and only
    reads the incoming context and returns an updated one.
    """
    summary: str = ""          # rolling, concise understanding of the chat so far
    sent_replies: List[str] = []  # replies the user actually picked & sent (client-managed)

class GenerateRepliesRequest(BaseModel):
    screenshot_base64: str
    preferences: UserPreferences
    context: Optional[ConversationContext] = None

class GenerateRepliesResponse(BaseModel):
    suggestions: List[str]
    context: ConversationContext
    # Allowance state, so the client can update its paywall UI without a /me
    # round-trip. `remaining` counts FREE generations only — ignore it when
    # is_pro is true, where generations are unlimited.
    is_pro: bool = False
    free_used: int = 0
    free_limit: int = 0
    remaining: int = 0

class OnboardingProfileRequest(BaseModel):
    """What onboarding learned, sent once when the flow finishes.

    Every field is optional and length-capped: a question the app hasn't asked
    yet simply arrives as None, and nothing here is ever echoed back to a
    client, so it only needs to be small enough to store.
    """
    gender: Optional[str] = Field(default=None, max_length=64)
    sexuality: Optional[str] = Field(default=None, max_length=64)
    age_range: Optional[str] = Field(default=None, max_length=32)
    looking_for: Optional[str] = Field(default=None, max_length=64)
    style: Optional[str] = Field(default=None, max_length=32)
    tone: Optional[str] = Field(default=None, max_length=32)
    flirt_level: Optional[str] = Field(default=None, max_length=32)


class ContentReportRequest(BaseModel):
    """A user flagging something the model wrote.

    Google's Generative AI policy requires an in-app way to report offensive
    output, so this route is a release requirement rather than a nice-to-have.

    Only the flagged suggestion travels — never the screenshot, and never the
    conversation context. A report is about one thing the model said, and
    shipping the surrounding chat to answer that question would collect far
    more than the report needs.
    """
    text: str = Field(max_length=2000)
    reason: Optional[str] = Field(default=None, max_length=64)


class MeResponse(BaseModel):
    app_user_id: str
    is_pro: bool
    free_used: int
    free_limit: int
    remaining: int


# --- Structured Output Schema for Groq ---

class ReplySuggestions(BaseModel):
    """Structured output schema passed to Groq via response_format json_schema."""
    suggestion_1: str = Field(description="First reply suggestion")
    suggestion_2: str = Field(description="Second reply suggestion")
    suggestion_3: str = Field(description="Third reply suggestion")


RESPONSE_FORMAT = {
    "type": "json_schema",
    "json_schema": {
        "name": "reply_suggestions",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "suggestion_1": {"type": "string"},
                "suggestion_2": {"type": "string"},
                "suggestion_3": {"type": "string"},
                "updated_context_summary": {"type": "string"},
            },
            "required": [
                "suggestion_1", "suggestion_2", "suggestion_3",
                "updated_context_summary",
            ],
            "additionalProperties": False,
        },
    },
}


# --- Model Config ---
# qwen3.8-27b is currently the vision-capable model Groq serves us; the
# llama-4 scout/maverick pair it replaced now returns model_not_found.
MODEL = os.getenv("GROQ_MODEL", "qwen/qwen3.8-27b")
FALLBACK_MODEL = os.getenv("GROQ_FALLBACK_MODEL", "qwen/qwen3.8-27b")

# It is a reasoning model: left to itself it emits a <think> block before the
# answer, which makes both JSON modes fail outright. "none" turns that off.
# Groq only accepts "none" or "default" here.
REASONING_EFFORT = os.getenv("GROQ_REASONING_EFFORT", "none")

# Compact prompt — fewer input tokens = faster processing.
# "JSON" must appear literally for Groq's json_object fallback mode.
PROMPT_TEMPLATE = """You are an expert flirting agent. Your task is to analyse the attached screenshot and generate 3 flirty chat reply suggestions keeping the parameters described below.
{context_block}
Preferences: style={style}, tone={tone}, flirt={flirt_level}, length={reply_length}, emoji={emoji_use}{profile_info}

Rules:
- SHORT=3-4 words, NORMAL=1 sentence, EXTENDED=1-2 sentences
- NEVER/MINIMAL/EXPRESSIVE controls emoji usage
- LESS/MEDIUM/BOLD controls flirt intensity
- updated_context_summary: rewrite the running conversation summary by folding in this newest screenshot without losing earlier facts (who the match is, tone, key topics/facts); keep it concise, a few sentences, max ~150 words

Reply with JSON only: {{"suggestion_1": "...", "suggestion_2": "...", "suggestion_3": "...", "updated_context_summary": "..."}}"""

def strip_reasoning(text: str) -> str:
    """Drop any <think> block and code fences a reasoning model may still emit."""
    if "</think>" in text:
        text = text.split("</think>", 1)[1]
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1]
        if "```" in text:
            text = text.rsplit("```", 1)[0]
    # Fall back to the outermost JSON object if prose crept in around it.
    if not text.lstrip().startswith("{") and "{" in text and "}" in text:
        text = text[text.index("{"):text.rindex("}") + 1]
    return text.strip()


def retry_after_seconds(err: Exception, default: int = 30) -> int:
    """Seconds to wait after a Groq 429, from the header or the message text."""
    header = getattr(getattr(err, "response", None), "headers", None)
    if header:
        raw = header.get("retry-after")
        if raw:
            try:
                return max(1, int(float(raw)))
            except ValueError:
                pass
    # Groq spells it out in the body: "Please try again in 21.225s."
    match = re.search(r"try again in ([\d.]+)s", str(err))
    if match:
        return max(1, int(float(match.group(1))) + 1)
    return default


def build_context_block(context: Optional[ConversationContext]) -> str:
    """Render the prior context so the model can carry the conversation forward.

    Empty when there is nothing yet, so the first request is unchanged.
    """
    if context is None:
        return ""
    summary = (context.summary or "").strip()
    sent = [r for r in (context.sent_replies or []) if r and r.strip()]
    if not summary and not sent:
        return ""
    lines = ["\nConversation so far (context only — do not repeat verbatim):"]
    if summary:
        lines.append(f"Summary: {summary}")
    if sent:
        joined = "\n".join(f"- {r}" for r in sent)
        lines.append("Replies the user has already sent in this conversation:\n" + joined)
    return "\n".join(lines) + "\n"


def build_prompt(p: UserPreferences, context: Optional[ConversationContext] = None) -> str:
    parts = []
    if p.profile_name: parts.append(f", name={p.profile_name}")
    if p.profile_gender: parts.append(f", gender={p.profile_gender}")
    profile_info = "".join(parts)
    return PROMPT_TEMPLATE.format(
        context_block=build_context_block(context),
        style=p.style, tone=p.tone, flirt_level=p.flirt_level,
        reply_length=p.reply_length, emoji_use=p.emoji_use, profile_info=profile_info
    )


# --- Routes ---

def _remaining(free_used: int) -> int:
    return max(0, FREE_GENERATION_LIMIT - free_used)


@app.get("/")
async def root():
    return {"status": "online", "service": "Hook", "version": APP_VERSION}


async def _entitlement_snapshot(app_user_id: str) -> MeResponse:
    """Current entitlement + allowance for one install."""
    pro = await is_pro(app_user_id)
    free_used = await store.get_used(app_user_id)
    return MeResponse(
        app_user_id=app_user_id,
        is_pro=pro,
        free_used=free_used,
        free_limit=FREE_GENERATION_LIMIT,
        remaining=_remaining(free_used),
    )


@app.get("/me", response_model=MeResponse)
async def me(app_user_id: str = Depends(authenticate)):
    """Entitlement + free-allowance state for one install.

    The app calls this on launch and after a purchase to refresh its paywall.
    """
    return await _entitlement_snapshot(app_user_id)


@app.post("/entitlement/refresh", response_model=MeResponse)
async def refresh_entitlement(app_user_id: str = Depends(authenticate)):
    """Same answer as /me, but re-asks RevenueCat instead of trusting the cache.

    The app calls this the moment a purchase grants Pro. Without it the cached
    "not Pro" answer from a second earlier keeps standing for the rest of the
    TTL, and /generate-replies hands a 402 to someone who has just paid.

    Same auth as every other metered route — this only drops our own cache, it
    never lets the client assert its own entitlement.
    """
    invalidate(app_user_id)
    return await _entitlement_snapshot(app_user_id)


@app.post("/onboarding", status_code=204)
async def save_onboarding(
    profile: OnboardingProfileRequest,
    app_user_id: str = Depends(authenticate),
):
    """Record a finished onboarding for this install.

    The app calls this once, after the last screen. It is an upsert, so a retry
    from a client that lost the network mid-call is harmless.

    A failure here must never cost the user anything — they have already
    finished onboarding — so a store that is down is logged and swallowed
    rather than turned into an error the app has to handle.
    """
    try:
        await store.save_onboarding_profile(app_user_id, profile.model_dump())
    except Exception as e:
        print(f"[ERR] could not save onboarding profile for {app_user_id}: {e}")
    return None


@app.post("/report", status_code=204)
async def report_content(
    report: ContentReportRequest,
    app_user_id: str = Depends(authenticate),
):
    """Record a user's report of something the model generated.

    Deliberately not metered and not gated on Pro: making it harder to report
    offensive output than to generate it would be the wrong way round, and
    Google's Generative AI policy expects the mechanism to be available to
    everyone using the app.

    Reports land in the server log under a single greppable prefix. That is
    enough to satisfy the policy and to actually read them at current volume;
    if reports become frequent enough to need triage, give them a table.

    Never fails the caller. Someone reporting offensive content has already had
    a bad experience, and an error on top of it — for something they cannot
    retry usefully — would only add to it.
    """
    reason = report.reason or "unspecified"
    # Newlines would let one report forge extra log lines.
    text = report.text.replace("\n", " ").replace("\r", " ").strip()
    print(f"[REPORT] user={app_user_id} reason={reason} text={text!r}")
    return None


@app.post(
    "/generate-replies",
    response_model=GenerateRepliesResponse,
    responses={402: {"description": "Free allowance exhausted — subscription required"}},
)
async def generate_replies(
    request: GenerateRepliesRequest,
    app_user_id: str = Depends(authenticate),
):
    start_time = time.time()

    # Pro is unlimited; everyone else spends from a lifetime free allowance.
    pro = await is_pro(app_user_id)
    free_used = await store.get_used(app_user_id)
    if not pro and free_used >= FREE_GENERATION_LIMIT:
        return JSONResponse(
            status_code=402,
            content={
                "error": "allowance_exhausted",
                "free_limit": FREE_GENERATION_LIMIT,
                "used": free_used,
            },
        )

    try:
        # Pass the base64 payload straight through as a data URL — no re-encoding.
        # Android client already sends 640px 70% JPEG.
        b64 = request.screenshot_base64
        if b64.startswith("data:"):
            b64 = b64.split(",", 1)[-1]
        prompt = build_prompt(request.preferences, request.context)

        messages = [{
            "role": "user",
            "content": [
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                },
                {"type": "text", "text": prompt},
            ],
        }]

        suggestions = []
        updated_summary = ""
        last_error = None

        # (model, response_format) attempts. json_schema is strict but only some
        # Groq models accept it; json_object is the universal fallback.
        attempts = [
            (MODEL, RESPONSE_FORMAT),
            (MODEL, {"type": "json_object"}),
            (FALLBACK_MODEL, {"type": "json_object"}),
        ]

        for model, response_format in attempts:
            try:
                groq_start = time.time()
                kwargs = {}
                if REASONING_EFFORT:
                    kwargs["reasoning_effort"] = REASONING_EFFORT
                response = await _client.chat.completions.create(
                    model=model,
                    messages=messages,
                    response_format=response_format,
                    # Room for 3 replies plus the ~150-word rolling summary.
                    max_tokens=600,
                    temperature=0.7,
                    **kwargs,
                )
                groq_time = time.time() - groq_start

                text = response.choices[0].message.content
                candidate_summary = ""
                if text:
                    data = json.loads(strip_reasoning(text))
                    suggestions = [
                        data.get("suggestion_1", ""),
                        data.get("suggestion_2", ""),
                        data.get("suggestion_3", ""),
                    ]
                    # Present in the json_schema branch and, when the model
                    # cooperates, in the json_object fallback too.
                    raw_summary = data.get("updated_context_summary", "")
                    candidate_summary = strip_reasoning(raw_summary) if raw_summary else ""

                if suggestions and len(suggestions) >= 3 and all(suggestions):
                    updated_summary = candidate_summary
                    total = time.time() - start_time
                    print(f"[PERF] {model}: groq={groq_time:.2f}s total={total:.2f}s")
                    break
                else:
                    suggestions = []
            except RateLimitError as e:
                # Retrying only spends more of the very budget we just ran out
                # of, so surface it straight away as a 429 the client can act on.
                print(f"[ERR] {model}: rate limited: {e}")
                raise HTTPException(
                    status_code=429,
                    detail="Rate limit reached. Try again in a moment.",
                    headers={"Retry-After": str(retry_after_seconds(e))},
                )
            except Exception as e:
                print(f"[ERR] {model}: {e}")
                last_error = e
                continue

        if not suggestions or len(suggestions) < 3:
            # The provider's own words (model ids, quota wording, stack detail)
            # stay in our logs: the client turns any 5xx into its own copy, and
            # echoing upstream text back has only ever leaked internals.
            print(f"[ERR] generation produced no usable suggestions: "
                  f"{last_error if last_error else 'model returned nothing valid'}")
            raise HTTPException(status_code=500, detail="AI generation failed")

        # Client holds the context; we pass its sent_replies through unchanged
        # and hand back either the freshly updated summary or the prior one.
        prior_summary = request.context.summary if request.context else ""
        passthrough_sent = request.context.sent_replies if request.context else []
        result_context = ConversationContext(
            summary=updated_summary or prior_summary,
            sent_replies=passthrough_sent,
        )

        # Only now — after a generation the user actually got — do we spend
        # allowance. A Groq failure above never costs the user anything, and Pro
        # users never touch the free counter at all.
        if not pro:
            try:
                free_used = await store.increment(app_user_id)
            except Exception as e:
                # The user already has their replies; losing one tick of the
                # counter is better than 500-ing on a delivered response.
                print(f"[ERR] allowance increment failed for {app_user_id}: {e}")
                free_used += 1

        return GenerateRepliesResponse(
            suggestions=suggestions[:3],
            context=result_context,
            is_pro=pro,
            free_used=free_used,
            free_limit=FREE_GENERATION_LIMIT,
            remaining=_remaining(free_used),
        )

    except HTTPException:
        raise
    except Exception as e:
        print(f"[ERR] unexpected failure in /generate-replies: {e!r}")
        raise HTTPException(status_code=500, detail="Unexpected error")

if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8000))
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=port,
        workers=1,
        loop="uvloop" if os.name != "nt" else "asyncio",
        http="httptools",
        log_level="warning"
    )
