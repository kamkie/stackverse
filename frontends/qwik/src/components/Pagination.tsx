import { component$, type PropFunction } from "@builder.io/qwik";
import { m, type I18nState } from "../lib/i18n";

interface Props {
  i18n: I18nState;
  page: number;
  totalPages: number;
  onPage$: PropFunction<(page: number) => void>;
}

export default component$<Props>((props) => {
  if (props.totalPages <= 1) return null;
  return (
    <div class="sv-pagination">
      <button
        type="button"
        class="sv-button sv-button--sm"
        disabled={props.page <= 0}
        onClick$={() => props.onPage$(props.page - 1)}
      >
        {m(props.i18n, "ui.action.previous")}
      </button>
      <span>
        {props.page + 1} / {props.totalPages}
      </span>
      <button
        type="button"
        class="sv-button sv-button--sm"
        disabled={props.page >= props.totalPages - 1}
        onClick$={() => props.onPage$(props.page + 1)}
      >
        {m(props.i18n, "ui.action.next")}
      </button>
    </div>
  );
});
