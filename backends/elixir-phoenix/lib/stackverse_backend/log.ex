defmodule StackverseBackend.Log do
  @moduledoc false

  require Logger

  def event(level, event, outcome, message, fields \\ []) do
    Logger.log(level, message, Keyword.merge([event: event, outcome: outcome], fields))
  end
end
