// The shared design is part of the contract — consumed verbatim from
// spec/design, never copied or overridden (see frontends/README.md).
import "../../../spec/design/tokens.css";
import "../../../spec/design/stackverse.css";

import { StackverseApp } from "./app";

const app = document.querySelector("stackverse-app");
if (!(app instanceof StackverseApp)) {
  throw new Error("stackverse-app not found");
}
