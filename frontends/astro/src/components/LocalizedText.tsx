import { onMount } from "solid-js";
import { initializeClient } from "../lib/initializeClient";
import { i18n, m } from "../lib/i18n";

interface Props {
  message: string;
  fallback: string;
}

export default function LocalizedText(props: Props) {
  onMount(() => void initializeClient());
  return <>{i18n().ready ? m(i18n(), props.message) : props.fallback}</>;
}
