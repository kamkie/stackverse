defmodule StackverseBackend.I18n do
  @moduledoc false

  import Ecto.Query

  alias StackverseBackend.Repo
  alias StackverseBackend.Schemas.Message

  @default_language "en"

  def default_language, do: @default_language

  def resolve_language(lang, accept_language) do
    supported = supported_languages()

    cond do
      is_binary(lang) and MapSet.member?(supported, lang) ->
        lang

      true ->
        accept_language
        |> parse_accept_language()
        |> Enum.find(&MapSet.member?(supported, &1))
        |> Kernel.||(@default_language)
    end
  end

  def parse_accept_language(nil), do: []
  def parse_accept_language(""), do: []

  def parse_accept_language(header) do
    header
    |> String.split(",", trim: true)
    |> Enum.with_index()
    |> Enum.map(fn {part, index} ->
      [tag | parameters] = String.split(String.trim(part), ";")

      quality =
        parameters
        |> Enum.find_value(1.0, fn parameter ->
          case Regex.run(~r/^\s*q=([0-9.]+)\s*$/, parameter) do
            [_, raw] ->
              case Float.parse(raw) do
                {value, ""} -> value
                _ -> 0.0
              end

            _ ->
              nil
          end
        end)

      code =
        tag
        |> String.downcase()
        |> String.split("-", parts: 2)
        |> hd()

      {code, quality, index}
    end)
    |> Enum.filter(fn {code, _quality, _index} -> Regex.match?(~r/^[a-z]{1,8}$/, code) end)
    |> Enum.sort_by(fn {_code, quality, index} -> {-quality, index} end)
    |> Enum.map(fn {code, _quality, _index} -> code end)
  end

  def localize(key, language) do
    text_for(key, language) || text_for(key, @default_language) || key
  end

  def bundle(language) do
    languages = Enum.uniq([language, @default_language])

    messages = Message |> where([message], message.language in ^languages) |> Repo.all()

    fallback =
      messages
      |> Enum.filter(&(&1.language == @default_language))
      |> Map.new(&{&1.key, &1.text})

    requested =
      messages
      |> Enum.filter(&(&1.language == language))
      |> Map.new(&{&1.key, &1.text})

    Map.merge(fallback, requested)
  end

  defp supported_languages do
    Message
    |> distinct([message], message.language)
    |> select([message], message.language)
    |> Repo.all()
    |> MapSet.new()
  end

  defp text_for(key, language) do
    Repo.one(
      from message in Message,
        where: message.key == ^key and message.language == ^language,
        select: message.text
    )
  end
end
