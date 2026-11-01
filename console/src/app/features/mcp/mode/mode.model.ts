export interface ModeView {
  mode: string;
  reason: string;
  updatedAt: string;
}

export interface ModeChange {
  mode: string;
  reason: string;
}

export interface ModeHistoryItem {
  id: number;
  at: string;
  actorId: string;
  actorOwner: string;
  mode: string;
  reason: string;
}
