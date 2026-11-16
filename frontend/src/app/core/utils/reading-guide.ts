import { ReadingBlock, ReadingGuideData } from '../models/reading-guide';

/** Use only terms and contextual spellings already identified by the server in this source. */
export function explainText(text: string, guide: ReadingGuideData | null, documentId: string): ReadingBlock[] {
  if (!guide || guide.documentId !== documentId) return [{id: 'text', text, occurrences: []}];
  const sourceSpellings = guide.blocks.flatMap(b => b.occurrences.map(o => b.text.slice(o.start, o.end).toLocaleLowerCase('pl')));
  const matches: {start: number; end: number; termId: string}[] = [];
  for (const term of guide.terms) {
    for (const alias of new Set([term.name, ...term.aliases])) {
      if (!alias || (term.contextualAliases?.some(a => a.toLocaleLowerCase('pl') === alias.toLocaleLowerCase('pl'))
        && !sourceSpellings.includes(alias.toLocaleLowerCase('pl')))) continue;
      const escaped = alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const pattern = new RegExp(`(?<![\\p{L}\\p{N}_])${escaped}(?![\\p{L}\\p{N}_])`, 'giu');
      for (const match of text.matchAll(pattern)) matches.push({start: match.index, end: match.index + match[0].length, termId: term.id});
    }
  }
  matches.sort((a, b) => (b.end - b.start) - (a.end - a.start) || a.start - b.start);
  const accepted: typeof matches = [];
  for (const match of matches) if (!accepted.some(a => match.start < a.end && match.end > a.start)) accepted.push(match);
  const seen = new Set<string>();
  const occurrences = accepted.sort((a, b) => a.start - b.start).filter(o => {
    if (seen.has(o.termId)) return false; seen.add(o.termId); return true;
  });
  return [{id: 'text', text, occurrences}];
}
