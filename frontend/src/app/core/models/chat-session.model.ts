/**
 * DTO backend pour l'historique des conversations persistées (Chat/ChatHistory), consommé par la
 * page Assistant T24 (/assistant, ChatbotPageComponent) via `ChatbotService`.
 */
export interface Chat {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  userId: number;
  /** Nombre de messages (USER + ASSISTANT), pour l'affichage dans la liste des conversations. */
  messageCount: number;
  /** Contexte T24 choisi via le parcours à bulles de /assistant (module/table/champ), absents tant
   * qu'aucune sélection n'a été confirmée (voir ChatContextUpdateDTO côté backend). */
  selectedModule?: string;
  selectedTable?: string;
  selectedField?: string;
}

/** Corps de PUT /api/chats/{id}/context (backend/dto/ChatContextUpdateDTO). */
export interface ChatContextUpdate {
  module: string;
  table?: string;
  field?: string;
}

export type ChatMessageType = 'USER' | 'ASSISTANT';

export interface ChatHistory {
  id: number;
  content: string;
  messageType: ChatMessageType;
  createdAt: string;
  chatId: number;
}

/** ChatAskRequest/ChatAskResponse (backend/dto) : POST /api/chat/ask, le chatbot RAG. */
export interface ChatAskRequest {
  question: string;
  /** Conversation active (optionnel) : l'échange y est enregistré, et pas dans la plus récemment modifiée. */
  chatId?: number;
}

export interface ChatAskResponse {
  answer: string;
  /** true si le JWT était absent/invalide/expiré : l'historique n'a pas été enregistré. */
  authenticationRequired: boolean;
  /** Id ChatHistory de la question/réponse persistées (absents si non authentifié ou échec de
   * persistance) — permet de proposer modifier/supprimer sur ce message précis. */
  userMessageId?: number;
  assistantMessageId?: number;
}
