defmodule StackverseBackend.I18n do
  @moduledoc false

  alias StackverseBackend.Repo

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
    languages = Enum.uniq([language, @default_language])

    """
    select text from messages
    where key = $1 and language = any($2::text[])
    order by case when language = $3 then 0 else 1 end
    limit 1
    """
    |> Repo.query!([key, languages, language])
    |> case do
      %{rows: [[text]]} -> text
      _ -> key
    end
  end

  def bundle(language) do
    languages = Enum.uniq([language, @default_language])

    result =
      Repo.query!(
        """
        select key, language, text from messages
        where language = any($1::text[])
        order by key, case when language = $2 then 1 else 0 end
        """,
        [languages, language]
      )

    Enum.reduce(result.rows, %{}, fn [key, row_language, text], acc ->
      if row_language == language or not Map.has_key?(acc, key) do
        Map.put(acc, key, text)
      else
        acc
      end
    end)
  end

  defp supported_languages do
    %{rows: rows} = Repo.query!("select distinct language from messages", [])

    rows
    |> Enum.map(fn [language] -> language end)
    |> MapSet.new()
  end
end
