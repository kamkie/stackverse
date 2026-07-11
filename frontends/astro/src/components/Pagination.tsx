import { Show } from "solid-js";
import { i18n, m } from "../lib/i18n";

interface Props {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}

export default function Pagination(props: Props) {
  return (
    <Show when={props.totalPages > 1}>
      <div class="sv-pagination">
        <button
          type="button"
          class="sv-button sv-button--sm"
          disabled={props.page <= 0}
          onClick={() => props.onPage(props.page - 1)}
        >
          {m(i18n(), "ui.action.previous")}
        </button>
        <span>
          {props.page + 1} / {props.totalPages}
        </span>
        <button
          type="button"
          class="sv-button sv-button--sm"
          disabled={props.page >= props.totalPages - 1}
          onClick={() => props.onPage(props.page + 1)}
        >
          {m(i18n(), "ui.action.next")}
        </button>
      </div>
    </Show>
  );
}
