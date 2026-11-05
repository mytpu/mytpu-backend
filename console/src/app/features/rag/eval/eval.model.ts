export interface EvalQuestionItem {
  id?: number;
  question: string;
  expectedUrls: string[];
  hasAnswer: boolean;
  note?: string | null;
}

export interface EvalRunItem {
  id: number;
  at: string;
  questions: number;
  hit3: number;
  hit5: number;
  falseFound: number;
}
