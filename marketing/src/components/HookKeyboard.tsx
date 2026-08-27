import React from "react";
import { useCurrentFrame } from "remotion";
import { KEYBOARD_HEIGHT, pebble } from "../theme";
import { HookLogo } from "./HookLogo";

export type Suggestion = {
  text: string;
  /** 0..1 pop-in. */
  reveal?: number;
  /** 0..1 press-and-commit, drives the spring squash + glow. */
  press?: number;
};

/**
 * The Hook keyboard surface, rebuilt from `ui/keyboard/KeyboardPanel.kt` and
 * `ui/theme/Pebble.kt`: allowance chip, transcript (screenshot left, replies
 * right), and the bottom bar with "+", the Rizz pill, globe, gear and backspace.
 *
 * Dark theme on purpose — that is what the app looks like on the phones the
 * audience is holding at 1am.
 */
export const HookKeyboard: React.FC<{
  /** What the panel is doing. Mirrors RizzSession.Status. */
  status: "empty" | "generating" | "suggestions";
  suggestions?: Suggestion[];
  /** Rendered inside the screenshot attachment card. */
  screenshot?: React.ReactNode;
  screenshotReveal?: number;
  freeRemaining?: number;
  /** 0..1, the Generate pill being tapped. */
  generatePress?: number;
}> = ({
  status,
  suggestions = [],
  screenshot,
  screenshotReveal = 0,
  freeRemaining = 3,
  generatePress = 0,
}) => {
  return (
    <div
      style={{
        height: KEYBOARD_HEIGHT,
        background: pebble.darkBackground,
        padding: "10px 12px",
        display: "flex",
        flexDirection: "column",
        boxShadow: "0 -1px 0 rgba(255,255,255,0.06)",
      }}
    >
      <AllowanceChip remaining={freeRemaining} />
      <div style={{ height: 6 }} />

      <div
        style={{
          flex: 1,
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          justifyContent: "flex-end",
          gap: 8,
          overflow: "hidden",
        }}
      >
        {status === "empty" && screenshotReveal <= 0 ? (
          <EmptyState />
        ) : (
          <>
            {screenshotReveal > 0 && (
              <div
                style={{
                  display: "flex",
                  justifyContent: "flex-start",
                  opacity: screenshotReveal,
                  transform: `translateY(${(1 - screenshotReveal) * 14}px) scale(${0.9 + screenshotReveal * 0.1})`,
                  transformOrigin: "bottom left",
                }}
              >
                <ScreenshotCard>{screenshot}</ScreenshotCard>
              </div>
            )}

            {status === "generating" && <TypingBubble />}

            {suggestions.map((s, i) => (
              <div
                key={i}
                style={{
                  display: "flex",
                  justifyContent: "flex-end",
                  opacity: s.reveal ?? 1,
                }}
              >
                <PebbleBubble suggestion={s} />
              </div>
            ))}
          </>
        )}
      </div>

      {/* The controls sit on a fade that rises off the bottom of the chat. */}
      <div style={{ position: "relative" }}>
        <div
          style={{
            position: "absolute",
            inset: 0,
            background:
              "linear-gradient(180deg, rgba(0,0,0,0) 0%, rgba(0,0,0,0.6) 100%)",
            pointerEvents: "none",
          }}
        />
        <div style={{ height: 28 }} />
        <BottomBar
          label={status === "generating" ? "Cooking…" : "Rizz"}
          busy={status === "generating"}
          press={generatePress}
        />
      </div>
    </div>
  );
};

const AllowanceChip: React.FC<{ remaining: number }> = ({ remaining }) => (
  <div style={{ display: "flex", justifyContent: "flex-end" }}>
    <span
      style={{
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: 0.5,
        color: pebble.darkOnSurfaceVariant,
        background: `${pebble.darkSurfaceVariant}B3`,
        borderRadius: 10,
        padding: "4px 10px",
      }}
    >
      {remaining > 0 ? `${remaining} free left` : "Go Pro"}
    </span>
  </div>
);

const ScreenshotCard: React.FC<{ children?: React.ReactNode }> = ({
  children,
}) => (
  <div
    style={{
      width: 210,
      height: 150,
      borderRadius: 16,
      overflow: "hidden",
      background: pebble.darkScreenshotCard,
      border: `1px solid ${pebble.darkOutline}99`,
      display: "flex",
      alignItems: "flex-start",
      justifyContent: "center",
    }}
  >
    {children ?? <HookLogo size={52} tile={false} />}
  </div>
);

/**
 * The pebble: vertical blue gradient, soft blue glow, spring squash on press.
 * Same recipe as `Pebble.kt`.
 */
const PebbleBubble: React.FC<{ suggestion: Suggestion }> = ({ suggestion }) => {
  const r = suggestion.reveal ?? 1;
  const p = suggestion.press ?? 0;

  return (
    <div
      style={{
        maxWidth: 240,
        borderRadius: 18,
        padding: "10px 14px",
        fontSize: 14,
        lineHeight: "19px",
        fontWeight: 500,
        color: "#fff",
        background: `linear-gradient(180deg, ${pebble.bubbleTop} 0%, ${pebble.bubbleBottom} 100%)`,
        boxShadow: `0 6px 18px ${pebble.blue}${p > 0 ? "88" : "4D"}, 0 1px 0 rgba(255,255,255,0.28) inset`,
        transform: `translateY(${(1 - r) * 16}px) scale(${(0.86 + r * 0.14) * (1 - p * 0.06)})`,
        transformOrigin: "bottom right",
        transition: "none",
      }}
    >
      {suggestion.text}
    </div>
  );
};

/** No loading screen by design — the transcript stays up and this bubble ticks. */
const TypingBubble: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <div style={{ display: "flex", justifyContent: "flex-start" }}>
      <div
        style={{
          borderRadius: 18,
          padding: "12px 16px",
          background: pebble.darkSurfaceVariant,
          display: "flex",
          gap: 6,
          alignItems: "center",
        }}
      >
        {[0, 1, 2].map((i) => {
          const t = ((frame - i * 4) % 30) / 30;
          const lift = Math.max(0, Math.sin(t * Math.PI * 2));
          return (
            <div
              key={i}
              style={{
                width: 7,
                height: 7,
                borderRadius: 4,
                background: pebble.blueLight,
                opacity: 0.45 + lift * 0.55,
                transform: `translateY(${-lift * 3}px)`,
              }}
            />
          );
        })}
      </div>
    </div>
  );
};

const EmptyState: React.FC = () => (
  <div
    style={{
      flex: 1,
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      padding: "0 24px",
      textAlign: "center",
    }}
  >
    <span
      style={{
        fontSize: 22,
        fontWeight: 600,
        color: pebble.darkOnBackground,
      }}
    >
      Take a screenshot
    </span>
    <span
      style={{
        fontSize: 14,
        marginTop: 8,
        color: pebble.darkOnSurfaceVariant,
      }}
    >
      So Hook knows what&rsquo;s on your screen
    </span>
    <div
      style={{
        marginTop: 20,
        width: 76,
        height: 76,
        borderRadius: 22,
        background: pebble.darkSurfaceVariant,
        border: `1px solid ${pebble.darkOutline}66`,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <HookLogo size={48} tile={false} />
    </div>
  </div>
);

const BottomBar: React.FC<{
  label: string;
  busy: boolean;
  press: number;
}> = ({ label, busy, press }) => (
  <div
    style={{
      display: "flex",
      alignItems: "center",
      gap: 10,
      position: "relative",
    }}
  >
    <IconPebble>
      <path d="M12 5v14M5 12h14" />
    </IconPebble>

    <div
      style={{
        flex: 1,
        borderRadius: 18,
        padding: "13px 16px",
        textAlign: "center",
        fontSize: 15,
        fontWeight: 600,
        color: "#fff",
        background: `linear-gradient(180deg, ${pebble.bubbleTop} 0%, ${pebble.bubbleBottom} 100%)`,
        boxShadow: `0 8px 22px ${pebble.blue}${press > 0 ? "AA" : "55"}, 0 1px 0 rgba(255,255,255,0.28) inset`,
        transform: `scale(${1 - press * 0.05})`,
        opacity: busy ? 0.85 : 1,
      }}
    >
      {label}
    </div>

    <IconPebble>
      <circle cx="12" cy="12" r="10" />
      <path d="M2 12h20" />
      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
    </IconPebble>

    <IconPebble>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6h.09A1.65 1.65 0 0 0 10 3.09V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z" />
    </IconPebble>

    <IconPebble>
      <path d="M9 5h11v14H9L3 12ZM12.5 9.5l5 5M17.5 9.5l-5 5" />
    </IconPebble>
  </div>
);

const IconPebble: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div
    style={{
      width: 44,
      height: 44,
      borderRadius: 16,
      flexShrink: 0,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      background: `linear-gradient(180deg, #2A3145 0%, ${pebble.slate} 100%)`,
      boxShadow: "0 4px 12px rgba(0,0,0,0.5), 0 1px 0 rgba(255,255,255,0.1) inset",
    }}
  >
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke={pebble.darkOnBackground}
      strokeWidth="1.9"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  </div>
);
