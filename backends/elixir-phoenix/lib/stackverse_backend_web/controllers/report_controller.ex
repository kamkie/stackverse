defmodule StackverseBackendWeb.ReportController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.{Problem, Reports, Validation}
  alias StackverseBackendWeb.{PageJSON, ReportJSON}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def create(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, bookmark_id} <- Support.path_uuid(conn, raw_id),
         {:ok, input} <- Support.validate(conn, Validation.validate_report(conn.body_params)) do
      case Reports.create(caller.username, bookmark_id, input) do
        {:ok, report} -> conn |> put_status(201) |> json(ReportJSON.response(report))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, :duplicate_report} -> duplicate_problem(conn)
      end
    else
      {:error, conn} -> conn
    end
  end

  def list(conn, _params) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, %{page: page, size: size}} <- Support.paging(conn),
         {:ok, status} <- Support.optional_enum(conn, "status", ~w[open dismissed actioned]) do
      result = Reports.list(caller.username, status, page, size)
      items = Enum.map(result.items, &ReportJSON.response/1)
      json(conn, PageJSON.response(items, page, size, result.total))
    else
      {:error, conn} -> conn
    end
  end

  def update(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, id} <- Support.path_uuid(conn, raw_id) do
      case Reports.update(caller.username, id, conn.body_params) do
        {:ok, report} -> json(conn, ReportJSON.response(report))
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, :not_open} -> resolved_problem(conn)
        {:error, {:validation, errors}} -> Problem.validation(conn, errors)
      end
    else
      {:error, conn} -> conn
    end
  end

  def withdraw(conn, %{"id" => raw_id}) do
    with {:ok, caller} <- Support.require_caller(conn),
         {:ok, id} <- Support.path_uuid(conn, raw_id) do
      case Reports.withdraw(caller.username, id) do
        {:ok, _result} -> send_resp(conn, 204, "")
        {:error, :not_found} -> Problem.send(conn, 404, "Not Found")
        {:error, :not_open} -> resolved_problem(conn)
      end
    else
      {:error, conn} -> conn
    end
  end

  defp duplicate_problem(conn) do
    Problem.send(conn, 409, "Conflict", "You already have an open report on this bookmark.")
  end

  defp resolved_problem(conn) do
    Problem.send(conn, 409, "Conflict", "The report has already been resolved.")
  end
end
