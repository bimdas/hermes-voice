#!/usr/bin/env python3
"""
Hermes Voice Client for Pixel 9 Pro
====================================
Thin voice terminal that connects to the Voice Bridge on P6P.

Flow: Mic -> Record -> Send to P6P -> Play response audio

Dependencies:
  pip install requests
  pkg install termux-api mpv

Usage:
  python voice_client.py              # Interactive mode (press Enter to talk)
  python voice_client.py --toggle     # Toggle mode (press Enter to start/stop)
  python voice_client.py --continuous # Always listening (experimental)
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
VOICE_BRIDGE_URL = os.getenv(
    "VOICE_BRIDGE_URL",
    "http://100.67.204.21:8700"  # P6P Tailscale IP + voice bridge port
)
MAX_RECORD_SECONDS = int(os.getenv("MAX_RECORD_SECONDS", "60"))
SAMPLE_RATE = 16000
RECORDINGS_DIR = Path.home() / ".hermes" / "voice-recordings"

# Colors for terminal
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"


def check_dependencies():
    """Verify required tools are installed."""
    missing = []
    for tool in ["termux-microphone-record", "mpv", "curl"]:
        if subprocess.run(["which", tool], capture_output=True).returncode != 0:
            missing.append(tool)
    if missing:
        print(f"{RED}Missing dependencies: {', '.join(missing)}{RESET}")
        print(f"Install with: pkg install {' '.join(missing)}")
        if "termux-microphone-record" in missing:
            print("Also install: pkg install termux-api")
            print("And the Termux:API app from F-Droid")
        sys.exit(1)


def check_bridge():
    """Check if voice bridge is reachable."""
    import requests
    try:
        resp = requests.get(f"{VOICE_BRIDGE_URL}/health", timeout=5)
        data = resp.json()
        if data.get("status") == "ok":
            hermes = "connected" if data.get("hermes_api") else "NOT CONNECTED"
            groq = "configured" if data.get("groq_key") else "NOT CONFIGURED"
            print(f"{GREEN}Voice Bridge: OK{RESET}")
            print(f"  Hermes API: {hermes}")
            print(f"  Groq STT:   {groq}")
            print(f"  TTS Voice:  {data.get('tts_voice', '?')}")
            return True
        else:
            print(f"{RED}Voice Bridge: unhealthy{RESET}")
            return False
    except Exception as e:
        print(f"{RED}Voice Bridge unreachable: {e}{RESET}")
        print(f"  URL: {VOICE_BRIDGE_URL}")
        print(f"  Make sure the voice bridge is running on P6P")
        return False


def record_audio(duration=None):
    """Record audio from mic using termux-microphone-record."""
    RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
    output = RECORDINGS_DIR / f"rec_{int(time.time())}.ogg"

    cmd = ["termux-microphone-record", "-f", str(output), "-l", str(MAX_RECORD_SECONDS)]
    if duration:
        cmd = ["termux-microphone-record", "-f", str(output), "-l", str(duration)]

    print(f"{YELLOW}Recording... (speak now, max {MAX_RECORD_SECONDS}s){RESET}")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    try:
        proc.wait(timeout=MAX_RECORD_SECONDS + 5)
    except subprocess.TimeoutExpired:
        proc.send_signal(signal.SIGINT)
        proc.wait(timeout=5)

    if output.exists() and output.stat().st_size > 0:
        size_kb = output.stat().st_size / 1024
        print(f"{GREEN}Recorded: {size_kb:.0f}KB{RESET}")
        return output
    else:
        print(f"{RED}Recording failed or empty{RESET}")
        return None


def record_audio_interactive():
    """Record with Enter to stop."""
    RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
    output = RECORDINGS_DIR / f"rec_{int(time.time())}.ogg"

    cmd = ["termux-microphone-record", "-f", str(output), "-l", str(MAX_RECORD_SECONDS)]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    print(f"{YELLOW}Recording... press Enter to stop{RESET}")
    try:
        # Wait for user to press Enter (or timeout)
        input()
    except (KeyboardInterrupt, EOFError):
        pass

    proc.send_signal(signal.SIGINT)
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()

    if output.exists() and output.stat().st_size > 1000:  # At least 1KB
        size_kb = output.stat().st_size / 1024
        print(f"{GREEN}Recorded: {size_kb:.0f}KB{RESET}")
        return output
    else:
        print(f"{RED}Recording too short or empty{RESET}")
        if output.exists():
            output.unlink()
        return None


def send_audio(audio_path):
    """Send audio to voice bridge and get response."""
    import requests

    print(f"{CYAN}Sending to Hermes...{RESET}")
    t0 = time.time()

    try:
        with open(audio_path, "rb") as f:
            resp = requests.post(
                f"{VOICE_BRIDGE_URL}/voice",
                data=f.read(),
                headers={"Content-Type": "audio/ogg"},
                timeout=120,
            )

        elapsed = time.time() - t0

        if resp.status_code != 200:
            print(f"{RED}Bridge error ({resp.status_code}): {resp.text[:200]}{RESET}")
            return None

        # Extract metadata
        transcript = resp.headers.get("X-Transcript", "")
        response_text = resp.headers.get("X-Response", "")
        timings = json.loads(resp.headers.get("X-Timings", "{}"))

        print(f"\n{BOLD}You:{RESET} {transcript}")
        print(f"{BOLD}Hermes:{RESET} {response_text}")
        print(f"{CYAN}Timings: STT={timings.get('stt','?')}s "
              f"Chat={timings.get('chat','?')}s "
              f"TTS={timings.get('tts','?')}s "
              f"Total={timings.get('total','?')}s{RESET}")

        # Save and return audio
        if resp.headers.get("Content-Type", "").startswith("audio"):
            audio_out = RECORDINGS_DIR / f"resp_{int(time.time())}.mp3"
            audio_out.write_bytes(resp.content)
            return audio_out

        return None

    except requests.exceptions.ConnectionError:
        print(f"{RED}Cannot reach voice bridge at {VOICE_BRIDGE_URL}{RESET}")
        return None
    except Exception as e:
        print(f"{RED}Error: {e}{RESET}")
        return None


def play_audio(audio_path):
    """Play audio through speaker using mpv."""
    if not audio_path or not audio_path.exists():
        return

    print(f"{CYAN}Playing response...{RESET}")
    try:
        subprocess.run(
            ["mpv", "--no-video", "--really-quiet", str(audio_path)],
            timeout=120,
        )
    except FileNotFoundError:
        # Fallback to termux-media-player
        try:
            subprocess.run(["termux-media-player", "play", str(audio_path)],
                          timeout=120)
        except Exception as e:
            print(f"{RED}Playback failed: {e}{RESET}")
    except Exception as e:
        print(f"{RED}Playback error: {e}{RESET}")


def cleanup_old_recordings(max_age_hours=24):
    """Clean up old recordings."""
    if not RECORDINGS_DIR.exists():
        return
    cutoff = time.time() - (max_age_hours * 3600)
    for f in RECORDINGS_DIR.iterdir():
        if f.is_file() and f.stat().st_mtime < cutoff:
            f.unlink()


# ---------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------
def interactive_mode():
    """Press Enter to talk, get response."""
    print(f"\n{BOLD}Hermes Voice Assistant{RESET}")
    print(f"Press {GREEN}Enter{RESET} to start recording, "
          f"{GREEN}Enter{RESET} again to stop")
    print(f"Type {YELLOW}q{RESET} to quit\n")

    while True:
        try:
            cmd = input(f"{BOLD}[Press Enter to talk]{RESET} ").strip().lower()
            if cmd in ("q", "quit", "exit"):
                break

            # Record
            audio = record_audio_interactive()
            if not audio:
                continue

            # Send and get response
            response_audio = send_audio(audio)

            # Play response
            if response_audio:
                play_audio(response_audio)

            print()  # Blank line between turns

        except KeyboardInterrupt:
            print(f"\n{YELLOW}Exiting...{RESET}")
            break
        except EOFError:
            break


def toggle_mode():
    """Hold-to-talk mode: hold Enter to record, release to send."""
    print(f"\n{BOLD}Hermes Voice Assistant (Toggle Mode){RESET}")
    print(f"Hold {GREEN}Enter{RESET} to start recording, "
          f"press again to stop and send")
    print(f"Type {YELLOW}q{RESET} to quit\n")

    while True:
        try:
            # Wait for user to start
            cmd = input(f"{BOLD}[Hold Enter to talk]{RESET} ").strip().lower()
            if cmd in ("q", "quit", "exit"):
                break

            # Record with fixed duration
            print(f"{YELLOW}Recording for {MAX_RECORD_SECONDS}s "
                  f"(press Ctrl+C to stop early)...{RESET}")
            audio = record_audio()
            if not audio:
                continue

            # Send and get response
            response_audio = send_audio(audio)

            # Play response
            if response_audio:
                play_audio(response_audio)

            print()

        except KeyboardInterrupt:
            print(f"\n{YELLOW}Stopped recording{RESET}")
            # The record_audio function handles the interrupt
            continue


def continuous_mode():
    """Always listening with silence detection (experimental)."""
    print(f"\n{BOLD}Hermes Voice Assistant (Continuous Mode){RESET}")
    print(f"{YELLOW}Always listening... speak when ready{RESET}")
    print(f"Press {YELLOW}Ctrl+C{RESET} to stop\n")

    while True:
        try:
            # Record short chunks and check for speech
            audio = record_audio(duration=5)
            if not audio:
                time.sleep(0.5)
                continue

            # Check if there's actual audio content (simple size check)
            if audio.stat().st_size < 5000:  # Less than 5KB = probably silence
                audio.unlink()
                continue

            # Send and get response
            response_audio = send_audio(audio)

            # Play response
            if response_audio:
                play_audio(response_audio)

        except KeyboardInterrupt:
            print(f"\n{YELLOW}Stopping...{RESET}")
            break


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="Hermes Voice Client")
    parser.add_argument("--url", default=VOICE_BRIDGE_URL,
                       help=f"Voice bridge URL (default: {VOICE_BRIDGE_URL})")
    parser.add_argument("--toggle", action="store_true",
                       help="Toggle mode (hold Enter to record)")
    parser.add_argument("--continuous", action="store_true",
                       help="Always listening mode (experimental)")
    parser.add_argument("--check", action="store_true",
                       help="Check dependencies and connectivity")
    args = parser.parse_args()

    global VOICE_BRIDGE_URL
    VOICE_BRIDGE_URL = args.url

    check_dependencies()
    cleanup_old_recordings()

    if args.check or not check_bridge():
        if args.check:
            sys.exit(0)
        print(f"\n{YELLOW}Start the voice bridge on P6P first:{RESET}")
        print(f"  python ~/.hermes/voice-assistant/voice_bridge.py")
        sys.exit(1)

    if args.continuous:
        continuous_mode()
    elif args.toggle:
        toggle_mode()
    else:
        interactive_mode()


if __name__ == "__main__":
    main()
