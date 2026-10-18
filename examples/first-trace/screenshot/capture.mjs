// Maintainer-only capture of a disposable Langfuse 4.41.0 project, never a user's project.
import { chromium } from 'playwright-core';
import { readFile } from 'node:fs/promises';

const host = process.env.LANGFUSE_HOST;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(host ?? '') || !process.env.LANGFUSE_E2E_INIT_PASSWORD) {
  throw new Error('Run capture through experiments/core-preflight/docker/run.sh with disposable credentials.');
}
const rows = JSON.parse(await readFile('examples/first-trace/target/first-trace.json', 'utf8'));
const root = rows.find(row => row.name === 'java-first-trace');
if (rows.length !== 3 || root?.projectId !== 'langfuse-otel-java-e2e-project'
    || root?.metadata?.demo !== 'synthetic-no-model-call') {
  throw new Error('Expected the verified synthetic first-trace fixture.');
}
const path = `/project/${root.projectId}/traces/${root.traceId}`;
const browser = await chromium.launch({
  executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
  headless: true,
});
try {
  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1,
    ignoreHTTPSErrors: true, // Only this disposable loopback project's self-signed certificate.
    colorScheme: 'light',
  });
  page.setDefaultTimeout(60000);
  await page.goto(`${host}/auth/sign-in?targetPath=${encodeURIComponent(path)}`);
  await page.locator('input[name="email"]').fill('e2e@example.invalid');
  await page.locator('input[name="password"]').fill(process.env.LANGFUSE_E2E_INIT_PASSWORD);
  await page.getByTestId('submit-email-password-sign-in-form').click();
  await page.waitForURL(url => url.pathname === path);
  await page.getByText('lookup-docs', { exact: true }).first().waitFor();
  await page.getByText('answer-question', { exact: true }).first().waitFor();
  await page.getByText('How do I trace a Java application?', { exact: false }).first().waitFor();
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: 'examples/first-trace/target/first-trace.png' });
  console.log('Captured the actual synthetic trace in Langfuse 4.41.0.');
} finally {
  await browser.close();
}
