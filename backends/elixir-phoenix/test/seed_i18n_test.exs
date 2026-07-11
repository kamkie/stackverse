defmodule StackverseBackend.SeedI18nTest do
  use StackverseBackend.DataCase, async: false

  @moduletag :database
  @moduletag skip: System.get_env("STACKVERSE_DB_TESTS") != "true"

  alias StackverseBackend.{I18n, Query, Repo, Seed}
  alias StackverseBackend.Schemas.Message

  test "message seed import is sorted, idempotent, and preserves runtime edits" do
    directory =
      Path.join(System.tmp_dir!(), "stackverse-seed-#{System.unique_integer([:positive])}")

    File.mkdir_p!(directory)
    File.write!(Path.join(directory, "pl.json"), Jason.encode!(%{"ui.hello" => "Cześć"}))
    File.write!(Path.join(directory, "en.json"), Jason.encode!(%{"ui.hello" => "Hello"}))

    original = Application.get_env(:stackverse_backend, :settings)
    Application.put_env(:stackverse_backend, :settings, seed_messages_dir: directory)

    on_exit(fn ->
      File.rm_rf!(directory)

      if is_nil(original),
        do: Application.delete_env(:stackverse_backend, :settings),
        else: Application.put_env(:stackverse_backend, :settings, original)
    end)

    Seed.import!()
    assert Repo.aggregate(Message, :count, :id) == 2

    english = Repo.get_by!(Message, key: "ui.hello", language: "en")
    english |> Ecto.Changeset.change(text: "Edited at runtime") |> Repo.update!()

    Seed.import!()
    assert Repo.aggregate(Message, :count, :id) == 2
    assert Repo.get_by!(Message, key: "ui.hello", language: "en").text == "Edited at runtime"
  end

  test "language resolution honors explicit support, quality ordering, regional tags, and English fallback" do
    insert_message("ui.title", "en", "Title")
    insert_message("ui.title", "pl", "Tytuł")
    insert_message("ui.only-en", "en", "Fallback")

    assert I18n.parse_accept_language(nil) == []
    assert I18n.parse_accept_language("") == []
    assert I18n.parse_accept_language("de;q=bogus, pl-PL;q=0.8, en;q=0.7") == ["de", "pl", "en"]
    assert I18n.resolve_language("pl", "en") == "pl"
    assert I18n.resolve_language("de", "pl-PL;q=0.9,en;q=0.8") == "pl"
    assert I18n.resolve_language(nil, "de") == "en"
    assert I18n.localize("ui.title", "pl") == "Tytuł"
    assert I18n.localize("ui.only-en", "pl") == "Fallback"
    assert I18n.localize("missing.key", "pl") == "missing.key"

    assert I18n.bundle("pl") == %{
             "ui.only-en" => "Fallback",
             "ui.title" => "Tytuł"
           }
  end

  test "query helpers map PostgreSQL results, escape wildcard input, and emit UTC microsecond timestamps" do
    result = %Postgrex.Result{columns: ["name", "count"], rows: [["elixir", 2]]}
    assert Query.rows(result) == [%{"name" => "elixir", "count" => 2}]
    assert Query.escape_like(~S(100%_\safe)) == ~S(100\%\_\\safe)
    assert %DateTime{time_zone: "Etc/UTC", microsecond: {_value, 6}} = Query.now()
  end

  defp insert_message(key, language, text) do
    %Message{}
    |> Message.changeset(%{key: key, language: language, text: text})
    |> Repo.insert!()
  end
end
