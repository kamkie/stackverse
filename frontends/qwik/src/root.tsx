// The shared design is part of the contract - consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { component$ } from "@builder.io/qwik";
import App from "./App";

export default component$(() => {
  return <App />;
});
