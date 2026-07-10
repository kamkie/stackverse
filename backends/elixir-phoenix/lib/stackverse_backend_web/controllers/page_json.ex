defmodule StackverseBackendWeb.PageJSON do
  @moduledoc false

  def response(items, page, size, total_items) do
    %{
      items: items,
      page: page,
      size: size,
      totalItems: total_items,
      totalPages: ceil(total_items / size)
    }
  end
end
