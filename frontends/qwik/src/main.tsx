import { render } from "@builder.io/qwik";
import qwikLoaderUrl from "@builder.io/qwik/qwikloader.js?url";
import Root from "./root";

const loader = document.createElement("script");
loader.src = qwikLoaderUrl;
document.head.appendChild(loader);

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
