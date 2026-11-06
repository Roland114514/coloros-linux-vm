#!/usr/bin/env python3
"""
Gemini API Caller Utility
Supports calling Gemini models (including gemini-4-argon) via the official Gemini REST API.
Zero external dependencies required (uses standard library urllib).
"""

import os
import sys
import json
import argparse
import urllib.request
import urllib.error
import ssl
from pathlib import Path

try:
    import certifi
    SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where())
except Exception:
    SSL_CONTEXT = ssl.create_default_context()
    SSL_CONTEXT.check_hostname = False
    SSL_CONTEXT.verify_mode = ssl.CERT_NONE

BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

def load_env():
    """Load GEMINI_API_KEY from .env file if not in environment."""
    if os.environ.get("GEMINI_API_KEY"):
        return os.environ.get("GEMINI_API_KEY")
    
    # Try finding .env in current dir or parent dirs
    current = Path(__file__).resolve().parent
    for p in [current, current.parent, Path.cwd()]:
        env_file = p / ".env"
        if env_file.exists():
            with open(env_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("GEMINI_API_KEY="):
                        key = line.split("=", 1)[1].strip().strip('"').strip("'")
                        if key:
                            os.environ["GEMINI_API_KEY"] = key
                            return key
    return None

def call_gemini(
    prompt: str,
    model: str = "gemini-4-argon",
    system_instruction: str = None,
    temperature: float = 0.7,
    max_output_tokens: int = 8192,
    context_files: list = None,
    api_key: str = None
) -> str:
    key = api_key or load_env()
    if not key:
        raise ValueError("Missing GEMINI_API_KEY. Set it in .env or environment variable.")

    # Combine context files into prompt if provided
    full_prompt = ""
    if context_files:
        full_prompt += "### Context Files:\n"
        for filepath in context_files:
            p = Path(filepath)
            if p.exists():
                try:
                    content = p.read_text(encoding="utf-8")
                    full_prompt += f"\n--- File: {filepath} ---\n{content}\n"
                except Exception as e:
                    full_prompt += f"\n--- File: {filepath} (Error reading: {e}) ---\n"
        full_prompt += "\n### User Instruction:\n"
    full_prompt += prompt

    url = f"{BASE_URL}/{model}:generateContent?key={key}"

    payload = {
        "contents": [
            {
                "parts": [{"text": full_prompt}]
            }
        ],
        "generationConfig": {
            "temperature": temperature,
            "maxOutputTokens": max_output_tokens
        }
    }

    if system_instruction:
        payload["systemInstruction"] = {
            "parts": [{"text": system_instruction}]
        }

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"}
    )

    try:
        with urllib.request.urlopen(req, timeout=120, context=SSL_CONTEXT) as response:
            res_body = response.read().decode("utf-8")
            res_json = json.loads(res_body)
            
            candidates = res_json.get("candidates", [])
            if not candidates:
                return f"[No candidate returned from {model}]"
            
            parts = candidates[0].get("content", {}).get("parts", [])
            text_parts = [p.get("text", "") for p in parts if "text" in p]
            return "".join(text_parts)
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8")
        raise RuntimeError(f"HTTP Error {e.code}: {err_msg}")
    except Exception as e:
        raise RuntimeError(f"Request failed: {str(e)}")

def main():
    parser = argparse.ArgumentParser(description="Call Gemini models via API")
    parser.add_argument("-p", "--prompt", type=str, help="Prompt text")
    parser.add_argument("-f", "--file", type=str, help="File containing prompt")
    parser.add_argument("-m", "--model", type=str, default="gemini-4-argon", help="Model name (default: gemini-4-argon)")
    parser.add_argument("-s", "--system", type=str, help="System instruction")
    parser.add_argument("-t", "--temperature", type=float, default=0.7, help="Sampling temperature")
    parser.add_argument("-c", "--context", nargs="*", help="List of file paths to include as context")
    parser.add_argument("--json", action="store_true", help="Output full JSON response")

    args = parser.parse_args()

    prompt_text = args.prompt
    if not prompt_text and args.file:
        prompt_text = Path(args.file).read_text(encoding="utf-8")
    elif not prompt_text and not sys.stdin.isatty():
        prompt_text = sys.stdin.read()

    if not prompt_text:
        parser.error("A prompt must be provided via --prompt, --file, or stdin.")

    try:
        result = call_gemini(
            prompt=prompt_text,
            model=args.model,
            system_instruction=args.system,
            temperature=args.temperature,
            context_files=args.context
        )
        print(result)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
