from __future__ import annotations

import json
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class HttpJsonError(RuntimeError):
    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


def request_json(
    url: str,
    *,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 30,
) -> dict[str, Any]:
    encoded = None if payload is None else json.dumps(payload).encode("utf-8")
    request_headers = {"Accept": "application/json", **(headers or {})}
    if encoded is not None:
        request_headers["Content-Type"] = "application/json; charset=utf-8"
    request = Request(url, data=encoded, headers=request_headers, method=method)

    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise HttpJsonError(
            f"HTTP {error.code} from {url}: {body}", status_code=error.code
        ) from error
    except URLError as error:
        raise HttpJsonError(f"Cannot reach {url}: {error.reason}") from error

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as error:
        raise HttpJsonError(f"Non-JSON response from {url}: {raw[:300]}") from error
    if not isinstance(parsed, dict):
        raise HttpJsonError(f"Expected a JSON object from {url}")
    return parsed


class MindustryClient:
    def __init__(self, base_url: str, token: str = "", team: str = "sharded") -> None:
        self.base_url = base_url.rstrip("/")
        self.headers = {"Authorization": f"Bearer {token}"} if token else {}
        self.headers["X-Mindustry-Team"] = team

    def health(self) -> dict[str, Any]:
        return request_json(f"{self.base_url}/health", headers=self.headers)

    def state(self) -> dict[str, Any]:
        return request_json(f"{self.base_url}/v1/state", headers=self.headers)

    def act(
        self,
        request_id: str,
        actions: list[dict[str, Any]],
        *,
        expected_episode_id: str,
        observed_tick: float,
        agent_telemetry: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "request_id": request_id,
            "expected_episode_id": expected_episode_id,
            "observed_tick": observed_tick,
            "actions": actions,
        }
        if agent_telemetry is not None:
            payload["agent_telemetry"] = agent_telemetry
        return request_json(
            f"{self.base_url}/v1/action",
            method="POST",
            payload=payload,
            headers=self.headers,
        )
