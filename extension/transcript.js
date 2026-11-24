const cueBoundary = /\n\n(?=[1-9]\d*\n\d{2,}:\d{2}:\d{2},\d{3} --> )/;
const cueHeader = /^[1-9]\d*\n(\d{2,}:\d{2}:\d{2},\d{3}) --> \d{2,}:\d{2}:\d{2},\d{3}\n/;

export function formatTranscript(text, srt, timestamps) {
  if (!timestamps) return text;
  if (typeof srt !== "string" || !srt.trim()) return null;

  const cues = srt.replace(/\r\n/g, "\n").trim().split(cueBoundary);
  const lines = [];
  const bodies = [];
  for (const cue of cues) {
    const header = cue.match(cueHeader);
    if (!header) return null;
    const content = cue.slice(header[0].length);
    if (!content) return null;
    bodies.push(content);
    lines.push(`[${header[1].replace(",", ".")}] ${content}`);
  }
  if (bodies.join("\n") !== text.replace(/\r\n/g, "\n")) return null;
  return lines.join("\n");
}
