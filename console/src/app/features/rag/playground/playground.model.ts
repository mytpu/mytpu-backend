export interface CandidateItem {
  place: number;
  sectionId: string;
  chunkId: number;
  title: string;
  path: string;
  similarity: number | null;
  inText: boolean;
  rrf: number;
}

export interface PlaygroundThresholds {
  minSimilarity: number;
  softSimilarity: number;
  minMargin: number;
}

export interface PlaygroundAnswer {
  found: boolean;
  reason: string;
  bestSimilarity: number | null;
  margin: number;
  thresholds: PlaygroundThresholds;
  candidates: CandidateItem[];
}

export interface PlaygroundProbe {
  query: string;
  access?: string;
}
