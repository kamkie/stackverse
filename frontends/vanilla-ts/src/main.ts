// The shared design is part of the contract — consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { startAppController } from "./app-controller";

const root = document.getElementById("app");
if (!(root instanceof HTMLElement)) throw new Error("#app not found");

void startAppController(root);
