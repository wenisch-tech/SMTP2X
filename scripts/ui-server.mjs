import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { delimiter } from "node:path";
const classpath = [
  "target/classes",
  "target/test-classes",
  readFileSync("target/ui-classpath.txt", "utf8").trim(),
].join(delimiter);
const child = spawn(
  "java",
  ["-cp", classpath, "tech.wenisch.smtp2x.web.FixtureServer"],
  { stdio: "inherit" },
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => child.kill(signal));
child.on("exit", (code) => process.exit(code ?? 0));
