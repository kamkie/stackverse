import json

import pytest
from starlette.requests import Request

from stackverse_backend.problems import (
    BadRequestProblem,
    NotFoundProblem,
    escape_like,
    first_param,
    multi_param,
    omit_none,
    parse_uuid,
    problem_response,
    require_max_length,
    require_valid_paging,
    single_param,
)


def make_request(query: str = "") -> Request:
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/test",
            "headers": [],
            "query_string": query.encode("ascii"),
        }
    )


def test_problem_response_uses_rfc9457_media_type_and_optional_fields() -> None:
    response = problem_response(
        400,
        "Bad Request",
        "Request validation failed.",
        [{"field": "title", "messageKey": "validation.title.required", "message": "Title is required."}],
    )

    assert response.status_code == 400
    assert response.media_type == "application/problem+json"
    assert json.loads(response.body) == {
        "type": "about:blank",
        "title": "Bad Request",
        "status": 400,
        "detail": "Request validation failed.",
        "errors": [{"field": "title", "messageKey": "validation.title.required", "message": "Title is required."}],
    }


def test_query_param_helpers_preserve_contract_for_repeated_values() -> None:
    request = make_request("tag=python&tag=fastapi&q=search")

    assert first_param(request, "tag") == "python"
    assert multi_param(request, "tag") == ["python", "fastapi"]
    assert single_param(request, "q") == "search"
    assert single_param(request, "missing") is None
    with pytest.raises(BadRequestProblem, match="tag must not be repeated"):
        single_param(request, "tag")


def test_require_valid_paging_defaults_and_rejects_out_of_range_values() -> None:
    assert require_valid_paging(make_request("")) == (0, 20)
    assert require_valid_paging(make_request("page=2&size=100")) == (2, 100)

    with pytest.raises(BadRequestProblem, match="page must be an integer"):
        require_valid_paging(make_request("page=two"))
    with pytest.raises(BadRequestProblem, match="page must not be negative"):
        require_valid_paging(make_request("page=-1"))
    with pytest.raises(BadRequestProblem, match="size must be between 1 and 100"):
        require_valid_paging(make_request("size=101"))


def test_misc_problem_helpers_cover_masking_and_sql_like_escaping() -> None:
    assert parse_uuid("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA") == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    with pytest.raises(NotFoundProblem):
        parse_uuid("not-a-uuid")

    with pytest.raises(BadRequestProblem, match="q must be at most 3 characters"):
        require_max_length("four", 3, "q")

    assert escape_like(r"50%_off\path") == r"50\%\_off\\path"
    assert omit_none({"present": 1, "missing": None}) == {"present": 1}
