defmodule StackverseBackendWeb.ModerationController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Moderation, Problem, Validation}
  alias StackverseBackendWeb.{BookmarkJSON, PageJSON, ReportJSON}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def list_reports(conn, _params) do
    with {:ok, _caller} <- Support.require_role(conn, "moderator"),
         {:ok, %{page: page, size: size}} <- Support.paging(conn),
         {:ok, status} <- Support.optional_enum(conn, "status", ~w[open dismissed actioned]) do
      result = Moderation.list_reports(status || "open", page, size)
      items = Enum.map(result.items, &ReportJSON.response/1)
      json(conn, PageJSON.response(items, page, size, result.total))
    else
      {:error, conn} -> conn
    end
  end

  def resolve_report(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_role(conn, "moderator"),
         {:ok, id} <- Support.path_uuid(conn, raw_id),
         {:ok, input} <-
           Support.validate(conn, Validation.validate_report_resolution(conn.body_params)) do
      case Moderation.resolve_report(caller.username, id, input) do
        {:ok, report} -> json(conn, ReportJSON.response(report))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, :duplicate_report} -> duplicate_problem(conn)
      end
    else
      {:error, conn} -> conn
    end
  end

  def set_bookmark_status(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_role(conn, "moderator"),
         {:ok, id} <- Support.path_uuid(conn, raw_id),
         {:ok, input} <-
           Support.validate(conn, Validation.validate_bookmark_status(conn.body_params)) do
      case Moderation.set_bookmark_status(caller.username, id, input) do
        {:ok, bookmark} -> json(conn, BookmarkJSON.response(bookmark))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
      end
    else
      {:error, conn} -> conn
    end
  end

  defp duplicate_problem(conn) do
    Problem.send(
      conn,
      409,
      "Conflict",
      "The reporter already has another open report on this bookmark."
    )
  end
end
