defmodule StackverseBackend.Seed do
  @moduledoc false

  alias StackverseBackend.{Log, Repo}

  def import! do
    dir = Application.fetch_env!(:stackverse_backend, :settings)[:seed_messages_dir]

    files =
      dir
      |> File.ls!()
      |> Enum.filter(&String.ends_with?(&1, ".json"))
      |> Enum.sort()

    Enum.each(files, fn file ->
      language = Path.basename(file, ".json")
      entries = dir |> Path.join(file) |> File.read!() |> Jason.decode!()
      keys = Map.keys(entries)
      texts = Enum.map(keys, &Map.fetch!(entries, &1))

      result =
        Repo.query!(
          """
          insert into messages (id, key, language, text, created_at, updated_at)
          select gen_random_uuid(), key, $1, text, now(), now()
          from unnest($2::text[], $3::text[]) as seed(key, text)
          on conflict (key, language) do nothing
          """,
          [language, keys, texts]
        )

      inserted = result.num_rows

      Log.event(:info, "message_seed_imported", "success", "Message seed imported",
        language: language,
        inserted: inserted,
        skipped: length(keys) - inserted
      )
    end)
  end
end
