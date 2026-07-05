import AxeBuilder from "@axe-core/playwright";
import { expect, type Page, type TestInfo } from "@playwright/test";

const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

type AxeResults = Awaited<ReturnType<InstanceType<typeof AxeBuilder>["analyze"]>>;
type AxeViolation = AxeResults["violations"][number];

export async function expectNoAxeViolations(
  page: Page,
  testInfo: TestInfo,
  label: string,
): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();

  if (results.violations.length > 0) {
    await testInfo.attach(`${slug(label)}-axe-violations.json`, {
      body: JSON.stringify(results.violations, null, 2),
      contentType: "application/json",
    });
  }

  expect(results.violations, formatViolations(label, results.violations)).toEqual([]);
}

function formatViolations(label: string, violations: AxeViolation[]): string {
  if (violations.length === 0) return `${label}: no axe-core violations`;

  const details = violations
    .map((violation) => {
      const nodes = violation.nodes
        .slice(0, 5)
        .map((node) => {
          const target = node.target.join(", ");
          const summary = node.failureSummary?.replace(/\s+/g, " ").trim();
          return summary ? `      - ${target}: ${summary}` : `      - ${target}`;
        })
        .join("\n");
      const hidden = violation.nodes.length > 5 ? `\n      - ...${violation.nodes.length - 5} more` : "";
      return [
        `  ${violation.id} (${violation.impact ?? "unknown impact"})`,
        `    ${violation.help}`,
        `    ${violation.helpUrl}`,
        nodes,
        hidden,
      ]
        .filter(Boolean)
        .join("\n");
    })
    .join("\n\n");

  return `${label}: ${violations.length} axe-core violation(s)\n${details}`;
}

function slug(label: string): string {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}
