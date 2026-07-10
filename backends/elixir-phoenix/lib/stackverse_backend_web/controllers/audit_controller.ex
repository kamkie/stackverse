defmodule StackverseBackendWeb.AuditController do
  use Phoenix.Controller, formats: [:json]

  alias StackverseBackend.Audit
  alias StackverseBackendWeb.{AdminJSON, PageJSON}
  alias StackverseBackendWeb.ControllerSupport, as: Support

  def list(conn, _params) do
    with {:ok, _caller} <- Support.require_role(conn, "admin"),
         {:ok, %{page: page, size: size}} <- Support.paging(conn),
         {:ok, filters} <- filters(conn) do
      result = Audit.list(filters, page, size)
      items = Enum.map(result.items, &AdminJSON.audit/1)
      json(conn, PageJSON.response(items, page, size, result.total))
    else
      {:error, conn} -> conn
    end
  end

  defp filters(conn) do
    with {:ok, actor} <- Support.optional_single(conn, "actor"),
         {:ok, action} <- Support.optional_single(conn, "action"),
         {:ok, target_type} <- Support.optional_single(conn, "targetType"),
         {:ok, target_id} <- Support.optional_single(conn, "targetId"),
         {:ok, from} <- Support.datetime_param(conn, "from"),
         {:ok, to} <- Support.datetime_param(conn, "to") do
      {:ok,
       %{
         actor: actor,
         action: action,
         target_type: target_type,
         target_id: target_id,
         from: from,
         to: to
       }}
    end
  end
end
