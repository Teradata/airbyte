# Copyright (c) 2023 Airbyte, Inc., all rights reserved.

from typing import Any, Dict, Optional

from airbyte_cdk.utils.datetime_helpers import AirbyteDateTime


class ConfigBuilder:
    def __init__(self) -> None:
        self._subdomain: Optional[str] = None
        self._start_date: Optional[str] = None
        self._credentials: Dict[str, str] = {}
        self._ignore_pagination: Optional[bool] = None

    def with_subdomain(self, subdomain: str) -> "ConfigBuilder":
        self._subdomain = subdomain
        return self

    def with_oauth_credentials(self, access_token: str) -> "ConfigBuilder":
        self._credentials["access_token"] = access_token
        self._credentials["credentials"] = "oauth2.0"
        return self

    def with_basic_auth_credentials(self, email: str, password: str) -> "ConfigBuilder":
        self._credentials["api_token"] = password
        self._credentials["credentials"] = "api_token"
        self._credentials["email"] = email
        return self

    def with_start_date(self, start_date: AirbyteDateTime) -> "ConfigBuilder":
        self._start_date = start_date.strftime("%Y-%m-%dT%H:%M:%SZ")
        return self

    def with_ignore_pagination(self) -> "ConfigBuilder":
        self._ignore_pagination = True
        return self

    def build(self) -> Dict[str, Any]:
        config = {}
        if self._subdomain:
            config["subdomain"] = self._subdomain
        if self._start_date:
            config["start_date"] = self._start_date
        if self._credentials:
            config["credentials"] = self._credentials
        if self._ignore_pagination:
            config["ignore_pagination"] = self._ignore_pagination
        return config
