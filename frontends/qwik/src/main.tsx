import "@builder.io/qwik/qwikloader.js";

import { render } from "@builder.io/qwik";
import Root from "./root";

if (import.meta.env.DEV) {
  void import("./dev/forwardConsoleToDevServer").then(({ forwardConsoleToDevServer }) => {
    forwardConsoleToDevServer();
  });
  void import("./dev/logUserActions").then(({ logUserActions }) => {
    logUserActions();
  });
}

const target = document.getElementById("app");
if (!target) throw new Error("#app not found");
render(target, <Root />);
