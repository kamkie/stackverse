export interface RenderedPage {
  html: string;
  publish?: () => void;
}

export function renderedPage(html: string, publish?: () => void): RenderedPage {
  return publish ? { html, publish } : { html };
}
